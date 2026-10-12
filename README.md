# Antenna IPTV

A fast, modern player for your **IPTV subscription** (Xtream Codes) and your **Stremio** library, on **Mac,
iPhone and Android TV** (Google TV boxes, projectors), with your phone as a remote.

Built to replace IPTV Smarters Pro: it opens fast, stays open, and looks like a streaming app.

**[⬇︎ Download the latest version](../../releases/latest)**

## What it does

**Live TV**
- Channels by category, favorites, recently watched, and a TV guide grid
- Search channels *and* shows: type "Formula 1" to see which channels show it now or later today
- Rewind and pause live TV (the channel you're watching is recorded locally, 30 min by default)
- Picture in picture on Mac and iPhone (swipe home on iPhone and it floats)

**Movies & Series** (optional, with a Stremio account)
- Your Stremio library, Continue Watching and installed add-ons: catalogs, search, episodes, sources
- Progress is saved back to your Stremio account, so the official Stremio apps stay in sync
- Torrent sources stream through a built-in engine on every device; direct links play as they are
- Subtitles from your add-ons (OpenSubtitles and others), audio tracks, next episode
- Low-storage mode: files bigger than your free space stream anyway, deleting what you've watched as you go

**Play on TV**
- From the iPhone or Mac, send a channel, movie or episode to an Antenna TV on the same Wi-Fi
- The phone becomes the remote: pause, seek, ±10/30 s, subtitles, audio, next episode, volume

**Phone remote for the whole TV**
- Use the iPhone as a **trackpad** for the TV or projector: a cursor appears on screen in every app (Google TV home,
  YouTube, Settings…). Tap to click, two fingers to scroll, Back / Home / volume buttons
- **Air mouse:** hold your thumb on the screen and point the phone at the TV, the cursor follows
- The remote's arrows and OK, and a keyboard that types into the TV's search boxes

## Install

Get the files from the [latest release](../../releases/latest).

### Mac (macOS 14+, Apple Silicon and Intel)
Download `Antenna-IPTV-<version>.dmg`, open it and drag **Antenna IPTV** to Applications. It's signed and
notarized by Apple, so it opens normally.

### Android TV / Google TV (Android 8+)
Download `Antenna-IPTV-AndroidTV-<version>.apk` and install it on the TV, either:
- **With the Downloader app** (from the TV's app store): turn on *Settings → Apps → Security → Unknown sources*
  for Downloader, then open the APK's link in Downloader; or
- **From a computer** with adb: enable developer options and network debugging on the TV, then
  `adb connect <tv-ip>:5555` and `adb install Antenna-IPTV-AndroidTV-<version>.apk`.

To sign in, the TV shows a QR code: scan it with your phone and type your IPTV details there instead of with the
remote.

### iPhone (iOS 17+)
Apple doesn't allow installing iPhone apps from a website, so use one of these free tools with your own Apple ID:
- **[AltStore](https://altstore.io)**: install AltStore, then open `Antenna-IPTV-iPhone-<version>.ipa` with it.
  AltStore refreshes the app automatically every 7 days while your computer is on the same Wi-Fi.
- **[Sideloadly](https://sideloadly.io)** (Mac or Windows): connect your iPhone, drag the `.ipa` into Sideloadly,
  enter your Apple ID and press Start. Repeat every 7 days (free Apple IDs sign apps for 7 days).

The first time, allow the app under *Settings → General → VPN & Device Management*, and turn on
*Settings → Privacy & Security → Developer Mode* if asked.

## Getting started
1. **Live TV:** sign in with the server URL, username and password from your IPTV provider.
2. **Movies & Series (optional):** open Movies & Series and sign in to Stremio. The sign-in happens on Stremio's own
   page (Facebook, Apple or email); Antenna only receives a session key.
3. **Play on TV:** install Antenna on the TV and keep it on the same Wi-Fi as your phone. A TV button appears
   in the player and next to sources.

### Turning on the phone remote (once per TV)
The TV part of the remote is an accessibility service, so Android asks you to allow it:
1. On the TV: *Settings → System → Accessibility → Antenna phone remote → On* (Antenna's own Settings screen has a
   shortcut). If it's greyed out, first open *Settings → Apps → Antenna IPTV* and choose *Allow restricted settings*.
2. Or from a computer: `adb shell appops set com.malick.antenna.tv ACCESS_RESTRICTED_SETTINGS allow` then
   `adb shell settings put secure enabled_accessibility_services com.malick.antenna.tv/com.malick.antenna.tv.pointer.PointerService`.
3. On the iPhone: the remote button at the top of Home, or *Settings → Phone remote for your TV*.

It only accepts phones on your home network, and it can only tap, scroll and press buttons where you point.

## Privacy
Antenna has no server and collects nothing. It connects to your IPTV provider and, if you use Movies & Series,
to Stremio's account service, the add-ons you installed and, for torrent sources, other torrent users.
Your IPTV password stays in the Keychain (Apple) or encrypted storage (Android).

## Please note
Antenna is a player. It doesn't include, host or link to any channels, movies or series: you bring your own IPTV
subscription and choose your own Stremio add-ons. Streaming a torrent means downloading it from, and sharing it
with, other people while you watch. Only watch content you have the right to watch where you live.

Not affiliated with Stremio, VideoLAN or any IPTV provider.

## Build from source

| | Needs |
|---|---|
| Mac + iPhone | Xcode 26, Homebrew `cmake ninja boost` |
| Android TV | JDK 17, Android SDK (compileSdk 35) |

```sh
./Tools/build_libtorrent.sh          # once: builds libtorrent for Mac, iPhone and simulator (~1 min)
open Antenna.xcodeproj               # targets: Antenna (Mac), AntennaPhone (iPhone); set your own Team
cd AndroidTV && ./gradlew assembleDebug
```

Scripts in `Tools/`:
- `make_release.sh`: builds all three downloads into `Release/` (Mac signing and notarization need a paid
  Apple Developer account; see `package.sh`)
- `install.sh` (Mac), `install_iphone.sh` (connected iPhone), `install_tv.sh <tv-ip>` (TV over adb)
- `mock_xtream.py` and `mock_stremio.py`: local fake IPTV provider and Stremio account for development, with
  public-domain test videos (Blender's Sintel and Big Buck Bunny). Debug builds read test switches such as
  `-AntennaDevServer`, `-AntennaDevStremio` and `-AntennaDevTorrentBudgetMB` (see the sources).

### Layout
- `Shared/`: code used by the Mac and iPhone apps (Xtream client, Stremio client and add-on protocol, players,
  live rewind recorder, torrent streamer, Play on TV)
- `Antenna/`: Mac app · `AntennaPhone/`: iPhone app · `AndroidTV/`: Android TV app (Kotlin, Compose for TV)
- `Shared/Torrent/ATTorrentEngine.mm`: Objective-C++ bridge to libtorrent

## License
MIT, see [LICENSE](LICENSE). Third-party components and their licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
