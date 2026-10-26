#!/bin/sh
# 一个源码树，三个变体：每个变体把一套第三方桌面打进 assets，得到一个自包含的离线安装包。
#
#   ./build.sh cyt       # 内置 CleanYourTV（默认）
#   ./build.sh aerial    # 内置 Aerial
#   ./build.sh emotn     # 内置 Emotn UI
#
# 环境：
#   JAVA_HOME      JDK 17+（JDK 21 也可以）
#   ANDROID_HOME   需要 platforms/android-34 与 build-tools/35.0.0
#   TOKEN          可选。默认写死的本机脚本令牌；TOKEN=random 则交回运行时随机生成
#
# 依赖的桌面 APK 由 ./fetch-vendor.sh 取到 vendor/，或手动放进去。
set -eu
cd "$(dirname "$0")"

VARIANT="${1:-cyt}"

case "$VARIANT" in
    cyt)
        PKG="com.gathin.tvhome"
        LABEL="CleanYourTV"
        TAG="cleanyourtv"
        SRC="vendor/CleanYourTV-3.0.0.apk"
        ;;
    aerial)
        PKG="app.orchard.tv"
        LABEL="Aerial"
        TAG="aerial"
        SRC="vendor/aerial-standard.apk"
        ;;
    emotn)
        PKG="com.oversea.aslauncher"
        LABEL="Emotn UI"
        TAG="emotnui"
        SRC="vendor/EmotnUI-1.1.0.2.apk"
        ;;
    *)
        echo "unknown variant: $VARIANT (cyt|aerial|emotn)" >&2
        exit 2
        ;;
esac

: "${JAVA_HOME:?set JAVA_HOME to a JDK 17+}"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"

AJ="$ANDROID_HOME/platforms/android-34/android.jar"
BT="$ANDROID_HOME/build-tools/35.0.0"
[ -f "$AJ" ] || { echo "missing $AJ" >&2; exit 3; }
[ -d "$BT" ] || { echo "missing $BT (build-tools 35.0.0)" >&2; exit 3; }
[ -f "$SRC" ] || { echo "missing $SRC —— 先跑 ./fetch-vendor.sh" >&2; exit 3; }

TOKEN="${TOKEN:-0e8344e8b70b4f95e48e887c912a8f35ad1e4a37b92f290b031f8dca7b6224c7}"
if [ "$TOKEN" = "random" ]; then TOKEN=""; fi

OUT="ffalcontrol-0.2.0-${TAG}.apk"

rm -rf build
mkdir -p build/classes build/dex build/gen/app/ffalcontrol build/assets dist

cat > build/gen/app/ffalcontrol/LauncherSpec.java <<SPEC
package app.ffalcontrol;

/** 由 build.sh 生成，不要手改。 */
final class LauncherSpec {
    static final String TOKEN = "${TOKEN}";
    static final String TARGET = "${PKG}";
    static final String LABEL = "${LABEL}";
}
SPEC

sed -e "s/@VARIANT@/${TAG}/g" -e "s/@LABEL@/${LABEL}/g" \
    AndroidManifest.xml > build/AndroidManifest.xml

cp "$SRC" build/assets/launcher.apk

"$JAVA_HOME/bin/javac" --release 8 -nowarn -cp "$AJ" \
    -d build/classes src/app/ffalcontrol/*.java build/gen/app/ffalcontrol/LauncherSpec.java

"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$AJ" --min-api 28 --output build/dex build/classes/app/ffalcontrol/*.class

"$BT/aapt2" link -o build/base.apk -I "$AJ" -A build/assets --manifest build/AndroidManifest.xml

cp build/dex/classes.dex build/classes.dex
if command -v zip >/dev/null 2>&1; then
    (cd build && zip -q -u base.apk classes.dex)
elif command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/base.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/classes.dex', 'classes.dex')
PY
else
    (cd build && "$JAVA_HOME/bin/jar" uf base.apk classes.dex)
fi

"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign \
    --ks "${KS:-$HOME/.android/debug.keystore}" \
    --ks-pass "${KS_PASS:-pass:android}" \
    --ks-key-alias "${KS_ALIAS:-androiddebugkey}" \
    --out "dist/$OUT" build/aligned.apk

echo "built: dist/$OUT  (launcher=${LABEL} / ${PKG})"
