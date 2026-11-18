import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailTerminal
import SailTerminalWire
@testable import sail_term_probe
import Synchronization
import Testing

/// What a person reads of a refusal names no command line and no route: every refusal the server
/// recorded (each API refusal, and the terminal door's refusal frames), and every failure of a call,
/// said as the app says it. The server's own test (`RefusalsNameNoCommandTest`) reads every site
/// that builds a refusal in its sources, so a refusal added there is held to this too.
@Suite struct RefusalWordsTests {
    static func spelled(_ lines: [String]) -> [String] {
        lines.filter { $0.contains(/(^|[\s'`(:"])sail (?!system\b)[a-z][a-z-]*/) || $0.contains("/v1/") }
    }

    /// The refusal `exchange` answers, as the client decodes it. A `catch` with a pattern here
    /// crashes Swift 6.2.4's SILGen on macOS, so the error is matched after it is caught.
    static func refusal(in exchange: Exchange) -> Refusal? {
        let response = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
        let error: SailError
        do {
            _ = try Endpoint.whoami.decode(response)
            return nil
        } catch let caught {
            error = caught
        }
        guard case let .refused(refusal) = error else { return nil }
        return refusal
    }

    @Test func noRecordedRefusalReadsAsACommandOrARoute() throws {
        var read = 0
        var spelled: [String] = []
        for name in try Goldens.names(in: "http") {
            let exchange = try Exchange(name)
            guard exchange.status >= 400, let refusal = Self.refusal(in: exchange) else { continue }
            read += 1
            spelled += Self.spelled(refusal.lines).map { "\(name): \($0)" }
        }
        for (name, wire) in try Goldens.frames() {
            switch try FrameCodec.decode(payload: wire.dropFirst(4)) {
            case let .refused(refusal):
                read += 1
                spelled += Self.spelled(refusal.lines).map { "\(name): \($0)" }
            case let .ticketRefused(_, reason, remedy):
                read += 1
                spelled += Self.spelled(HostRefusal(message: reason, remedy: remedy).lines).map { "\(name): \($0)" }
            default:
                continue
            }
        }
        #expect(read > 20, "\(read) refusals read")
        #expect(spelled == [])
    }

    /// M2: a call that ended in no answer of the server's is said by its kind, never by its route.
    @Test func everyFailureOfACallIsSaidByItsKindWithoutItsRoute() {
        let failures: [SailError] = [
            .unexpected(route: "POST /v1/terminal/tickets", status: 502),
            .undecodable(Undecodable(route: "GET /v1/projects", field: "projects", reason: "not a list")),
            .notAnEventStream(route: "GET /v1/events", contentType: "text/html"),
            .unreachable("The request timed out."),
        ]
        let said = failures.map(\.description) + failures.map { TerminalError.controlPlane($0).description }
        #expect(Self.spelled(said) == [], "\(said)")
        #expect(said.prefix(4) == [
            "The control plane answered with an error (502).", "The control plane sent an answer this app cannot read.",
            "The control plane's proxy answered in place of its event stream.", "Sail could not reach the control plane: The request timed out.",
        ])
        #expect(failures.map(\.route) == ["POST /v1/terminal/tickets", "GET /v1/projects", "GET /v1/events", nil], "kept for a log")
    }
}

/// M6: a runner terminal asks its tickets of whoever is signed in at that moment.
@Suite struct SignedInTicketsTests {
    final class Desk: TicketSource {
        let asked = Mutex(0)

        func ticket(for _: TicketRequest) async throws(SailError) -> TerminalTicket {
            asked.withLock { $0 += 1 }
            return TerminalTicket(ticket: "tkt", address: "127.0.0.1:9", expiresAt: Date())
        }
    }

    @MainActor final class Standing {
        var current: (handle: String, tickets: any TicketSource)?
    }

    @MainActor @Test func aTerminalAsksAsWhoeverIsSignedInNowAndNeverAsAnother() async throws {
        let standing = Standing()
        let first = Desk(), again = Desk()
        standing.current = ("uday", first)
        let tickets = SignedInTickets(for: "uday") { standing.current }
        _ = try await tickets.ticket(for: .attach(session: "app-a"))
        standing.current = nil
        await #expect(throws: SailError.signIn(.signedOut)) { try await tickets.ticket(for: .attach(session: "app-a")) }
        standing.current = ("uday", again)
        _ = try await tickets.ticket(for: .attach(session: "app-a"))
        #expect(first.asked.withLock { $0 } == 1 && again.asked.withLock { $0 } == 1, "the sign-in of the moment, not the one at the open")
        standing.current = ("ana", again)
        await #expect(throws: SailError.signIn(.another(owner: "uday", signedIn: "ana"))) { try await tickets.ticket(for: .attach(session: "app-a")) }
        #expect(again.asked.withLock { $0 } == 1, "another person never asks for it")
        #expect(SignIn.another(owner: "uday", signedIn: "ana").lines == ["This terminal is uday's, and ana is signed in.", "Sign in as uday to reach it."])
        #expect(SailError.signIn(.signedOut).route == nil, "the client's own condition, never a server's answer")
    }
}

/// m1: the fake runner door the app's tests and the drive use refuses what the real door refuses,
/// driven here by the real client.
@Suite(.timeLimit(.minutes(1))) struct FakeRunnerDoorTests {
    final class Tickets: TicketSource {
        let door: FakeRunnerDoor

        init(_ door: FakeRunnerDoor) {
            self.door = door
        }

        func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
            door.issue(for: request)
        }
    }

    static func opened(_ door: FakeRunnerDoor, _ name: String) async throws -> TerminalSession {
        let create = SessionCreate(session: name, command: [], workingDirectory: "/tmp", project: "acme", room: "", size: TerminalSize(columns: 80, rows: 24), workspace: "try")
        let session = TerminalSession(open: create, door: .runner(Tickets(door)), screen: try screen())
        let watch = Watch(session)
        session.start()
        try await watch.attached(within: 10)
        return session
    }

    /// Played as this Mac's host, it ends what it is asked, refuses or holds Kills when told, and
    /// lists what lives, driven here by the real client.
    @Test func asThisMacsHostItEndsRefusesAndHoldsKills() async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer {
            door.releaseKills()
            door.stop()
        }
        func attached(_ name: String) async throws -> TerminalSession {
            let create = SessionCreate(session: name, command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 80, rows: 24))
            let session = TerminalSession(open: create, door: .local(.tcp(host: "127.0.0.1", port: door.port)), screen: try screen())
            let watch = Watch(session)
            session.start()
            try await watch.attached(within: 10)
            return session
        }
        let ended = try await attached("mac-a")
        #expect(await ended.end() == .ended)
        door.refuseKills(saying: "Session 'mac-b' is not yours.")
        let refused = try await attached("mac-b")
        #expect(await refused.end() == .unconfirmed(["Session 'mac-b' is not yours."]))
        #expect(door.sessions == ["mac-b"])
        let late = try await attached("mac-c")
        door.holdKills()
        #expect(await late.end(within: .seconds(1)) == .unconfirmed(["This Mac's terminal host did not answer within 1 s."]))
        #expect(door.sessions == ["mac-b", "mac-c"])
        #expect(door.kills == ["mac-a", "mac-b"], "the held Kill is not yet counted")
    }

    @Test func itEndsASessionOnlyForItsWriterAndWelcomesATicketOnce() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let ours = try await Self.opened(door, "app-ours")
        let theirs = try await Self.opened(door, "app-theirs")
        #expect(door.sessions == ["app-ours", "app-theirs"])
        ours.close()
        #expect(await Door.runner(Tickets(door)).end("app-ours") == .ended)
        door.holdWrite("app-theirs")
        theirs.close()
        #expect(await Door.runner(Tickets(door)).end("app-theirs") == .unconfirmed([FakeRunnerDoor.notTheWriter]))
        #expect(door.sessions == ["app-theirs"], "the host's session list after the ends")
        let ticket = door.issue(for: .attach(session: "app-theirs"))
        let spent = try await onThread { () throws -> [String] in
            var said: [String] = []
            for _ in 0 ..< 2 {
                let connection = try Connection.connect(to: try HostAddress(ticketAddress: ticket.address), timeout: 5)
                defer { connection.close() }
                do {
                    _ = try connection.admit(token: ticket.ticket)
                    said.append("welcomed")
                } catch {
                    guard case let TerminalError.refused(refusal) = error else { throw error }
                    said.append(refusal.message)
                }
            }
            return said
        }
        #expect(spent == ["welcomed", "This ticket was presented already, and a ticket admits one connection; ask the API for a new one."])
    }

    /// A ticket covers what it was asked for and nothing else: an attach ticket presented to open a
    /// session is refused in the real door's words, and the door hangs up.
    @Test func aTicketCoversOnlyWhatItWasAskedFor() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let ticket = door.issue(for: .attach(session: "app-ours"))
        let said = try await onThread { () throws -> String in
            let connection = try Connection.connect(to: try HostAddress(ticketAddress: ticket.address), timeout: 5)
            defer { connection.close() }
            _ = try connection.admit(token: ticket.ticket)
            let create = SessionCreate(session: "app-new", command: [], workingDirectory: "/tmp", project: "acme", room: "", size: TerminalSize(columns: 80, rows: 24), workspace: "try")
            do {
                try connection.request(.create(create))
                return "made"
            } catch {
                guard case let TerminalError.refused(refusal) = error else { throw error }
                return refusal.message
            }
        }
        #expect(said == "This connection's ticket is to attach to session 'app-ours', and it admits nothing else; ask the API for a ticket for that.")
        #expect(door.sessions.isEmpty && door.createsReceived == 0)
    }
}
