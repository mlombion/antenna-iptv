import SwiftUI

struct SidebarView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            BrandMark()
                .padding(.horizontal, 10)
                .padding(.top, 46)
                .padding(.bottom, 18)

            NavRow(title: "Home", symbol: "house", shortcut: "⌘0", selected: model.section == .home) { model.go(.home) }
            NavRow(title: "Live TV", symbol: "tv", shortcut: "⌘1", selected: model.section == .live) { model.go(.live) }
            NavRow(title: "TV Guide", symbol: "calendar", shortcut: "⌘2", selected: model.section == .guide) { model.go(.guide) }
            NavRow(title: "Favorites", symbol: "star", shortcut: "⌘3", selected: model.section == .favorites) { model.go(.favorites) }
            NavRow(title: "Movies & Series", symbol: "film.stack", shortcut: "⌘4", selected: model.section == .movies) { model.go(.movies) }
            NavRow(title: "Search", symbol: "magnifyingglass", shortcut: "⌘K", selected: false) { model.showSearch = true }

            SectionLabel("App").padding(.horizontal, 10).padding(.top, 16).padding(.bottom, 6)
            NavRow(title: "Settings", symbol: "gearshape", shortcut: "⌘,", selected: model.section == .settings) { model.go(.settings) }

            Spacer()
            if model.isPiP, let ch = model.playing { nowPlaying(ch) }
            if model.isMoviePiP, let m = model.movie { movieCard(m) }
            if model.cast.active != nil { castingCard }
            accountFooter
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 14)
        .frame(width: 224)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(LinearGradient(colors: [Theme.sidebarTop, Theme.sidebarBottom], startPoint: .top, endPoint: .bottom))
        .overlay(alignment: .trailing) { Rectangle().fill(Theme.line).frame(width: 1) }
    }

    private var accountFooter: some View {
        Button { model.go(.settings) } label: {
            HStack(spacing: 10) {
                Avatar(name: model.account?.name ?? "IPTV", size: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.account?.name ?? "IPTV").font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                    HStack(spacing: 5) {
                        Circle().fill(model.isRefreshing ? Theme.accent : Theme.ok).frame(width: 7, height: 7)
                        Text(statusLine).font(.system(size: 11)).foregroundStyle(Theme.faint).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(10)
            .background(Color.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.line))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Shown while the video floats, so it's always one click to bring it back or stop it.
    private func nowPlaying(_ ch: Channel) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Floating window")
            HStack(spacing: 10) {
                ChannelLogo(channel: ch, width: 36, height: 27)
                VStack(alignment: .leading, spacing: 1) {
                    Text(ch.displayName).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                    Text(model.schedule(for: ch).current?.title ?? "Live").font(.system(size: 11)).foregroundStyle(Theme.muted).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 6) {
                Button { model.exitPiP(returnToApp: true) } label: { Label("Expand", systemImage: "arrow.up.left.and.arrow.down.right") }
                    .buttonStyle(PillButtonStyle())
                Button { model.exitPiP(returnToApp: false) } label: { Label("Stop", systemImage: "stop.fill") }
                    .buttonStyle(PillButtonStyle())
            }
            .controlSize(.small)
        }
        .padding(10)
        .background(Theme.accentSoft, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.accent.opacity(0.3)))
        .padding(.bottom, 8)
    }

    private func movieCard(_ m: MoviePlayback) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Floating window")
            HStack(spacing: 10) {
                AsyncImage(url: m.meta.poster) { $0.resizable().scaledToFill() } placeholder: { Theme.surface3 }
                    .frame(width: 24, height: 36).clipShape(RoundedRectangle(cornerRadius: 4))
                VStack(alignment: .leading, spacing: 1) {
                    Text(m.title).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                    Text(m.subtitle ?? "Movie").font(.system(size: 11)).foregroundStyle(Theme.muted).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 6) {
                Button { model.exitMoviePiP(returnToApp: true) } label: { Label("Expand", systemImage: "arrow.up.left.and.arrow.down.right") }
                    .buttonStyle(PillButtonStyle())
                Button { model.exitMoviePiP(returnToApp: false) } label: { Label("Stop", systemImage: "stop.fill") }
                    .buttonStyle(PillButtonStyle())
            }
            .controlSize(.small)
        }
        .padding(10)
        .background(Theme.accentSoft, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.accent.opacity(0.3)))
        .padding(.bottom, 8)
    }

    /// While a TV is playing: what's on, its rewind position, and remote buttons.
    private var castingCard: some View {
        let status = model.cast.status
        return VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Playing on \(model.cast.active?.displayName ?? "TV")")
            HStack(spacing: 10) {
                if let ch = model.castingChannel { ChannelLogo(channel: ch, width: 36, height: 27) }
                VStack(alignment: .leading, spacing: 1) {
                    Text(status?.playing?.name ?? model.castingChannel?.displayName ?? "Starting…")
                        .font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                    Text(status?.timeline.map { $0.live ? "Live" : "−" + RewindBar.format($0.behindMs / 1000) + " behind live" }
                         ?? status?.playing?.title ?? " ")
                        .font(.system(size: 11)).foregroundStyle(Theme.muted).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 2) {
                castButton("chevron.up", "Previous channel") { model.castZap(-1) }
                castButton("chevron.down", "Next channel") { model.castZap(1) }
                castButton("gobackward.10", "Back 10 seconds") { model.cast.command("back10") }
                castButton(status?.state == "paused" ? "play.fill" : "pause.fill", "Pause") { model.cast.command("pause") }
                castButton("goforward.10", "Forward 10 seconds") { model.cast.command("fwd10") }
                castButton("forward.end.fill", "Go live") { model.cast.command("live") }
            }
            HStack(spacing: 6) {
                Button { model.stopCasting(resumeHere: true) } label: { Label("Watch here", systemImage: "desktopcomputer") }
                    .buttonStyle(PillButtonStyle())
                Button { model.stopCasting(resumeHere: false) } label: { Label("Stop", systemImage: "stop.fill") }
                    .buttonStyle(PillButtonStyle())
            }
            .controlSize(.small)
            Text("Click any channel to play it on the TV.").font(.system(size: 10.5)).foregroundStyle(Theme.faint)
        }
        .padding(10)
        .background(Theme.accentSoft, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.accent.opacity(0.3)))
        .padding(.bottom, 8)
    }

    private func castButton(_ symbol: String, _ help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 11, weight: .semibold)).frame(width: 30, height: 28)
                .background(Theme.surface3, in: RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .help(help)
    }

    private var statusLine: String {
        if model.isRefreshing { return "Refreshing…" }
        if let exp = model.accountInfo?.expiry {
            return "Until \(exp.formatted(.dateTime.day().month(.abbreviated).year()))"
        }
        return "Connected"
    }
}

struct NavRow: View {
    let title: String
    let symbol: String
    let shortcut: String
    let selected: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 14))
                    .frame(width: 18)
                    .foregroundStyle(selected ? Theme.accent : (hover ? Theme.fg : Theme.muted))
                Text(title).foregroundStyle(selected || hover ? Theme.fg : Theme.muted)
                Spacer()
                Text(shortcut).font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.faint)
                    .opacity(hover || selected ? 1 : 0)
            }
            .font(.system(size: 13, weight: .medium))
            .padding(.horizontal, 10)
            .frame(height: 34)
            .background(RoundedRectangle(cornerRadius: 8)
                .fill(selected ? Theme.surface3 : (hover ? Color.white.opacity(0.04) : .clear)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}
