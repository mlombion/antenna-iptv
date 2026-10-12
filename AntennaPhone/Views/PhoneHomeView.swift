import SwiftUI

struct PhoneHomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
                    if let featured { hero(featured) }
                    rail("Recently watched", model.recentChannels, seeAll: nil)
                    rail("Your favorites", model.favoriteChannels, seeAll: LiveCategory.favoritesID)
                    ForEach(model.categories.prefix(4)) { cat in
                        rail(cat.name, Array(model.channels(in: cat.id).prefix(20)), seeAll: cat.id)
                    }
                }
                .padding(.vertical, 12)
            }
            .background(Theme.bg)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark(size: 17) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { model.showRemote = true } label: { Image(systemName: "av.remote") }
                        .accessibilityLabel("Phone remote for your TV")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var featured: Channel? {
        model.recentChannels.first ?? model.favoriteChannels.first ?? model.channels.first
    }

    private func hero(_ ch: Channel) -> some View {
        let sched = model.schedule(for: ch)
        return Button { model.play(ch) } label: {
            ZStack(alignment: .bottomLeading) {
                ChannelBackdrop(channel: ch)
                ChannelLogo(channel: ch, width: 120, height: 88)
                    .shadow(color: .black.opacity(0.5), radius: 20, y: 8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(20)
                LinearGradient(colors: [.clear, Color(hex: 0x080A0E, opacity: 0.92)], startPoint: .top, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        LiveBadge()
                        Text("\(ch.displayName) · \(ch.num)").font(.system(size: 12)).foregroundStyle(Theme.muted)
                    }
                    Text(sched.current?.title ?? ch.displayName)
                        .font(.system(size: 26, weight: .heavy))
                        .tracking(-0.6)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if let cur = sched.current {
                        HStack(spacing: 8) {
                            ProgressLine(value: cur.progress(at: model.now)).frame(width: 120)
                            Text("\(max(1, Int(cur.end.timeIntervalSince(model.now) / 60))) min left")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(Color(hex: 0xC3CAD6))
                        }
                    }
                    Label("Watch live", systemImage: "play.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.accentInk)
                        .padding(.horizontal, 16).frame(height: 36)
                        .background(Theme.accent, in: Capsule())
                        .padding(.top, 4)
                }
                .padding(18)
            }
            .frame(height: 280)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Theme.line))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .onAppear { model.requestEPG(ch) }
    }

    @ViewBuilder
    private func rail(_ title: String, _ channels: [Channel], seeAll: String?) -> some View {
        if !channels.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(title).font(.system(size: 18, weight: .bold))
                    Spacer()
                    if let seeAll {
                        Button("See all") {
                            model.selectCategory(seeAll)
                            model.section = .live
                        }
                        .font(.system(size: 14))
                    }
                }
                .padding(.horizontal, 16)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 12) {
                        ForEach(channels) { ChannelCard(channel: $0, list: channels).frame(width: 190) }
                    }
                    .padding(.horizontal, 16)
                }
                .scrollIndicators(.never)
            }
        }
    }
}

struct PhoneFavoritesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let favs = model.favoriteChannels
        NavigationStack {
            Group {
                if favs.isEmpty {
                    ContentUnavailableView("No favorites yet", systemImage: "star",
                                           description: Text("In Live TV, swipe left on a channel and tap Favorite."))
                } else {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 12)], spacing: 18) {
                            ForEach(favs) { ChannelCard(channel: $0, list: favs) }
                        }
                        .padding(16)
                    }
                }
            }
            .background(Theme.bg)
            .navigationTitle("Favorites")
        }
    }
}
