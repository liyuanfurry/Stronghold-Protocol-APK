package com.strongholdprotocol.client;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the actual game server on the phone.
 *
 * <p>The APK carries a Bionic Node runtime ({@code assets/node}) plus the server's own JavaScript
 * ({@code assets/gsrv} — server/, shared/, data/, the page, and the {@code ws} module). On first start
 * they are unpacked into app-private storage; after that {@code node server/index.js} is spawned as a
 * child process and the server listens on {@code 0.0.0.0:<port>}, so anyone on the same Wi-Fi can play.
 *
 * <p>Two details are worth knowing:
 *
 * <ul>
 *   <li>The Termux Node build has {@code RUNPATH=/data/data/com.termux/files/usr/lib} baked in, which
 *       does not exist here. {@code LD_LIBRARY_PATH} is set to the unpacked {@code node/lib} to take
 *       precedence over it, and {@code TMPDIR}/{@code HOME} are redirected for the same reason.</li>
 *   <li>{@code LD_PRELOAD} is cleared: inheriting a Termux {@code termux-exec} preload would break the
 *       child before Node ever starts.</li>
 * </ul>
 *
 * <p>Only API-level-21-safe calls are used — {@code Process.isAlive()}, {@code destroyForcibly()} and
 * {@code waitFor(long, TimeUnit)} are all API 26 and would throw on older devices.
 */
public final class LocalServer {

    private static final String TAG = "LocalServer";
    private static final String PAYLOAD_LIST = "payload.txt";
    private static final String STAMP = ".stamp";
    private static final int LOG_KEEP = 300;
    private static final int PORT_CANDIDATES = 4;

    public interface Listener {
        void onUnpack(int done, int total, String current);
        void onLog(String line);
        /** the child exited on its own (crash or shutdown) */
        void onExit(int code);
    }

    /** The runtime ships as lib/arm64-v8a/libnode.so, so the installer puts it here. */
    private static final String NODE_LIB = "libnode.so";

    private final Context app;
    private final File root;
    private final File nodeBin;
    private final File libDir;
    private final File nativeDir;
    private final File gsrvDir;
    private final ArrayDeque<String> log = new ArrayDeque<String>();

    private Process process;
    private volatile boolean ready;
    private volatile int exitCode = Integer.MIN_VALUE;
    private int port = 3000;

    public LocalServer(Context c) {
        this.app = c.getApplicationContext();
        this.root = new File(this.app.getFilesDir(), "localsrv");
        // Executed straight out of the native library directory rather than from app storage: Android
        // refuses to execve() a file in an app's home directory (verified on-device — mode 0700,
        // canExecute() true, still EACCES), while the native library directory is always executable
        // and its mode is set by the installer, so no runtime chmod is involved either.
        this.nativeDir = new File(this.app.getApplicationInfo().nativeLibraryDir);
        this.nodeBin = new File(nativeDir, NODE_LIB);
        this.libDir = nativeDir;
        this.gsrvDir = new File(root, "gsrv");
    }

    public File root() { return root; }

    /**
     * Records a line in the same ring buffer the child's output goes to.
     *
     * <p>Deliberately not {@code Log.w} alone: this app is debugged by a player reading the in-app log
     * dialog, and a message that only reaches logcat is a message nobody sees. (The first build failed
     * with "Permission denied" and the dialog said "(暂无日志)" — that is why.)
     */
    private void note(String line) {
        Log.i(TAG, line);
        synchronized (log) {
            log.addLast(line);
            while (log.size() > LOG_KEEP) log.removeFirst();
        }
    }

    public boolean isRunning() {
        Process p = process;
        if (p == null) return false;
        try {
            p.exitValue();
            return false;                       // finished
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    public boolean isReady() { return ready; }
    public int port() { return port; }
    public String url() { return "http://127.0.0.1:" + port; }
    public int exitCode() { return exitCode; }

    public List<String> recentLog() {
        synchronized (log) { return new ArrayList<String>(log); }
    }

    /** Last {@code maxLines} lines only: the whole ring buffer is far too long for a dialog. */
    public String recentLogText(int maxLines) {
        StringBuilder sb = new StringBuilder();
        synchronized (log) {
            int skip = Math.max(0, log.size() - maxLines);
            int i = 0;
            for (String s : log) {
                if (i++ >= skip) sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    public String recentLogText() {
        StringBuilder sb = new StringBuilder();
        synchronized (log) {
            for (String s : log) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    // ---- unpacking --------------------------------------------------------------------------------

    /** How many files the payload has. Also the cache key: change the payload, re-unpack. */
    public int payloadCount() {
        try {
            BufferedReader r = new BufferedReader(
                new InputStreamReader(app.getAssets().open(PAYLOAD_LIST), "UTF-8"));
            try {
                int n = 0;
                while (r.readLine() != null) n++;
                return n;
            } finally {
                r.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "no payload list", e);
            return 0;
        }
    }

    /**
     * Unpacks {@code assets/payload.txt} into app-private storage, skipping the work when the stamp
     * still matches. Safe to call repeatedly; a partial run is simply redone from scratch (the whole
     * tree, not a merge, so a payload that shrank cannot leave stale files behind).
     */
    public synchronized void unpack(Listener l) throws Exception {
        String stamp = versionName() + ":" + payloadCount();
        File stampFile = new File(root, STAMP);
        if (stampFile.isFile() && stamp.equals(readText(stampFile).trim())) {
            Log.i(TAG, "payload already unpacked");
            return;
        }

        Log.i(TAG, "unpacking payload to " + root);
        deleteRecursive(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new Exception("cannot create " + root);

        AssetManager am = app.getAssets();
        BufferedReader r = new BufferedReader(new InputStreamReader(am.open(PAYLOAD_LIST), "UTF-8"));
        List<String[]> rows = new ArrayList<String[]>();
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                rows.add(new String[]{line.substring(0, t), line.substring(t + 1).trim()});
            }
        } finally {
            r.close();
        }

        int done = 0;
        for (String[] row : rows) {
            String rel = row[0];
            File out = new File(root, rel);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new Exception("cannot create " + parent);
            }
            InputStream in = am.open(rel);
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
            if (l != null) l.onUnpack(done, rows.size(), rel);
        }

        writeText(stampFile, stamp);
        Log.i(TAG, "payload unpacked: " + done + " files");
    }

    /** Everything worth knowing when the child refuses to start. */
    private String describeRuntime() {
        return "node  " + nodeBin.getAbsolutePath()
            + "\n       存在=" + nodeBin.isFile()
            + " 可读=" + nodeBin.canRead()
            + " 可执行=" + nodeBin.canExecute()
            + " 大小=" + nodeBin.length()
            + "\nlibs  " + libDir.getAbsolutePath()
            + " 可读=" + libDir.canRead()
            + "\ngsrv  " + gsrvDir.getAbsolutePath()
            + " 可读=" + gsrvDir.canRead()
            + " 脚本=" + new File(gsrvDir, "server/index.js").isFile();
    }

    public void wipePayload() {
        stop();
        deleteRecursive(root);
    }

    // ---- running ----------------------------------------------------------------------------------

    /** Unpacks if needed, spawns Node, and waits for the server to answer {@code /healthz}. */
    public synchronized void start(Listener l) throws Exception {
        if (isRunning()) return;
        unpack(l);

        port = pickPort();
        synchronized (log) { log.clear(); }
        exitCode = Integer.MIN_VALUE;
        ready = false;

        List<String> cmd = new ArrayList<String>();
        cmd.add(nodeBin.getAbsolutePath());
        cmd.add(new File(gsrvDir, "server/index.js").getAbsolutePath());
        Log.i(TAG, "spawning " + cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gsrvDir);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.remove("LD_PRELOAD");
        String inherited = env.get("LD_LIBRARY_PATH");
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath()
            + (inherited == null || inherited.isEmpty() ? "" : ":" + inherited));
        File tmp = new File(root, "tmp");
        tmp.mkdirs();
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("HOME", root.getAbsolutePath());
        env.put("PORT", String.valueOf(port));
        env.put("HOST", "0.0.0.0");
        env.put("NODE_ENV", "production");

        note("LD_LIBRARY_PATH=" + env.get("LD_LIBRARY_PATH"));
        note(describeRuntime());
        try {
            process = pb.start();
        } catch (Exception e) {
            note("拉起 node 失败: " + e);
            throw e;
        }
        note("node 已拉起");
        pump(process, l);

        ready = waitReady(25000);
        if (!ready) {
            Log.w(TAG, "server did not become ready on port " + port);
        }
    }

    public void stop() {
        Process p = process;
        ready = false;
        process = null;
        if (p == null) return;
        Log.i(TAG, "stopping local server");
        p.destroy();
        for (int i = 0; i < 30; i++) {                 // up to ~3s for the graceful SIGTERM path
            try {
                p.exitValue();
                return;
            } catch (IllegalThreadStateException e) {
                sleep(100);
            }
        }
        p.destroy();
    }

    private void pump(final Process p, final Listener l) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (log) {
                            log.addLast(line);
                            while (log.size() > LOG_KEEP) log.removeFirst();
                        }
                        if (l != null) l.onLog(line);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "log pump ended", e);
                } finally {
                    try { if (r != null) r.close(); } catch (Exception ignored) { }
                    int code = Integer.MIN_VALUE;
                    try { code = p.exitValue(); } catch (IllegalThreadStateException e) { }
                    exitCode = code;
                    ready = false;
                    if (l != null) l.onExit(code);
                }
            }
        }, "localsrv-log");
        t.setDaemon(true);
        t.start();
    }

    /** Polls {@code /healthz} until it answers, the child dies, or the budget runs out. */
    private boolean waitReady(int budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return false;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url() + "/healthz").openConnection();
                conn.setConnectTimeout(600);
                conn.setReadTimeout(900);
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (conn.getResponseCode() == 200) return true;
            } catch (Exception ignored) {
                // not up yet
            } finally {
                if (conn != null) conn.disconnect();
            }
            sleep(250);
        }
        return false;
    }

    /** 3000 first (the game's own default), then the next few, skipping anything already bound. */
    private int pickPort() {
        for (int i = 0; i < PORT_CANDIDATES; i++) {
            int candidate = 3000 + i;
            ServerSocket probe = null;
            try {
                probe = new ServerSocket();
                probe.setReuseAddress(false);
                probe.bind(new InetSocketAddress("127.0.0.1", candidate), 1);
                return candidate;
            } catch (Exception taken) {
                Log.i(TAG, "port " + candidate + " busy, trying next");
            } finally {
                if (probe != null) try { probe.close(); } catch (Exception ignored) { }
            }
        }
        return 3000;
    }

    // ---- small helpers ----------------------------------------------------------------------------

    private String versionName() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String readText(File f) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), "UTF-8"));
            try { return r.readLine(); } finally { r.close(); }
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeText(File f, String s) {
        try {
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try { w.write(s); } finally { w.close(); }
        } catch (Exception e) {
            Log.w(TAG, "stamp write failed", e);
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

    private static void sleep(long ms) {
        try { TimeUnit.MILLISECONDS.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
