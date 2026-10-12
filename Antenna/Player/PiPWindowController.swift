import AppKit
import SwiftUI

/// Floating video window that stays above other apps, on every Space and over full-screen apps.
/// System picture-in-picture only works with AVPlayer, and playback here is VLC, so this is our own panel.
@MainActor
final class PiPWindowController: PiPPresenting, MoviePiPPresenting {
    private unowned let model: AppModel
    private var panel: PiPPanel?
    private static let frameName = "AntennaPictureInPicture"

    init(model: AppModel) { self.model = model }

    var isVisible: Bool { panel?.isVisible ?? false }

    func show() {
        let panel = self.panel ?? makePanel()
        self.panel = panel
        // A fresh hosting view each time, so the shared video view is re-attached here.
        panel.contentView = PiPHostingView(rootView: PiPView().environment(model))
        updateAspectRatio(model.player.videoSize)
        panel.orderFrontRegardless()
        panel.makeKey()
    }

    func hide() {
        guard let panel else { return }
        panel.orderOut(nil)
        panel.contentView = nil
    }

    // Movies & Series use the same floating window.
    var movieActivatesImmediately: Bool { true }

    func showMovie() {
        let panel = self.panel ?? makePanel()
        self.panel = panel
        panel.contentView = PiPHostingView(rootView: MoviePiPView().environment(model))
        updateAspectRatio(model.vod.videoSize)
        panel.orderFrontRegardless()
        panel.makeKey()
    }

    func hideMovie() { hide() }

    func bringAppForward() {
        NSApp.activate()
        NSApp.windows.first { !($0 is NSPanel) && $0.canBecomeMain }?.makeKeyAndOrderFront(nil)
    }

    func updateAspectRatio(_ size: CGSize) {
        guard let panel else { return }
        let ratio = size.width > 0 && size.height > 0 ? NSSize(width: size.width, height: size.height) : NSSize(width: 16, height: 9)
        panel.contentAspectRatio = ratio
        // Snap the current frame to the new shape, keeping the width.
        var frame = panel.frame
        let newHeight = (frame.width * ratio.height / ratio.width).rounded()
        if abs(newHeight - frame.height) > 1 {
            frame.origin.y += frame.height - newHeight
            frame.size.height = newHeight
            panel.setFrame(frame, display: true, animate: false)
        }
    }

    private func makePanel() -> PiPPanel {
        let panel = PiPPanel(contentRect: NSRect(x: 0, y: 0, width: 480, height: 270),
                             styleMask: [.titled, .resizable, .fullSizeContentView, .nonactivatingPanel],
                             backing: .buffered, defer: false)
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        for b in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] { panel.standardWindowButton(b)?.isHidden = true }
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isMovableByWindowBackground = true
        panel.backgroundColor = .black
        panel.hasShadow = true
        panel.minSize = NSSize(width: 240, height: 135)
        panel.isReleasedWhenClosed = false
        if !panel.setFrameUsingName(Self.frameName) {
            let screen = NSApp.mainWindow?.screen ?? NSScreen.main
            let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
            panel.setFrame(NSRect(x: visible.maxX - 480 - 24, y: visible.minY + 24, width: 480, height: 270), display: false)
        }
        panel.setFrameAutosaveName(Self.frameName)
        return panel
    }
}

final class PiPPanel: NSPanel {
    // Lets keyboard shortcuts reach the floating window without bringing the whole app forward.
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

/// Buttons in the floating window respond to the first click even when Antenna isn't the active app.
final class PiPHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

/// Transparent layer that moves the window when dragged, returns to the app on double-click, and reports hover.
/// Its tracking area works even while another app is active, which SwiftUI's hover does not guarantee.
struct WindowDragArea: NSViewRepresentable {
    var onDoubleClick: () -> Void
    var onHover: (Bool) -> Void

    func makeNSView(context: Context) -> DragView {
        let v = DragView()
        v.onDoubleClick = onDoubleClick
        v.onHover = onHover
        return v
    }

    func updateNSView(_ v: DragView, context: Context) {
        v.onDoubleClick = onDoubleClick
        v.onHover = onHover
    }

    final class DragView: NSView {
        var onDoubleClick: (() -> Void)?
        var onHover: ((Bool) -> Void)?
        override var mouseDownCanMoveWindow: Bool { true }
        override func updateTrackingAreas() {
            super.updateTrackingAreas()
            trackingAreas.forEach(removeTrackingArea)
            addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                           owner: self, userInfo: nil))
        }
        override func mouseEntered(with event: NSEvent) { onHover?(true) }
        override func mouseExited(with event: NSEvent) { onHover?(false) }
        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
        override func mouseDown(with event: NSEvent) {
            if event.clickCount == 2 { onDoubleClick?() } else { window?.performDrag(with: event) }
        }
    }
}
