#!/bin/zsh
# Builds the Developer ID signed Release app (same as the shared package) and installs it as the only copy.
set -e
ROOT=${0:A:h:h}
cd "$ROOT"
xcodebuild -project Antenna.xcodeproj -scheme Antenna -configuration Release \
  -derivedDataPath .build/dd-dist -clonedSourcePackagesDirPath .build/spm \
  ARCHS="arm64 x86_64" ONLY_ACTIVE_ARCH=NO \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY="Developer ID Application" \
  DEVELOPMENT_TEAM=${ANTENNA_TEAM:-$(grep -m1 "DEVELOPMENT_TEAM = " Antenna.xcodeproj/project.pbxproj | sed -E 's/.*= ([A-Z0-9]+);/\1/')} \
  ENABLE_HARDENED_RUNTIME=YES CODE_SIGN_INJECT_BASE_ENTITLEMENTS=NO OTHER_CODE_SIGN_FLAGS=--timestamp build \
  | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
osascript -e 'tell application "Antenna IPTV" to quit' 2>/dev/null || true
sleep 1
rm -rf "/Applications/Antenna IPTV.app"
ditto ".build/dd-dist/Build/Products/Release/Antenna IPTV.app" "/Applications/Antenna IPTV.app"
# Build products must not show up in Launchpad or Spotlight next to the installed app.
LSR=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
for app in .build/*/Build/Products/*/*.app(N) ~/Library/Developer/Xcode/DerivedData/Antenna-*/Build/Products/*/*.app(N); do
  "$LSR" -u "$app" 2>/dev/null || true
done
"$LSR" -f "/Applications/Antenna IPTV.app"
echo "Installed /Applications/Antenna IPTV.app"
