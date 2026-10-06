package com.strongholdprotocol.client;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
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

import java.util.List;
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
    private Button updateButton, playButton, checkButton, revertButton, fullCheckButton;
    private ProgressBar progress;
    private TextView status, builtinInfo, serverInfo, overlayInfo, footer;
    private Button bundleButton;
    private TextView localIp, scanStatus;
    private Button scanButton;
    private LinearLayout scanResults;
    private TextView srvStatus;
    private Button srvToggle, srvUse, srvLog, srvCopy;
    private TextView upStatus;
    private Button upRefresh, upEmbedded;
    private LinearLayout upList;

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
        fullCheckButton = (Button) findViewById(R.id.full_check);
        playButton = (Button) findViewById(R.id.play);
        checkButton = (Button) findViewById(R.id.check);
        revertButton = (Button) findViewById(R.id.revert);
        progress = (ProgressBar) findViewById(R.id.progress);
        status = (TextView) findViewById(R.id.status);
        bundleButton = (Button) findViewById(R.id.bundle_import);
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
        srvCopy = (Button) findViewById(R.id.srv_copy);
        upStatus = (TextView) findViewById(R.id.up_status);
        upRefresh = (Button) findViewById(R.id.up_refresh);
        upEmbedded = (Button) findViewById(R.id.up_embedded);
        upList = (LinearLayout) findViewById(R.id.up_list);

        ((TextView) findViewById(R.id.build_stamp)).setText(
            "STRONGHOLD PROTOCOL · 安卓客户端 v" + BuildStamp.VERSION + " · 构建于 " + BuildStamp.BUILT_AT);

        serverField.setText(Prefs.server(this));
        updateField.setText(Prefs.updateBase(this));

        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startUpdate(false); }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(); }
        });
        fullCheckButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmFullCheck(); }
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
        srvCopy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyLanUrl(); }
        });
        upRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshUpstream(); }
        });
        upEmbedded.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { useEmbeddedGsrv(); }
        });
        bundleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmBundleImport(); }
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
        upStatus.setText("本机服务器当前运行：" + Upstream.activeVersion(this) + "\n点「刷新版本列表」向 GitHub 查询上游有哪些版本。");
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

    /**
     * Checks the address is actually alive before spending a game-screen launch on it.
     *
     * <p>A wrong port or a sleeping server used to land the player on a black WebView with no
     * explanation. Now the failure names itself, and the player can still force their way in — the
     * check is advice, not a gate.
     */
    private void play() {
        final String server = requireServer();
        if (server == null) return;
        updateBaseFromUi();                       // remember the update address too

        status.setText("正在测试 " + server + " 的连通性…");
        playButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String[] r = LanScanner.checkServer(server);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        playButton.setEnabled(true);
                        if ("1".equals(r[0])) {
                            status.setText("已连通 · " + r[1]);
                            launchGame(server);
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("连不上这台服务器")
                            .setMessage(server + "\n\n" + r[1]
                                + "\n\n地址拼错、端口没开、服务端没启动，都会是这样。仍然要进去吗？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("仍然进入", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) { launchGame(server); }
                            })
                            .show();
                    }
                });
            }
        }, "server-probe").start();
    }

    private void launchGame(String server) {
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_SERVER, server);
        startActivity(i);
    }

    private void startUpdate(boolean full) {
        String base = updateBaseFromUi();
        if (base.isEmpty()) { needAddress("资源更新地址"); return; }
        if (cache.isRunning()) { Toast.makeText(this, "更新已在进行中", Toast.LENGTH_SHORT).show(); return; }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setIndeterminate(true);
        progress.setProgress(0);
        status.setText(full ? "正在完整核对（无视清单缓存）…" : "正在连接 " + base + " …");
        setBusy(true);
        cache.startSync(base, this, full);
    }

    /**
     * Confirms first, because the difference between the two is not obvious and the slow one costs the
     * server thousands of requests.
     */
    private void confirmFullCheck() {
        new AlertDialog.Builder(this)
            .setTitle("完整核对素材")
            .setMessage("平时点「在线更新资源」会先看清单的哈希：没变就直接结束，只发 1 个请求。\n\n"
                + "但那个哈希只覆盖清单本身（路径和元数据），**不覆盖素材文件内容——"
                + "上游只重绘了某张图、路径没变时，清单哈希不变，普通更新会说「已是最新」，那张图就更新不了。\n\n"
                + "完整核对照样把包里 4000 多个文件挨个问一遍（带 ETag，没变的服务器只回 304 不传正文），"
                + "慢几十秒、请求多，但不会漏掉这种情况。\n\n"
                + "它同时会把服务器上的「本机美术」清单（data/local-assets.json：官方棋盘图集、UI 贴图、"
                + "表情等，约 1500 个文件）一并同步进本机覆盖层 —— 那部分约几十 MB，"
                + "同步一次之后就不用再向服务器取，之后每次都从本机读。\n\n只在需要时用。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始完整核对", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { startUpdate(true); }
            })
            .show();
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

    // ---- artwork from the upstream bundle ---------------------------------------------------------

    private void confirmBundleImport() {
        final String tag = Upstream.activeVersion(this);
        new AlertDialog.Builder(this)
            .setTitle("从 GitHub 整合包补素材")
            .setMessage("下载上游 " + tag + " 的 Release 整合包，从中取出美术与音频写入本机覆盖层。\n\n"
                + "· 整合包很大（v0.1.4 约 431 MB、v0.1.3 约 290 MB），建议连 Wi-Fi 或在代理下进行\n"
                + "· 全程先在临时目录解包，成功后才一次性生效，中途失败不会留下新老素材混在一起的覆盖层\n"
                + "· 现有素材不会被删除，只是被新素材盖住\n"
                + "· 临时文件会在结束后自动清理\n\n"
                + "前提是「上游版本 / 本机现有」那个版本有对应的整合包。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始下载", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { runBundleImport(); }
            })
            .show();
    }

    private void runBundleImport() {
        bundleButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        BundleImport.run(this, new BundleImport.Listener() {
            @Override public void onProgress(final int percent, final String detail) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        status.setText(detail);
                        if (percent >= 0) {
                            progress.setIndeterminate(false);
                            progress.setProgress(percent);
                        } else {
                            progress.setIndeterminate(true);
                        }
                    }
                });
            }

            @Override public void onDone(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        bundleButton.setEnabled(true);
                        progress.setIndeterminate(false);
                        progress.setProgress(ok ? 100 : 0);
                        status.setText(message);
                        refreshResourceInfo();
                        Toast.makeText(MainActivity.this,
                            ok ? "素材已从整合包补齐" : message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    // ---- upstream versions ------------------------------------------------------------------------

    private void refreshUpstream() {
        upRefresh.setEnabled(false);
        upStatus.setText("正在向 GitHub 查询上游版本…");
        Upstream.list(new Upstream.ListListener() {
            @Override public void onList(final boolean ok, final List<Upstream.Release> list, final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        upRefresh.setEnabled(true);
                        if (!ok) {
                            upStatus.setText("查询失败：" + error
                                + "\nGitHub 在部分网络下不可达，开代理后重试。");
                            return;
                        }
                        renderUpstream(list);
                    }
                });
            }
        });
    }

    private void renderUpstream(List<Upstream.Release> list) {
        upList.removeAllViews();
        final String active = Upstream.activeVersion(this);
        String newest = list.isEmpty() ? null : list.get(0).tag;
        upStatus.setText("共 " + list.size() + " 个上游版本；本机服务器当前运行 " + active);

        for (final Upstream.Release r : list) {
            StringBuilder label = new StringBuilder(r.tag);
            if (!r.date.isEmpty()) label.append("　·　").append(r.date);
            if (r.tag.equals(newest)) label.append("　【最新】");
            if (r.tag.equals(active)) label.append("　【手机现有】");

            Button row = new Button(this);
            row.setText(label.toString());
            row.setAllCaps(false);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmInstall(r); }
            });
            upList.addView(row);
        }
    }

    private void confirmInstall(final Upstream.Release r) {
        if (r.tag.equals(Upstream.activeVersion(this))) {
            Toast.makeText(this, "本机服务器已经在跑 " + r.tag, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("切换到上游版本 " + r.tag)
            .setMessage("从 GitHub 取该版本的源码包（约 7 MB），解包后本机服务器改跑这个版本。\n\n"
                + "素材不会重新下载，仍用安装包自带的那份。若两个版本跨度较大导致素材对不上，"
                + "可在「资源版本」里点「在线更新资源」补齐。")
            .setNegativeButton("取消", null)
            .setPositiveButton("下载并切换", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { installUpstream(r.tag); }
            })
            .show();
    }

    private void installUpstream(final String tag) {
        upRefresh.setEnabled(false);
        upStatus.setText("正在下载 " + tag + " …");
        Upstream.install(this, tag, new Upstream.InstallListener() {
            @Override public void onProgress(final int done, final int total, final String detail) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { upStatus.setText(tag + "：" + detail); }
                });
            }

            @Override public void onDone(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        upRefresh.setEnabled(true);
                        upStatus.setText(message);
                        if (!ok) { Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show(); return; }
                        // A running server is still serving the old tree; stop it so the next start picks
                        // up the new one — silently keeping the old version running would be a lie.
                        LocalServer srv = App.localServerOf(MainActivity.this);
                        if (srv.isRunning()) {
                            LocalServerService.stop(MainActivity.this);
                            srv.stop();
                            upStatus.setText(message + "\n本机服务器已停止，重新点「启动」即用新版本。");
                        }
                        refreshServerUi();
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void useEmbeddedGsrv() {
        String embedded = Upstream.embeddedVersion(this);
        String active = Upstream.activeVersion(this);
        if (embedded.equals(active)) {
            Toast.makeText(this, "已经在用安装包内置的版本（" + embedded + "）", Toast.LENGTH_SHORT).show();
            return;
        }
        LocalServer srv = App.localServerOf(this);
        if (srv.isRunning()) { LocalServerService.stop(this); srv.stop(); }
        Prefs.setActiveGsrv(this, "");
        refreshServerUi();
        upStatus.setText("已切回安装包内置版本：" + embedded
            + (srv.isRunning() ? "" : "\n重新点「启动本机服务器」生效。"));
        Toast.makeText(this, "已切回内置版本 " + embedded, Toast.LENGTH_SHORT).show();
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
        if (!LocalServer.runtimeSupported()) {
            Toast.makeText(this, "本机服务器需要 Android 7.0 及以上，当前系统 "
                + Build.VERSION.RELEASE + " 不支持", Toast.LENGTH_LONG).show();
            return;
        }
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

    /**
     * Puts the address other devices should open on the clipboard.
     *
     * <p>The preference order matters: the LAN address is the one worth sharing, the loopback address
     * is useful to nobody but this phone, and a scanned-in server is what the player was last looking
     * at. Whichever it copies is fed back in the toast so a wrong guess is immediately visible.
     */
    private void copyLanUrl() {
        LocalServer srv = App.localServerOf(this);
        String label, url = null;

        if (srv.isRunning()) {
            String ip = NetInfo.localIpv4();
            if (ip != null) {
                url = "http://" + ip + ":" + srv.port();
                label = "同网段设备连接地址";
            } else {
                url = srv.url();
                label = "本机地址（未连接局域网，同网段连不上）";
            }
        } else {
            String field = Prefs.normalizeServer(serverField.getText().toString());
            if (!field.isEmpty()) {
                url = field;
                label = "服务器地址";
            } else {
                label = null;
            }
        }

        if (url == null) {
            Toast.makeText(this, "没有可复制的地址：先填服务器地址，或启动本机服务器",
                Toast.LENGTH_LONG).show();
            return;
        }

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("卫戍协议 服务器地址", url));
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t, Toast.LENGTH_LONG).show();
            return;
        }
        // Android 13+ shows its own "copied" confirmation; a second toast on top of it is noise.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "已复制" + label + "：" + url, Toast.LENGTH_LONG).show();
        } else {
            status.setText("已复制" + label + "：" + url);
        }
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

        // Honest dead end beats a button that fails with a linker error nobody can act on.
        if (!LocalServer.runtimeSupported()) {
            srvStatus.setText("此系统不支持本机服务器\n"
                + "内嵌的 Node 运行时需要 Android 7.0（API 24）及以上，当前是 Android "
                + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）。\n"
                + "连别人的服务器不受影响，填地址直接进游戏即可。");
            srvToggle.setText("本机服务器不可用");
            srvToggle.setEnabled(false);
            srvUse.setEnabled(false);
            srvCopy.setEnabled(false);
            return;
        }
        srvToggle.setEnabled(true);
        srvUse.setEnabled(true);
        srvCopy.setEnabled(true);
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
        fullCheckButton.setEnabled(!busy);
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
