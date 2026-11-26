import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailTerminal
import SailTerminalWire
import SailWorkbench
import Testing
@testable import sail_term_probe

/// A runner tab whose session is an agent, against ``FakeRunnerDoor``: its `Create` names the
/// harness and mode and no command, the runner lists the harness by name, and a start the host
/// refuses (no login stored) ends the session with the host's refusal whole, its code and its
/// remedy as data, nothing made.
@Suite(.timeLimit(.minutes(1))) struct AgentSessionTests {
    static let place = Place.workspace(project: "acme", name: "dock")
    static let start = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan")

    static func agent(_ door: FakeRunnerDoor, _ name: String) throws -> TerminalSession {
        let create = place.sessionCreate(session: name, size: TerminalSize(columns: 80, rows: 24), agent: start)
        return TerminalSession(
            open: create, door: .runner(FakeRunnerDoorTests.Tickets(door)), screen: try screen(), timeout: Patience.answer, sleep: Naps().sleep
        )
    }

    @Test func anAgentsCreateNamesItsHarnessAndModeAndNoCommandAndIsListedByIt() async throws {
        let door = try FakeRunnerDoor(person: "ana")
        defer { door.stop() }
        let session = try Self.agent(door, "app-agent")
        let watch = Watch(session)
        session.start()
        try await watch.until("attached") { session.state.phase == .attached }
        let create = try #require(door.create(of: "app-agent"))
        #expect(create.command.isEmpty && create.workspace == "dock")
        #expect(create.agent == SessionAgent(harness: "claude-code", mode: "plan", inside: false))
        #expect(door.listing.sessions.first?.agent == RunnerAgent(harness: "claude-code", display: "Claude Code", mode: "plan", login: "stored"))
        session.close()
    }

    @Test func aStartTheHostRefusesEndsWithItsRefusalWholeAndMakesNothing() async throws {
        let door = try FakeRunnerDoor(person: "ana")
        defer { door.stop() }
        let refusal = HostRefusal(
            message: "No Claude Code login stored for ana, so a Claude Code session cannot start in dock.",
            remedy: [.words("Create an API key in the Claude Console, then "), .command(run: .known(.sail), verb: "agent login", args: ["claude-code"]), .words(".")],
            code: ErrorCode.loginNotStored.rawValue
        )
        door.refuseCreates(with: refusal)
        let session = try Self.agent(door, "app-refused")
        let watch = Watch(session)
        session.start()
        try await watch.until("ended") { if case .ended = session.state.phase { true } else { false } }
        #expect(session.state.phase == .ended(.refused(refusal)))
        #expect(door.sessions.isEmpty)
    }
}
