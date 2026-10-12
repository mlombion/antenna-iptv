import Foundation
import Security

/// The password lives in the Keychain; everything else about the account is in UserDefaults.
enum Keychain {
    private static let service = "com.malick.antenna"

    static func set(_ value: String, account: String) throws {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    static func get(account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: account,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func delete(account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}

struct SavedAccount: Codable, Equatable {
    var name: String
    var server: String
    var username: String

    var keychainKey: String { "\(server)|\(username)" }

    private static let key = "account"

    static func load() -> SavedAccount? {
        guard let d = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(SavedAccount.self, from: d)
    }
    func save() { UserDefaults.standard.set(try? JSONEncoder().encode(self), forKey: Self.key) }
    static func clear() { UserDefaults.standard.removeObject(forKey: key) }
}

enum LineupCache {
    private static var url: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "Antenna", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appending(path: "lineup.json")
    }

    static func load() -> Lineup? {
        guard let d = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Lineup.self, from: d)
    }

    static func save(_ lineup: Lineup) {
        let target = url
        Task.detached(priority: .utility) {
            if let d = try? JSONEncoder().encode(lineup) { try? d.write(to: target, options: .atomic) }
        }
    }

    static func clear() { try? FileManager.default.removeItem(at: url) }
}

enum BufferSize: Int, CaseIterable, Identifiable {
    case low = 1000, normal = 3000, high = 8000
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .low: "Low · 1 s"
        case .normal: "Normal · 3 s"
        case .high: "High · 8 s"
        }
    }
}

enum StartSection: String, CaseIterable, Identifiable {
    case home, live, lastChannel
    var id: String { rawValue }
    var label: String {
        switch self {
        case .home: "Show Home"
        case .live: "Show Live TV"
        case .lastChannel: "Play last channel"
        }
    }
}

@MainActor @Observable
final class AppSettings {
    @ObservationIgnored private let d = UserDefaults.standard

    var format: StreamFormat { didSet { d.set(format.rawValue, forKey: "format") } }
    var buffer: BufferSize { didSet { d.set(buffer.rawValue, forKey: "buffer") } }
    var hardwareDecoding: Bool { didSet { d.set(hardwareDecoding, forKey: "hardwareDecoding") } }
    var autoReconnect: Bool { didSet { d.set(autoReconnect, forKey: "autoReconnect") } }
    var hideAdult: Bool { didSet { d.set(hideAdult, forKey: "hideAdult") } }
    var startSection: StartSection { didSet { d.set(startSection.rawValue, forKey: "startSection") } }
    /// Minutes of the current channel kept on disk for rewinding. 0 = off.
    var rewindMinutes: Int { didSet { d.set(rewindMinutes, forKey: "rewindMinutes") } }

    init() {
        format = StreamFormat(rawValue: d.string(forKey: "format") ?? "") ?? .ts
        buffer = BufferSize(rawValue: d.integer(forKey: "buffer")) ?? .normal
        hardwareDecoding = d.object(forKey: "hardwareDecoding") as? Bool ?? true
        autoReconnect = d.object(forKey: "autoReconnect") as? Bool ?? true
        hideAdult = d.object(forKey: "hideAdult") as? Bool ?? true
        startSection = StartSection(rawValue: d.string(forKey: "startSection") ?? "") ?? .live
        rewindMinutes = d.object(forKey: "rewindMinutes") as? Int ?? 30
    }
}

/// Caps concurrent guide requests so scrolling a long list doesn't flood the provider.
actor AsyncLimiter {
    private var available: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ limit: Int) { available = limit }

    func acquire() async {
        if available > 0 { available -= 1; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        if waiters.isEmpty { available += 1 } else { waiters.removeFirst().resume() }
    }
}
