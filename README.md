# 卫戍协议：盟约 · 安卓客户端

> ## ⚠️ 这是**手机移植版**，不是原项目
>
> **原项目：[`sganggs/Stronghold-Protocol`](https://github.com/sganggs/Stronghold-Protocol)**
> —— 《明日方舟》限时玩法「卫戍协议：盟约」的浏览器自走棋塔防复刻，单人或 1–4 人联机合作。
>
> 游戏本体、服务端、客户端逻辑、玩法设计、数据与美术音频，**全部由上游项目完成**。
> 与之相关的一切著作权与维护责任，**归原作者 [@sganggs](https://github.com/sganggs) 及上游贡献者所有**。
>
> 本仓库只做了「把它包成一个 Android App」这一层工作：预装素材、WebView 外壳、资源增量更新、
> 局域网发现、内嵌 Node 运行时。**与上游没有隶属关系，也未经上游授权或认可。**
> 上游更新后请以**上游仓库**为准，本仓库只是跟随。

把 [`sganggs/Stronghold-Protocol`](https://github.com/sganggs/Stronghold-Protocol) 这个浏览器自走棋联机游戏包成
Android APK，解决三件事：

- **272 MiB 素材随包预装**，装完即玩、首次启动零下载；
- **可以在 App 里校对资源版本、按需增量在线更新**（只下变动的文件）；
- **手机自己就能当服务器**：内嵌 Node 运行时，启动后在局域网里广播一个地址，同网段的设备直接连。

产物：`StrongholdProtocol-2.3.apk` · 约 315 MiB · minSdk 21 / targetSdk 34 · v1+v2+v3 签名

> **这个客户端不含任何内置服务器地址，包名也不含任何域名。**
> APK 是会流传的，把服务器地址写死在里面，等于给搭建者的机器挂一块公网招牌。
> 包名用中性的 `com.strongholdprotocol.client`，服务器地址首次启动必须自己填。

---

## 和同类包的区别

同一件事目前至少有三条线在做。写在这里，是因为「你这个跟别人有啥区别」是必然会遇到的一问——
与其等人指出，不如自己说清楚，包括别人做得更好的地方。

|  | 本包 | [jingjiangze/Stronghold-Protocol](https://github.com/jingjiangze/Stronghold-Protocol) | [Starst796](https://github.com/Starst796/StrongholdProtocolClient) / [lilyco-42](https://github.com/lilyco-42/StrongholdProtocolClient) |
|---|---|---|---|
| 平台 | Android | Android | Windows 桌面 + Android + iOS |
| APK 体积 | 315 MiB | 518.7 MiB | 222 MiB（debug） |
| 素材本地化 | ✅ | ✅ | ✅ |
| 手机自己开服 | ✅ 内嵌真 Node，跑**未经修改**的上游 `server/index.js` | ✅ | ✅ 服务器引擎跑在 WebView 里 |
| 额外进程 | 有（Node，跑本机服务器时多占几十 MB 内存） | — | 无（同进程） |
| 手机上切换上游版本 | ✅ 列上游版本、标「最新」「手机现有」、一键切换 | 靠作者重新发包 | 靠作者重新发包 |
| 从上游 Release 整合包补素材 | ✅ | — | — |
| 大厅整合 / 签名服务器清单 / 下载站 / 完整发布流水线 | — | ✅ | — |
| 跨平台、P2P 直连探索 | — | — | ✅ |

**本包的取舍**：优先「跑未经修改的上游服务端」和「手机上直接从 GitHub 切上游版本，不用等作者发包」。
代价是多一个 Node 进程、包体比 Capacitor 那套大，界面也只做必要的事。

**别人更强的地方直说**：`jingjiangze` 那套功能更全（大厅整合、签名服务器清单、下载站、
测试候选 → 通过才发的发布流程）；`Starst796` / `lilyco-42` 那套跨了桌面与 iOS，还在做 P2P 直连。

---

## 三层素材，各管各的

| 层 | 内容 | 位置 | 何时更新 |
|---|---|---|---|
| **内置包 bundle** | 4028 个文件 · 272.1 MiB：干员立绘、Spine 骨骼、音效 BGM、字体、vendor 库 | **APK 里** | 重新发版 |
| **覆盖层 overlay** | 相对内置包变动过的文件 | 应用私有目录 | 「在线更新资源」，只下变动的 |
| **页面 + 联机** | `index.html` / `js/` / `css/` / `data/*.json` / `/ws` | **服务器** | 每次启动实时取 |

页面永远从服务器加载，客户端和协议版本天然同步；素材反过来——大、变得少，预装最划算。

## 校对版本 / 在线更新

内置包在构建时记下了每个文件的 **ETag**（`baseline/etags.tsv`，4028/4028 全覆盖）和当时的清单版本
（`baseline/info.txt`）。ETag 由服务端的 `size + mtime` 决定，本地编不出来，必须问服务器要。

```
校对版本   GET <更新地址>/data/assets.json      一个请求 676 KB，不下素材
           比对 hash：一样 →「已是最新」，结束
在线更新   对每个文件带 If-None-Match: <内置 ETag>
           没变的 → 304（几百字节），保留本地副本
           变了的 → 200，写入覆盖层
           服务器新增的 → 直接下载
```

上游加几个干员的那种更新，实际传输是**几 MB**，不是重下一次 272 MiB。

**更新地址**在启动页单独一栏，留空则用游戏服务器。指镜像站、CDN 或另一台服务器都行。

> 服务器侧不需要任何额外配置。版本信息就是服务器本来就在提供的 `/data/assets.json`。

## 局域网

### 扫描发现

「扫描局域网」会走 /24 网段，对每个地址探测 3 个候选端点（你已配置的那个 + 游戏默认的 `3000` + 反代常用的
`8443`），用 `/healthz` 的 JSON 特征识别服务器（`ok:true` + `app` + `version`），点结果即填入地址栏。
同时显示本机 Wi-Fi IP。

### 手机自己当服务器

APK 内嵌了一套 Bionic Node 运行时。它在 APK 里的布局是**刻意**的：

```
lib/arm64-v8a/   libnode.so + 传递闭包解出的 9 个库（含 31.6 MB 的 libicudata）   ≈ 89 MB
assets/gsrv/     上游服务端代码（server/ shared/ data/ public/）+ ws 模块        ≈ 9 MB
assets/payload.txt   208 条解包清单
```

Node 和它的库**不是**放在 `assets/` 里解包后再执行的，而是作为 `lib/*.so` 交给**安装器**放进
`nativeLibraryDir`。原因是 Android 不允许应用 `execve()` 自己**数据目录**里的文件——设备上实测：
文件模式已经是 0700、`canExecute()` 为 true，`execve` 仍然返回 EACCES，而同一台设备上另一个应用
用完全相同的 SELinux 标签执行同样的文件却没问题。原生库目录是平台唯一当作代码看待的位置，
只有放那里才稳定。附带两个好处：不需要运行时 chmod，而且运行时解包量从 97 MB 降到 9 MB。

首次启动把它们解到应用私有目录（约 97 MB），然后拉起：

```
node <filesDir>/localsrv/gsrv/server/index.js     HOST=0.0.0.0  PORT=3000
```

服务端默认就监听 `0.0.0.0:3000`，所以**没有改动上游一行代码**。局域网里任何人打开
`http://<你的IP>:3000` 就能加入；本机点「让本应用连本机」即走 `127.0.0.1:3000`。

**素材不需要解包**：局域网里每台跑这个客户端的设备，自己的 WebView 就会把 `/assets/*`、`/fonts/*`、
`/vendor/*` 拦下来从本地读，所以主机不必为它们发送 272 MiB。代价是**纯浏览器客户端**加入时素材会 404
（页面、JS、数据都正常，只是没有美术音频）。

### 四个踩过的坑

**1. Android 禁止应用执行自己数据目录里的文件。** 不是文件权限问题：设备上实测模式已是 0700、
`canExecute()` 为 true，`execve` 仍返回 EACCES。解决办法是把可执行文件放进 `nativeLibraryDir`
（作为 `lib/*.so` 随 APK 分发，由安装器解出来），那里是平台唯一当作代码的位置。

**2. 包管理器只提取 `lib/<abi>/` 下以 `.so` 结尾的文件，而链接器按精确 soname 查找。**
所以 `libcrypto.so.3` / `libicudata.so.78` 这类名字两头不讨好，必须改名成 `libcrypto.so` 之类。
只改文件名不够，还要把 ELF 里的 **`DT_NEEDED`、`DT_SONAME` 和 `.gnu.version_r` 的 verneed 三处**
一起改——只改 `DT_NEEDED` 的话，设备会报
`cannot find "libcrypto.so" from verneed[0] in DT_NEEDED list`。

**3. `File.setExecutable()` 会把文件 chmod 成 `0111`，而动态链接的 ELF 只有执行位跑不起来。**
在设备上实测的模式表：

```
mode 100 / 111 / 644  →  Permission denied (execve error 13)
mode 500 / 700 / 755  →  能跑
```

linker 必须能**读**这个二进制。（现在这条路已经绕开了——模式由安装器决定，不再运行时 chmod。）

**4. Termux 的 Node 把 `RUNPATH` 写死成 `/data/data/com.termux/files/usr/lib`（不存在）。**
拉起子进程时用 `LD_LIBRARY_PATH` 指向原生库目录覆盖它，`TMPDIR`/`HOME` 同理；
另外必须清掉 `LD_PRELOAD`，否则继承来的 termux-exec 会在 Node 启动前就把它搞死。

## 仓库里有什么

只放源码。**不含游戏素材，不含 APK，也不含 node 运行时与服务端代码**：

- `android/assets/mirror/**`（约 272 MiB 的游戏美术 / 音频）版权属鹰角，不在 GPL 范围内，
  且 GitHub 对单文件有 100 MB 限制。用 `build_bundle.py` / `refresh_bundle.py` 自行装配。
- `android/assets/{node,gsrv}/**` 不进仓库：`node` 是 Termux 的二进制，`gsrv` 是**上游 GPL 源码**，
  应当留在上游仓库而不是 vendor 到这里。用 `make_native_payload.py` 一键重建。
- `keys/` 签名密钥不提交：口令就写在 `build.sh` 里，公开等于谁都能签出"同一个 App"的 APK。
- `*.apk` 走 Release 分发，不塞进 git 历史。

版权与使用条件的完整说明见 [NOTICE.md](NOTICE.md)。

## 安装

```
/sdcard/Download/StrongholdProtocol-2.3.apk
```

自签名证书，系统提示"未知来源"是正常的；约 315 MiB，安装要等一会儿。

- **客户端**：要求 Android 5.0（API 21）以上。
- **本机服务器**：要求 **Android 7.0（API 24）以上**。内嵌的 Node 运行时是按 API 24 构建的
  （`libnode.so` 的 `.note.android.ident` 里写着 `API level 24`），并且**强引用**了
  `pthread_barrier_init` / `pthread_barrier_wait` / `pthread_barrier_destroy`、`getgrnam_r`、
  `getgrgid_r` 这些 **API 24 才引入**的 libc 符号。动态链接器在加载时就会解析全部强符号，
  所以 Android 5–6 上 `execve` 会直接以 `cannot locate symbol` 失败——它不是权限问题，加任何
  权限都没用。这类系统上 App 会把「启动本机服务器」置灰并说明原因，**连别人的服务器不受影响**。
  （顺带一提：`memfd_create` 是 API 30 才有的，但它只是**弱引用**，缺失可以容忍，不构成门槛。）
- **要求系统 WebView 为 Chrome/89 以上**（游戏用了 import maps）。版本过低时 App 会弹窗提示。

## 首次使用

1. **填服务器地址**（不预置任何地址），例如 `https://example.com:8443`，局域网填 `http://192.168.1.10:3000`。
   裸域名自动补 `https://`，**端口号要带上**。
2. **开始游戏**。素材已在包里，不需要先下载。
3. 想自己当主机：点「启动本机服务器」→ 首次解包约 8.6 MB（需 Android 7.0+）→ 状态显示
   `运行中 · 本机 http://127.0.0.1:3000` 与 `同网段设备连：http://192.168.x.x:3000`。
4. 出问题点「查看服务端日志」：进程退出码、`node` 的权限/大小、`LD_LIBRARY_PATH`、服务端自己的报错都在里面。

## 代码结构

```
android/
  AndroidManifest.xml
  java/com/strongholdprotocol/client/
    App.java                   Application，持有 AssetCache 与 LocalServer 两个单例
    Prefs.java                 服务器地址 / 更新地址 / 上次更新 / 已应用的版本
    AssetCache.java            内置包读取 + 覆盖层 + ETag 增量更新 + 版本校对
    GameWebViewClient.java     把 /assets /fonts /vendor /media 映射到覆盖层或内置包
    MainActivity.java          设置页（地址、版本校对、在线更新、扫描、本机服务器）
    GameActivity.java          横屏沉浸全屏 WebView
    NetInfo.java               本机局域网 IPv4 / 网段
    LanScanner.java            /24 扫描 + /healthz 指纹识别
    LocalServer.java           解包、拉起 node 子进程、日志环形缓冲、停止
    LocalServerService.java    前台服务保活（失败不影响服务端运行）
  res/                         布局、主题、图标（make_icons.py 生成）
```

## 重新构建

需要 Termux 侧工具链：`openjdk-21 aapt2 d8 apksigner android-tools`，外加一个 `android.jar`
（Google 官方 `platform-35_r01.zip` 里就有）。

```bash
# 1. 素材包（三选一，见下文）→ android/assets/
python3 build_bundle.py  <release.zip> https://your-server:8443 android/assets 24
python3 finalize_bundle.py android/assets

# 2. 本机服务器载荷
python3 make_native_payload.py android/assets /path/to/upstream-checkout

# 3. 打包
./build.sh

# 4. 校验
python3 audit_api.py out/classes platform-35.zip 21
```

素材包的三种装法：

- `build_bundle.py` —— 内容取自上游 release 整合包（GitHub CDN 快得多），**ETag 取自你要连的那台服务器**
  （它由该服务器的 size+mtime 决定，本地算不出来），再用服务器返回的 Content-Length 逐个核对大小、
  随机抽样 SHA-256 比对。推荐。
- `refresh_bundle.py` —— 已有素材包时刷新到服务器当前版本，**只下增量**（先 HEAD 全部，再只取变了的）。
- `fetch_bundle.py` —— 完全从服务器逐文件抓。逻辑最直白，但受限于服务器上行带宽
  （实测自建服务器单流约 60 KB/s，269 MiB 要一个多小时）。

`android/assets/baseline/info.txt` 记录的就是"这个 APK 出厂时的素材版本"，必须和抓取时的服务器一致——
`finalize_bundle.py` 会做这个断言，`refresh_bundle.py` 会自动更新它。

## 已验证

对成品 APK 实际跑过的检查（不是看代码猜的）：

- 签名 v1 / v2 / v3 全部 `Verifies`；`unzip -t` 全条目 **No errors detected**。
- 4271 个条目、未压缩条目的**对齐违规 0 个**、重复条目 0。
- `baseline/index.txt` 里 4028 条路径全部能在 APK 里取到；`payload.txt` 218 条同理，缺 0。
- 版本校对：内置 `1:7f118cf0f7bb` vs 服务器 `/data/assets.json` → 一致。
- 从 APK 里读出的素材与服务器逐字节比对：抽样 SHA-256 全部一致。
- 增量更新：带内置 ETag 请求抽样 10 个文件（含 `/media/` 音频路由）→ **10/10 返回 304**，零下载。
- **内嵌服务端在本机实际跑通**：`node server/index.js` 后 `/healthz`、`/`、`/js/main.js`、
  `/data/assets.json` 全部 200；`/assets/*`、`/vendor/*` 如设计般 404（由客户端 WebView 拦截）。
- API 级别审计：所有 `android.*` 引用在 minSdk 21 上均存在；3 处 API 26 调用列在 `audit_allow.txt`
  并注明都在 `Build.VERSION.SDK_INT >= 26` 守卫内。

## 已知限制

- 更新与本机服务器跑在应用进程内。息屏靠唤醒锁继续，但**从最近任务划掉应用会中断**；下次会接着来。
- 一次「在线更新」要对 4028 个文件各发一个条件请求，流量极小但按服务器响应速度可能要几分钟。
- 覆盖层是整文件替换，没有二进制差分。单张立绘级别没问题，上游若换整套 3D 棋盘那次会比较大。
- APK 约 372 MiB，**每次改 App 代码重新发版都是这个体积**（素材更新走增量，不受影响）。
- **手机当主机时，纯浏览器客户端拿不到美术音频**（页面与逻辑正常）。跑本客户端的设备不受影响。
- 只做了 Android，没有 iOS。

## 版权

- 原项目：[`sganggs/Stronghold-Protocol`](https://github.com/sganggs/Stronghold-Protocol)，**著作权归原作者及上游贡献者**。
- 本仓库自己的代码（`android/java/**`、`android/res/**`、各 `*.py`、`build.sh`、文档）以
  **GPL-3.0-or-later** 发布，全文见 [LICENSE](LICENSE)。
- 《明日方舟》及「卫戍协议」相关的全部名称、角色、美术、Spine 模型、界面、音乐音效、文本与游戏数据，
  版权归**上海鹰角网络科技有限公司**及其授权方（Yostar 等）所有，**不在 GPL 授权范围内**，
  本仓库也无权就这些内容向任何人授予任何权利。
- 本仓库**不包含**上述素材：见 [NOTICE.md](NOTICE.md) 与 `.gitignore`。

## 免责声明

- **本项目是第三方非官方移植，不是原项目，与上游作者无隶属关系、未获其授权或认可。**
- 本客户端按「原样」提供，**不附带任何明示或暗示的担保**，不保证可用、安全或服务不中断。
- **本仓库作者不承担任何责任**，包括但不限于：因使用或无法使用本客户端造成的设备损坏、数据丢失、
  账号封禁、服务中断、流量费用；以及因架设服务器、分发由本仓库构建的产物、或使用游戏素材而产生的
  任何法律后果。
- **仅供学习、研究与个人非商业娱乐。** 严禁任何形式的盈利，包括上架应用商店、付费下载、内置广告、
  以捐赠或赞助为门槛的分发等。
- 使用者需自行确认其使用方式（所连接的服务器、所分发的构建产物、所在地区法律）合规，
  并自行承担全部风险。
