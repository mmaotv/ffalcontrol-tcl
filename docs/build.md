# 构建与自测

不需要 Gradle，`javac → d8 → aapt2 → zipalign → apksigner` 直出。

## 环境

```sh
export JAVA_HOME=/path/to/jdk-17        # JDK 21 也测过
export ANDROID_HOME=/path/to/android-sdk   # 需要 platforms/android-34 + build-tools/35.0.0
```

> build-tools **34.0.0** 自带的 d8 在 JDK 21 上会 NPE 崩，务必用 35.0.0。

## 编出四个变体

```sh
./fetch-vendor.sh              # 取第三方桌面到 vendor/
./build.sh cyt                 # 或 aerial / emotn / universal
```

产物在 `dist/`，文件名带版本号。

`fetch-vendor.sh` 拉的是各桌面的公开 Release；`vendor/*.apk` 不进版本库（见 .gitignore），所以第一次构建前必须先跑它。

## 换一套内置桌面

把 APK 放进 `vendor/`，在 `build.sh` 的 `case` 里加一行（包名 + 显示名 + 路径），重编即可。构建会生成 `LauncherSpec.java`，主界面「装桌面」按钮的文案和包名都从这里来。

## 图标与资源

图标是 `res/mipmap-*/ic_launcher.png`（48 / 72 / 96 / 144 / 192），由 `tools/shot/make_icon.py` 生成（Pillow 4× 超采样）。`build.sh` 里多了一步 `aapt2 compile --dir res -o build/res`，链接时把 `build/res/*.flat` 一起带上；换图标只改 `res/` 里的 PNG 即可，不用动清单。

改版本号：`AndroidManifest.xml` 的 `versionCode` / `versionName`，以及 `build.sh` 里产物名 `OUT`。

## 自测

判定逻辑和 AXML 解析器都不依赖 Android，可以直接在 JVM 上验：

```sh
javac -d build/test src/app/ffalcontrol/AxmlReader.java \
      src/app/ffalcontrol/SecurityGate.java test/app/ffalcontrol/SelfTest.java
java -cp build/test app.ffalcontrol.SelfTest vendor/*.apk
```

会打印每个 APK 的包名、版本、`declaresHome`，再跑 16 条策略判定场景：第三方桌面被拦、白名单放行、TCL 签名放行、缺 metaData 仍被拦、黑名单命中、系统安装器触发 `thridStrategy`、本工具路径不触发、adb 触发 `pmStrategy`、patch 计划只加不删……

改判定逻辑后先跑它，比上电视试快得多。
