#if DEBUG
import AppKit
import SwiftUI

/// Debug-only: with `-AntennaDevTour <folder>`, visits each screen and saves a PNG of the window,
/// so the UI can be checked without Screen Recording permission.
@MainActor
enum DevTour {
    static func runIfRequested(_ model: AppModel) {
        guard let dir = UserDefaults.standard.string(forKey: "AntennaDevTour") else { return }
        Task { @MainActor in
            func note(_ name: String, _ text: String) {
                try? text.write(toFile: "\(dir)/\(name).txt", atomically: true, encoding: .utf8)
            }
            @MainActor func shot(_ name: String, after seconds: Double = 2.0) async {
                try? await Task.sleep(for: .seconds(seconds))
                capture(to: "\(dir)/\(name).png")
            }
            if UserDefaults.standard.string(forKey: "AntennaDevTourMode") == "zap" {
                guard let ch = model.channels.dropFirst(16).first else { return }
                model.play(ch)
                try? await Task.sleep(for: .seconds(8))
                var lines = ["start \(model.playing?.displayName ?? "-") \(model.player.state) rewind=\(model.player.canRewind)"]
                for i in 1...3 {
                    model.zap(1)
                    try? await Task.sleep(for: .seconds(9))
                    lines.append("zap\(i) \(model.playing?.displayName ?? "-") \(model.player.state) rewind=\(model.player.canRewind) reason=\(model.player.rewindUnavailableReason ?? "-")")
                }
                note("zap", lines.joined(separator: "\n"))
                FileManager.default.createFile(atPath: "\(dir)/done", contents: nil)
                return
            }
            if UserDefaults.standard.string(forKey: "AntennaDevTourMode") == "rewind" {
                guard let ch = model.channels.dropFirst(16).first else { return }
                let p = model.player
                @MainActor func t() -> String {
                    guard let tl = p.timeline else { return "\(p.state) no timeline" }
                    return "\(p.state) buffer=\(Int(tl.length))s behind=\(Int(tl.behind))s behindLive=\(p.isBehindLive)"
                }
                model.play(ch)
                await shot("r1-live", after: 30)
                note("r1-live", t())
                p.seek(by: -20)
                await shot("r2-rewound-20", after: 2.5)
                note("r2-rewound-20", t())
                p.togglePause()
                try? await Task.sleep(for: .seconds(6))
                note("r3-paused-6s", t())
                p.togglePause()
                await shot("r3-resumed", after: 3)
                note("r3-resumed", t())
                p.goLive()
                await shot("r4-live-again", after: 2.5)
                note("r4-live-again", t())
                FileManager.default.createFile(atPath: "\(dir)/done", contents: nil)
                return
            }
            if UserDefaults.standard.string(forKey: "AntennaDevTourMode") == "pip" {
                guard let ch = model.channels.dropFirst(16).first else { return }
                model.play(ch)
                try? await Task.sleep(for: .seconds(4))
                model.enterPiP()
                model.go(.guide)
                await shot("p1-pip", after: 4)
                let v = model.player.videoView
                func tree(_ l: CALayer?, _ d: Int = 0) -> String {
                    guard let l else { return "" }
                    return String(repeating: "  ", count: d) + "\(type(of: l)) \(l.frame) hidden=\(l.isHidden) op=\(l.opacity)\n" + (l.sublayers ?? []).map { tree($0, d + 1) }.joined()
                }
                note("p1-pip", "\(model.player.state) pip=\(model.isPiP)\nview frame=\(v.frame) window=\(String(describing: v.window?.className)) super=\(String(describing: v.superview?.frame)) subviews=\(v.subviews.map { "\(type(of: $0)) \($0.frame)" })\n" + tree(v.layer))
                model.zap(1)
                await shot("p2-pip-zapped", after: 5)
                note("p2-pip-zapped", "\(model.playing?.displayName ?? "-") \(model.player.state)")
                model.exitPiP(returnToApp: true)
                await shot("p3-back-in-app", after: 3)
                note("p3-back-in-app", "\(model.player.state) pip=\(model.isPiP)")
                FileManager.default.createFile(atPath: "\(dir)/done", contents: nil)
                return
            }
            await shot("1-live", after: 3)
            model.moveSelection(3)
            await shot("2-live-moved", after: 1.5)
            model.go(.home);       await shot("3-home")
            model.go(.guide);      await shot("4-guide", after: 2.5)
            model.go(.favorites);  await shot("5-favorites")
            model.go(.settings);   await shot("6-settings")
            model.go(.live)
            model.showSearch = true; await shot("7-search", after: 1)
            model.showSearch = false
            if let ch = model.channels.first(where: { $0.displayName.contains("BEIN SPORTS 1") }) ?? model.channels.first {
                model.play(ch)
                await shot("8a-player-osd", after: 2.8)
                await shot("8-player", after: 4)
                note("8-player", "\(model.player.state) · \(model.player.videoSize) · audio \(model.player.audioTracks.map(\.name))")
                model.zap(1)
                await shot("8b-player-zapped", after: 4)
                note("8b-player-zapped", "\(model.playing?.displayName ?? "-") · \(model.player.state)")
            }
            if let broken = model.channel(number: 999) {
                model.play(broken)
                await shot("9-player-error", after: 7)
                note("9-player-error", "\(model.player.state)")
            }
            model.stopPlaying()
            model.beginSwitchAccount()
            await shot("10-login", after: 1.5)
            model.cancelSwitchAccount()
            FileManager.default.createFile(atPath: "\(dir)/done", contents: nil)
        }
    }

    /// Leaves a marker; the dev script takes the real window screenshot, then removes it.
    static func capture(to path: String) {
        let marker = path + ".pending"
        FileManager.default.createFile(atPath: marker, contents: nil)
        let deadline = Date().addingTimeInterval(5)
        while FileManager.default.fileExists(atPath: marker) && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
    }
}
#endif
