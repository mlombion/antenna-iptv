import AppKit
import SwiftUI

/// The Mac player for movies and episodes. Space pauses, ← / → jump 10 s (with ⇧, 60 s), F is full screen,
/// Esc closes. Controls fade after a few seconds without the mouse moving.
struct MoviePlayerView: View {
    @Environment(AppModel.self) private var model
    let movie: MoviePlayback
    @FocusState private var focused: Bool
    @State private var showUI = true
    @State private var hideTask: Task<Void, Never>?
    @State private var dragFraction: Double?
    @State private var flash: String?

    private var vod: VodPlayer { model.vod }
    private var loading: Bool { movie.error == nil && (movie.step != nil || vod.state == .opening) }

    var body: some View {
        ZStack {
            Color.black
            VodSurfaceMac(player: vod, active: !model.isMoviePiP)
            if loading { loadingCover }
            if let error = movie.error {
                Color.black.opacity(0.7)
                VStack(spacing: 8) {
                    Text("Can't play this source").font(.system(size: 18, weight: .semibold))
                    Text(error).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center)
                    Button("Back to sources") { model.stopMovie() }.buttonStyle(PillButtonStyle(prominent: true)).padding(.top, 6)
                }
                .padding(28).frame(maxWidth: 480)
            }
            if vod.state == .paused, !showUI {
                Image(systemName: "play.fill").font(.system(size: 34)).frame(width: 84, height: 84)
                    .background(.black.opacity(0.45), in: Circle()).allowsHitTesting(false)
            }
            if vod.state == .ended, let next = movie.next {
                VStack(spacing: 8) {
                    Text("UP NEXT").font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.accent)
                    Text("\(next.label) · \(next.title)").font(.system(size: 20, weight: .bold))
                    Button { model.playNextEpisode() } label: { Label("Play", systemImage: "play.fill") }
                        .buttonStyle(PillButtonStyle(prominent: true)).padding(.top, 4)
                }
                .padding(26).background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
            }
            if let flash {
                Text(flash).font(.system(size: 22, weight: .bold)).padding(.horizontal, 22).padding(.vertical, 12)
                    .background(.black.opacity(0.55), in: Capsule()).allowsHitTesting(false)
            }
            osd.opacity(showUI || loading ? 1 : 0)
        }
        .foregroundStyle(.white)
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onAppear { focused = true; poke() }
        .onDisappear { hideTask?.cancel(); NSCursor.setHiddenUntilMouseMoves(false) }
        .onContinuousHover { _ in poke() }
        .onTapGesture(count: 2) { NSApp.keyWindow?.toggleFullScreen(nil) }
        .onTapGesture { vod.togglePause(); poke() }
        .onKeyPress(.escape) { model.stopMovie(); return .handled }
        .onKeyPress(.space) { vod.togglePause(); poke(); return .handled }
        .onKeyPress(keys: [.leftArrow, .rightArrow]) { press in
            let step: TimeInterval = press.modifiers.contains(.shift) ? 60 : 10
            skip(press.key == .leftArrow ? -step : step)
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "fnp")) { press in
            if press.characters == "f" { NSApp.keyWindow?.toggleFullScreen(nil) }
            if press.characters == "p" { model.enterMoviePiP() }
            if press.characters == "n", movie.next != nil { model.playNextEpisode() }
            return .handled
        }
    }

    private var loadingCover: some View {
        ZStack {
            Color.black.overlay {
                AsyncImage(url: movie.meta.background ?? movie.detail?.meta.background ?? movie.meta.poster) { $0.resizable().scaledToFill() } placeholder: { Color.black }
                    .opacity(0.3)
            }
            .clipped()
            VStack(spacing: 12) {
                Text(movie.title).font(.system(size: 30, weight: .bold))
                if let s = movie.subtitle { Text(s).font(.system(size: 15)).foregroundStyle(.white.opacity(0.7)) }
                ProgressView().controlSize(.large).tint(.white).padding(.top, 6)
                Text(movie.step ?? "Opening").font(.system(size: 14)).foregroundStyle(.white.opacity(0.8))
                if let t = movie.torrent {
                    Text(t.hasMetadata ? "\(t.peers) peers · \(speed(t.downloadRate)) · \(t.bufferedAhead / 1_048_576) MB ready" : "Looking for peers…")
                        .font(.system(size: 12, design: .monospaced)).foregroundStyle(.white.opacity(0.55))
                }
                Text(movie.stream.title).font(.system(size: 11)).foregroundStyle(.white.opacity(0.4)).lineLimit(1).frame(maxWidth: 520)
            }
        }
        .allowsHitTesting(false)
    }

    private var osd: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                Button { model.stopMovie() } label: {
                    Image(systemName: "chevron.left").font(.system(size: 15, weight: .bold)).frame(width: 36, height: 36)
                        .background(.white.opacity(0.14), in: Circle())
                }
                .buttonStyle(.plain).help("Back (Esc)")
                VStack(alignment: .leading, spacing: 2) {
                    Text(movie.title).font(.system(size: 20, weight: .bold)).lineLimit(1)
                    if let s = movie.subtitle { Text(s).font(.system(size: 13)).foregroundStyle(.white.opacity(0.7)).lineLimit(1) }
                }
                Spacer()
                if !model.cast.devices.isEmpty {
                    Menu {
                        ForEach(model.cast.devices) { tv in Button(tv.displayName) { model.moveMovieToTV(tv) } }
                    } label: { Label("Play on TV", systemImage: "tv") }
                    .menuStyle(.borderlessButton).fixedSize()
                    .padding(.horizontal, 12).frame(height: 32).background(.white.opacity(0.14), in: Capsule())
                }
                Button { model.enterMoviePiP() } label: {
                    Image(systemName: "pip.enter").frame(width: 32, height: 32).background(.white.opacity(0.14), in: Circle())
                }
                .buttonStyle(.plain).help("Picture in picture (P)")
                Button { NSApp.keyWindow?.toggleFullScreen(nil) } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 32, height: 32).background(.white.opacity(0.14), in: Circle())
                }
                .buttonStyle(.plain).help("Full screen (F)")
            }
            .padding(.horizontal, 28).padding(.top, 40).padding(.bottom, 30)
            .background(LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom))

            Spacer()

            if !loading && movie.error == nil {
                VStack(spacing: 14) {
                    scrubber
                    HStack(spacing: 10) {
                        icon("gobackward.10", "Back 10 s (←)") { skip(-10) }
                        icon(vod.state == .paused ? "play.fill" : "pause.fill", "Play / pause (Space)", big: true) { vod.togglePause(); poke() }
                        icon("goforward.10", "Forward 10 s (→)") { skip(10) }
                        Spacer()
                        subtitlesMenu
                        audioMenu
                        if movie.next != nil {
                            Button { model.playNextEpisode() } label: { Label("Next episode", systemImage: "forward.end.fill") }
                                .buttonStyle(PillButtonStyle()).help("Next episode (N)")
                        }
                    }
                }
                .padding(.horizontal, 28).padding(.bottom, 26).padding(.top, 40)
                .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom))
            }
        }
    }

    private var scrubber: some View {
        let fraction = dragFraction ?? (vod.length > 0 ? min(1, vod.time / vod.length) : 0)
        return HStack(spacing: 12) {
            Text(RewindBar.format(fraction * vod.length)).font(.system(size: 12, design: .monospaced))
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.22)).frame(height: 5)
                    Capsule().fill(Theme.accent).frame(width: max(5, g.size.width * fraction), height: 5)
                    Circle().fill(.white).frame(width: 14, height: 14).offset(x: max(0, g.size.width * fraction - 7))
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { dragFraction = min(1, max(0, $0.location.x / g.size.width)); poke() }
                    .onEnded { v in
                        let f = min(1, max(0, v.location.x / g.size.width))
                        dragFraction = nil
                        vod.seek(to: f * vod.length)
                    })
            }
            .frame(height: 20)
            Text("−" + RewindBar.format(max(0, vod.length - fraction * vod.length))).font(.system(size: 12, design: .monospaced)).foregroundStyle(.white.opacity(0.6))
        }
    }

    private var subtitlesMenu: some View {
        Menu {
            Button { model.movieSubtitlesOff() } label: { if vod.currentSubtitleTrack < 0 { Label("Off", systemImage: "checkmark") } else { Text("Off") } }
            ForEach(vod.subtitleTracks) { t in
                Button { vod.selectSubtitle(t.id) } label: { if t.id == vod.currentSubtitleTrack { Label(t.name, systemImage: "checkmark") } else { Text(t.name) } }
            }
            if !movie.subtitles.isEmpty {
                Section("From your add-ons") { ForEach(movie.subtitles) { s in Button(s.languageName) { model.chooseMovieSubtitle(s) } } }
            }
        } label: { Label("Subtitles", systemImage: "captions.bubble") }
        .menuStyle(.borderlessButton).fixedSize()
        .padding(.horizontal, 12).frame(height: 32).background(.white.opacity(0.14), in: Capsule())
    }

    private var audioMenu: some View {
        Menu {
            ForEach(vod.audioTracks) { t in
                Button { vod.selectAudioTrack(t.id) } label: { if t.id == vod.currentAudioTrack { Label(t.name, systemImage: "checkmark") } else { Text(t.name) } }
            }
        } label: { Label("Audio", systemImage: "waveform") }
        .menuStyle(.borderlessButton).fixedSize()
        .padding(.horizontal, 12).frame(height: 32).background(.white.opacity(0.14), in: Capsule())
        .disabled(vod.audioTracks.count < 2)
    }

    private func icon(_ symbol: String, _ help: String, big: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: big ? 20 : 15, weight: .semibold))
                .frame(width: big ? 48 : 38, height: big ? 48 : 38)
                .background(big ? Theme.accent : .white.opacity(0.14), in: Circle())
                .foregroundStyle(big ? Theme.accentInk : .white)
        }
        .buttonStyle(.plain).help(help)
    }

    private func skip(_ seconds: TimeInterval) {
        vod.seek(by: seconds)
        flash = seconds < 0 ? "−\(Int(-seconds)) s" : "+\(Int(seconds)) s"
        poke()
        Task {
            try? await Task.sleep(for: .milliseconds(700))
            flash = nil
        }
    }

    private func poke() {
        showUI = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(3.2))
            guard !Task.isCancelled, vod.state == .playing else { return }
            showUI = false
            NSCursor.setHiddenUntilMouseMoves(true)
        }
    }

    private func speed(_ bps: Int) -> String { bps >= 1_048_576 ? String(format: "%.1f MB/s", Double(bps) / 1_048_576) : "\(bps / 1024) KB/s" }
}

/// Hosts the movie player's video view. Only the active surface (full player or floating window) takes it,
/// so a view that's fading out can't pull the video back.
struct VodSurfaceMac: NSViewRepresentable {
    let player: VodPlayer
    var active = true

    func makeNSView(context: Context) -> NSView {
        let host = NSView()
        host.wantsLayer = true
        host.layer?.backgroundColor = NSColor.black.cgColor
        if active { attach(to: host) }
        return host
    }

    func updateNSView(_ host: NSView, context: Context) {
        if active, player.videoView.superview !== host { attach(to: host) }
    }

    private func attach(to host: NSView) {
        let v = player.videoView
        v.removeFromSuperview()
        v.frame = host.bounds
        v.autoresizingMask = [.width, .height]
        host.addSubview(v)
    }
}

/// The movie in the floating window: hover for controls, double-click to return to Antenna.
struct MoviePiPView: View {
    @Environment(AppModel.self) private var model
    @State private var hover = false
    @FocusState private var focused: Bool

    private var vod: VodPlayer { model.vod }

    var body: some View {
        ZStack {
            Color.black
            VodSurfaceMac(player: vod, active: model.isMoviePiP)
            WindowDragArea(onDoubleClick: { model.exitMoviePiP(returnToApp: true) },
                           onHover: { h in withAnimation(.easeOut(duration: 0.15)) { hover = h } })
            if vod.state == .opening { ProgressView().controlSize(.small).tint(.white).allowsHitTesting(false) }
            controls.opacity(hover ? 1 : 0)
        }
        .foregroundStyle(.white)
        .ignoresSafeArea()
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onAppear { focused = true }
        .onChange(of: vod.videoSize) { _, size in model.pipPresenter?.updateAspectRatio(size) }
        .onKeyPress(.escape) { model.exitMoviePiP(returnToApp: true); return .handled }
        .onKeyPress(.space) { vod.togglePause(); return .handled }
        .onKeyPress(keys: [.leftArrow, .rightArrow]) { press in
            vod.seek(by: (press.key == .leftArrow ? -1 : 1) * (press.modifiers.contains(.shift) ? 60 : 10))
            return .handled
        }
    }

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.65), .clear, .clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack {
                    button("arrow.up.left.and.arrow.down.right", "Back to Antenna (Esc)", 12) { model.exitMoviePiP(returnToApp: true) }
                    Spacer()
                    button("xmark", "Close", 12) { model.exitMoviePiP(returnToApp: false) }
                }
                .padding(8)
                Spacer()
                HStack(spacing: 18) {
                    button("gobackward.10", "Back 10 s (←)", 15) { vod.seek(by: -10) }
                    button(vod.state == .paused ? "play.fill" : "pause.fill", "Pause (space)", 20) { vod.togglePause() }
                    button("goforward.10", "Forward 10 s (→)", 15) { vod.seek(by: 10) }
                }
                Spacer()
                VStack(alignment: .leading, spacing: 4) {
                    if let m = model.movie {
                        Text(m.subtitle.map { "\(m.title) · \($0)" } ?? m.title).font(.system(size: 11.5, weight: .semibold)).lineLimit(1)
                    }
                    ProgressLine(value: vod.length > 0 ? vod.time / vod.length : 0, height: 3)
                }
                .padding(.horizontal, 10).padding(.bottom, 9)
            }
        }
    }

    private func button(_ symbol: String, _ help: String, _ size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: size, weight: .semibold))
                .frame(width: size + 18, height: size + 18)
                .background(.black.opacity(0.35), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .help(help)
    }
}
