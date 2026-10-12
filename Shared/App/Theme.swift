import SwiftUI

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

/// Palette shared with the design mockup.
enum Theme {
    static let bg = Color(hex: 0x0B0E13)
    static let surface = Color(hex: 0x11151C)
    static let surface2 = Color(hex: 0x181D26)
    static let surface3 = Color(hex: 0x212835)
    static let line = Color(hex: 0x96AACD, opacity: 0.11)
    static let lineStrong = Color(hex: 0x96AACD, opacity: 0.22)
    static let fg = Color(hex: 0xE9EDF3)
    static let muted = Color(hex: 0x8C96A8)
    static let faint = Color(hex: 0x5F6879)
    static let accent = Color(hex: 0xF2B544)
    static let accentSoft = Color(hex: 0xF2B544, opacity: 0.14)
    static let accentInk = Color(hex: 0x1B1406)
    static let live = Color(hex: 0xFF5151)
    static let ok = Color(hex: 0x46C28A)
    static let sidebarTop = Color(hex: 0x121722)
    static let sidebarBottom = Color(hex: 0x0E1219)
}

extension String {
    /// FNV-1a, stable across launches (unlike `hashValue`). Used to give each channel its own tint.
    var stableHash: UInt32 {
        var h: UInt32 = 2_166_136_261
        for b in utf8 { h ^= UInt32(b); h = h &* 16_777_619 }
        return h
    }
}

extension Date {
    var clock: String { formatted(date: .omitted, time: .shortened) }
}

/// Small uppercase section label used across views.
struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10.5, weight: .semibold))
            .tracking(0.8)
            .foregroundStyle(Theme.faint)
    }
}

extension View {
    /// The app draws its own flat chrome, so skip the system blur at the top of full-height scroll views.
    @ViewBuilder func flatScrollEdges() -> some View {
        if #available(macOS 26.0, iOS 26.0, *) { scrollEdgeEffectHidden(true, for: .all) } else { self }
    }
}
