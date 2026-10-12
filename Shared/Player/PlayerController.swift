import Observation
import SwiftUI
#if os(macOS)
import AppKit
import VLCKit
typealias PlatformView = NSView
#else
import AVKit
import UIKit
import MobileVLCKit
typealias PlatformView = VideoHostView

/// The view VLC draws into; it can also carry Apple's player layer for picture in picture.
final class VideoHostView: UIView {
    var playerLayer: AVPlayerLayer? {
        didSet {
            oldValue?.removeFromSuperlayer()
            if let playerLayer { layer.addSublayer(playerLayer); setNeedsLayout() }
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer?.frame = bounds
        CATransaction.commit()
    }
}
#endif

/// Wraps one VLC media player that lives for the whole app, so switching channels never rebuilds the video pipeline.
@MainActor @Observable
final class PlayerController: NSObject {
    enum State: Equatable {
        case idle, opening, playing, paused
        case failed(String)
    }

    struct Track: Identifiable, Hashable {
        let id: Int32
        let name: String
    }

    /// Which window shows the video. Only the matching surface may take the shared video view.
    enum Surface { case main, pip }

    struct Options: Equatable {
        var bufferMs: Int
        var hardwareDecoding: Bool
        var autoReconnect: Bool
        /// How far back you can rewind. 0 turns the local recording off.
        var timeshiftMinutes: Int
    }

    /// The rewindable window: what's recorded, and where playback is within it.
    struct Timeline: Equatable {
        var start: Date
        var end: Date
        var playhead: Date
        var behind: TimeInterval { max(0, end.timeIntervalSince(playhead)) }
        var length: TimeInterval { max(1, end.timeIntervalSince(start)) }
    }

    private(set) var state: State = .idle
    private(set) var videoSize: CGSize = .zero
    private(set) var audioTracks: [Track] = []
    private(set) var subtitleTracks: [Track] = []
    private(set) var currentAudioTrack: Int32 = -1
    private(set) var currentSubtitleTrack: Int32 = -1
    private(set) var reconnectAttempt = 0
    var surface: Surface = .main
    var volume: Double = 0.8 { didSet { applyVolume() } }
    var isMuted = false { didSet { applyVolume() } }

    /// The view VLC draws into. It is moved into whichever SwiftUI surface is showing video.
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

    @ObservationIgnored private let mp = VLCMediaPlayer()
    @ObservationIgnored private var current: (url: URL, options: Options)?
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var timeoutTask: Task<Void, Never>?
    @ObservationIgnored private var monitorTask: Task<Void, Never>?
    @ObservationIgnored private var retryPending = false
    /// A stream that never started gets one quick retry; one that drops mid-show gets three.
    @ObservationIgnored private var hasPlayed = false
    @ObservationIgnored private var openedAt = Date.distantPast
    /// True until the current channel shows its first frame; the view hides the previous channel's last frame meanwhile.
    private(set) var isSwitching = false
    private(set) var timeline: Timeline?
    /// Why rewind is off for the current channel, when it's switched on in Settings but couldn't start.
    private(set) var rewindUnavailableReason: String?

    @ObservationIgnored private var relay: TimeshiftRelay?
    @ObservationIgnored private var anchorTime: Date?
    @ObservationIgnored private var anchorWall: Date?
    @ObservationIgnored private var pausedAt: Date?
    @ObservationIgnored private var pausedTotal: TimeInterval = 0

    /// Picture-in-picture events from the system (iPhone), passed on to the app model.
    enum PiPEvent { case started, restore, stopped(restored: Bool) }
    @ObservationIgnored var onPiP: ((PiPEvent) -> Void)?
    /// Shown briefly when picture in picture can't start on the current channel.
    private(set) var pipNotice: String?

    #if os(iOS)
    // Picture in picture on iPhone needs Apple's player. It takes over from VLC at the same moment,
    // reading the same recording as HLS, and stays in charge until the channel changes.
    @ObservationIgnored private var av: AVPlayer?
    @ObservationIgnored private var avLayer: AVPlayerLayer?
    @ObservationIgnored private var pip: AVPictureInPictureController?
    @ObservationIgnored private var avStatusObservation: NSKeyValueObservation?
    @ObservationIgnored private var pipPossibleObservation: NSKeyValueObservation?
    @ObservationIgnored private var avDeadline: Task<Void, Never>?
    @ObservationIgnored private var pendingPiP = false
    @ObservationIgnored private var restoringFromPiP = false
    @ObservationIgnored private var avSeekTarget: Date?
    /// The background hand-off that makes swiping home float the video. Its failures stay quiet.
    @ObservationIgnored private var silentHandoff = false
    @ObservationIgnored private var handoffTask: Task<Void, Never>?
    @ObservationIgnored private var handoffAttempts = 0
    /// True when the floating window opened because the app went to the background.
    @ObservationIgnored private(set) var pipStartedAutomatically = false
    private(set) var usesSystemPlayer = false
    private(set) var isPiPActive = false
    #endif

    var canRewind: Bool { timeline != nil }

    /// True after starting a channel or pressing Go live, until you rewind or pause.
    private(set) var followingLive = true

    /// "Live" follows what you did rather than the measured gap: providers send video in bursts, so the gap to the
    /// newest data swings by several seconds, and judging by it alone made the badge (and the controls) flicker.
    /// Following live only counts as behind if playback has genuinely slipped well back.
    var isBehindLive: Bool {
        guard let timeline else { return false }
        return followingLive ? timeline.behind > 30 : timeline.behind > 2
    }

    override init() {
        super.init()
        #if DEBUG
        if ProcessInfo.processInfo.environment["ANTENNA_VLC_LOG"] != nil {
            VLCLibrary.shared().debugLogging = true
            VLCLibrary.shared().debugLoggingLevel = 3
        }
        #endif
        mp.drawable = videoView
        mp.delegate = self
        #if os(macOS)
        let terminate = NSApplication.willTerminateNotification
        #else
        let terminate = UIApplication.willTerminateNotification
        #endif
        NotificationCenter.default.addObserver(forName: terminate, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.relay?.stop(waitUntilDone: true) }
        }
        // VLC's delegate callbacks are not reliable for every stream type, so also check twice a second.
        monitorTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(400))
                self?.poll()
            }
        }
    }

    // MARK: Control

    /// Shows the spinner right away, before the (possibly debounced) load starts.
    func prepare() {
        retryTask?.cancel()
        state = .opening
        isSwitching = true
    }

    func load(_ url: URL, options: Options) {
        #if os(iOS)
        tearDownSystemPlayer()
        handoffAttempts = 0
        #endif
        current = (url, options)
        reconnectAttempt = 0
        hasPlayed = false
        isSwitching = true
        followingLive = true
        rewindUnavailableReason = nil
        AppLog.write("player: load \(url.absoluteString) buffer=\(options.bufferMs) rewind=\(options.timeshiftMinutes)min")
        startSource()
    }

    func retry() {
        reconnectAttempt = 0
        startSource()
    }

    /// With the rewind buffer, pause holds the picture and resumes where you stopped.
    /// Without it, live TV can't really pause, so resuming jumps back to the live point.
    func togglePause() {
        #if os(iOS)
        if usesSystemPlayer, let av {
            if state == .paused { av.play(); state = .playing } else { av.pause(); state = .paused; followingLive = false }
            return
        }
        #endif
        switch state {
        case .playing:
            mp.pause()
            timeoutTask?.cancel()
            pausedAt = Date()
            state = .paused
            if relay != nil { followingLive = false }
        case .paused:
            if relay != nil {
                if let p = pausedAt { pausedTotal += Date().timeIntervalSince(p) }
                pausedAt = nil
                mp.play()
                state = .playing
            } else {
                open()
            }
        default:
            break
        }
    }

    // MARK: Rewind

    func seek(by seconds: TimeInterval) {
        guard let playhead = currentPlayhead else { return }
        seek(to: playhead.addingTimeInterval(seconds))
    }

    func seek(to time: Date) {
        guard let relay else { return }
        let snap = relay.snapshot()
        guard let start = snap.startTime, let end = snap.endTime else { return }
        #if os(iOS)
        if usesSystemPlayer, let item = av?.currentItem {
            if time >= end.addingTimeInterval(-6) { goLive(); return }
            followingLive = false
            _ = item.seek(to: max(time, start.addingTimeInterval(0.5)), completionHandler: nil)
            return
        }
        #endif
        if time >= end.addingTimeInterval(-2) { goLive(); return }
        followingLive = false
        open(fromOffset: relay.offset(for: max(time, start.addingTimeInterval(0.5))))
    }

    func goLive() {
        guard let relay, isBehindLive || state == .paused else { return }
        followingLive = true
        #if os(iOS)
        if usesSystemPlayer, let av, let range = av.currentItem?.seekableTimeRanges.last?.timeRangeValue {
            av.seek(to: CMTimeSubtract(range.end, CMTime(seconds: 4, preferredTimescale: 600)))
            av.play()
            state = .playing
            return
        }
        #endif
        // Start just inside what's already recorded so the picture comes back at once instead of
        // waiting for VLC's buffer to fill from the live edge. It lands about as far behind as live always is.
        let lead = Double(current?.options.bufferMs ?? 3000) / 1000 + 1
        guard let end = relay.snapshot().endTime else { open(fromOffset: nil); return }
        open(fromOffset: relay.offset(for: end.addingTimeInterval(-lead)))
    }

    private var currentPlayhead: Date? {
        #if os(iOS)
        if usesSystemPlayer { return av?.currentItem?.currentDate() }
        #endif
        guard relay != nil, let anchorTime else { return nil }
        guard let anchorWall else { return anchorTime }
        let paused = pausedTotal + (pausedAt.map { Date().timeIntervalSince($0) } ?? 0)
        return anchorTime.addingTimeInterval(Date().timeIntervalSince(anchorWall) - paused)
    }

    func stop() {
        #if os(iOS)
        tearDownSystemPlayer()
        #endif
        stopRelay()
        isSwitching = false
        retryTask?.cancel()
        timeoutTask?.cancel()
        current = nil
        mp.stop()
        state = .idle
        videoSize = .zero
        audioTracks = []
        subtitleTracks = []
    }

    func selectAudioTrack(_ id: Int32) {
        mp.currentAudioTrackIndex = id
        currentAudioTrack = mp.currentAudioTrackIndex
    }

    func selectSubtitle(_ id: Int32) {
        mp.currentVideoSubTitleIndex = id
        currentSubtitleTrack = mp.currentVideoSubTitleIndex
    }

    var statsText: String {
        let size = videoSize == .zero ? "—" : "\(Int(videoSize.width))×\(Int(videoSize.height))"
        let stats = mp.media?.statistics
        // VLC reports bitrate in bytes per microsecond-ish units; ×8000 gives kbit/s.
        let kbps = Double(stats?.demuxBitrate ?? 0) * 8000
        let rate = kbps > 0 ? String(format: "%.1f Mbps", kbps / 1000) : "measuring…"
        let lost = stats?.lostPictures ?? 0
        let format = current?.url.pathExtension.uppercased() ?? "—"
        let rewind = timeline.map { "rewind buffer \(Int($0.length) / 60) min \(Int($0.length) % 60) s" } ?? (rewindUnavailableReason.map { "rewind off: \($0)" } ?? "rewind off")
        return "\(size) · \(rate)\n\(format == "M3U8" ? "HLS" : "MPEG-TS") · buffer \((current?.options.bufferMs ?? 0) / 1000) s\n\(rewind) · dropped frames \(lost)"
    }

    // MARK: Internals

    /// MPEG-TS streams go through the local rewind buffer; anything else plays directly.
    private func startSource() {
        // Close the previous channel's provider connection first: many subscriptions allow only one.
        stopRelay(waitUntilDone: true)
        guard let current else { return }
        let minutes = current.options.timeshiftMinutes
        if minutes > 0, current.url.pathExtension != "ts", rewindUnavailableReason == nil {
            rewindUnavailableReason = "Rewind needs the MPEG-TS stream format (Settings → Stream format)."
        }
        guard minutes > 0, current.url.pathExtension == "ts",
              let relay = try? TimeshiftRelay(source: current.url, maxAge: TimeInterval(minutes * 60)) else {
            open()
            return
        }
        self.relay = relay
        retryTask?.cancel()
        state = .opening
        relay.start { [weak self] ok in
            guard let self, self.relay === relay else { return }
            if ok { self.open(fromOffset: nil) } else { self.stopRelay(); self.open() }
        }
    }

    private func stopRelay(waitUntilDone: Bool = false) {
        relay?.stop(waitUntilDone: waitUntilDone)
        relay = nil
        timeline = nil
        anchorTime = nil
        anchorWall = nil
        pausedAt = nil
        pausedTotal = 0
    }

    private func open(fromOffset: Int64? = nil) {
        guard let current else { return }
        retryTask?.cancel()
        retryPending = false
        state = .opening
        videoSize = .zero
        let mediaURL: URL
        if let relay {
            mediaURL = relay.localURL(from: fromOffset)
            anchorTime = fromOffset.map { relay.time(for: $0) } ?? Date()
            anchorWall = nil
            pausedAt = nil
            pausedTotal = 0
        } else {
            mediaURL = current.url
        }
        let media = VLCMedia(url: mediaURL)
        media.addOption(":network-caching=\(current.options.bufferMs)")
        media.addOption(":live-caching=\(current.options.bufferMs)")
        media.addOption(current.options.hardwareDecoding ? ":avcodec-hw=any" : ":avcodec-hw=none")
        media.addOption(":http-reconnect")
        mp.media = media
        openedAt = Date()
        mp.play()
        applyVolume()
        timeoutTask?.cancel()
        timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            guard !Task.isCancelled, let self, self.state == .opening else { return }
            self.failOrRetry("The channel didn't start within 15 seconds.")
        }
    }

    private func applyVolume() {
        mp.audio?.volume = Int32(isMuted ? 0 : volume * 100)
        #if os(iOS)
        if usesSystemPlayer { av?.volume = Float(volume); av?.isMuted = isMuted }
        #endif
    }

    private func refreshTracks() {
        func tracks(_ names: [Any], _ ids: [Any]) -> [Track] {
            zip(ids, names).compactMap { id, name in
                guard let n = id as? NSNumber, n.int32Value >= 0 else { return nil }
                return Track(id: n.int32Value, name: (name as? String) ?? "Track \(n)")
            }
        }
        let audio = tracks(mp.audioTrackNames, mp.audioTrackIndexes)
        let subs = tracks(mp.videoSubTitlesNames, mp.videoSubTitlesIndexes)
        if audio != audioTracks { audioTracks = audio }
        if subs != subtitleTracks { subtitleTracks = subs }
        if mp.currentAudioTrackIndex != currentAudioTrack { currentAudioTrack = mp.currentAudioTrackIndex }
        if mp.currentVideoSubTitleIndex != currentSubtitleTrack { currentSubtitleTrack = mp.currentVideoSubTitleIndex }
    }

    private func failOrRetry(_ message: String) {
        guard let current else { return }
        timeoutTask?.cancel()
        AppLog.write("player: problem (\(message)) played=\(hasPlayed) attempt=\(reconnectAttempt) relay=\(relay != nil)")
        if relay != nil, !hasPlayed {
            rewindUnavailableReason = "Rewind couldn't start on this channel. \(message)"
        }
        let maxAttempts = hasPlayed ? 3 : 1
        if current.options.autoReconnect && reconnectAttempt < maxAttempts {
            reconnectAttempt += 1
            retryPending = true
            if !hasPlayed, let alt = Self.alternateFormat(of: current.url) {
                // Some providers only serve one of the two formats; try the other before giving up.
                self.current = (alt, current.options)
            }
            state = .opening
            let delay = Double(reconnectAttempt)
            retryTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                self?.startSource()
            }
        } else {
            mp.stop()
            stopRelay()
            state = .failed(message)
        }
    }

    private static func alternateFormat(of url: URL) -> URL? {
        switch url.pathExtension {
        case "ts": url.deletingPathExtension().appendingPathExtension("m3u8")
        case "m3u8": url.deletingPathExtension().appendingPathExtension("ts")
        default: nil
        }
    }

    fileprivate func poll() {
        #if DEBUG
        if ProcessInfo.processInfo.environment["ANTENNA_VLC_LOG"] != nil { NSLog("%@", "ANTENNA state=\(mp.state.rawValue) playing=\(mp.isPlaying) ours=\(state)") }
        #endif
        guard current != nil, !retryPending else { return }
        #if os(iOS)
        if pollSystemPlayer() { return }
        #endif
        if let relay {
            let snap = relay.snapshot()
            if case .failed(let message) = snap.upstream, state == .opening {
                failOrRetry(message)
                return
            }
            if let start = snap.startTime, let end = snap.endTime, let playhead = currentPlayhead {
                let t = Timeline(start: start, end: max(end, start), playhead: min(max(playhead, start), end))
                if t != timeline { timeline = t }
            }
        }
        switch mp.state {
        case .error:
            if state == .opening || state == .playing { failOrRetry("This channel isn't responding right now.") }
            return
        case .ended:
            if state == .playing || state == .opening { failOrRetry("The stream stopped.") }
            return
        case .stopped:
            // A stream that can't be opened (404, refused, bad format) lands back on stopped, not error.
            if state == .opening, !mp.isPlaying, Date().timeIntervalSince(openedAt) > 1.5 {
                failOrRetry("This channel isn't responding right now.")
                return
            }
        default:
            break
        }
        if state == .opening, mp.isPlaying, mp.hasVideoOut || mp.time.intValue > 0 {
            state = .playing
            hasPlayed = true
            isSwitching = false
            if relay != nil, anchorWall == nil { anchorWall = Date() }
            #if os(iOS)
            scheduleHandoff(after: 8)
            #endif
            AppLog.write("player: playing via \(relay != nil ? "rewind buffer" : "direct \(current?.url.pathExtension ?? "")")")
            reconnectAttempt = 0
            timeoutTask?.cancel()
            refreshTracks()
        }
        if state == .playing {
            if videoSize == .zero, mp.videoSize != .zero { videoSize = mp.videoSize }
            refreshTracks()
        }
    }
}

extension PlayerController: VLCMediaPlayerDelegate {
    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor in self.poll() }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor in self.poll() }
    }
}

#if os(iOS)
// MARK: - Picture in picture (iPhone)

extension PlayerController: AVPictureInPictureControllerDelegate {
    /// Hands playback to Apple's player at the current moment, then floats it.
    func startPictureInPicture() {
        guard current != nil else { return }
        pendingPiP = true
        silentHandoff = false
        if usesSystemPlayer { tryStartPiP() } else if av == nil { switchToSystemPlayer() }
    }

    /// After a channel has played for a moment, quietly hand it to Apple's player so that swiping home
    /// floats it automatically. Channel changes stay on VLC, which starts faster.
    private func scheduleHandoff(after seconds: Double) {
        handoffTask?.cancel()
        guard relay != nil, !usesSystemPlayer, av == nil, handoffAttempts < 3 else { return }
        handoffTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, let self, let relay = self.relay else { return }
            guard self.state == .playing, !self.usesSystemPlayer, self.av == nil else { return }
            // Apple's player starts a few segments back from the newest one, so wait for enough recording.
            guard relay.snapshot().segmentCount >= 4 else { self.scheduleHandoff(after: 2); return }
            self.handoffAttempts += 1
            self.silentHandoff = true
            self.switchToSystemPlayer()
        }
    }

    func stopPictureInPicture() {
        pendingPiP = false
        if pip?.isPictureInPictureActive == true { pip?.stopPictureInPicture() }
    }

    /// Back in the app after swiping home: the floating video returns to the full-screen player.
    func returnFromAutomaticPiP() {
        guard isPiPActive, pipStartedAutomatically else { return }
        restoringFromPiP = true
        onPiP?(.restore)
        pip?.stopPictureInPicture()
    }

    private func switchToSystemPlayer() {
        guard av == nil else { return }
        // The simulator can't show the floating window, but the hand-off to Apple's player can still be tested there.
        #if targetEnvironment(simulator)
        let supported = true
        #else
        let supported = AVPictureInPictureController.isPictureInPictureSupported()
        #endif
        guard supported else {
            pipUnavailable("This device doesn't support picture in picture.")
            return
        }
        let url: URL
        if let relay {
            url = relay.hlsURL
        } else if let current, current.url.pathExtension == "m3u8" {
            url = current.url
        } else {
            pipUnavailable("Picture in picture needs the rewind buffer on this channel (Settings → Rewind buffer).")
            return
        }
        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = 4
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        let layer = AVPlayerLayer(player: player)
        layer.videoGravity = .resizeAspect
        layer.opacity = 0
        videoView.playerLayer = layer
        av = player
        avLayer = layer
        // Pick up where VLC is, but never closer to the live edge than Apple's player can start.
        if let relay, let playhead = currentPlayhead, let end = relay.snapshot().endTime {
            avSeekTarget = min(playhead, end.addingTimeInterval(-7))
        } else {
            avSeekTarget = nil
        }
        AppLog.write("pip: starting Apple player on \(url.absoluteString)")
        avStatusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in self?.systemItemStatusChanged(item) }
        }
        if let controller = AVPictureInPictureController(playerLayer: layer) {
            controller.delegate = self
            controller.canStartPictureInPictureAutomaticallyFromInline = true
            pip = controller
            pipPossibleObservation = controller.observe(\.isPictureInPicturePossible, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in self?.tryStartPiP() }
            }
        }
        avDeadline = Task { [weak self] in
            try? await Task.sleep(for: .seconds(20))
            guard !Task.isCancelled, let self, !self.usesSystemPlayer, self.av != nil else { return }
            self.pipUnavailable("Picture in picture took too long to start on this channel. Try again in a few seconds.")
        }
    }

    private func systemItemStatusChanged(_ item: AVPlayerItem) {
        guard av?.currentItem === item else { return }
        switch item.status {
        case .readyToPlay:
            if let target = avSeekTarget {
                avSeekTarget = nil
                _ = item.seek(to: target) { [weak self] _ in Task { @MainActor in self?.av?.play() } }
            } else {
                av?.play()
            }
        case .failed:
            AppLog.write("pip: Apple player failed: \(item.error?.localizedDescription ?? "unknown")")
            if usesSystemPlayer { fallBackToVLC() } else { pipUnavailable("This channel can't play in picture in picture.") }
        default:
            break
        }
    }

    /// Called from the poll loop. Returns true while Apple's player owns playback.
    func pollSystemPlayer() -> Bool {
        guard let av else { return false }
        if !usesSystemPlayer {
            if av.timeControlStatus == .playing, av.currentItem?.currentDate() != nil { completeSwitch() }
            return false
        }
        switch av.timeControlStatus {
        case .playing: if state != .playing { state = .playing }
        case .paused: if state == .playing { state = .paused }
        default: break
        }
        if let item = av.currentItem {
            if videoSize == .zero, item.presentationSize != .zero { videoSize = item.presentationSize }
            if let relay, let start = relay.snapshot().startTime, let end = relay.snapshot().endTime, let playhead = item.currentDate() {
                let t = Timeline(start: start, end: max(end, start), playhead: min(max(playhead, start), end))
                if t != timeline { timeline = t }
            }
        }
        return true
    }

    private func completeSwitch() {
        guard let av, let item = av.currentItem else { return }
        let hasAudio = item.tracks.contains { $0.assetTrack?.mediaType == .audio }
        if !hasAudio && !audioTracks.isEmpty {
            pipUnavailable("This channel's sound format doesn't work in picture in picture.")
            return
        }
        avDeadline?.cancel()
        silentHandoff = false
        usesSystemPlayer = true
        avLayer?.opacity = 1
        av.isMuted = isMuted
        av.volume = Float(volume)
        mp.stop()
        state = .playing
        AppLog.write("pip: Apple player took over")
        tryStartPiP()
    }

    private func tryStartPiP() {
        guard pendingPiP, usesSystemPlayer, let pip, pip.isPictureInPicturePossible, !pip.isPictureInPictureActive else { return }
        pendingPiP = false
        pip.startPictureInPicture()
    }

    private func pipUnavailable(_ message: String) {
        AppLog.write("pip: unavailable\(silentHandoff ? " (background hand-off)" : ""): \(message)")
        let quiet = silentHandoff
        tearDownSystemPlayer()
        if quiet {
            // Nobody asked for it yet, so just try again a little later.
            scheduleHandoff(after: 20)
            return
        }
        pipNotice = message
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            if self?.pipNotice == message { self?.pipNotice = nil }
        }
    }

    /// Apple's player failed mid-show: go back to VLC at the same moment.
    private func fallBackToVLC() {
        let playhead = currentPlayhead
        let wasFloating = isPiPActive
        tearDownSystemPlayer()
        if wasFloating { onPiP?(.restore) }
        if let relay, let playhead { open(fromOffset: relay.offset(for: playhead)) } else { startSource() }
    }

    func tearDownSystemPlayer() {
        handoffTask?.cancel()
        silentHandoff = false
        pipStartedAutomatically = false
        avDeadline?.cancel()
        avStatusObservation = nil
        pipPossibleObservation = nil
        if pip?.isPictureInPictureActive == true { pip?.stopPictureInPicture() }
        pip?.delegate = nil
        pip = nil
        av?.pause()
        av = nil
        videoView.playerLayer = nil
        avLayer = nil
        pendingPiP = false
        avSeekTarget = nil
        if usesSystemPlayer { usesSystemPlayer = false }
        if isPiPActive { isPiPActive = false }
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor in
            self.isPiPActive = true
            self.pipStartedAutomatically = UIApplication.shared.applicationState != .active
            self.onPiP?(.started)
        }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in
            self.restoringFromPiP = true
            self.onPiP?(.restore)
            // Give the full-screen player a moment to reappear before the video flies back into it.
            try? await Task.sleep(for: .milliseconds(450))
            completionHandler(true)
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor in
            self.isPiPActive = false
            let restored = self.restoringFromPiP
            self.restoringFromPiP = false
            self.onPiP?(.stopped(restored: restored))
        }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                failedToStartPictureInPictureWithError error: Error) {
        Task { @MainActor in self.pipUnavailable("Picture in picture couldn't start. \(error.localizedDescription)") }
    }
}
#endif
