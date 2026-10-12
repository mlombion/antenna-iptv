import AVKit
import SwiftUI

/// Full-screen player. Tap shows the controls; swipe up or down changes channel;
/// double-tap the left or right side to rewind or skip 10 seconds.
struct PhonePlayerView: View {
    @Environment(AppModel.self) private var model
    @State private var showUI = true
    @State private var hideTask: Task<Void, Never>?
    @State private var showList = false
    @State private var skipFlash: (text: String, leading: Bool)?
    @State private var flashTask: Task<Void, Never>?

    private var player: PlayerController { model.player }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                VideoSurface(player: player).ignoresSafeArea()

                gestureLayer(width: geo.size.width)

                if player.state == .opening {
                    ZStack {
                        Color.black.opacity(player.isSwitching ? 0.9 : 0.3).ignoresSafeArea()
                        VStack(spacing: 16) {
                            if player.isSwitching, let ch = model.playing {
                                ChannelLogo(channel: ch, width: 96, height: 72).opacity(0.6)
                            }
                            ProgressView().controlSize(.large).tint(.white)
                            if player.reconnectAttempt > 0 { Text("Reconnecting…").foregroundStyle(.white.opacity(0.7)) }
                        }
                    }
                    .allowsHitTesting(false)
                }

                if case .failed(let message) = player.state { failure(message) }

                if let flash = skipFlash {
                    Text(flash.text)
                        .font(.system(size: 15, weight: .bold))
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.black.opacity(0.55), in: Capsule())
                        .frame(maxWidth: .infinity, alignment: flash.leading ? .leading : .trailing)
                        .padding(.horizontal, 40)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                }

                controls
                    .opacity(showUI ? 1 : 0)
                    .allowsHitTesting(showUI)

                if let notice = player.pipNotice {
                    Text(notice)
                        .font(.system(size: 13, weight: .medium))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 32)
                        .frame(maxHeight: .infinity, alignment: .top)
                        .padding(.top, 70)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                }
            }
        }
        .foregroundStyle(.white)
        .statusBarHidden(!showUI)
        .persistentSystemOverlays(.hidden)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            poke()
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: player.isBehindLive) { poke() }
        .sheet(isPresented: $showList) { channelSheet }
        .animation(.easeOut(duration: 0.2), value: showUI)
    }

    // MARK: Gestures

    private func gestureLayer(width: CGFloat) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .ignoresSafeArea()
            .onTapGesture(count: 2, coordinateSpace: .local) { point in
                guard player.canRewind else { return }
                let back = point.x < width / 2
                player.seek(by: back ? -10 : 10)
                flash(back ? "−10 s" : "+10 s", leading: back)
            }
            .onTapGesture { showUI ? hide() : poke() }
            .gesture(DragGesture(minimumDistance: 40).onEnded { v in
                guard abs(v.translation.height) > abs(v.translation.width) * 1.5 else { return }
                model.zap(v.translation.height < 0 ? 1 : -1)
                poke()
            })
    }

    // MARK: Controls

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    roundButton("chevron.down", size: 17) { model.stopPlaying() }
                    if let ch = model.playing {
                        Text(ch.displayName).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    }
                    Spacer()
                    roundButton("pip.enter", size: 16) { model.enterPiP() }
                    roundButton("list.bullet", size: 16) { showList = true }
                    if let ch = model.playing {
                        roundButton(model.isFavorite(ch) ? "star.fill" : "star", size: 16) { model.toggleFavorite(ch) }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)

                Spacer()

                HStack(spacing: 36) {
                    if player.canRewind {
                        roundButton("gobackward.30", size: 24) { player.seek(by: -30) }
                    }
                    roundButton(player.state == .paused ? "play.fill" : "pause.fill", size: 34) { player.togglePause() }
                    if player.canRewind {
                        roundButton("goforward.30", size: 24) { player.seek(by: 30) }
                            .disabled(!player.isBehindLive)
                            .opacity(player.isBehindLive ? 1 : 0.35)
                    }
                }

                Spacer()

                VStack(alignment: .leading, spacing: 12) {
                    if let ch = model.playing { nowCard(ch) }
                    if let t = player.timeline {
                        RewindBar(timeline: t, live: !player.isBehindLive) { player.seek(to: $0); poke() }
                    }
                    PlayOnTVButton()
                        .frame(maxWidth: .infinity)
                        .padding(.top, 4)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
            }
        }
    }

    private func nowCard(_ ch: Channel) -> some View {
        let sched = model.schedule(for: ch)
        return HStack(alignment: .center, spacing: 12) {
            ChannelLogo(channel: ch, width: 52, height: 39)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text("\(ch.num)").font(.system(size: 12, weight: .bold, design: .monospaced))
                    if player.isBehindLive, let t = player.timeline {
                        BehindBadge(seconds: t.behind)
                        Button { player.goLive() } label: { Label("Go live", systemImage: "forward.end.fill") }
                            .buttonStyle(GlassPillStyle())
                    } else {
                        LiveBadge()
                    }
                    QualityBadge(quality: ch.quality)
                }
                Text(sched.current?.title ?? ch.displayName)
                    .font(.system(size: 19, weight: .bold))
                    .lineLimit(1)
                if let next = sched.upcoming.first {
                    Text("Next at \(next.start.clock) · \(next.title)")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            VStack(spacing: 6) {
                roundButton("chevron.up", size: 15) { model.zap(-1) }
                roundButton("chevron.down", size: 15) { model.zap(1) }
            }
        }
    }

    private func failure(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash").font(.system(size: 32)).foregroundStyle(.white.opacity(0.7))
            Text("Can't play this channel").font(.system(size: 18, weight: .semibold))
            Text("\(message) Try again, or swipe to another channel.")
                .foregroundStyle(.white.opacity(0.65))
                .multilineTextAlignment(.center)
            HStack {
                Button("Try again") { player.retry() }.buttonStyle(PillButtonStyle(prominent: true))
                Button("Next channel") { model.zap(1) }.buttonStyle(PillButtonStyle())
            }
        }
        .padding(24)
        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
        .padding(24)
    }

    private var channelSheet: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List(model.zapList) { ch in
                    Button {
                        model.play(ch, in: model.zapList)
                        showList = false
                    } label: { PhoneChannelRow(channel: ch) }
                        .buttonStyle(.plain)
                        .listRowBackground(ch.id == model.playing?.id ? Theme.surface3 : Theme.bg)
                        .id(ch.id)
                }
                .antennaList()
                .onAppear { if let id = model.playing?.id { proxy.scrollTo(id, anchor: .center) } }
            }
            .navigationTitle("Channels")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showList = false } } }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }

    private func roundButton(_ symbol: String, size: CGFloat, action: @escaping () -> Void) -> some View {
        Button { action(); poke() } label: {
            Image(systemName: symbol)
                .font(.system(size: size, weight: .semibold))
                .frame(width: size + 26, height: size + 26)
                .background(.black.opacity(0.35), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Helpers

    private func poke() {
        showUI = true
        hideTask?.cancel()
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "AntennaDevKeepControls") { return }
        #endif
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, player.state != .paused, !showList else { return }
            showUI = false
        }
    }

    private func hide() {
        hideTask?.cancel()
        showUI = false
    }

    private func flash(_ text: String, leading: Bool) {
        withAnimation { skipFlash = (text, leading) }
        flashTask?.cancel()
        flashTask = Task {
            try? await Task.sleep(for: .seconds(0.8))
            guard !Task.isCancelled else { return }
            withAnimation { skipFlash = nil }
        }
    }
}

struct GlassPillStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 11.5, weight: .semibold))
            .padding(.horizontal, 10)
            .frame(height: 26)
            .background(.white.opacity(configuration.isPressed ? 0.25 : 0.15), in: Capsule())
    }
}
