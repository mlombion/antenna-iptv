import SwiftUI

/// Phone-sized guide: each channel with what's on now and the next shows, swipeable sideways.
struct PhoneGuideView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let categoryID = currentCategory
        let list = model.channels(in: categoryID)
        NavigationStack {
            Group {
                if list.isEmpty {
                    ContentUnavailableView("Nothing to show", systemImage: "calendar",
                                           description: Text("Pick a category with the button above."))
                } else {
                    List(list) { ch in
                        GuideRow(channel: ch) { model.play(ch, in: list) }
                            .listRowBackground(Theme.bg)
                            .listRowSeparatorTint(Theme.line)
                            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 0))
                    }
                    .antennaList()
                }
            }
            .background(Theme.bg)
            .navigationTitle("Guide")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Favorites") { model.guideCategoryID = LiveCategory.favoritesID }
                        Divider()
                        ForEach(model.categories) { cat in
                            Button(cat.name) { model.guideCategoryID = cat.id }
                        }
                    } label: {
                        Label(model.categoryName(categoryID), systemImage: "line.3.horizontal.decrease.circle")
                            .labelStyle(.titleAndIcon)
                            .font(.system(size: 14))
                            .lineLimit(1)
                    }
                }
            }
        }
    }

    private var currentCategory: String {
        if let id = model.guideCategoryID { return id }
        return model.favorites.isEmpty ? (model.categories.first?.id ?? LiveCategory.allID) : LiveCategory.favoritesID
    }
}

private struct GuideRow: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let play: () -> Void

    var body: some View {
        let sched = model.schedule(for: channel)
        VStack(alignment: .leading, spacing: 8) {
            Button(action: play) {
                HStack(spacing: 10) {
                    ChannelLogo(channel: channel, width: 36, height: 27)
                    Text(channel.displayName).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    Text("\(channel.num)").font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.faint)
                    Spacer()
                }
            }
            .buttonStyle(.plain)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 8) {
                    if let cur = sched.current { block(cur, live: true) }
                    ForEach(sched.upcoming.prefix(6)) { block($0, live: false) }
                    if sched.current == nil && sched.upcoming.isEmpty {
                        Text(sched.isLoading ? "Loading…" : "No guide information")
                            .font(.system(size: 12)).foregroundStyle(Theme.faint)
                            .frame(height: 52)
                    }
                }
                .padding(.trailing, 16)
            }
            .scrollIndicators(.never)
        }
        .onAppear { model.requestEPG(channel) }
    }

    private func block(_ p: Programme, live: Bool) -> some View {
        Button(action: play) {
            VStack(alignment: .leading, spacing: 3) {
                Text(p.title).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                Text(live ? "Now · until \(p.end.clock)" : p.start.clock)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(live ? Theme.accent : Theme.faint)
                if live { ProgressLine(value: p.progress(at: model.now), height: 2).padding(.top, 2) }
            }
            .padding(.horizontal, 10)
            .frame(width: live ? 190 : 150, height: 56, alignment: .leading)
            .background(live ? Theme.accentSoft : Theme.surface2, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(live ? Theme.accent.opacity(0.35) : Theme.line))
        }
        .buttonStyle(.plain)
    }
}
