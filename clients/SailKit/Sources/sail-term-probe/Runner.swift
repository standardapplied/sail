import Foundation
import SailTerminal
import SailVT

/// Runs a script's steps against one session in order, stopping at the first that fails.
struct Runner {
    let session: TerminalSession
    let follower: Follower
    let host: LocalHostProcess?
    let echoBudget: Double
    /// Told each step's result as it finishes.
    let progress: @Sendable (StepResult) -> Void
    private let clock = ContinuousClock()

    init(session: TerminalSession, host: LocalHostProcess?, echoBudget: Double, progress: @escaping @Sendable (StepResult) -> Void) {
        self.session = session
        self.host = host
        self.echoBudget = echoBudget
        self.progress = progress
        follower = Follower(session)
    }

    func run(_ script: Script) async -> Report {
        var results: [StepResult] = [] {
            didSet { results.last.map(progress) }
        }
        var kept = Kept()
        var budgets: [Budget] = []
        do {
            try await follower.attached(within: 30)
            results.append(StepResult(step: "attach", ok: true, milliseconds: 0, detail: nil))
        } catch {
            results.append(StepResult(step: "attach", ok: false, milliseconds: 0, detail: "\(error)"))
            return Report(steps: results, latency: session.latencyReport, droppedInputs: session.state.droppedInputs, budgets: [])
        }
        for step in script.steps {
            let started = clock.now
            do {
                if case let .measure(keys) = step {
                    budgets = try await measure(keys)
                } else {
                    try await take(step, kept: &kept)
                }
                results.append(StepResult(step: "\(step)", ok: true, milliseconds: (clock.now - started).milliseconds, detail: nil))
            } catch {
                results.append(StepResult(step: "\(step)", ok: false, milliseconds: (clock.now - started).milliseconds, detail: "\(error)"))
                break
            }
        }
        return Report(steps: results, latency: session.latencyReport, droppedInputs: session.state.droppedInputs, budgets: budgets)
    }

    /// The screens a script kept, and how many replays the session had begun when it last
    /// reconnected: a screen compared after a reconnect is the replay's, never the one before it.
    private struct Kept {
        var screens: [String: String] = [:]
        var replays = 0
    }

    private func take(_ step: Step, kept: inout Kept) async throws {
        switch step {
        case let .type(text):
            text.forEach { session.send(.typing($0)) }
        case let .wait(pattern, within):
            let expression = try Regex(pattern)
            try await follower.until(within: within, "showed /\(pattern)/") { try session.screen.text().contains(expression) }
        case let .resize(columns, rows):
            try session.resize(GridSize(columns: columns, rows: rows))
        case let .snapshot(name):
            kept.screens[name] = try session.screen.text()
        case let .same(name):
            guard let screen = kept.screens[name] else { throw ProbeError("no snapshot is named \(name)") }
            let replays = kept.replays
            try await follower.until("showed the screen kept as \(name) from a replay") {
                try session.state.replays > replays && session.screen.text() == screen
            }
        case .drop:
            kept.replays = session.state.replays
            session.dropConnection()
            try await follower.until("lost its connection") { session.state.phase != .attached }
        case .detach:
            session.detach()
            try await follower.phase(.detached)
        case .attach:
            kept.replays = session.state.replays
            session.attach()
            try await follower.attached(within: 30)
        case .killHost:
            guard let host else { throw ProbeError("killHost needs --host-binary, so the probe owns the host") }
            host.crash()
            try await host.start()
        case let .phase(name, within):
            try await follower.until(within: within, "reached \(name)") { Self.matches(session.state.phase, name) }
        case .measure:
            break
        }
    }

    /// Types `keys` keys into a program that echoes them, each once the one before reached the
    /// screen, and checks the budgets.
    private func measure(_ keys: Int) async throws -> [Budget] {
        let before = session.latencyReport.screen.count
        for index in 1 ... max(1, keys) {
            session.send(KeyInput(key: .x, text: "x", unshifted: "x"))
            try await follower.until("echoed key \(index)") { session.latencyReport.screen.count >= min(before + index, 4096) }
        }
        let report = session.latencyReport
        return [
            Budget(name: "key to bytes written, p95", milliseconds: report.written.p95, limit: 1),
            Budget(name: "echo, key to screen updated, p95", milliseconds: report.screen.p95, limit: echoBudget),
        ]
    }

    private static func matches(_ phase: SessionPhase, _ name: String) -> Bool {
        switch phase {
        case .attached: name == "attached"
        case .detached: name == "detached"
        case .parked: name == "parked"
        case .connecting, .reconnecting: name == "reconnecting"
        case let .ended(end):
            name == "ended" || (name.hasPrefix("ended:") && end.description.contains(name.dropFirst("ended:".count)))
        }
    }
}
