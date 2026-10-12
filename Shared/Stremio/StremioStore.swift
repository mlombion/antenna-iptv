import Foundation
import Observation

/// Movies & Series on this device: the Stremio account (library, Continue Watching, add-ons) and what the
/// add-ons find for a title. Playback happens on an Antenna TV; this device browses and is the remote.
@MainActor @Observable
final class StremioStore {
    private static let keychainAccount = "stremio-auth-key"

    private(set) var authKey: String? = Keychain.get(account: StremioStore.keychainAccount)
    private(set) var email: String? = UserDefaults.standard.string(forKey: "stremioEmail")
    private(set) var addons: [StremioAddon] = []
    private(set) var library: [StremioLibraryItem] = []
    private(set) var isRefreshing = false
    private(set) var loadError: String?
    var hideAdult = true
    var signedIn: Bool { authKey != nil }

    /// False for test runs, whose sign-in must never reach the real app's Keychain entry.
    @ObservationIgnored private var persist = true

    init() {
        #if DEBUG
        // Local test server (Tools/mock_stremio.py) for development: -AntennaDevStremio http://localhost:8790/
        if let dev = UserDefaults.standard.string(forKey: "AntennaDevStremio") {
            StremioAPI.apiBase = dev + "api/"
            StremioAPI.linkBase = dev + "link/"
            persist = false
            authKey = nil
            email = nil
        }
        #endif
    }

    // MARK: Sign-in

    private(set) var linkCode: (code: String, link: URL)?
    private(set) var signInError: String?
    @ObservationIgnored private var signInTask: Task<Void, Never>?

    func startSignIn() {
        guard signInTask == nil else { return }
        signInError = nil
        signInTask = Task {
            defer { signInTask = nil }
            while !Task.isCancelled {
                do { linkCode = try await StremioAPI.createLink() } catch {
                    signInError = error.localizedDescription; linkCode = nil; return
                }
                guard let code = linkCode?.code else { return }
                for _ in 0..<100 {
                    try? await Task.sleep(for: .seconds(3))
                    if Task.isCancelled { return }
                    if let key = await StremioAPI.readLink(code) { await finishSignIn(key); return }
                }
            }
        }
    }

    func cancelSignIn() { signInTask?.cancel(); signInTask = nil; linkCode = nil }

    private func finishSignIn(_ key: String) async {
        do {
            let mail = try await StremioAPI.email(key)
            if persist {
                try? Keychain.set(key, account: Self.keychainAccount)
                UserDefaults.standard.set(mail, forKey: "stremioEmail")
            }
            authKey = key
            email = mail
            linkCode = nil
            rows = [:]
            await refresh()
        } catch {
            signInError = error.localizedDescription
        }
    }

    func signOut() {
        cancelSignIn()
        if persist {
            Keychain.delete(account: Self.keychainAccount)
            UserDefaults.standard.removeObject(forKey: "stremioEmail")
        }
        authKey = nil; email = nil
        addons = []; library = []; rows = [:]; searchResults = [:]
    }

    func refresh() async {
        guard let key = authKey else { return }
        isRefreshing = true
        loadError = nil
        defer { isRefreshing = false }
        do {
            async let a = StremioAPI.addons(key)
            async let l = StremioAPI.library(key)
            addons = try await a
            // Keep changes made here that haven't reached the account yet.
            let fetched = try await l
            let unsent = library.filter { dirty.contains($0.id) }
            library = fetched.map { item in unsent.first { $0.id == item.id && $0.mtime > item.mtime } ?? item }
                + unsent.filter { u in !fetched.contains { $0.id == u.id } }
        } catch let e as StremioError {
            loadError = e.message
            if e.signedOut { signOut() }
        } catch {
            loadError = error.localizedDescription
        }
    }

    // MARK: What the screens show

    var continueWatching: [StremioLibraryItem] {
        Array(library.filter(\.inContinueWatching).sorted { $0.mtime > $1.mtime }.prefix(40))
    }
    var libraryItems: [StremioLibraryItem] { library.filter(\.inLibrary).sorted { $0.mtime > $1.mtime } }
    func libraryItem(_ id: String) -> StremioLibraryItem? { library.first { $0.id == id } }

    private let shownTypes: Set<String> = ["movie", "series", "anime"]
    private var visibleAddons: [StremioAddon] { addons.filter { !(hideAdult && $0.adult) } }

    /// Catalog rows from the installed add-ons, in the order they were installed (as on Stremio's board).
    var boardCatalogs: [(addon: StremioAddon, catalog: StremioCatalog)] {
        visibleAddons.flatMap { a in a.catalogs.filter { !$0.needsInput && shownTypes.contains($0.type) }.map { (a, $0) } }
    }

    enum RowState { case loading, loaded([StremioMeta]), failed }
    private(set) var rows: [String: RowState] = [:]

    func loadRow(_ addon: StremioAddon, _ catalog: StremioCatalog) {
        guard rows[catalog.id] == nil else { return }
        rows[catalog.id] = .loading
        Task {
            let metas = try? await StremioAPI.catalog(addon, catalog)
            rows[catalog.id] = (metas?.isEmpty ?? true) ? .failed : .loaded(metas!)
        }
    }

    // MARK: Search

    private(set) var searchResults: [String: RowState] = [:]
    @ObservationIgnored private var searchTask: Task<Void, Never>?

    var searchCatalogs: [(addon: StremioAddon, catalog: StremioCatalog)] {
        visibleAddons.flatMap { a in a.catalogs.filter { $0.supportsSearch && $0.searchOnly && shownTypes.contains($0.type) }.map { (a, $0) } }
    }

    func search(_ query: String) {
        searchTask?.cancel()
        searchResults = [:]
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return }
        searchTask = Task {
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            await withTaskGroup(of: Void.self) { group in
                for (addon, catalog) in searchCatalogs {
                    searchResults[catalog.id] = .loading
                    group.addTask { @MainActor in
                        let metas = try? await StremioAPI.catalog(addon, catalog, search: q)
                        guard !Task.isCancelled else { return }
                        self.searchResults[catalog.id] = (metas?.isEmpty ?? true) ? .failed : .loaded(metas!)
                    }
                }
            }
        }
    }

    // MARK: A title: details and sources

    func meta(for preview: StremioMeta) async -> StremioMetaDetail? {
        for a in visibleAddons where a.provides("meta", type: preview.type, id: preview.id) {
            if let m = try? await StremioAPI.meta(a, type: preview.type, id: preview.id) { return m }
        }
        return nil
    }

    struct SourceGroup: Identifiable {
        let addon: String
        var state: RowStateSources
        var id: String { addon }
    }
    enum RowStateSources { case loading, loaded([StremioStream]), failed }

    /// Asks every add-on that has sources for this movie or episode; `update` is called as each answers.
    func sources(type: String, videoID: String) -> AsyncStream<[SourceGroup]> {
        let providers = visibleAddons.filter { $0.provides("stream", type: type, id: videoID) }
        return AsyncStream { continuation in
            var groups = providers.map { SourceGroup(addon: $0.name, state: .loading) }
            continuation.yield(groups)
            let task = Task { @MainActor in
                await withTaskGroup(of: (Int, RowStateSources).self) { group in
                    for (i, a) in providers.enumerated() {
                        group.addTask {
                            let s = try? await StremioAPI.streams(a, type: type, videoID: videoID)
                            return (i, (s?.isEmpty ?? true) ? .failed : .loaded(s!))
                        }
                    }
                    for await (i, state) in group {
                        groups[i].state = state
                        continuation.yield(groups)
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: Watching here: progress, subtitles, library

    @ObservationIgnored private var dirty = Set<String>()
    @ObservationIgnored private var lastPush = Date.distantPast

    /// Stremio's own progress rules, so Continue Watching and "watched" match the official apps.
    func recordProgress(meta: StremioMeta, videoID: String, nextVideoID: String?, time: TimeInterval, duration: TimeInterval, final: Bool) {
        let t = Int((time * 1000).rounded()), d = Int((duration * 1000).rounded())
        guard d > 0, t > 0 else { return }
        let existing = libraryItem(meta.id) ?? .new(for: meta)
        let item = existing.updated { _, st in
            func n(_ k: String) -> Int { (st[k] as? Int) ?? Int((st[k] as? Double) ?? 0) }
            st["lastWatched"] = StremioDate.format(Date())
            if (st["video_id"] as? String) != videoID {
                st["overallTimeWatched"] = n("overallTimeWatched") + n("timeWatched")
                st["timeWatched"] = 0; st["flaggedWatched"] = 0; st["timeOffset"] = 0
                st["video_id"] = videoID
            }
            let delta = t - n("timeOffset")
            if delta > 0 && delta <= 60_000 {
                st["timeWatched"] = n("timeWatched") + delta
                st["overallTimeWatched"] = n("overallTimeWatched") + delta
            }
            st["timeOffset"] = t
            st["duration"] = d
            if Double(n("timeWatched")) > Double(d) * 0.7 && n("flaggedWatched") == 0 {
                st["flaggedWatched"] = 1
                st["timesWatched"] = n("timesWatched") + 1
            }
            if final && Double(t) > Double(d) * 0.9 {
                // Finished: a series moves on to the next episode; a movie leaves Continue Watching.
                if let nextVideoID {
                    st["overallTimeWatched"] = n("overallTimeWatched") + n("timeWatched")
                    st["video_id"] = nextVideoID; st["timeOffset"] = 1; st["timeWatched"] = 0; st["flaggedWatched"] = 0
                } else {
                    st["timeOffset"] = 0
                }
            }
        }
        upsert(item)
        push(force: final)
    }

    func toggleLibrary(_ meta: StremioMeta) {
        let existing = libraryItem(meta.id)
        let add = existing?.inLibrary != true
        let item = (existing ?? .new(for: meta)).updated { o, _ in
            o["removed"] = !add; o["temp"] = false
            if add { o["name"] = meta.name; if let p = meta.poster { o["poster"] = p.absoluteString } }
        }
        upsert(item)
        push(force: true)
    }

    private func upsert(_ item: StremioLibraryItem) {
        library = library.filter { $0.id != item.id } + [item]
        dirty.insert(item.id)
    }

    /// Sends changed entries to the account at most every 90 seconds while watching (as Stremio does).
    private func push(force: Bool) {
        guard let key = authKey, force || Date().timeIntervalSince(lastPush) > 90 else { return }
        lastPush = Date()
        let items = library.filter { dirty.contains($0.id) }
        guard !items.isEmpty else { return }
        Task {
            if (try? await StremioAPI.putLibrary(key, items)) != nil { dirty.subtract(items.map(\.id)) }
        }
    }

    /// Subtitles from the add-ons that have them, one per language, your languages first.
    func subtitles(type: String, videoID: String, fileName: String?, fileSize: Int64?) async -> [StremioSubtitle] {
        var extra: [(String, String)] = []
        if let fileSize { extra.append(("videoSize", String(fileSize))) }
        if let fileName { extra.append(("filename", fileName)) }
        var found: [StremioSubtitle] = []
        for a in visibleAddons where a.provides("subtitles", type: type, id: videoID) {
            found += (try? await StremioAPI.subtitles(a, type: type, videoID: videoID, extra: extra)) ?? []
        }
        var seen = Set<String>()
        let unique = found.filter { seen.insert($0.languageName).inserted }
        let preferred = ([UserDefaults.standard.string(forKey: "subtitleLang")] + Locale.preferredLanguages.map { String($0.prefix(2)) } + ["en", "fr"])
            .compactMap { $0 }.map(StremioSubtitle.languageName)
        func rank(_ s: StremioSubtitle) -> Int { preferred.firstIndex(of: s.languageName) ?? Int.max }
        return unique.sorted { (rank($0), $0.languageName) < (rank($1), $1.languageName) }
    }

    /// What the TV needs to play a source and save progress to this account.
    func castBody(meta: StremioMeta, videoID: String, episodeTitle: String?, stream: StremioStream, startAt: TimeInterval? = nil) -> JSON {
        var body: JSON = ["type": meta.type, "id": meta.id, "videoId": videoID, "name": meta.name,
                          "addon": stream.addonName, "stream": stream.raw]
        if let p = meta.poster?.absoluteString { body["poster"] = p }
        if let b = meta.background?.absoluteString { body["background"] = b }
        if let episodeTitle { body["episodeTitle"] = episodeTitle }
        if let authKey { body["authKey"] = authKey }
        if let startAt { body["startAt"] = Int(startAt * 1000) }
        return body
    }
}
