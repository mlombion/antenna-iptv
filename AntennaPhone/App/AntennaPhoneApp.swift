import AVFoundation
import SwiftUI

@main
struct AntennaPhoneApp: App {
    @State private var model = AppModel()

    init() {
        // Channel logos are fetched through AsyncImage, which uses the shared cache.
        URLCache.shared = URLCache(memoryCapacity: 48 << 20, diskCapacity: 256 << 20)
        // Play sound with the silent switch on, and keep it going when the phone locks.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            PhoneRootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .task {
                    if model.pipPresenter == nil {
                        model.pipPresenter = PhonePiPPresenter(model: model)
                        model.player.onPiP = { [weak model] event in model?.handleSystemPiP(event) }
                        model.vod.onPiP = { [weak model] event in model?.handleMoviePiP(event) }
                    }
                    await model.start()
                }
        }
    }
}

/// Starts and stops the system picture-in-picture window; the window itself appears asynchronously.
@MainActor
final class PhonePiPPresenter: PiPPresenting, MoviePiPPresenting {
    private unowned let model: AppModel
    init(model: AppModel) { self.model = model }

    var movieActivatesImmediately: Bool { false }
    func showMovie() { model.vod.startPictureInPicture() }
    func hideMovie() { model.vod.stopPictureInPicture() }

    var activatesImmediately: Bool { false }
    func show() { model.player.startPictureInPicture() }
    func hide() { model.player.stopPictureInPicture() }
    func updateAspectRatio(_ size: CGSize) {}
    func bringAppForward() {}
}

/// Hosts the shared VLC video view inside SwiftUI.
struct VideoSurface: UIViewRepresentable {
    let player: PlayerController

    func makeUIView(context: Context) -> UIView {
        let host = UIView()
        host.backgroundColor = .black
        attach(to: host)
        return host
    }

    func updateUIView(_ host: UIView, context: Context) {
        if player.videoView.superview !== host { attach(to: host) }
    }

    private func attach(to host: UIView) {
        let v = player.videoView
        v.removeFromSuperview()
        v.frame = host.bounds
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.addSubview(v)
    }
}
