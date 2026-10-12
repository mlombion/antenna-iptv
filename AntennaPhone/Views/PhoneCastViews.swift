import SwiftUI

/// "Play on TV" button shown below the player.
struct PlayOnTVButton: View {
    @Environment(AppModel.self) private var model
    @State private var showPicker = false

    var body: some View {
        Button { showPicker = true } label: {
            HStack(spacing: 8) {
                Image(systemName: "tv.and.mediabox")
                Text("Play on TV").font(.system(size: 14, weight: .semibold))
            }
            .padding(.horizontal, 16)
            .frame(height: 38)
            .background(.white.opacity(0.14), in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.18)))
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $showPicker) { CastPicker().environment(model) }
    }
}

/// Lists the Antenna TVs found on the Wi-Fi.
struct CastPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.cast.devices.isEmpty {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Looking for TVs on your Wi-Fi…").foregroundStyle(Theme.muted)
                        }
                        .listRowBackground(Theme.surface2)
                    }
                    ForEach(model.cast.devices) { tv in
                        Button {
                            if model.playing != nil { model.castCurrent(to: tv) }
                            dismiss()
                        } label: {
                            HStack(spacing: 14) {
                                Image(systemName: "tv")
                                    .font(.system(size: 20))
                                    .foregroundStyle(Theme.accent)
                                    .frame(width: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(tv.displayName).font(.system(size: 16, weight: .semibold))
                                    Text("Antenna IPTV").font(.system(size: 12)).foregroundStyle(Theme.muted)
                                }
                                Spacer()
                                if model.cast.active == tv { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Theme.surface2)
                    }
                } footer: {
                    Text("The TV plays the channel itself, straight from your provider, and this phone becomes the remote. Antenna IPTV needs to be installed on the TV, on the same Wi-Fi.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .navigationTitle("Play on TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium])
        .preferredColorScheme(.dark)
        .onAppear { model.cast.startBrowsing() }
    }
}

/// Bar above the tabs while a TV is playing: what's on, channel up/down and stop.
struct CastingBar: View {
    @Environment(AppModel.self) private var model
    @State private var showRemote = false

    var body: some View {
        if let tv = model.cast.active, let vod = model.cast.status?.vod {
            Button { showRemote = true } label: {
                HStack(spacing: 12) {
                    AsyncImage(url: vod.poster.flatMap(URL.init(string:))) { $0.resizable().scaledToFill() } placeholder: { Theme.surface3 }
                        .frame(width: 30, height: 44).clipShape(RoundedRectangle(cornerRadius: 4))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(vod.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                        Label(vod.subtitle.map { "\($0) · \(tv.displayName)" } ?? "Playing on \(tv.displayName)", systemImage: "tv")
                            .font(.system(size: 11.5)).foregroundStyle(Theme.accent).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    barButton(vod.state == "paused" ? "play.fill" : "pause.fill") { model.cast.command("pause") }
                    barButton("stop.fill") { model.stopCasting(resumeHere: false) }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.accent.opacity(0.35)))
                .padding(.horizontal, 12)
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $showRemote) { CastRemote().environment(model) }
        } else if let tv = model.cast.active {
            Button { showRemote = true } label: {
                HStack(spacing: 12) {
                    if let ch = model.castingChannel {
                        ChannelLogo(channel: ch, width: 44, height: 33)
                    } else {
                        Image(systemName: "tv").frame(width: 44, height: 33)
                    }
                    VStack(alignment: .leading, spacing: 1) {
                        Text(model.cast.status?.playing?.name ?? model.castingChannel?.displayName ?? "Starting…")
                            .font(.system(size: 14, weight: .semibold)).lineLimit(1)
                        Label("Playing on \(tv.displayName)", systemImage: "tv")
                            .font(.system(size: 11.5)).foregroundStyle(Theme.accent).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    barButton("chevron.up") { model.castZap(-1) }
                    barButton("chevron.down") { model.castZap(1) }
                    barButton("stop.fill") { model.stopCasting(resumeHere: false) }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.accent.opacity(0.35)))
                .padding(.horizontal, 12)
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $showRemote) { CastRemote().environment(model) }
        }
    }

    private func barButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 15, weight: .semibold)).frame(width: 36, height: 36)
                .background(Theme.surface3, in: Circle())
        }
        .buttonStyle(.plain)
    }
}

/// Full remote for the TV: what's on, the TV's rewind timeline, playback, volume and channels.
struct CastRemote: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showChannels = false
    @State private var dragFraction: Double?

    var body: some View {
        if let vod = model.cast.status?.vod {
            VodRemote(vod: vod).environment(model)
        } else {
            channelRemote
        }
    }

    private var channelRemote: some View {
        let status = model.cast.status
        return ScrollView {
            VStack(spacing: 20) {
                if let tv = model.cast.active {
                    Label("Playing on \(tv.displayName)", systemImage: "tv").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.accent)
                        .padding(.top, 20)
                }
                if let ch = model.castingChannel {
                    HStack(spacing: 14) {
                        ChannelLogo(channel: ch, width: 72, height: 54)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(status?.playing?.name ?? ch.displayName).font(.system(size: 18, weight: .bold)).lineLimit(1)
                            Text(status?.playing?.title ?? model.schedule(for: ch).current?.title ?? " ")
                                .font(.system(size: 14)).foregroundStyle(Theme.muted).lineLimit(1)
                            Text(stateText(status?.state)).font(.system(size: 12)).foregroundStyle(Theme.faint)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 24)
                }

                if let t = status?.timeline { timeline(t) }

                // Playback
                HStack(spacing: 14) {
                    smallButton("gobackward.30", "Back 30 seconds") { model.cast.command("back30") }
                    smallButton("gobackward.10", "Back 10 seconds") { model.cast.command("back10") }
                    Button { model.cast.command("pause") } label: {
                        Image(systemName: status?.state == "paused" ? "play.fill" : "pause.fill")
                            .font(.system(size: 28, weight: .semibold))
                            .frame(width: 76, height: 76)
                            .background(Theme.accent, in: Circle())
                            .foregroundStyle(Theme.accentInk)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Pause")
                    smallButton("goforward.10", "Forward 10 seconds") { model.cast.command("fwd10") }
                    smallButton("goforward.30", "Forward 30 seconds") { model.cast.command("fwd30") }
                }
                if let t = status?.timeline, !t.live {
                    Button { model.cast.command("live") } label: {
                        Label("Go live", systemImage: "forward.end.fill").font(.system(size: 14, weight: .semibold))
                            .padding(.horizontal, 18).frame(height: 36)
                            .background(Theme.live.opacity(0.18), in: Capsule())
                            .foregroundStyle(Theme.live)
                    }
                    .buttonStyle(.plain)
                }

                // Channels and volume, like the two rockers on a TV remote.
                HStack(spacing: 18) {
                    rocker(title: "CH", up: ("chevron.up", { model.castZap(-1) }), down: ("chevron.down", { model.castZap(1) }))
                    VStack(spacing: 10) {
                        Button { showChannels = true } label: {
                            VStack(spacing: 4) {
                                Image(systemName: "list.bullet").font(.system(size: 20, weight: .semibold))
                                Text("Channels").font(.system(size: 12, weight: .medium))
                            }
                            .frame(width: 96, height: 72)
                            .background(Theme.surface3, in: RoundedRectangle(cornerRadius: 16))
                        }
                        .buttonStyle(.plain)
                        Button { model.cast.command("mute") } label: {
                            Label("Mute", systemImage: "speaker.slash.fill").font(.system(size: 12, weight: .medium))
                                .frame(width: 96, height: 40)
                                .background(Theme.surface3, in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                    }
                    rocker(title: "VOL", up: ("plus", { model.cast.command("volumeUp") }), down: ("minus", { model.cast.command("volumeDown") }))
                }

                VStack(spacing: 10) {
                    Button {
                        model.stopCasting(resumeHere: true)
                        dismiss()
                    } label: {
                        Label("Watch on iPhone instead", systemImage: "iphone")
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Theme.surface3, in: RoundedRectangle(cornerRadius: 12))
                    }
                    Button(role: .destructive) {
                        model.stopCasting(resumeHere: false)
                        dismiss()
                    } label: {
                        Label("Stop on TV", systemImage: "stop.fill")
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Theme.live.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                    }
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            }
        }
        .presentationDetents([.large])
        .preferredColorScheme(.dark)
        .background(Theme.bg)
        .sheet(isPresented: $showChannels) { remoteChannelList }
    }

    /// The TV's recording, with a draggable position like the phone's own player.
    private func timeline(_ t: CastManager.Status.Timeline) -> some View {
        let fraction = dragFraction ?? (t.live ? 1 : (t.playheadMs - t.startMs) / t.lengthMs)
        return VStack(spacing: 6) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.18)).frame(height: 6)
                    Capsule().fill(t.live ? Theme.live : Theme.accent).frame(width: max(6, g.size.width * fraction), height: 6)
                    Circle().fill(.white).frame(width: 18, height: 18).offset(x: max(0, g.size.width * fraction - 9))
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { dragFraction = min(1, max(0, $0.location.x / g.size.width)) }
                    .onEnded { v in
                        let f = min(1, max(0, v.location.x / g.size.width))
                        dragFraction = nil
                        if f > 0.98 { model.cast.command("live") }
                        else { model.cast.command("seekTo", ["time": Int64(t.startMs + f * t.lengthMs)]) }
                    })
            }
            .frame(height: 24)
            HStack {
                Text("−" + RewindBar.format(t.lengthMs / 1000)).foregroundStyle(Theme.faint)
                Spacer()
                Text(t.live ? "LIVE" : "−" + RewindBar.format(t.behindMs / 1000)).foregroundStyle(t.live ? Theme.live : Theme.fg)
            }
            .font(.system(size: 12, weight: .medium, design: .monospaced))
        }
        .padding(.horizontal, 24)
    }

    private var remoteChannelList: some View {
        let list = model.zapList.isEmpty ? model.channels(in: LiveCategory.allID) : model.zapList
        return NavigationStack {
            ScrollViewReader { proxy in
                List(list) { ch in
                    Button {
                        model.play(ch, in: list)
                        showChannels = false
                    } label: { PhoneChannelRow(channel: ch) }
                        .buttonStyle(.plain)
                        .listRowBackground(ch.id == model.castingChannel?.id ? Theme.surface3 : Theme.bg)
                        .id(ch.id)
                }
                .antennaList()
                .onAppear { if let id = model.castingChannel?.id { proxy.scrollTo(id, anchor: .center) } }
            }
            .navigationTitle("Play on TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showChannels = false } } }
        }
        .presentationDetents([.large])
        .preferredColorScheme(.dark)
    }

    private func stateText(_ state: String?) -> String {
        switch state {
        case "playing": "Playing"
        case "opening": "Starting…"
        case "paused": "Paused"
        case "failed": "The TV couldn't play this channel"
        case "closed": "Antenna is closed on the TV"
        default: " "
        }
    }

    private func smallButton(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 20, weight: .semibold))
                .frame(width: 52, height: 52)
                .background(Theme.surface3, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func rocker(title: String, up: (String, () -> Void), down: (String, () -> Void)) -> some View {
        VStack(spacing: 0) {
            Button(action: up.1) { Image(systemName: up.0).font(.system(size: 18, weight: .bold)).frame(width: 64, height: 52) }
            Text(title).font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.faint)
            Button(action: down.1) { Image(systemName: down.0).font(.system(size: 18, weight: .bold)).frame(width: 64, height: 52) }
        }
        .buttonStyle(.plain)
        .background(Theme.surface3, in: Capsule())
    }
}


/// The remote while a movie or episode from Movies & Series plays on the TV.
private struct VodRemote: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let vod: CastManager.Status.Vod
    @State private var dragFraction: Double?

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                if let tv = model.cast.active {
                    Label("Playing on \(tv.displayName)", systemImage: "tv").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.accent)
                        .padding(.top, 20)
                }
                HStack(spacing: 14) {
                    AsyncImage(url: vod.poster.flatMap(URL.init(string:))) { $0.resizable().scaledToFill() } placeholder: { Theme.surface3 }
                        .frame(width: 60, height: 90).clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(vod.title).font(.system(size: 19, weight: .bold)).lineLimit(2)
                        if let sub = vod.subtitle { Text(sub).font(.system(size: 14)).foregroundStyle(Theme.muted).lineLimit(2) }
                        Text(stateText).font(.system(size: 12)).foregroundStyle(vod.error != nil ? Theme.live : Theme.faint).lineLimit(3)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 24)

                if vod.durationMs > 0 { scrubber }

                HStack(spacing: 14) {
                    small("gobackward.30") { model.cast.command("back30") }
                    small("gobackward.10") { model.cast.command("back10") }
                    Button { model.cast.command("pause") } label: {
                        Image(systemName: vod.state == "paused" ? "play.fill" : "pause.fill")
                            .font(.system(size: 28, weight: .semibold))
                            .frame(width: 76, height: 76)
                            .background(Theme.accent, in: Circle())
                            .foregroundStyle(Theme.accentInk)
                    }
                    .buttonStyle(.plain)
                    small("goforward.10") { model.cast.command("fwd10") }
                    small("goforward.30") { model.cast.command("fwd30") }
                }

                HStack(spacing: 10) {
                    trackMenu("Subtitles", "captions.bubble", vod.subtitles, action: "subtitle")
                    trackMenu("Audio", "waveform", vod.audio, action: "audio")
                    if vod.hasNext {
                        Button { model.cast.command("next") } label: {
                            chip("Next episode", "forward.end.fill")
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 24)

                HStack(spacing: 12) {
                    Button { model.cast.command("volumeDown") } label: { chip("Vol −", "speaker.wave.1.fill") }
                    Button { model.cast.command("mute") } label: { chip("Mute", "speaker.slash.fill") }
                    Button { model.cast.command("volumeUp") } label: { chip("Vol +", "speaker.wave.3.fill") }
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24)

                Button(role: .destructive) {
                    model.stopCasting(resumeHere: false)
                    dismiss()
                } label: {
                    Label("Stop on TV", systemImage: "stop.fill")
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(Theme.live.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            }
        }
        .presentationDetents([.large])
        .preferredColorScheme(.dark)
        .background(Theme.bg)
    }

    private var stateText: String {
        if let e = vod.error { return e }
        switch vod.state {
        case "opening": return vod.step ?? "Starting…"
        case "paused": return "Paused"
        case "ended": return vod.hasNext ? "Finished · Next episode is ready" : "Finished"
        case "playing": return "Playing"
        default: return " "
        }
    }

    private var scrubber: some View {
        let fraction = dragFraction ?? min(1, max(0, vod.positionMs / vod.durationMs))
        return VStack(spacing: 6) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.18)).frame(height: 6)
                    Capsule().fill(Theme.accent).frame(width: max(6, g.size.width * fraction), height: 6)
                    Circle().fill(.white).frame(width: 18, height: 18).offset(x: max(0, g.size.width * fraction - 9))
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { dragFraction = min(1, max(0, $0.location.x / g.size.width)) }
                    .onEnded { v in
                        let f = min(1, max(0, v.location.x / g.size.width))
                        dragFraction = nil
                        model.cast.command("seekTo", ["position": Int64(f * vod.durationMs)])
                    })
            }
            .frame(height: 24)
            HStack {
                Text(RewindBar.format(fraction * vod.durationMs / 1000))
                Spacer()
                Text("−" + RewindBar.format((1 - fraction) * vod.durationMs / 1000)).foregroundStyle(Theme.faint)
            }
            .font(.system(size: 12, weight: .medium, design: .monospaced))
        }
        .padding(.horizontal, 24)
    }

    private func trackMenu(_ title: String, _ symbol: String, _ tracks: [CastManager.Status.Vod.Track], action: String) -> some View {
        Menu {
            ForEach(tracks) { t in
                Button { model.cast.command(action, ["key": t.key]) } label: {
                    if t.on { Label(t.label, systemImage: "checkmark") } else { Text(t.label) }
                }
            }
        } label: { chip(title, symbol) }
        .disabled(tracks.isEmpty)
    }

    private func chip(_ title: String, _ symbol: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).font(.system(size: 17, weight: .semibold))
            Text(title).font(.system(size: 11.5, weight: .medium)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, minHeight: 60)
        .background(Theme.surface3, in: RoundedRectangle(cornerRadius: 14))
        .foregroundStyle(Theme.fg)
    }

    private func small(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 20, weight: .semibold))
                .frame(width: 52, height: 52)
                .background(Theme.surface3, in: Circle())
        }
        .buttonStyle(.plain)
    }
}
