# TCL 净化 · ffalcontrol 衍生版

给 TCL / 雷鸟（FFALCON）Android TV 用的一体化工具：**一键关掉预装广告、遥测、推荐位与诱导入口，并把一个干净的第三方桌面直接装好、设为默认首页**。

一个 APK 全覆盖：桌面已经打进包里，装的时候不需要电脑、不需要 ADB、不需要 root、不需要再拷第二个安装包。断网也能用。

> 本项目是 [xnklerEd/ffalcontrol](https://github.com/xnklerEd/ffalcontrol)（MIT）的衍生版，来源与改动见 [来源说明](#来源说明)。非官方项目，与 TCL、雷鸟、FFALCON 无关。

## 下载

三个包功能完全一致，区别只是**内置了哪一套桌面**。按自己的口味选一个装，不用全装。

| 变体 | 内置桌面 | 桌面包名 | 体积 | 说明 |
|---|---|---|---|---|
| `ffalcontrol-0.2.0-cleanyourtv.apk` | CleanYourTV | `com.gathin.tvhome` | ~0.6 MB | 极简，纯电视直播/应用入口，无推荐流 |
| `ffalcontrol-0.2.0-aerial.apk` | Aerial | `app.orchard.tv` | ~3 MB | tvOS 风格，天气/时钟/自定义壁纸，[开源](https://github.com/pengchujin/aerial-tv) |
| `ffalcontrol-0.2.0-emotnui.apk` | Emotn UI | `com.oversea.aslauncher` | ~14 MB | 卡片式，可换主题，闭源免费软件 |

去 [Releases](../../releases) 下载。三个包签名相同、包名相同，可以互相覆盖安装（换桌面不用先卸载）。

## 兼容性

核心机制依赖厂商的 `com.tcl.providers.config`（ConfigProvider）与 `com.tcl.guard`（TGuard）两个系统组件，两者同时存在才有效。

**已确认可用**

| 项目 | 实测值 |
|---|---|
| 机型 / 平台 | TCL Smart TV Pro · `MT9655_4K_CN` · Android 14 |
| 固件 | `TCL/MT9655_4K_CN/MT9655:14/UTT2.250416.001/AU01:user/release-keys` |
| 系统组件 | `com.tcl.providers.config`（/system_ext/priv-app/ConfigProvider）· `com.tcl.guard`（/system_ext/app/TGuard）均在位 |
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
2. 打开「净化」，焦点默认在 **装内置桌面** 上。
3. 依次点：

| 按钮 | 做什么 |
|---|---|
| **装内置桌面** | 三步一条链：把内置桌面的包名写进 `InstallConfig.launcherStrategy` 白名单 → 从包内 resource 流式安装桌面 → 申请 HOME 角色，弹出「默认桌面」确认框 |
| **一键净化** | 写 `Guard_disable_App`（22 个包 / 41 个组件）+ 追加 `AppBootPolicy` 拦截条目（44 条）+ 维护 launcher 白名单。已写过会提示「已经是目标状态，无需重复」 |
| **设为默认桌面** | 单独再申请一次 HOME 角色（不支持时回落系统默认应用页） |
| **桌面状态** | 内置包是否已装 / 版本 / 当前默认桌面是谁 / 是否持有 HOME 角色 / 是否允许安装未知应用 |
| **放行桌面** | 只把内置桌面的包名加进安装白名单（换桌面变体后装不上时用） |
| **刷新诊断** | 只读：读回设备信息、配置行、应用状态 |
| **停止并退出** | 关掉本机 18742 端口服务并退出 |

4. **断电冷启动**（拔插头 30 秒再上电）。包级禁用由 `com.tcl.guard` 在开机广播时执行，不重启不生效。
5. 开机后如果厂商桌面抢到前台，按一次 **Home 键** 就回到新桌面（MT9655 的 `WindowManager` 在 `systemReady` 时硬编码拉起一次 `com.tcl.cyberui`，这是固件行为，工具改不了）。

## 它到底改了什么

全部通过厂商自己暴露的通道完成，不 hook、不刷机、不改系统分区。

| 配置行 | 谁消费它 | 效果 |
|---|---|---|
| `Guard_disable_App` | `com.tcl.guard`，开机 / 唤醒时执行 | 包级禁用（等价于 `pm disable-user`，状态记为 `enabled=2`），重启后保持 |
| `AppBootPolicy.activityBlackList` | TCL 框架，在 activity 启动前拦截 | 拦掉应用商店、桌面切换、卡片中心、专题页等入口。这是本机唯一可靠的组件级手段 |
| `InstallConfig.launcherStrategy.packages` | `PackageInstaller_tv` 的 `LauncherStrategy.allowInstall` | 第三方桌面（声明 HOME 的 APK）的安装放行白名单 |
| `bootStagedStartupConfig` | TCL 启动阶段 | 预留，本工具只在需要时读写 |

写入策略：每次写之前读原值、算 SHA-256、请求里带 `expected_sha256` 做 compare-and-set，写完再读回比对；不一致就失败回滚，不盲写。`/backups` 里保留每次改动前的原始内容。

内置桌面怎么装上的：APK 以 asset 形式放在包内，安装时由本应用自己的 `PackageInstaller` 会话（`openWrite()` 拿到的 FD）把字节直接灌进安装会话。字节不落盘、不经过 `system_server` 读文件，所以绕开了「`/sdcard` 是 FUSE 视图、系统安装器读不了」这个常见的卡点——这也是为什么这个包不需要存储权限、不需要 `adb install`。

## 风险与恢复

这个工具直接写系统配置，写错会让你点不开应用。

- 每次写入前都会备份原值，`GET /backups` 可以列出来，用 `/config-write` 把原文写回去
- 实在不行：恢复出厂设置（配置行会回到固件默认值，工具装的桌面会被清掉，需要重装一次）
- 恢复出厂**不会**回滚固件版本；OTA 升级可能收紧 ConfigProvider / guard 策略，升级后请重新跑一次诊断
- 已知限制：预装应用在只读分区上，厂商卸载白名单为空，`pm uninstall --user 0` 必然失败，**能禁用不能真删**；后台 service（如 `LauncherUpdateService`）既不在 `AppBootPolicy` 覆盖范围、guard 也会跳过，目前禁不掉

## 本机 HTTP 接口（可选）

服务只 bind `127.0.0.1:18742` 且拒绝非 loopback 来源，默认令牌写在包里便于脚本化；想改回运行时随机生成，构建时用 `TOKEN=random`。

```sh
TOK=0e8344e8b70b4f95e48e887c912a8f35ad1e4a37b92f290b031f8dca7b6224c7
curl -s -H "Authorization: Bearer $TOK" http://127.0.0.1:18742/health
curl -s -H "Authorization: Bearer $TOK" http://127.0.0.1:18742/launcher
curl -s -H "Authorization: Bearer $TOK" http://127.0.0.1:18742/snapshot
curl -s -X POST -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
     -d '{"level":"core","write":true}' http://127.0.0.1:18742/provision
```

端点：`/health` `/snapshot` `/launcher` `/install-config` `/app-boot-policy` `/provision` `/settings` `/settings-all` `/pkg/<包名>` `/backups` `/guard` `/config-write` `/cyberui-messages`。

## 构建

不需要 Gradle，`javac → d8 → aapt2 → zipalign → apksigner` 直出。

```sh
export JAVA_HOME=/path/to/jdk-17        # JDK 21 也测过
export ANDROID_HOME=/path/to/android-sdk   # 需要 platforms/android-34 + build-tools/35.0.0

./fetch-vendor.sh          # 取第三方桌面到 vendor/
./build.sh cyt             # 或 aerial / emotn
```

产物在 `dist/`。注意 build-tools 34.0.0 自带的 d8 在 JDK 21 上会 NPE 崩，用 **35.0.0**。

换一套内置桌面：把 APK 放进 `vendor/`，在 `build.sh` 的 `case` 里加一行（包名 + 显示名 + 路径），重编即可。

## 来源说明

| 来源 | 作用 | 许可 |
|---|---|---|
| [xnklerEd/ffalcontrol](https://github.com/xnklerEd/ffalcontrol) v0.1.4 | **上游主体**：ConfigProvider 读写、Guard / AppBootPolicy / InstallConfig 三行配置的写入与校验、本机 HTTP 接口 | MIT |
| [mmaotv/AerialToolbox](https://github.com/mmaotv/AerialToolbox) 的 CleanYourTV 3.0.0 | 内置桌面之一 | 见其仓库 |
| [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) v1.56 `aerial-standard` | 内置桌面之一，同时是本项目 UI 的视觉来源（Palette / Type / 药丸按钮） | MIT |
| Emotn UI 1.1.0.2 | 内置桌面之一 | 闭源免费软件，版权归 Emotn |

本仓库相对上游的改动：

1. **内置桌面**：新增 `BundledLauncher`，把桌面 APK 以 asset 打包，用 `PackageInstaller` 会话流式安装，并按变体生成 `LauncherSpec`（包名/显示名）
2. **默认桌面**：新增 `RoleManager.ROLE_HOME` 申请链路，装完直接弹确认框，不需要自己去系统设置里翻
3. **界面重做**：新增 `Ui`，按 aerial-tv 的 tvOS 深色配色重排——玻璃卡片、药丸按钮、聚焦放大、成功/失败语义色
4. **一键链**：`装内置桌面` 把「白名单 → 安装 → 申请 HOME 角色」串成一步
5. **构建**：一个源码树出三个变体；`TOKEN` 可选固定值，方便没有 adb 的环境用脚本驱动

上游的英文文档、机制分析与未解限制仍然适用，建议一并阅读。

## 免责声明

- 只在**你自己的设备**上使用
- 会修改系统配置，可能让桌面异常、应用打不开，最坏情况需要恢复出厂
- 不提供任何绕过付费内容、破解会员、解除授权的能力，也没有这个打算
- 因使用本工具导致的任何后果由使用者自行承担

## English

Derivative of [xnklerEd/ffalcontrol](https://github.com/xnklerEd/ffalcontrol) (MIT) that bundles a third-party TV launcher inside the APK, so a single sideloaded file debloats a TCL / FFALCON Android TV **and** installs plus enables a clean home screen — no PC, no ADB, no root, works offline.

Three variants differ only in the bundled launcher: **CleanYourTV**, **Aerial** (`aerial-standard`), **Emotn UI**. Built and checked against `MT9655_4K_CN` / Android 14 / `UTT2.250416.001 AU01`, where `com.tcl.providers.config` and `com.tcl.guard` are present. Expect it to work on other MT9655/MT9653 TCL and FFALCON sets shipping the same CyberUI/TGuard stack; it does nothing on non-MTK TCL TVs or Google TV builds without those components.

All the heavy lifting (ConfigProvider compare-and-set writes, Guard/AppBootPolicy/InstallConfig semantics, loopback HTTP API) comes from upstream. The additions here are: bundled-launcher install via a `PackageInstaller` session, `RoleManager` HOME request, an aerial-tv-styled UI, and a variant-aware build script.
