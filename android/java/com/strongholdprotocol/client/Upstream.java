package com.strongholdprotocol.client;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Talks to the upstream project on GitHub: which versions exist, and pulling one down to run locally.
 *
 * <p>Two transport choices, both deliberate:
 *
 * <ul>
 *   <li><b>The releases Atom feed, not the REST API.</b> The REST API allows 60 unauthenticated
 *       requests per hour per IP, and that budget is shared with every other caller behind the same
 *       address — a version list built on it fails unpredictably for reasons the player cannot see.
 *       The feed is a plain 48 KiB document with no such limit.</li>
 *   <li><b>The source zip, not the release bundle.</b> The published bundle is ~290 MiB because it
 *       carries the game's artwork; the git repository itself is ~7 MiB, because the artwork is
 *       deliberately not committed. This client already ships that artwork, so all it needs from a
 *       version is its code.</li>
 * </ul>
 *
 * <p>An installed version lands in {@code filesDir/localsrv/versions/<tag>/} and becomes what the
 * on-phone server runs; {@link Prefs#activeGsrv} records the choice.
 */
public final class Upstream {

    private static final String TAG = "Upstream";

    public static final String REPO = "sganggs/Stronghold-Protocol";
    public static final String PAGE = "https://github.com/" + REPO;
    private static final String ATOM = "https://github.com/" + REPO + "/releases.atom";
    private static final String ZIP = "https://codeload.github.com/" + REPO + "/zip/refs/tags/";

    private static final Pattern ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern UPDATED = Pattern.compile("<updated>(.*?)</updated>", Pattern.DOTALL);
    private static final Pattern TAGLINK = Pattern.compile("href=\"[^\"]*/releases/tag/([^\"]+)\"");

    /** The parts of the tree the server actually needs; the rest of the zip is skipped. */
    private static final String[] KEEP = {"server/", "shared/", "data/", "public/"};
    private static final String[] SKIP = {"public/assets/", "public/fonts/", "public/dev/"};

    public static final class Release {
        public final String tag;
        public final String title;
        public final String date;

        Release(String tag, String title, String date) {
            this.tag = tag;
            this.title = title;
            this.date = date;
        }

        public String describe() {
            return tag + (date == null || date.isEmpty() ? "" : "　·　" + date);
        }
    }

    public interface ListListener {
        void onList(boolean ok, List<Release> list, String error);
    }

    public interface InstallListener {
        void onProgress(int done, int total, String detail);
        void onDone(boolean ok, String message);
    }

    private Upstream() {}

    // ---- listing ----------------------------------------------------------------------------------

    public static void list(final ListListener l) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String xml = getText(ATOM);
                    List<Release> out = new ArrayList<Release>();
                    Matcher m = ENTRY.matcher(xml);
                    while (m.find()) {
                        String body = m.group(1);
                        String tag = firstGroup(TAGLINK, body);
                        if (tag == null) continue;
                        String title = firstGroup(TITLE, body);
                        String date = firstGroup(UPDATED, body);
                        out.add(new Release(tag, title == null ? tag : unescape(title),
                            date == null ? "" : (date.length() > 10 ? date.substring(0, 10) : date)));
                    }
                    Log.i(TAG, "upstream releases: " + out.size());
                    l.onList(true, out, null);
                } catch (Exception e) {
                    Log.w(TAG, "release list failed", e);
                    l.onList(false, null, describe(e));
                }
            }
        }, "upstream-list").start();
    }

    // ---- installing -------------------------------------------------------------------------------

    /** Downloads the source zip for {@code tag} and unpacks the server tree into place. */
    public static void install(final Context context, final String tag, final InstallListener l) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                File stage = new File(versionsDir(app), ".stage-" + safe(tag));
                File zip = new File(app.getCacheDir(), "upstream-" + safe(tag) + ".zip");
                try {
                    deleteRecursive(stage);
                    if (!stage.mkdirs()) throw new IOException("cannot create " + stage);

                    long total = fetch(ZIP + tag, zip);
                    l.onProgress(0, 1, String.format(java.util.Locale.US, "已下载 %.1f MB，解包中…",
                        total / 1048576.0));

                    int files = extract(zip, stage, l);
                    File target = new File(versionsDir(app), safe(tag));
                    deleteRecursive(target);
                    if (!stage.renameTo(target)) throw new IOException("cannot move into place");
                    zip.delete();

                    Prefs.setActiveGsrv(app, tag);
                    l.onDone(true, "已切换到 " + tag + "（" + files + " 个文件）");
                } catch (Exception e) {
                    deleteRecursive(stage);
                    zip.delete();
                    Log.w(TAG, "install " + tag + " failed", e);
                    l.onDone(false, "切换失败：" + describe(e));
                }
            }
        }, "upstream-install").start();
    }

    /** @return how many files were written */
    private static int extract(File zip, File dest, InstallListener l) throws IOException {
        ZipFile zf = new ZipFile(zip);
        try {
            List<ZipEntry> wanted = new ArrayList<ZipEntry>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (relative(e.getName()) != null) wanted.add(e);
            }
            if (wanted.isEmpty()) throw new IOException("zip 里没有服务端代码（格式变了？）");

            int done = 0;
            for (ZipEntry e : wanted) {
                String rel = relative(e.getName());
                File out = new File(dest, rel);
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                InputStream in = new BufferedInputStream(zf.getInputStream(e), 64 * 1024);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    try { fos.close(); } catch (Exception ignored) { }
                    try { in.close(); } catch (Exception ignored) { }
                }
                done++;
                if (done % 10 == 0 || done == wanted.size()) {
                    l.onProgress(done, wanted.size(), "解包 " + done + " / " + wanted.size());
                }
            }
            return done;
        } finally {
            try { zf.close(); } catch (Exception ignored) { }
        }
    }

    /** @return the path inside the zip that we want, or null when this entry is not ours */
    private static String relative(String entryName) {
        // GitHub zips wrap everything in "<repo>-<tag>/".
        int slash = entryName.indexOf('/');
        if (slash < 0) return null;
        String rel = entryName.substring(slash + 1);
        for (String s : SKIP) if (rel.startsWith(s)) return null;
        for (String k : KEEP) if (rel.startsWith(k)) return rel;
        return null;
    }

    // ---- version bookkeeping ----------------------------------------------------------------------

    public static File versionsDir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "localsrv/versions");
    }

    public static boolean isInstalled(Context c, String tag) {
        return new File(new File(versionsDir(c), safe(tag)), "server/index.js").isFile();
    }

    public static void remove(Context c, String tag) {
        deleteRecursive(new File(versionsDir(c), safe(tag)));
        if (tag.equals(Prefs.activeGsrv(c))) Prefs.setActiveGsrv(c, "");
    }

    /** The tag this APK was built against, stamped into assets/gsrv/.version by the build. */
    public static String embeddedVersion(Context c) {
        InputStream in = null;
        try {
            in = c.getAssets().open("gsrv/.version");
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** The tag the on-phone server is actually running right now. */
    public static String activeVersion(Context c) {
        String t = Prefs.activeGsrv(c);
        if (t != null && !t.isEmpty() && isInstalled(c, t)) return t;
        return embeddedVersion(c);
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private static String safe(String tag) {
        return tag.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static long fetch(String url, File dest) throws IOException {
        HttpURLConnection conn = open(url);
        InputStream in = null;
        FileOutputStream out = null;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long len = conn.getContentLength();
            in = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[128 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            if (len > 0 && total != len) throw new IOException("传输不完整（" + total + "/" + len + "）");
            return total;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            conn.disconnect();
        }
    }

    public static String getText(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "StrongholdProtocol-Android/1.9 (+" + PAGE + ")");
            conn.setRequestProperty("Accept-Encoding", "identity");
            return conn;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    public static String describe(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "域名解析失败（DNS 或网络受限）";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        if (e instanceof java.net.ConnectException) return "拒绝连接（网络被阻断？）";
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : "：" + m);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
