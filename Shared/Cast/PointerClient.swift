import Foundation
import Network
import Observation

/// The phone remote's line to a TV's pointer service (port 8782): cursor moves, clicks, scrolling, the remote's
/// buttons and typing, for the whole TV. Moves are gathered and sent up to 120 times a second.
@MainActor @Observable
final class PointerClient {
    enum State: Equatable {
        case idle, connecting, connected
        case failed(String)
    }

    static let port: UInt16 = 8782

    private(set) var state: State = .idle
    private(set) var device: CastManager.Device?

    @ObservationIgnored private var connection: NWConnection?
    @ObservationIgnored private var pendingMove = CGPoint.zero
    @ObservationIgnored private var pendingScroll = CGPoint.zero
    @ObservationIgnored private var flushTask: Task<Void, Never>?

    func connect(to device: CastManager.Device, using cast: CastManager) {
        if self.device == device, state == .connected || state == .connecting { return }
        disconnect()
        self.device = device
        state = .connecting
        Task {
            do {
                let host = try await cast.host(of: device)
                guard self.device == device else { return }
                open(host: host)
            } catch {
                state = .failed((error as? LocalizedError)?.errorDescription ?? "Couldn't reach the TV.")
            }
        }
    }

    private func open(host: String) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 5
        let c = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: Self.port)!, using: NWParameters(tls: nil, tcp: tcp))
        connection = c
        c.stateUpdateHandler = { [weak self] st in
            Task { @MainActor in
                guard let self, self.connection === c else { return }
                switch st {
                case .ready:
                    self.state = .connected
                    self.send("show")
                case .failed, .waiting:
                    self.state = .failed("The phone remote is off on this TV. On the TV, open Antenna → Settings → Phone remote, or Settings → Accessibility → Antenna phone remote, and turn it on.")
                    c.cancel()
                case .cancelled:
                    if self.state == .connected { self.state = .idle }
                default: break
                }
            }
        }
        c.start(queue: .main)
        flushTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(8))
                self?.flush()
            }
        }
    }

    func disconnect() {
        flushTask?.cancel()
        connection?.cancel()
        connection = nil
        device = nil
        state = .idle
    }

    // MARK: Commands

    /// Cursor movement in phone points (the TV scales it to its screen).
    func move(dx: CGFloat, dy: CGFloat) { pendingMove.x += dx; pendingMove.y += dy }
    /// A two-finger drag; the TV swipes the same way, so content follows the fingers.
    func scroll(dx: CGFloat, dy: CGFloat) { pendingScroll.x += dx; pendingScroll.y += dy }
    func click() { flush(); send("c") }
    func longPress() { flush(); send("lc") }
    func center() { pendingMove = .zero; send("center") }
    /// back, home, up, down, left, right, ok, recents, notifications
    func key(_ name: String) { send("k \(name)") }
    /// up, down, mute
    func volume(_ dir: String) { send("v \(dir)") }
    func type(_ text: String) { send("t " + Data(text.utf8).base64EncodedString()) }
    func enter() { send("enter") }

    private func flush() {
        if pendingMove != .zero {
            send(String(format: "m %.1f %.1f", pendingMove.x, pendingMove.y))
            pendingMove = .zero
        }
        if pendingScroll != .zero {
            send(String(format: "s %.1f %.1f", pendingScroll.x, pendingScroll.y))
            pendingScroll = .zero
        }
    }

    private func send(_ line: String) {
        guard let connection, state == .connected else { return }
        connection.send(content: Data((line + "\n").utf8), completion: .idempotent)
    }
}
