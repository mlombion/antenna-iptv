import SwiftUI

struct PhoneSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmSignOut = false

    var body: some View {
        @Bindable var settings = model.settings
        NavigationStack {
            Form {
                Section("Account") {
                    HStack(spacing: 12) {
                        Avatar(name: model.account?.name ?? "IPTV", size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.account?.name ?? "IPTV").font(.system(size: 16, weight: .semibold))
                            Text("\(model.account?.username ?? "") · \(URL(string: model.account?.server ?? "")?.host() ?? "")")
                                .font(.system(size: 13)).foregroundStyle(Theme.muted)
                        }
                    }
                    LabeledContent("Channels", value: model.channels.count.formatted())
                    LabeledContent("Status", value: model.accountInfo?.status ?? "—")
                    LabeledContent("Expires", value: model.accountInfo?.expiry?.formatted(.dateTime.day().month(.abbreviated).year()) ?? "Never")
                    if let max = model.accountInfo?.maxConnections {
                        LabeledContent("Screens at once", value: "\(max)")
                    }
                    Button(model.isRefreshing ? "Refreshing…" : "Refresh channels") { Task { await model.refresh(silent: false) } }
                        .disabled(model.isRefreshing)
                    Button("Switch account") { model.beginSwitchAccount() }
                }

                Section {
                    Button { model.showRemote = true } label: {
                        Label("Phone remote for your TV", systemImage: "av.remote")
                    }
                } footer: {
                    Text("Use this iPhone as a trackpad or air mouse for the whole TV or projector, not just Antenna. Turn on \"Antenna phone remote\" once in the TV's accessibility settings.")
                }

                Section {
                    Picker("Rewind buffer", selection: $settings.rewindMinutes) {
                        Text("Off").tag(0)
                        Text("15 minutes").tag(15)
                        Text("30 minutes").tag(30)
                        Text("1 hour").tag(60)
                    }
                    Picker("Stream format", selection: $settings.format) {
                        ForEach(StreamFormat.allCases) { Text($0.label).tag($0) }
                    }
                    Picker("Buffer size", selection: $settings.buffer) {
                        ForEach(BufferSize.allCases) { Text($0.label).tag($0) }
                    }
                    Toggle("Hardware decoding", isOn: $settings.hardwareDecoding)
                    Toggle("Reconnect automatically", isOn: $settings.autoReconnect)
                } header: {
                    Text("Playback")
                } footer: {
                    Text("Rewind keeps the channel you're watching on your phone so you can pause and go back. It needs MPEG-TS and uses about 2 GB per 30 minutes of HD, deleted when you change channel.")
                }

                Section("Interface") {
                    Toggle("Hide adult categories", isOn: $settings.hideAdult)
                }

                Section("Watching") {
                    tip("hand.tap", "Tap the video to show controls")
                    tip("hand.draw", "Swipe up or down to change channel")
                    tip("arrow.left.and.right", "Double-tap left or right to rewind or skip 10 s")
                    tip("star", "Swipe left on a channel to add it to Favorites")
                }

                Section {
                    Button("Sign out", role: .destructive) { confirmSignOut = true }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .navigationTitle("Settings")
            .confirmationDialog("Sign out of \(model.account?.name ?? "this account")?", isPresented: $confirmSignOut,
                                titleVisibility: .visible) {
                Button("Sign out", role: .destructive) { model.signOut() }
            } message: {
                Text("Your login is removed from this phone. Favorites stay.")
            }
        }
    }

    private func tip(_ symbol: String, _ text: String) -> some View {
        Label(text, systemImage: symbol).foregroundStyle(Theme.muted)
    }
}
