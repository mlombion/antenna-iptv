import SwiftUI

/// Full-screen player for movies and episodes. Tap shows the controls; double-tap the left or right side
/// to go back or forward 10 seconds.
struct PhoneMoviePlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let movie: MoviePlayback
    @State private var showUI = true
    @State private var hideTask: Task<Void, Never>?
    @State private var dragFraction: Double?
    @State private var flash: (text: String, leading: Bool)?

    private var vod: VodPlayer { model.vod }
    private var loading: Bool { movie.error == nil && (movie.step != nil || vod.state == .opening) }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                VodSurface(player: vod).ignoresSafeArea()
                gestures(width: geo.size.width)
                if loading { loadingCover }
                if let error = movie.error { panel("Can't play this source", error + "\nClose the player and pick another source.") }
                if vod.state == .ended, let next = movie.next { upNext(next) }
                if let flash {
                    Text(flash.text).font(.system(size: 15, weight: .bold))
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.black.opacity(0.55), in: Capsule())
                        .frame(maxWidth: .infinity, alignment: flash.leading ? .leading : .trailing)
                        .padding(.horizontal, 40)
                        .allowsHitTesting(false)
                }
                controls.opacity(showUI || loading || vod.state == .paused ? 1 : 0).allowsHitTesting(showUI || loading || vod.state == .paused)
                if let notice = vod.pipNotice {
                    Text(notice).font(.system(size: 13, weight: .medium)).multilineTextAlignment(.center)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 28)
                        .frame(maxHeight: .infinity, alignment: .top).padding(.top, 70)
                        .allowsHitTesting(false)
                }
            }
        }
        .foregroundStyle(.white)
        .statusBarHidden(!showUI)
        .persistentSystemOverlays(.hidden)
        .animation(.easeOut(duration: 0.2), value: showUI)
        .onAppear { poke() }
    }

    // MARK: Pieces

    private var loadingCover: some View {
        ZStack {
            // The backdrop fills the screen without making the stack wider than it.
            Color.black.overlay {
                AsyncImage(url: movie.meta.background ?? movie.detail?.meta.background ?? movie.meta.poster) { $0.resizable().scaledToFill() } placeholder: { Color.black }
                    .opacity(0.3)
            }
            .clipped().ignoresSafeArea()
            VStack(spacing: 12) {
                Text(movie.title).font(.system(size: 24, weight: .bold)).multilineTextAlignment(.center)
                if let s = movie.subtitle { Text(s).font(.system(size: 15)).foregroundStyle(.white.opacity(0.7)) }
                ProgressView().controlSize(.large).tint(.white).padding(.top, 6)
                Text(movie.step ?? "Opening").font(.system(size: 14)).foregroundStyle(.white.opacity(0.8))
                if let t = movie.torrent {
                    Text(t.hasMetadata ? "\(t.peers) peers · \(speed(t.downloadRate)) · \(t.bufferedAhead / 1_048_576) MB ready" : "Looking for peers…")
                        .font(.system(size: 12, design: .monospaced)).foregroundStyle(.white.opacity(0.55))
                }
            }
            .padding(24)
        }
        .allowsHitTesting(false)
    }

    private func panel(_ title: String, _ message: String) -> some View {
        VStack(spacing: 8) {
            Text(title).font(.system(size: 18, weight: .semibold))
            Text(message).font(.system(size: 14)).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center)
        }
        .padding(22)
        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
        .padding(30)
    }

    private func upNext(_ next: StremioVideo) -> some View {
        Button { model.playNextEpisode() } label: {
            VStack(spacing: 6) {
                Text("UP NEXT").font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.accent)
                Text("\(next.label) · \(next.title)").font(.system(size: 17, weight: .bold)).multilineTextAlignment(.center)
                Label("Play", systemImage: "play.fill").font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 18).frame(height: 36).background(Theme.accent, in: Capsule()).foregroundStyle(Theme.accentInk)
                    .padding(.top, 6)
            }
            .padding(22)
            .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    private func gestures(width: CGFloat) -> some View {
        HStack(spacing: 0) {
            Color.clear.contentShape(Rectangle())
                .onTapGesture(count: 2) { skip(-10, leading: true) }
                .onTapGesture { toggleUI() }
            Color.clear.contentShape(Rectangle())
                .onTapGesture(count: 2) { skip(10, leading: false) }
                .onTapGesture { toggleUI() }
        }
        .ignoresSafeArea()
    }

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea().allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack(alignment: .top, spacing: 12) {
                    Button { model.stopMovie(); dismiss() } label: {
                        Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).frame(width: 40, height: 40)
                            .background(.white.opacity(0.15), in: Circle())
                    }
                    .buttonStyle(.plain)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(movie.title).font(.system(size: 17, weight: .bold)).lineLimit(1)
                        if let s = movie.subtitle { Text(s).font(.system(size: 13)).foregroundStyle(.white.opacity(0.7)).lineLimit(1) }
                    }
                    Spacer()
                    Button { model.enterMoviePiP() } label: {
                        Image(systemName: "pip.enter").font(.system(size: 16, weight: .semibold)).frame(width: 40, height: 40)
                            .background(.white.opacity(0.15), in: Circle())
                    }
                    .buttonStyle(.plain)
                    if !model.cast.devices.isEmpty {
                        Menu {
                            ForEach(model.cast.devices) { tv in
                                Button { model.moveMovieToTV(tv); dismiss() } label: { Label(tv.displayName, systemImage: "tv") }
                            }
                        } label: {
                            Image(systemName: "tv.and.mediabox").font(.system(size: 16, weight: .semibold)).frame(width: 40, height: 40)
                                .background(.white.opacity(0.15), in: Circle())
                        }
                    }
                }
                .padding(.horizontal, 16).padding(.top, 8)

                Spacer()
                if !loading && movie.error == nil {
                    HStack(spacing: 44) {
                        round("gobackward.10", 26) { skip(-10, leading: true) }
                        round(vod.state == .paused ? "play.fill" : "pause.fill", 34) { vod.togglePause(); poke() }
                        round("goforward.10", 26) { skip(10, leading: false) }
                    }
                }
                Spacer()

                if !loading && movie.error == nil {
                    VStack(spacing: 12) {
                        scrubber
                        HStack(spacing: 10) {
                            subtitlesMenu
                            audioMenu
                            if movie.next != nil {
                                Button { model.playNextEpisode() } label: { chip("Next episode", "forward.end.fill") }.buttonStyle(.plain)
                            }
                            Spacer()
                        }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 10)
                }
            }
        }
    }

    private var scrubber: some View {
        let fraction = dragFraction ?? (vod.length > 0 ? min(1, vod.time / vod.length) : 0)
        return VStack(spacing: 4) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.25)).frame(height: 4)
                    Capsule().fill(Theme.accent).frame(width: max(4, g.size.width * fraction), height: 4)
                    Circle().fill(.white).frame(width: 16, height: 16).offset(x: max(0, g.size.width * fraction - 8))
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
            .frame(height: 24)
            HStack {
                Text(RewindBar.format(fraction * vod.length))
                Spacer()
                Text("−" + RewindBar.format(max(0, vod.length - fraction * vod.length))).foregroundStyle(.white.opacity(0.6))
            }
            .font(.system(size: 12, weight: .medium, design: .monospaced))
        }
    }

    private var subtitlesMenu: some View {
        Menu {
            Button { model.movieSubtitlesOff() } label: { if vod.currentSubtitleTrack < 0 { Label("Off", systemImage: "checkmark") } else { Text("Off") } }
            ForEach(vod.subtitleTracks) { t in
                Button { vod.selectSubtitle(t.id) } label: { if t.id == vod.currentSubtitleTrack { Label(t.name, systemImage: "checkmark") } else { Text(t.name) } }
            }
            if !movie.subtitles.isEmpty {
                Section("From your add-ons") {
                    ForEach(movie.subtitles) { s in Button(s.languageName) { model.chooseMovieSubtitle(s) } }
                }
            }
        } label: { chip("Subtitles", "captions.bubble") }
    }

    private var audioMenu: some View {
        Menu {
            ForEach(vod.audioTracks) { t in
                Button { vod.selectAudioTrack(t.id) } label: { if t.id == vod.currentAudioTrack { Label(t.name, systemImage: "checkmark") } else { Text(t.name) } }
            }
        } label: { chip("Audio", "waveform") }
        .disabled(vod.audioTracks.count < 2)
    }

    private func chip(_ title: String, _ symbol: String) -> some View {
        Label(title, systemImage: symbol).font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 12).frame(height: 34)
            .background(.white.opacity(0.15), in: Capsule())
            .foregroundStyle(.white)
    }

    private func round(_ symbol: String, _ size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: size, weight: .semibold)).frame(width: size * 2.2, height: size * 2.2)
                .background(.black.opacity(0.3), in: Circle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Behaviour

    private func toggleUI() { if showUI { showUI = false } else { poke() } }

    private func poke() {
        showUI = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, vod.state == .playing else { return }
            showUI = false
        }
    }

    private func skip(_ seconds: TimeInterval, leading: Bool) {
        vod.seek(by: seconds)
        flash = (seconds < 0 ? "−\(Int(-seconds)) s" : "+\(Int(seconds)) s", leading)
        Task {
            try? await Task.sleep(for: .milliseconds(700))
            flash = nil
        }
    }

    private func speed(_ bps: Int) -> String { bps >= 1_048_576 ? String(format: "%.1f MB/s", Double(bps) / 1_048_576) : "\(bps / 1024) KB/s" }
}

/// Hosts the movie player's video view.
struct VodSurface: UIViewRepresentable {
    let player: VodPlayer

    func makeUIView(context: Context) -> UIView {
        let host = UIView()
        host.backgroundColor = .black
        attach(to: host)
        return host
    }

    func updateUIView(_ host: UIView, context: Context) {
        if player.videoView.superview !== host { attach(to: host) }
    }

    private func attach(to host: UIView) {
        let v = player.videoView
        v.removeFromSuperview()
        v.frame = host.bounds
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.addSubview(v)
    }
}
