#!/bin/zsh
# Builds libtorrent (RC_2_0) as static libraries for iPhone, the iPhone simulator and the Mac, packed into
# Vendor/libtorrent.xcframework, for streaming torrent sources in Movies & Series.
# Needs: brew install cmake ninja boost. Uses libtorrent's built-in SHA-1/RC4 (no OpenSSL to ship).
set -e
ROOT=${0:A:h:h}
SRC=$ROOT/.build/libtorrent-src/lt
OUT=$ROOT/.build/libtorrent-build
[[ -d $SRC ]] || git clone --depth 1 --branch RC_2_0 --recurse-submodules --shallow-submodules https://github.com/arvidn/libtorrent.git $SRC

build() {
  local name=$1; shift
  echo "== $name"
  cmake -S $SRC -B $OUT/$name -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -Ddeprecated-functions=OFF -Dbuild_tests=OFF -Dbuild_examples=OFF -Dbuild_tools=OFF \
    -DCMAKE_DISABLE_FIND_PACKAGE_OpenSSL=ON -DCMAKE_DISABLE_FIND_PACKAGE_GnuTLS=ON -DCMAKE_DISABLE_FIND_PACKAGE_LibGcrypt=ON \
    -DBoost_INCLUDE_DIR=/opt/homebrew/include -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
    -DCMAKE_CXX_STANDARD=17 "$@" > $OUT/$name.log
  cmake --build $OUT/$name --target torrent-rasterbar >> $OUT/$name.log
}

mkdir -p $OUT
build macos -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 "-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64"
build ios -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_OSX_ARCHITECTURES=arm64
build iossim -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphonesimulator -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_OSX_ARCHITECTURES=arm64

# Headers: libtorrent's own; Boost headers are only needed when compiling the bridge (from Homebrew).
rm -rf $ROOT/Vendor/libtorrent.xcframework $OUT/headers
mkdir -p $OUT/headers && cp -R $SRC/include/libtorrent $OUT/headers/
xcodebuild -create-xcframework \
  -library $OUT/macos/libtorrent-rasterbar.a -headers $OUT/headers \
  -library $OUT/ios/libtorrent-rasterbar.a -headers $OUT/headers \
  -library $OUT/iossim/libtorrent-rasterbar.a -headers $OUT/headers \
  -output $ROOT/Vendor/libtorrent.xcframework
# The bridge (Shared/Torrent/ATTorrentEngine.mm) compiles against these; Boost headers come from Homebrew.
rm -rf $ROOT/Vendor/libtorrent-include && mkdir -p $ROOT/Vendor/libtorrent-include && cp -R $SRC/include/libtorrent $ROOT/Vendor/libtorrent-include/
echo "Done: Vendor/libtorrent.xcframework"
