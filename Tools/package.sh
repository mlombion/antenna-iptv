#!/bin/zsh
# Builds a Developer ID signed, notarized disk image for sharing: Release/Antenna-IPTV-<version>.dmg
# Notarization uses the Keychain profile "AntennaNotary" (created once with `xcrun notarytool store-credentials`).
# Pass --skip-notarize to stop after signing.
set -e
ROOT=${0:A:h:h}
cd "$ROOT"
# Your Apple Developer team (defaults to the one in the Xcode project) and notarization profile.
TEAM=${ANTENNA_TEAM:-$(grep -m1 "DEVELOPMENT_TEAM = " Antenna.xcodeproj/project.pbxproj | sed -E 's/.*= ([A-Z0-9]+);/\1/')}
IDENTITY=${ANTENNA_SIGN_IDENTITY:-"Developer ID Application"}
PROFILE=${ANTENNA_NOTARY_PROFILE:-AntennaNotary}

xcodebuild -project Antenna.xcodeproj -scheme Antenna -configuration Release \
  -derivedDataPath .build/dd-dist -clonedSourcePackagesDirPath .build/spm \
  ARCHS="arm64 x86_64" ONLY_ACTIVE_ARCH=NO \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY="Developer ID Application" DEVELOPMENT_TEAM=$TEAM \
  ENABLE_HARDENED_RUNTIME=YES CODE_SIGN_INJECT_BASE_ENTITLEMENTS=NO OTHER_CODE_SIGN_FLAGS=--timestamp build | grep -E "error:|BUILD (SUCCEEDED|FAILED)"

APP=".build/dd-dist/Build/Products/Release/Antenna IPTV.app"
VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Contents/Info.plist")
codesign --verify --deep --strict "$APP"
if codesign -d --entitlements - "$APP" 2>/dev/null | grep -q get-task-allow; then echo "get-task-allow present, refusing to package"; exit 1; fi
# Build products must not appear in Launchpad next to the installed app.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -u "$APP" 2>/dev/null || true

STAGE=.build/dmg-stage
rm -rf "$STAGE"; mkdir -p "$STAGE" Release
ditto "$APP" "$STAGE/Antenna IPTV.app"
ln -s /Applications "$STAGE/Applications"
cp Tools/dmg-readme.txt "$STAGE/Read me first.txt"

DMG="Release/Antenna-IPTV-$VERSION.dmg"
rm -f "$DMG"
hdiutil create -volname "Antenna IPTV" -srcfolder "$STAGE" -ov -format UDZO -fs HFS+ "$DMG" >/dev/null
codesign --sign "$IDENTITY" --timestamp "$DMG"
# The staging copy must not linger in Launchpad either.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -u "$STAGE/Antenna IPTV.app" 2>/dev/null || true
rm -rf "$STAGE"

if [[ "$1" != "--skip-notarize" ]]; then
  xcrun notarytool submit "$DMG" --keychain-profile "$PROFILE" --wait
  xcrun stapler staple "$DMG"
  spctl --assess --type open --context context:primary-signature --verbose=2 "$DMG"
fi
echo "Built $ROOT/$DMG"
