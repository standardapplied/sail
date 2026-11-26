import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailTerminal
import SailTerminalWire
import Synchronization
import Testing
@testable import sail_term_probe

/// N2: a session whose `Create` the runner is still answering (a workspace starting) is one the
/// runner may be making: it counts as at the host, and ending it waits for the answer within the
/// end's one deadline, then ends what was made, or says it could not. Against ``FakeRunnerDoor``
/// holding its `Create`s, as the real host does while it starts a workspace.
@Suite(.timeLimit(.minutes(1))) struct OpeningEndTests {
    static func opening(_ door: FakeRunnerDoor, _ name: String) throws -> TerminalSession {
        let create = SessionCreate(
            session: name, command: [], workingDirectory: "/tmp", project: "acme", room: "", size: TerminalSize(columns: 80, rows: 24), workspace: "try"
        )
        return TerminalSession(open: create, door: .runner(FakeRunnerDoorTests.Tickets(door)), screen: try screen())
    }

    @Test func anEndWhileTheCreateIsUnansweredWaitsForItAndEndsWhatWasMade() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        door.holdCreates()
        let session = try Self.opening(door, "app-opening")
        let watch = Watch(session)
        session.start()
        try await watch.until("the runner is making it") { door.createsReceived == 1 }
        #expect(session.atHost == .coming, "a session coming counts")
        let ending = Task { await session.end(within: .seconds(10)) }
        door.releaseCreates()
        #expect(await ending.value == .ended)
        #expect(door.sessions.isEmpty, "the host's list: nothing left behind")
    }

    /// The deadline holds with the `Create` still unanswered: the end says it could not end what
    /// may be made, and the runner, once it answers, holds the session, as was said. The `Create`
    /// is held until after the end, so an end that waited on it past its deadline would hold the
    /// test past its minute; the deadline is the wall clock's, which waits at least its second, so
    /// nothing of it is left for the Kill whatever the runner's speed.
    @Test(.timeLimit(.minutes(1))) func aCreateUnansweredAtTheDeadlineIsSaidAndWhatItMakesIsWhatWasSaid() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        door.holdCreates()
        let session = try Self.opening(door, "app-slow")
        let watch = Watch(session)
        session.start()
        try await watch.until("the runner is making it") { door.createsReceived == 1 }
        #expect(await session.end(within: .seconds(1)) == .unconfirmed(["The runner did not answer within 1 s."]))
        door.releaseCreates()
        try await watch.until("the runner made it") { door.sessions == ["app-slow"] }
    }

    /// A session ended before its `Create` went out is at no host and never will be: gone, and the
    /// runner never asked to make it.
    @Test func aSessionEndedBeforeItsCreateWentOutIsGoneAndNeverMade() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let desk = HeldTickets(door)
        let create = SessionCreate(
            session: "app-never", command: [], workingDirectory: "/tmp", project: "acme", room: "", size: TerminalSize(columns: 80, rows: 24), workspace: "try"
        )
        let session = TerminalSession(open: create, door: .runner(desk), screen: try screen())
        let watch = Watch(session)
        session.start()
        try await watch.until("its ticket is asked") { desk.asked }
        #expect(session.atHost == .none)
        #expect(await session.end(within: .seconds(2)) == .gone)
        desk.release()
        #expect(door.createsReceived == 0 && door.sessions.isEmpty)
    }

    /// r4: a `Create` whose answer was lost (the connection dropped while the runner made it) is not
    /// taken for never made: the end asks the runner, which ends what it made.
    @Test func aCreateWhoseAnswerWasLostIsEndedNotTakenForNeverMade() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        door.loseCreateAnswers()
        let timeline = Timeline()
        let create = SessionCreate(
            session: "app-lost", command: [], workingDirectory: "/tmp", project: "acme", room: "", size: TerminalSize(columns: 80, rows: 24), workspace: "try"
        )
        let session = TerminalSession(
            open: create, door: .runner(FakeRunnerDoorTests.Tickets(door)), screen: try screen(), timeout: 5, sleep: timeline.sleep
        )
        let watch = Watch(session)
        session.start()
        try await watch.until("the runner made it, its answer lost") { door.sessions == ["app-lost"] && session.atHost == .maybe }
        #expect(await session.end(within: .seconds(5)) == .ended)
        #expect(door.sessions.isEmpty, "the host's list: nothing left behind")
        timeline.passAll()
    }

    /// N1: what may still run is said as what is true of it: it runs, and keeps its workspace
    /// running, until someone ends it or stops the workspace; named by its tab.
    @Test func whatMayStillRunIsSaidAsWhatIsTrueOfIt() {
        #expect(EndOutcome.mayStillRun("harbor · dock", ["The runner did not answer within 10 s."])
            == "harbor · dock may still be running on the runner. The runner did not answer within 10 s. If it is, it keeps running there, and keeps"
            + " its workspace running, until someone ends it or stops the workspace.")
        #expect(EndOutcome.mayStillRun("This terminal", ["This Mac's terminal host did not answer within 10 s."], onThisMac: true)
            == "This terminal may still be running on this Mac. This Mac's terminal host did not answer within 10 s. If it is, it keeps running"
            + " until someone ends it, and Sail opens it as a tab of its own at its next launch.")
    }
}

/// Tickets of a door, the first held until the test releases it, as a control plane that has not
/// answered yet.
final class HeldTickets: TicketSource {
    private let door: FakeRunnerDoor
    private let held = Mutex<(asked: Bool, waiting: CheckedContinuation<Void, Never>?, released: Bool)>((false, nil, false))

    init(_ door: FakeRunnerDoor) {
        self.door = door
    }

    var asked: Bool {
        held.withLock { $0.asked }
    }

    /// The ticket held goes out.
    func release() {
        let waiting = held.withLock { held in
            held.released = true
            defer { held.waiting = nil }
            return held.waiting
        }
        waiting?.resume()
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        await withCheckedContinuation { (waiting: CheckedContinuation<Void, Never>) in
            let now = held.withLock { held -> Bool in
                held.asked = true
                guard !held.released else { return true }
                held.waiting = waiting
                return false
            }
            if now { waiting.resume() }
        }
        return door.issue(for: request)
    }
}
