import Foundation

struct TorrentError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Streams torrent sources on this device, like Stremio's streaming server: downloads the chosen file in order,
/// with the pieces just ahead of the playhead fetched first, and serves it to VLC over a loopback HTTP address that
/// supports seeking. One torrent at a time; its data is deleted when you stop. (The TV app has the same engine.)
final class TorrentStreamer: @unchecked Sendable {
    static let shared = TorrentStreamer()

    struct Opened { let url: URL; let fileName: String; let size: Int64 }
    struct Stats { let hasMetadata: Bool; let peers: Int; let downloadRate: Int; let bufferedAhead: Int64 }

    private final class Active: @unchecked Sendable {
        let hash: String
        let fileIndex: Int
        let relativePath: String
        let size: Int64
        let fileOffset: Int64
        let pieceLength: Int64
        let firstPiece: Int
        let lastPiece: Int
        let window: Int
        var focusPiece = -1
        /// Where the data is now; low-storage mode starts over in a fresh folder each time it frees space.
        var dir: URL
        var generation = 0
        /// Low-storage mode: how much may be on disk before what's been watched is deleted (nil = keep everything).
        let budget: Int64?
        /// First piece of the current folder's download (low-storage mode).
        var generationStart: Int
        let restartLock = NSLock()
        var file: URL { dir.appendingPathComponent(relativePath) }

        init(hash: String, fileIndex: Int, relativePath: String, dir: URL, size: Int64, fileOffset: Int64, pieceLength: Int64, budget: Int64?) {
            self.hash = hash; self.fileIndex = fileIndex; self.relativePath = relativePath; self.dir = dir
            self.size = size; self.fileOffset = fileOffset; self.pieceLength = pieceLength; self.budget = budget
            firstPiece = Int(fileOffset / pieceLength)
            lastPiece = Int((fileOffset + size - 1) / pieceLength)
            // A longer look-ahead in low-storage mode, since nothing beyond it is downloaded.
            let ahead: Int64 = budget.map { min(160 * 1024 * 1024, $0 / 3) } ?? 48 * 1024 * 1024
            window = min(budget == nil ? 120 : 400, max(6, Int(ahead / pieceLength)))
            generationStart = firstPiece
        }
        func piece(at pos: Int64) -> Int { Int((fileOffset + pos) / pieceLength) }
    }

    private static let extraTrackers = [
        "udp://tracker.opentrackr.org:1337/announce", "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce", "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce", "udp://tracker.openbittorrent.com:6969/announce",
    ]
    private static let videoExtensions: Set<String> = ["mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "mpg", "mpeg", "flv"]

    private let engine = ATTorrentEngine.shared
    private let lock = NSLock()
    private var current: Active?
    private var pendingHash: String?
    private var serverSocket: Int32 = -1
    private var port: UInt16 = 0
    private let root: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Antenna/Torrents", isDirectory: true)
        try? FileManager.default.removeItem(at: dir) // whatever an earlier run left behind
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private var active: Active? { lock.lock(); defer { lock.unlock() }; return current }

    // MARK: Open / stop

    func open(infoHash: String, fileIdx: Int?, sources: [String], season: Int?, episode: Int?,
              step: @escaping @Sendable (String) -> Void) async throws -> Opened {
        stop()
        let hash = infoHash.lowercased()
        let trackers = sources.filter { $0.hasPrefix("tracker:") }.map { String($0.dropFirst("tracker:".count)) } + Self.extraTrackers
        var seen = Set<String>()
        let magnet = "magnet:?xt=urn:btih:\(hash)" + trackers.filter { seen.insert($0).inserted }.map { "&tr=" + uriComponent($0) }.joined()
        let dir = root.appendingPathComponent(hash, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        do { try engine.addMagnet(magnet, hash: hash, savePath: dir.path) } catch {
            throw TorrentError(message: "This source's link is broken. Try another one.")
        }
        lock.lock(); pendingHash = hash; lock.unlock()

        step("Finding people sharing this file")
        var waited = 0
        while engine.status(forHash: hash)?.hasMetadata != true {
            lock.lock(); let stillWanted = pendingHash == hash; lock.unlock()
            if !stillWanted { throw TorrentError(message: "Stopped.") }
            if waited >= 45_000 {
                remove(hash, dir: dir)
                throw TorrentError(message: "No one is sharing this file right now. Try another source.")
            }
            try await Task.sleep(for: .milliseconds(200)); waited += 200
        }
        let files = engine.files(forHash: hash)
        guard let file = pick(files, fileIdx: fileIdx, season: season, episode: episode) else {
            remove(hash, dir: dir)
            throw TorrentError(message: "This torrent doesn't contain a video. Try another source.")
        }
        engine.selectFile(file.index, hash: hash)
        let free = (try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? Int64.max
        // Bigger than the free space: stream it in low-storage mode, deleting what's been watched as it goes.
        let margin: Int64 = 1_000_000_000
        var budget: Int64?
        #if DEBUG
        // Test runs: -AntennaDevTorrentBudgetMB 40 forces low-storage mode with a small budget.
        let forced = UserDefaults.standard.integer(forKey: "AntennaDevTorrentBudgetMB")
        if forced > 0 { budget = Int64(forced) * 1_048_576 }
        #endif
        if budget == nil, file.size > free - margin {
            guard free - margin >= 600_000_000 else {
                remove(hash, dir: dir)
                throw TorrentError(message: "There's only \(gb(free)) free, not enough to stream this. Free up some space and try again.")
            }
            budget = free - margin
        }
        let a = Active(hash: hash, fileIndex: Int(file.index), relativePath: file.path, dir: dir, size: file.size,
                       fileOffset: file.offset, pieceLength: Int64(engine.pieceLength(forHash: hash)), budget: budget)
        if budget != nil { engine.keepOnlyPieces(Int32(a.firstPiece), count: Int32(a.window), hash: hash) }
        lock.lock(); pendingHash = nil; current = a; lock.unlock()
        // Players read the start first, and MKV and MP4 files often keep their index at the very end.
        focus(a, a.firstPiece, force: true)
        for p in max(a.firstPiece, a.lastPiece - 1)...a.lastPiece { engine.setDeadline(1500, piece: Int32(p), hash: hash) }
        let port = try startServer()
        let url = URL(string: "http://127.0.0.1:\(port)/\(hash)/\(file.index)/\(uriComponent(file.name))")!
        return Opened(url: url, fileName: file.name, size: file.size)
    }

    func stats() -> Stats? {
        lock.lock(); let hash = current?.hash ?? pendingHash; let a = current; lock.unlock()
        guard let hash, let s = engine.status(forHash: hash) else { return nil }
        return Stats(hasMetadata: s.hasMetadata, peers: Int(s.peers), downloadRate: Int(s.downloadRate), bufferedAhead: a.map(bufferedAhead) ?? 0)
    }

    /// Stops the torrent and deletes its data.
    func stop() {
        lock.lock()
        let pending = pendingHash, a = current
        pendingHash = nil; current = nil
        lock.unlock()
        if let pending { remove(pending, dir: root.appendingPathComponent(pending)) }
        if let a { remove(a.hash, dir: root.appendingPathComponent(a.hash)) }
    }

    /// True when the file is bigger than the free space and is being streamed in low-storage mode.
    var isLowStorage: Bool { active?.budget != nil }

    private func remove(_ hash: String, dir: URL) {
        engine.removeHash(hash)
        DispatchQueue.global().asyncAfter(deadline: .now() + 3) { [weak self] in
            if self?.active?.hash != hash { try? FileManager.default.removeItem(at: dir) }
        }
    }

    /// Low-storage mode: once the folder holds about the budget, start over in a fresh one from the playhead.
    /// This deletes what's been watched; the look-ahead is fetched again, so playback pauses for a moment.
    private func freeSpaceIfNeeded(_ a: Active, at piece: Int) {
        // Counted from what's actually on disk, plus room for the look-ahead about to be fetched.
        func full() -> Bool {
            guard let budget = a.budget, let done = engine.status(forHash: a.hash)?.totalDone else { return false }
            return done + Int64(a.window) * a.pieceLength > budget
        }
        guard full() else { return }
        a.restartLock.lock(); defer { a.restartLock.unlock() }
        guard full(), active === a else { return }
        let old = a.dir
        let fresh = root.appendingPathComponent(a.hash, isDirectory: true).appendingPathComponent("part\(a.generation + 1)", isDirectory: true)
        try? FileManager.default.createDirectory(at: fresh, withIntermediateDirectories: true)
        guard engine.restartHash(a.hash, savePath: fresh.path, file: Int32(a.fileIndex), first: Int32(piece), count: Int32(a.window)) else { return }
        a.dir = fresh
        a.generation += 1
        a.generationStart = piece
        a.focusPiece = -1
        if old.lastPathComponent.hasPrefix("part") { try? FileManager.default.removeItem(at: old) }
    }

    private func pick(_ files: [ATTorrentFile], fileIdx: Int?, season: Int?, episode: Int?) -> ATTorrentFile? {
        if let fileIdx, let f = files.first(where: { Int($0.index) == fileIdx }) { return f }
        let videos = files.filter { Self.videoExtensions.contains(($0.name as NSString).pathExtension.lowercased()) }
        if let season, let episode {
            let patterns = ["(?i)s0*\(season)[ ._-]*e0*\(episode)(?!\\d)", "(?i)(?<!\\d)0*\(season)x0*\(episode)(?!\\d)"]
            let matching = videos.filter { f in patterns.contains { f.name.range(of: $0, options: .regularExpression) != nil } }
            if let best = matching.max(by: { $0.size < $1.size }) { return best }
        }
        return videos.max(by: { $0.size < $1.size })
    }

    /// Asks for the pieces just ahead of `piece` first, most urgent nearest the playhead.
    private func focus(_ a: Active, _ piece: Int, force: Bool = false) {
        if !force && piece == a.focusPiece { return }
        freeSpaceIfNeeded(a, at: piece)
        let jumped = a.focusPiece < 0 || piece < a.focusPiece - 2 || piece > a.focusPiece + a.window
        a.focusPiece = piece
        if jumped { engine.clearDeadlines(forHash: a.hash) }
        for k in 0..<a.window {
            let p = piece + k
            if p > a.lastPiece { break }
            if !engine.havePiece(Int32(p), hash: a.hash) {
                // In low-storage mode nothing outside the look-ahead is wanted, so ask for these explicitly.
                if a.budget != nil { engine.setTopPriorityPiece(Int32(p), hash: a.hash) }
                engine.setDeadline(Int32(250 + k * 150), piece: Int32(p), hash: a.hash)
            }
        }
    }

    private func bufferedAhead(_ a: Active) -> Int64 {
        let start = a.focusPiece >= 0 ? a.focusPiece : a.firstPiece
        var p = start
        while p <= a.lastPiece, p < start + 400, engine.havePiece(Int32(p), hash: a.hash) { p += 1 }
        return Int64(p - start) * a.pieceLength
    }

    private func gb(_ bytes: Int64) -> String { String(format: "%.1f GB", Double(bytes) / 1_073_741_824) }

    // MARK: Loopback HTTP server VLC reads from

    private func startServer() throws -> UInt16 {
        lock.lock(); defer { lock.unlock() }
        if serverSocket >= 0 { return port }
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw TorrentError(message: "Couldn't start the local player connection.") }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = 0
        let bound = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        guard bound == 0, listen(fd, 16) == 0 else { close(fd); throw TorrentError(message: "Couldn't start the local player connection.") }
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) } }
        serverSocket = fd
        port = UInt16(bigEndian: addr.sin_port)
        let t = Thread { [weak self] in
            while true {
                let client = accept(fd, nil, nil)
                if client < 0 { break }
                var noSigPipe: Int32 = 1
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout<Int32>.size))
                let worker = Thread { self?.serve(client); close(client) }
                worker.start()
            }
        }
        t.name = "torrent-http"
        t.start()
        return port
    }

    private func serve(_ fd: Int32) {
        var request = Data()
        var buf = [UInt8](repeating: 0, count: 4096)
        while request.count < 16_384 {
            let n = recv(fd, &buf, buf.count, 0)
            if n <= 0 { return }
            request.append(buf, count: n)
            if request.range(of: Data("\r\n\r\n".utf8)) != nil { break }
        }
        let text = String(decoding: request, as: UTF8.self)
        let lines = text.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 2 else { return }
        let method = String(parts[0])
        let segments = parts[1].split(separator: "/")
        let range = lines.first { $0.lowercased().hasPrefix("range:") }.map { $0.dropFirst(6).trimmingCharacters(in: .whitespaces) }
        guard let a = active, segments.count >= 2, segments[0] == a.hash else {
            write(fd, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); return
        }
        var start: Int64 = 0
        var end = a.size - 1
        let partial = range?.hasPrefix("bytes=") == true
        if let range, partial {
            let spec = range.dropFirst(6).split(separator: ",").first.map(String.init) ?? ""
            let bits = spec.split(separator: "-", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
            if bits.first?.isEmpty == true, bits.count > 1, let suffix = Int64(bits[1]) {
                start = max(0, a.size - suffix)
            } else {
                start = Int64(bits.first ?? "0") ?? 0
                if bits.count > 1, let to = Int64(bits[1]) { end = min(to, a.size - 1) }
            }
            if start >= a.size {
                write(fd, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */\(a.size)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            }
        }
        let type: String = switch a.file.pathExtension.lowercased() {
        case "mp4", "m4v": "video/mp4"
        case "webm": "video/webm"
        case "avi": "video/x-msvideo"
        case "ts", "m2ts": "video/mp2t"
        case "mov": "video/quicktime"
        default: "video/x-matroska"
        }
        var header = partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n"
        header += "Content-Type: \(type)\r\nAccept-Ranges: bytes\r\nContent-Length: \(end - start + 1)\r\n"
        if partial { header += "Content-Range: bytes \(start)-\(end)/\(a.size)\r\n" }
        header += "Connection: close\r\n\r\n"
        guard write(fd, header), method != "HEAD" else { return }
        stream(a, from: start, to: end, fd: fd)
    }

    private func stream(_ a: Active, from start: Int64, to end: Int64, fd: Int32) {
        var pos = start
        var handle: FileHandle?
        var generation = a.generation
        defer { try? handle?.close() }
        while pos <= end {
            guard active === a else { return }
            let piece = a.piece(at: pos)
            focus(a, piece)
            // Low-storage mode moved the data to a fresh folder: read from the new file.
            if a.generation != generation { try? handle?.close(); handle = nil; generation = a.generation }
            var waited = 0
            while !engine.havePiece(Int32(piece), hash: a.hash) {
                guard active === a, waited < 120_000 else { return }
                Thread.sleep(forTimeInterval: 0.04); waited += 40
            }
            let pieceEnd = Int64(piece + 1) * a.pieceLength - a.fileOffset
            let count = Int(min(256 * 1024, pieceEnd - pos, end - pos + 1))
            if handle == nil { handle = try? FileHandle(forReadingFrom: a.file) }
            guard let h = handle else { Thread.sleep(forTimeInterval: 0.04); continue }
            try? h.seek(toOffset: UInt64(pos))
            guard let data = try? h.read(upToCount: count), !data.isEmpty else { Thread.sleep(forTimeInterval: 0.04); continue }
            let ok = data.withUnsafeBytes { raw -> Bool in
                var sent = 0
                while sent < raw.count {
                    let n = send(fd, raw.baseAddress!.advanced(by: sent), raw.count - sent, 0)
                    if n <= 0 { return false }
                    sent += n
                }
                return true
            }
            if !ok { return }
            pos += Int64(data.count)
        }
    }

    @discardableResult
    private func write(_ fd: Int32, _ s: String) -> Bool {
        let bytes = Array(s.utf8)
        return bytes.withUnsafeBytes { send(fd, $0.baseAddress!, $0.count, 0) } == bytes.count
    }
}
