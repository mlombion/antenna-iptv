#!/bin/zsh
# Builds every download for a GitHub release into Release/:
#   Antenna-IPTV-<v>.dmg              Mac, Developer ID signed and notarized (Tools/package.sh)
#   Antenna-IPTV-Android-<v>.apk       Android TV / Google TV boxes, projectors and Android phones (one APK)
#   Antenna-IPTV-iPhone-<v>.ipa        iPhone, unsigned: install with Sideloadly or AltStore, which sign it with your Apple ID
# Needs Vendor/libtorrent.xcframework (Tools/build_libtorrent.sh) and JDK 17 + Android SDK for the TV app.
set -e
setopt pipefail
ROOT=${0:A:h:h}
cd "$ROOT"
mkdir -p Release
[[ -d Vendor/libtorrent.xcframework ]] || ./Tools/build_libtorrent.sh

echo "== Mac"
./Tools/package.sh "$@"

echo "== Android (TV + phone)"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
(cd AndroidTV && ./gradlew assembleRelease --console=plain -q)
TV_VERSION=$(grep versionName AndroidTV/app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
cp AndroidTV/app/build/outputs/apk/release/app-release.apk "Release/Antenna-IPTV-Android-$TV_VERSION.apk"

echo "== iPhone"
xcodebuild -project Antenna.xcodeproj -scheme AntennaPhone -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath .build/dd-ios-unsigned -clonedSourcePackagesDirPath .build/spm \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
APP=".build/dd-ios-unsigned/Build/Products/Release-iphoneos/Antenna IPTV Phone.app"
IOS_VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Info.plist")
rm -rf .build/ipa-unsigned && mkdir -p .build/ipa-unsigned/Payload
ditto "$APP" ".build/ipa-unsigned/Payload/Antenna IPTV Phone.app"
IPA="Release/Antenna-IPTV-iPhone-$IOS_VERSION.ipa"
rm -f "$IPA"
(cd .build/ipa-unsigned && zip -qry "../../$IPA" Payload)
rm -rf .build/ipa-unsigned

ls -lh Release/*"$IOS_VERSION"* Release/*"$TV_VERSION".apk
