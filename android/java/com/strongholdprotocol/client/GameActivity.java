package com.strongholdprotocol.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen landscape WebView running the game.
 *
 * <p>The page itself comes from the player's server, so the client and the multiplayer protocol stay in
 * lockstep with whatever that server runs; only the mirrored textures are served locally (see
 * {@link GameWebViewClient}).
 */
public class GameActivity extends Activity {

    public static final String EXTRA_SERVER = "server";
    private static final String TAG = "GameActivity";

    /** Bump to drop the WebView cache once on upgrade. See the call site in onCreate. */
    private static final int CACHE_BUST = 1;

    private FrameLayout root;
    private WebView web;
    private ProgressBar bar;
    private TextView errorView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    // Held directly: WebView.getWebChromeClient() only exists from API 26 and this app supports 21.
    private WebChromeClient chromeClient;
    private boolean pageLoaded;

    /**
     * Loads the battlefield renderer while the player is still in the lobby instead of on the "LOADING
     * FIELD" screen, which is where that cost lands today.
     *
     * <p>Entering a match mounts the render engine for the first time: {@code /vendor/pixi.min.js} (456 KiB)
     * and {@code /vendor/pixi-spine.js} (362 KiB) fetched through injected {@code <script>} tags, then parsed
     * and executed, then {@code render/app.js} imported, then a WebGL context created. None of it depends on
     * which match is being played, so none of it has to wait for one.
     *
     * <p>Delayed rather than immediate so it does not compete with the boot's own modules and fonts — a
     * slower lobby to save a slower match would be a bad trade.
     */
    private void warmRenderEngine(final WebView view) {
        view.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    view.evaluateJavascript(WARM_JS, null);
                    Log.i(TAG, "render engine warm-up requested");
                } catch (Exception e) {
                    Log.w(TAG, "render warm-up failed", e);
                }
            }
        }, WARM_DELAY_MS);
    }

    private static final long WARM_DELAY_MS = 4000;

    /**
     * Best-effort, idempotent. Two things happen here, both moving work off the first match's critical path:
     *
     * <ol>
     *   <li>the battlefield render engine — 818 KiB of Pixi + pixi-spine plus {@code render/app.js};</li>
     *   <li>the match's data files — the game starts fetching those only once the player is already in a
     *       room ({@code warmGameData()} in main.js), and the match screen waits on them, which is what the
     *       first "LOADING FIELD" actually spends its ~5 s on. 15 files, ~3.9 MiB ({@code chess.json} alone
     *       is 1.6 MiB). Starting the same fetch from the lobby simply moves that download to a moment when
     *       nobody is staring at a spinner; every later match reads it from the WebView cache either way.</li>
     * </ol>
     *
     * <p>Both are safe for the same reason: the render engine's loader checks {@code if (!globalThis.PIXI)}
     * before injecting its own script, and {@code data.loadAll} shares one promise per file, so asking first
     * cannot make the game fetch anything twice.
     */
    private static final String WARM_JS =
        "(function(){try{"
        // ---- render engine
        + "if(!window.PIXI){var s=document.createElement('script');s.src='/vendor/pixi.min.js';"
        + "s.onload=function(){try{if(!(window.PIXI&&window.PIXI.spine)){"
        + "var t=document.createElement('script');t.src='/vendor/pixi-spine.js';document.head.appendChild(t);"
        + "}}catch(e){}};document.head.appendChild(s);}"
        + "import('/js/render/app.js').catch(function(){});"
        // ---- the match's data files (GAME_FILES), taken from the game itself so the list stays in step
        + "var n=0;(function w(){n++;var sp=window.__SP__;"
        + "if(sp&&sp.data&&typeof sp.data.loadAll==='function'){"
        + "import('/js/ui/gameComponents.js').then(function(m){try{sp.data.loadAll(m.GAME_FILES);}catch(e){}},"
        + "function(){});return;}"
        + "if(n<60)setTimeout(w,500);})();"
        + "}catch(e){}})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);

        root = (FrameLayout) findViewById(R.id.root);
        web = (WebView) findViewById(R.id.web);
        bar = (ProgressBar) findViewById(R.id.game_progress);
        errorView = (TextView) findViewById(R.id.game_error);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE); } catch (Throwable ignored) { }

        web.setBackgroundColor(0xFF0C0F0E);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        try { CookieManager.getInstance().setAcceptCookie(true); } catch (Throwable ignored) { }

        // v2.7 wrongly cached /data/local-assets.json as a 404 for a day, which hides the server's copy of
        // the optional local-client art manifest (the official board art and some enemy models come from
        // it). Dropping the cache once on upgrade is the only way to evict that entry; the match data is
        // refetched by the warm-up below, so the cost is a single slower first match after upgrading.
        if (Prefs.cacheBust(this) < CACHE_BUST) {
            try {
                web.clearCache(true);
                Log.i(TAG, "cleared the WebView cache (one-shot, cache-bust " + CACHE_BUST + ")");
            } catch (Throwable t) {
                Log.w(TAG, "cache clear failed", t);
            }
            Prefs.setCacheBust(this, CACHE_BUST);
        }

        final AssetCache cache = App.cacheOf(this);
        web.setWebViewClient(new GameWebViewClient(cache) {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                bar.setVisibility(View.GONE);
                warmRenderEngine(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;                  // everything the game links to stays inside this window
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Sufficient on every API level: the framework's API 23+ overload delegates to this one.
                if (!pageLoaded) {
                    bar.setVisibility(View.GONE);
                    showError("无法从服务器加载游戏页面。\n\n" + description + "\n\n" + failingUrl
                        + "\n\n请检查服务器地址与网络连接。");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError err) {
                // A private server may use a self-signed certificate; that is the player's call, not ours.
                new AlertDialog.Builder(GameActivity.this)
                    .setTitle("证书不受信任")
                    .setMessage("服务器 " + err.getUrl() + " 的 HTTPS 证书无法验证。\n"
                        + "如果是你自己搭建的服务器，可以继续；否则请先退出。")
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { handler.cancel(); }
                    })
                    .setPositiveButton("仍然继续", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { handler.proceed(); }
                    })
                    .show();
            }
        });

        chromeClient = new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) bar.setVisibility(View.GONE);
                else bar.setVisibility(View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.d(TAG, "[web] " + msg.message() + " @" + msg.sourceId() + ":" + msg.lineNumber());
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                immersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customViewCallback != null) { customViewCallback.onCustomViewHidden(); customViewCallback = null; }
                immersive();
            }
        };
        web.setWebChromeClient(chromeClient);

        String server = getIntent().getStringExtra(EXTRA_SERVER);
        if (server == null || server.trim().isEmpty()) server = Prefs.server(this);
        server = Prefs.normalizeServer(server);
        if (server.isEmpty()) {
            // Reachable only if something launched this activity directly; the settings screen validates.
            Toast.makeText(this, "没有服务器地址，请返回填写", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        web.loadUrl(server + "/");
    }

    private void showError(String text) {
        errorView.setText(text);
        errorView.setVisibility(View.VISIBLE);
    }

    /** Immersive sticky fullscreen: the game draws its own HUD and must not lose height to system bars. */
    private void immersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { if (chromeClient != null) chromeClient.onHideCustomView(); return; }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        immersive();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
