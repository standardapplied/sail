import Foundation
import SailAPI
@testable import SailTerminal
import SailTerminalWire
import SailVT
@testable import sail_term_probe
import Synchronization
import Testing

/// A real runner's terminal door, through real tickets from its API: `SAIL_API` names the API on
/// the private address, `SAIL_TOKEN` a member FDE's token and `SAIL_OTHER_TOKEN` another FDE's.
/// The session under test opens in `SAIL_PROJECT`'s container through an open ticket; where no
/// project can run (no Incus), `SAIL_BOX_SOCKET` names the box's unix socket instead, and the
/// session opens there as the box's own person, in a folder, to be attached through tickets.
/// Skipped without `SAIL_API`; CI's `terminal-e2e` sets it.
@Suite(.serialized, .timeLimit(.minutes(3)), .enabled(if: RealRunner.api != nil))
struct RealRunnerTests {
    /// Scenario 2.
    @Test(.enabled(if: RealRunner.project != nil))
    func anOpenTicketOpensASessionInTheProjectsContainer() async throws {
        let runner = try RealRunner()
        let (session, watch) = try await runner.ours()
        "echo hi-$((6*7))\r".forEach { session.send(.typing($0)) }
        try await watch.until(within: 30, "echoed") { try session.screen.text().contains("\nhi-42") }
        session.close()
    }

    /// Scenario 3: a ticket is spent by its first presentation; the second is not welcomed and
    /// leaves the session alone.
    @Test func aSpentTicketIsRefusedAndTouchesNoSession() async throws {
        let runner = try RealRunner()
        let (session, watch) = try await runner.ours()
        let ticket = try await runner.client.ticket(for: .attach(session: session.state.session))
        let address = try HostAddress(ticketAddress: ticket.address)
        try await onThread { try Connection.open(to: address, token: ticket.ticket, timeout: 10).close() }
        await #expect(throws: TerminalError.self) {
            try await onThread { try Connection.open(to: address, token: ticket.ticket, timeout: 10).close() }
        }
        "echo still-here\r".forEach { session.send(.typing($0)) }
        try await watch.until(within: 30, "echoed") { try session.screen.text().contains("\nstill-here") }
        #expect(session.state.phase == .attached)
        session.close()
    }

    /// Scenario 4: a ticket older than its life is refused. The host's clock cannot be moved from
    /// here, so this waits for the ticket's own expiry.
    @Test func aTicketOlderThanThirtySecondsIsRefused() async throws {
        let runner = try RealRunner()
        let (session, _) = try await runner.ours()
        let ticket = try await runner.client.ticket(for: .attach(session: session.state.session))
        let address = try HostAddress(ticketAddress: ticket.address)
        let wait = max(0, ticket.expiresAt.timeIntervalSinceNow) + 2
        try await Task.sleep(for: .seconds(wait))
        await #expect(throws: TerminalError.self) {
            try await onThread { try Connection.open(to: address, token: ticket.ticket, timeout: 10).close() }
        }
        session.close()
    }

    /// Scenarios 5 and 6: a dropped connection asks a new attach ticket, attaches and replays the
    /// same screen; the unfocused view reports focus lost to a program that asked.
    @Test func aDropAsksANewTicketAndReplaysTheSameScreen() async throws {
        let runner = try RealRunner()
        let (session, watch) = try await runner.ours()
        "printf 'mark-%s\\033[?1004h\\n' $((6*7)); cat -v\r".forEach { session.send(.typing($0)) }
        try await watch.until(within: 30, "started cat") { try session.screen.text().contains("\nmark-42") }
        let before = try session.screen.text()
        let asked = runner.counter.count
        session.setFocused(false)
        session.dropConnection()
        try await watch.until(within: 30, "reattached") { runner.counter.count > asked && session.state.phase == .attached }
        try await watch.until(within: 30, "showed the focus-lost report") { try session.screen.text().contains("^[[O") }
        #expect(try session.screen.text().hasPrefix(before.split(separator: "\n").dropLast().joined(separator: "\n")))
        #expect(runner.counter.last == .attach(session: session.state.session))
        session.close()
    }

    /// Scenario 8.
    @Test func anAttachToAnotherFDEsSessionIsRefusedInTheHostsSentence() async throws {
        let runner = try RealRunner()
        let (session, _) = try await runner.ours()
        let name = session.state.session
        let theirs = TerminalSession(attach: name, write: true, door: .runner(runner.other), screen: try screen())
        let watch = Watch(theirs)
        theirs.start()
        try await watch.phase(.ended(.refused("There is no session '\(name)' on this box that you may attach.")), within: 30)
        session.close()
    }
}

/// The runner the suite reaches, and the session it opens there for each test.
struct RealRunner {
    static let environment = ProcessInfo.processInfo.environment
    static let api = environment["SAIL_API"].flatMap(URL.init(string:))
    static let project = environment["SAIL_PROJECT"]

    let client: SailClient
    let other: SailClient
    let counter: CountingTickets

    init() throws {
        guard let api = Self.api, let token = Self.environment["SAIL_TOKEN"], let otherToken = Self.environment["SAIL_OTHER_TOKEN"] else {
            throw HostError("SAIL_API, SAIL_TOKEN and SAIL_OTHER_TOKEN name the runner")
        }
        client = SailClient(transport: URLSessionTransport(base: api), token: token)
        other = SailClient(transport: URLSessionTransport(base: api), token: otherToken)
        counter = CountingTickets(client)
    }

    /// A session of this FDE's on the runner, attached through tickets.
    func ours() async throws -> (TerminalSession, Watch) {
        let name = "test-" + UUID().uuidString.prefix(8).lowercased()
        let size = TerminalSize(columns: 100, rows: 30)
        if let project = Self.project {
            let create = SessionCreate(session: name, command: [], workingDirectory: "/tmp", project: project, room: "", size: size)
            let session = TerminalSession(open: create, door: .runner(counter), screen: try screen(columns: 100, rows: 30))
            let watch = Watch(session)
            session.start()
            try await watch.attached(within: 60)
            return (session, watch)
        }
        guard let socket = Self.environment["SAIL_BOX_SOCKET"] else { throw HostError("SAIL_PROJECT or SAIL_BOX_SOCKET names where a session opens") }
        let create = SessionCreate(session: name, command: [], workingDirectory: "/tmp", project: "", room: "", size: size)
        let opener = TerminalSession(open: create, door: .local(.unix(path: socket)), screen: try screen(columns: 100, rows: 30))
        let opened = Watch(opener)
        opener.start()
        try await opened.attached(within: 30)
        opener.detach()
        try await opened.phase(.detached)
        let session = TerminalSession(attach: name, write: true, door: .runner(counter), screen: try screen(columns: 100, rows: 30))
        let watch = Watch(session)
        session.start()
        try await watch.attached(within: 30)
        return (session, watch)
    }
}

/// A ticket source that counts what it was asked, around the real one.
final class CountingTickets: TicketSource {
    private let source: any TicketSource
    private let asked = Mutex<[TicketRequest]>([])

    init(_ source: any TicketSource) {
        self.source = source
    }

    var count: Int {
        asked.withLock { $0.count }
    }

    var last: TicketRequest? {
        asked.withLock { $0.last }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        asked.withLock { $0.append(request) }
        return try await source.ticket(for: request)
    }
}
