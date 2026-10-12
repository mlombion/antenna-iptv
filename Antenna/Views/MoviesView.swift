import SwiftUI

/// Movies & Series on the Mac: the Stremio library and add-ons. Sources play here, or on an Antenna TV.
struct MoviesView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var selected: StremioMeta?

    var body: some View {
        let s = model.stremio
        Group {
            if !s.signedIn {
                MacStremioSignIn()
            } else if let selected {
                MacTitleView(preview: selected) { self.selected = nil }
                    .id(selected.id)
            } else {
                board
            }
        }
        .task(id: s.signedIn) {
            s.hideAdult = model.settings.hideAdult
            if s.signedIn { await s.refresh() }
            #if DEBUG
            // Test runs: -AntennaDevOpen tt1727587 opens that title (and -AntennaDevPlayFirst YES plays its first source).
            if s.signedIn, let id = UserDefaults.standard.string(forKey: "AntennaDevOpen"), let item = s.libraryItem(id) { selected = item.meta }
            #endif
        }
    }

    private var board: some View {
        let s = model.stremio
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 26) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Movies & Series").font(.system(size: 28, weight: .heavy)).tracking(-0.6)
                        Text(s.loadError ?? "\(s.addons.count) add-ons · \(s.libraryItems.count) in your library")
                            .font(.system(size: 12)).foregroundStyle(s.loadError == nil ? Theme.faint : Theme.live)
                    }
                    Spacer()
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass").foregroundStyle(Theme.muted)
                        TextField("Search movies and series", text: $query).textFieldStyle(.plain).frame(width: 240)
                        if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.faint) }.buttonStyle(.plain) }
                    }
                    .padding(.horizontal, 12).frame(height: 34)
                    .background(Theme.surface2, in: RoundedRectangle(cornerRadius: 9))
                    .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(Theme.line))
                }
                .padding(.horizontal, 28).padding(.top, 40)

                if searching { searchResults } else { shelves }
            }
            .padding(.bottom, 40)
        }
        .flatScrollEdges()
        .onChange(of: query) { _, q in s.search(q) }
    }

    @ViewBuilder private var shelves: some View {
        let s = model.stremio
        if !s.continueWatching.isEmpty {
            MovieShelf(title: "Continue watching", source: nil) {
                ForEach(s.continueWatching) { item in
                    MoviePoster(meta: item.meta, subtitle: item.episodeLabel ?? item.minutesLeft.map { "\($0) min left" }, progress: item.progress) { selected = item.meta }
                }
            }
        }
        if !s.libraryItems.isEmpty {
            MovieShelf(title: "Your library", source: nil) {
                ForEach(s.libraryItems.prefix(80)) { item in MoviePoster(meta: item.meta, subtitle: nil, progress: nil) { selected = item.meta } }
            }
        }
        ForEach(s.boardCatalogs, id: \.catalog.id) { e in
            MacCatalogShelf(addon: e.addon, catalog: e.catalog) { selected = $0 }
        }
        if s.isRefreshing && s.addons.isEmpty {
            HStack(spacing: 10) { ProgressView().controlSize(.small); Text("Loading your Stremio library and add-ons…").foregroundStyle(Theme.muted) }
                .padding(.horizontal, 28)
        }
    }

    @ViewBuilder private var searchResults: some View {
        let s = model.stremio
        let entries = s.searchCatalogs.compactMap { e in s.searchResults[e.catalog.id].map { (e.addon, e.catalog, $0) } }
        ForEach(entries, id: \.1.id) { addon, catalog, state in
            switch state {
            case .loaded(let metas):
                MovieShelf(title: "\(catalog.name) · \(typeLabel(catalog.type))", source: addon.name) {
                    ForEach(metas) { m in MoviePoster(meta: m, subtitle: m.releaseInfo, progress: nil) { selected = m } }
                }
            case .loading:
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text("\(addon.name) is looking…").foregroundStyle(Theme.muted) }.padding(.horizontal, 28)
            case .failed:
                EmptyView()
            }
        }
        if !entries.isEmpty, entries.allSatisfy({ if case .failed = $0.2 { true } else { false } }) {
            Text("Nothing found for “\(query)”.").foregroundStyle(Theme.muted).padding(.horizontal, 28)
        }
    }
}

func typeLabel(_ type: String) -> String {
    switch type { case "movie": "Movies"; case "series": "Series"; case "anime": "Anime"; default: type.capitalized }
}

struct MovieShelf<Content: View>: View {
    let title: String
    let source: String?
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title).font(.system(size: 17, weight: .bold))
                if let source { Text(source).font(.system(size: 11.5)).foregroundStyle(Theme.faint) }
            }
            .padding(.horizontal, 28)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 16) { content }.padding(.horizontal, 28).padding(.vertical, 6)
            }
            .scrollIndicators(.never)
        }
    }
}

private struct MacCatalogShelf: View {
    @Environment(AppModel.self) private var model
    let addon: StremioAddon
    let catalog: StremioCatalog
    let open: (StremioMeta) -> Void

    var body: some View {
        Group {
            switch model.stremio.rows[catalog.id] {
            case .loaded(let metas):
                MovieShelf(title: "\(catalog.name) · \(typeLabel(catalog.type))", source: addon.name) {
                    ForEach(metas) { m in MoviePoster(meta: m, subtitle: m.releaseInfo, progress: nil) { open(m) } }
                }
            case .failed:
                EmptyView()
            default:
                VStack(alignment: .leading, spacing: 12) {
                    Text("\(catalog.name) · \(typeLabel(catalog.type))").font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.muted)
                    HStack(spacing: 16) { ForEach(0..<6, id: \.self) { _ in RoundedRectangle(cornerRadius: 10).fill(Theme.surface2).frame(width: 150, height: 225) } }
                }
                .padding(.horizontal, 28)
            }
        }
        .onAppear { model.stremio.loadRow(addon, catalog) }
    }
}

struct MoviePoster: View {
    let meta: StremioMeta
    let subtitle: String?
    let progress: Double?
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack(alignment: .bottom) {
                    RoundedRectangle(cornerRadius: 10).fill(Theme.surface2)
                    Text(meta.name).font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.muted)
                        .multilineTextAlignment(.center).padding(8).frame(maxHeight: .infinity)
                    AsyncImage(url: meta.poster) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
                    if let progress {
                        LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).frame(height: 30)
                        ProgressLine(value: progress, height: 3).padding(8)
                    }
                }
                .frame(width: 150, height: 225)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(hover ? Theme.accent : .clear, lineWidth: 2))
                .scaleEffect(hover ? 1.04 : 1)
                .shadow(color: .black.opacity(hover ? 0.5 : 0), radius: 14, y: 6)
                Text(meta.name).font(.system(size: 12.5, weight: .medium)).lineLimit(1)
                if let subtitle { Text(subtitle).font(.system(size: 11.5)).foregroundStyle(Theme.faint).lineLimit(1) }
            }
            .frame(width: 150)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(.easeOut(duration: 0.15)) { hover = h } }
    }
}

// MARK: - Sign-in

private struct MacStremioSignIn: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        let s = model.stremio
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "film.stack").font(.system(size: 40)).foregroundStyle(Theme.accent)
            Text("Movies & Series").font(.system(size: 30, weight: .heavy))
            Text("Sign in with your Stremio account to bring your library, Continue Watching and add-ons here. Play on this Mac or on your TV; progress is saved to your account, so the Stremio apps stay in sync.")
                .foregroundStyle(Theme.muted).frame(maxWidth: 520, alignment: .leading)
            if let error = s.signInError {
                Text(error).foregroundStyle(Theme.live)
                Button("Try again") { s.startSignIn() }.buttonStyle(PillButtonStyle(prominent: true))
            } else if let code = s.linkCode {
                Button { openURL(code.link) } label: {
                    Label("Sign in to Stremio", systemImage: "person.crop.circle").frame(minWidth: 200)
                }
                .buttonStyle(PillButtonStyle(prominent: true))
                Text("Opens Stremio's sign-in page in your browser, where Facebook, Apple or email all work. This page updates by itself once you're signed in. Code: \(code.code)")
                    .font(.system(size: 12)).foregroundStyle(Theme.faint).frame(maxWidth: 520, alignment: .leading)
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .onAppear { s.startSignIn() }
    }
}

// MARK: - A title

private struct MacTitleView: View {
    @Environment(AppModel.self) private var model
    let preview: StremioMeta
    let back: () -> Void
    @State private var detail: StremioMetaDetail?
    @State private var loaded = false
    @State private var season: Int?
    @State private var chosen: StremioVideo?

    private var meta: StremioMeta { detail?.meta ?? preview }
    private var isSeries: Bool { (detail?.videos.count ?? 0) > 1 || preview.type == "series" || preview.type == "anime" }

    var body: some View {
        let item = model.stremio.libraryItem(preview.id)
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header(item)
                if !loaded {
                    ProgressView().controlSize(.small).padding(.horizontal, 28)
                } else if isSeries, let detail {
                    HStack(alignment: .top, spacing: 24) {
                        episodes(detail, current: item?.videoID).frame(minWidth: 360, maxWidth: 520)
                        if let chosen {
                            MacSourcesList(meta: meta, videoID: chosen.id, detail: detail, video: chosen, heading: "\(chosen.label) · \(chosen.title)")
                                .id(chosen.id)
                        } else {
                            Text("Pick an episode to see its sources.").foregroundStyle(Theme.muted).padding(.top, 50)
                        }
                    }
                    .padding(.horizontal, 28)
                } else {
                    MacSourcesList(meta: meta, videoID: detail?.videos.count == 1 ? detail!.videos[0].id : preview.id, detail: detail, video: nil, heading: "Sources")
                        .padding(.horizontal, 28)
                }
            }
            .padding(.bottom, 40)
        }
        .flatScrollEdges()
        .task {
            detail = await model.stremio.meta(for: preview)
            loaded = true
            if let detail, isSeries {
                let current = item?.videoID.flatMap { id in detail.videos.first { $0.id == id } }
                season = current?.season ?? detail.seasons.first { $0 > 0 } ?? detail.seasons.first
                chosen = current
            }
        }
        .onKeyPress(.escape) { back(); return .handled }
    }

    private func header(_ item: StremioLibraryItem?) -> some View {
        ZStack(alignment: .bottomLeading) {
            Color.black.overlay {
                AsyncImage(url: meta.background ?? meta.poster) { $0.resizable().scaledToFill() } placeholder: { Theme.surface2 }
            }
            .clipped()
            LinearGradient(colors: [Theme.bg.opacity(0.95), Theme.bg.opacity(0.6), .clear], startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.clear, Theme.bg], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 12) {
                if let logo = meta.logo {
                    AsyncImage(url: logo) { $0.resizable().scaledToFit() } placeholder: { Text(meta.name).font(.system(size: 36, weight: .heavy)) }
                        .frame(maxWidth: 360, maxHeight: 110, alignment: .leading)
                } else {
                    Text(meta.name).font(.system(size: 36, weight: .heavy)).tracking(-0.8).lineLimit(2)
                }
                let facts = [meta.releaseInfo, meta.runtime, meta.imdbRating.map { "★ \($0)" }, meta.genres.prefix(3).joined(separator: " · ")]
                    .compactMap { $0 }.filter { !$0.isEmpty }
                if !facts.isEmpty { Text(facts.joined(separator: "   ")).font(.system(size: 13)).foregroundStyle(Color(hex: 0xC3CAD6)) }
                if let d = meta.description {
                    Text(d).font(.system(size: 13.5)).foregroundStyle(Color(hex: 0xC3CAD6)).lineLimit(4).frame(maxWidth: 620, alignment: .leading)
                }
                HStack(spacing: 10) {
                    if let item, item.inContinueWatching, isSeries, let id = item.videoID, let v = detail?.videos.first(where: { $0.id == id }) {
                        Button { season = v.season; chosen = v } label: {
                            Label(["Resume", item.episodeLabel, item.minutesLeft.map { "\($0) min left" }].compactMap { $0 }.joined(separator: " · "), systemImage: "play.fill")
                        }
                        .buttonStyle(PillButtonStyle(prominent: true))
                    }
                    Button { model.stremio.toggleLibrary(meta) } label: {
                        Label(item?.inLibrary == true ? "In your library" : "Add to library", systemImage: item?.inLibrary == true ? "checkmark" : "plus")
                    }
                    .buttonStyle(PillButtonStyle())
                }
            }
            .padding(28)
            Button(action: back) {
                Image(systemName: "chevron.left").font(.system(size: 14, weight: .bold)).frame(width: 34, height: 34)
                    .background(.black.opacity(0.45), in: Circle())
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(.top, 40).padding(.leading, 24)
        }
        .frame(height: 380)
    }

    private func episodes(_ detail: StremioMetaDetail, current: String?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if detail.seasons.count > 1 {
                ScrollView(.horizontal) {
                    HStack(spacing: 6) {
                        ForEach(detail.seasons, id: \.self) { n in
                            Button { season = n } label: {
                                Text(n == 0 ? "Specials" : "Season \(n)").font(.system(size: 12.5, weight: .semibold))
                                    .padding(.horizontal, 12).frame(height: 28)
                                    .background(season == n ? Theme.accent : Theme.surface2, in: Capsule())
                                    .foregroundStyle(season == n ? Theme.accentInk : Theme.muted)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .scrollIndicators(.never)
            }
            LazyVStack(spacing: 6) {
                ForEach(season.map(detail.episodes) ?? detail.videos) { v in
                    Button { chosen = v } label: {
                        HStack(spacing: 12) {
                            AsyncImage(url: v.thumbnail) { $0.resizable().scaledToFill() } placeholder: { Theme.surface2 }
                                .frame(width: 128, height: 72).clipShape(RoundedRectangle(cornerRadius: 6))
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(v.episode.map { "\($0). " } ?? "")\(v.title)").font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                                if v.id == current { Text("Watching").font(.system(size: 11)).foregroundStyle(Theme.accent) }
                                if let r = v.released, r > .now { Text("Coming \(r.formatted(date: .abbreviated, time: .omitted))").font(.system(size: 11)).foregroundStyle(Theme.accent) }
                                if let o = v.overview { Text(o).font(.system(size: 11.5)).foregroundStyle(Theme.muted).lineLimit(2) }
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(8)
                        .background(chosen?.id == v.id ? Theme.surface3 : Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(chosen?.id == v.id ? Theme.accent.opacity(0.6) : .clear))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - Sources

private struct MacSourcesList: View {
    @Environment(AppModel.self) private var model
    let meta: StremioMeta
    let videoID: String
    let detail: StremioMetaDetail?
    let video: StremioVideo?
    let heading: String
    @State private var groups: [StremioStore.SourceGroup] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            let pending = groups.filter { if case .loading = $0.state { true } else { false } }.count
            HStack(alignment: .firstTextBaseline) {
                Text(heading).font(.system(size: 17, weight: .bold)).lineLimit(1)
                Spacer()
                if pending > 0 { Text("\(pending) add-on\(pending == 1 ? "" : "s") looking…").font(.system(size: 11.5)).foregroundStyle(Theme.faint) }
            }
            ForEach(groups) { g in
                if case .loaded(let streams) = g.state {
                    SectionLabel(g.addon).padding(.top, 6)
                    ForEach(streams) { st in row(st) }
                }
            }
            if pending > 0 { ProgressView().controlSize(.small).frame(maxWidth: .infinity).padding(6) }
            if pending == 0, !groups.contains(where: { if case .loaded = $0.state { true } else { false } }) {
                Text(groups.isEmpty ? "None of your add-ons provide sources for this." : "Your add-ons didn't find any sources for this one.")
                    .foregroundStyle(Theme.muted)
            }
        }
        .task(id: videoID) {
            for await g in model.stremio.sources(type: meta.type, videoID: videoID) {
                groups = g
                #if DEBUG
                if UserDefaults.standard.bool(forKey: "AntennaDevPlayFirst"), model.movie == nil,
                   let st = g.compactMap({ if case .loaded(let s) = $0.state { s.first { $0.playable } } else { nil } }).first {
                    model.playMovie(meta: detail?.meta ?? meta, detail: detail, video: video, videoID: videoID, stream: st)
                    if UserDefaults.standard.bool(forKey: "AntennaDevMoviePiP") {
                        Task { try? await Task.sleep(for: .seconds(30)); model.enterMoviePiP() }
                    }
                }
                #endif
            }
        }
    }

    private func row(_ st: StremioStream) -> some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text((st.name ?? st.addonName).trimmingCharacters(in: .whitespacesAndNewlines)).font(.system(size: 12.5, weight: .bold)).lineLimit(3)
                Text(st.kind).font(.system(size: 10.5)).foregroundStyle(st.playable ? Theme.faint : Theme.live)
            }
            .frame(width: 120, alignment: .leading)
            Text(st.description ?? "").font(.system(size: 12)).foregroundStyle(Color(hex: 0xC3CAD6)).lineLimit(4)
                .frame(maxWidth: .infinity, alignment: .leading)
            if st.playable {
                Button { model.playMovie(meta: detail?.meta ?? meta, detail: detail, video: video, videoID: videoID, stream: st) } label: {
                    Label("Play", systemImage: "play.fill")
                }
                .buttonStyle(PillButtonStyle(prominent: true))
                if !model.cast.devices.isEmpty {
                    Menu {
                        ForEach(model.cast.devices) { tv in
                            Button(tv.displayName) { model.castStremio(meta: meta, videoID: videoID, episodeTitle: video?.title, stream: st, to: tv) }
                        }
                    } label: { Image(systemName: "tv") }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    .help("Play on TV")
                }
            }
        }
        .padding(12)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
        .opacity(st.playable ? 1 : 0.5)
    }
}
