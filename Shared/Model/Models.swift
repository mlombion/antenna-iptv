import Foundation

struct XtreamAccountInfo: Codable, Hashable {
    var status: String
    var expiry: Date?
    var maxConnections: Int?
    var activeConnections: Int?
    var isTrial: Bool
    var formats: [String]

    var isActive: Bool { status.lowercased() == "active" }
}

struct LiveCategory: Codable, Identifiable, Hashable {
    static let allID = "__all"
    static let favoritesID = "__fav"

    let id: String
    let name: String

    var isAdult: Bool {
        let n = name.lowercased()
        return ["adult", "xxx", "18+", "+18", "porn"].contains { n.contains($0) }
    }
}

struct Channel: Codable, Identifiable, Hashable {
    let id: Int
    let num: Int
    let name: String
    let displayName: String
    let quality: String?
    let iconURL: URL?
    let categoryID: String
    let epgID: String?
    let hasArchive: Bool

    static func == (a: Channel, b: Channel) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }

    var hue: Double { Double(name.stableHash % 360) / 360 }

    var monogram: String {
        let words = displayName.split(whereSeparator: { $0 == " " || $0 == "-" })
        if displayName.count <= 5 { return displayName.uppercased() }
        var s = ""
        for w in words {
            if w.allSatisfy(\.isNumber) { s += w } else if let f = w.first { s += String(f).uppercased() + (w.contains("+") ? "+" : "") }
        }
        return String(s.prefix(4))
    }

    func matches(_ query: String) -> Bool {
        displayName.localizedCaseInsensitiveContains(query) || String(num) == query
    }
}

extension Channel {
    init(stream s: RawStream, fallbackNumber: Int) {
        let (clean, q) = Channel.clean(s.name)
        self.init(id: s.streamID,
                  num: s.num ?? fallbackNumber,
                  name: s.name,
                  displayName: clean,
                  quality: q,
                  iconURL: s.icon.flatMap { $0.hasPrefix("http") ? URL(string: $0) : nil },
                  categoryID: s.categoryID ?? "",
                  epgID: s.epgID.flatMap { $0.isEmpty ? nil : $0 },
                  hasArchive: s.archive)
    }

    /// "FR| TF1 FHD" -> ("TF1", "FHD"). Provider names carry country prefixes and quality tags.
    static func clean(_ raw: String) -> (String, String?) {
        var n = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = n.range(of: #"^\|?\s*[A-Z]{2,4}\s*[|:]\|?\s*"#, options: .regularExpression) {
            n.removeSubrange(r)
        }
        var quality: String?
        let tags: [(String, String?)] = [
            (#"\b(4K|UHD|2160P?)\b"#, "4K"), (#"\b(FHD|1080P?)\b"#, "FHD"), (#"\b(HD|720P?)\b"#, "HD"),
            (#"\bSD\b"#, "SD"), (#"\b(HEVC|H265|H\.265)\b"#, nil),
        ]
        for (pattern, label) in tags {
            if let r = n.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
                if quality == nil, let label { quality = label }
                n.removeSubrange(r)
            }
        }
        n = n.replacingOccurrences(of: #"[\[\(]\s*[\]\)]"#, with: "", options: .regularExpression)
        n = n.replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression)
        n = n.trimmingCharacters(in: CharacterSet(charactersIn: " |-:•"))
        return (n.isEmpty ? raw : n, quality)
    }
}

struct Programme: Codable, Hashable, Identifiable {
    let title: String
    let summary: String
    let start: Date
    let end: Date

    var id: Double { start.timeIntervalSince1970 }
    var minutes: Int { Int(end.timeIntervalSince(start) / 60) }

    func progress(at now: Date) -> Double {
        guard end > start else { return 0 }
        return min(1, max(0, now.timeIntervalSince(start) / end.timeIntervalSince(start)))
    }
    func isOn(at now: Date) -> Bool { start <= now && now < end }
}

enum EPGState: Equatable {
    case loading
    case loaded([Programme])
    case unavailable
    case failed
}

/// What a channel is showing now and later, derived from its short EPG.
struct Schedule {
    var current: Programme?
    var upcoming: [Programme] = []
    var state: EPGState?

    var isLoading: Bool { state == nil || state == .loading }
}

/// Everything the app needs to show the channel list; cached on disk so the app opens instantly.
struct Lineup: Codable {
    var account: XtreamAccountInfo
    var categories: [LiveCategory]
    var channels: [Channel]
    var savedAt: Date
}
