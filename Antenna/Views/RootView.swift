import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            switch model.phase {
            case .launching:
                ProgressView().controlSize(.small)
            case .signedOut:
                LoginView()
            case .connecting:
                if model.isLoggingIn { LoginView() } else { LoadingView() }
            case .ready:
                MainView()
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                ToastView(text: toast)
                    .padding(.bottom, 28)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.toast)
        .foregroundStyle(Theme.fg)
    }
}

struct LoadingView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        VStack(spacing: 24) {
            BrandMark(size: 26)
            StepList(steps: model.steps).frame(width: 260)
        }
    }
}

struct MainView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack(spacing: 0) {
            SidebarView()
            ZStack {
                switch model.section {
                case .home: HomeView()
                case .live: LiveTVView()
                case .guide: GuideView()
                case .favorites: FavoritesView()
                case .movies: MoviesView()
                case .settings: SettingsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .ignoresSafeArea()
        .onAppear { model.cast.startBrowsing() }
        .overlay {
            if model.playing != nil && !model.isPiP {
                PlayerView()
                    .ignoresSafeArea()
                    .transition(.opacity)
            }
        }
        .overlay {
            if let movie = model.movie, !model.isMoviePiP {
                MoviePlayerView(movie: movie)
                    .ignoresSafeArea()
                    .transition(.opacity)
            }
        }
        .overlay {
            if model.showSearch {
                SearchPalette().transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.18), value: model.playing?.id)
        .animation(.easeOut(duration: 0.18), value: model.isPiP)
        .animation(.easeOut(duration: 0.18), value: model.movie?.id)
        .animation(.easeOut(duration: 0.18), value: model.isMoviePiP)
        .animation(.easeOut(duration: 0.12), value: model.showSearch)
    }
}
