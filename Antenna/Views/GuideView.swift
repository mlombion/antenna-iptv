import SwiftUI

/// Programme grid for one category at a time: a full lineup can run to thousands of channels.
struct GuideView: View {
    @Environment(AppModel.self) private var model
    private let pointsPerMinute: CGFloat = 5
    private let rowHeight: CGFloat = 58
    private let channelWidth: CGFloat = 210

    var body: some View {
        let categoryID = currentCategory
        let list = model.channels(in: categoryID)
        let start = windowStart
        let end = start.addingTimeInterval(5 * 3600)
        let width = CGFloat(end.timeIntervalSince(start) / 60) * pointsPerMinute

        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Text("TV Guide").font(.system(size: 22, weight: .semibold)).tracking(-0.3)
                Text(model.now.formatted(.dateTime.weekday(.wide).day().month(.wide)))
                    .foregroundStyle(Theme.muted)
                Spacer()
                Menu {
                    Button("Favorites") { model.guideCategoryID = LiveCategory.favoritesID }
                    Divider()
                    ForEach(model.categories) { cat in
                        Button(cat.name) { model.guideCategoryID = cat.id }
                    }
                } label: {
                    Label(model.categoryName(categoryID), systemImage: "line.3.horizontal.decrease")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(Theme.surface3, in: RoundedRectangle(cornerRadius: 8))
            }
            .padding(.horizontal, 24)
            .padding(.top, 40)
            .padding(.bottom, 14)

            if list.isEmpty {
                EmptyState(symbol: "calendar", title: "Nothing to show",
                           message: categoryID == LiveCategory.favoritesID ? "Add favorites, or pick a category above." : "This category has no channels.")
                Spacer()
            } else {
                ScrollView(.vertical) {
                    HStack(alignment: .top, spacing: 0) {
                        // Channel names stay put while the programmes scroll sideways.
                        LazyVStack(spacing: 0) {
                            Text("\(list.count) channels")
                                .font(.system(size: 11)).foregroundStyle(Theme.faint)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)
                                .frame(height: 34)
                                .overlay(alignment: .bottom) { Rectangle().fill(Theme.line).frame(height: 1) }
                            ForEach(list) { ch in
                                Button { model.reveal(ch) } label: {
                                    HStack(spacing: 10) {
                                        Text("\(ch.num)").font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.faint)
                                            .frame(width: 30, alignment: .trailing)
                                        ChannelLogo(channel: ch, width: 36, height: 27)
                                        Text(ch.displayName).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                                        Spacer(minLength: 0)
                                    }
                                    .padding(.horizontal, 12)
                                    .frame(height: rowHeight)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .overlay(alignment: .bottom) { Rectangle().fill(Theme.line).frame(height: 1) }
                                .onAppear { model.requestEPG(ch) }
                            }
                        }
                        .frame(width: channelWidth)
                        .background(Theme.surface)
                        .overlay(alignment: .trailing) { Rectangle().fill(Theme.line).frame(width: 1) }

                        ScrollView(.horizontal) {
                            VStack(alignment: .leading, spacing: 0) {
                                timeHeader(start: start, end: end)
                                ForEach(list) { ch in
                                    row(ch, start: start, end: end, width: width)
                                }
                            }
                            .frame(width: width)
                            .overlay(alignment: .topLeading) {
                                Rectangle().fill(Theme.live).frame(width: 2)
                                    .offset(x: CGFloat(model.now.timeIntervalSince(start) / 60) * pointsPerMinute)
                                    .allowsHitTesting(false)
                            }
                        }
                        .scrollIndicators(.visible)
                    }
                }
                .overlay(alignment: .top) { Rectangle().fill(Theme.line).frame(height: 1) }
            }
        }
    }

    private var currentCategory: String {
        if let id = model.guideCategoryID { return id }
        return model.favorites.isEmpty ? (model.categories.first?.id ?? LiveCategory.allID) : LiveCategory.favoritesID
    }

    private var windowStart: Date {
        let cal = Calendar.current
        var comps = cal.dateComponents([.year, .month, .day, .hour, .minute], from: model.now)
        comps.minute = (comps.minute ?? 0) < 30 ? 0 : 30
        return (cal.date(from: comps) ?? model.now).addingTimeInterval(-30 * 60)
    }

    private func timeHeader(start: Date, end: Date) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(stride(from: start, to: end, by: 1800)), id: \.self) { t in
                Text(t.clock)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Theme.muted)
                    .padding(.leading, 6)
                    .frame(height: 34)
                    .overlay(alignment: .leading) { Rectangle().fill(Theme.line).frame(width: 1) }
                    .offset(x: CGFloat(t.timeIntervalSince(start) / 60) * pointsPerMinute)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 34, maxHeight: 34, alignment: .topLeading)
        .overlay(alignment: .bottom) { Rectangle().fill(Theme.line).frame(height: 1) }
    }

    private func row(_ ch: Channel, start: Date, end: Date, width: CGFloat) -> some View {
        let progs = model.programmes(for: ch).filter { $0.end > start && $0.start < end }
        return ZStack(alignment: .topLeading) {
            if progs.isEmpty {
                Text(model.schedule(for: ch).isLoading ? "Loading…" : "No guide information")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.faint)
                    .padding(.horizontal, 12)
                    .frame(width: width - 8, height: rowHeight - 10, alignment: .leading)
                    .background(Theme.surface2.opacity(0.5), in: RoundedRectangle(cornerRadius: 7))
                    .offset(x: 4, y: 5)
            }
            ForEach(progs) { p in
                let x0 = max(0, CGFloat(p.start.timeIntervalSince(start) / 60) * pointsPerMinute)
                let x1 = min(width, CGFloat(p.end.timeIntervalSince(start) / 60) * pointsPerMinute)
                ProgrammeBlock(programme: p, now: model.now) { model.play(ch, in: model.channels(in: currentCategory)) }
                    .frame(width: max(0, x1 - x0 - 4), height: rowHeight - 10)
                    .offset(x: x0 + 2, y: 5)
            }
        }
        .frame(width: width, height: rowHeight, alignment: .topLeading)
        .overlay(alignment: .bottom) { Rectangle().fill(Theme.line).frame(height: 1) }
    }
}

private struct ProgrammeBlock: View {
    let programme: Programme
    let now: Date
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        let on = programme.isOn(at: now)
        let past = programme.end <= now
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(programme.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                Text("\(programme.start.clock) – \(programme.end.clock)")
                    .font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.faint).lineLimit(1)
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 7).fill(hover ? Theme.surface3 : Theme.surface2)
                        if on {
                            Rectangle().fill(Theme.accentSoft).frame(width: g.size.width * programme.progress(at: now))
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 7))
                }
            }
            .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(on ? Theme.accent.opacity(0.35) : Theme.line))
            .opacity(past ? 0.45 : 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .help(programme.summary.isEmpty ? programme.title : "\(programme.title)\n\n\(programme.summary)")
    }
}
