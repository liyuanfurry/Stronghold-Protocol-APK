package com.strongholdprotocol.client;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the immutable half of the game out of the APK (and out of the update overlay), and lets
 * everything else go to the server untouched.
 *
 * <p>Why not mirror the whole site: the client's HTML, JS, CSS and the {@code data/assets.json}
 * manifest are what change between builds, and a bundled copy has no way to follow a server update.
 * Leaving them on the network keeps the page and the socket in step, while the media payload — which
 * does change, but rarely and file-by-file — comes off the disk.
 *
 * <p>A path that is in neither layer is not an error: returning {@code null} hands the request back to
 * the WebView, so a server that has content the bundle predates still works.
 */
public class GameWebViewClient extends WebViewClient {

    private static final String TAG = "GameWebViewClient";

    /** Where upstream's index.html pulls its webfonts from, with a render-blocking <link>. */
    private static final String GFONTS_CSS = "fonts.googleapis.com";
    private static final String GFONTS_FILES = "fonts.gstatic.com";
    /** Same-origin path our own offline stylesheet uses for the bundled font files. */
    private static final String LOCAL_FONT_PATH = "/localfont/";

    private final AssetCache cache;

    public GameWebViewClient(AssetCache cache) {
        this.cache = cache;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        try {
            Uri url = request.getUrl();
            String host = url.getHost();
            // Upstream's index.html loads its three text families from Google with a render-blocking
            // <link>. On a network that cannot reach Google, that one request freezes the first paint
            // until the connection gives up — which is what "the first load takes forever" actually is,
            // and it survives localising all 272 MiB of artwork because the fonts are not artwork.
            if (host != null && (host.equals(GFONTS_CSS) || host.equals(GFONTS_FILES))) {
                return googleFonts(url.getPath());
            }

            String path = url.getPath();
            if (path == null) return null;

            // data/local-assets.json is the optional art extracted from a local game client. This client
            // never ships it, and the game asks for it at boot and again when a match mounts, each time
            // inside a multi-second timeout gate. Answering from the overlay (if a sync ever put one there)
            // or with an immediate 404 keeps those gates from waiting on a round trip that can only fail.
            if (path.equals("/data/local-assets.json")) {
                AssetCache.Source local = cache.open(path);
                if (local != null) return respond(local, "application/json");
                return notFound();
            }

            if (path.startsWith(LOCAL_FONT_PATH)) {
                return respond(cache.openBundled("localfonts/" + path.substring(LOCAL_FONT_PATH.length())),
                    mimeOf(path));
            }

            if (path.startsWith("/assets/") || path.startsWith("/fonts/") || path.startsWith("/vendor/")) {
                return respond(cache.open(path), mimeOf(path));
            }

            // Audio is fetched through an extension-less path (shared/media.js):
            //   /media/bgm/act1  ->  /assets/audio/bgm/act1.mp3
            if (path.startsWith("/media/")) {
                String rest = path.substring("/media/".length());
                for (String ext : AssetCache.AUDIO_EXTS) {
                    AssetCache.Source src = cache.open("/assets/audio/" + rest + ext);
                    if (src != null) return respond(src, "audio/mpeg");
                }
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "intercept failed", e);
        }
        return null;
    }

    /**
     * Answers Google's font hosts locally.
     *
     * <p>The stylesheet is replaced by a bundled one that carries the same {@code @font-face} rules for
     * Oxanium and Rajdhani (OFL, ~240 KiB, shipped in the APK) and deliberately says nothing about
     * Noto Sans SC — Android's own CJK font *is* Noto Sans CJK SC, so it falls back to something
     * visually equivalent instead of costing several megabytes of subset downloads.
     *
     * <p>Anything else on those hosts gets an empty stylesheet rather than a hang.
     */
    private WebResourceResponse googleFonts(String path) {
        if (path != null && path.startsWith("/css")) {
            AssetCache.Source css = cache.openBundled("localfonts/offline.css");
            if (css != null) return respond(css, "text/css");
        }
        return emptyCss();
    }

    private static WebResourceResponse notFound() {
        try {
            WebResourceResponse r = new WebResourceResponse("application/json", "utf-8",
                new ByteArrayInputStream("{}".getBytes("UTF-8")));
            r.setStatusCodeAndReasonPhrase(404, "Not Found");
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "public, max-age=86400");
            r.setResponseHeaders(headers);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static WebResourceResponse emptyCss() {
        try {
            WebResourceResponse r = new WebResourceResponse("text/css", "utf-8",
                new ByteArrayInputStream("/* served offline by the client */".getBytes("UTF-8")));
            r.setStatusCodeAndReasonPhrase(200, "OK");
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Content-Type", "text/css");
            headers.put("Cache-Control", "public, max-age=86400");
            r.setResponseHeaders(headers);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static WebResourceResponse respond(AssetCache.Source src, String mime) {
        if (src == null) return null;
        WebResourceResponse r = new WebResourceResponse(mime, null, src.stream);
        r.setStatusCodeAndReasonPhrase(200, "OK");
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Type", mime);
        // Never let the WebView keep its own copy: an update replaces overlay files, and a cached
        // response would then keep serving the old art. Reading from disk is cheap.
        headers.put("Cache-Control", "no-store");
        r.setResponseHeaders(headers);
        return r;
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".ogg") || p.endsWith(".oga")) return "audio/ogg";
        if (p.endsWith(".m4a") || p.endsWith(".aac")) return "audio/mp4";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".opus")) return "audio/opus";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".atlas")) return "text/plain";
        if (p.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
