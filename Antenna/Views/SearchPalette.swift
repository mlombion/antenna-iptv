import SwiftUI

struct SearchPalette: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var selection = 0
    @FocusState private var focused: Bool

    private struct Hit: Identifiable {
        let channel: Channel
        let title: String
        let subtitle: String
        var later = false
        var id: String { "\(channel.id)-\(title)-\(later)" }
    }

    var body: some View {
        let (channelHits, showHits) = results
        let all = channelHits + showHits

        ZStack(alignment: .top) {
            Color.black.opacity(0.5)
                .onTapGesture { model.showSearch = false }
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass").font(.system(size: 16)).foregroundStyle(Theme.muted)
                    TextField("Search channels and shows", text: $query)
                        .textFieldStyle(.plain)
                        .font(.system(size: 16))
                        .focused($focused)
                        .onSubmit { open(all) }
                }
                .padding(.horizontal, 16)
                .frame(height: 54)
                .overlay(alignment: .bottom) { Rectangle().fill(Theme.line).frame(height: 1) }

                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 2) {
                            if all.isEmpty {
                                Text(query.isEmpty ? "Type a channel, a number, or a show like “Formula 1”." : "Nothing matches “\(query)”.")
                                    .foregroundStyle(Theme.muted)
                                    .frame(maxWidth: .infinity)
                                    .padding(30)
                            }
                            if !channelHits.isEmpty {
                                SectionLabel(query.isEmpty ? "Recently watched" : "Channels").padding(.horizontal, 10).padding(.top, 10)
                                ForEach(Array(channelHits.enumerated()), id: \.element.id) { i, hit in
                                    row(hit, index: i).id(i)
                                }
                            }
                            if showHits.contains(where: { !$0.later }) {
                                SectionLabel("On now").padding(.horizontal, 10).padding(.top, 10)
                            }
                            ForEach(Array(showHits.enumerated()), id: \.element.id) { i, hit in
                                if hit.later && (i == 0 || !showHits[i - 1].later) {
                                    SectionLabel("Later today").padding(.horizontal, 10).padding(.top, 10)
                                }
                                row(hit, index: i + channelHits.count).id(i + channelHits.count)
                            }
                            if !query.isEmpty && model.isLoadingGuide {
                                Text("Loading the full TV guide so shows on every channel can be found…")
                                    .font(.system(size: 11.5)).foregroundStyle(Theme.faint).padding(10)
                            }
                        }
                        .padding(6)
                    }
                    .onChange(of: selection) { _, s in proxy.scrollTo(s) }
                }
                .frame(maxHeight: 420)

                HStack(spacing: 16) {
                    Text("↑ ↓ move")
                    Text("↵ watch")
                    Text("esc close")
                }
                .font(.system(size: 11))
                .foregroundStyle(Theme.faint)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .frame(height: 34)
                .overlay(alignment: .top) { Rectangle().fill(Theme.line).frame(height: 1) }
            }
            .frame(width: 620)
            .background(Color(hex: 0x161B24), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.lineStrong))
            .shadow(color: .black.opacity(0.7), radius: 40, y: 20)
            .padding(.top, 110)
        }
        .onAppear { focused = true }
        .onChange(of: query) { selection = 0 }
        .onKeyPress(.downArrow) { selection = min(max(0, all.count - 1), selection + 1); return .handled }
        .onKeyPress(.upArrow) { selection = max(0, selection - 1); return .handled }
        .onKeyPress(.escape) { model.showSearch = false; return .handled }
    }

    /// Channels whose name matches, then shows on now, then shows later today.
    private var results: ([Hit], [Hit]) {
        let q = query.trimmingCharacters(in: .whitespaces)
        func sub(_ c: Channel) -> String {
            let cur = model.schedule(for: c).current
            return cur.map { "\(c.num) · Now: \($0.title)" } ?? "Channel \(c.num) · \(model.categoryName(c.categoryID))"
        }
        if q.isEmpty {
            return (model.recentChannels.prefix(6).map { Hit(channel: $0, title: $0.displayName, subtitle: sub($0)) }, [])
        }
        let channels = model.channels(in: LiveCategory.allID).lazy.filter { $0.matches(q) }.prefix(6)
            .map { Hit(channel: $0, title: $0.displayName, subtitle: sub($0)) }
        let shows = model.showMatches(q, limit: 12)
        let onNow = shows.now.map { Hit(channel: $0.channel, title: $0.programme.title, subtitle: "\($0.channel.displayName) · on now · until \($0.programme.end.clock)", later: false) }
        let later = shows.later.map { Hit(channel: $0.channel, title: $0.programme.title, subtitle: "\($0.channel.displayName) · starts \($0.programme.start.clock)", later: true) }
        return (Array(channels), onNow + later)
    }

    private func row(_ hit: Hit, index: Int) -> some View {
        Button { model.play(hit.channel) } label: {
            HStack(spacing: 12) {
                ChannelLogo(channel: hit.channel)
                VStack(alignment: .leading, spacing: 1) {
                    Text(hit.title).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    Text(hit.subtitle).font(.system(size: 12)).foregroundStyle(Theme.muted).lineLimit(1)
                }
                Spacer()
                if hit.later {
                    Text("LATER").font(.system(size: 10, weight: .bold)).tracking(0.8).foregroundStyle(Theme.muted)
                        .padding(.horizontal, 6).padding(.vertical, 3.5)
                        .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(Theme.lineStrong))
                } else {
                    LiveBadge()
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 8).fill(index == selection ? Theme.surface3 : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { if $0 { selection = index } }
    }

    private func open(_ all: [Hit]) {
        guard all.indices.contains(selection) else { return }
        model.play(all[selection].channel)
    }
}
