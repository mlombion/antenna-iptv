#!/bin/zsh
# Builds the iPhone app signed for registered devices and installs it on the connected iPhone over the cable.
# Uses ideviceinstaller (brew install ideviceinstaller) because Xcode 26.6 can't mount iOS 27's developer disk image.
set -e
ROOT=${0:A:h:h}
cd "$ROOT"
UDID=${1:-$(idevice_id -l | head -1)}
[[ -n "$UDID" ]] || { echo "No iPhone connected"; exit 1; }
xcodebuild -project Antenna.xcodeproj -scheme AntennaPhone -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath .build/dd-ios-device -clonedSourcePackagesDirPath .build/spm -allowProvisioningUpdates build \
  | grep -E "error|BUILD (SUCCEEDED|FAILED)"
APP=".build/dd-ios-device/Build/Products/Release-iphoneos/Antenna IPTV Phone.app"
VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Info.plist")
IPA="Release/Antenna-IPTV-iPhone-$VERSION.ipa"
rm -rf .build/ipa "$IPA"; mkdir -p .build/ipa/Payload Release
ditto "$APP" ".build/ipa/Payload/Antenna IPTV Phone.app"
(cd .build/ipa && zip -qry "../../$IPA" Payload)
rm -rf .build/ipa
ideviceinstaller -u "$UDID" install "$IPA" | tail -1
