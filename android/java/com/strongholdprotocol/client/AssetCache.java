package com.strongholdprotocol.client;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves the game's media payload, and keeps it up to date.
 *
 * <p>Two layers sit on top of each other:
 *
 * <ol>
 *   <li><b>The bundle</b> — the ~269 MiB of textures, Spine models, sound effects and webfonts packed
 *       into the APK at build time. A fresh install plays without downloading anything.</li>
 *   <li><b>The overlay</b> — files that changed since the bundle was built, kept in app-private
 *       storage and preferred over the bundle. Almost always tiny.</li>
 * </ol>
 *
 * <p>Updates are incremental by construction: the bundle carries the ETag of every file it shipped
 * (see {@code baseline/etags.tsv}), so an update asks the server "has this changed?" with
 * {@code If-None-Match} and the server answers 304 for the untouched majority. Only genuinely changed
 * files — plus any the server added — come down the wire.
 *
 * <p>The page itself (HTML/JS/CSS), {@code data/*.json} and the multiplayer socket are never bundled:
 * they come from the game server on every launch, so the client and the protocol stay in step with
 * whatever that server runs.
 */
public final class AssetCache {

    private static final String TAG = "AssetCache";
    private static final String MANIFEST_PATH = "/data/assets.json";
    private static final String META_FILE = "asset-meta.tsv";

    // Where the build packed the baseline, relative to the APK's assets/ root.
    private static final String BUNDLE_PREFIX = "mirror";
    private static final String BUNDLE_INDEX = "baseline/index.txt";
    private static final String BUNDLE_ETAGS = "baseline/etags.tsv";
    private static final String BUNDLE_INFO = "baseline/info.txt";

    private static final int THREADS = 6;
    private static final int RETRIES = 3;

    /**
     * Libraries the client imports that the asset manifest does not list (tools/vendor.mjs copies them
     * out of node_modules into public/vendor). A 404 is not an error here, so a server without the 3D
     * board still syncs cleanly.
     */
    private static final String[] VENDOR = {
        "/vendor/preact.module.js",
        "/vendor/hooks.module.js",
        "/vendor/htm.module.js",
        "/vendor/pixi.min.js",
        "/vendor/pixi-spine.js",
        "/vendor/three.core.js",
        "/vendor/three.module.js",
    };

    /**
     * Extensions tried when resolving an extension-less {@code /media/...} audio request. Must stay in
     * step with AUDIO_EXTS in the game's shared/media.js — the server only resolves these.
     */
    public static final String[] AUDIO_EXTS = {".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav"};

    public interface Listener {
        /** @param done files finished @param total files this run will look at @param detail short status line */
        void onProgress(int done, int total, String detail);
        void onDone(boolean ok, String message);
    }

    /** What the update host is serving, compared against what this install already has. */
    public static final class Version {
        public final boolean reachable;
        public final String remoteHash;
        public final String currentHash;
        public final String error;

        Version(boolean reachable, String remoteHash, String currentHash, String error) {
            this.reachable = reachable;
            this.remoteHash = remoteHash;
            this.currentHash = currentHash;
            this.error = error;
        }

        public boolean hasUpdate() {
            return reachable && remoteHash != null && !remoteHash.equals(currentHash);
        }
    }

    public interface VersionListener {
        void onVersion(Version v);
    }

    public static final class Stats {
        public final int files;
        public final long bytes;
        public Stats(int files, long bytes) { this.files = files; this.bytes = bytes; }
    }

    /** An open stream over one asset, from the overlay or from the bundle. */
    public static final class Source {
        public final InputStream stream;
        public final long length;
        Source(InputStream stream, long length) { this.stream = stream; this.length = length; }
    }

    private final Context app;
    private final AssetManager assets;
    private final File root;
    private final File metaFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, String> overlayEtags = new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, String> bundledEtags = new ConcurrentHashMap<String, String>();
    private final AtomicLong transferred = new AtomicLong(0);

    private volatile Set<String> bundledIndex;
    private volatile boolean bundleRead;
    private volatile String bundledHash = "";
    private volatile int bundledFiles = -1;
    private volatile long bundledBytes = -1;

    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile Listener listener;
    private volatile int lastDone;
    private volatile int lastTotal;
    private volatile String lastCurrent = "";

    private PowerManager.WakeLock wake;

    public AssetCache(Context c) {
        this.app = c.getApplicationContext();
        this.assets = this.app.getAssets();
        this.root = new File(this.app.getFilesDir(), "mirror");
        this.metaFile = new File(this.app.getFilesDir(), META_FILE);
    }

    public File root() { return root; }

    // ---- path mapping -----------------------------------------------------------------------------

    /** Maps a server path such as {@code /assets/ui/x.png} onto its file in the overlay. */
    public File fileFor(String urlPath) {
        String p = strip(urlPath);
        if (p == null) return null;
        return new File(root, p.startsWith("/") ? p.substring(1) : p);
    }

    private static String strip(String urlPath) {
        if (urlPath == null) return null;
        String p = urlPath;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        if (p.isEmpty() || p.contains("..")) return null;
        return p;
    }

    // ---- availability -----------------------------------------------------------------------------

    /** True when the APK itself carries this path. */
    public boolean isBundled(String path) {
        String p = strip(path);
        return p != null && bundledIndex().contains(p);
    }

    /** True when an update has written this path over the bundled copy. */
    public boolean hasOverlay(String path) {
        File f = fileFor(path);
        return f != null && f.isFile() && f.length() > 0;
    }

    public boolean isAvailable(String path) {
        return hasOverlay(path) || isBundled(path);
    }

    /**
     * Opens a file that ships inside the APK but is not part of the media mirror — currently only the
     * bundled webfonts under {@code localfonts/}. Not routed through the overlay/etag machinery, because
     * these are not syncable game assets: they belong to this shell.
     *
     * @return a stream the caller must close, or null when the file is missing
     */
    public Source openBundled(String assetPath) {
        try {
            return new Source(new BufferedInputStream(assets.open(assetPath), 32 * 1024), -1);
        } catch (Exception e) {
            Log.w(TAG, "missing bundled asset " + assetPath, e);
            return null;
        }
    }

    /**
     * Opens one asset: the overlay wins over the bundle.
     *
     * @return a stream the caller must close, or null when neither layer has the file
     */
    public Source open(String path) {
        String p = strip(path);
        if (p == null) return null;

        File f = fileFor(p);
        if (f != null && f.isFile() && f.length() > 0) {
            try {
                return new Source(new FileInputStream(f), f.length());
            } catch (Exception e) {
                Log.w(TAG, "overlay open failed for " + p, e);
            }
        }
        if (!bundledIndex().contains(p)) return null;
        try {
            return new Source(assets.open(BUNDLE_PREFIX + p), -1);
        } catch (Exception e) {
            Log.w(TAG, "bundled open failed for " + p, e);
            return null;
        }
    }

    // ---- stats / version --------------------------------------------------------------------------

    /** Files the overlay has replaced since the bundle was built. */
    public Stats overlayStats() {
        int[] n = {0};
        long[] b = {0};
        walk(root, n, b);
        return new Stats(n[0], b[0]);
    }

    public int bundledCount() {
        readBundle();
        return bundledIndex().size();
    }

    public long bundledBytes() {
        readBundle();
        return bundledBytes > 0 ? bundledBytes : 0L;
    }

    public String bundledHash() {
        readBundle();
        return bundledHash;
    }

    /** The payload version this install is currently on: an applied update, else the bundle's. */
    public String currentHash() {
        String h = Prefs.manifestHash(app);
        return (h == null || h.isEmpty()) ? bundledHash() : h;
    }

    /** "1:375e961f…" -> "375e961f"; keeps status lines readable. */
    public static String shortHash(String h) {
        if (h == null || h.isEmpty()) return "—";
        String s = h;
        int c = s.indexOf(':');
        if (c >= 0) s = s.substring(c + 1);
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    /**
     * Asks the update host which payload version it serves. One request — no files transfer.
     *
     * @param updateBase base URL (no trailing slash)
     */
    public void checkVersion(final String updateBase, final VersionListener cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Version v = probeVersion(updateBase);
                main.post(new Runnable() {
                    @Override public void run() { cb.onVersion(v); }
                });
            }
        }, "version-check").start();
    }

    /** One manifest request. Never throws — unreachable hosts come back as an unreachable Version. */
    private Version probeVersion(String updateBase) {
        try {
            String body = httpGetString(updateBase + MANIFEST_PATH);
            JSONObject json = new JSONObject(body);
            return new Version(true, json.opt("version") + ":" + json.opt("hash"), currentHash(), null);
        } catch (Exception e) {
            Log.i(TAG, "version check failed: " + e);
            return new Version(false, null, currentHash(), String.valueOf(e.getMessage()));
        }
    }

    // ---- updating ---------------------------------------------------------------------------------

    public boolean isRunning() { return running; }
    public int lastDone() { return lastDone; }
    public int lastTotal() { return lastTotal; }
    public String lastCurrent() { return lastCurrent; }

    public void setListener(Listener l) { this.listener = l; }
    public void clearListener(Listener l) { if (this.listener == l) this.listener = null; }

    public void cancel() { cancelled = true; }

    /** Brings the overlay up to date with the update host, trusting the manifest hash. */
    public void startSync(final String updateBase, final Listener l) {
        startSync(updateBase, l, false);
    }

    /**
     * @param full revalidate every known path instead of trusting the manifest hash.
     *
     * <p>Needed because the upstream manifest's {@code hash} is computed over the manifest body —
     * paths and metadata — and <b>not</b> over the asset bytes (see {@code tools/fetch-assets.mjs}:
     * {@code hash: contentHash(body)}). So a texture that is re-rendered without any structural change
     * leaves the hash identical, the incremental path answers "already up to date", and that artwork is
     * never fetched. A full sweep asks about every path regardless, which costs a few thousand
     * conditional requests but cannot miss a content-only change.
     */
    public void startSync(final String updateBase, final Listener l, final boolean full) {
        this.listener = l;
        if (running) {
            main.post(new Runnable() {
                @Override public void run() {
                    if (listener != null) listener.onProgress(lastDone, lastTotal, "更新已在进行中…");
                }
            });
            return;
        }
        running = true;
        cancelled = false;
        new Thread(new Runnable() {
            @Override public void run() { sync(updateBase, full); }
        }, "asset-sync").start();
    }

    /**
     * The stage/level data under /data/ -- stages.json, chess.json, enemies.json and friends.
     *
     * <p>Deliberately driven by the list inside this APK rather than a hard-coded array: whatever the
     * build shipped is exactly what a sync should keep current, so adding a data file upstream needs
     * no change here.
     */
    private List<String> dataPaths() {
        List<String> out = new ArrayList<String>();
        try {
            String[] names = assets.list("gsrv/data");
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) if (n.endsWith(".json")) out.add("/data/" + n);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot list the bundled data/ directory", e);
        }
        return out;
    }

    private static boolean isLoopback(String base) {
        if (base == null) return false;
        String b = base.toLowerCase(java.util.Locale.US);
        return b.contains("//127.0.0.1") || b.contains("//localhost") || b.contains("//[::1]");
    }

    private void sync(String updateBase, final boolean full) {
        final String base = updateBase;
        acquireWake();
        try {
            // Pointing the updater at this phone's own server compares the device with itself: every
            // file is already here, so the sweep would cost a full round of requests and change
            // nothing. Say so instead of doing the work.
            LocalServer own = App.localServerOf(app);
            if (own.isRunning() && isLoopback(base)) {
                finish(true, "更新地址是本机服务器，素材就在本机，已跳过校对");
                return;
            }

            final LinkedHashSet<String> wanted = new LinkedHashSet<String>();
            String body = httpGetString(base + MANIFEST_PATH);
            JSONObject json = new JSONObject(body);
            String remoteHash = json.opt("version") + ":" + json.opt("hash");
            String current = currentHash();
            final boolean manifestChanged = full || !remoteHash.equals(current);

            if (manifestChanged) {
                collect(json, wanted);
                for (String v : VENDOR) wanted.add(v);
            }

            readBundle();
            loadMeta();

            final List<String> jobs = new ArrayList<String>();
            // Stage/level data is revalidated on every sync, and deliberately *not* gated on the
            // manifest hash: /data/assets.json indexes artwork only, so a rebalance that rewrites
            // stages.json or chess.json leaves the manifest -- and the hash -- untouched, and these
            // files would otherwise never be noticed. Eighteen small conditional requests; the ones
            // that did not change answer 304 with no body.
            jobs.addAll(dataPaths());
            if (manifestChanged) {
                // Every art path gets checked, not just the missing ones: a file that exists locally
                // may still be the older one the bundle shipped, and the ETag tells the two apart.
                jobs.addAll(wanted);
            }
            Log.i(TAG, "update " + shortHash(current) + " -> " + shortHash(remoteHash)
                    + (full ? " (full sweep, hash ignored)"
                            : manifestChanged ? "" : " (manifest unchanged, data only)")
                    + ", " + jobs.size() + " paths to check");

            final int total = jobs.size();
            final AtomicInteger done = new AtomicInteger(0);
            final AtomicInteger failed = new AtomicInteger(0);
            final AtomicInteger fetched = new AtomicInteger(0);
            final AtomicInteger unchanged = new AtomicInteger(0);
            transferred.set(0);

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            for (final String path : jobs) {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        if (cancelled) return;
                        String tag = isAvailable(path) ? etagFor(path) : null;
                        int r = fetch(base, path, tag);
                        if (r < 0) failed.incrementAndGet();
                        else if (r == 0) unchanged.incrementAndGet();
                        else if (r == 1) fetched.incrementAndGet();

                        int d = done.incrementAndGet();
                        lastDone = d;
                        lastTotal = total;
                        lastCurrent = path;
                        if (d == total || d % 25 == 0) {
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (listener == null) return;
                                    String detail = String.format(java.util.Locale.US,
                                        "已下载 %.1f MB · 更新 %d · 未变 %d%s",
                                        transferred.get() / 1048576.0, fetched.get(), unchanged.get(),
                                        failed.get() > 0 ? " · 失败 " + failed.get() : "");
                                    listener.onProgress(done.get(), total, detail);
                                }
                            });
                        }
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(12, TimeUnit.HOURS);
            saveMeta();

            if (cancelled) { finish(false, "已取消"); return; }

            if (failed.get() == 0) {
                Prefs.setManifestHash(app, remoteHash);
                Prefs.setLastSyncAt(app, System.currentTimeMillis());
                finish(true, String.format(java.util.Locale.US,
                    "资源更新完成：%d 个文件，%.1f MB%s", fetched.get(), transferred.get() / 1048576.0,
                    full ? "（完整核对）"
                         : manifestChanged ? "" : "（素材清单未变，只核对了关卡数据）"));
            } else {
                finish(false, "有 " + failed.get() + " / " + total + " 个文件失败，可重试");
            }
        } catch (Exception e) {
            Log.w(TAG, "sync failed", e);
            finish(false, "更新失败：" + e.getMessage());
        }
    }

    /** Drops the overlay, going back to exactly what the APK shipped with. */
    public void clearOverlay() {
        deleteRecursive(root);
        overlayEtags.clear();
        saveMeta();
        Prefs.setManifestHash(app, "");
        Prefs.setLastSyncAt(app, 0L);
    }

    private String etagFor(String path) {
        String t = overlayEtags.get(path);
        return t != null ? t : bundledEtags.get(path);
    }

    private void finish(final boolean ok, final String msg) {
        running = false;
        releaseWake();
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onDone(ok, msg);
            }
        });
    }

    /** Recursively pulls every server path out of the manifest, wherever it sits in the tree. */
    private static void collect(Object node, Set<String> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            while (it.hasNext()) collect(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
        } else if (node instanceof String) {
            String s = (String) node;
            if (s.startsWith("/assets/") || s.startsWith("/fonts/")) out.add(s);
        }
    }

    /**
     * Fetches one file.
     *
     * @param ifNoneMatch ETag to revalidate with, or null to always transfer
     * @return 1 transferred, 0 still valid (304), 2 absent upstream (404), -1 failed
     */
    private int fetch(String base, String path, String ifNoneMatch) {
        File target = fileFor(path);
        if (target == null) return -1;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return -1;

        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            File part = new File(target.getPath() + ".part");
            try {
                conn = openConnection(base + path);
                if (ifNoneMatch != null) conn.setRequestProperty("If-None-Match", ifNoneMatch);
                int code = conn.getResponseCode();
                if (code == 304) return 0;
                if (code == 404) { Log.i(TAG, "skip (404) " + path); return 2; }
                if (code != 200) throw new IllegalStateException("HTTP " + code);

                String tag = conn.getHeaderField("ETag");
                in = conn.getInputStream();
                out = new FileOutputStream(part);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    transferred.addAndGet(n);
                }
                out.flush();
                out.close();
                out = null;
                in.close();
                in = null;

                if (target.exists()) target.delete();
                if (!part.renameTo(target)) throw new IllegalStateException("rename failed");
                if (tag != null) overlayEtags.put(path, tag);
                return 1;
            } catch (Exception e) {
                if (cancelled) return -1;
                Log.i(TAG, "attempt " + attempt + " failed for " + path + ": " + e);
                if (attempt == RETRIES) return -1;
                try { Thread.sleep(400L * attempt); } catch (InterruptedException ie) { return -1; }
            } finally {
                closeQuietly(out);
                closeQuietly(in);
                if (conn != null) conn.disconnect();
            }
        }
        return -1;
    }

    private String httpGetString(String url) throws Exception {
        HttpURLConnection conn = openConnection(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code + " for " + url);
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "StrongholdProtocol-Android/1.1");
        // Never transparently gunzip: asset bytes must land on disk exactly as the server sent them.
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    // ---- bundle + overlay bookkeeping -------------------------------------------------------------

    private Set<String> bundledIndex() {
        readBundle();
        Set<String> idx = bundledIndex;
        return idx == null ? new HashSet<String>() : idx;
    }

    /** Reads baseline/index.txt, baseline/etags.tsv and baseline/info.txt once, on first use. */
    private void readBundle() {
        if (bundleRead) return;
        synchronized (this) {
            if (bundleRead) return;
            Set<String> idx = new HashSet<String>();
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(assets.open(BUNDLE_INDEX), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) idx.add(line);
                }
            } catch (Exception e) {
                Log.w(TAG, "no bundled index (asset bundle missing?)", e);
            } finally {
                closeQuietly(r);
            }
            bundledIndex = idx;

            r = null;
            try {
                r = new BufferedReader(new InputStreamReader(assets.open(BUNDLE_ETAGS), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t > 0) bundledEtags.put(line.substring(0, t), line.substring(t + 1));
                }
            } catch (Exception e) {
                Log.w(TAG, "no bundled etags", e);
            } finally {
                closeQuietly(r);
            }

            r = null;
            try {
                r = new BufferedReader(new InputStreamReader(assets.open(BUNDLE_INFO), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('=');
                    if (t <= 0) continue;
                    String k = line.substring(0, t);
                    String v = line.substring(t + 1).trim();
                    if ("hash".equals(k)) bundledHash = v;
                    else if ("files".equals(k)) { try { bundledFiles = Integer.parseInt(v); } catch (Exception ignored) { } }
                    else if ("bytes".equals(k)) { try { bundledBytes = Long.parseLong(v); } catch (Exception ignored) { } }
                }
            } catch (Exception e) {
                Log.w(TAG, "no bundled info", e);
            } finally {
                closeQuietly(r);
            }

            bundleRead = true;
            Log.i(TAG, "bundle: " + idx.size() + " files, hash " + shortHash(bundledHash)
                    + ", " + bundledFiles + " files reported");
        }
    }

    private void loadMeta() {
        overlayEtags.clear();
        if (!metaFile.isFile()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(metaFile), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t > 0) overlayEtags.put(line.substring(0, t), line.substring(t + 1));
            }
        } catch (Exception e) {
            Log.w(TAG, "meta read failed", e);
        } finally {
            closeQuietly(r);
        }
    }

    private void saveMeta() {
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(metaFile), "UTF-8"));
            for (Map.Entry<String, String> e : overlayEtags.entrySet()) {
                w.write(e.getKey());
                w.write('\t');
                w.write(e.getValue());
                w.write('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "meta write failed", e);
        } finally {
            closeQuietly(w);
        }
    }

    private static void walk(File dir, int[] n, long[] b) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) walk(f, n, b);
            else if (f.isFile() && !f.getName().endsWith(".part")) { n[0]++; b[0] += f.length(); }
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

    private void acquireWake() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stronghold:sync");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(12 * 60 * 60 * 1000L);
        } catch (Throwable ignored) { }
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) { }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) { }
    }
}
