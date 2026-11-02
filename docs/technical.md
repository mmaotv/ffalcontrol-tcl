# 原理与接口

给想搞清楚它到底做了什么的人看的。只想用的话回 [README](../README.md)。

## 厂商的安装校验是怎么挂上去的

TCL 在 `PackageInstaller_tv` 里塞了一套安装校验：

```
PackageManager 发出 PACKAGE_NEEDS_VERIFICATION 广播（仅当安装带 verification id）
  └─ VerifierReciver
       ├─ 没有 system feature com.tcl.software.packageinstaller_install_control → 直接放行
       └─ 有（本机实测有）→ StrategyService → StrategyChain
            六个策略逐个跑，全过才放行，任一拒绝 → verifyPendingInstall(-1)
```

六个策略的真面目（由 `PackageInstaller_tv.apk` 反编译得出，未复制其代码）：

| 策略 | 判定 | 拦谁 |
|---|---|---|
| `launcherStrategy` | 只对声明 `MAIN` + `HOME` 的 APK 生效。包名在 `packages` 白名单，或签名等于当前默认桌面且 `metaData com.tcl.app.type == "com.tcl.ui"` → 放行 | **第三方桌面** |
| `blackListStrategy` | 包名 ∈ `packages` → 拒 | 名单内的包 |
| `overDueStrategy` | 同上（"过期"名单） | 名单内的包 |
| `thridStrategy` | **仅当 installer 是 `com.android.packageinstaller`** 时才检查来源 | 从系统安装器点装的第三方来源 |
| `pmStrategy` | `installFlag & 0x20`（`INSTALL_FROM_ADB`）→ 拒 | `adb install` |
| `safeStrategy` | 空实现，永远放行 | 不拦任何东西 |

由此得出三个不太直观的结论：

1. **把 APK 拷到 U 盘、用文件管理器点开安装被拦，原因不是桌面策略，是 `thridStrategy`。** 这时 installer 是系统安装器，而发起方（文件管理器）既不是系统应用也不在白名单里。
2. **用本工具自己的 `PackageInstaller` 会话安装，`thridStrategy` 和 `pmStrategy` 都不触发。** installer 是 `app.ffalcontrol`，也不带 adb 标志。所以真正需要动的配置只有三处：桌面放行名单、两个拦截名单。
3. **`adb install` 未必能解决问题。** `pmStrategy` 明确拦 `INSTALL_FROM_ADB`，它启用时 adb 直接被判拒。

## 判定 → 安装的完整流程

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

判定逻辑（`SecurityGate`）和二进制清单解析（`AxmlReader`）都不依赖 Android，可以直接在 JVM 上跑真实判定。

## 改配置的克制

| 操作 | 影响面 | 默认 |
|---|---|---|
| 往 `launcherStrategy.packages` 加一项 | 仅该包名，仅桌面判定 | 开 |
| 从 `blackListStrategy` / `overDueStrategy` 移出一项 | 仅该包名 | 仅在命中时 |
| 关闭 `launcherStrategy.enable` | 该策略整体失效 | 关闭，需二次确认 |
| 关闭 `InstallConfig` 顶层 `enable` | **六个策略全失效** | 关闭，**不做** |

**只允许从黑名单里移除，绝不往黑名单里加**——加进去没有任何用处，只会增加破坏面。

**为什么不需要存储权限、也不需要 adb**：APK 字节以流的形式经 `openWrite()` 的 FD 直接进安装会话，不落盘、不经过 `system_server` 读文件，所以绕开了「`/sdcard` 是 FUSE 视图、系统安装器读不了」这个卡点。

## 实测环境

| 项目 | 实测值 |
|---|---|
| 机型 / 平台 | TCL Smart TV Pro · `MT9655_4K_CN` · Android 14 |
| 固件 | `TCL/MT9655_4K_CN/MT9655:14/UTT2.250416.001/AU01:user/release-keys` |
| 系统组件 | `com.tcl.providers.config`（/system_ext/priv-app/ConfigProvider）· `com.tcl.guard`（/system_ext/app/TGuard）均在位 |
| 安装校验 | `com.tcl.software.packageinstaller_install_control` = true |
| 目标包 | 22 个待禁用包在本机全部存在（缺失 0） |

## 已知限制

- 预装应用在只读分区，厂商卸载白名单为空，`pm uninstall --user 0` 必然失败，**能禁用不能真删**
- 后台 service（如 `LauncherUpdateService`）既不在 `AppBootPolicy` 覆盖范围、guard 也会跳过，目前禁不掉
- MT9655 的 `WindowManager` 在 `systemReady` 时硬编码拉起一次 `com.tcl.cyberui`，开机后按一次 Home 才回新桌面
- OTA 升级可能收紧策略，升级后请重新跑一次诊断

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

令牌默认固定在构建里（方便没有 adb 的环境用脚本驱动）；想改回运行时随机生成，构建时传 `TOKEN=random`。

## 与上游的差异

| 来源 | 作用 | 许可 |
|---|---|---|
| [xnklerEd/ffalcontrol](https://github.com/xnklerEd/ffalcontrol) v0.1.4 | **上游主体**：ConfigProvider 读写、Guard / AppBootPolicy / InstallConfig 的写入与校验、本机 HTTP 接口 | MIT |
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
6. **构建**（0.3.0）：一个源码树出四个变体；`TOKEN` 可选固定值

上游的英文文档与机制分析仍然适用，建议一并阅读。
