import Foundation

// Stremio's data as its own apps read it: the account (library, installed add-ons) and the open add-on protocol.
// Kept as plain JSON underneath so a source can be handed to the TV exactly as the add-on described it.

typealias JSON = [String: Any]

private func str(_ o: JSON, _ k: String) -> String? {
    guard let s = o[k] as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
    return s
}

private func strings(_ v: Any?) -> [String] { (v as? [Any])?.compactMap { $0 as? String }.filter { !$0.isEmpty } ?? [] }

private func int(_ v: Any?) -> Int? {
    if let i = v as? Int { return i }
    if let d = v as? Double { return Int(d) }
    if let s = v as? String { return Int(s) }
    return nil
}

/// RFC 3339 dates as Stremio writes them ("2026-10-11T18:25:00.123Z").
enum StremioDate {
    nonisolated(unsafe) private static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f
    }()
    nonisolated(unsafe) private static let plain = ISO8601DateFormatter()
    static func parse(_ s: String?) -> Date? {
        guard let s else { return nil }
        return withFraction.date(from: s) ?? plain.date(from: s) ?? plain.date(from: String(s.prefix(10)) + "T00:00:00Z")
    }
    static func format(_ d: Date) -> String { withFraction.string(from: d) }
}

/// JavaScript's encodeURIComponent, which add-on URLs are built with.
func uriComponent(_ s: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-_.!~*'()")
    return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
}

struct StremioCatalog: Identifiable, Hashable {
    let addonID: String
    let type: String
    let catalogID: String
    let name: String
    let extras: [(name: String, required: Bool)]
    var id: String { "\(addonID)/\(type)/\(catalogID)" }
    var supportsSearch: Bool { extras.contains { $0.name == "search" } }
    var needsInput: Bool { extras.contains { $0.required } }
    var searchOnly: Bool { extras.allSatisfy { !$0.required || $0.name == "search" } }

    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct StremioAddon: Identifiable {
    struct Resource { let name: String; let types: [String]?; let idPrefixes: [String]? }

    let transportURL: String
    let id: String
    let name: String
    let types: [String]
    let idPrefixes: [String]?
    let resources: [Resource]
    let catalogs: [StremioCatalog]
    let adult: Bool
    let configurationRequired: Bool

    init?(descriptor d: JSON) {
        guard let m = d["manifest"] as? JSON, let transport = str(d, "transportUrl") else { return nil }
        transportURL = transport
        id = (m["id"] as? String) ?? transport
        name = str(m, "name") ?? id
        types = strings(m["types"])
        idPrefixes = m["idPrefixes"].map(strings)
        resources = ((m["resources"] as? [Any]) ?? []).compactMap { r in
            if let s = r as? String { return Resource(name: s, types: nil, idPrefixes: nil) }
            if let o = r as? JSON, let n = o["name"] as? String { return Resource(name: n, types: o["types"].map(strings), idPrefixes: o["idPrefixes"].map(strings)) }
            return nil
        }
        let addonID = id
        catalogs = ((m["catalogs"] as? [JSON]) ?? []).map { c in
            var extras: [(String, Bool)] = ((c["extra"] as? [JSON]) ?? []).compactMap { e in
                (e["name"] as? String).map { ($0, (e["isRequired"] as? Bool) ?? false) }
            }
            let required = strings(c["extraRequired"])
            for n in strings(c["extraSupported"]) where !extras.contains(where: { $0.0 == n }) { extras.append((n, required.contains(n))) }
            let cid = (c["id"] as? String) ?? ""
            return StremioCatalog(addonID: addonID, type: (c["type"] as? String) ?? "", catalogID: cid,
                                  name: str(c, "name") ?? cid, extras: extras.map { (name: $0.0, required: $0.1) })
        }
        let hints = m["behaviorHints"] as? JSON
        adult = (hints?["adult"] as? Bool) ?? false
        configurationRequired = (hints?["configurationRequired"] as? Bool) ?? false
    }

    /// The manifest rule Stremio uses: a resource's own types and id prefixes, or else the add-on's.
    func provides(_ resource: String, type: String, id: String) -> Bool {
        guard !configurationRequired, let r = resources.first(where: { $0.name == resource }) else { return false }
        guard (r.types ?? types).contains(type) else { return false }
        let prefixes = r.idPrefixes ?? idPrefixes ?? []
        return prefixes.isEmpty || prefixes.contains { id.hasPrefix($0) }
    }

    func url(_ resource: String, type: String, id: String, extra: [(String, String)] = []) -> URL? {
        var base = transportURL
        if base.hasSuffix("/manifest.json") { base.removeLast("/manifest.json".count) }
        let tail = extra.isEmpty ? "" : "/" + extra.map { uriComponent($0.0) + "=" + uriComponent($0.1) }.joined(separator: "&")
        return URL(string: "\(base)/\(resource)/\(uriComponent(type))/\(uriComponent(id))\(tail).json")
    }
}

struct StremioMeta: Identifiable, Hashable {
    let id: String
    let type: String
    let name: String
    let poster: URL?
    let posterShape: String
    let background: URL?
    let logo: URL?
    let description: String?
    let releaseInfo: String?
    let imdbRating: String?
    let genres: [String]
    let runtime: String?

    init(id: String, type: String, name: String, poster: URL?, posterShape: String = "poster", background: URL? = nil) {
        self.id = id; self.type = type; self.name = name; self.poster = poster; self.posterShape = posterShape
        self.background = background; logo = nil; description = nil; releaseInfo = nil; imdbRating = nil; genres = []; runtime = nil
    }

    init?(_ o: JSON, fallbackType: String? = nil) {
        guard let id = str(o, "id") else { return nil }
        self.id = id
        type = str(o, "type") ?? fallbackType ?? "movie"
        name = str(o, "name") ?? id
        poster = str(o, "poster").flatMap(URL.init(string:))
        posterShape = str(o, "posterShape") ?? "poster"
        background = str(o, "background").flatMap(URL.init(string:))
        logo = str(o, "logo").flatMap(URL.init(string:))
        description = str(o, "description")
        releaseInfo = str(o, "releaseInfo") ?? str(o, "year")
        imdbRating = str(o, "imdbRating")
        genres = strings(o["genres"]).isEmpty ? strings(o["genre"]) : strings(o["genres"])
        runtime = str(o, "runtime")
    }

    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct StremioVideo: Identifiable, Hashable {
    let id: String
    let title: String
    let season: Int?
    let episode: Int?
    let released: Date?
    let thumbnail: URL?
    let overview: String?

    var label: String { if let season, let episode { "S\(season) · E\(episode)" } else { title } }

    init?(_ o: JSON) {
        guard let id = str(o, "id") else { return nil }
        self.id = id
        title = str(o, "title") ?? str(o, "name") ?? id
        season = int(o["season"])
        episode = int(o["episode"]) ?? int(o["number"])
        released = StremioDate.parse(str(o, "released"))
        thumbnail = str(o, "thumbnail").flatMap(URL.init(string:))
        overview = str(o, "overview") ?? str(o, "description")
    }
}

struct StremioMetaDetail {
    let meta: StremioMeta
    let videos: [StremioVideo]

    var seasons: [Int] {
        let s = Array(Set(videos.compactMap(\.season))).sorted()
        return s.count > 1 && s.first == 0 ? Array(s.dropFirst()) + [0] : s
    }
    func episodes(_ season: Int) -> [StremioVideo] { videos.filter { $0.season == season }.sorted { ($0.episode ?? 0) < ($1.episode ?? 0) } }

    init?(_ o: JSON) {
        guard let m = StremioMeta(o) else { return nil }
        meta = m
        videos = ((o["videos"] as? [JSON]) ?? []).compactMap(StremioVideo.init)
    }
}

struct StremioStream: Identifiable {
    let raw: JSON
    let addonName: String
    let name: String?
    let description: String?
    let url: String?
    let infoHash: String?
    let ytID: String?
    let externalURL: String?
    let id = UUID()

    var isTorrent: Bool { infoHash != nil }
    var fileIdx: Int? { int(raw["fileIdx"]) }
    var sources: [String] { strings(raw["sources"] ?? raw["announce"]) }
    private var hints: JSON { (raw["behaviorHints"] as? JSON) ?? [:] }
    var bingeGroup: String? { str(hints, "bingeGroup") }
    var filename: String? { str(hints, "filename") }
    var videoSize: Int64? { int(hints["videoSize"]).map(Int64.init) }
    /// Request headers some add-ons need (only User-Agent and Referer can be passed to VLC).
    var headers: [String: String] { ((hints["proxyHeaders"] as? JSON)?["request"] as? [String: String]) ?? [:] }
    /// What the TV can play: a direct link or a torrent.
    var playable: Bool { url != nil || infoHash != nil }
    var title: String { (name ?? addonName).replacingOccurrences(of: "\n", with: " ") }
    var kind: String {
        if isTorrent { return "Torrent" }
        if url != nil { return "Direct link" }
        if ytID != nil { return "YouTube · not supported" }
        return "Website only"
    }

    init?(_ o: JSON, addonName: String) {
        raw = o
        self.addonName = addonName
        name = str(o, "name")
        description = str(o, "description") ?? str(o, "title")
        url = str(o, "url")
        infoHash = str(o, "infoHash")?.lowercased()
        ytID = str(o, "ytId")
        externalURL = str(o, "externalUrl")
        if url == nil, infoHash == nil, ytID == nil, externalURL == nil { return nil }
    }
}

/// A library entry exactly as Stremio stores it.
struct StremioLibraryItem: Identifiable {
    let raw: JSON
    var id: String { (raw["_id"] as? String) ?? "" }
    var name: String { (raw["name"] as? String) ?? "" }
    var type: String { (raw["type"] as? String) ?? "" }
    var poster: URL? { str(raw, "poster").flatMap(URL.init(string:)) }
    var removed: Bool { (raw["removed"] as? Bool) ?? false }
    var temp: Bool { (raw["temp"] as? Bool) ?? false }
    var mtime: Date { StremioDate.parse(raw["_mtime"] as? String) ?? .distantPast }
    private var state: JSON { (raw["state"] as? JSON) ?? [:] }
    var timeOffset: Double { Double(int(state["timeOffset"]) ?? 0) }
    var duration: Double { Double(int(state["duration"]) ?? 0) }
    var videoID: String? { str(state, "video_id") }
    var progress: Double { duration > 0 ? min(1, max(0, timeOffset / duration)) : 0 }
    var inContinueWatching: Bool { type != "other" && (!removed || temp) && timeOffset > 0 }
    var inLibrary: Bool { !removed && !temp }

    var episodeLabel: String? {
        guard let parts = videoID?.split(separator: ":"), parts.count >= 3,
              let s = Int(parts[parts.count - 2]), let e = Int(parts[parts.count - 1]) else { return nil }
        return "S\(s) · E\(e)"
    }
    var minutesLeft: Int? { duration > 0 ? max(1, Int((duration - timeOffset) / 60_000)) : nil }
    var meta: StremioMeta { StremioMeta(id: id, type: type, name: name, poster: poster) }
}

struct StremioSubtitle: Identifiable, Hashable {
    let id: String
    let lang: String
    let url: URL

    init?(_ o: JSON) {
        guard let u = (o["url"] as? String).flatMap(URL.init(string:)) else { return nil }
        url = u
        id = (o["id"] as? String) ?? (o["id"] as? Int).map(String.init) ?? u.absoluteString
        lang = (o["lang"] as? String) ?? "Unknown"
    }

    /// "eng" / "en" → "English", for menus.
    var languageName: String { Self.languageName(lang) }

    static func languageName(_ code: String) -> String {
        let c = code.trimmingCharacters(in: .whitespaces)
        if (2...3).contains(c.count), let name = Locale(identifier: "en").localizedString(forLanguageCode: c), name.lowercased() != c.lowercased() {
            return name
        }
        return c.capitalized
    }
}

extension StremioLibraryItem {
    /// A new entry for something being watched that isn't in the library yet (Stremio does the same).
    static func new(for meta: StremioMeta) -> StremioLibraryItem {
        let now = StremioDate.format(Date())
        let raw: JSON = [
            "_id": meta.id, "name": meta.name, "type": meta.type,
            "poster": meta.poster?.absoluteString as Any? ?? NSNull(), "posterShape": meta.posterShape,
            "removed": true, "temp": true, "_ctime": now, "_mtime": now,
            "state": ["lastWatched": now, "timeWatched": 0, "timeOffset": 0, "overallTimeWatched": 0, "timesWatched": 0,
                      "flaggedWatched": 0, "duration": 0, "video_id": NSNull(), "watched": NSNull(), "noNotif": false] as JSON,
            "behaviorHints": ["defaultVideoId": NSNull(), "featuredVideoId": NSNull(), "hasScheduledVideos": false] as JSON,
        ]
        return StremioLibraryItem(raw: raw)
    }

    /// A copy with changes to the entry and its watch state; stamps the modification time.
    func updated(_ edit: (inout JSON, inout JSON) -> Void) -> StremioLibraryItem {
        var o = raw
        var st = (o["state"] as? JSON) ?? [:]
        edit(&o, &st)
        o["state"] = st
        o["_mtime"] = StremioDate.format(Date())
        return StremioLibraryItem(raw: o)
    }
}

struct StremioError: LocalizedError {
    let message: String
    var signedOut = false
    var errorDescription: String? { message }
}

/// The Stremio account service and add-ons, used the way Stremio's own apps use them.
enum StremioAPI {
    static var apiBase = "https://api.strem.io/api/"
    static var linkBase = "https://link.stremio.com/api/v2/"

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    // MARK: Sign-in with a code (Stremio's own page handles Facebook, Apple or email)

    static func createLink() async throws -> (code: String, link: URL) {
        let o = try await get(linkBase + "create?type=Create")
        guard let r = o["result"] as? JSON, let code = r["code"] as? String, let link = (r["link"] as? String).flatMap(URL.init(string:)) else {
            throw StremioError(message: "Stremio didn't give a sign-in code. Try again in a moment.")
        }
        return (code, link)
    }

    static func readLink(_ code: String) async -> String? {
        guard let o = try? await get(linkBase + "read?type=Read&code=\(code)") else { return nil }
        return ((o["result"] as? JSON)?["authKey"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    // MARK: Account

    static func email(_ key: String) async throws -> String {
        let r = try await call("getUser", ["type": "GetUser", "authKey": key]) as? JSON
        return (r?["email"] as? String) ?? "your Stremio account"
    }

    static func addons(_ key: String) async throws -> [StremioAddon] {
        let r = try await call("addonCollectionGet", ["type": "AddonCollectionGet", "authKey": key, "update": true]) as? JSON
        return ((r?["addons"] as? [JSON]) ?? []).compactMap(StremioAddon.init(descriptor:))
    }

    static func library(_ key: String) async throws -> [StremioLibraryItem] {
        let r = try await call("datastoreGet", ["authKey": key, "collection": "libraryItem", "ids": [String](), "all": true]) as? [JSON]
        return (r ?? []).filter { $0["_id"] != nil && $0["state"] != nil }.map(StremioLibraryItem.init(raw:))
    }

    static func putLibrary(_ key: String, _ items: [StremioLibraryItem]) async throws {
        guard !items.isEmpty else { return }
        _ = try await call("datastorePut", ["authKey": key, "collection": "libraryItem", "changes": items.map(\.raw)])
    }

    // MARK: Add-ons

    static func subtitles(_ addon: StremioAddon, type: String, videoID: String, extra: [(String, String)]) async throws -> [StremioSubtitle] {
        let o = try await fetch(addon.url("subtitles", type: type, id: videoID, extra: extra))
        return ((o["subtitles"] as? [JSON]) ?? []).compactMap(StremioSubtitle.init)
    }

    static func catalog(_ addon: StremioAddon, _ c: StremioCatalog, search: String? = nil) async throws -> [StremioMeta] {
        let extra = search.map { [("search", $0)] } ?? []
        let o = try await fetch(addon.url("catalog", type: c.type, id: c.catalogID, extra: extra))
        return ((o["metas"] as? [JSON]) ?? []).compactMap { StremioMeta($0, fallbackType: c.type) }
    }

    static func meta(_ addon: StremioAddon, type: String, id: String) async throws -> StremioMetaDetail? {
        let o = try await fetch(addon.url("meta", type: type, id: id))
        return (o["meta"] as? JSON).flatMap(StremioMetaDetail.init)
    }

    static func streams(_ addon: StremioAddon, type: String, videoID: String) async throws -> [StremioStream] {
        let o = try await fetch(addon.url("stream", type: type, id: videoID))
        return ((o["streams"] as? [JSON]) ?? []).compactMap { StremioStream($0, addonName: addon.name) }
    }

    // MARK: Plumbing

    private static func call(_ method: String, _ body: JSON) async throws -> Any? {
        guard let url = URL(string: apiBase + method) else { throw StremioError(message: "Bad Stremio address.") }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let o = try await load(req)
        if let e = o["error"] as? JSON {
            let message = (e["message"] as? String) ?? "error"
            let signedOut = int(e["code"]) == 1 || message.localizedCaseInsensitiveContains("session")
            throw StremioError(message: signedOut ? "Your Stremio sign-in expired. Sign in again." : "Stremio: \(message)", signedOut: signedOut)
        }
        return o["result"]
    }

    private static func get(_ s: String) async throws -> JSON {
        guard let url = URL(string: s) else { throw StremioError(message: "Bad Stremio address.") }
        return try await load(URLRequest(url: url))
    }

    private static func fetch(_ url: URL?) async throws -> JSON {
        guard let url else { throw StremioError(message: "Bad add-on address.") }
        var req = URLRequest(url: url)
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        return try await load(req)
    }

    private static func load(_ req: URLRequest) async throws -> JSON {
        let data: Data
        do { (data, _) = try await session.data(for: req) } catch {
            throw StremioError(message: "Can't reach Stremio. Check your internet connection.")
        }
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON else {
            throw StremioError(message: "Stremio sent an unexpected answer.")
        }
        return o
    }
}
