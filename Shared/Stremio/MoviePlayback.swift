import Foundation
import Observation
#if os(iOS)
import UIKit
#endif

/// A movie or episode playing on this device: what it is, which source, and how it's getting on.
@MainActor @Observable
final class MoviePlayback: Identifiable {
    let id = UUID()
    let meta: StremioMeta
    var detail: StremioMetaDetail?
    let videoID: String
    let video: StremioVideo?
    let stream: StremioStream
    /// What's happening while it starts (torrents take a few seconds); nil once playing.
    var step: String? = "Starting"
    var error: String?
    var subtitles: [StremioSubtitle] = []
    var torrent: TorrentStreamer.Stats?
    var fileName: String?
    var fileSize: Int64?
    #if os(iOS)
    /// Extra time from iOS so a torrent keeps starting if you switch apps; once playing, background audio keeps it going.
    var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
    #endif

    init(meta: StremioMeta, detail: StremioMetaDetail?, video: StremioVideo?, videoID: String, stream: StremioStream) {
        self.meta = meta; self.detail = detail; self.video = video; self.videoID = videoID; self.stream = stream
        fileName = stream.filename
        fileSize = stream.videoSize
    }

    var title: String { detail?.meta.name ?? meta.name }
    var episode: StremioVideo? { detail?.videos.first { $0.id == videoID } ?? video }
    var subtitle: String? {
        guard let e = episode else { return nil }
        return e.season != nil ? [e.label, e.title].filter { !$0.isEmpty }.joined(separator: " · ") : e.title
    }
    /// The episode after this one, in watching order.
    var next: StremioVideo? {
        guard let videos = detail?.videos, !videos.isEmpty else { return nil }
        let ordered = videos.filter { ($0.season ?? 0) > 0 }.sorted { ($0.season ?? 0, $0.episode ?? 0) < ($1.season ?? 0, $1.episode ?? 0) }
        let list = ordered.isEmpty ? videos : ordered
        guard let i = list.firstIndex(where: { $0.id == videoID }), i + 1 < list.count else { return nil }
        return list[i + 1]
    }
}

/// Picture in picture for movies: the Mac's own floating window, or Apple's on iPhone (formats it can play).
@MainActor protocol MoviePiPPresenting: AnyObject {
    func showMovie()
    func hideMovie()
    /// False when the window appears later (iPhone): the model then waits for the system's start event.
    var movieActivatesImmediately: Bool { get }
}

extension AppModel {
    func enterMoviePiP() {
        guard movie != nil, !isMoviePiP, let presenter = pipPresenter as? MoviePiPPresenting else { return }
        if presenter.movieActivatesImmediately { isMoviePiP = true }
        presenter.showMovie()
    }

    func exitMoviePiP(returnToApp: Bool) {
        guard isMoviePiP else { return }
        if returnToApp {
            (pipPresenter as? MoviePiPPresenting)?.hideMovie()
            isMoviePiP = false
            pipPresenter?.bringAppForward()
        } else {
            stopMovie()
        }
    }

    /// Apple's picture-in-picture events for movies (iPhone).
    func handleMoviePiP(_ event: PlayerController.PiPEvent) {
        switch event {
        case .started: isMoviePiP = true
        case .restore: isMoviePiP = false
        case .stopped(let restored):
            // Closing the floating video with its ✕ stops the movie, as on the Mac.
            if !restored && isMoviePiP { isMoviePiP = false; stopMovie() }
        }
    }
}

extension AppModel {
    /// Plays a source on this device. Torrents stream through the built-in engine.
    func playMovie(meta: StremioMeta, detail: StremioMetaDetail?, video: StremioVideo?, videoID: String,
                   stream: StremioStream, startAt: TimeInterval? = nil) {
        guard stream.playable else {
            showToast(stream.ytID != nil ? "YouTube sources can't play here yet. Pick another one." : "This source only opens on its own website. Pick another one.")
            return
        }
        if playing != nil { stopPlaying() }
        finishMovie()
        let p = MoviePlayback(meta: meta, detail: detail, video: video, videoID: videoID, stream: stream)
        movie = p
        let item = stremio.libraryItem(meta.id)
        let resume = startAt ?? item.flatMap { i -> TimeInterval? in
            guard i.videoID == videoID, i.timeOffset > 5_000, i.duration == 0 || i.timeOffset < i.duration * 0.95 else { return nil }
            return i.timeOffset / 1000
        } ?? 0
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = true
        p.backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Starting a movie") { [weak p] in p?.endBackgroundTask() }
        #endif

        movieTask = Task { [weak self, weak p] in
            guard let self, let p else { return }
            if p.detail == nil, let d = await self.stremio.meta(for: meta), self.movie === p { p.detail = d }
            let url: URL
            if let hash = stream.infoHash {
                let statsTask = Task { [weak p] in
                    while !Task.isCancelled, let p, p.step != nil {
                        p.torrent = TorrentStreamer.shared.stats()
                        try? await Task.sleep(for: .seconds(1))
                    }
                }
                defer { statsTask.cancel() }
                do {
                    let opened = try await TorrentStreamer.shared.open(infoHash: hash, fileIdx: stream.fileIdx, sources: stream.sources,
                                                                       season: video?.season, episode: video?.episode) { text in
                        Task { @MainActor in if p.step != nil { p.step = text } }
                    }
                    guard self.movie === p else { return }
                    p.fileName = p.fileName ?? opened.fileName
                    p.fileSize = p.fileSize ?? opened.size
                    p.step = "Downloading the start of the video"
                    url = opened.url
                } catch {
                    if self.movie === p { p.error = error.localizedDescription; p.step = nil }
                    return
                }
            } else if let s = stream.url, let u = URL(string: s) {
                url = u
            } else {
                p.error = "This source's link is broken. Try another one."
                return
            }
            guard self.movie === p else { return }
            self.vod.open(url, startAt: resume, userAgent: stream.headers["User-Agent"], referrer: stream.headers["Referer"], torrent: stream.isTorrent)
            Task { [weak self, weak p] in await self?.loadMovieSubtitles(p) }
            // Torrent progress for the loading screen, then progress saving while watching.
            var lastSave = Date.distantPast
            while !Task.isCancelled, self.movie === p {
                if stream.isTorrent { p.torrent = TorrentStreamer.shared.stats() }
                switch self.vod.state {
                case .playing:
                    p.step = nil
                    #if os(iOS)
                    p.endBackgroundTask()
                    #endif
                    if Date().timeIntervalSince(lastSave) >= 5 { lastSave = Date(); self.saveMovieProgress(final: false) }
                case .failed(let message):
                    if p.error == nil { p.error = message }
                case .ended:
                    p.step = nil
                default:
                    break
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    func stopMovie() { finishMovie() }

    private func finishMovie() {
        guard let p = movie else { return }
        if isMoviePiP {
            isMoviePiP = false
            (pipPresenter as? MoviePiPPresenting)?.hideMovie()
        }
        saveMovieProgress(final: true)
        movieTask?.cancel()
        vod.stop()
        movie = nil
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = false
        p.endBackgroundTask()
        #endif
        if p.stream.isTorrent { DispatchQueue.global().async { TorrentStreamer.shared.stop() } }
    }

    private func saveMovieProgress(final: Bool) {
        guard let p = movie else { return }
        stremio.recordProgress(meta: p.detail?.meta ?? p.meta, videoID: p.videoID, nextVideoID: p.next?.id,
                               time: vod.time, duration: vod.length, final: final)
    }

    private func loadMovieSubtitles(_ p: MoviePlayback?) async {
        guard let p else { return }
        let subs = await stremio.subtitles(type: p.meta.type, videoID: p.videoID, fileName: p.fileName, fileSize: p.fileSize)
        guard movie === p else { return }
        p.subtitles = subs
        // Turn on the language picked last time, unless the file already has it.
        guard let wanted = UserDefaults.standard.string(forKey: "subtitleLang"), let match = subs.first(where: { $0.lang == wanted }) else { return }
        for _ in 0..<20 where vod.state != .playing { try? await Task.sleep(for: .milliseconds(500)) }
        let name = StremioSubtitle.languageName(wanted)
        if movie === p, !vod.subtitleTracks.contains(where: { $0.name.localizedCaseInsensitiveContains(name) }) { vod.addSubtitle(match.url) }
    }

    func chooseMovieSubtitle(_ s: StremioSubtitle) {
        vod.addSubtitle(s.url)
        UserDefaults.standard.set(s.lang, forKey: "subtitleLang")
    }

    func movieSubtitlesOff() {
        vod.selectSubtitle(-1)
        UserDefaults.standard.removeObject(forKey: "subtitleLang")
    }

    /// The next episode, with the same kind of source (same add-on and quality) when one turns up.
    func playNextEpisode() {
        guard let p = movie, let next = p.next else { return }
        let meta = p.meta, detail = p.detail, group = p.stream.bingeGroup, addon = p.stream.addonName
        finishMovie()
        showToast("Finding \(next.label)…")
        Task {
            var fallback: StremioStream?
            for await groups in stremio.sources(type: meta.type, videoID: next.id) {
                let streams = groups.compactMap { g -> [StremioStream]? in if case .loaded(let s) = g.state { s } else { nil } }.flatMap { $0 }
                if let group, let match = streams.first(where: { $0.bingeGroup == group && $0.playable }) {
                    playMovie(meta: meta, detail: detail, video: next, videoID: next.id, stream: match)
                    return
                }
                fallback = fallback ?? streams.first { $0.addonName == addon && $0.playable }
            }
            if let fallback { playMovie(meta: meta, detail: detail, video: next, videoID: next.id, stream: fallback) }
            else { showToast("No sources found for \(next.label).") }
        }
    }

    /// Moves what's playing here to a TV, at the same moment.
    func moveMovieToTV(_ tv: CastManager.Device) {
        guard let p = movie else { return }
        let at = vod.time
        let meta = p.detail?.meta ?? p.meta
        finishMovie()
        let body = stremio.castBody(meta: meta, videoID: p.videoID, episodeTitle: p.episode?.title, stream: p.stream, startAt: at)
        Task {
            do {
                try await cast.playStremio(body, on: tv)
                showToast("Playing on \(tv.displayName)")
            } catch {
                showToast((error as? LocalizedError)?.errorDescription ?? "Couldn't play on the TV.")
            }
        }
    }
}
