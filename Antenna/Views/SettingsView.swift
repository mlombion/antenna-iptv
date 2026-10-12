import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmSignOut = false

    var body: some View {
        @Bindable var settings = model.settings
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                Text("Settings").font(.system(size: 22, weight: .semibold)).tracking(-0.3).padding(.top, 40)

                group("Account") {
                    HStack(spacing: 14) {
                        Avatar(name: model.account?.name ?? "IPTV", size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.account?.name ?? "IPTV").font(.system(size: 15, weight: .semibold))
                            Text("\(model.account?.username ?? "") · \(URL(string: model.account?.server ?? "")?.host() ?? "")")
                                .foregroundStyle(Theme.muted)
                        }
                        Spacer()
                        Button("Refresh channels") { Task { await model.refresh(silent: false) } }
                            .buttonStyle(PillButtonStyle())
                            .disabled(model.isRefreshing)
                        Button("Switch account") { model.beginSwitchAccount() }
                            .buttonStyle(PillButtonStyle())
                    }
                    .padding(16)
                    HStack(spacing: 32) {
                        stat("Channels", model.channels.count.formatted())
                        stat("Status", model.accountInfo?.status ?? "—")
                        stat("Expires", model.accountInfo?.expiry?.formatted(.dateTime.day().month(.abbreviated).year()) ?? "Never")
                        stat("Screens", screens)
                        stat("Last refresh", model.lastRefresh.map { $0.formatted(.relative(presentation: .named)) } ?? "—")
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
                }

                group("Playback") {
                    row("Stream format", "MPEG-TS starts faster with less delay. Try HLS if channels stutter or won't start.") {
                        Picker("", selection: $settings.format) {
                            ForEach(StreamFormat.allCases) { Text($0.label).tag($0) }
                        }
                        .labelsHidden().fixedSize()
                    }
                    row("Buffer size", "A larger buffer stalls less on slow connections but takes longer to start.") {
                        Picker("", selection: $settings.buffer) {
                            ForEach(BufferSize.allCases) { Text($0.label).tag($0) }
                        }
                        .labelsHidden().fixedSize()
                    }
                    row("Rewind buffer", "Keeps the channel you're watching on disk so you can pause, rewind and catch up. Works with MPEG-TS. About 2 GB per 30 minutes of HD, deleted when you change channel.") {
                        Picker("", selection: $settings.rewindMinutes) {
                            Text("Off").tag(0)
                            Text("15 minutes").tag(15)
                            Text("30 minutes").tag(30)
                            Text("1 hour").tag(60)
                            Text("2 hours").tag(120)
                        }
                        .labelsHidden().fixedSize()
                    }
                    row("Hardware decoding", "Uses the Mac's media engine. Lower CPU use and less heat.") {
                        Toggle("", isOn: $settings.hardwareDecoding).toggleStyle(.switch).labelsHidden().tint(Theme.accent)
                    }
                    row("Reconnect automatically", "Retries up to three times when a stream drops.", last: true) {
                        Toggle("", isOn: $settings.autoReconnect).toggleStyle(.switch).labelsHidden().tint(Theme.accent)
                    }
                }

                group("Interface") {
                    row("When the app opens", nil) {
                        Picker("", selection: $settings.startSection) {
                            ForEach(StartSection.allCases) { Text($0.label).tag($0) }
                        }
                        .labelsHidden().fixedSize()
                    }
                    row("Hide adult categories", nil, last: true) {
                        Toggle("", isOn: $settings.hideAdult).toggleStyle(.switch).labelsHidden().tint(Theme.accent)
                    }
                }

                group("Keyboard") {
                    VStack(alignment: .leading, spacing: 8) {
                        shortcut("↑ ↓", "Move through channels, or change channel while watching")
                        shortcut("← →", "Rewind or skip ahead 10 seconds while watching (⇧ for 60 seconds)")
                        shortcut("↵", "Watch the selected channel")
                        shortcut("0–9", "Go to a channel number")
                        shortcut("F", "Favorite the selected channel · full screen while watching")
                        shortcut("L / I / M", "Channel list · stream info · mute, while watching")
                        shortcut("P", "Picture in Picture: keep watching in a floating window")
                        shortcut("⌘K", "Search")
                    }
                    .padding(16)
                }

                Button("Sign out") { confirmSignOut = true }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.live)
                    .confirmationDialog("Sign out of \(model.account?.name ?? "this account")?", isPresented: $confirmSignOut) {
                        Button("Sign out", role: .destructive) { model.signOut() }
                    } message: {
                        Text("Your login is removed from this Mac. Favorites stay.")
                    }
            }
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
        }
        .flatScrollEdges()
    }

    private var screens: String {
        guard let max = model.accountInfo?.maxConnections else { return "—" }
        return "\(model.accountInfo?.activeConnections ?? 0) / \(max)"
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(title).padding(.leading, 4)
            VStack(alignment: .leading, spacing: 0) { content() }
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line))
        }
    }

    private func row<C: View>(_ title: String, _ detail: String?, last: Bool = false, @ViewBuilder control: () -> C) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let detail { Text(detail).font(.system(size: 12)).foregroundStyle(Theme.muted) }
            }
            Spacer()
            control()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .overlay(alignment: .bottom) { if !last { Rectangle().fill(Theme.line).frame(height: 1) } }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased()).font(.system(size: 10.5)).tracking(0.6).foregroundStyle(Theme.faint)
            Text(value).font(.system(size: 14, weight: .semibold, design: .monospaced))
        }
    }

    private func shortcut(_ keys: String, _ text: String) -> some View {
        HStack(spacing: 12) {
            Text(keys).font(.system(size: 12, design: .monospaced)).frame(width: 80, alignment: .leading)
                .foregroundStyle(Theme.accent)
            Text(text).foregroundStyle(Theme.muted)
        }
    }
}
