import SailTerminal
import Synchronization

/// Follows a session's events and screen changes, so the probe waits until what it needs holds. A
/// wait that names no limit of its own waits `patience` seconds, timed by `sleep`; with no patience
/// it waits for what it needs alone, until the waiting task is cancelled.
final class Follower: Sendable {
    /// How often a wait with no limit looks again with nothing told: what it waits for may be no
    /// session's (a fake's count, a clock's waits). A cadence, never a limit.
    private static let glance = Duration.milliseconds(100)
    private let session: TerminalSession
    private let patience: Int?
    private let sleep: @Sendable (Duration) async throws -> Void
    private let tick = Alarm { try await Task.sleep(for: $0) }
    private let log = Mutex<[SessionEvent]>([])

    init(
        _ session: TerminalSession, patience: Int? = nil,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.session = session
        self.patience = patience
        self.sleep = sleep
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

    /// Waits until `holds`, failing, named `what`, when it still does not once `seconds`, or the
    /// follower's patience, have been slept.
    func until(within seconds: Int? = nil, _ what: String, _ holds: () throws -> Bool) async throws {
        guard let limit = seconds ?? patience else {
            while try !holds() {
                guard !Task.isCancelled else { throw ProbeError("the session never \(what): \(session.state.phase)") }
                await tick.wait(Self.glance)
            }
            return
        }
        try await withThrowingTaskGroup(of: Bool.self) { group in
            group.addTask { [sleep] in
                try await sleep(.seconds(limit))
                return true
            }
            defer { group.cancelAll() }
            var expired = false
            while try !holds() {
                guard !Task.isCancelled else { throw ProbeError("the session never \(what): \(session.state.phase)") }
                guard !expired else { throw ProbeError("the session never \(what) within \(limit) s: \(session.state.phase)") }
                group.addTask { [tick] in
                    await tick.wait(nil)
                    return false
                }
                expired = try await group.next() == true
            }
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
