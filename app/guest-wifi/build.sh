#!/bin/sh
# Rebuilds src/main/assets/engines/kk/aemu-wifi.jar (guest Wi-Fi service for the Android 4.0 - 7.1 images, see WifiEmu.kt).
# Needs: JDK 8+ (javac), dalvik-exchange (apt) or a Build Tools `dx`, and an android.jar of API 21 or newer
# (NetworkInfo / NetworkCapabilities / LinkProperties are only public from 21; everything hidden is reached by reflection).
#   ANDROID_JAR=/path/to/platforms/android-21/android.jar ./build.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_JAR:?set ANDROID_JAR to an android-21 (or newer) android.jar}"
# dx is gone from current build tools: D8_JAR=$ANDROID_HOME/build-tools/<ver>/lib/d8.jar uses d8 instead
DX=${DX:-$(command -v dalvik-exchange || command -v dx || true)}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"
# stubs/ only stands in for hidden framework classes at compile time; it is not packaged
javac --release 8 -nowarn -Xlint:-options -cp "$ANDROID_JAR" -d "$work/classes" \
    $(find "$here/java" "$here/stubs" -name '*.java')
find "$work/classes" -path '*/android' -prune -exec rm -rf {} + 2>/dev/null || true
if [ -n "${D8_JAR:-}" ]; then
    (cd "$work/classes" && java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 19 --lib "$ANDROID_JAR" --output "$work/dex" $(find . -name "*.class"))
else
    "$DX" --dex --min-sdk-version=19 --output="$work/dex/classes.dex" "$work/classes"
fi
out="$here/../src/main/assets/engines/kk/aemu-wifi.jar"
(cd "$work/dex" && rm -f "$out" && zip -q -X "$out" classes.dex)
echo "wrote $out"
