import SwiftUI

struct PhoneRootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            switch model.phase {
            case .launching:
                ProgressView()
            case .signedOut:
                PhoneLoginView()
            case .connecting:
                if model.isLoggingIn { PhoneLoginView() } else {
                    VStack(spacing: 24) {
                        BrandMark(size: 24)
                        StepList(steps: model.steps).frame(width: 260)
                    }
                }
            case .ready:
                PhoneTabs()
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                ToastView(text: toast)
                    .padding(.bottom, 96)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.toast)
        .foregroundStyle(Theme.fg)
    }
}

private struct PhoneTabs: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.section) {
            PhoneHomeView()
                .tabItem { Label("Home", systemImage: "house") }
                .tag(AppSection.home)
            PhoneLiveView()
                .tabItem { Label("Live TV", systemImage: "tv") }
                .tag(AppSection.live)
            PhoneGuideView()
                .tabItem { Label("Guide", systemImage: "calendar") }
                .tag(AppSection.guide)
            // Favorites stay on Home ("Your favorites"); five tabs is the most iOS shows without a More tab.
            PhoneMoviesView()
                .tabItem { Label("Movies", systemImage: "film.stack") }
                .tag(AppSection.movies)
            PhoneSettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(AppSection.settings)
        }
        // While the video floats in picture in picture, the full-screen player steps aside.
        .fullScreenCover(isPresented: Binding(get: { model.playing != nil && !model.isPiP },
                                              set: { if !$0 && !model.isPiP { model.stopPlaying() } })) {
            PhonePlayerView().environment(model)
        }
        .background {
            // Like live TV, the full-screen player steps aside while the movie floats.
            Color.clear.fullScreenCover(item: Binding(get: { model.isMoviePiP ? nil : model.movie },
                                                      set: { if $0 == nil && !model.isMoviePiP { model.stopMovie() } })) { movie in
                PhoneMoviePlayerView(movie: movie).environment(model)
            }
        }
        // Sits just above the floating tab bar, like a music mini-player.
        .overlay(alignment: .bottom) { CastingBar().padding(.bottom, 66) }
        .onAppear { model.cast.startBrowsing() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.player.returnFromAutomaticPiP() }
        }
    }
}

/// A channel row shared by Live TV, Favorites and search results.
struct PhoneChannelRow: View {
    @Environment(AppModel.self) private var model
    let channel: Channel

    var body: some View {
        let sched = model.schedule(for: channel)
        HStack(spacing: 12) {
            Text("\(channel.num)")
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(Theme.faint)
                .frame(minWidth: 28, alignment: .trailing)
            ChannelLogo(channel: channel, width: 48, height: 36)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(channel.displayName).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    QualityBadge(quality: channel.quality)
                    if model.isFavorite(channel) {
                        Image(systemName: "star.fill").font(.system(size: 10)).foregroundStyle(Theme.accent)
                    }
                }
                Text(sched.current?.title ?? (sched.isLoading ? " " : "No guide information"))
                    .font(.system(size: 13))
                    .foregroundStyle(sched.current == nil ? Theme.faint : Theme.muted)
                    .lineLimit(1)
                if let cur = sched.current {
                    ProgressLine(value: cur.progress(at: model.now), height: 2.5)
                }
            }
            Spacer(minLength: 0)
            if let cur = sched.current {
                Text("\(max(1, Int(cur.end.timeIntervalSince(model.now) / 60)))m")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Theme.faint)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onAppear { model.requestEPG(channel) }
    }
}

extension View {
    /// Dark list styling matching the rest of the app.
    func antennaList() -> some View {
        listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
    }
}
