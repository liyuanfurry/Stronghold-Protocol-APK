package com.strongholdprotocol.client;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Refills the artwork from an upstream release bundle.
 *
 * <p>The media update path elsewhere in this app talks to a game server and asks it, file by file,
 * "has this changed?" (see {@link AssetCache#startSync}). That works only when somebody is serving
 * the game. Upstream is not: the artwork is deliberately not committed to git, so it exists only
 * inside the release bundle — a zip of a few hundred megabytes. This class is the way to get art
 * from GitHub with no server involved at all.
 *
 * <p>Two details matter for correctness:
 *
 * <ul>
 *   <li>Everything is unpacked into a staging directory and only moved into the overlay once the
 *       whole archive has been read. Unpacking straight into the overlay would, if it failed
 *       halfway, leave new artwork layered over old artwork — a mixture no version ever shipped.</li>
 *   <li>The overlay is where files go, never the bundle: files in the icon-packed bundle are
 *       read-only, and the overlay already wins over it (see {@link AssetCache#open}).</li>
 * </ul>
 *
 * <p>One caveat worth stating: imported files carry no server ETag, so a later
 * {@code 在线更新资源} re-downloads them once before settling down. Content is already correct; only
 * the bookkeeping has to catch up.
 */
public final class BundleImport {

    private static final String TAG = "BundleImport";

    /** Paths inside the zip we want, and where each belongs in the overlay. */
    private static final String[][] TREES = {
        {"public/assets/", "assets/"},
        {"public/fonts/", "fonts/"},
    };

    private static final Pattern API_ZIP =
        Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+?\\.zip)\"");

    public interface Listener {
        /** percent is 0-100, or negative when the total is not yet known. */
        void onProgress(int percent, String detail);
        void onDone(boolean ok, String message);
    }

    private BundleImport() {}

    /** @param version the release tag to pull, e.g. {@code v0.2.2} */
    public static void run(final Context context, final String version, final Listener l) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                File zip = new File(app.getCacheDir(), "upstream-bundle.zip");
                File stage = new File(app.getFilesDir(), "mirror-import");
                try {
                    final String tag = version;
                    if (tag == null || tag.isEmpty()) throw new IOException("没有指定要补哪个版本");

                    File overlay = App.cacheOf(app).root();
                    long need = 900L * 1024 * 1024;        // 整合包本身 + 解出来的素材，留余量
                    if (stage.getUsableSpace() > 0 && stage.getUsableSpace() < need) {
                        throw new IOException("存储空间不足（至少需要 900 MB 可用）");
                    }

                    String url = resolveUrl(tag);
                    l.onProgress(-1, "正在从 GitHub 下载整合包…\n" + url);
                    long got = download(url, zip, l);

                    deleteRecursive(stage);
                    if (!stage.mkdirs()) throw new IOException("无法建立临时目录 " + stage);

                    l.onProgress(-1, String.format(Locale.US, "已下载 %.0f MB，正在解包…", got / 1048576.0));
                    int[] counts = extract(zip, stage, l);
                    zip.delete();

                    long before = System.currentTimeMillis();
                    int moved = promote(stage, overlay);
                    deleteRecursive(stage);
                    Log.i(TAG, "promoted " + moved + " files in " + (System.currentTimeMillis() - before) + " ms");

                    l.onDone(true, "已从整合包补入 " + moved + " 个素材文件（" + tag + "）\n"
                        + "跳过 " + counts[1] + " 个不需要的条目；临时文件已清理。");
                } catch (Exception e) {
                    zip.delete();
                    deleteRecursive(stage);
                    Log.w(TAG, "bundle import failed", e);
                    l.onDone(false, "补齐失败：" + Upstream.describe(e));
                }
            }
        }, "bundle-import").start();
    }

    // ---- getting the archive ----------------------------------------------------------------------

    /**
     * The bundle is named after its tag, but rather than trust that forever, fall back to asking the
     * API which assets the release actually carries.
     */
    private static String resolveUrl(String tag) {
        String guess = "https://github.com/" + Upstream.REPO + "/releases/download/" + tag
            + "/Stronghold-Protocol-" + tag + ".zip";
        if (headOk(guess)) return guess;
        Log.i(TAG, "guessed bundle name 404s, asking the API: " + guess);
        try {
            String json = Upstream.getText(
                "https://api.github.com/repos/" + Upstream.REPO + "/releases/tags/" + tag);
            Matcher m = API_ZIP.matcher(json);
            if (m.find()) return m.group(1).replace("\\/", "/");
        } catch (Exception e) {
            Log.w(TAG, "asset lookup failed", e);
        }
        return guess;
    }

    private static boolean headOk(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "StrongholdProtocol-Android");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long download(String url, File dest, Listener l) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "StrongholdProtocol-Android");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("下载整合包失败：HTTP " + code + "（该版本可能没有整合包）");

            long len = conn.getContentLength();
            in = new BufferedInputStream(conn.getInputStream(), 256 * 1024);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[256 * 1024];
            long total = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                int pct = len > 0 ? (int) (total * 100 / len) : -1;
                if (pct != lastPct) {
                    lastPct = pct;
                    l.onProgress(pct, String.format(Locale.US, "下载整合包 %.0f MB%s",
                        total / 1048576.0,
                        len > 0 ? String.format(Locale.US, " / %.0f MB", len / 1048576.0) : ""));
                }
            }
            if (len > 0 && total != len) {
                throw new IOException("下载不完整（" + total + "/" + len + " 字节），请重试");
            }
            return total;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            conn.disconnect();
        }
    }

    // ---- unpacking ---------------------------------------------------------------------------------

    /** @return {files written, entries skipped} */
    private static int[] extract(File zip, File stage, Listener l) throws IOException {
        ZipFile zf = new ZipFile(zip);
        try {
            // The zip wraps everything in "<repo>-<version>/"; take the prefix from whatever entry
            // comes first rather than guessing it, because upstream strips the leading "v".
            String prefix = null;
            Enumeration<? extends ZipEntry> probe = zf.entries();
            while (probe.hasMoreElements()) {
                ZipEntry e = probe.nextElement();
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash > 0) { prefix = name.substring(0, slash + 1); break; }
            }
            if (prefix == null) throw new IOException("整合包结构无法识别");

            List<ZipEntry> wanted = new ArrayList<ZipEntry>();
            List<String> targets = new ArrayList<String>();
            int skipped = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.startsWith(prefix)) { skipped++; continue; }
                String rel = name.substring(prefix.length());
                String target = null;
                for (String[] t : TREES) {
                    if (rel.startsWith(t[0])) { target = t[1] + rel.substring(t[0].length()); break; }
                }
                if (target == null) { skipped++; continue; }
                wanted.add(e);
                targets.add(target);
            }
            if (wanted.isEmpty()) throw new IOException("整合包里没有 assets/fonts（版本太老？）");

            for (int i = 0; i < wanted.size(); i++) {
                File out = new File(stage, targets.get(i));
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法建立目录 " + parent);
                }
                InputStream in = new BufferedInputStream(zf.getInputStream(wanted.get(i)), 128 * 1024);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    try { fos.close(); } catch (Exception ignored) { }
                    try { in.close(); } catch (Exception ignored) { }
                }
                if (i % 50 == 0 || i == wanted.size() - 1) {
                    l.onProgress(i * 100 / wanted.size(),
                        "解包素材 " + (i + 1) + " / " + wanted.size());
                }
            }
            return new int[]{wanted.size(), skipped};
        } finally {
            try { zf.close(); } catch (Exception ignored) { }
        }
    }

    /** Moves the staged tree into the overlay. A rename within one filesystem, so it is quick. */
    private static int promote(File stage, File overlay) throws IOException {
        int[] moved = {0};
        moveInto(stage, stage, overlay, moved);
        return moved[0];
    }

    private static void moveInto(File base, File dir, File overlay, int[] moved) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                moveInto(base, k, overlay, moved);
                k.delete();
                continue;
            }
            String rel = k.getAbsolutePath().substring(base.getAbsolutePath().length() + 1);
            File target = new File(overlay, rel);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法建立目录 " + parent);
            }
            if (target.exists()) target.delete();
            if (!k.renameTo(target)) throw new IOException("无法移动 " + rel);
            moved[0]++;
        }
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
