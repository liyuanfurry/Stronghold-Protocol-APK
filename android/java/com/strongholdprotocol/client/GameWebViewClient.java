package com.strongholdprotocol.client;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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

    private final AssetCache cache;

    public GameWebViewClient(AssetCache cache) {
        this.cache = cache;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        try {
            String path = request.getUrl().getPath();
            if (path == null) return null;

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
