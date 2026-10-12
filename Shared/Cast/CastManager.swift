import Foundation
import Network
import Observation

/// "Antenna Connect": finds Antenna TV apps on the home network (Bonjour `_antenna._tcp`) and sends them
/// channels to play. The TV plays the stream itself, straight from the provider; this device becomes the remote.
@MainActor @Observable
final class CastManager {
    struct Device: Identifiable, Hashable {
        let name: String
        let endpoint: NWEndpoint
        var id: String { name }

        /// "Antenna · Living room" → "Living room".
        var displayName: String {
            name.hasPrefix("Antenna · ") ? String(name.dropFirst("Antenna · ".count)) : name
        }
    }

    struct Status: Decodable, Equatable {
        struct Playing: Decodable, Equatable {
            let streamId: Int
            let num: Int
            let name: String
            let title: String?
        }
        /// The TV's rewind window, in milliseconds since 1970 (as the TV's clock sees it).
        struct Timeline: Decodable, Equatable {
            let startMs: Double
            let endMs: Double
            let playheadMs: Double
            let behindMs: Double
            let live: Bool
            var lengthMs: Double { max(1, endMs - startMs) }
        }
        /// A movie or episode from Movies & Series playing on the TV.
        struct Vod: Decodable, Equatable {
            struct Track: Decodable, Equatable, Identifiable {
                let key: String
                let label: String
                let on: Bool
                var id: String { key }
            }
            let id: String
            let type: String
            let title: String
            let subtitle: String?
            let poster: String?
            let state: String
            let step: String?
            let error: String?
            let positionMs: Double
            let durationMs: Double
            let hasNext: Bool
            let subtitles: [Track]
            let audio: [Track]
        }
        let name: String
        let signedIn: Bool
        let server: String?
        let username: String?
        let state: String
        let playing: Playing?
        let timeline: Timeline?
        let vod: Vod?
        /// Whether the TV is signed in to Stremio (older TV apps don't say).
        let stremio: Bool?
    }

    struct CastError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private(set) var devices: [Device] = []
    private(set) var isBrowsing = false
    /// The TV this device is controlling, once a channel has been sent to it.
    private(set) var active: Device?
    private(set) var status: Status?
    private(set) var isSending = false

    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var baseURL: URL?
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 30
        return URLSession(configuration: c)
    }()

    // MARK: Discovery

    func startBrowsing() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjour(type: "_antenna._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            var found = results.compactMap { r -> Device? in
                if case .service(let name, _, _, _) = r.endpoint { return Device(name: name, endpoint: r.endpoint) }
                return nil
            }.sorted { $0.name < $1.name }
            #if DEBUG
            // Test runs only see the test TV (-AntennaDevTVFilter Emulator), never the real ones at home.
            if let only = UserDefaults.standard.string(forKey: "AntennaDevTVFilter") { found = found.filter { $0.name.contains(only) } }
            #endif
            Task { @MainActor in self?.devices = found }
        }
        b.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                if case .failed = state { self?.isBrowsing = false }
            }
        }
        b.start(queue: .main)
        browser = b
        isBrowsing = true
    }

    func stopBrowsing() {
        browser?.cancel()
        browser = nil
        isBrowsing = false
    }

    // MARK: Commands

    /// Sends a channel to `device`. The account goes along so a TV that isn't signed in (or uses another
    /// subscription) can sign in with the same one.
    func play(streamId: Int, on device: Device, account: [String: String]?) async throws {
        isSending = true
        defer { isSending = false }
        let base = try await resolve(device)
        var body: [String: Any] = ["streamId": streamId]
        if let account { body["account"] = account }
        let (data, response) = try await post(base.appending(path: "play"), body)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw CastError(message: message ?? "The TV didn't accept the channel (\(code)).")
        }
        active = device
        startPolling()
    }

    /// Sends a movie or episode (and the source to play) to `device`.
    func playStremio(_ body: [String: Any], on device: Device) async throws {
        isSending = true
        defer { isSending = false }
        let base = try await resolve(device)
        let (data, response) = try await post(base.appending(path: "play"), ["stremio": body])
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw CastError(message: message ?? (code == 404 ? "Update Antenna on the TV to play movies and series." : "The TV didn't accept it (\(code))."))
        }
        active = device
        startPolling()
    }

    func command(_ action: String, _ extra: [String: Any] = [:]) {
        guard let baseURL else { return }
        var body = extra
        body["action"] = action
        Task {
            _ = try? await post(baseURL.appending(path: "command"), body)
            // Show the effect straight away instead of waiting for the next poll.
            try? await Task.sleep(for: .milliseconds(400))
            await refreshStatus()
        }
    }

    /// Stops playback on the TV and lets go of it.
    func disconnect(stopTV: Bool) {
        if stopTV { command("stop") }
        pollTask?.cancel()
        active = nil
        status = nil
    }

    // MARK: Plumbing

    private func post(_ url: URL, _ body: [String: Any]) async throws -> (Data, URLResponse) {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        do {
            return try await session.data(for: req)
        } catch {
            throw CastError(message: "Couldn't reach the TV. Make sure it's on and on the same Wi-Fi.")
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshStatus()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    func refreshStatus() async {
        guard let baseURL else { return }
        guard let (data, _) = try? await session.data(from: baseURL.appending(path: "status")),
              let s = try? JSONDecoder().decode(Status.self, from: data) else { return }
        if s != status { status = s }
    }

    /// Turns the Bonjour service into an IPv4 address and port the HTTP requests can use.
    private func resolve(_ device: Device) async throws -> URL {
        if active == device, let baseURL { return baseURL }
        let params = NWParameters.tcp
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let connection = NWConnection(to: device.endpoint, using: params)
        let url: URL = try await withCheckedThrowingContinuation { cont in
            var finished = false
            connection.stateUpdateHandler = { state in
                guard !finished else { return }
                switch state {
                case .ready:
                    finished = true
                    if case .hostPort(let host, let port)? = connection.currentPath?.remoteEndpoint {
                        let h = "\(host)".split(separator: "%").first.map(String.init) ?? "\(host)"
                        cont.resume(returning: URL(string: "http://\(h):\(port.rawValue)/")!)
                    } else {
                        cont.resume(throwing: CastError(message: "Couldn't find the TV's address."))
                    }
                    connection.cancel()
                case .failed, .waiting:
                    finished = true
                    connection.cancel()
                    cont.resume(throwing: CastError(message: "Couldn't reach the TV. Make sure Antenna is installed on it and it's on the same Wi-Fi."))
                default:
                    break
                }
            }
            connection.start(queue: .main)
        }
        baseURL = url
        return url
    }
}
