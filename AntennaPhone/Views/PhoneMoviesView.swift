import SwiftUI

/// Movies & Series: the Stremio library and add-ons. Pick a source and it plays on an Antenna TV;
/// this phone is the remote.
struct PhoneMoviesView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""

    var body: some View {
        let s = model.stremio
        NavigationStack {
            Group {
                if !s.signedIn {
                    StremioSignInView()
                } else if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    searchResults
                } else {
                    board
                }
            }
            .background(Theme.bg)
            .navigationTitle("Movies & Series")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: StremioMeta.self) { TitleView(preview: $0) }
            .modifier(SearchIfSignedIn(signedIn: s.signedIn, query: $query))
            .onChange(of: query) { _, q in s.search(q) }
        }
        .task(id: s.signedIn) {
            s.hideAdult = model.settings.hideAdult
            if s.signedIn { await s.refresh() }
        }
    }

    private var board: some View {
        let s = model.stremio
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                if let error = s.loadError {
                    Text(error).font(.system(size: 13)).foregroundStyle(Theme.live).padding(.horizontal, 16)
                }
                if !s.continueWatching.isEmpty {
                    shelf("Continue watching", source: nil) {
                        ForEach(s.continueWatching) { item in
                            NavigationLink(value: item.meta) {
                                Poster(meta: item.meta, subtitle: item.episodeLabel ?? item.minutesLeft.map { "\($0) min left" }, progress: item.progress)
                            }
                        }
                    }
                }
                if !s.libraryItems.isEmpty {
                    shelf("Your library", source: nil) {
                        ForEach(s.libraryItems.prefix(60)) { item in
                            NavigationLink(value: item.meta) { Poster(meta: item.meta, subtitle: nil, progress: nil) }
                        }
                    }
                }
                ForEach(s.boardCatalogs, id: \.catalog.id) { entry in
                    CatalogShelf(addon: entry.addon, catalog: entry.catalog)
                }
                if s.isRefreshing && s.addons.isEmpty {
                    HStack(spacing: 10) { ProgressView(); Text("Loading your Stremio library and add-ons…").foregroundStyle(Theme.muted) }
                        .padding(16)
                }
            }
            .padding(.vertical, 12)
            .padding(.bottom, 70)
        }
        .refreshable { await s.refresh() }
    }

    private var searchResults: some View {
        let s = model.stremio
        let entries = s.searchCatalogs.compactMap { e in s.searchResults[e.catalog.id].map { (e.addon, e.catalog, $0) } }
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                ForEach(entries, id: \.1.id) { addon, catalog, state in
                    switch state {
                    case .loaded(let metas):
                        shelf("\(catalog.name) · \(typeLabel(catalog.type))", source: addon.name) {
                            ForEach(metas) { m in NavigationLink(value: m) { Poster(meta: m, subtitle: m.releaseInfo, progress: nil) } }
                        }
                    case .loading:
                        HStack(spacing: 10) { ProgressView(); Text("\(addon.name) is looking…").foregroundStyle(Theme.muted) }.padding(.horizontal, 16)
                    case .failed:
                        EmptyView()
                    }
                }
                if !entries.isEmpty, entries.allSatisfy({ if case .failed = $0.2 { true } else { false } }) {
                    Text("Nothing found for “\(query)”.").foregroundStyle(Theme.muted).padding(16)
                }
            }
            .padding(.vertical, 12)
            .padding(.bottom, 70)
        }
    }
}

private struct SearchIfSignedIn: ViewModifier {
    let signedIn: Bool
    @Binding var query: String
    func body(content: Content) -> some View {
        if signedIn {
            content.searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Movies and series")
        } else { content }
    }
}

func typeLabel(_ type: String) -> String {
    switch type { case "movie": "Movies"; case "series": "Series"; case "anime": "Anime"; default: type.capitalized }
}

@ViewBuilder
private func shelf<Content: View>(_ title: String, source: String?, @ViewBuilder content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: 10) {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title).font(.system(size: 18, weight: .bold))
            if let source { Text(source).font(.system(size: 12)).foregroundStyle(Theme.faint) }
        }
        .padding(.horizontal, 16)
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) { content() }.padding(.horizontal, 16)
        }
        .scrollIndicators(.never)
    }
}

private struct CatalogShelf: View {
    @Environment(AppModel.self) private var model
    let addon: StremioAddon
    let catalog: StremioCatalog

    var body: some View {
        Group {
            switch model.stremio.rows[catalog.id] {
            case .loaded(let metas):
                shelf("\(catalog.name) · \(typeLabel(catalog.type))", source: addon.name) {
                    ForEach(metas) { m in NavigationLink(value: m) { Poster(meta: m, subtitle: m.releaseInfo, progress: nil) } }
                }
            case .failed:
                EmptyView()
            default:
                VStack(alignment: .leading, spacing: 10) {
                    Text("\(catalog.name) · \(typeLabel(catalog.type))").font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.muted)
                        .padding(.horizontal, 16)
                    HStack(spacing: 12) {
                        ForEach(0..<4, id: \.self) { _ in RoundedRectangle(cornerRadius: 10).fill(Theme.surface2).frame(width: 110, height: 165) }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
        .onAppear { model.stremio.loadRow(addon, catalog) }
    }
}

struct Poster: View {
    let meta: StremioMeta
    let subtitle: String?
    let progress: Double?
    var width: CGFloat = 110

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 10).fill(Theme.surface2)
                Text(meta.name).font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center).padding(8).frame(maxHeight: .infinity)
                AsyncImage(url: meta.poster) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
                if let progress {
                    LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).frame(height: 26)
                    ProgressLine(value: progress, height: 3).padding(7)
                }
            }
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(meta.name).font(.system(size: 12.5, weight: .medium)).lineLimit(1)
            if let subtitle { Text(subtitle).font(.system(size: 11.5)).foregroundStyle(Theme.faint).lineLimit(1) }
        }
        .frame(width: width)
        .foregroundStyle(Theme.fg)
    }
}

// MARK: - Sign-in

private struct StremioSignInView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        let s = model.stremio
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: "film.stack").font(.system(size: 40)).foregroundStyle(Theme.accent).padding(.top, 30)
                Text("Your Stremio, on your TV").font(.system(size: 26, weight: .heavy))
                Text("Sign in with your Stremio account to browse your library, Continue Watching and add-ons here, then play them on your TV with this phone as the remote. Progress is saved to your account, so the Stremio apps stay in sync.")
                    .foregroundStyle(Theme.muted)
                if let error = s.signInError {
                    Text(error).foregroundStyle(Theme.live)
                    Button("Try again") { s.startSignIn() }.buttonStyle(PillButtonStyle(prominent: true))
                } else if let code = s.linkCode {
                    Button { openURL(code.link) } label: {
                        Label("Sign in to Stremio", systemImage: "person.crop.circle")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
                            .foregroundStyle(Theme.accentInk)
                    }
                    .buttonStyle(.plain)
                    Text("Opens Stremio's sign-in page, where Facebook, Apple or email all work. Come back here afterwards; this screen updates by itself. Code: \(code.code)")
                        .font(.system(size: 13)).foregroundStyle(Theme.faint)
                } else {
                    ProgressView()
                }
            }
            .padding(24)
        }
        .onAppear { s.startSignIn() }
    }
}

// MARK: - A title

private struct TitleView: View {
    @Environment(AppModel.self) private var model
    let preview: StremioMeta
    @State private var detail: StremioMetaDetail?
    @State private var loaded = false
    @State private var season: Int?

    private var meta: StremioMeta { detail?.meta ?? preview }
    private var isSeries: Bool { (detail?.videos.count ?? 0) > 1 || preview.type == "series" || preview.type == "anime" }

    var body: some View {
        let item = model.stremio.libraryItem(preview.id)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                VStack(alignment: .leading, spacing: 12) {
                    let facts = [meta.releaseInfo, meta.runtime, meta.imdbRating.map { "★ \($0)" }, meta.genres.prefix(3).joined(separator: " · ")]
                        .compactMap { $0 }.filter { !$0.isEmpty }
                    if !facts.isEmpty { Text(facts.joined(separator: "   ")).font(.system(size: 13)).foregroundStyle(Theme.muted) }
                    if let d = meta.description { Text(d).font(.system(size: 14)).foregroundStyle(Color(hex: 0xC3CAD6)) }
                    if let item, item.inContinueWatching {
                        resumeLink(item)
                    }
                }
                .padding(.horizontal, 16)

                if !loaded {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if isSeries, let detail {
                    episodes(detail, current: item?.videoID)
                } else {
                    SourcesList(meta: meta, videoID: detail?.videos.count == 1 ? detail!.videos[0].id : preview.id, detail: detail)
                        .padding(.horizontal, 16)
                }
            }
            .padding(.bottom, 90)
        }
        .background(Theme.bg)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            detail = await model.stremio.meta(for: preview)
            loaded = true
            if let detail, isSeries {
                let current = item?.videoID.flatMap { id in detail.videos.first { $0.id == id } }
                season = current?.season ?? detail.seasons.first { $0 > 0 } ?? detail.seasons.first
            }
        }
    }

    private var header: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: meta.background ?? meta.poster) { $0.resizable().scaledToFill() } placeholder: { Theme.surface2 }
                .frame(height: 220).clipped()
            LinearGradient(colors: [.clear, Theme.bg], startPoint: .center, endPoint: .bottom)
            Group {
                if let logo = meta.logo {
                    AsyncImage(url: logo) { $0.resizable().scaledToFit() } placeholder: { Text(meta.name).font(.system(size: 28, weight: .heavy)) }
                        .frame(maxWidth: 240, maxHeight: 80, alignment: .leading)
                } else {
                    Text(meta.name).font(.system(size: 28, weight: .heavy)).lineLimit(2)
                }
            }
            .padding(16)
        }
        .frame(height: 220)
    }

    @ViewBuilder
    private func resumeLink(_ item: StremioLibraryItem) -> some View {
        let video = item.videoID.flatMap { id in detail?.videos.first { $0.id == id } }
        let label = ["Resume", item.episodeLabel, item.minutesLeft.map { "\($0) min left" }].compactMap { $0 }.joined(separator: " · ")
        NavigationLink {
            ScrollView {
                SourcesList(meta: meta, videoID: item.videoID ?? preview.id, detail: detail, video: video).padding(16)
            }
            .background(Theme.bg)
            .navigationTitle(item.episodeLabel ?? "Sources")
        } label: {
            Label(label, systemImage: "play.fill")
                .font(.system(size: 15, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
                .foregroundStyle(Theme.accentInk)
        }
        .buttonStyle(.plain)
    }

    private func episodes(_ detail: StremioMetaDetail, current: String?) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if detail.seasons.count > 1 {
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(detail.seasons, id: \.self) { n in
                            Button { season = n } label: {
                                Text(n == 0 ? "Specials" : "Season \(n)")
                                    .font(.system(size: 13, weight: .semibold))
                                    .padding(.horizontal, 14).frame(height: 32)
                                    .background(season == n ? Theme.accent : Theme.surface2, in: Capsule())
                                    .foregroundStyle(season == n ? Theme.accentInk : Theme.muted)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .scrollIndicators(.never)
            }
            LazyVStack(spacing: 8) {
                ForEach(season.map(detail.episodes) ?? detail.videos) { v in
                    NavigationLink {
                        ScrollView {
                            SourcesList(meta: meta, videoID: v.id, detail: detail, video: v).padding(16)
                        }
                        .background(Theme.bg)
                        .navigationTitle(v.label)
                    } label: {
                        EpisodeRow(video: v, current: v.id == current)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

private struct EpisodeRow: View {
    let video: StremioVideo
    let current: Bool

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: video.thumbnail) { $0.resizable().scaledToFill() } placeholder: { Theme.surface2 }
                .frame(width: 120, height: 68).clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                Text("\(video.episode.map { "\($0). " } ?? "")\(video.title)").font(.system(size: 14, weight: .semibold)).lineLimit(2)
                if current { Text("Watching").font(.system(size: 11.5)).foregroundStyle(Theme.accent) }
                if let released = video.released, released > .now {
                    Text("Coming \(released.formatted(date: .abbreviated, time: .omitted))").font(.system(size: 11.5)).foregroundStyle(Theme.accent)
                }
                if let o = video.overview { Text(o).font(.system(size: 12)).foregroundStyle(Theme.muted).lineLimit(2) }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.faint)
        }
        .padding(8)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
    }
}

// MARK: - Sources

private struct SourcesList: View {
    @Environment(AppModel.self) private var model
    let meta: StremioMeta
    let videoID: String
    var detail: StremioMetaDetail? = nil
    var video: StremioVideo? = nil
    var episodeTitle: String? { video?.title }
    @State private var groups: [StremioStore.SourceGroup] = []
    @State private var chooseFor: StremioStream?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            let pending = groups.filter { if case .loading = $0.state { true } else { false } }.count
            HStack(alignment: .firstTextBaseline) {
                Text("Sources").font(.system(size: 18, weight: .bold))
                Spacer()
                Text(pending > 0 ? "\(pending) add-on\(pending == 1 ? "" : "s") looking…" : "").font(.system(size: 12)).foregroundStyle(Theme.faint)
            }
            Text(model.cast.devices.isEmpty ? "Tap a source to play it." : "Tap a source to play it here or on your TV.")
                .font(.system(size: 12.5)).foregroundStyle(Theme.muted)
            ForEach(groups) { g in
                if case .loaded(let streams) = g.state {
                    Text(g.addon.uppercased()).font(.system(size: 11, weight: .semibold)).tracking(0.8).foregroundStyle(Theme.faint).padding(.top, 6)
                    ForEach(streams) { st in
                        Button { send(st) } label: { SourceRow(stream: st) }
                            .buttonStyle(.plain)
                            .disabled(!st.playable)
                    }
                }
            }
            if pending > 0 { ProgressView().frame(maxWidth: .infinity).padding(8) }
            if pending == 0, !groups.contains(where: { if case .loaded = $0.state { true } else { false } }) {
                Text(groups.isEmpty ? "None of your add-ons provide sources for this." : "Your add-ons didn't find any sources for this one.")
                    .foregroundStyle(Theme.muted)
            }
        }
        .task(id: videoID) {
            for await g in model.stremio.sources(type: meta.type, videoID: videoID) { groups = g }
        }
        .confirmationDialog("Play on", isPresented: Binding(get: { chooseFor != nil }, set: { if !$0 { chooseFor = nil } }),
                            titleVisibility: .visible, presenting: chooseFor) { st in
            Button("This iPhone") { playHere(st) }
            ForEach(model.cast.devices) { tv in
                Button(tv.displayName) { model.castStremio(meta: meta, videoID: videoID, episodeTitle: episodeTitle, stream: st, to: tv) }
            }
        }
    }

    /// With a TV around, ask where; otherwise play here.
    private func send(_ st: StremioStream) {
        if model.cast.devices.isEmpty { playHere(st) } else { chooseFor = st }
    }

    private func playHere(_ st: StremioStream) {
        model.playMovie(meta: detail?.meta ?? meta, detail: detail, video: video, videoID: videoID, stream: st)
    }
}

private struct SourceRow: View {
    let stream: StremioStream
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text((stream.name ?? stream.addonName).trimmingCharacters(in: .whitespacesAndNewlines))
                    .font(.system(size: 13, weight: .bold)).lineLimit(3)
                Text(stream.kind).font(.system(size: 11)).foregroundStyle(stream.playable ? Theme.faint : Theme.live)
            }
            .frame(width: 96, alignment: .leading)
            Text(stream.description ?? "").font(.system(size: 12)).foregroundStyle(Color(hex: 0xC3CAD6)).lineLimit(5)
            Spacer(minLength: 0)
            Image(systemName: "tv").font(.system(size: 13)).foregroundStyle(stream.playable ? Theme.accent : Theme.faint)
        }
        .padding(12)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
        .opacity(stream.playable ? 1 : 0.5)
        .contentShape(Rectangle())
    }
}
