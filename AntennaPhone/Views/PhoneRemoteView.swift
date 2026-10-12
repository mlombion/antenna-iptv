import CoreMotion
import SwiftUI
import UIKit

/// The phone as a remote for the whole TV: a trackpad, an air mouse (point the phone), or the remote's buttons.
/// Talks to the TV's "Antenna phone remote" service, so it works in every app on the TV, not just Antenna.
struct PhoneRemoteView: View {
    enum Mode: String, CaseIterable, Identifiable {
        case trackpad = "Trackpad", air = "Air mouse", buttons = "Buttons"
        var id: String { rawValue }
    }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @AppStorage("remoteMode") private var mode: Mode = .trackpad
    @State private var showKeyboard = false

    private var pointer: PointerClient { model.pointer }

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                tvPicker
                Picker("Mode", selection: $mode) { ForEach(Mode.allCases) { Text($0.rawValue).tag($0) } }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)

                if pointer.state == .connected && pointer.arrowMode && mode != .buttons {
                    Label("This app uses the remote's arrows: move to step the highlight, tap for OK.", systemImage: "arrow.up.and.down.and.arrow.left.and.right")
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(Theme.accentInk)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Theme.accent, in: RoundedRectangle(cornerRadius: 10))
                        .padding(.horizontal, 16)
                }
                Group {
                    switch pointer.state {
                    case .connected:
                        switch mode {
                        case .trackpad: TrackpadPanel(pointer: pointer)
                        case .air: AirMousePanel(pointer: pointer)
                        case .buttons: ButtonsPanel(pointer: pointer)
                        }
                    case .connecting:
                        ProgressView("Connecting to the TV…").frame(maxHeight: .infinity)
                    case .failed(let message):
                        VStack(spacing: 14) {
                            Image(systemName: "cursorarrow.rays").font(.system(size: 40)).foregroundStyle(Theme.accent)
                            Text(message).multilineTextAlignment(.center).foregroundStyle(Theme.muted)
                            Button("Try again") { reconnect() }.buttonStyle(PillButtonStyle(prominent: true))
                        }
                        .padding(28).frame(maxHeight: .infinity)
                    case .idle:
                        VStack(spacing: 12) {
                            ProgressView()
                            Text("Looking for TVs on your Wi-Fi…").foregroundStyle(Theme.muted)
                        }
                        .frame(maxHeight: .infinity)
                    }
                }

                bottomBar
            }
            .padding(.top, 8)
            .background(Theme.bg)
            .navigationTitle("Remote")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $showKeyboard) { KeyboardSheet(pointer: pointer) }
        }
        .preferredColorScheme(.dark)
        .onAppear { model.cast.startBrowsing(); reconnect() }
        .onChange(of: model.cast.devices) { _, _ in if pointer.device == nil { reconnect() } }
    }

    private func reconnect() {
        let devices = model.cast.devices
        guard let tv = pointer.device.flatMap({ d in devices.first { $0 == d } }) ?? model.cast.active ?? devices.first else { return }
        pointer.disconnect()
        pointer.connect(to: tv, using: model.cast)
    }

    private var tvPicker: some View {
        Menu {
            ForEach(model.cast.devices) { tv in
                Button { pointer.connect(to: tv, using: model.cast) } label: {
                    if tv == pointer.device { Label(tv.displayName, systemImage: "checkmark") } else { Text(tv.displayName) }
                }
            }
        } label: {
            HStack(spacing: 8) {
                Circle().fill(pointer.state == .connected ? Theme.ok : Theme.faint).frame(width: 8, height: 8)
                Image(systemName: "tv")
                Text(pointer.device?.displayName ?? "Choose a TV").font(.system(size: 15, weight: .semibold))
                Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.muted)
            }
            .padding(.horizontal, 14).frame(height: 36)
            .background(Theme.surface2, in: Capsule())
        }
        .foregroundStyle(Theme.fg)
    }

    private var bottomBar: some View {
        HStack(spacing: 10) {
            barButton("arrow.uturn.backward", "Back") { pointer.key("back") }
            barButton("house", "Home") { pointer.key("home") }
            barButton("keyboard", "Type") { showKeyboard = true }
            barButton("speaker.minus", "Vol −") { pointer.volume("down") }
            barButton("speaker.slash", "Mute") { pointer.volume("mute") }
            barButton("speaker.plus", "Vol +") { pointer.volume("up") }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .disabled(pointer.state != .connected)
        .opacity(pointer.state == .connected ? 1 : 0.4)
    }

    private func barButton(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 18, weight: .semibold))
                Text(label).font(.system(size: 10.5, weight: .medium))
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Theme.surface2, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Trackpad

private struct TrackpadPanel: View {
    let pointer: PointerClient

    var body: some View {
        VStack(spacing: 8) {
            TrackpadSurface(pointer: pointer)
                .background(
                    RoundedRectangle(cornerRadius: 22).fill(Theme.surface)
                        .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(Theme.line))
                )
                .overlay {
                    VStack(spacing: 6) {
                        Image(systemName: "hand.point.up.left").font(.system(size: 28)).foregroundStyle(Theme.faint)
                        Text("Slide to move · tap to click · hold to long-press\nTwo fingers to scroll · two-finger tap to go back")
                            .font(.system(size: 12.5)).multilineTextAlignment(.center).foregroundStyle(Theme.faint)
                    }
                    .allowsHitTesting(false)
                }
                .padding(.horizontal, 16)
        }
        .frame(maxHeight: .infinity)
    }
}

/// UIKit gestures, which give the precise movement deltas a trackpad needs.
private struct TrackpadSurface: UIViewRepresentable {
    let pointer: PointerClient

    func makeCoordinator() -> Coordinator { Coordinator(pointer: pointer) }

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .clear
        let c = context.coordinator
        let move = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.move(_:)))
        move.maximumNumberOfTouches = 1
        let scroll = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.scroll(_:)))
        scroll.minimumNumberOfTouches = 2
        let tap = UITapGestureRecognizer(target: c, action: #selector(Coordinator.tap(_:)))
        let twoTap = UITapGestureRecognizer(target: c, action: #selector(Coordinator.back(_:)))
        twoTap.numberOfTouchesRequired = 2
        let hold = UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.hold(_:)))
        hold.minimumPressDuration = 0.55
        hold.allowableMovement = 8
        tap.require(toFail: hold)
        [move, scroll, tap, twoTap, hold].forEach(v.addGestureRecognizer)
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) { context.coordinator.pointer = pointer }

    final class Coordinator: NSObject {
        var pointer: PointerClient
        private var last = CGPoint.zero
        private var lastTime = CACurrentMediaTime()
        private var lastScroll = CGPoint.zero
        private let haptic = UIImpactFeedbackGenerator(style: .light)

        init(pointer: PointerClient) { self.pointer = pointer }

        @MainActor @objc func move(_ g: UIPanGestureRecognizer) {
            let t = g.translation(in: g.view)
            if g.state == .began { last = .zero; lastTime = CACurrentMediaTime() }
            let d = CGPoint(x: t.x - last.x, y: t.y - last.y)
            last = t
            let now = CACurrentMediaTime()
            let speed = hypot(d.x, d.y) / max(0.001, now - lastTime)
            lastTime = now
            // Pointer acceleration: slow movements are precise, quick flicks cross the screen.
            let gain = 1 + min(speed / 700, 2.2)
            pointer.move(dx: d.x * gain, dy: d.y * gain)
        }

        @MainActor @objc func scroll(_ g: UIPanGestureRecognizer) {
            let t = g.translation(in: g.view)
            if g.state == .began { lastScroll = .zero }
            pointer.scroll(dx: t.x - lastScroll.x, dy: t.y - lastScroll.y)
            lastScroll = t
        }

        @MainActor @objc func tap(_ g: UITapGestureRecognizer) { haptic.impactOccurred(); pointer.click() }
        @MainActor @objc func back(_ g: UITapGestureRecognizer) { haptic.impactOccurred(); pointer.key("back") }
        @MainActor @objc func hold(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            pointer.longPress()
        }
    }
}

// MARK: - Air mouse

/// Point the phone at the TV and turn or tilt it: the cursor follows, like a Wii remote or LG's Magic Remote.
/// The cursor only moves while your thumb is on the pad, so you can rest your arm; a quick tap clicks.
private struct AirMousePanel: View {
    let pointer: PointerClient
    @AppStorage("airSensitivity") private var sensitivity = 1.0
    @State private var motion = AirMotion()
    @State private var holding = false
    @State private var touchStart = Date.distantPast
    @State private var lastScroll: CGFloat = 0

    var body: some View {
        VStack(spacing: 12) {
            if !motion.available {
                Text("The air mouse needs a real iPhone (it uses the gyroscope).").foregroundStyle(Theme.muted).padding()
            }
            HStack(spacing: 12) {
                // Hold to aim; quick tap to click.
                RoundedRectangle(cornerRadius: 26)
                    .fill(holding ? Theme.accent.opacity(0.22) : Theme.surface)
                    .overlay(RoundedRectangle(cornerRadius: 26).strokeBorder(holding ? Theme.accent : Theme.line, lineWidth: holding ? 2 : 1))
                    .overlay {
                        VStack(spacing: 8) {
                            Image(systemName: holding ? "scope" : "hand.point.up.left.fill").font(.system(size: 34))
                                .foregroundStyle(holding ? Theme.accent : Theme.faint)
                            Text(holding ? "Point at the TV and move" : "Hold here and point the phone\nQuick tap to click")
                                .font(.system(size: 13)).multilineTextAlignment(.center).foregroundStyle(Theme.muted)
                        }
                    }
                    .gesture(DragGesture(minimumDistance: 0)
                        .onChanged { _ in
                            if !holding {
                                holding = true
                                touchStart = Date()
                                motion.start { dx, dy in pointer.move(dx: dx, dy: dy) }
                            }
                        }
                        .onEnded { _ in
                            holding = false
                            motion.stop()
                            if Date().timeIntervalSince(touchStart) < 0.25 {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                pointer.click()
                            }
                        })

                // Scroll strip: drag up and down.
                RoundedRectangle(cornerRadius: 18)
                    .fill(Theme.surface)
                    .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Theme.line))
                    .overlay {
                        VStack(spacing: 6) {
                            Image(systemName: "chevron.up"); Spacer()
                            Text("Scroll").font(.system(size: 11, weight: .semibold)).rotationEffect(.degrees(-90)).fixedSize()
                            Spacer(); Image(systemName: "chevron.down")
                        }
                        .foregroundStyle(Theme.faint).padding(.vertical, 18)
                    }
                    .frame(width: 64)
                    .gesture(DragGesture(minimumDistance: 0)
                        .onChanged { v in
                            pointer.scroll(dx: 0, dy: v.translation.height - lastScroll)
                            lastScroll = v.translation.height
                        }
                        .onEnded { _ in lastScroll = 0 })
            }
            .padding(.horizontal, 16)
            .frame(maxHeight: .infinity)

            HStack(spacing: 12) {
                Image(systemName: "tortoise").foregroundStyle(Theme.faint)
                Slider(value: $sensitivity, in: 0.4...2.2).tint(Theme.accent)
                Image(systemName: "hare").foregroundStyle(Theme.faint)
                Button { pointer.center() } label: {
                    Label("Center", systemImage: "scope").font(.system(size: 13, weight: .semibold))
                        .padding(.horizontal, 12).frame(height: 32).background(Theme.surface2, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
        }
        .onChange(of: sensitivity) { _, s in motion.sensitivity = s }
        .onAppear { motion.sensitivity = sensitivity }
        .onDisappear { motion.stop() }
    }
}

/// Turns the gyroscope into cursor movement. Turning the phone left or right moves the cursor sideways (rotation
/// around the vertical, whatever angle the phone is held at); tilting its top up or down moves it vertically.
@MainActor
final class AirMotion {
    private let manager = CMMotionManager()
    var sensitivity = 1.0
    var available: Bool { manager.isDeviceMotionAvailable }
    private var smoothX = 0.0
    private var smoothY = 0.0

    func start(onMove: @escaping (CGFloat, CGFloat) -> Void) {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        smoothX = 0; smoothY = 0
        let dt = 1.0 / 100
        manager.deviceMotionUpdateInterval = dt
        manager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] m, _ in
            guard let self, let m else { return }
            let w = m.rotationRate, g = m.gravity
            // "Up" in the phone's own axes, and the horizontal axis across the phone's pointing direction (its top).
            let up = (x: -g.x, y: -g.y, z: -g.z)
            var right = (x: 1 * up.z - 0 * up.y, y: 0 * up.x - 0 * up.z, z: 0 * up.y - 1 * up.x) // (0,1,0) × up
            let len = max(0.0001, sqrt(right.x * right.x + right.y * right.y + right.z * right.z))
            right = (right.x / len, right.y / len, right.z / len)
            var yaw = w.x * up.x + w.y * up.y + w.z * up.z          // turning left is positive
            var pitch = w.x * right.x + w.y * right.y + w.z * right.z // tilting the top up is positive
            // Ignore hand tremor, then smooth.
            if abs(yaw) < 0.015 { yaw = 0 }
            if abs(pitch) < 0.015 { pitch = 0 }
            self.smoothX = self.smoothX * 0.45 + yaw * 0.55
            self.smoothY = self.smoothY * 0.45 + pitch * 0.55
            let k = 950 * self.sensitivity * dt // points per radian, per sample
            onMove(CGFloat(-self.smoothX * k), CGFloat(-self.smoothY * k))
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }
}

// MARK: - Buttons

private struct ButtonsPanel: View {
    let pointer: PointerClient

    var body: some View {
        VStack(spacing: 22) {
            ZStack {
                Circle().fill(Theme.surface).frame(width: 250, height: 250)
                    .overlay(Circle().strokeBorder(Theme.line))
                VStack(spacing: 0) {
                    arrow("chevron.up", "up")
                    HStack(spacing: 0) {
                        arrow("chevron.left", "left")
                        Button { haptic(); pointer.key("ok") } label: {
                            Text("OK").font(.system(size: 20, weight: .bold)).frame(width: 96, height: 96)
                                .background(Theme.surface3, in: Circle())
                        }
                        .buttonStyle(.plain)
                        arrow("chevron.right", "right")
                    }
                    arrow("chevron.down", "down")
                }
            }
            Text("The remote's arrows and OK, for apps that don't respond to the cursor.")
                .font(.system(size: 12.5)).foregroundStyle(Theme.faint).multilineTextAlignment(.center).padding(.horizontal, 30)
        }
        .frame(maxHeight: .infinity)
    }

    private func arrow(_ symbol: String, _ key: String) -> some View {
        Button { haptic(); pointer.key(key) } label: {
            Image(systemName: symbol).font(.system(size: 24, weight: .bold)).frame(width: 76, height: 76).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func haptic() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
}

// MARK: - Keyboard

private struct KeyboardSheet: View {
    let pointer: PointerClient
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Select a search box on the TV first (move the cursor onto it and tap), then type here.")
                    .font(.system(size: 13)).foregroundStyle(Theme.muted)
                TextField("Type for the TV", text: $text)
                    .textFieldStyle(.roundedBorder)
                    .focused($focused)
                    .submitLabel(.search)
                    .onChange(of: text) { _, t in pointer.type(t) }
                    .onSubmit { pointer.enter(); dismiss() }
                HStack {
                    Button("Clear") { text = "" }.buttonStyle(PillButtonStyle())
                    Spacer()
                    Button("Search") { pointer.enter(); dismiss() }.buttonStyle(PillButtonStyle(prominent: true))
                }
                Spacer()
            }
            .padding(20)
            .background(Theme.bg)
            .navigationTitle("Type on TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium])
        .preferredColorScheme(.dark)
        .onAppear { focused = true }
    }
}
