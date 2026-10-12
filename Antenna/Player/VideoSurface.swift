import AppKit
import SwiftUI

/// Hosts the shared VLC video view inside SwiftUI.
struct VideoSurface: NSViewRepresentable {
    let player: PlayerController
    let role: PlayerController.Surface
    /// Passed in from the parent's body so a change of owner triggers an update here.
    let owner: PlayerController.Surface

    func makeNSView(context: Context) -> NSView {
        let host = NSView()
        host.wantsLayer = true
        host.layer?.backgroundColor = NSColor.black.cgColor
        if owner == role { attach(to: host) }
        return host
    }

    func updateNSView(_ host: NSView, context: Context) {
        // A view that is fading out must not pull the video back from the window that now owns it.
        if owner == role, player.videoView.superview !== host { attach(to: host) }
    }

    private func attach(to host: NSView) {
        let v = player.videoView
        v.removeFromSuperview()
        v.frame = host.bounds
        v.autoresizingMask = [.width, .height]
        host.addSubview(v)
    }
}
