import SwiftUI

struct LiveBadge: View {
    @State private var dim = false
    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(.white).frame(width: 6, height: 6).opacity(dim ? 0.35 : 1)
            Text("LIVE").font(.system(size: 10, weight: .bold)).tracking(0.8)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 6).padding(.vertical, 3.5)
        .background(Theme.live, in: RoundedRectangle(cornerRadius: 4))
        .onAppear {
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { dim = true }
        }
    }
}

struct QualityBadge: View {
    let quality: String?
    var body: some View {
        if let quality {
            Text(quality)
                .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(quality == "4K" ? Theme.accent : Theme.muted)
                .padding(.horizontal, 4).padding(.vertical, 2)
                .overlay(RoundedRectangle(cornerRadius: 4)
                    .strokeBorder(quality == "4K" ? Theme.accent.opacity(0.45) : Theme.lineStrong))
        }
    }
}

struct ProgressLine: View {
    let value: Double
    var height: CGFloat = 3
    var tint: Color = Theme.accent
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.1))
                Capsule().fill(tint).frame(width: max(0, g.size.width * value))
            }
        }
        .frame(height: height)
    }
}

/// The provider's logo when it has one, otherwise a tinted monogram.
struct ChannelLogo: View {
    let channel: Channel
    var width: CGFloat = 40
    var height: CGFloat = 30

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: height * 0.2)
                .fill(Color(hue: channel.hue, saturation: 0.38, brightness: 0.22))
            if let url = channel.iconURL {
                AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.15))) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFit().padding(height * 0.12)
                    } else {
                        monogram
                    }
                }
            } else {
                monogram
            }
        }
        .frame(width: width, height: height)
        .overlay(RoundedRectangle(cornerRadius: height * 0.2)
            .strokeBorder(Color(hue: channel.hue, saturation: 0.5, brightness: 0.4).opacity(0.5)))
    }

    private var monogram: some View {
        Text(channel.monogram)
            .font(.system(size: height * 0.36, weight: .heavy))
            .tracking(-0.3)
            .foregroundStyle(Color(hue: channel.hue, saturation: 0.35, brightness: 0.95))
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .padding(.horizontal, 3)
    }
}

/// Soft two-colour wash in the channel's hue; stands in for artwork, which live channels don't have.
struct ChannelBackdrop: View {
    let channel: Channel
    var body: some View {
        ZStack {
            Color(hue: channel.hue, saturation: 0.35, brightness: 0.1)
            RadialGradient(colors: [Color(hue: channel.hue, saturation: 0.65, brightness: 0.45).opacity(0.9), .clear],
                           center: UnitPoint(x: 0.2, y: 0.3), startRadius: 0, endRadius: 360)
            RadialGradient(colors: [Color(hue: (channel.hue + 0.11).truncatingRemainder(dividingBy: 1),
                                          saturation: 0.7, brightness: 0.38).opacity(0.8), .clear],
                           center: UnitPoint(x: 0.85, y: 0.85), startRadius: 0, endRadius: 300)
        }
    }
}

struct StarButton: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    var size: CGFloat = 15

    var body: some View {
        let on = model.isFavorite(channel)
        Button { model.toggleFavorite(channel) } label: {
            Image(systemName: on ? "star.fill" : "star")
                .font(.system(size: size))
                .foregroundStyle(on ? Theme.accent : Theme.faint)
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(on ? "Remove from Favorites" : "Add to Favorites")
    }
}

struct PillButtonStyle: ButtonStyle {
    var prominent = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 14)
            .frame(height: 34)
            .foregroundStyle(prominent ? Theme.accentInk : Theme.fg)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(prominent ? Theme.accent : Theme.surface3)
                    .brightness(configuration.isPressed ? -0.08 : 0)
            )
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(prominent ? .clear : Theme.line))
    }
}

struct ToastView: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(Color(hex: 0x11151C))
            .padding(.horizontal, 16).padding(.vertical, 9)
            .background(Color(hex: 0xF1F3F7), in: RoundedRectangle(cornerRadius: 9))
            .shadow(color: .black.opacity(0.5), radius: 15, y: 8)
    }
}

struct EmptyState: View {
    let symbol: String
    let title: String
    let message: String
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 30)).foregroundStyle(Theme.faint)
            Text(title).font(.system(size: 15, weight: .semibold))
            Text(message).foregroundStyle(Theme.muted).multilineTextAlignment(.center).frame(maxWidth: 320)
        }
        .padding(40)
        .frame(maxWidth: .infinity)
    }
}

/// Live channel card used on Home and Favorites.
struct ChannelCard: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    var list: [Channel]?
    @State private var hover = false

    var body: some View {
        let sched = model.schedule(for: channel)
        Button { model.play(channel, in: list) } label: {
            VStack(alignment: .leading, spacing: 8) {
                ZStack {
                    ChannelBackdrop(channel: channel)
                    ChannelLogo(channel: channel, width: 84, height: 60)
                        .shadow(color: .black.opacity(0.4), radius: 10, y: 4)
                    VStack {
                        HStack { LiveBadge(); Spacer(); QualityBadge(quality: channel.quality) }
                        Spacer()
                        if let cur = sched.current {
                            ProgressLine(value: cur.progress(at: model.now), height: 3)
                        }
                    }
                    .padding(8)
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(hover ? Theme.accent : Theme.line, lineWidth: hover ? 2 : 1))
                .offset(y: hover ? -2 : 0)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(channel.displayName).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                        Text("\(channel.num)").font(.system(size: 11.5, design: .monospaced)).foregroundStyle(Theme.faint)
                    }
                    Text(sched.current?.title ?? (sched.isLoading ? " " : "Live"))
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                        .lineLimit(1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(.easeOut(duration: 0.15)) { hover = h } }
        .onAppear { model.requestEPG(channel) }
        .contextMenu {
            Button(model.isFavorite(channel) ? "Remove from Favorites" : "Add to Favorites") { model.toggleFavorite(channel) }
            Button("Show in Live TV") { model.reveal(channel) }
        }
    }
}

struct BrandMark: View {
    var size: CGFloat = 17
    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: size + 2, weight: .semibold))
                .foregroundStyle(Theme.accent)
            (Text("Antenna ") + Text("IPTV").foregroundStyle(Theme.accent))
                .font(.system(size: size, weight: .semibold)).tracking(-0.2)
        }
    }
}

struct Avatar: View {
    let name: String
    var size: CGFloat = 30
    var body: some View {
        Text(String(name.prefix(1)).uppercased())
            .font(.system(size: size * 0.4, weight: .bold))
            .foregroundStyle(Theme.accentInk)
            .frame(width: size, height: size)
            .background(LinearGradient(colors: [Theme.accent, Color(hex: 0xE0703A)], startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: Circle())
    }
}
