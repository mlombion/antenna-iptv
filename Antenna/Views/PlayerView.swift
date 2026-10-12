import SwiftUI

struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @FocusState private var focused: Bool
    @State private var showUI = true
    @State private var hideTask: Task<Void, Never>?
    @State private var showList = false
    @State private var showStats = false
    @State private var numberEntry = ""
    @State private var numberTask: Task<Void, Never>?

    private var player: PlayerController { model.player }

    var body: some View {
        ZStack {
            Color.black
            VideoSurface(player: player, role: .main, owner: player.surface)

            if player.state == .opening {
                ZStack {
                    Color.black.opacity(player.isSwitching ? 0.92 : 0.35)
                    if player.isSwitching, let ch = model.playing {
                        ChannelLogo(channel: ch, width: 120, height: 88).opacity(0.5).offset(y: -90)
                    }
                    VStack(spacing: 12) {
                        ProgressView().controlSize(.large).tint(.white)
                        if player.reconnectAttempt > 0 {
                            Text("Reconnecting…").foregroundStyle(.white.opacity(0.7))
                        }
                    }
                }
                .allowsHitTesting(false)
            }

            if case .failed(let message) = player.state {
                Color.black.opacity(0.85)
                failure(message)
            }

            if player.state == .paused {
                Image(systemName: "play.fill")
                    .font(.system(size: 34))
                    .frame(width: 84, height: 84)
                    .background(.black.opacity(0.45), in: Circle())
                    .allowsHitTesting(false)
            }

            osd.opacity(showUI ? 1 : 0)

            if !numberEntry.isEmpty {
                Text(numberEntry)
                    .font(.system(size: 44, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(.top, 70).padding(.trailing, 32)
            }

            if showStats {
                Text(player.statsText)
                    .font(.system(size: 11.5, design: .monospaced))
                    .lineSpacing(3)
                    .foregroundStyle(Color(hex: 0xC9F2D9))
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 8))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(.top, 80).padding(.leading, 24)
                    .allowsHitTesting(false)
            }

            HStack(spacing: 0) {
                if showList { zapList.transition(.move(edge: .leading)) }
                Spacer(minLength: 0)
            }
        }
        .foregroundStyle(.white)
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onAppear { focused = true; poke() }
        .onDisappear { hideTask?.cancel(); NSCursor.setHiddenUntilMouseMoves(false) }
        .onContinuousHover { _ in poke() }
        .onTapGesture(count: 2) { NSApp.keyWindow?.toggleFullScreen(nil) }
        .onKeyPress(.escape) {
            if showList { withAnimation { showList = false } } else { model.stopPlaying() }
            return .handled
        }
        .onKeyPress(.upArrow) { model.zap(-1); poke(); return .handled }
        .onKeyPress(.downArrow) { model.zap(1); poke(); return .handled }
        .onKeyPress(.pageUp) { model.zap(-1); poke(); return .handled }
        .onKeyPress(.pageDown) { model.zap(1); poke(); return .handled }
        .onKeyPress(.space) { player.togglePause(); poke(); return .handled }
        .onKeyPress(keys: [.leftArrow, .rightArrow]) { press in
            let step: TimeInterval = press.modifiers.contains(.shift) ? 60 : 10
            let sign: TimeInterval = press.key == .leftArrow ? -1 : 1
            if player.canRewind {
                player.seek(by: sign * step)
            } else {
                player.volume = min(1, max(0, player.volume + sign * 0.1))
            }
            poke()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "-=+")) { press in
            player.volume = min(1, max(0, player.volume + (press.characters == "-" ? -0.1 : 0.1)))
            poke()
            return .handled
        }
        .onKeyPress(characters: .decimalDigits) { press in
            typeDigit(press.characters)
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "mfilp")) { press in
            switch press.characters {
            case "p": model.enterPiP(); return .handled
            case "m": player.isMuted.toggle()
            case "f": NSApp.keyWindow?.toggleFullScreen(nil)
            case "i": showStats.toggle()
            case "l": withAnimation(.easeOut(duration: 0.2)) { showList.toggle() }
            default: break
            }
            poke()
            return .handled
        }
        .animation(.easeOut(duration: 0.25), value: showUI)
        .onChange(of: player.isBehindLive) { poke() }
    }

    // MARK: OSD

    private var osd: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button { model.stopPlaying() } label: { Label("Back", systemImage: "chevron.left") }
                    .buttonStyle(GlassButtonStyle())
                Button { withAnimation(.easeOut(duration: 0.2)) { showList.toggle() } } label: {
                    Label("Channels", systemImage: "list.bullet")
                }
                .buttonStyle(GlassButtonStyle())
                Spacer()
                Text(player.canRewind ? "↑ ↓ change channel · ← → rewind 10 s · esc back" : "↑ ↓ change channel · 0–9 go to number · esc back")
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.55))
            }
            .padding(.horizontal, 24)
            .padding(.top, 34)
            .padding(.bottom, 40)
            .background(LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom))

            Spacer()

            VStack(alignment: .leading, spacing: 16) {
                if let ch = model.playing { nowCard(ch) }
                if let t = player.timeline { RewindBar(timeline: t, live: !player.isBehindLive) { player.seek(to: $0); poke() } }
                controls
            }
            .padding(.horizontal, 28)
            .padding(.top, 60)
            .padding(.bottom, 22)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.55), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom))
        }
    }

    private func nowCard(_ ch: Channel) -> some View {
        let sched = model.schedule(for: ch)
        return HStack(spacing: 16) {
            ChannelLogo(channel: ch, width: 64, height: 48)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text("\(ch.num)").font(.system(size: 13, weight: .bold, design: .monospaced))
                    Text(ch.displayName).font(.system(size: 13, weight: .bold))
                    if player.isBehindLive, let t = player.timeline {
                        BehindBadge(seconds: t.behind)
                        Button { player.goLive() } label: { Label("Go live", systemImage: "forward.end.fill") }
                            .buttonStyle(GlassButtonStyle())
                    } else {
                        LiveBadge()
                    }
                    QualityBadge(quality: ch.quality)
                }
                Text(sched.current?.title ?? ch.displayName)
                    .font(.system(size: 26, weight: .bold))
                    .tracking(-0.4)
                    .lineLimit(1)
                if let cur = sched.current {
                    HStack(spacing: 10) {
                        Text(cur.start.clock)
                        ProgressLine(value: cur.progress(at: model.now))
                        Text(cur.end.clock)
                    }
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.7))
                }
                if let next = sched.upcoming.first {
                    Text("Next at \(next.start.clock) · \(next.title)")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.55))
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: 820, alignment: .leading)
    }

    private var controls: some View {
        @Bindable var player = player
        return HStack(spacing: 6) {
            iconButton("chevron.up", "Previous channel (↑)") { model.zap(-1) }
            iconButton("chevron.down", "Next channel (↓)") { model.zap(1) }
            if player.canRewind {
                iconButton("gobackward.30", "Rewind 30 seconds") { player.seek(by: -30) }
            } else if let reason = player.rewindUnavailableReason {
                iconButton("gobackward.30", reason) {}
                    .disabled(true)
                    .opacity(0.35)
            }
            iconButton(player.state == .paused ? "play.fill" : "pause.fill", "Pause (space)") { player.togglePause() }
            if player.canRewind {
                iconButton("goforward.30", "Skip ahead 30 seconds") { player.seek(by: 30) }
                    .disabled(!player.isBehindLive)
                    .opacity(player.isBehindLive ? 1 : 0.35)
            }
            iconButton(player.isMuted || player.volume == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill", "Mute (M)") {
                player.isMuted.toggle()
            }
            Slider(value: $player.volume, in: 0...1).frame(width: 100).tint(.white)
            Spacer()
            if player.audioTracks.count > 1 {
                Menu {
                    ForEach(player.audioTracks) { t in
                        Button { player.selectAudioTrack(t.id) } label: {
                            if t.id == player.currentAudioTrack { Label(t.name, systemImage: "checkmark") } else { Text(t.name) }
                        }
                    }
                } label: { Label("Audio", systemImage: "waveform") }
                .menuStyle(.borderlessButton).fixedSize().buttonStyle(GlassButtonStyle())
            }
            if !player.subtitleTracks.isEmpty {
                Menu {
                    Button("Off") { player.selectSubtitle(-1) }
                    ForEach(player.subtitleTracks) { t in
                        Button { player.selectSubtitle(t.id) } label: {
                            if t.id == player.currentSubtitleTrack { Label(t.name, systemImage: "checkmark") } else { Text(t.name) }
                        }
                    }
                } label: { Label("Subtitles", systemImage: "captions.bubble") }
                .menuStyle(.borderlessButton).fixedSize()
            }
            Button { showStats.toggle() } label: { Text("Stream info") }.buttonStyle(GlassButtonStyle())
            iconButton("pip.enter", "Picture in Picture (P): keep watching in a floating window") { model.enterPiP() }
            Menu {
                if model.cast.devices.isEmpty {
                    Text("Looking for TVs on your Wi-Fi…")
                }
                ForEach(model.cast.devices) { tv in
                    Button { model.castCurrent(to: tv) } label: { Label(tv.displayName, systemImage: "tv") }
                }
            } label: {
                Label("Play on TV", systemImage: "tv.and.mediabox")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .help("Play this channel on your TV; this Mac becomes the remote")
            if let ch = model.playing {
                iconButton(model.isFavorite(ch) ? "star.fill" : "star", "Favorite") { model.toggleFavorite(ch) }
            }
            iconButton("arrow.up.left.and.arrow.down.right", "Full screen (F)") { NSApp.keyWindow?.toggleFullScreen(nil) }
        }
    }

    private func iconButton(_ symbol: String, _ help: String, action: @escaping () -> Void) -> some View {
        Button { action(); poke() } label: {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 38, height: 38)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(help)
    }

    private func failure(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash").font(.system(size: 34)).foregroundStyle(.white.opacity(0.7))
            Text("Can't play this channel").font(.system(size: 18, weight: .semibold))
            Text("\(message) Try again, or switch to another channel with ↑ ↓.")
                .foregroundStyle(.white.opacity(0.65))
                .multilineTextAlignment(.center)
                .frame(maxWidth: 380)
            HStack {
                Button("Try again") { player.retry() }.buttonStyle(PillButtonStyle(prominent: true))
                Button("Next channel") { model.zap(1) }.buttonStyle(PillButtonStyle())
            }
        }
        .padding(28)
        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: Channel list

    private var zapList: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Channels").font(.system(size: 15, weight: .semibold))
                Spacer()
                Button { withAnimation { showList = false } } label: { Image(systemName: "xmark") }.buttonStyle(.plain)
            }
            .padding(.horizontal, 18).padding(.top, 36).padding(.bottom, 10)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(model.zapList) { ch in
                            Button { model.play(ch, in: model.zapList) } label: {
                                HStack(spacing: 10) {
                                    Text("\(ch.num)").font(.system(size: 11, design: .monospaced)).foregroundStyle(.white.opacity(0.45))
                                        .frame(width: 32, alignment: .trailing)
                                    ChannelLogo(channel: ch, width: 36, height: 27)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(ch.displayName).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                                        Text(model.schedule(for: ch).current?.title ?? " ")
                                            .font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal, 8).padding(.vertical, 7)
                                .background(RoundedRectangle(cornerRadius: 8)
                                    .fill(ch.id == model.playing?.id ? Color.white.opacity(0.12) : .clear))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .id(ch.id)
                            .onAppear { model.requestEPG(ch) }
                        }
                    }
                    .padding(.horizontal, 8).padding(.bottom, 20)
                }
                .onAppear { if let id = model.playing?.id { proxy.scrollTo(id, anchor: .center) } }
            }
        }
        .frame(width: 340)
        .frame(maxHeight: .infinity)
        .background(.ultraThinMaterial)
        .environment(\.colorScheme, .dark)
        .overlay(alignment: .trailing) { Rectangle().fill(.white.opacity(0.08)).frame(width: 1) }
    }

    // MARK: Helpers

    private func poke() {
        showUI = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(3.2))
            guard !Task.isCancelled, player.state != .paused, !showList else { return }
            showUI = false
            NSCursor.setHiddenUntilMouseMoves(true)
        }
    }

    private func typeDigit(_ d: String) {
        numberEntry = String((numberEntry + d).suffix(4))
        numberTask?.cancel()
        numberTask = Task {
            try? await Task.sleep(for: .seconds(1.1))
            guard !Task.isCancelled else { return }
            if let n = Int(numberEntry) { model.tune(number: n) }
            numberEntry = ""
            poke()
        }
    }
}

struct GlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12.5, weight: .medium))
            .padding(.horizontal, 12)
            .frame(height: 32)
            .background(.white.opacity(configuration.isPressed ? 0.22 : 0.12), in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.12)))
    }
}
