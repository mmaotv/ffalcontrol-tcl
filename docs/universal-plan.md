# 通用版（Universal）方案 — 推送安装 + 任意三方可控放行

> 目标机型：TCL / 雷鸟（FFALCON）Android TV，MT9655_4K_CN 平台（`ro.build.fingerprint = TCL/MT9655_4K_CN/MT9655:14/UTT2.250416.001/AU01`）
> 本文只做设计，不含代码改动。所有结论均来自本机实证，证据来源标注在每节末尾。

---

## 0. 一句话定位

通用版不是"破解厂商限制"，而是**把厂商那道看不见的安装校验，变成一个可见、可控、可回滚的操作台**：用户把包丢进来（或选进来），工具先算出"这个包会被哪条规则拦"，自动把该改的那一项改掉，再走系统原生安装确认框完成安装。

**不做静默安装。** 系统确认框一定保留 —— 普通应用没有 `INSTALL_PACKAGES` 权限，技术上本来也做不到静默，这一点正好让工具的性质停留在"用户对自己设备的处置权"，而不是一个装任何东西的后门。

---

## 1. 实证的拦截模型（本节是全方案的地基）

### 1.1 触发链路

```
PackageManagerService（带 verificationId 的安装）
  └─ 广播 android.intent.action.PACKAGE_NEEDS_VERIFICATION
       └─ PackageInstaller_tv / VerifierReciver.onReceive
            ├─ !hasSystemFeature("com.tcl.software.packageinstaller_install_control")
            │     → verifyPendingInstall(id, 1)  ← 直接放行
            └─ 否则 startForegroundService(StrategyService)
                  └─ InstallStrategyManager.setUpStrategies(PackageData)
                       ├─ InstallConfig 顶层 isEnable()==false
                       │     → verifyPendingInstall(id, 1)  ← 直接放行，策略跑都不跑
                       └─ 否则 StrategyChain：逐个跑，全 true 才放行；任一 false → (-1) 拒绝
```

本机实测：`pm has-feature com.tcl.software.packageinstaller_install_control` → **true**。这是只读 system feature（在 `/system/etc/permissions` 或 framework 里），改不了，所以校验一定是启用的。

**证据**：`VerifierReciver.java`、`StrategyService.java`、`InstallStrategyManager.java`（反编译 `/system/priv-app/PackageInstaller_tv/PackageInstaller_tv.apk`）

### 1.2 六个策略的真实判定

策略由 `InstallConfig` 里 `strategies[]` 数组的 `name` 字段派发（`InstallStrategyManager.getOrderedStrategies` 的 switch），共六种：

| name | 判定逻辑 | 拦谁 | 对本工具发起的安装 |
|---|---|---|---|
| `safeStrategy` | `onCheckEnd(true)` 硬编码，空实现 | 不拦任何东西 | 无影响 |
| `launcherStrategy` | 只对「含 `MAIN` + `HOME`」的 APK 生效；包名在 `packages` 白名单，或签名 == 当前默认 HOME 签名且 `metaData com.tcl.app.type == "com.tcl.ui"` → 放行，否则拒 | **第三方桌面** | **会拦** → 必须写白名单 |
| `blackListStrategy` | 包名 ∈ `packages` → 拒 | 黑名单包 | **会拦** → 需从名单剔除 |
| `overDueStrategy` | 同 `blackListStrategy`（"过期"名单） | 指定包 | 同上 |
| `thridStrategy` | 仅当 `installerPackageName == "com.android.packageinstaller"` 时才检查：origin 是系统应用 / 是自己 / 在白名单 → 放行，否则拒 | 从系统安装器 UI 直接点装的第三方来源 | **不拦**（我们的 installer 是 `app.ffalcontrol`） |
| `pmStrategy` | `installFlag & 0x20`（`INSTALL_FROM_ADB`）→ 拒 | `adb install` | **不拦**（非 adb 路径） |

### 1.3 三个必须纠正的认知

**① "开 ADB 就能装"在这台机器上可能是错的。**
`PmStrategy` 明确拦 `INSTALL_FROM_ADB`（flag `0x20`）。如果 `pmStrategy` 的 `enable` 为 true，`adb install` 会被 verifier 直接判 -1。我之前一直把"开 ADB"当作兜底方案，这个假设需要实测推翻或确认。

**② 用户从文件管理器点装被拦，原因不是桌面策略，是 `thridStrategy`。**
用户把 apk 拷到 U 盘/下载目录、用文件管理器点开安装时，`installerPackageName == com.android.packageinstaller`，`originPackageName` 是文件管理器（非系统、非自身、不在白名单）→ 拒。所以"装普通软件也被拦"的真相在这里，跟桌面无关。

**③ 用我们自己的 PackageInstaller 会话安装，天然绕过其中两条。**
`thridStrategy` 只查 installer 为 `com.android.packageinstaller` 的情况；`pmStrategy` 只查 adb flag。我们走 `app.ffalcontrol` 自己的 session，两条都不触发。

**所以通用版真正需要动的只有两处**：`launcherStrategy.packages`（桌面类）和 `blackListStrategy`/`overDueStrategy` 名单（命中时剔除）。**不需要关闭任何全局开关**，这是低风险方案能成立的前提。

---

## 2. 通用版的能力设计

### 2.1 三个入口（都要，各解决不同场景）

| 入口 | 形态 | 解决什么 | 优先级 |
|---|---|---|---|
| **A. 本地选包** | `ACTION_OPEN_DOCUMENT`，mime `application/vnd.android.package-archive` | 电视上从 U 盘 / 下载目录直接选。**不需要网络、不需要第二台设备** | 必做 |
| **B. 局域网推送** | 复用 `127.0.0.1:18742`，新增 `POST /push`（body 为 apk 字节）；UI 显示局域网地址 + 二维码 | 手机/电脑直接把包发到电视，不用拷 U 盘 | 必做 |
| **C. 手动输入包名** | 输入框 + `POST /push/preauthorize` | 只做"预授权放行"，不能装软件（包名本身不含安装数据）。用于：先给某包放行、再在别处装 | 必做，但要写清语义 |

入口 A 其实已经覆盖 90% 场景 —— 这是必须说清楚的：**用户不必依赖"推送"，选包就够了**。推送的价值只在"不想拷 U 盘"。

### 2.2 安装前置解析（本地复刻厂商判定，安装前就给结论）

拿到 APK 后，落 app 私有目录 `getCacheDir()/push-<uuid>.apk`（必须落盘，因为 `LauncherStrategy.isLauncher()` 是用 `PackageParser.parsePackage(file)` 读文件判定的，我们自己预演也需要文件路径），然后：

```
PackageManager.getPackageArchiveInfo(path,
    GET_ACTIVITIES | GET_META_DATA | GET_SIGNATURES | GET_PERMISSIONS)
```

提取并本地复刻三条判定：

| 判定项 | 提取方式 | 对应策略 |
|---|---|---|
| 是否桌面 | activities 里是否存在 `MAIN` + `HOME` 同时出现的 intent-filter | `launcherStrategy` |
| 是否 TCL 官方桌面 | 签名 == 当前默认 HOME 签名 && `metaData["com.tcl.app.type"] == "com.tcl.ui"` | `launcherStrategy` 的 `isTclLauncher` 分支 |
| 是否命中黑名单 | 包名 ∈ `blackListStrategy.packages` ∪ `overDueStrategy.packages` | 两个名单策略 |

产出「预演结论」：`{ 包名, 版本, 是否桌面, 命中哪条规则, 建议动作 }`，在真正发起安装**之前**显示给用户。

这是通用版最有价值的部分 —— 把"装了才失败、失败还不知道为什么"变成"装之前就知道会怎样"。

### 2.3 处理流水线（状态机）

```
IDLE
 └─ RECEIVED       收到字节 / 选中文件 / 输入包名
     └─ STAGED     落到 cache，算出 sha256、字节数
         └─ ANALYZED   解析包名/版本/HOME/签名 → 产出预演结论
             ├─ PASS           无需改动 → INSTALLING
             └─ NEEDS_PATCH    需改配置
                  └─ PATCHED    写 InstallConfig（sha256 CAS + 备份 + 语义比对）→ 读回校验
                      └─ INSTALLING
                          ├─ PENDING_USER     系统确认框弹出（STATUS_PENDING_USER_ACTION）
                          │     └─ COMMITTED / USER_CANCELED
                          ├─ COMMITTED        安装成功 → post-check（能否解析到 activity / 是否被 guard 禁用）
                          └─ FAILED           带失败码 + 已回滚标记
```

关键设计点：

- **先改配置再装**。顺序反了就是"装了被拒 → 改了再装"，会留下失败记录，且某些场景下 session 已作废要重建。
- **每次写配置都要 CAS**。沿用现有 `InstallConfigControl` 的 `expected_sha256` 机制：不匹配就拒写并回显当前值，避免覆盖掉 OTA 刚下发的配置。
- **写后必须读回比对**。现有实现已经在做（`observed_sha256` + `persistence_verified`），沿用。
- **失败要自动回滚名单**（可选，默认开）：如果装上失败且原因是我们的改动引起，把刚加的包名撤掉，恢复原状。
- **安装后检查**：装上了不等于能用。`com.tcl.guard` 可能在新一轮开机时把它 disable 掉，`AppBootPolicy.activityBlackList` 可能拦它启动。所以 post-check 要查三项：能解析到 `PackageManager` 条目 / 应用未处于 disabled 状态 / 有可启动 activity。

### 2.4 配置写入的最小作用域

现有 `InstallConfigControl` 只允许改 `launcherStrategy` 的 `packages` / `enable`，且用 `render()` 做深度语义比对，确保其它 strategy 一字不动。通用版要扩展，但**必须保持同样的克制**：

| 需求 | 允许改的字段 | 是否新增能力 |
|---|---|---|
| 放行桌面 | `strategies[launcherStrategy].packages` += 包名 | 已实现 |
| 放行命中黑名单的包 | `strategies[blackListStrategy].packages` -= 包名<br>`strategies[overDueStrategy].packages` -= 包名 | **新增**，只允许"移除"，不允许"新增"进黑名单 |
| 一键全放行（高级） | `InstallConfig` 顶层 `enable = false`，或 `launcherStrategy.enable = false` | **新增**，默认关闭，需二次确认 |

**为什么"只允许移除不允许添加"**：往黑名单里加包名对我们没有任何用处，只增加破坏风险。能力面收窄到够用即可。

### 2.5 手动输入包名的语义（必须写清楚，避免误解）

输入包名**不能装软件**。它能做的是：
1. 把该包名写进 `launcherStrategy.packages` 预授权（之后无论从哪装都不拦）
2. 把该包名从 `blackListStrategy` / `overDueStrategy` 里剔除
3. 查询该包名当前会不会被拦（纯只读）

UI 上文案必须是「预授权放行」而不是「安装」，否则用户会以为输入包名就能装上。

---

## 3. 接口设计（在现有 18742 上扩展）

沿用现有风格：`Authorization: Bearer <token>`、JSON 进出、只绑 loopback。

| 方法 | 路径 | 作用 |
|---|---|---|
| `POST` | `/push?name=xxx.apk` | body 直接是 APK 字节。落到 cache，返回 `{staged_id, bytes, sha256}` |
| `POST` | `/push/analyze` | `{staged_id}` → 预演结论（包名/版本/HOME/命中规则/建议动作） |
| `POST` | `/push/install` | `{staged_id, patch:true|false, dry_run:true}` → 改配置(可选)→建 session→流式写→commit，返回逐步结果 |
| `GET` | `/push/jobs` | 列出当前与历史任务及状态 |
| `GET` | `/push/jobs/<id>` | 单个任务详情（含失败码与回滚状态） |
| `POST` | `/push/preauthorize` | `{package}` → 只做配置预授权，不装 |
| `GET` | `/security/policies` | 六个策略的 enable + 各名单内容（只读快照） |
| `POST` | `/security/policies` | `{action:"remove_from_blacklist"|"set_launcher_enable"|..., expected_sha256}` → 带 CAS 的改动 |

`dry_run` 默认 true，必须先看 diff 才写。这与现有 `/install-config` 的行为一致。

---

## 4. 失败码 → 对策对照表

安装回执里能拿到的信息有限，所以这张表是把"黑盒失败"映射成"具体动作"的关键：

| 现象 | 真实原因 | 对策 |
|---|---|---|
| `STATUS_FAILURE_BLOCKED` | verifier 判 -1（策略拒） | 走预演的规则判定：桌面→加白名单；黑名单→剔除；都不是→提示可能是签名/来源类，给出策略快照让用户判断 |
| `STATUS_FAILURE_CONFLICT` | 签名不同 / 版本降级 / 已存在同名 | 提示需先卸载。**注意**：卸载也需权限，普通 app 只能卸载自己装的 |
| `STATUS_FAILURE_INVALID` | APK 损坏、abi 不匹配、minSdk 过高 | 回显 `minSdk / targetSdk / abi` 供判断 |
| `STATUS_FAILURE_INCOMPATIBLE` | 与设备不兼容 | 同上 |
| `STATUS_PENDING_USER_ACTION` | 正常，等用户点确认 | 取出 `EXTRA_INTENT` 用 Activity 拉起，等第二次回执 |
| 装完能解析但点不开 | `AppBootPolicy.activityBlackList` 拦启动，或被 guard disable | post-check 三项，命中则走 `/app-boot-policy` 与 guard 的恢复流程（已有能力） |
| 装完重启后消失 | `Guard_disable_App` 在 `BOOT_COMPLETED` 执行了禁用 | 需同步处理 guard 名单（该执行是开机时，所以改完必须提示冷启动） |

最后两条是现有三个变体也存在的隐患，通用版必须覆盖 —— 因为"装上了但用不了"比"装不上"更让人困惑。

---

## 5. 风险分级与默认值

| 操作 | 影响面 | 默认 | 回滚 |
|---|---|---|---|
| 加 `launcherStrategy.packages` 一项 | 仅该包名，仅桌面判定 | 开 | 有备份，可精确撤 |
| 从 blackList/overDue 剔除一项 | 仅该包名 | 开（仅在命中时） | 有备份，可精确加回 |
| `launcherStrategy.enable=false` | 该策略整体失效（所有第三方桌面放行） | **关**，需二次确认 | 可恢复 |
| `InstallConfig` 顶层 `enable=false` | **六个策略全失效**，黑名单/来源校验一起没了 | **关**，需二次确认，UI 标红 | 可恢复，但有窗口期 |

顶层 `enable=false` 要明确告知：这是把整道门拆掉，不是给一把钥匙。默认不走这条路。

---

## 6. 必须先实测的三件事（决定实现复杂度）

这三条如果结论不同，实现方式差别很大，所以在动手前先验：

1. **Session 安装到底带不带 verificationId？**
   `VerifierReciver` 只在收到 `PACKAGE_NEEDS_VERIFICATION` 时干活，而这个广播只在安装携带 verification 参数时发出。如果 `PackageInstaller.Session` 路径默认不带 id，那么**整套校验对我们就根本不触发**，通用版会退化成"选包→装"这么简单；如果 TCL 改过 `PackageInstallerService` 让所有 session 都带，就必须老实写白名单。
   验证方式：用 0.2.0-local 装一个非桌面包，看 `STATUS_*` 是否出现 `BLOCKED`，以及 logcat 里有没有 `verifier` 相关输出（需 READ_LOGS，Termux 拿不到，所以在应用内加调试记录）。

2. **`pmStrategy` 的 `enable` 是不是 true？**
   这决定"开 ADB"是否还是有效兜底。读 `/security/policies` 即可。

3. **写 InstallConfig 后是即时生效还是要冷启动？**
   `InstallConfigManager` 通过 `IConfigGrabber` 读，并注册了 `IConfigObserver`（`registerOnlineConfig`），`InstallConfigData` 是单例共享对象 —— 看上去**改完即时生效**，不必重启。但这只是静态推断，要实测：改完后立刻装一次，看是否还拒。这一条如果成立，体验会好很多（不用每次都拔插头）。

---

## 7. 版本与产物规划

### 7.1 变体策略

| 变体 | 内置桌面 | 推送安装 | 体积 |
|---|---|---|---|
| `cyt` / `aerial` / `emotn` | 各自内置 | **也加上** | 现有 + ~30KB |
| `universal` | 无 | 有 | ~75KB |

判断：推送安装做成**公共模块**，四个变体都编进去。理由：已经装了 cyt/aerial 变体的用户同样会遇到"想装别的软件"的场景，没道理让他们为了这个再装第二个包。`universal` 只是"不带桌面"的那个，给已经有桌面的用户。

### 7.2 文件清单

```
src/app/ffalcontrol/
  PushInstall.java        新增  入口/状态机/任务表
  ApkAnalyzer.java        新增  解析 + 本地复刻厂商判定（纯逻辑，可 JVM 测）
  PolicyGuard.java        新增  六个策略的名单手术（CAS + 备份 + 语义比对）
  PushHttp.java           新增  /push 系列端点（或并入 MainActivity 路由）
  BundledLauncher.java    改造  与 PushInstall 共用 session 安装的底层
  InstallConfigControl.java 扩展 增加 blackList/overDue 的移除能力，保持 render() 比对
  MainActivity.java       改造  第三行 UI：推送安装 / 选包安装 / 预授权
build.sh                  改造  增加 universal 变体；LauncherSpec 支持"无内置桌面"
```

`ApkAnalyzer` 刻意做成不依赖 Android 运行时（只吃字节 + 一个 `PackageInfo` 摘要），这样可以像之前验证 phone 端那样，用 JVM 直接跑真实判定逻辑，不用每次上电视试。

---

## 8. UI 改动（沿用 aerial-tv 风格）

现有界面是两行按钮。通用版加第三行：

```
┌────────────────────────────────────────┐
│  净化 · 通用版           [策略快照 chip] │
├────────────────────────────────────────┤
│  一键净化   恢复    停止并退出            │
│  装内置桌面  桌面状态  设为默认桌面        │  ← universal 变体这行隐藏
│  推送安装   选包安装   预授权放行          │  ← 新增
├────────────────────────────────────────┤
│  结果区：预演结论 / 逐步进度 / 失败码      │
└────────────────────────────────────────┘
```

「推送安装」点开后是一个二级页：局域网地址 + 二维码 + 已接收文件列表 + 每个文件一行「安装 / 查看判定 / 删除」。
「选包安装」直接弹 SAF，选完落到同一条流水线。
「预授权放行」只有输入框和一个只读的"当前判定"回显。

---

## 9. 不做什么

- **不做静默安装**（技术上也没有 `INSTALL_PACKAGES`，做不到）
- **不做"任意包名加进黑名单"**（对我们无用，只增加破坏面）
- **默认不关闭顶层 `enable`**（拆门不是配钥匙）
- **不碰付费内容授权解锁**：这是绕厂商的"安装校验"，不是绕内容版权校验，两者性质不同，后者不做
- **不伪装签名**：`isTclLauncher` 那条走的是 TCL 私钥签名，伪造不了也不该伪造

---

## 10. 需要你拍板的四点

1. **默认策略**：精准白名单（推荐，影响面最小）还是默认就关顶层 `enable`（一次到位但风险高）？
2. **推送通道**：局域网 HTTP 上传要不要做？（不做的话就只能"选包"，少一个手机直发的能力）
3. **失败自动回滚**：装失败时自动撤掉刚写的配置，还是保留并提示？
4. **universal 是否单独出包**，还是只把推送安装并进现有三个变体？

回我一句就行，我按你的选择开始改代码。
