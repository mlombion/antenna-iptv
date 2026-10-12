import Foundation

/// Today's programmes for every channel, from the provider's full XMLTV guide (`xmltv.php`).
/// The short guide the lists use only loads channel by channel as you scroll; this one covers everything,
/// so searching "Formula 1" can find the channel showing it.
struct GuideIndex: Codable {
    /// Programmes keyed by the guide's channel id (the `epg_channel_id` of each stream), lowercased.
    var programmes: [String: [Programme]]
    var builtAt: Date

    /// Downloads and reads the whole guide, keeping only `from`…`to`.
    static func download(_ client: XtreamClient, from: Date, to: Date) async throws -> GuideIndex {
        let url = client.xmltvURL
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 600
        let session = URLSession(configuration: config)
        var request = URLRequest(url: url)
        request.setValue("VLC/3.0.21 LibVLC/3.0.21", forHTTPHeaderField: "User-Agent")
        let (file, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: file) }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw XtreamError.http(http.statusCode)
        }
        return try await Task.detached(priority: .utility) {
            let reader = XMLTVReader(from: from, to: to)
            guard let parser = XMLParser(contentsOf: file) else { throw XtreamError.badResponse }
            parser.delegate = reader
            parser.parse()
            guard !reader.result.isEmpty else { throw XtreamError.badResponse }
            return GuideIndex(programmes: reader.result.mapValues { $0.sorted { $0.start < $1.start } }, builtAt: Date())
        }.value
    }
}

/// Streams through an XMLTV file (they can be tens of megabytes) keeping only programmes inside the window.
private final class XMLTVReader: NSObject, XMLParserDelegate {
    private let from: TimeInterval
    private let to: TimeInterval
    var result: [String: [Programme]] = [:]

    private var channel: String?
    private var start: TimeInterval = 0
    private var stop: TimeInterval = 0
    private var inWindow = false
    private var field: String?
    private var title = ""
    private var desc = ""

    init(from: Date, to: Date) {
        self.from = from.timeIntervalSince1970
        self.to = to.timeIntervalSince1970
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        switch name {
        case "programme":
            channel = attributes["channel"]?.lowercased()
            start = Self.time(attributes["start"]) ?? 0
            stop = Self.time(attributes["stop"]) ?? 0
            inWindow = channel != nil && stop > from && start < to && stop > start
            title = ""
            desc = ""
        case "title", "desc":
            if inWindow { field = name }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard inWindow, let field else { return }
        if field == "title" { title += string } else if desc.count < 400 { desc += string }
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        switch name {
        case "title", "desc":
            field = nil
        case "programme":
            if inWindow, let channel, !title.isEmpty {
                result[channel, default: []].append(Programme(
                    title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                    summary: desc.trimmingCharacters(in: .whitespacesAndNewlines),
                    start: Date(timeIntervalSince1970: start), end: Date(timeIntervalSince1970: stop)))
            }
            inWindow = false
        default:
            break
        }
    }

    /// "20261011093000 +0200" without a DateFormatter, which would be slow over hundreds of thousands of entries.
    static func time(_ s: String?) -> TimeInterval? {
        guard let s else { return nil }
        let b = Array(s.utf8)
        guard b.count >= 14 else { return nil }
        func num(_ i: Int, _ n: Int) -> Int {
            var v = 0
            for k in i..<(i + n) { v = v * 10 + Int(b[k]) - 48 }
            return v
        }
        let y = num(0, 4), m = num(4, 2), d = num(6, 2), h = num(8, 2), mi = num(10, 2), se = num(12, 2)
        // Days from civil date (Howard Hinnant's algorithm).
        let yy = m <= 2 ? y - 1 : y
        let era = (yy >= 0 ? yy : yy - 399) / 400
        let yoe = yy - era * 400
        let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        let days = era * 146_097 + doe - 719_468
        var t = TimeInterval(days * 86_400 + h * 3600 + mi * 60 + se)
        // Optional " +hhmm" offset.
        if let plus = b.firstIndex(where: { $0 == 43 || $0 == 45 }), plus >= 14, b.count >= plus + 5 {
            let sign: TimeInterval = b[plus] == 45 ? -1 : 1
            let oh = num(plus + 1, 2), om = num(plus + 3, 2)
            t -= sign * TimeInterval(oh * 3600 + om * 60)
        }
        return t
    }
}

/// Matching that forgives accents, capitals, French/English spellings and number words:
/// "Formula One" finds "Formule 1 : Grand Prix", "F1" finds both.
enum ShowMatcher {
    private static let numberWords: [String: String] = [
        "one": "1", "un": "1", "une": "1", "two": "2", "deux": "2", "three": "3", "trois": "3",
        "four": "4", "quatre": "4", "five": "5", "cinq": "5",
    ]
    private static let synonyms: [String: [[String]]] = [
        "f1": [["formula", "1"], ["formule", "1"]],
    ]

    static func tokens(_ s: String) -> [String] {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .map { numberWords[String($0)] ?? String($0) }
    }

    /// Alternative spellings of the query (each a list of tokens that must all match).
    static func queries(_ query: String) -> [[String]] {
        let q = tokens(query)
        guard !q.isEmpty else { return [] }
        var all = [q]
        if q.count == 1, let syn = synonyms[q[0]] { all += syn }
        return all
    }

    static func matches(_ queries: [[String]], _ titleTokens: [String]) -> Bool {
        queries.contains { q in q.allSatisfy { token in titleTokens.contains { word(token, $0) } } }
    }

    private static func word(_ q: String, _ t: String) -> Bool {
        if t == q { return true }
        if q.count >= 3 && t.hasPrefix(q) { return true }
        // formula ~ formule, olympic ~ olympique: same first five letters.
        if q.count >= 5 && t.count >= 5 && q.prefix(5) == t.prefix(5) { return true }
        return false
    }
}
