import Foundation

/// Rolling on-disk recording of a live MPEG-TS stream, kept as ~2 second segment files that start on a
/// TS packet boundary, so the same recording can be read as one continuous stream (VLC) or as HLS segments
/// (Apple's player, for picture in picture on iPhone).
/// Byte offsets are global (they keep counting as old segments are dropped), and arrival-time marks map
/// between a moment in the broadcast and a byte offset. Not thread-safe: the relay uses it from one queue.
final class TimeshiftBuffer {
    struct HLSSegment {
        let index: Int
        let startTime: Date
        let duration: TimeInterval
        let discontinuity: Bool
    }

    private struct Segment {
        let index: Int
        let url: URL
        let start: Int64
        var end: Int64
        let startTime: Date
        var endTime: Date
        let discontinuity: Bool
    }

    private struct Mark {
        let offset: Int64
        let time: Date
    }

    static let segmentSeconds: TimeInterval = 2

    static var rootDirectory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appending(path: "Antenna/Timeshift", directoryHint: .isDirectory)
    }

    /// Leftovers from a session that didn't shut down cleanly.
    static func removeLeftovers() {
        try? FileManager.default.removeItem(at: rootDirectory)
    }

    private let dir: URL
    private let maxAge: TimeInterval
    private var segments: [Segment] = []
    private var marks: [Mark] = []
    private var writer: FileHandle?
    private var readers: [URL: FileHandle] = [:]
    private var segmentCount = 0
    /// Where the current provider connection's bytes began; packet boundaries are counted from here.
    private var alignBase: Int64 = 0
    private var forceRotate = false
    private var pendingDiscontinuity = false
    private(set) var endOffset: Int64 = 0

    var startOffset: Int64 { segments.first?.start ?? 0 }
    var startTime: Date? { marks.first?.time }
    var endTime: Date? { marks.last?.time }

    init(maxAge: TimeInterval) throws {
        self.maxAge = maxAge
        dir = Self.rootDirectory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// Call when the provider connection is re-established: the next bytes start a fresh, unrelated stream.
    func markDiscontinuity() {
        alignBase = endOffset
        forceRotate = true
        pendingDiscontinuity = true
    }

    func append(_ data: Data, at time: Date = Date()) {
        guard !data.isEmpty else { return }
        var rest = data
        let due = segments.last.map { time.timeIntervalSince($0.startTime) >= Self.segmentSeconds } ?? true
        if writer == nil || forceRotate || due {
            if writer != nil, !forceRotate {
                // Finish the current TS packet in this segment so the next one starts on a packet boundary.
                let into = Int((endOffset - alignBase) % 188)
                if into != 0 {
                    let head = 188 - into
                    if rest.count <= head {
                        write(rest, at: time)
                        return
                    }
                    write(rest.prefix(head), at: time)
                    rest = rest.dropFirst(head)
                }
            }
            forceRotate = false
            rotate(at: time)
        }
        write(rest, at: time)
        trim(now: time)
    }

    /// Up to `maxLength` bytes starting at `offset` (moved forward if that part was already dropped).
    func read(from offset: Int64, maxLength: Int) -> (data: Data, offset: Int64)? {
        let o = max(offset, startOffset)
        guard o < endOffset, let seg = segments.first(where: { $0.start <= o && o < $0.end }) else { return nil }
        guard let handle = reader(for: seg.url) else { return nil }
        let length = Int(min(Int64(maxLength), seg.end - o))
        do {
            try handle.seek(toOffset: UInt64(o - seg.start))
            guard let data = try handle.read(upToCount: length), !data.isEmpty else { return nil }
            return (data, o)
        } catch {
            return nil
        }
    }

    /// Byte offset of the broadcast at `time`, snapped to a 188-byte TS packet.
    func offset(for time: Date) -> Int64 {
        guard let i = marks.lastIndex(where: { $0.time <= time }) else { return startOffset }
        let o = marks[i].offset
        return max(startOffset, o - (o - alignBase(for: o)) % 188)
    }

    func time(for offset: Int64) -> Date {
        marks.first(where: { $0.offset >= offset })?.time ?? endTime ?? Date()
    }

    /// Finished segments, oldest first. The one still being written is left out.
    var completedSegments: [HLSSegment] {
        segments.dropLast().map {
            HLSSegment(index: $0.index, startTime: $0.startTime,
                       duration: max(0.1, $0.endTime.timeIntervalSince($0.startTime)), discontinuity: $0.discontinuity)
        }
    }

    func segmentData(index: Int) -> Data? {
        guard let seg = segments.dropLast().first(where: { $0.index == index }) else { return nil }
        return try? Data(contentsOf: seg.url)
    }

    func close() {
        try? writer?.close()
        readers.values.forEach { try? $0.close() }
        readers = [:]
        writer = nil
        try? FileManager.default.removeItem(at: dir)
    }

    private func write(_ data: Data, at time: Date) {
        guard !data.isEmpty, !segments.isEmpty else { return }
        if marks.isEmpty { marks.append(Mark(offset: endOffset, time: time)) }
        try? writer?.write(contentsOf: data)
        endOffset += Int64(data.count)
        segments[segments.count - 1].end = endOffset
        segments[segments.count - 1].endTime = time
        if let last = marks.last, time.timeIntervalSince(last.time) >= 0.25 {
            marks.append(Mark(offset: endOffset, time: time))
        } else if let last = marks.last, marks.count > 1 {
            // Keep the newest mark current so the live edge is exact.
            marks[marks.count - 1] = Mark(offset: endOffset, time: max(last.time, time))
        }
    }

    private func rotate(at time: Date) {
        try? writer?.close()
        segmentCount += 1
        let url = dir.appending(path: "segment-\(segmentCount).ts")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        writer = try? FileHandle(forWritingTo: url)
        segments.append(Segment(index: segmentCount, url: url, start: endOffset, end: endOffset,
                                startTime: time, endTime: time, discontinuity: pendingDiscontinuity))
        pendingDiscontinuity = false
    }

    private func alignBase(for offset: Int64) -> Int64 {
        offset >= alignBase ? alignBase : 0
    }

    private func trim(now: Date) {
        while segments.count > 2, now.timeIntervalSince(segments[1].startTime) > maxAge {
            let old = segments.removeFirst()
            if let r = readers.removeValue(forKey: old.url) { try? r.close() }
            try? FileManager.default.removeItem(at: old.url)
            let start = startOffset
            if let keep = marks.firstIndex(where: { $0.offset >= start }), keep > 0 { marks.removeFirst(keep) }
        }
    }

    private func reader(for url: URL) -> FileHandle? {
        if let r = readers[url] { return r }
        let r = try? FileHandle(forReadingFrom: url)
        readers[url] = r
        return r
    }
}
