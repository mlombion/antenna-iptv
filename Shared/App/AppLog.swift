import Foundation

/// Small diagnostic log at ~/Library/Logs/Antenna IPTV/antenna.log, so playback problems on a real provider
/// can be explained afterwards. Usernames and passwords in stream URLs are masked before anything is written.
enum AppLog {
    static let fileURL: URL = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        .appending(path: "Logs/Antenna IPTV/antenna.log")

    private static let queue = DispatchQueue(label: "antenna.log", qos: .utility)
    private static let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func write(_ message: String) {
        let line = "\(formatter.string(from: Date())) \(redact(message))\n"
        queue.async {
            let fm = FileManager.default
            try? fm.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            if let size = (try? fm.attributesOfItem(atPath: fileURL.path))?[.size] as? Int, size > 1_000_000 {
                try? fm.removeItem(at: fileURL)
            }
            if !fm.fileExists(atPath: fileURL.path) { fm.createFile(atPath: fileURL.path, contents: nil) }
            guard let h = try? FileHandle(forWritingTo: fileURL) else { return }
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: Data(line.utf8))
            try? h.close()
        }
    }

    static func redact(_ s: String) -> String {
        var out = s.replacingOccurrences(of: #"/(live|timeshift|movie|series)/[^/\s]+/[^/\s]+/"#, with: "/$1/•••/•••/",
                                         options: .regularExpression)
        out = out.replacingOccurrences(of: #"(username|password)=[^&\s]+"#, with: "$1=•••", options: .regularExpression)
        return out
    }
}
