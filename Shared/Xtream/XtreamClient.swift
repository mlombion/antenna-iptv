import Foundation

enum XtreamError: LocalizedError, Equatable {
    case badURL
    case unreachable
    case badCredentials
    case inactive(String)
    case badResponse
    case http(Int)

    var errorDescription: String? {
        switch self {
        case .badURL: "That server URL doesn't look right. It usually looks like http://example.com:8080."
        case .unreachable: "Can't reach the server. Check the URL and your internet connection."
        case .badCredentials: "The server rejected that username or password."
        case .inactive(let status): "Your subscription is \(status.lowercased()). Contact your provider to renew it."
        case .badResponse: "The server answered, but not like an IPTV server. Check the URL."
        case .http(let code): "The server returned an error (HTTP \(code)). Try again in a moment."
        }
    }
}

enum StreamFormat: String, CaseIterable, Codable, Identifiable {
    case ts, m3u8
    var id: String { rawValue }
    var label: String {
        switch self {
        case .ts: "MPEG-TS (.ts)"
        case .m3u8: "HLS (.m3u8)"
        }
    }
}

/// Talks to an Xtream Codes `player_api.php` endpoint.
struct XtreamClient: Sendable {
    let server: URL
    let username: String
    let password: String

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 180
        c.httpMaximumConnectionsPerHost = 6
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        return URLSession(configuration: c)
    }()

    /// Accepts what people paste: "host:port", "http://host:port/", or a full get.php / player_api.php link.
    static func normalize(_ raw: String) -> URL? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if !s.contains("://") { s = "http://" + s }
        guard var comps = URLComponents(string: s), let host = comps.host, !host.isEmpty,
              comps.scheme == "http" || comps.scheme == "https" else { return nil }
        comps.path = ""
        comps.query = nil
        comps.fragment = nil
        comps.user = nil
        comps.password = nil
        return comps.url
    }

    // MARK: Requests

    func authenticate() async throws -> XtreamAccountInfo {
        let data = try await fetch(apiURL(action: nil), allowAuthFailure: true)
        let trimmed = String(decoding: data.prefix(8), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.hasPrefix("[") { throw XtreamError.badCredentials }
        guard let env = try? JSONDecoder().decode(RawAuthEnvelope.self, from: data) else { throw XtreamError.badResponse }
        guard let u = env.user_info else { throw XtreamError.badCredentials }
        guard u.auth else { throw XtreamError.badCredentials }
        let info = XtreamAccountInfo(status: u.status, expiry: u.expiry, maxConnections: u.maxConnections,
                                     activeConnections: u.activeConnections, isTrial: u.isTrial, formats: u.formats)
        guard info.isActive else { throw XtreamError.inactive(u.status) }
        return info
    }

    func liveCategories() async throws -> [LiveCategory] {
        let data = try await fetch(apiURL(action: "get_live_categories"))
        guard let raw = try? JSONDecoder().decode([Lossy<RawCategory>].self, from: data) else { throw XtreamError.badResponse }
        return raw.compactMap(\.value).map { LiveCategory(id: $0.id, name: $0.name) }
    }

    func liveStreams() async throws -> [Channel] {
        let data = try await fetch(apiURL(action: "get_live_streams"))
        guard let raw = try? JSONDecoder().decode([Lossy<RawStream>].self, from: data) else { throw XtreamError.badResponse }
        var seen = Set<Int>()
        return raw.compactMap(\.value).enumerated().compactMap { index, s in
            guard seen.insert(s.streamID).inserted else { return nil }
            return Channel(stream: s, fallbackNumber: index + 1)
        }
    }

    func shortEPG(streamID: Int, limit: Int = 14) async throws -> [Programme] {
        let url = apiURL(action: "get_short_epg", extra: [
            URLQueryItem(name: "stream_id", value: String(streamID)),
            URLQueryItem(name: "limit", value: String(limit)),
        ])
        let data = try await fetch(url)
        guard let env = try? JSONDecoder().decode(RawEPGEnvelope.self, from: data) else { return [] }
        return (env.epg_listings ?? []).compactMap(\.value)
            .map { Programme(title: $0.title, summary: $0.summary, start: $0.start, end: $0.end) }
            .sorted { $0.start < $1.start }
    }

    /// The provider's full programme guide for every channel.
    var xmltvURL: URL {
        var comps = URLComponents(url: server.appending(path: "xmltv.php"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "username", value: username), URLQueryItem(name: "password", value: password)]
        return comps.url!
    }

    func streamURL(for channel: Channel, format: StreamFormat) -> URL {
        server.appending(path: "live")
            .appending(path: username)
            .appending(path: password)
            .appending(path: "\(channel.id).\(format.rawValue)")
    }

    // MARK: Plumbing

    private func apiURL(action: String?, extra: [URLQueryItem] = []) -> URL {
        var comps = URLComponents(url: server.appending(path: "player_api.php"), resolvingAgainstBaseURL: false)!
        var items = [URLQueryItem(name: "username", value: username), URLQueryItem(name: "password", value: password)]
        if let action { items.append(URLQueryItem(name: "action", value: action)) }
        comps.queryItems = items + extra
        return comps.url!
    }

    private func fetch(_ url: URL, allowAuthFailure: Bool = false) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await Self.session.data(from: url)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw XtreamError.unreachable
        }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            if http.statusCode == 401 || http.statusCode == 403 { throw XtreamError.badCredentials }
            throw XtreamError.http(http.statusCode)
        }
        return data
    }
}
