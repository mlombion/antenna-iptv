#!/bin/zsh
# Builds the Android app and installs it on an Android phone, over USB or wireless debugging.
# Usage: Tools/install_phone.sh [device]   (device = serial or ip:port from `adb devices`; optional with one phone)
# The same APK runs on TVs and phones: it shows the phone screens on a phone.
set -e
ROOT=${0:A:h:h}
ADB=~/Library/Android/sdk/platform-tools/adb
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd "$ROOT/AndroidTV"
./gradlew assembleRelease --console=plain -q
VERSION=$(grep versionName app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p "$ROOT/Release"
APK="$ROOT/Release/Antenna-IPTV-Android-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
TARGET=()
[[ -n "$1" ]] && TARGET=(-s "$1")
$ADB $TARGET install -r "$APK"
$ADB $TARGET shell am start -n com.malick.antenna.tv/.MainActivity >/dev/null
