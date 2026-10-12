import Observation
import SwiftUI
#if os(macOS)
import VLCKit
#else
import AVKit
import MobileVLCKit
#endif

/// Plays movies and episodes from Movies & Series with VLC, which handles what add-ons turn up (MKV, HEVC,
/// DTS/AC3, subtitles inside the file). Live TV keeps its own `PlayerController` with the rewind buffer.
@MainActor @Observable
final class VodPlayer: NSObject {
    enum State: Equatable {
        case idle, opening, playing, paused, ended
        case failed(String)
    }

    private(set) var state: State = .idle
    /// Seconds into the video, and its length (0 until known).
    private(set) var time: TimeInterval = 0
    private(set) var length: TimeInterval = 0
    private(set) var audioTracks: [PlayerController.Track] = []
    private(set) var subtitleTracks: [PlayerController.Track] = []
    private(set) var currentAudioTrack: Int32 = -1
    private(set) var currentSubtitleTrack: Int32 = -1
    private(set) var videoSize: CGSize = .zero

    @ObservationIgnored let videoView: PlatformView = {
        let v = PlatformView()
        #if os(macOS)
        v.wantsLayer = true
        v.layer?.backgroundColor = NSColor.black.cgColor
        #else
        v.backgroundColor = .black
        #endif
        return v
    }()

    /// Shown briefly when picture in picture can't start (iPhone).
    private(set) var pipNotice: String?
    @ObservationIgnored var onPiP: ((PlayerController.PiPEvent) -> Void)?
    @ObservationIgnored private var currentURL: URL?
    #if os(iOS)
    // Picture in picture on iPhone needs Apple's player; it takes over from VLC for formats it can play.
    @ObservationIgnored private var av: AVPlayer?
    @ObservationIgnored private var avLayer: AVPlayerLayer?
    @ObservationIgnored private var pip: AVPictureInPictureController?
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var avDeadline: Task<Void, Never>?
    @ObservationIgnored private var restoring = false
    private(set) var usesSystemPlayer = false
    #endif

    @ObservationIgnored private let mp = VLCMediaPlayer()
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var openedAt = Date.distantPast
    @ObservationIgnored private var seekTarget: TimeInterval?

    override init() {
        super.init()
        mp.drawable = videoView
    }

    func open(_ url: URL, startAt: TimeInterval, userAgent: String? = nil, referrer: String? = nil, torrent: Bool = false) {
        let media = VLCMedia(url: url)
        media.addOption(":network-caching=\(torrent ? 6000 : 3000)")
        media.addOption(":avcodec-hw=any")
        media.addOption(":http-reconnect")
        if startAt > 0 { media.addOption(String(format: ":start-time=%.1f", locale: Locale(identifier: "en_US_POSIX"), startAt)) }
        if let userAgent { media.addOption(":http-user-agent=\(userAgent)") }
        if let referrer { media.addOption(":http-referrer=\(referrer)") }
        #if os(iOS)
        tearDownSystemPlayer()
        #endif
        currentURL = url
        mp.media = media
        time = startAt
        length = 0
        audioTracks = []; subtitleTracks = []
        state = .opening
        openedAt = Date()
        mp.play()
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                self?.poll()
            }
        }
    }

    func togglePause() {
        #if os(iOS)
        if usesSystemPlayer, let av {
            if state == .paused { av.play(); state = .playing } else { av.pause(); state = .paused }
            return
        }
        #endif
        switch state {
        case .playing: mp.pause(); state = .paused
        case .paused: mp.play(); state = .playing
        case .ended: mp.stop(); mp.play(); state = .opening
        default: break
        }
    }

    func seek(to seconds: TimeInterval) {
        let target = length > 0 ? min(max(0, seconds), length - 1) : max(0, seconds)
        #if os(iOS)
        if usesSystemPlayer, let av {
            av.seek(to: CMTime(seconds: target, preferredTimescale: 600))
            time = target
            return
        }
        #endif
        mp.time = VLCTime(int: Int32(target * 1000))
        time = target
        seekTarget = target
    }

    func seek(by seconds: TimeInterval) { seek(to: time + seconds) }

    func selectAudioTrack(_ id: Int32) { mp.currentAudioTrackIndex = id; currentAudioTrack = id }
    func selectSubtitle(_ id: Int32) { mp.currentVideoSubTitleIndex = id; currentSubtitleTrack = id }

    /// Adds a subtitle file from an add-on (OpenSubtitles and others) and shows it.
    func addSubtitle(_ url: URL) { mp.addPlaybackSlave(url, type: .subtitle, enforce: true) }

    func stop() {
        #if os(iOS)
        tearDownSystemPlayer()
        #endif
        pollTask?.cancel()
        mp.stop()
        state = .idle
        time = 0; length = 0
    }

    private func poll() {
        #if os(iOS)
        if usesSystemPlayer, let av {
            if let d = av.currentItem?.duration.seconds, d.isFinite, d > 0 { length = d }
            let t = av.currentTime().seconds
            if t.isFinite, t > 0 { time = t }
            switch av.timeControlStatus {
            case .playing: if state != .playing { state = .playing }
            case .paused: if state == .playing { state = length > 0 && time > length - 2 ? .ended : .paused }
            default: break
            }
            return
        }
        #endif
        if let len = mp.media?.length.intValue, len > 0 { length = TimeInterval(len) / 1000 }
        let t = TimeInterval(mp.time.intValue) / 1000
        // VLC reports the old position for a moment after a seek; don't let the bar jump back.
        if let target = seekTarget {
            if abs(t - target) < 3 { seekTarget = nil; time = t }
        } else if t > 0 {
            time = t
        }
        switch mp.state {
        case .error:
            state = .failed("This source stopped working. Try another one.")
        case .ended:
            state = .ended
        case .stopped:
            if state == .opening, Date().timeIntervalSince(openedAt) > 20 { state = .failed("This source didn't start. Try another one.") }
            else if state == .playing, length > 0, time > length - 5 { state = .ended }
        case .paused:
            if state == .playing { state = .paused }
        default:
            if mp.isPlaying, state == .opening || state == .paused, mp.hasVideoOut || mp.time.intValue > 0 { state = .playing }
        }
        if state == .playing || state == .paused {
            refreshTracks()
            if mp.videoSize != .zero, mp.videoSize != videoSize { videoSize = mp.videoSize }
        }
    }

    private func refreshTracks() {
        func tracks(_ names: [Any], _ ids: [Any]) -> [PlayerController.Track] {
            zip(ids, names).compactMap { id, name in
                guard let n = id as? NSNumber, n.int32Value >= 0 else { return nil }
                return PlayerController.Track(id: n.int32Value, name: (name as? String) ?? "Track \(n)")
            }
        }
        let audio = tracks(mp.audioTrackNames, mp.audioTrackIndexes)
        let subs = tracks(mp.videoSubTitlesNames, mp.videoSubTitlesIndexes)
        if audio != audioTracks { audioTracks = audio }
        if subs != subtitleTracks { subtitleTracks = subs }
        if mp.currentAudioTrackIndex != currentAudioTrack { currentAudioTrack = mp.currentAudioTrackIndex }
        if mp.currentVideoSubTitleIndex != currentSubtitleTrack { currentSubtitleTrack = mp.currentVideoSubTitleIndex }
    }
}

#if os(iOS)
extension VodPlayer: AVPictureInPictureControllerDelegate {
    /// Formats Apple's player can open; MKV and AVI (common for torrents) can't float.
    private static let floatable: Set<String> = ["mp4", "m4v", "mov", "m3u8"]

    /// Hands playback to Apple's player at the same moment, then floats it.
    func startPictureInPicture() {
        guard let url = currentURL, av == nil else { return }
        let ext = url.pathExtension.lowercased()
        guard Self.floatable.contains(ext) || url.absoluteString.contains(".m3u8") else {
            notice("Picture in picture only works with MP4 sources; this one is \(ext.isEmpty ? "another format" : ext.uppercased()). Pick an MP4 source to watch in a floating window.")
            return
        }
        #if !targetEnvironment(simulator)
        guard AVPictureInPictureController.isPictureInPictureSupported() else { notice("This device doesn't support picture in picture."); return }
        #endif
        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        let layer = AVPlayerLayer(player: player)
        layer.videoGravity = .resizeAspect
        layer.opacity = 0
        videoView.playerLayer = layer
        av = player
        avLayer = layer
        let resumeAt = time
        observations.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, self.av?.currentItem === item else { return }
                switch item.status {
                case .readyToPlay:
                    _ = await item.seek(to: CMTime(seconds: resumeAt, preferredTimescale: 600))
                    self.av?.play()
                case .failed:
                    self.notice("This video can't play in picture in picture. \(item.error?.localizedDescription ?? "")")
                    self.tearDownSystemPlayer()
                default: break
                }
            }
        })
        if let controller = AVPictureInPictureController(playerLayer: layer) {
            controller.delegate = self
            pip = controller
            observations.append(controller.observe(\.isPictureInPicturePossible, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.completeHandoff() }
            })
            observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.completeHandoff() }
            })
        }
        avDeadline = Task { [weak self] in
            try? await Task.sleep(for: .seconds(20))
            guard !Task.isCancelled, let self, !self.usesSystemPlayer, self.av != nil else { return }
            self.notice("Picture in picture took too long to start. Try again in a few seconds.")
            self.tearDownSystemPlayer()
        }
    }

    /// Once Apple's player is playing and the floating window is possible, VLC steps aside.
    private func completeHandoff() {
        guard !usesSystemPlayer, let av, av.timeControlStatus == .playing, let pip, pip.isPictureInPicturePossible else { return }
        avDeadline?.cancel()
        usesSystemPlayer = true
        mp.pause()
        avLayer?.opacity = 1
        av.isMuted = false
        state = .playing
        pip.startPictureInPicture()
    }

    func stopPictureInPicture() {
        if pip?.isPictureInPictureActive == true { pip?.stopPictureInPicture() }
    }

    /// Back from the floating window: VLC carries on from where Apple's player got to.
    private func returnToVLC() {
        guard usesSystemPlayer, let av else { tearDownSystemPlayer(); return }
        let t = av.currentTime().seconds
        let wasPlaying = av.timeControlStatus == .playing
        tearDownSystemPlayer()
        if t.isFinite, t > 0 { mp.time = VLCTime(int: Int32(t * 1000)); time = t; seekTarget = t }
        if wasPlaying { mp.play(); state = .playing } else { state = .paused }
    }

    func tearDownSystemPlayer() {
        avDeadline?.cancel()
        observations = []
        if pip?.isPictureInPictureActive == true { pip?.stopPictureInPicture() }
        pip?.delegate = nil
        pip = nil
        av?.pause()
        av = nil
        videoView.playerLayer = nil
        avLayer = nil
        if usesSystemPlayer { usesSystemPlayer = false }
    }

    private func notice(_ message: String) {
        pipNotice = message
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            if self?.pipNotice == message { self?.pipNotice = nil }
        }
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor in self.onPiP?(.started) }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in
            self.restoring = true
            self.onPiP?(.restore)
            // Let the full-screen player reappear before the video flies back into it.
            try? await Task.sleep(for: .milliseconds(450))
            completionHandler(true)
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor in
            let restored = self.restoring
            self.restoring = false
            if restored { self.returnToVLC() } else { self.tearDownSystemPlayer() }
            self.onPiP?(.stopped(restored: restored))
        }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                failedToStartPictureInPictureWithError error: Error) {
        Task { @MainActor in
            self.notice("Picture in picture couldn't start. \(error.localizedDescription)")
            self.returnToVLC()
        }
    }
}
#endif
