#!/bin/zsh
# Builds the Android TV app and installs it on the TV box over Wi-Fi (classic adb on port 5555).
# Usage: Tools/install_tv.sh <tv-ip>   (or set ANTENNA_TV=<tv-ip>). Turn on adb debugging on the TV first.
set -e
ROOT=${0:A:h:h}
BOX=${1:-${ANTENNA_TV:?Usage: Tools/install_tv.sh <tv-ip>  (or set ANTENNA_TV)}}:5555
ADB=~/Library/Android/sdk/platform-tools/adb
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd "$ROOT/AndroidTV"
./gradlew assembleRelease --console=plain -q
VERSION=$(grep versionName app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p "$ROOT/Release"
cp app/build/outputs/apk/release/app-release.apk "$ROOT/Release/Antenna-IPTV-Android-$VERSION.apk"
$ADB connect "$BOX" >/dev/null
$ADB -s "$BOX" install -r "$ROOT/Release/Antenna-IPTV-Android-$VERSION.apk"
$ADB -s "$BOX" shell am start -n com.malick.antenna.tv/.MainActivity >/dev/null
