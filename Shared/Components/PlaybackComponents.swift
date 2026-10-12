import SwiftUI

struct StepList: View {
    let steps: [AppModel.Step]
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(steps) { step in
                HStack(spacing: 10) {
                    Group {
                        switch step.state {
                        case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.ok)
                        case .active: ProgressView().controlSize(.mini)
                        case .waiting: Image(systemName: "circle").foregroundStyle(Theme.lineStrong)
                        }
                    }
                    .frame(width: 16, height: 16)
                    Text(step.title).foregroundStyle(step.state == .waiting ? Theme.muted : Theme.fg)
                }
            }
        }
    }
}

/// "−1:24" in place of the LIVE badge while watching behind live.
struct BehindBadge: View {
    let seconds: TimeInterval
    var body: some View {
        Text("−" + RewindBar.format(seconds))
            .font(.system(size: 10.5, weight: .bold, design: .monospaced))
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(.white.opacity(0.22), in: RoundedRectangle(cornerRadius: 4))
            .help("Behind live")
    }
}

/// The recorded window of the current channel. Click or drag anywhere to jump there.
struct RewindBar: View {
    let timeline: PlayerController.Timeline
    let live: Bool
    let seek: (Date) -> Void
    @State private var dragFraction: Double?
    @State private var hover = false

    static func format(_ t: TimeInterval) -> String {
        let s = Int(t.rounded())
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    var body: some View {
        // At live the dot sits at the end, even though playback trails the edge by the player's buffer.
        let fraction = dragFraction ?? (live ? 1 : timeline.playhead.timeIntervalSince(timeline.start) / timeline.length)
        HStack(spacing: 12) {
            Text("−" + Self.format(timeline.length))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.white.opacity(0.55))
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.18)).frame(height: hover ? 6 : 4)
                    Capsule().fill(live ? Theme.live : Theme.accent)
                        .frame(width: max(4, g.size.width * fraction), height: hover ? 6 : 4)
                    Circle().fill(.white)
                        .frame(width: hover || dragFraction != nil ? 14 : 10)
                        .offset(x: max(0, g.size.width * fraction - 7))
                        .shadow(color: .black.opacity(0.4), radius: 3)
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in dragFraction = min(1, max(0, v.location.x / g.size.width)) }
                    .onEnded { v in
                        let f = min(1, max(0, v.location.x / g.size.width))
                        dragFraction = nil
                        seek(timeline.start.addingTimeInterval(f * timeline.length))
                    })
            }
            .frame(height: 18)
            .onHover { hover = $0 }
            Text(live ? "LIVE" : "−" + Self.format(timeline.behind))
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(live ? Theme.live : .white)
                .frame(minWidth: 48, alignment: .trailing)
        }
        .frame(maxWidth: 820)
        .animation(.easeOut(duration: 0.12), value: hover)
    }
}
