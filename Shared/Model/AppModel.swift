import SwiftUI
import Observation

enum AppSection: Hashable {
    case home, live, guide, movies, favorites, settings
}

/// The floating picture-in-picture window. Provided by the Mac app; the iPhone app has none yet.
@MainActor protocol PiPPresenting: AnyObject {
    func show()
    func hide()
    func updateAspectRatio(_ size: CGSize)
    /// Brings the main app window to the front after the video returns from the floating window.
    func bringAppForward()
    /// False when the window appears later (iPhone): the model then waits for `pipDidStart`.
    var activatesImmediately: Bool { get }
}

extension PiPPresenting {
    var activatesImmediately: Bool { true }
}

@MainActor @Observable
final class AppModel {
    enum Phase: Equatable { case launching, signedOut, connecting, ready }

    struct Step: Identifiable, Equatable {
        enum State { case waiting, active, done }
        let id: Int
        var title: String
        var state: State
    }

    // MARK: Session
    var phase: Phase = .launching
    private(set) var isLoggingIn = false
    private(set) var steps: [Step] = []
    var loginError: String?

    private(set) var account: SavedAccount?
    @ObservationIgnored private(set) var client: XtreamClient?
    private(set) var accountInfo: XtreamAccountInfo?
    private(set) var lastRefresh: Date?
    private(set) var isRefreshing = false

    // MARK: Lineup
    private(set) var allCategories: [LiveCategory] = []
    private(set) var channels: [Channel] = []
    private(set) var byCategory: [String: [Channel]] = [:]
    private(set) var byID: [Int: Channel] = [:]
    @ObservationIgnored private var byNum: [Int: Channel] = [:]

    // MARK: Navigation
    var section: AppSection = .live
    var selectedCategoryID = LiveCategory.allID
    var selectedChannelID: Int?
    var filter = ""
    var guideCategoryID: String?
    var showSearch = false
    private(set) var toast: String?

    // MARK: Personal
    private(set) var favorites: Set<Int>
    private(set) var recents: [Int]

    // MARK: Guide
    private var epg: [Int: EPGState] = [:]
    @ObservationIgnored private var epgFetchedAt: [Int: Date] = [:]
    @ObservationIgnored private var epgInFlight: Set<Int> = []
    @ObservationIgnored private let epgLimiter = AsyncLimiter(4)
    private(set) var now = Date()

    // MARK: Full guide (for searching shows)
    private(set) var guideIndex: [String: [Programme]] = [:]
    private(set) var isLoadingGuide = false
    @ObservationIgnored private var guideBuiltAt: Date?
    @ObservationIgnored private var titleTokens: [String: [String]] = [:]

    // MARK: Playback
    let settings = AppSettings()
    let player = PlayerController()
    let cast = CastManager()
    /// The phone remote for the whole TV (trackpad, air mouse, buttons).
    let pointer = PointerClient()
    var showRemote = false
    /// Movies & Series from the user's Stremio account and add-ons (played on an Antenna TV).
    let stremio = StremioStore()
    /// The movie or episode playing on this device, and its player (separate from live TV's).
    let vod = VodPlayer()
    var movie: MoviePlayback?
    /// The movie is in the floating window (Mac) or Apple's picture in picture (iPhone).
    var isMoviePiP = false
    @ObservationIgnored var movieTask: Task<Void, Never>?
    private(set) var castError: String?
    private(set) var playing: Channel?
    private(set) var zapList: [Channel] = []
    @ObservationIgnored private var zapTask: Task<Void, Never>?
    private(set) var isPiP = false
    @ObservationIgnored var pipPresenter: PiPPresenting?
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored private var clockTask: Task<Void, Never>?
    /// False for the debug dev-server login, so test runs never overwrite the real saved account.
    @ObservationIgnored private var persist = true

    init() {
        favorites = Set(UserDefaults.standard.array(forKey: "favorites") as? [Int] ?? [])
        recents = UserDefaults.standard.array(forKey: "recents") as? [Int] ?? []
    }

    // MARK: - Launch

    func start() async {
        guard phase == .launching else { return }
        TimeshiftBuffer.removeLeftovers()
        clockTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(20))
                self?.now = Date()
            }
        }

        #if DEBUG
        if await startFromDevArguments() { return }
        #endif

        guard let acc = SavedAccount.load(), let password = Keychain.get(account: acc.keychainKey),
              let server = URL(string: acc.server) else {
            phase = .signedOut
            return
        }
        account = acc
        client = XtreamClient(server: server, username: acc.username, password: password)

        if let cached = LineupCache.load() {
            apply(cached)
            phase = .ready
            applyStartSection()
            Task { await refresh(silent: true) }
        } else {
            phase = .connecting
            do {
                let lineup = try await load(using: client!, showSteps: true)
                apply(lineup)
                LineupCache.save(lineup)
                phase = .ready
                applyStartSection()
            } catch {
                loginError = message(for: error)
                phase = .signedOut
            }
        }
    }

    private func applyStartSection() {
        switch settings.startSection {
        case .home: section = .home
        case .live: section = .live
        case .lastChannel:
            section = .live
            if let id = recents.first, let ch = byID[id] { play(ch) }
        }
    }

    // MARK: - Account

    func login(name: String, server: String, username: String, password: String) async {
        loginError = nil
        let user = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let pass = password.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let base = XtreamClient.normalize(server) else { loginError = XtreamError.badURL.errorDescription; return }
        guard !user.isEmpty, !pass.isEmpty else { loginError = "Enter your username and password."; return }

        let newClient = XtreamClient(server: base, username: user, password: pass)
        isLoggingIn = true
        phase = .connecting
        do {
            let lineup = try await load(using: newClient, showSteps: true)
            let acc = SavedAccount(name: name.trimmingCharacters(in: .whitespaces).isEmpty ? "My IPTV" : name,
                                   server: base.absoluteString, username: user)
            if persist {
                if let old = account, old.keychainKey != acc.keychainKey { Keychain.delete(account: old.keychainKey) }
                try? Keychain.set(pass, account: acc.keychainKey)
                acc.save()
                LineupCache.save(lineup)
            }
            if account?.keychainKey != acc.keychainKey { epg = [:]; epgFetchedAt = [:] }
            account = acc
            client = newClient
            apply(lineup)
            isLoggingIn = false
            phase = .ready
            section = .live
            showToast("Connected · \(channels.count.formatted()) channels")
        } catch {
            isLoggingIn = false
            loginError = message(for: error)
            phase = account == nil ? .signedOut : .ready
        }
    }

    /// Opens the login screen while keeping the current account until a new one connects.
    func beginSwitchAccount() {
        stopPlaying()
        loginError = nil
        phase = .signedOut
    }

    func cancelSwitchAccount() {
        guard account != nil, !channels.isEmpty else { return }
        phase = .ready
    }

    func signOut() {
        stopPlaying()
        if let acc = account { Keychain.delete(account: acc.keychainKey) }
        SavedAccount.clear()
        LineupCache.clear()
        account = nil
        client = nil
        accountInfo = nil
        allCategories = []
        channels = []
        reindex()
        epg = [:]
        epgFetchedAt = [:]
        selectedChannelID = nil
        phase = .signedOut
    }

    func refresh(silent: Bool) async {
        guard let client, !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        do {
            let lineup = try await load(using: client, showSteps: false)
            apply(lineup)
            if persist { LineupCache.save(lineup) }
            if !silent { showToast("Channels refreshed · \(channels.count.formatted()) channels") }
        } catch is CancellationError {
        } catch {
            let important = (error as? XtreamError).map { if case .inactive = $0 { true } else { $0 == .badCredentials } } ?? false
            if !silent || important { showToast("Couldn't refresh channels. \(message(for: error))") }
        }
    }

    private func load(using client: XtreamClient, showSteps: Bool) async throws -> Lineup {
        if showSteps {
            steps = ["Connecting to the server", "Loading categories", "Loading channels"]
                .enumerated().map { Step(id: $0.offset, title: $0.element, state: .waiting) }
        }
        mark(0)
        let info = try await client.authenticate()
        mark(1)
        async let cats = client.liveCategories()
        mark(2)
        let chans = try await client.liveStreams()
        let categories = try await cats
        if showSteps {
            steps[2].title = "Loaded \(chans.count.formatted()) channels"
            steps[2].state = .done
        }
        return Lineup(account: info, categories: categories, channels: chans, savedAt: Date())
    }

    private func mark(_ index: Int) {
        guard steps.indices.contains(index) else { return }
        for i in steps.indices { steps[i].state = i < index ? .done : (i == index ? .active : .waiting) }
    }

    private func apply(_ lineup: Lineup) {
        accountInfo = lineup.account
        let known = Set(lineup.categories.map(\.id))
        // Channels whose category the panel doesn't list still need a home.
        var cats = lineup.categories
        if lineup.channels.contains(where: { !known.contains($0.categoryID) }) {
            cats.append(LiveCategory(id: "", name: "Other"))
        }
        allCategories = cats
        channels = lineup.channels.map { known.contains($0.categoryID) ? $0 : $0.withCategory("") }
        lastRefresh = lineup.savedAt
        reindex()
        Task { await loadGuideIndex() }
        if selectedChannelID.flatMap({ byID[$0] }) == nil { selectedChannelID = visibleChannels.first?.id }
    }

    private func reindex() {
        byCategory = Dictionary(grouping: channels, by: \.categoryID)
        byID = Dictionary(channels.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        byNum = Dictionary(channels.map { ($0.num, $0) }, uniquingKeysWith: { a, _ in a })
    }

    private func message(for error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }

    // MARK: - Lineup queries

    var categories: [LiveCategory] {
        settings.hideAdult ? allCategories.filter { !$0.isAdult } : allCategories
    }

    private var hiddenCategoryIDs: Set<String> {
        settings.hideAdult ? Set(allCategories.filter(\.isAdult).map(\.id)) : []
    }

    func channels(in categoryID: String) -> [Channel] {
        switch categoryID {
        case LiveCategory.allID:
            let hidden = hiddenCategoryIDs
            return hidden.isEmpty ? channels : channels.filter { !hidden.contains($0.categoryID) }
        case LiveCategory.favoritesID:
            return channels.filter { favorites.contains($0.id) }
        default:
            return byCategory[categoryID] ?? []
        }
    }

    func categoryName(_ id: String) -> String {
        switch id {
        case LiveCategory.allID: "All channels"
        case LiveCategory.favoritesID: "Favorites"
        default: allCategories.first { $0.id == id }?.name ?? "Other"
        }
    }

    var visibleChannels: [Channel] {
        let base = channels(in: selectedCategoryID)
        let f = filter.trimmingCharacters(in: .whitespaces)
        return f.isEmpty ? base : base.filter { $0.matches(f) }
    }

    var selectedChannel: Channel? { selectedChannelID.flatMap { byID[$0] } }
    var favoriteChannels: [Channel] { channels.filter { favorites.contains($0.id) } }
    var recentChannels: [Channel] { recents.compactMap { byID[$0] } }
    func channel(number: Int) -> Channel? { byNum[number] }

    func selectCategory(_ id: String) {
        selectedCategoryID = id
        filter = ""
        let list = visibleChannels
        if let sel = selectedChannelID, list.contains(where: { $0.id == sel }) { return }
        selectedChannelID = list.first?.id
    }

    /// Moves the Live TV selection up or down the visible list.
    func moveSelection(_ delta: Int) {
        let list = visibleChannels
        guard !list.isEmpty else { return }
        let i = list.firstIndex { $0.id == selectedChannelID } ?? -1
        selectedChannelID = list[min(list.count - 1, max(0, i + delta))].id
    }

    func reveal(_ channel: Channel) {
        if !channels(in: selectedCategoryID).contains(channel) { selectedCategoryID = LiveCategory.allID }
        filter = ""
        selectedChannelID = channel.id
        section = .live
    }

    // MARK: - Guide data

    func schedule(for channel: Channel) -> Schedule {
        var state = epg[channel.id]
        if case .loaded = state {} else if let fromGuide = indexProgrammes(channel), !fromGuide.isEmpty {
            // The full guide already knows this channel, so no need to wait for its own request.
            state = .loaded(fromGuide)
        }
        guard case .loaded(let list) = state else { return Schedule(state: state) }
        let current = list.first { $0.isOn(at: now) }
        let after = current?.end ?? now
        return Schedule(current: current, upcoming: list.filter { $0.start >= after }, state: state)
    }

    func programmes(for channel: Channel) -> [Programme] {
        if case .loaded(let list) = epg[channel.id] { return list }
        return indexProgrammes(channel) ?? []
    }

    private func indexProgrammes(_ channel: Channel) -> [Programme]? {
        guard let id = channel.epgID?.lowercased() else { return nil }
        return guideIndex[id]
    }

    // MARK: - Searching shows

    struct ShowMatch: Identifiable {
        let channel: Channel
        let programme: Programme
        var id: String { "\(channel.id)-\(programme.start.timeIntervalSince1970)" }
    }

    /// Channels showing something that matches `query` now, and later today.
    func showMatches(_ query: String, limit: Int = 30) -> (now: [ShowMatch], later: [ShowMatch]) {
        let queries = ShowMatcher.queries(query)
        guard !queries.isEmpty else { return ([], []) }
        let t = now
        let horizon = t.addingTimeInterval(8 * 3600)
        var onNow: [ShowMatch] = []
        var later: [ShowMatch] = []
        for channel in channels(in: LiveCategory.allID) {
            let list = programmes(for: channel)
            if list.isEmpty { continue }
            var laterFound = false
            for p in list where p.end > t && p.start < horizon {
                guard ShowMatcher.matches(queries, tokens(for: p.title)) else { continue }
                if p.isOn(at: t) {
                    if onNow.count < limit { onNow.append(ShowMatch(channel: channel, programme: p)) }
                } else if !laterFound, later.count < limit * 3 {
                    later.append(ShowMatch(channel: channel, programme: p))
                    laterFound = true
                }
            }
        }
        later.sort { $0.programme.start < $1.programme.start }
        return (onNow, Array(later.prefix(limit)))
    }

    private func tokens(for title: String) -> [String] {
        if let cached = titleTokens[title] { return cached }
        let t = ShowMatcher.tokens(title)
        titleTokens[title] = t
        return t
    }

    private static var guideCacheURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "Antenna/guide-index.json")
    }

    /// Loads the full guide: the saved copy if it's recent, otherwise a fresh download in the background.
    func loadGuideIndex(force: Bool = false) async {
        guard let client, !isLoadingGuide else { return }
        if !force, guideBuiltAt.map({ Date().timeIntervalSince($0) < 3 * 3600 }) == true { return }
        if !force, persist, guideIndex.isEmpty, let data = try? Data(contentsOf: Self.guideCacheURL),
           let cached = try? JSONDecoder().decode(GuideIndex.self, from: data),
           Date().timeIntervalSince(cached.builtAt) < 3 * 3600 {
            guideIndex = cached.programmes
            guideBuiltAt = cached.builtAt
            return
        }
        isLoadingGuide = true
        defer { isLoadingGuide = false }
        do {
            let index = try await GuideIndex.download(client, from: Date().addingTimeInterval(-3600), to: Date().addingTimeInterval(10 * 3600))
            guideIndex = index.programmes
            guideBuiltAt = index.builtAt
            titleTokens = [:]
            AppLog.write("guide: full guide loaded for \(index.programmes.count) channels")
            if persist {
                let url = Self.guideCacheURL
                Task.detached(priority: .utility) { try? JSONEncoder().encode(index).write(to: url, options: .atomic) }
            }
        } catch {
            AppLog.write("guide: full guide unavailable: \(error.localizedDescription)")
        }
    }

    func requestEPG(_ channel: Channel) {
        guard let client else { return }
        let id = channel.id
        guard !epgInFlight.contains(id) else { return }
        if let fetched = epgFetchedAt[id] {
            switch epg[id] {
            case .loaded(let list):
                let coversNextHour = (list.last?.end ?? .distantPast) > now.addingTimeInterval(3600)
                if coversNextHour && now.timeIntervalSince(fetched) < 1800 { return }
            case .unavailable:
                if now.timeIntervalSince(fetched) < 3600 { return }
            case .failed:
                if now.timeIntervalSince(fetched) < 120 { return }
            default: break
            }
        }
        epgInFlight.insert(id)
        if epg[id] == nil { epg[id] = .loading }
        Task {
            await epgLimiter.acquire()
            let result: EPGState
            do {
                let list = try await client.shortEPG(streamID: id)
                result = list.isEmpty ? .unavailable : .loaded(list)
            } catch {
                result = .failed
            }
            await epgLimiter.release()
            epgInFlight.remove(id)
            epgFetchedAt[id] = Date()
            if case .failed = result, case .loaded = epg[id] { return }
            epg[id] = result
        }
    }

    // MARK: - Personal

    func isFavorite(_ c: Channel) -> Bool { favorites.contains(c.id) }

    func toggleFavorite(_ c: Channel) {
        if favorites.contains(c.id) {
            favorites.remove(c.id)
            showToast("Removed \(c.displayName) from Favorites")
        } else {
            favorites.insert(c.id)
            showToast("Added \(c.displayName) to Favorites")
        }
        if persist { UserDefaults.standard.set(Array(favorites), forKey: "favorites") }
    }

    func noteRecent(_ c: Channel) {
        recents.removeAll { $0 == c.id }
        recents.insert(c.id, at: 0)
        if recents.count > 20 { recents.removeLast(recents.count - 20) }
        if persist { UserDefaults.standard.set(recents, forKey: "recents") }
    }

    // MARK: - Playback

    var effectiveFormat: StreamFormat {
        let allowed = accountInfo?.formats ?? []
        if allowed.isEmpty || allowed.contains(settings.format.rawValue) { return settings.format }
        return allowed.contains("m3u8") ? .m3u8 : .ts
    }

    private var playerOptions: PlayerController.Options {
        .init(bufferMs: settings.buffer.rawValue,
              hardwareDecoding: settings.hardwareDecoding,
              autoReconnect: settings.autoReconnect,
              timeshiftMinutes: settings.rewindMinutes)
    }

    /// Starts a channel. Up/down in the player then moves through `list` (defaults to what's on screen).
    func play(_ channel: Channel, in list: [Channel]? = nil) {
        if let tv = cast.active {
            if let list, list.contains(channel) { zapList = list }
            castChannel(channel, to: tv)
            return
        }
        if let list, list.contains(channel) {
            zapList = list
        } else {
            let visible = visibleChannels
            zapList = visible.contains(channel) ? visible : channels(in: LiveCategory.allID)
        }
        showSearch = false
        tune(channel, debounce: false)
    }

    func zap(_ delta: Int) {
        guard let current = playing, !zapList.isEmpty else { return }
        let i = zapList.firstIndex(of: current) ?? 0
        tune(zapList[(i + delta + zapList.count) % zapList.count], debounce: true)
    }

    func tune(number: Int) {
        guard let c = channel(number: number) else { showToast("No channel \(number)"); return }
        if !zapList.contains(c) { zapList = channels(in: LiveCategory.allID) }
        tune(c, debounce: false)
    }

    private func tune(_ channel: Channel, debounce: Bool) {
        if movie != nil { stopMovie() }
        playing = channel
        selectedChannelID = channel.id
        noteRecent(channel)
        requestEPG(channel)
        guard let client else { return }
        let url = client.streamURL(for: channel, format: effectiveFormat)
        let options = playerOptions
        zapTask?.cancel()
        player.prepare()
        if debounce {
            // Holding the arrow key flips through channels; only open the one you stop on.
            zapTask = Task { [player] in
                try? await Task.sleep(for: .milliseconds(260))
                guard !Task.isCancelled else { return }
                player.load(url, options: options)
            }
        } else {
            player.load(url, options: options)
        }
    }

    func stopPlaying() {
        if isPiP {
            isPiP = false
            pipPresenter?.hide()
        }
        player.surface = .main
        zapTask?.cancel()
        player.stop()
        playing = nil
    }

    // MARK: - Play on TV (Antenna Connect)

    /// The channel currently playing on the TV, as this device last sent it.
    private(set) var castingChannel: Channel?

    /// Sends what's playing here to the TV and stops playing it locally (most subscriptions allow one stream).
    func castCurrent(to tv: CastManager.Device) {
        guard let channel = playing else { return }
        if zapList.isEmpty { zapList = channels(in: LiveCategory.allID) }
        castChannel(channel, to: tv)
    }

    func castChannel(_ channel: Channel, to tv: CastManager.Device) {
        castError = nil
        // Free the subscription's connection before the TV asks for it.
        if playing != nil { stopPlaying() }
        castingChannel = channel
        noteRecent(channel)
        requestEPG(channel)
        let account: [String: String]? = client.map {
            ["name": self.account?.name ?? "My IPTV", "server": $0.server.absoluteString, "username": $0.username, "password": $0.password]
        }
        Task {
            do {
                try await cast.play(streamId: channel.id, on: tv, account: account)
                showToast("Playing on \(tv.displayName)")
            } catch {
                castError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                showToast(castError ?? "Couldn't play on the TV.")
                if cast.active == nil { castingChannel = nil }
            }
        }
    }

    /// Sends a movie or episode from Movies & Series to the TV, which plays the source itself.
    func castStremio(meta: StremioMeta, videoID: String, episodeTitle: String?, stream: StremioStream, to tv: CastManager.Device) {
        castError = nil
        if playing != nil { stopPlaying() }
        castingChannel = nil
        let body = stremio.castBody(meta: meta, videoID: videoID, episodeTitle: episodeTitle, stream: stream)
        Task {
            do {
                try await cast.playStremio(body, on: tv)
                showToast("Playing on \(tv.displayName)")
            } catch {
                castError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                showToast(castError ?? "Couldn't play on the TV.")
            }
        }
    }

    /// Channel up/down on the TV, through this device's channel list.
    func castZap(_ delta: Int) {
        guard let tv = cast.active, let current = castingChannel, !zapList.isEmpty else { return }
        let i = zapList.firstIndex(of: current) ?? 0
        castChannel(zapList[(i + delta + zapList.count) % zapList.count], to: tv)
    }

    /// Stops the TV; optionally carries on watching here.
    func stopCasting(resumeHere: Bool) {
        let channel = castingChannel
        cast.disconnect(stopTV: true)
        castingChannel = nil
        if resumeHere, let channel {
            // Give the TV a moment to release the subscription's single connection.
            Task {
                try? await Task.sleep(for: .milliseconds(800))
                play(channel, in: zapList.isEmpty ? nil : zapList)
            }
        }
    }

    // MARK: - Picture in picture

    /// Moves the video into a floating window that stays above other apps.
    func enterPiP() {
        guard playing != nil, !isPiP, let pipPresenter else { return }
        guard pipPresenter.activatesImmediately else { pipPresenter.show(); return }
        player.surface = .pip
        isPiP = true
        pipPresenter.show()
    }

    /// Closes the floating window, either bringing the video back into Antenna or stopping it.
    func exitPiP(returnToApp: Bool) {
        guard isPiP else { return }
        if returnToApp {
            player.surface = .main
            pipPresenter?.hide()
            isPiP = false
            pipPresenter?.bringAppForward()
        } else {
            stopPlaying()
        }
    }

    /// System picture-in-picture events (iPhone).
    func handleSystemPiP(_ event: PlayerController.PiPEvent) {
        switch event {
        case .started:
            isPiP = true
        case .restore:
            isPiP = false
        case .stopped(let restored):
            // Closing the floating video with its ✕ stops playback, as on the Mac.
            if !restored && isPiP {
                isPiP = false
                stopPlaying()
            }
        }
    }

    func togglePiP() {
        if isPiP { exitPiP(returnToApp: true) } else { enterPiP() }
    }

    func pipAspectChanged(_ size: CGSize) {
        if isPiP { pipPresenter?.updateAspectRatio(size) }
    }

    // MARK: - UI helpers

    func go(_ s: AppSection) {
        guard phase == .ready else { return }
        if playing != nil && !isPiP { stopPlaying() }
        if movie != nil && !isMoviePiP { stopMovie() }
        showSearch = false
        section = s
    }

    func showToast(_ text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.2))
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    // MARK: - Debug launch arguments

    #if DEBUG
    /// `-AntennaDevServer URL -AntennaDevUser U -AntennaDevPass P [-AntennaDevSection guide] [-AntennaDevPlay 3]`
    /// logs into a local test server without saving anything. Used for development only.
    private func startFromDevArguments() async -> Bool {
        let d = UserDefaults.standard
        guard let s = d.string(forKey: "AntennaDevServer"), let u = d.string(forKey: "AntennaDevUser"),
              let p = d.string(forKey: "AntennaDevPass") else { return false }
        persist = false
        favorites = []
        recents = []
        await login(name: "Test server", server: s, username: u, password: p)
        guard phase == .ready else { return true }
        let favs = channels.prefix(8).map(\.id).filter { $0 % 3 != 0 }
        favorites = Set(favs)
        recents = Array(channels.dropFirst(10).prefix(5).map(\.id))
        switch d.string(forKey: "AntennaDevSection") {
        case "home": section = .home
        case "guide": section = .guide
        case "favorites": section = .favorites
        case "movies": section = .movies
        case "settings": section = .settings
        case "login": phase = .signedOut
        default: section = .live
        }
        if d.bool(forKey: "AntennaDevSearch") { showSearch = true }
        if let f = d.string(forKey: "AntennaDevFilter") { filter = f }
        let num = d.integer(forKey: "AntennaDevPlay")
        if num > 0, let c = channel(number: num) { play(c) }
        #if os(macOS)
        DevTour.runIfRequested(self)
        #endif
        return true
    }
    #endif
}

private extension Channel {
    func withCategory(_ id: String) -> Channel {
        Channel(id: self.id, num: num, name: name, displayName: displayName, quality: quality, iconURL: iconURL,
                categoryID: id, epgID: epgID, hasArchive: hasArchive)
    }
}
