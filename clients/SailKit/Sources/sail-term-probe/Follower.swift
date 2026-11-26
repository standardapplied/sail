import SailTerminal
import Synchronization

/// Follows a session's events and screen changes, so the probe, and a test, waits until what it
/// needs holds. A wait that names no limit of its own waits `patience` seconds; with no patience it
/// waits for what it needs alone, until the waiting task is cancelled (a test's time limit).
final class Follower: Sendable {
    /// How often a wait with no limit looks again with nothing told: what it waits for may be no
    /// session's (a fake's count, a clock's waits). A cadence, never a limit.
    private static let glance = Duration.milliseconds(100)
    private let session: TerminalSession
    private let patience: Int?
    private let tick = Alarm { try await Task.sleep(for: $0) }
    private let log = Mutex<[SessionEvent]>([])

    init(_ session: TerminalSession, patience: Int? = nil) {
        self.session = session
        self.patience = patience
        Task { [self] in
            for await event in session.events {
                log.withLock { $0.append(event) }
                tick.ring()
            }
            tick.ring()
        }
        session.onScreenChange { [tick] _ in tick.ring() }
    }

    /// Every event the session told, in order.
    var seen: [SessionEvent] {
        log.withLock { $0 }
    }

    /// Waits until `holds`, failing after `seconds`, or the follower's patience, naming `what`.
    func until(within seconds: Int? = nil, _ what: String, _ holds: () throws -> Bool) async throws {
        let limit = (seconds ?? patience).map { (seconds: $0, deadline: ContinuousClock.now + .seconds($0)) }
        while try !holds() {
            guard !Task.isCancelled else { throw ProbeError("the session never \(what): \(session.state.phase)") }
            guard let limit else {
                await tick.wait(Self.glance)
                continue
            }
            let left = limit.deadline - ContinuousClock.now
            guard left > .zero else { throw ProbeError("the session never \(what) within \(limit.seconds) s: \(session.state.phase)") }
            await tick.wait(left)
        }
    }

    func phase(_ phase: SessionPhase, within seconds: Int? = nil) async throws {
        try await until(within: seconds, "reached \(phase)") { session.state.phase == phase }
    }

    func attached(within seconds: Int? = nil) async throws {
        try await phase(.attached, within: seconds)
    }

    func text(_ text: String, within seconds: Int? = nil) async throws {
        try await until(within: seconds, "showed what it waited for") { try session.screen.text().contains(text) }
    }
}
