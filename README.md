# TCL 净化 · 推送安装版

给 TCL / 雷鸟（FFALCON）Android TV 用的一体化工具：**一键关掉预装广告、遥测、推荐位，把干净的第三方桌面装好设为首页，并且能让它装进任何被厂商拦下来的 APK。**

不需要电脑、不需要 ADB、不需要 root。桌面已经打进包里，断网也能用。

📄 **图文介绍页（推荐先看这个）**：https://mmaotv.github.io/ffalcontrol-tcl/

> 本项目是 [xnklerEd/ffalcontrol](https://github.com/xnklerEd/ffalcontrol)（MIT）的衍生版，来源与改动见 [来源说明](#来源说明)。非官方项目，与 TCL、雷鸟、FFALCON 无关。


## 0.3.0 新增了什么

| 新能力 | 说明 |
|---|---|
| **推送安装** | 手机浏览器打开电视地址就能把 APK 发过去；也可以在电视上直接选包。发过去之后先判定会被哪条策略拦，再自动改掉那一处，最后走系统原生安装框 |
| **通用版** | `universal` 变体，不内置桌面，只留净化 + 推送安装，90 KB |
| **策略判定** | 把厂商的安装校验完整复刻到本地，**装之前**就告诉你"这个包会被哪条规则拦、我准备怎么处理" |
| **可回滚** | 每次配置改动都带 sha256 compare-and-set 和备份，装失败能自动撤销 |

## 下载

四个包签名相同、包名相同，可以互相覆盖安装，换桌面不用先卸载。**只装一个。**

| 变体 | 内置桌面 | 桌面包名 | 体积 |
|---|---|---|---|
| `ffalcontrol-0.3.0-cleanyourtv.apk` | CleanYourTV | `com.gathin.tvhome` | ~0.6 MB |
| `ffalcontrol-0.3.0-aerial.apk` | Aerial 1.56 | `app.orchard.tv` | ~3 MB |
| `ffalcontrol-0.3.0-emotnui.apk` | Emotn UI 1.1.0.2 | `com.oversea.aslauncher` | ~14 MB |
| `ffalcontrol-0.3.0-universal.apk` | **无**（自带推送安装） | — | ~90 KB |

下载：[Releases](../../releases)（原版），或 [介绍页直连镜像](https://mmaotv.github.io/ffalcontrol-tcl/)（GitHub Pages，国内可达）。

不知道选哪个：**已经装好桌面就选 universal**，还没装就挑一套喜欢的桌面，三个内置版里同样带推送安装。

## 为什么它装得上

TCL 在 `PackageInstaller_tv` 里塞了一套安装校验（`verifier` 机制）。我把那个 APK 反编译了，实际的判定逻辑是这样的：

```
PackageManager 发出 PACKAGE_NEEDS_VERIFICATION 广播（仅当安装带 verification id）
  └─ VerifierReciver
       ├─ 没有 system feature com.tcl.software.packageinstaller_install_control → 直接放行
       └─ 有（本机实测有）→ StrategyService → StrategyChain
            六个策略逐个跑，全过才放行，任一拒绝 → verifyPendingInstall(-1)
```

六个策略的真面目：

| 策略 | 判定 | 拦谁 |
|---|---|---|
| `launcherStrategy` | 只对声明 `MAIN` + `HOME` 的 APK 生效。包名在 `packages` 白名单，或签名等于当前默认桌面且 `metaData com.tcl.app.type == "com.tcl.ui"` → 放行 | **第三方桌面** |
| `blackListStrategy` | 包名 ∈ `packages` → 拒 | 名单内的包 |
| `overDueStrategy` | 同上（"过期"名单） | 名单内的包 |
| `thridStrategy` | **仅当 installer 是 `com.android.packageinstaller`** 时才检查来源 | 从系统安装器点装的第三方来源 |
| `pmStrategy` | `installFlag & 0x20`（`INSTALL_FROM_ADB`）→ 拒 | `adb install` |
| `safeStrategy` | 空实现，永远放行 | 不拦任何东西 |

由此得出三个不太直观的结论：

1. **你把 APK 拷到 U 盘、用文件管理器点开安装被拦，原因不是桌面策略，是 `thridStrategy`。** 因为这时 installer 是系统安装器，而发起方（文件管理器）既不是系统应用也不在白名单里。
2. **用本工具自己的 `PackageInstaller` 会话安装，`thridStrategy` 和 `pmStrategy` 都不触发。** installer 是 `app.ffalcontrol`，也不带 adb 标志。所以真正需要动的配置只有两处：桌面放行名单、两个拦截名单。
3. **`adb install` 未必能解决问题。** `pmStrategy` 明确拦 `INSTALL_FROM_ADB`，如果它处于启用状态，adb 会被直接判拒。

所以「推送安装」做的事很克制：**算清楚 → 只改那一条 → 走系统正常安装**，不去关闭任何全局开关。

## 推送安装怎么用

装好打开，焦点默认在「推送安装」上。三种入口：

**① 手机推送（最方便）**

点「推送安装」，屏幕上会显示 `http://<电视IP>:18743`。手机连同一个 WiFi，浏览器打开这个地址，选 APK 上传。传完电视上就出现在列表里了。

这个页面**只能收文件，不能安装、不能改任何设置**——装不装由电视前的人点。所以它不需要密码，暴露面也就到此为止。

**② 电视上直接选包**

点「选包安装」，走系统文件选择器，从 U 盘或下载目录里挑。不需要网络，也不需要第二台设备。

**③ 只放行、不装（预授权）**

点「预授权放行」，输入包名（形如 `com.example.app`）。它会把这个包名加进桌面放行名单、从拦截名单里移出。**这一步只放行，不会安装任何东西**——包名本身不含安装数据。

### 判定 → 安装的完整流程

```
收到包 → 解析包名/版本/是否桌面/签名/metaData
   → 本地复刻厂商三条判定，先告诉你「会被哪条策略拦、建议怎么改」
   → 你点确认
   → 改配置（sha256 CAS + 自动备份 + 读回校验）
   → 自己的 PackageInstaller 会话流式写入 → 系统确认框
   → 装完再查三项：能解析到吗 / 被禁用了吗 / 有可启动入口吗
```

最后那步很重要：**装上了但用不了，比装不上更让人困惑。** `com.tcl.guard` 会在开机时执行包级禁用，`AppBootPolicy.activityBlackList` 会拦 activity 启动，所以装完必须复查。

失败时如果判定是"被策略拦"，会自动把刚写的配置撤销回去；如果是你自己取消了安装，配置留着（下次更方便）。

### 改配置的克制

| 操作 | 影响面 | 默认 |
|---|---|---|
| 往 `launcherStrategy.packages` 加一项 | 仅该包名，仅桌面判定 | 开 |
| 从 `blackListStrategy` / `overDueStrategy` 移出一项 | 仅该包名 | 仅在命中时 |
| 关闭 `launcherStrategy.enable` | 该策略整体失效 | 关闭，需二次确认 |
| 关闭 `InstallConfig` 顶层 `enable` | **六个策略全失效** | 关闭，**不做** |

**只允许从黑名单里移除，绝不往黑名单里加**——加进去对我们没有任何用处，只会增加破坏面。

## 兼容性

核心机制依赖厂商的 `com.tcl.providers.config`（ConfigProvider）与 `com.tcl.guard`（TGuard）两个系统组件，两者同时存在才有效。

**已确认可用**

| 项目 | 实测值 |
|---|---|
| 机型 / 平台 | TCL Smart TV Pro · `MT9655_4K_CN` · Android 14 |
| 固件 | `TCL/MT9655_4K_CN/MT9655:14/UTT2.250416.001/AU01:user/release-keys` |
| 系统组件 | `com.tcl.providers.config`（/system_ext/priv-app/ConfigProvider）· `com.tcl.guard`（/system_ext/app/TGuard）均在位 |
| 安装校验 | `com.tcl.software.packageinstaller_install_control` = true（六策略表即由本机 APK 反编译得出）|
| 目标包 | 22 个待禁用包在本机全部存在（缺失 0） |

**预期可用，未逐个验证**

- 同平台 TCL 机型：MT9655 / MT9653 系列，Android 12–14，国内版（`*_CN`）固件
- 雷鸟 FFALCON 同代机型（本工具的样板机就是 鹤7 75R79A）
- TCL 集团旗下使用同一套 CyberUI / TGuard 固件的品牌机型
- 判断方法（不用装工具）：`getprop ro.board.platform` 是 `MT9655`/`MT9653`，且 `pm path com.tcl.guard` 有输出

**不可用**

- 非 MT96 平台的 TCL 电视（如 Amlogic、海思方案的旧机型）
- 海外版 Google TV 固件（没有 ConfigProvider / guard）
- 其它品牌（小米、海信、华为）——机制完全不同，别装

## 用法

1. 把 APK 拷到 U 盘或电视的下载目录，用电视上的文件管理器装上（首次会问「允许安装未知应用」）。
2. 打开「净化」，焦点默认在 **推送安装** 上。

| 按钮 | 做什么 |
|---|---|
| **推送安装** | 显示局域网上传地址 + 列出待安装的包，选一个开始 |
| **选包安装** | 系统文件选择器，直接挑本机的 APK |
| **预授权放行** | 只把包名写进白名单，不安装 |
| **装内置桌面** | 三步一条链：写白名单 → 从包内流式安装桌面 → 申请 HOME 角色（universal 变体没有这个按钮）|
| **一键净化** | 写 `Guard_disable_App`（22 个包 / 41 个组件）+ 追加 `AppBootPolicy` 拦截条目 + 维护 launcher 白名单 |
| **设为默认桌面** | 单独申请一次 HOME 角色（不支持时回落系统默认应用页）|
| **桌面状态** | 内置包是否已装 / 版本 / 当前默认桌面 / 是否持有 HOME 角色 |
| **放行桌面** | 只把内置桌面的包名加进安装白名单 |
| **刷新诊断** | 只读：设备信息、配置行、应用状态 |
| **停止并退出** | 关掉本机服务并退出 |

3. **断电冷启动**（拔插头 30 秒再上电）。包级禁用由 `com.tcl.guard` 在开机广播时执行，不重启不生效。
4. 开机后如果厂商桌面抢到前台，按一次 **Home 键** 就回到新桌面（MT9655 的 `WindowManager` 在 `systemReady` 时硬编码拉起一次 `com.tcl.cyberui`，这是固件行为，工具改不了）。

## 它到底改了什么

全部通过厂商自己暴露的通道完成，不 hook、不刷机、不改系统分区。

| 配置行 | 谁消费它 | 效果 |
|---|---|---|
| `Guard_disable_App` | `com.tcl.guard`，开机 / 唤醒时执行 | 包级禁用（等价 `pm disable-user`），重启后保持 |
| `AppBootPolicy.activityBlackList` | TCL 框架，activity 启动前拦截 | 拦掉应用商店、桌面切换、卡片中心等入口 |
| `InstallConfig.strategies[launcherStrategy].packages` | `PackageInstaller_tv` 的 `LauncherStrategy.allowInstall` | 第三方桌面的安装放行名单 |
| `InstallConfig.strategies[blackListStrategy / overDueStrategy].packages` | 同上，`BlackStrategy` / `OverDueStrategy` | 拦截名单，移出即解封 |
| `bootStagedStartupConfig` | TCL 启动阶段 | 预留 |

写入策略：每次写之前读原值、算 SHA-256、请求里带 `expected_sha256` 做 compare-and-set，写完再读回比对；不一致就失败、不盲写。`/backups` 里保留每次改动前的原始内容。

**为什么不需要存储权限、也不需要 adb**：APK 字节以流的形式经 `openWrite()` 的 FD 直接进安装会话，不落盘、不经过 `system_server` 读文件，所以绕开了「`/sdcard` 是 FUSE 视图、系统安装器读不了」这个卡点。

## 风险与恢复

这个工具直接写系统配置，写错会让你点不开应用。

- 每次写入前都会备份原值，`GET /backups` 可以列出来，用 `/config-write` 把原文写回去
- 实在不行：恢复出厂设置（配置行回到固件默认值，工具装的桌面会被清掉，需要重装一次）
- 恢复出厂**不会**回滚固件版本；OTA 升级可能收紧策略，升级后请重新跑一次诊断
- 已知限制：预装应用在只读分区，厂商卸载白名单为空，`pm uninstall --user 0` 必然失败，**能禁用不能真删**；后台 service（如 `LauncherUpdateService`）既不在 `AppBootPolicy` 覆盖范围、guard 也会跳过，目前禁不掉
- 推送安装仍然会有**一次**系统安装确认框。普通应用没有 `INSTALL_PACKAGES` 权限，这一步免不了，也**故意不免**——装不装永远由你点

## 本机接口

两个端口，能力刻意分开：

| 端口 | 绑定 | 认证 | 能力 |
|---|---|---|---|
| `18742` | `127.0.0.1` | 构建期令牌 | 完整：诊断、写配置、推送分析、触发安装 |
| `18743` | `0.0.0.0` | 无（手机浏览器没法带 Bearer） | **只能收文件**：上传页 + `/upload` + `/jobs`，不能装、不能写配置 |

```sh
TOK=0e8344e8b70b4f95e48e887c912a8f35ad1e4a37b92f290b031f8dca7b6224c7
B=http://127.0.0.1:18742

curl -s -H "Authorization: Bearer $TOK" $B/health
curl -s -H "Authorization: Bearer $TOK" $B/push/info
curl -s -H "Authorization: Bearer $TOK" $B/security/policies

# 推一个包上去（body 就是 APK 字节）
curl -s -X POST -H "Authorization: Bearer $TOK" \
     --data-binary @some.apk "$B/push?name=some.apk"

# 看它会被哪条策略拦
curl -s -X POST -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
     -d '{"staged_id":"p1700000000000-a1b2"}' $B/push/analyze

# 改配置并安装（异步，用 /push/jobs/<id> 轮询）
curl -s -X POST -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
     -d '{"staged_id":"p1700000000000-a1b2","patch":true,"rollback_on_failure":true}' $B/push/install

# 只放行某个包名
curl -s -X POST -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
     -d '{"package":"com.example.app","write":true}' $B/push/preauthorize
```

端点：`/health` `/snapshot` `/launcher` `/security/policies` `/push/info` `/push/jobs` `/push/jobs/<id>` `/push` `/push/analyze` `/push/install` `/push/preauthorize` `/push/delete` `/install-config` `/app-boot-policy` `/provision` `/settings` `/settings-all` `/pkg/<包名>` `/backups` `/guard` `/config-write` `/cyberui-messages`

想改回运行时随机生成令牌：构建时 `TOKEN=random`。

## 构建

不需要 Gradle，`javac → d8 → aapt2 → zipalign → apksigner` 直出。

```sh
export JAVA_HOME=/path/to/jdk-17        # JDK 21 也测过
export ANDROID_HOME=/path/to/android-sdk   # 需要 platforms/android-34 + build-tools/35.0.0

./fetch-vendor.sh              # 取第三方桌面到 vendor/
./build.sh cyt                 # 或 aerial / emotn / universal
```

产物在 `dist/`。注意 build-tools 34.0.0 自带的 d8 在 JDK 21 上会 NPE 崩，用 **35.0.0**。

换一套内置桌面：把 APK 放进 `vendor/`，在 `build.sh` 的 `case` 里加一行（包名 + 显示名 + 路径），重编即可。

### 自测

判定逻辑和 AXML 解析器都不依赖 Android，可以直接在 JVM 上验：

```sh
javac -d build/test src/app/ffalcontrol/AxmlReader.java \
      src/app/ffalcontrol/SecurityGate.java test/app/ffalcontrol/SelfTest.java
java -cp build/test app.ffalcontrol.SelfTest vendor/*.apk
```

会打印每个 APK 的包名、版本、`declaresHome`，再跑 16 条策略判定场景（第三方桌面被拦、白名单放行、TCL 签名放行、缺 metaData 仍被拦、黑名单命中、系统安装器触发 `thridStrategy`、本工具路径不触发、adb 触发 `pmStrategy`、patch 计划只加不删……）。

## 来源说明

| 来源 | 作用 | 许可 |
|---|---|---|
| [xnklerEd/ffalcontrol](https://github.com/xnklerEd/ffalcontrol) v0.1.4 | **上游主体**：ConfigProvider 读写、Guard / AppBootPolicy / InstallConfig 配置的写入与校验、本机 HTTP 接口 | MIT |
| [mmaotv/AerialToolbox](https://github.com/mmaotv/AerialToolbox) 的 CleanYourTV 3.0.0 | 内置桌面之一 | 见其仓库 |
| [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) v1.56 `aerial-standard` | 内置桌面之一，同时是 UI 的视觉来源（Palette / Type / 药丸按钮） | MIT |
| Emotn UI 1.1.0.2 | 内置桌面之一 | 闭源免费软件，版权归 Emotn |
| `PackageInstaller_tv.apk`（本机系统文件） | 六策略的判定逻辑由它反编译得出，未复制其代码 | 仅作分析依据 |

本仓库相对上游的改动：

1. **内置桌面**（0.2.0）：`BundledLauncher` 把桌面 APK 以 asset 打包，用 `PackageInstaller` 会话流式安装；按变体生成 `LauncherSpec`
2. **默认桌面**（0.2.0）：`RoleManager.ROLE_HOME` 申请链路，装完直接弹确认框
3. **界面**（0.2.0）：`Ui` 按 aerial-tv 的 tvOS 深色配色重排——玻璃卡片、药丸按钮、聚焦放大、语义色
4. **策略判定**（0.3.0）：`SecurityGate`（纯逻辑，可 JVM 测）+ `AxmlReader`（自写二进制清单解析，因为 `PackageInfo` 给不了 intent-filter）+ `ApkAnalyzer`
5. **推送安装**（0.3.0）：`PushInstall` 状态机、`PushServer` 局域网上传、`ApkInstaller` 抽出公共会话写入、`PolicyGuard` 名单手术与回滚
6. **构建**（0.3.0）：一个源码树出四个变体；`TOKEN` 可选固定值，方便没有 adb 的环境用脚本驱动

上游的英文文档与机制分析仍然适用，建议一并阅读。

## 免责声明

- 只在**你自己的设备**上使用
- 会修改系统配置，可能让桌面异常、应用打不开，最坏情况需要恢复出厂
- 本工具做的是**绕过厂商对安装来源的额外限制**，不是绕过 Android 的安装确认，也不是绕过任何内容版权/会员校验——后者不做，也没打算做
- 因使用本工具导致的任何后果由使用者自行承担

## English

Derivative of [xnklerEd/ffalcontrol](https://github.com/xnklerEd/ffalcontrol) (MIT) for TCL / FFALCON Android TVs. A single sideloaded APK debloats the set, installs and enables a clean third-party launcher, **and** can install any APK that the vendor's installer refuses — no PC, no ADB, no root, works offline.

The vendor ships a `verifier` inside `PackageInstaller_tv` with six strategies. Reverse-engineered from the actual APK on `MT9655_4K_CN` / Android 14: only third-party launchers (declaring `MAIN`+`HOME`), blacklisted packages, installs originating from the system installer, and `adb install` are blocked — plain sideloads are not. This tool detects which rule a given APK hits, patches exactly that one entry in `InstallConfig` (with sha256 compare-and-set and backup), then hands off to the normal system install dialog. Silent install is deliberately not implemented.

Four variants differ only in the bundled launcher: **CleanYourTV**, **Aerial**, **Emotn UI**, plus **universal** (no bundled launcher, 90 KB).
