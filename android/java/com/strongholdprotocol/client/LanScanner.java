package com.strongholdprotocol.client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Finds game servers on the local network.
 *
 * <p>There is no discovery protocol to speak of — the server is a plain HTTP service — so this walks
 * the /24 and asks every address for {@code /healthz}. The endpoint identifies itself with a
 * {@code {"ok":true,"app":"0.1.2",...}} body, which is specific enough that a random web server on the
 * network will not be mistaken for a game server.
 *
 * <p>Ports probed per host: whatever the player already configured, plus the two this project is
 * normally deployed on (plain {@code 3000} and reverse-proxied {@code 8443}).
 */
public final class LanScanner {

    private static final String TAG = "LanScanner";
    private static final int TIMEOUT_CONNECT_MS = 400;
    private static final int TIMEOUT_READ_MS = 800;
    private static final int THREADS = 32;
    private static final int MAX_BODY = 8192;

    /** A server that answered the fingerprint check. */
    public static final class Found {
        public final String url;
        public final String ip;
        public final int port;
        public final String app;
        public final int protocol;
        public final int humans;
        public final int rooms;

        Found(String url, String ip, int port, String app, int protocol, int humans, int rooms) {
            this.url = url;
            this.ip = ip;
            this.port = port;
            this.app = app;
            this.protocol = protocol;
            this.humans = humans;
            this.rooms = rooms;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(ip).append(':').append(port);
            if (app != null && !app.isEmpty()) sb.append("　· 版本 ").append(app);
            if (humans > 0) sb.append("　· ").append(humans).append(" 人在线");
            if (rooms > 0) sb.append("　· ").append(rooms).append(" 个房间");
            return sb.toString();
        }
    }

    public interface Listener {
        void onProgress(int done, int total);
        void onFound(Found found);
        /** @param found how many servers answered; {@code note} is null unless something went wrong */
        void onDone(int found, String note);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    /**
     * @param configuredUrl the address already in the settings field, probed first; may be null
     */
    public void scan(final String configuredUrl, final Listener listener) {
        cancelled = false;
        final String self = NetInfo.localIpv4();
        final String prefix = NetInfo.scanPrefix();
        if (prefix == null) {
            main.post(new Runnable() {
                @Override public void run() {
                    listener.onDone(0, "没有检测到局域网地址，请确认已连接 Wi-Fi。");
                }
            });
            return;
        }

        final List<String[]> endpoints = endpoints(configuredUrl);
        final List<String> jobs = new ArrayList<String>();
        for (int i = 1; i <= 254; i++) {
            String ip = prefix + "." + i;
            if (ip.equals(self)) continue;
            for (String[] ep : endpoints) {
                jobs.add(ep[0] + "|" + ep[1] + "|" + ip);
            }
        }
        final int total = jobs.size();
        Log.i(TAG, "scanning " + prefix + ".0/24, " + endpoints.size() + " endpoints each = " + total + " probes");

        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger found = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (final String job : jobs) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    if (cancelled) return;
                    String[] parts = job.split("\\|");
                    final Found f = probe(parts[0], parts[2], Integer.parseInt(parts[1]));
                    if (f != null) {
                        found.incrementAndGet();
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (!cancelled) listener.onFound(f);
                            }
                        });
                    }
                    int d = done.incrementAndGet();
                    if (d % 16 == 0 || d == total) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (!cancelled) listener.onProgress(d, total);
                            }
                        });
                    }
                }
            });
        }
        pool.shutdown();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (!pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        if (cancelled) { pool.shutdownNow(); break; }
                    }
                } catch (InterruptedException ignored) { }
                main.post(new Runnable() {
                    @Override public void run() {
                        int n = found.get();
                        listener.onDone(n, n == 0
                            ? "局域网内没有找到游戏服务器。确认对方已启动服务端、且在同一网段。"
                            : null);
                    }
                });
            }
        }, "scan-wait").start();
    }

    /** Which (scheme, port) pairs to try, configured address first. */
    private static List<String[]> endpoints(String configuredUrl) {
        List<String[]> out = new ArrayList<String[]>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        if (configuredUrl != null && !configuredUrl.isEmpty()) {
            try {
                URL u = new URL(configuredUrl);
                String scheme = u.getProtocol();
                int p = u.getPort();
                if (p <= 0) p = "https".equals(scheme) ? 443 : 80;
                if (seen.add(scheme + ":" + p)) out.add(new String[]{scheme, String.valueOf(p)});
            } catch (Exception ignored) { }
        }
        String[][] defaults = {{"http", "3000"}, {"https", "8443"}, {"http", "8080"}};
        for (String[] d : defaults) {
            if (seen.add(d[0] + ":" + d[1])) out.add(d);
        }
        while (out.size() > 3) out.remove(out.size() - 1);
        return out;
    }

    /**
     * One-shot reachability + identity check for an address the player typed.
     *
     * <p>Distinguishes the failure modes on purpose: "no such host", "connection refused" and "timed
     * out" mean three different mistakes (typo, port closed, host unreachable), and a single generic
     * "connect failed" would leave the player guessing which one they made.
     *
     * @return {ok, detail} — detail is a readable line in both cases
     */
    public static String[] checkServer(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + "/healthz");
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                SSLContext ctx = trustAll();
                if (ctx == null) return new String[]{"0", "HTTPS 初始化失败"};
                ((HttpsURLConnection) conn).setSSLSocketFactory(ctx.getSocketFactory());
                ((HttpsURLConnection) conn).setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    @Override public boolean verify(String host, javax.net.ssl.SSLSession session) { return true; }
                });
            }
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "StrongholdProtocol-Android check");

            int code = conn.getResponseCode();
            if (code != 200) return new String[]{"0", "服务器对 /healthz 返回 HTTP " + code};

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < MAX_BODY) bos.write(buf, 0, n);
            in.close();

            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            if (!o.optBoolean("ok")) return new String[]{"0", "/healthz 存在，但返回的不是这个服务端"};
            String app = o.optString("app", "?");
            return new String[]{"1", "服务端 " + app + " · 协议 " + o.optInt("version")
                + " · " + o.optInt("humans") + " 人在线 · " + o.optInt("rooms") + " 个房间"};
        } catch (java.net.UnknownHostException e) {
            return new String[]{"0", "域名解析不了（地址拼错，或者这台设备解析不到）"};
        } catch (java.net.ConnectException e) {
            return new String[]{"0", "拒绝连接（端口没开，或者服务端没在跑）"};
        } catch (java.net.SocketTimeoutException e) {
            return new String[]{"0", "连接超时（4 秒无响应：地址通但服务端没应答，或被网络挡住）"};
        } catch (javax.net.ssl.SSLException e) {
            return new String[]{"0", "TLS 握手失败（端口不是 HTTPS，或证书有问题）"};
        } catch (Exception e) {
            return new String[]{"0", e.getClass().getSimpleName() + "：" + e.getMessage()};
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** @return the server, or null when nothing game-shaped answered */
    private static Found probe(String scheme, String ip, int port) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(scheme + "://" + ip + ":" + port + "/healthz");
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                SSLContext ctx = trustAll();
                if (ctx == null) return null;
                ((HttpsURLConnection) conn).setSSLSocketFactory(ctx.getSocketFactory());
                // A LAN server commonly has a self-signed certificate. We only read a public status
                // blob here, and never send credentials, so accepting it for the probe is fine;
                // the real connection still goes through the WebView's certificate prompt.
                ((HttpsURLConnection) conn).setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    @Override public boolean verify(String host, javax.net.ssl.SSLSession session) { return true; }
                });
            }
            conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
            conn.setReadTimeout(TIMEOUT_READ_MS);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "StrongholdProtocol-Android discovery");
            if (conn.getResponseCode() != 200) return null;

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < MAX_BODY) bos.write(buf, 0, n);
            in.close();

            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            if (!o.optBoolean("ok")) return null;
            String app = o.optString("app", "");
            if (app.isEmpty() || !o.has("version")) return null;   // not this game

            String base = scheme + "://" + ip + ":" + port;
            return new Found(base, ip, port, app, o.optInt("version"),
                    o.optInt("humans"), o.optInt("rooms"));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static SSLContext trustAll() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            Log.w(TAG, "trust-all context unavailable", e);
            return null;
        }
    }
}
