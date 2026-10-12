import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 6) {
                if let featured {
                    Hero(channel: featured)
                        .padding(.horizontal, 24)
                        .padding(.top, 40)
                }
                Shelf(title: "Recently watched", channels: model.recentChannels, seeAll: nil)
                Shelf(title: "Your favorites", channels: model.favoriteChannels, seeAll: LiveCategory.favoritesID)
                ForEach(model.categories.prefix(4)) { cat in
                    Shelf(title: cat.name, channels: Array(model.channels(in: cat.id).prefix(24)), seeAll: cat.id)
                }
            }
            .padding(.bottom, 32)
        }
        .flatScrollEdges()
    }

    private var featured: Channel? {
        model.recentChannels.first ?? model.favoriteChannels.first ?? model.channels.first
    }
}

private struct Hero: View {
    @Environment(AppModel.self) private var model
    let channel: Channel

    var body: some View {
        let sched = model.schedule(for: channel)
        ZStack(alignment: .bottomLeading) {
            ChannelBackdrop(channel: channel)
            ChannelLogo(channel: channel, width: 220, height: 160)
                .shadow(color: .black.opacity(0.5), radius: 30, y: 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                .padding(.trailing, 70)
                .opacity(0.9)
            LinearGradient(colors: [Color(hex: 0x080A0E, opacity: 0.94), Color(hex: 0x080A0E, opacity: 0.55), .clear],
                           startPoint: .leading, endPoint: .trailing)
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    LiveBadge()
                    Text("\(channel.displayName) · \(channel.num)").font(.system(size: 12)).foregroundStyle(Theme.muted)
                    QualityBadge(quality: channel.quality)
                }
                Text(sched.current?.title ?? channel.displayName)
                    .font(.system(size: 38, weight: .heavy))
                    .tracking(-1)
                    .lineLimit(2)
                if let cur = sched.current {
                    HStack(spacing: 10) {
                        Text(cur.start.clock)
                        ProgressLine(value: cur.progress(at: model.now)).frame(width: 200)
                        Text("\(max(1, Int(cur.end.timeIntervalSince(model.now) / 60))) min left")
                    }
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Color(hex: 0xC3CAD6))
                    if !cur.summary.isEmpty {
                        Text(cur.summary).foregroundStyle(Color(hex: 0xC3CAD6)).lineLimit(2).frame(maxWidth: 520, alignment: .leading)
                    }
                }
                HStack(spacing: 8) {
                    Button { model.play(channel) } label: { Label("Watch live", systemImage: "play.fill") }
                        .buttonStyle(PillButtonStyle(prominent: true))
                    Button { model.toggleFavorite(channel) } label: {
                        Label(model.isFavorite(channel) ? "In Favorites" : "Add to Favorites",
                              systemImage: model.isFavorite(channel) ? "star.fill" : "star")
                    }
                    .buttonStyle(PillButtonStyle())
                }
                .padding(.top, 4)
            }
            .padding(32)
        }
        .frame(height: 320)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.line))
        .onAppear { model.requestEPG(channel) }
    }
}

private struct Shelf: View {
    @Environment(AppModel.self) private var model
    let title: String
    let channels: [Channel]
    let seeAll: String?

    var body: some View {
        if !channels.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(title).font(.system(size: 16, weight: .semibold))
                    Spacer()
                    if let seeAll {
                        Button("See all") { model.selectCategory(seeAll); model.go(.live) }
                            .buttonStyle(.plain)
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.muted)
                    }
                }
                .padding(.horizontal, 24)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 14) {
                        ForEach(channels) { ch in
                            ChannelCard(channel: ch, list: channels).frame(width: 230)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 4)
                }
                .scrollIndicators(.never)
            }
            .padding(.top, 20)
        }
    }
}

struct FavoritesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let favs = model.favoriteChannels
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text("Favorites").font(.system(size: 22, weight: .semibold)).tracking(-0.3)
                    Text("\(favs.count) channels").foregroundStyle(Theme.muted)
                }
                .padding(.top, 40)
                if favs.isEmpty {
                    EmptyState(symbol: "star", title: "No favorites yet",
                               message: "Press F on a channel in Live TV, or click its star, to keep it here.")
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 220, maximum: 300), spacing: 14)], spacing: 20) {
                        ForEach(favs) { ChannelCard(channel: $0, list: favs) }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
        .flatScrollEdges()
    }
}
