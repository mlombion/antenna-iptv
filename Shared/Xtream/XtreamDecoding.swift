import Foundation

// Xtream Codes panels are loose with types: the same field can arrive as "12", 12 or 12.0,
// and one malformed item must not throw away a list of thousands. These helpers absorb that.

extension KeyedDecodingContainer {
    func flexString(_ key: Key) -> String? {
        if let s = try? decodeIfPresent(String.self, forKey: key) { return s }
        if let i = try? decodeIfPresent(Int.self, forKey: key) { return String(i) }
        if let d = try? decodeIfPresent(Double.self, forKey: key) { return String(Int(d)) }
        return nil
    }

    func flexInt(_ key: Key) -> Int? {
        if let i = try? decodeIfPresent(Int.self, forKey: key) { return i }
        if let s = try? decodeIfPresent(String.self, forKey: key) {
            return Int(s.trimmingCharacters(in: .whitespaces)) ?? Double(s).map { Int($0) }
        }
        if let d = try? decodeIfPresent(Double.self, forKey: key) { return Int(d) }
        if let b = try? decodeIfPresent(Bool.self, forKey: key) { return b ? 1 : 0 }
        return nil
    }
}

/// Decodes `T` if it can, otherwise keeps `nil` so the surrounding array survives.
struct Lossy<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

struct AnyKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ s: String) { stringValue = s }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

// MARK: - Raw payloads

struct RawUserInfo: Decodable {
    let auth: Bool
    let status: String
    let expiry: Date?
    let maxConnections: Int?
    let activeConnections: Int?
    let isTrial: Bool
    let formats: [String]
    let message: String?

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        auth = (c.flexInt(AnyKey("auth")) ?? 0) == 1
        status = c.flexString(AnyKey("status")) ?? "Unknown"
        expiry = c.flexInt(AnyKey("exp_date")).flatMap { $0 > 0 ? Date(timeIntervalSince1970: TimeInterval($0)) : nil }
        maxConnections = c.flexInt(AnyKey("max_connections"))
        activeConnections = c.flexInt(AnyKey("active_cons"))
        isTrial = (c.flexInt(AnyKey("is_trial")) ?? 0) == 1
        formats = (try? c.decodeIfPresent([String].self, forKey: AnyKey("allowed_output_formats"))) ?? []
        message = c.flexString(AnyKey("message"))
    }
}

struct RawAuthEnvelope: Decodable {
    let user_info: RawUserInfo?
}

struct RawCategory: Decodable {
    let id: String
    let name: String
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        guard let id = c.flexString(AnyKey("category_id")) else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "missing category_id"))
        }
        self.id = id
        name = c.flexString(AnyKey("category_name")) ?? "Untitled"
    }
}

struct RawStream: Decodable {
    let streamID: Int
    let num: Int?
    let name: String
    let icon: String?
    let categoryID: String?
    let epgID: String?
    let archive: Bool

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        guard let id = c.flexInt(AnyKey("stream_id")) else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "missing stream_id"))
        }
        streamID = id
        num = c.flexInt(AnyKey("num"))
        name = c.flexString(AnyKey("name")) ?? "Channel \(id)"
        icon = c.flexString(AnyKey("stream_icon"))
        categoryID = c.flexString(AnyKey("category_id"))
            ?? (try? c.decodeIfPresent([Int].self, forKey: AnyKey("category_ids")))?.first.map(String.init)
        epgID = c.flexString(AnyKey("epg_channel_id"))
        archive = (c.flexInt(AnyKey("tv_archive")) ?? 0) == 1
    }
}

struct RawEPGEnvelope: Decodable {
    let epg_listings: [Lossy<RawListing>]?
}

struct RawListing: Decodable {
    let title: String
    let summary: String
    let start: Date
    let end: Date

    private static let local: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        title = Self.unbase64(c.flexString(AnyKey("title")) ?? "")
        summary = Self.unbase64(c.flexString(AnyKey("description")) ?? "")
        let s = c.flexInt(AnyKey("start_timestamp")).map { Date(timeIntervalSince1970: TimeInterval($0)) }
            ?? c.flexString(AnyKey("start")).flatMap { Self.local.date(from: $0) }
        let e = c.flexInt(AnyKey("stop_timestamp")).map { Date(timeIntervalSince1970: TimeInterval($0)) }
            ?? c.flexString(AnyKey("end")).flatMap { Self.local.date(from: $0) }
            ?? c.flexString(AnyKey("stop")).flatMap { Self.local.date(from: $0) }
        guard let s, let e, e > s else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "bad times"))
        }
        start = s
        end = e
    }

    /// Short EPG titles and descriptions are base64 on most panels, plain text on a few.
    static func unbase64(_ s: String) -> String {
        guard !s.isEmpty, let d = Data(base64Encoded: s), let t = String(data: d, encoding: .utf8) else { return s }
        return t
    }
}
