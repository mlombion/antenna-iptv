# Third-party software

Antenna IPTV's own code is MIT licensed (see `LICENSE`). The apps include or link these projects, each under
its own license:

| Project | Used for | License |
|---|---|---|
| [VLCKit / libVLC](https://code.videolan.org/videolan/VLCKit) (via [vlckit-spm](https://github.com/tylerjonesio/vlckit-spm)) | Playback on Mac and iPhone | LGPL 2.1 or later |
| [libVLC for Android](https://code.videolan.org/videolan/vlc-android) | Movies & Series playback on Android TV | LGPL 2.1 or later |
| [libtorrent](https://github.com/arvidn/libtorrent) | Torrent streaming on Mac and iPhone | BSD 3-Clause |
| [libtorrent4j](https://github.com/aldenml/libtorrent4j) | Torrent streaming on Android TV | MIT (libtorrent: BSD 3-Clause) |
| [Boost](https://www.boost.org) (headers) | Used by libtorrent | Boost Software License 1.0 |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Live TV playback on Android TV | Apache 2.0 |
| [Jetpack Compose, Compose for TV, AndroidX](https://developer.android.com/jetpack/androidx) | Android TV interface | Apache 2.0 |
| [OkHttp](https://github.com/square/okhttp) | Networking on Android TV | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil) | Images on Android TV | Apache 2.0 |
| [ZXing](https://github.com/zxing/zxing) | QR codes on Android TV | Apache 2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Android TV | Apache 2.0 |

VLC and libVLC are linked dynamically (VLCKit framework, libvlc shared libraries), as the LGPL allows; their
source is available from VideoLAN at the links above.

Antenna IPTV is an independent project. It is not affiliated with or endorsed by Stremio, VideoLAN or any IPTV
provider. "Stremio" is a trademark of its owners and is used here only to say which accounts and add-ons the app
works with.
