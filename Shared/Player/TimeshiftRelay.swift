import Foundation
import Network

/// Pulls the provider's live MPEG-TS stream into a `TimeshiftBuffer` and serves it to VLC over a loopback-only
/// HTTP server, starting from any buffered position. The provider sees one connection however often you rewind.
final class TimeshiftRelay: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    enum Upstream: Equatable {
        case connecting, streaming
        case failed(String)
    }

    struct Snapshot {
        var upstream: Upstream
        var startTime: Date?
        var endTime: Date?
        /// Finished HLS segments; Apple's player needs a few before it can start.
        var segmentCount = 0
    }

    private let source: URL
    private let queue = DispatchQueue(label: "antenna.timeshift")
    private let buffer: TimeshiftBuffer
    private var session: URLSession?
    private var listener: NWListener?
    private var client: NWConnection?
    private var clientGeneration = 0
    private var cursor: Int64 = 0
    private var upstream: Upstream = .connecting
    private var receivedAny = false
    private var reconnects = 0
    /// Providers that allow one connection refuse a new channel for a moment while the previous one closes.
    private var initialAttempts = 0
    private var lastError: String?
    private var stopped = false
    private var port: UInt16 = 0

    init(source: URL, maxAge: TimeInterval) throws {
        self.source = source
        buffer = try TimeshiftBuffer(maxAge: maxAge)
        super.init()
    }

    /// Starts the local server and the download. `ready` runs on the main actor once VLC can connect.
    func start(ready: @escaping @MainActor (Bool) -> Void) {
        let params = NWParameters.tcp
        params.requiredInterfaceType = .loopback
        guard let listener = try? NWListener(using: params, on: .any) else {
            Task { @MainActor in ready(false) }
            return
        }
        self.listener = listener
        var reported = false
        listener.stateUpdateHandler = { [weak self] state in
            guard let self, !reported else { return }
            switch state {
            case .ready:
                reported = true
                self.port = listener.port?.rawValue ?? 0
                self.startUpstream()
                Task { @MainActor in ready(true) }
            case .failed, .cancelled:
                reported = true
                Task { @MainActor in ready(false) }
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
        listener.start(queue: queue)
    }

    /// Where VLC should read from: a buffered byte offset, or the live edge when `nil`.
    func localURL(from offset: Int64?) -> URL {
        let from = offset.map(String.init) ?? "live"
        return URL(string: "http://127.0.0.1:\(port)/stream.ts?from=\(from)&n=\(UUID().uuidString.prefix(8))")!
    }

    /// The same recording as an HLS playlist, for Apple's player (picture in picture on iPhone).
    var hlsURL: URL { URL(string: "http://127.0.0.1:\(port)/live.m3u8")! }

    func snapshot() -> Snapshot {
        queue.sync {
            Snapshot(upstream: upstream, startTime: buffer.startTime, endTime: buffer.endTime,
                     segmentCount: buffer.completedSegments.count)
        }
    }

    func offset(for time: Date) -> Int64 { queue.sync { buffer.offset(for: time) } }
    func time(for offset: Int64) -> Date { queue.sync { buffer.time(for: offset) } }

    /// `waitUntilDone` is for app shutdown, when there's no later moment to delete the recording.
    func stop(waitUntilDone: Bool = false) {
        let work = { [self] in
            stopped = true
            session?.invalidateAndCancel()
            session = nil
            client?.cancel()
            client = nil
            listener?.cancel()
            listener = nil
            buffer.close()
        }
        if waitUntilDone { queue.sync(execute: work) } else { queue.async(execute: work) }
    }

    // MARK: Provider side

    private func startUpstream() {
        guard !stopped else { return }
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        let ops = OperationQueue()
        ops.maxConcurrentOperationCount = 1
        ops.underlyingQueue = queue
        session?.invalidateAndCancel()
        session = URLSession(configuration: config, delegate: self, delegateQueue: ops)
        var request = URLRequest(url: source)
        // The same agent VLC sends, which providers already accept.
        request.setValue("VLC/3.0.21 LibVLC/3.0.21", forHTTPHeaderField: "User-Agent")
        AppLog.write("relay: requesting \(source.absoluteString)")
        session?.dataTask(with: request).resume()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            lastError = "The provider refused the stream (HTTP \(http.statusCode))."
            AppLog.write("relay: HTTP \(http.statusCode) from \(http.url?.host() ?? "?")")
            completionHandler(.cancel)
            return
        }
        AppLog.write("relay: connected, HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0), \(response.mimeType ?? "no type")")
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !stopped, session === self.session else { return }
        buffer.append(data)
        receivedAny = true
        upstream = .streaming
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !stopped, session === self.session else { return }
        if let error, lastError == nil { lastError = error.localizedDescription }
        if receivedAny {
            // A live stream never ends on its own, so any completion is a drop: reconnect and keep recording.
            guard reconnects < 5 else {
                upstream = .failed(lastError ?? "The stream stopped.")
                AppLog.write("relay: gave up after \(reconnects) reconnects")
                return
            }
            reconnects += 1
            upstream = .connecting
            buffer.markDiscontinuity()
            AppLog.write("relay: stream dropped (\(lastError ?? "ended")), reconnect \(reconnects)")
            lastError = nil
            queue.asyncAfter(deadline: .now() + Double(reconnects)) { [weak self] in self?.startUpstream() }
        } else if initialAttempts < 5 {
            let delays = [0.5, 1.0, 1.5, 2.0, 3.0]
            let delay = delays[initialAttempts]
            initialAttempts += 1
            upstream = .connecting
            AppLog.write("relay: no video yet (\(lastError ?? "closed")), retry \(initialAttempts) in \(delay)s")
            lastError = nil
            queue.asyncAfter(deadline: .now() + delay) { [weak self] in self?.startUpstream() }
        } else {
            upstream = .failed(lastError ?? "The channel didn't send any video.")
            AppLog.write("relay: failed after retries: \(lastError ?? "no video")")
        }
    }

    // MARK: VLC side

    private func accept(_ conn: NWConnection) {
        conn.start(queue: queue)
        conn.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, _, error in
            guard let self, error == nil, let data, let request = String(data: data, encoding: .utf8),
                  let target = request.split(separator: " ").dropFirst().first,
                  let comps = URLComponents(string: String(target)) else {
                conn.cancel()
                return
            }
            switch comps.path {
            case "/stream.ts":
                let from = comps.queryItems?.first { $0.name == "from" }?.value
                self.startStream(conn, from: from.flatMap { Int64($0) })
            case "/live.m3u8":
                self.respond(conn, type: "application/vnd.apple.mpegurl", body: Data(self.playlist().utf8))
            default:
                if comps.path.hasPrefix("/seg-"), comps.path.hasSuffix(".ts"),
                   let index = Int(comps.path.dropFirst(5).dropLast(3)), let body = self.buffer.segmentData(index: index) {
                    self.respond(conn, type: "video/mp2t", body: body)
                } else {
                    self.respond(conn, status: "404 Not Found", type: "text/plain", body: Data())
                }
            }
        }
    }

    /// One continuous stream for VLC. A new request replaces the previous one (a seek reopens the stream).
    private func startStream(_ conn: NWConnection, from offset: Int64?) {
        client?.cancel()
        clientGeneration += 1
        let generation = clientGeneration
        client = conn
        cursor = offset ?? buffer.endOffset
        let header = "HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nAccept-Ranges: none\r\nConnection: close\r\n\r\n"
        conn.send(content: Data(header.utf8), completion: .contentProcessed { [weak self] err in
            if err != nil { conn.cancel(); return }
            self?.pump(generation)
        })
    }

    private func respond(_ conn: NWConnection, status: String = "200 OK", type: String, body: Data) {
        let header = "HTTP/1.1 \(status)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        conn.send(content: Data(header.utf8) + body, completion: .contentProcessed { _ in conn.cancel() })
    }

    private static let isoDate: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    /// Live HLS playlist of the finished segments. Program dates let the player seek by wall-clock time,
    /// so it can pick up exactly where VLC was.
    private func playlist() -> String {
        let segs = buffer.completedSegments
        let target = max(3, Int((segs.map(\.duration).max() ?? 2).rounded(.up)))
        var s = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:\(target)\n#EXT-X-MEDIA-SEQUENCE:\(segs.first?.index ?? 0)\n"
        for seg in segs {
            if seg.discontinuity { s += "#EXT-X-DISCONTINUITY\n" }
            s += "#EXT-X-PROGRAM-DATE-TIME:\(Self.isoDate.string(from: seg.startTime))\n"
            s += "#EXTINF:\(String(format: "%.3f", seg.duration)),\nseg-\(seg.index).ts\n"
        }
        return s
    }

    /// Streams buffered bytes to VLC, waiting at the live edge for more to arrive.
    private func pump(_ generation: Int) {
        guard !stopped, generation == clientGeneration, let conn = client else { return }
        if let chunk = buffer.read(from: cursor, maxLength: 256 * 1024) {
            cursor = chunk.offset + Int64(chunk.data.count)
            conn.send(content: chunk.data, completion: .contentProcessed { [weak self] err in
                if err != nil { conn.cancel(); return }
                self?.pump(generation)
            })
        } else if case .failed = upstream {
            conn.cancel()
        } else {
            queue.asyncAfter(deadline: .now() + 0.04) { [weak self] in self?.pump(generation) }
        }
    }
}
