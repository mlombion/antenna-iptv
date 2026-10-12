import SwiftUI

/// Content of the floating picture-in-picture window.
struct PiPView: View {
    @Environment(AppModel.self) private var model
    @State private var hover = false
    @FocusState private var focused: Bool

    private var player: PlayerController { model.player }

    var body: some View {
        ZStack {
            Color.black
            VideoSurface(player: player, role: .pip, owner: player.surface)
            WindowDragArea(onDoubleClick: { model.exitPiP(returnToApp: true) },
                           onHover: { h in withAnimation(.easeOut(duration: 0.15)) { hover = h } })

            if player.state == .opening {
                ZStack {
                    Color.black.opacity(player.isSwitching ? 0.9 : 0.3)
                    ProgressView().controlSize(.small).tint(.white)
                }
                .allowsHitTesting(false)
            }

            if case .failed = player.state {
                VStack(spacing: 8) {
                    Text("Can't play this channel").font(.system(size: 13, weight: .semibold))
                    HStack(spacing: 6) {
                        Button("Try again") { player.retry() }.buttonStyle(GlassButtonStyle())
                        Button("Next") { model.zap(1) }.buttonStyle(GlassButtonStyle())
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.black.opacity(0.75))
            }

            controls.opacity(hover ? 1 : 0)
        }
        .foregroundStyle(.white)
        .ignoresSafeArea()
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onAppear { focused = true }
        .onChange(of: player.videoSize) { _, size in model.pipAspectChanged(size) }
        .onKeyPress(.escape) { model.exitPiP(returnToApp: true); return .handled }
        .onKeyPress(.upArrow) { model.zap(-1); return .handled }
        .onKeyPress(.downArrow) { model.zap(1); return .handled }
        .onKeyPress(.space) { player.togglePause(); return .handled }
        .onKeyPress(keys: [.leftArrow, .rightArrow]) { press in
            player.seek(by: (press.key == .leftArrow ? -1 : 1) * (press.modifiers.contains(.shift) ? 60 : 10))
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "m")) { _ in player.isMuted.toggle(); return .handled }
    }

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.65), .clear, .clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack {
                    pipButton("arrow.up.left.and.arrow.down.right", "Back to Antenna (Esc)", size: 12) { model.exitPiP(returnToApp: true) }
                    Spacer()
                    pipButton("xmark", "Close", size: 12) { model.exitPiP(returnToApp: false) }
                }
                .padding(8)
                Spacer()
                HStack(spacing: 18) {
                    pipButton("chevron.up", "Previous channel (↑)", size: 16) { model.zap(-1) }
                    pipButton(player.state == .paused ? "play.fill" : "pause.fill", "Pause (space)", size: 20) { player.togglePause() }
                    pipButton("chevron.down", "Next channel (↓)", size: 16) { model.zap(1) }
                }
                Spacer()
                HStack(spacing: 8) {
                    if let ch = model.playing {
                        ChannelLogo(channel: ch, width: 28, height: 21)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(ch.displayName).font(.system(size: 11.5, weight: .semibold)).lineLimit(1)
                            if let cur = model.schedule(for: ch).current {
                                Text(cur.title).font(.system(size: 10.5)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                            }
                        }
                    }
                    Spacer(minLength: 4)
                    if player.canRewind {
                        if player.isBehindLive, let t = player.timeline {
                            Button { player.goLive() } label: { BehindBadge(seconds: t.behind) }
                                .buttonStyle(.plain)
                                .help("Go back to live")
                        }
                        pipButton("gobackward.30", "Rewind 30 seconds (←)", size: 12) { player.seek(by: -30) }
                    }
                    pipButton(player.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill", "Mute (M)", size: 12) { player.isMuted.toggle() }
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 8)
            }
        }
    }

    private func pipButton(_ symbol: String, _ help: String, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size, weight: .semibold))
                .frame(width: size + 18, height: size + 18)
                .background(.black.opacity(0.35), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .help(help)
    }
}
