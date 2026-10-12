import SwiftUI

struct LiveTVView: View {
    var body: some View {
        HStack(spacing: 0) {
            CategoryColumn()
                .frame(width: 210)
                .overlay(alignment: .trailing) { Rectangle().fill(Theme.line).frame(width: 1) }
            ChannelColumn()
                .frame(minWidth: 320, idealWidth: 430, maxWidth: 520)
                .overlay(alignment: .trailing) { Rectangle().fill(Theme.line).frame(width: 1) }
            ChannelDetail()
                .frame(minWidth: 360, maxWidth: .infinity)
        }
    }
}

// MARK: - Categories

private struct CategoryColumn: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 1) {
                SectionLabel("Categories").padding(.horizontal, 10).padding(.top, 46).padding(.bottom, 8)
                row(LiveCategory.allID, "All channels", symbol: "square.grid.2x2")
                row(LiveCategory.favoritesID, "Favorites", symbol: "star")
                Rectangle().fill(Theme.line).frame(height: 1).padding(.vertical, 8).padding(.horizontal, 10)
                ForEach(model.categories) { cat in
                    row(cat.id, cat.name, symbol: nil)
                }
            }
            .padding(.horizontal, 10)
            .padding(.bottom, 16)
        }
        .scrollIndicators(.never)
    }

    private func row(_ id: String, _ name: String, symbol: String?) -> some View {
        CategoryRow(name: name, symbol: symbol, count: model.channels(in: id).count,
                    selected: model.selectedCategoryID == id) {
            model.selectCategory(id)
        }
    }
}

private struct CategoryRow: View {
    let name: String
    let symbol: String?
    let count: Int
    let selected: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let symbol { Image(systemName: symbol).font(.system(size: 12)).frame(width: 14) }
                Text(name).lineLimit(1)
                Spacer(minLength: 4)
                Text("\(count)").font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(selected ? Theme.accent.opacity(0.8) : Theme.faint)
            }
            .font(.system(size: 12.5, weight: selected ? .semibold : .regular))
            .foregroundStyle(selected ? Theme.accent : (hover ? Theme.fg : Theme.muted))
            .padding(.horizontal, 10)
            .frame(height: 30)
            .background(RoundedRectangle(cornerRadius: 7)
                .fill(selected ? Theme.accentSoft : (hover ? Color.white.opacity(0.04) : .clear)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

// MARK: - Channel list

private struct ChannelColumn: View {
    @Environment(AppModel.self) private var model
    @FocusState private var listFocused: Bool
    @FocusState private var filterFocused: Bool

    var body: some View {
        @Bindable var model = model
        let list = model.visibleChannels

        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(model.categoryName(model.selectedCategoryID))
                        .font(.system(size: 17, weight: .semibold))
                        .lineLimit(1)
                    Spacer()
                    Text("\(list.count.formatted()) channels")
                        .font(.system(size: 11.5, design: .monospaced))
                        .foregroundStyle(Theme.faint)
                }
                HStack(spacing: 8) {
                    Image(systemName: "line.3.horizontal.decrease").font(.system(size: 12)).foregroundStyle(Theme.muted)
                    TextField("Channels or shows, like “Formula 1”", text: $model.filter)
                        .textFieldStyle(.plain)
                        .focused($filterFocused)
                        .onSubmit { listFocused = true }
                    if !model.filter.isEmpty {
                        Button { model.filter = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.faint)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 10)
                .frame(height: 32)
                .background(Theme.surface2, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(filterFocused ? Theme.accent.opacity(0.6) : Theme.line))
            }
            .padding(.horizontal, 16)
            .padding(.top, 40)
            .padding(.bottom, 10)

            if !model.filter.trimmingCharacters(in: .whitespaces).isEmpty,
               case let shows = model.showMatches(model.filter), !(shows.now.isEmpty && shows.later.isEmpty) {
                // The filter searches shows on every channel too, then lists matching channel names.
                ShowResults(shows: shows, channelHits: list)
            } else if list.isEmpty {
                EmptyState(symbol: model.selectedCategoryID == LiveCategory.favoritesID ? "star" : "magnifyingglass",
                           title: model.selectedCategoryID == LiveCategory.favoritesID && model.filter.isEmpty ? "No favorites yet" : "No channels found",
                           message: model.selectedCategoryID == LiveCategory.favoritesID && model.filter.isEmpty
                               ? "Click the star next to a channel to keep it here."
                               : "Nothing in this category matches “\(model.filter)”.")
                Spacer()
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 2) {
                            ForEach(list) { ch in
                                ChannelRow(channel: ch, selected: ch.id == model.selectedChannelID)
                                    .id(ch.id)
                                    .onTapGesture { model.selectedChannelID = ch.id; listFocused = true }
                                    .simultaneousGesture(TapGesture(count: 2).onEnded { model.play(ch, in: list) })
                            }
                        }
                        .padding(.horizontal, 8)
                        .padding(.bottom, 16)
                    }
                    .onChange(of: model.selectedChannelID) { _, id in
                        guard let id else { return }
                        withAnimation(.easeOut(duration: 0.12)) { proxy.scrollTo(id, anchor: nil) }
                    }
                }
                .focusable()
                .focused($listFocused)
                .focusEffectDisabled()
                .onKeyPress(.downArrow) { model.moveSelection(1); return .handled }
                .onKeyPress(.upArrow) { model.moveSelection(-1); return .handled }
                .onKeyPress(.pageDown) { model.moveSelection(12); return .handled }
                .onKeyPress(.pageUp) { model.moveSelection(-12); return .handled }
                .onKeyPress(.return) {
                    if let ch = model.selectedChannel { model.play(ch, in: list) }
                    return .handled
                }
                .onKeyPress(characters: CharacterSet(charactersIn: "f")) { _ in
                    if let ch = model.selectedChannel { model.toggleFavorite(ch) }
                    return .handled
                }
            }
        }
        .onAppear { listFocused = true }
    }
}

private struct ChannelRow: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let selected: Bool
    @State private var hover = false

    var body: some View {
        let sched = model.schedule(for: channel)
        HStack(spacing: 12) {
            Text("\(channel.num)")
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(Theme.faint)
                .frame(width: 34, alignment: .trailing)
            ChannelLogo(channel: channel)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(channel.displayName).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    QualityBadge(quality: channel.quality)
                }
                Text(sched.current?.title ?? placeholder(sched))
                    .font(.system(size: 12))
                    .foregroundStyle(sched.current == nil ? Theme.faint : Theme.muted)
                    .lineLimit(1)
                if let cur = sched.current {
                    ProgressLine(value: cur.progress(at: model.now), height: 2.5).padding(.top, 1)
                }
            }
            Spacer(minLength: 4)
            if let cur = sched.current {
                Text("\(max(1, Int(cur.end.timeIntervalSince(model.now) / 60)))m")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Theme.faint)
            }
            StarButton(channel: channel, size: 13)
                .opacity(hover || selected || model.isFavorite(channel) ? 1 : 0)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 9)
            .fill(selected ? Theme.surface3 : (hover ? Color.white.opacity(0.035) : .clear)))
        .overlay(alignment: .leading) {
            if selected { Capsule().fill(Theme.accent).frame(width: 3).padding(.vertical, 10) }
        }
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onAppear { model.requestEPG(channel) }
        .contextMenu {
            Button("Watch") { model.play(channel) }
            Button("Watch in Picture in Picture") { model.play(channel); model.enterPiP() }
            Button(model.isFavorite(channel) ? "Remove from Favorites" : "Add to Favorites") { model.toggleFavorite(channel) }
        }
    }

    private func placeholder(_ s: Schedule) -> String {
        switch s.state {
        case .unavailable, .failed: "No guide information"
        default: " "
        }
    }
}

// MARK: - Detail

private struct ChannelDetail: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let ch = model.selectedChannel {
            let sched = model.schedule(for: ch)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 20) {
                    Button { model.play(ch) } label: { PreviewTile(channel: ch, title: sched.current?.title) }
                        .buttonStyle(.plain)

                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 12) {
                            ChannelLogo(channel: ch, width: 56, height: 42)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(ch.displayName).font(.system(size: 15, weight: .semibold))
                                Text("Channel \(ch.num) · \(model.categoryName(ch.categoryID))")
                                    .font(.system(size: 12)).foregroundStyle(Theme.muted)
                            }
                        }
                        if let cur = sched.current {
                            Text(cur.title).font(.system(size: 24, weight: .bold)).tracking(-0.4)
                                .fixedSize(horizontal: false, vertical: true)
                            HStack(spacing: 10) {
                                Text(cur.start.clock)
                                ProgressLine(value: cur.progress(at: model.now)).frame(maxWidth: 220)
                                Text(cur.end.clock)
                                Text("· \(max(1, Int(cur.end.timeIntervalSince(model.now) / 60))) min left")
                            }
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Theme.muted)
                            if !cur.summary.isEmpty {
                                Text(cur.summary).foregroundStyle(Theme.muted).frame(maxWidth: 560, alignment: .leading)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        } else if !sched.isLoading {
                            Text("Live").font(.system(size: 24, weight: .bold))
                            Text("This channel doesn't publish a TV guide.").foregroundStyle(Theme.muted)
                        } else {
                            Text(" ").font(.system(size: 24, weight: .bold))
                        }
                        HStack(spacing: 8) {
                            Button { model.play(ch) } label: {
                                HStack(spacing: 8) {
                                    Label("Watch", systemImage: "play.fill")
                                    Text("↵").font(.system(size: 11, design: .monospaced)).opacity(0.55)
                                }
                            }
                            .buttonStyle(PillButtonStyle(prominent: true))
                            Button { model.play(ch); model.enterPiP() } label: {
                                Image(systemName: "pip.enter")
                            }
                            .buttonStyle(PillButtonStyle())
                            .help("Watch in a floating window")
                            Button { model.toggleFavorite(ch) } label: {
                                Label(model.isFavorite(ch) ? "In Favorites" : "Add to Favorites",
                                      systemImage: model.isFavorite(ch) ? "star.fill" : "star")
                            }
                            .buttonStyle(PillButtonStyle())
                        }
                        .padding(.top, 2)
                    }

                    if !sched.upcoming.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            SectionLabel("Coming up on \(ch.displayName)")
                            VStack(spacing: 0) {
                                ForEach(sched.upcoming.prefix(6)) { p in
                                    HStack(spacing: 12) {
                                        Text(p.start.clock).font(.system(size: 12, design: .monospaced))
                                            .foregroundStyle(Theme.muted).lineLimit(1).fixedSize().frame(minWidth: 72, alignment: .leading)
                                        Text(p.title).lineLimit(1)
                                        Spacer()
                                        Text("\(p.minutes) min").font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.faint)
                                    }
                                    .padding(.vertical, 10)
                                    .overlay(alignment: .bottom) { Rectangle().fill(Theme.line).frame(height: 1) }
                                }
                            }
                            .overlay(alignment: .top) { Rectangle().fill(Theme.line).frame(height: 1) }
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 40)
                .padding(.bottom, 28)
            }
            .flatScrollEdges()
            .id(ch.id)
        } else {
            EmptyState(symbol: "tv", title: "Pick a channel", message: "Choose a channel on the left to see what's on.")
        }
    }
}

/// Channel tile shown above the details. Click it to start watching.
struct PreviewTile: View {
    let channel: Channel
    let title: String?
    @State private var hover = false

    var body: some View {
        ZStack {
            ChannelBackdrop(channel: channel)
            ChannelLogo(channel: channel, width: 132, height: 96)
                .shadow(color: .black.opacity(0.45), radius: 18, y: 8)
            LinearGradient(colors: [.black.opacity(0.35), .clear, .clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
            VStack {
                HStack(spacing: 6) {
                    LiveBadge()
                    QualityBadge(quality: channel.quality)
                    Spacer()
                }
                Spacer()
                HStack {
                    Text(title ?? channel.displayName).font(.system(size: 17, weight: .semibold)).lineLimit(1)
                        .shadow(color: .black.opacity(0.6), radius: 6)
                    Spacer()
                    Text("Press ↵ to watch").font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.7))
                }
            }
            .padding(14)
            Circle()
                .fill(.ultraThinMaterial)
                .overlay(Image(systemName: "play.fill").font(.system(size: 22)).offset(x: 2))
                .frame(width: 62, height: 62)
                .opacity(hover ? 1 : 0)
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line))
        .shadow(color: .black.opacity(0.6), radius: 24, y: 14)
        .onHover { h in withAnimation(.easeOut(duration: 0.15)) { hover = h } }
    }
}

/// Search results in the Live TV column: shows on now and later today on any channel, then matching channel names.
private struct ShowResults: View {
    @Environment(AppModel.self) private var model
    let shows: (now: [AppModel.ShowMatch], later: [AppModel.ShowMatch])
    let channelHits: [Channel]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 2) {
                if !shows.now.isEmpty {
                    SectionLabel("On now").padding(.horizontal, 10).padding(.top, 6).padding(.bottom, 4)
                    ForEach(shows.now) { row($0, later: false) }
                }
                if !shows.later.isEmpty {
                    SectionLabel("Later today").padding(.horizontal, 10).padding(.top, 14).padding(.bottom, 4)
                    ForEach(shows.later) { row($0, later: true) }
                }
                if !channelHits.isEmpty {
                    SectionLabel("Channels").padding(.horizontal, 10).padding(.top, 14).padding(.bottom, 4)
                    ForEach(channelHits.prefix(40)) { ch in
                        Button { model.play(ch, in: channelHits) } label: {
                            HStack(spacing: 12) {
                                Text("\(ch.num)").font(.system(size: 11.5, design: .monospaced)).foregroundStyle(Theme.faint)
                                    .frame(width: 34, alignment: .trailing)
                                ChannelLogo(channel: ch)
                                Text(ch.displayName).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                                Spacer()
                            }
                            .padding(.horizontal, 8).padding(.vertical, 7)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 16)
        }
    }

    private func row(_ m: AppModel.ShowMatch, later: Bool) -> some View {
        Button { model.play(m.channel) } label: {
            HStack(spacing: 12) {
                ChannelLogo(channel: m.channel)
                VStack(alignment: .leading, spacing: 3) {
                    Text(m.programme.title).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    Text(later ? "\(m.channel.displayName) · starts \(m.programme.start.clock)"
                               : "\(m.channel.displayName) · until \(m.programme.end.clock)")
                        .font(.system(size: 12)).foregroundStyle(Theme.muted).lineLimit(1)
                    if !later { ProgressLine(value: m.programme.progress(at: model.now), height: 2.5) }
                }
                Spacer(minLength: 4)
                if later {
                    Text(m.programme.start.clock).font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.faint)
                } else {
                    LiveBadge()
                }
            }
            .padding(.horizontal, 8).padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 9).fill(Color.white.opacity(0.0001)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Watch \(m.channel.displayName)") { model.play(m.channel) }
            Button("Show channel in Live TV") { model.reveal(m.channel) }
        }
    }
}
