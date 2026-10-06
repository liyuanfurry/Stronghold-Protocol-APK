package com.strongholdprotocol.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Game server + resource-update settings, then hands over to {@link GameActivity}.
 *
 * <p>The media payload ships inside the APK, so this screen is not a prerequisite for playing — it is
 * where the player points the client at a server and, if that server's payload has moved on since the
 * APK was built, pulls just the files that changed.
 */
public class MainActivity extends Activity implements AssetCache.Listener {

    private EditText serverField, updateField;
    private Button updateButton, playButton, checkButton, revertButton;
    private ProgressBar progress;
    private TextView status, builtinInfo, serverInfo, overlayInfo, footer;
    private TextView localIp, scanStatus;
    private Button scanButton;
    private LinearLayout scanResults;
    private TextView srvStatus;
    private Button srvToggle, srvUse, srvLog;

    private AssetCache cache;
    private LanScanner scanner;
    private boolean scanning;
    private boolean autoChecked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cache = App.cacheOf(this);

        serverField = (EditText) findViewById(R.id.server);
        updateField = (EditText) findViewById(R.id.update_base);
        updateButton = (Button) findViewById(R.id.update);
        playButton = (Button) findViewById(R.id.play);
        checkButton = (Button) findViewById(R.id.check);
        revertButton = (Button) findViewById(R.id.revert);
        progress = (ProgressBar) findViewById(R.id.progress);
        status = (TextView) findViewById(R.id.status);
        builtinInfo = (TextView) findViewById(R.id.version_builtin);
        serverInfo = (TextView) findViewById(R.id.version_server);
        overlayInfo = (TextView) findViewById(R.id.version_overlay);
        footer = (TextView) findViewById(R.id.footer);
        localIp = (TextView) findViewById(R.id.local_ip);
        scanStatus = (TextView) findViewById(R.id.scan_status);
        scanButton = (Button) findViewById(R.id.scan);
        scanResults = (LinearLayout) findViewById(R.id.scan_results);

        scanner = new LanScanner();
        srvStatus = (TextView) findViewById(R.id.srv_status);
        srvToggle = (Button) findViewById(R.id.srv_toggle);
        srvUse = (Button) findViewById(R.id.srv_use);
        srvLog = (Button) findViewById(R.id.srv_log);

        serverField.setText(Prefs.server(this));
        updateField.setText(Prefs.updateBase(this));

        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startUpdate(); }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(); }
        });
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkVersion(false); }
        });
        revertButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmRevert(); }
        });
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startScan(); }
        });
        srvToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleServer(); }
        });
        srvUse.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { useLocalServer(); }
        });
        srvLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showServerLog(); }
        });

        refreshResourceInfo();
        warnIfWebViewTooOld();

        if (cache.isRunning()) {
            status.setText("资源更新仍在进行…");
            progress.setIndeterminate(false);
            setBusy(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshResourceInfo();
        refreshLocalIp();
        refreshServerUi();
        if (cache.isRunning()) {
            // The update keeps running while this screen is gone; re-attach and show where it is.
            cache.setListener(this);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setBusy(true);
            onProgress(cache.lastDone(), cache.lastTotal(), cache.lastCurrent());
        } else if (!autoChecked) {
            String base = updateBaseFromUi();
            if (!base.isEmpty()) {
                autoChecked = true;
                checkVersion(true);
            }
        }
    }

    // ---- inputs -----------------------------------------------------------------------------------

    /** @return the normalised game server, or null after telling the player what is missing */
    private String requireServer() {
        String s = Prefs.normalizeServer(serverField.getText().toString());
        if (s.isEmpty()) {
            needAddress("服务器地址");
            return null;
        }
        serverField.setText(s);
        Prefs.setServer(this, s);
        return s;
    }

    /**
     * Where updates come from: the dedicated field when set, otherwise the game server.
     *
     * @return a base URL, or "" when neither is filled in
     */
    private String updateBaseFromUi() {
        String u = Prefs.normalizeServer(updateField.getText().toString());
        Prefs.setUpdateBase(this, u);
        if (!u.isEmpty()) return u;
        String s = Prefs.normalizeServer(serverField.getText().toString());
        if (!s.isEmpty()) Prefs.setServer(this, s);
        return s;
    }

    private void needAddress(String what) {
        new AlertDialog.Builder(this)
            .setTitle("请先填写" + what)
            .setMessage("这个客户端不含任何内置服务器。\n"
                + "请填入你要连接的游戏服务器，例如 https://example.com:8443 "
                + "或局域网 http://192.168.1.10:3000。")
            .setPositiveButton("知道了", null)
            .show();
        (what.startsWith("服务器") ? serverField : updateField).requestFocus();
    }

    // ---- actions ----------------------------------------------------------------------------------

    private void play() {
        String server = requireServer();
        if (server == null) return;
        updateBaseFromUi();                       // remember the update address too

        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_SERVER, server);
        startActivity(i);
    }

    private void startUpdate() {
        String base = updateBaseFromUi();
        if (base.isEmpty()) { needAddress("资源更新地址"); return; }
        if (cache.isRunning()) { Toast.makeText(this, "更新已在进行中", Toast.LENGTH_SHORT).show(); return; }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setIndeterminate(true);
        progress.setProgress(0);
        status.setText("正在连接 " + base + " …");
        setBusy(true);
        cache.startSync(base, this);
    }

    private void checkVersion(final boolean auto) {
        final String base = updateBaseFromUi();
        if (base.isEmpty()) {
            serverInfo.setText("服务器版本：未填写地址");
            if (!auto) needAddress("资源更新地址");
            return;
        }
        serverInfo.setText("服务器版本：校对中…");
        if (!auto) {
            progress.setIndeterminate(true);
            status.setText("正在校对版本…");
        }
        cache.checkVersion(base, new AssetCache.VersionListener() {
            @Override public void onVersion(AssetCache.Version v) {
                if (!auto) progress.setIndeterminate(false);
                if (!v.reachable) {
                    serverInfo.setText("服务器版本：无法连接");
                    if (!auto) status.setText("校对失败：" + v.error);
                    return;
                }
                String cur = AssetCache.shortHash(v.currentHash);
                String rem = AssetCache.shortHash(v.remoteHash);
                if (v.hasUpdate()) {
                    serverInfo.setText("服务器版本：" + rem + "　（本机 " + cur + "，有更新）");
                    status.setText("发现资源更新：" + cur + " → " + rem
                        + "，点「在线更新资源」即可，只下载变动的文件。");
                } else {
                    serverInfo.setText("服务器版本：" + rem + "　（已是最新）");
                    if (!auto) status.setText("资源已是最新（版本 " + rem + "）");
                }
                refreshResourceInfo();
            }
        });
    }

    private void confirmRevert() {
        new AlertDialog.Builder(this)
            .setTitle("还原为内置资源")
            .setMessage("将删除所有在线更新下载的文件，回到安装包里预装的版本。\n游戏照常可以玩。")
            .setNegativeButton("取消", null)
            .setPositiveButton("还原", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { doRevert(); }
            })
            .show();
    }

    private void doRevert() {
        if (cache.isRunning()) { Toast.makeText(this, "请先等待更新结束", Toast.LENGTH_SHORT).show(); return; }
        cache.clearOverlay();
        refreshResourceInfo();
        serverInfo.setText("服务器版本：未校对");
        status.setText("已还原为内置资源");
        progress.setIndeterminate(false);
        progress.setProgress(0);
    }

    // ---- on-phone server --------------------------------------------------------------------------

    private final LocalServer.Listener serverListener = new LocalServer.Listener() {
        @Override public void onUnpack(final int done, final int total, String current) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    srvStatus.setText("解包中 " + done + " / " + total + " …（只在首次启动时做一次）");
                }
            });
        }
        @Override public void onLog(String line) {
            // kept in LocalServer's ring buffer; the dialog reads it on demand
        }
        @Override public void onExit(final int code) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    refreshServerUi();
                    if (code != 0) {
                        status.setText("本机服务器退出了（退出码 " + code + "）。"
                            + "点「查看服务端日志」看原因——那几行就是定位问题的关键。");
                    }
                }
            });
        }
    };

    private void toggleServer() {
        final LocalServer srv = App.localServerOf(this);
        if (srv.isRunning()) {
            LocalServerService.stop(this);
            srv.stop();
            refreshServerUi();
            return;
        }
        srvToggle.setEnabled(false);
        srvStatus.setText("正在准备载荷…（首次约 97 MB，需要一点时间）");
        LocalServerService.start(this);          // keep-alive; it will not double-start the child
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    srv.start(serverListener);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String e = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        srvToggle.setEnabled(true);
                        refreshServerUi();
                        if (e != null) srvStatus.setText("启动失败：" + e + "\n点「查看服务端日志」看详情");
                    }
                });
            }
        }, "localsrv-ui-start").start();
    }

    private void useLocalServer() {
        LocalServer srv = App.localServerOf(this);
        if (!srv.isRunning()) {
            Toast.makeText(this, "本机服务器还没启动", Toast.LENGTH_SHORT).show();
            return;
        }
        String u = "http://127.0.0.1:" + srv.port();
        serverField.setText(u);
        Prefs.setServer(this, u);
        status.setText("已填入 " + u + "，点「开始游戏」即可（联机也走这台手机）。");
    }

    private void showServerLog() {
        LocalServer srv = App.localServerOf(this);
        String head = "状态：" + (srv.isRunning() ? (srv.isReady() ? "运行中" : "启动中") : "未运行")
            + "　端口：" + srv.port()
            + "　退出码：" + (srv.exitCode() == Integer.MIN_VALUE ? "—" : String.valueOf(srv.exitCode()))
            + "\n载荷：" + srv.root().getAbsolutePath()
            + "\n\n";
        String body = srv.recentLogText(60);
        if (body.trim().isEmpty()) body = "(暂无日志)";
        else body = "—— 最后 60 行 ——\n" + body;
        new AlertDialog.Builder(this)
            .setTitle("本机服务器日志")
            .setMessage(head + body)
            .setPositiveButton("知道了", null)
            .show();
    }

    private void refreshServerUi() {
        LocalServer srv = App.localServerOf(this);
        String ip = NetInfo.localIpv4();
        if (srv.isRunning()) {
            String lan = (ip == null ? "（未连接局域网）" : "http://" + ip + ":" + srv.port());
            srvStatus.setText((srv.isReady() ? "运行中" : "启动中…")
                + " · 本机 " + srv.url()
                + "\n同网段设备连：" + lan);
            srvToggle.setText("停止本机服务器");
        } else {
            int code = srv.exitCode();
            srvStatus.setText(code == Integer.MIN_VALUE ? "未启动"
                : "已退出（退出码 " + code + "）—— 点「查看服务端日志」看原因");
            srvToggle.setText("启动本机服务器");
        }
        srvToggle.setEnabled(true);
    }

    // ---- LAN discovery ---------------------------------------------------------------------------

    private void refreshLocalIp() {
        String ip = NetInfo.localIpv4();
        String iface = NetInfo.localInterfaceName();
        if (ip == null) {
            localIp.setText("本机地址：未连接局域网");
        } else {
            localIp.setText("本机地址：" + ip + (iface == null ? "" : "（" + iface + "）")
                + "\n同网段的人可以用 http://" + ip + ":3000 连到本机服务器");
        }
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        scanResults.removeAllViews();
        scanButton.setEnabled(false);
        scanButton.setText("扫描中…");
        scanStatus.setText("准备扫描…");
        refreshLocalIp();
        scanner.scan(Prefs.normalizeServer(serverField.getText().toString()), scanListener);
    }

    private final LanScanner.Listener scanListener = new LanScanner.Listener() {
        @Override public void onProgress(int done, int total) {
            scanStatus.setText("已探测 " + done + " / " + total + " 个地址…");
        }

        @Override public void onFound(final LanScanner.Found found) {
            Button row = new Button(MainActivity.this);
            row.setText(found.describe());
            row.setAllCaps(false);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    serverField.setText(found.url);
                    Prefs.setServer(MainActivity.this, found.url);
                    scanStatus.setText("已填入 " + found.url + "，接着点「校对版本」或「开始游戏」。");
                }
            });
            scanResults.addView(row);
        }

        @Override public void onDone(int found, String note) {
            scanning = false;
            scanButton.setEnabled(true);
            scanButton.setText("扫描局域网");
            if (note != null) {
                scanStatus.setText(note);
            } else {
                scanStatus.setText("找到 " + found + " 个服务器，点条目填入地址。");
            }
        }
    };

    // ---- display ----------------------------------------------------------------------------------

    private void refreshResourceInfo() {
        int files = cache.bundledCount();
        long bytes = cache.bundledBytes();
        builtinInfo.setText(String.format(Locale.US, "内置资源：%d 个文件 · %.1f MB · 版本 %s",
            files, bytes / 1048576.0, AssetCache.shortHash(cache.bundledHash())));

        AssetCache.Stats ov = cache.overlayStats();
        if (ov.files == 0) {
            overlayInfo.setText("已更新覆盖：无（正在使用内置资源）");
        } else {
            overlayInfo.setText(String.format(Locale.US, "已更新覆盖：%d 个文件 · %.1f MB",
                ov.files, ov.bytes / 1048576.0));
        }

        long at = Prefs.lastSyncAt(this);
        if (at > 0) {
            footer.setText("上次更新："
                + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", at)
                + " · 更新下来的文件存在应用私有目录，卸载即清除");
        } else {
            footer.setText("尚未更新过。游戏所需素材已随安装包预装，可以直接开局。");
        }
    }

    private void setBusy(boolean busy) {
        updateButton.setEnabled(!busy);
        playButton.setEnabled(!busy);
        checkButton.setEnabled(!busy);
        revertButton.setEnabled(!busy);
    }

    /** The client needs import maps (Chrome 89+); an older System WebView cannot boot it at all. */
    private void warnIfWebViewTooOld() {
        String ua = "";
        try {
            ua = android.webkit.WebSettings.getDefaultUserAgent(this);
        } catch (Throwable ignored) { }
        int chrome = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua);
        if (m.find()) {
            try { chrome = Integer.parseInt(m.group(1)); } catch (Exception ignored) { }
        }
        if (chrome > 0 && chrome < 89) {
            new AlertDialog.Builder(this)
                .setTitle("系统 WebView 版本过低")
                .setMessage("当前版本 Chrome/" + chrome + "，游戏需要 Chrome/89 以上（import maps）。\n"
                    + "请在应用商店更新「Android System WebView」或 Chrome 后重试。")
                .setPositiveButton("知道了", null)
                .show();
        }
    }

    // ---- AssetCache.Listener ---------------------------------------------------------------------

    @Override
    public void onProgress(int done, int total, String current) {
        if (total <= 0) {
            progress.setIndeterminate(true);
            status.setText(current);
            return;
        }
        progress.setIndeterminate(false);
        progress.setMax(total);
        progress.setProgress(done);
        status.setText("校对并更新资源 " + done + " / " + total
            + (TextUtils.isEmpty(current) ? "" : "\n" + current));
    }

    @Override
    public void onDone(boolean ok, String message) {
        setBusy(false);
        progress.setIndeterminate(false);
        status.setText(message);
        refreshResourceInfo();
        if (ok) {
            serverInfo.setText("服务器版本：" + AssetCache.shortHash(cache.currentHash()) + "　（已是最新）");
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The update outlives this screen, so only drop the callback (it holds its own wake lock and
        // keeps going while the player is inside the game).
        cache.clearListener(this);
        if (scanner != null) scanner.cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
