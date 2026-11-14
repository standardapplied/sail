import SailTerminal
import Synchronization

/// Follows a session's events and screen changes, so the probe, and a test, waits until what it
/// needs holds, within a deadline.
final class Follower: Sendable {
    private let session: TerminalSession
    private let tick = Alarm { try await Task.sleep(for: $0) }
    private let log = Mutex<[SessionEvent]>([])

    init(_ session: TerminalSession) {
        self.session = session
        Task { [self] in
            for await event in session.events {
                log.withLock { $0.append(event) }
                tick.ring()
            }
            tick.ring()
        }
        Task { [tick] in
            for await _ in session.screenChanges {
                tick.ring()
            }
        }
    }

    /// Every event the session told, in order.
    var seen: [SessionEvent] {
        log.withLock { $0 }
    }

    /// Waits until `holds`, failing after `seconds` naming `what`.
    func until(within seconds: Int = 5, _ what: String, _ holds: () throws -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while try !holds() {
            let left = deadline - ContinuousClock.now
            guard left > .zero else { throw ProbeError("the session never \(what) within \(seconds) s: \(session.state.phase)") }
            await tick.wait(left)
        }
    }

    func phase(_ phase: SessionPhase, within seconds: Int = 5) async throws {
        try await until(within: seconds, "reached \(phase)") { session.state.phase == phase }
    }

    func attached(within seconds: Int = 5) async throws {
        try await phase(.attached, within: seconds)
    }

    func text(_ text: String, within seconds: Int = 5) async throws {
        try await until(within: seconds, "showed what it waited for") { try session.screen.text().contains(text) }
    }
}
