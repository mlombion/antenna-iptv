import SwiftUI

struct PhoneLiveView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        let list = model.visibleChannels
        NavigationStack {
            VStack(spacing: 0) {
                categoryChips
                if !model.filter.trimmingCharacters(in: .whitespaces).isEmpty {
                    searchResults(list)
                } else if list.isEmpty {
                    ContentUnavailableView(
                        model.selectedCategoryID == LiveCategory.favoritesID && model.filter.isEmpty ? "No favorites yet" : "No channels found",
                        systemImage: model.selectedCategoryID == LiveCategory.favoritesID ? "star" : "magnifyingglass",
                        description: Text(model.selectedCategoryID == LiveCategory.favoritesID && model.filter.isEmpty
                                          ? "Swipe left on a channel to add it to Favorites."
                                          : "Nothing in this category matches “\(model.filter)”."))
                    Spacer()
                } else {
                    List(list) { ch in
                        Button { model.play(ch, in: list) } label: { PhoneChannelRow(channel: ch) }
                            .buttonStyle(.plain)
                            .listRowBackground(Theme.bg)
                            .listRowSeparatorTint(Theme.line)
                            .swipeActions(edge: .trailing) {
                                Button { model.toggleFavorite(ch) } label: {
                                    Label(model.isFavorite(ch) ? "Unfavorite" : "Favorite",
                                          systemImage: model.isFavorite(ch) ? "star.slash" : "star")
                                }
                                .tint(Theme.accent)
                            }
                            .contextMenu {
                                Button { model.play(ch, in: list) } label: { Label("Watch", systemImage: "play.fill") }
                                Button { model.toggleFavorite(ch) } label: {
                                    Label(model.isFavorite(ch) ? "Remove from Favorites" : "Add to Favorites",
                                          systemImage: model.isFavorite(ch) ? "star.slash" : "star")
                                }
                            }
                    }
                    .antennaList()
                }
            }
            .background(Theme.bg)
            .navigationTitle(model.categoryName(model.selectedCategoryID))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $model.filter, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Channels or shows, like “Formula 1”")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("All channels") { model.selectCategory(LiveCategory.allID) }
                        Button("Favorites") { model.selectCategory(LiveCategory.favoritesID) }
                        Divider()
                        ForEach(model.categories) { cat in
                            Button(cat.name) { model.selectCategory(cat.id) }
                        }
                    } label: {
                        Image(systemName: "line.3.horizontal.decrease.circle")
                    }
                }
            }
        }
    }

    /// Search covers shows too: what's on now and later today on every channel, then channel names.
    private func searchResults(_ channelHits: [Channel]) -> some View {
        let shows = model.showMatches(model.filter)
        return List {
            if !shows.now.isEmpty {
                Section("On now") { ForEach(shows.now) { showRow($0, later: false) } }
            }
            if !shows.later.isEmpty {
                Section("Later today") { ForEach(shows.later) { showRow($0, later: true) } }
            }
            if !channelHits.isEmpty {
                Section("Channels") {
                    ForEach(channelHits.prefix(50)) { ch in
                        Button { model.play(ch, in: channelHits) } label: { PhoneChannelRow(channel: ch) }
                            .buttonStyle(.plain)
                            .listRowBackground(Theme.bg)
                    }
                }
            }
            if model.isLoadingGuide {
                Text("Loading the full TV guide so shows on every channel can be found…")
                    .font(.system(size: 12)).foregroundStyle(Theme.faint).listRowBackground(Theme.bg)
            } else if shows.now.isEmpty && shows.later.isEmpty && channelHits.isEmpty {
                Text("Nothing matches “\(model.filter)”.").foregroundStyle(Theme.muted).listRowBackground(Theme.bg)
            }
        }
        .antennaList()
    }

    private func showRow(_ m: AppModel.ShowMatch, later: Bool) -> some View {
        Button { model.play(m.channel) } label: {
            HStack(spacing: 12) {
                ChannelLogo(channel: m.channel, width: 48, height: 36)
                VStack(alignment: .leading, spacing: 3) {
                    Text(m.programme.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    Text(later ? "\(m.channel.displayName) · starts \(m.programme.start.clock)"
                               : "\(m.channel.displayName) · until \(m.programme.end.clock)")
                        .font(.system(size: 13)).foregroundStyle(Theme.muted).lineLimit(1)
                    if !later { ProgressLine(value: m.programme.progress(at: model.now), height: 2.5) }
                }
                Spacer(minLength: 0)
                if !later { LiveBadge() }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Theme.bg)
    }

    private var categoryChips: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                LazyHStack(spacing: 8) {
                    chip(LiveCategory.allID, "All")
                    chip(LiveCategory.favoritesID, "Favorites")
                    ForEach(model.categories) { chip($0.id, $0.name) }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .scrollIndicators(.never)
            .onAppear { proxy.scrollTo(model.selectedCategoryID, anchor: .center) }
            .onChange(of: model.selectedCategoryID) { _, id in
                withAnimation { proxy.scrollTo(id, anchor: .center) }
            }
        }
        .frame(height: 52)
    }

    private func chip(_ id: String, _ name: String) -> some View {
        let on = model.selectedCategoryID == id
        return Button { model.selectCategory(id) } label: {
            Text(name)
                .font(.system(size: 13, weight: on ? .semibold : .regular))
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(height: 32)
                .foregroundStyle(on ? Theme.accentInk : Theme.muted)
                .background(Capsule().fill(on ? Theme.accent : Theme.surface2))
                .overlay(Capsule().strokeBorder(on ? .clear : Theme.line))
        }
        .buttonStyle(.plain)
        .id(id)
    }
}
