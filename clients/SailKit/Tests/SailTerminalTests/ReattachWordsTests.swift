import Foundation
import SailAPI
@testable import SailTerminal
import Testing

/// What a reattach says, and who holds the keyboard, from what the server and the host say.
@Suite struct ReattachWordsTests {
    static func session(_ name: String, live: Bool, ended: String? = nil) -> RunnerSession {
        RunnerSession(name: name, live: live, attached: 0, writer: "", opener: "ana", command: [], createdAt: Date(), endedReason: ended)
    }

    /// A refused reattach is said from the workspace's listing: ended in the host's words, or gone,
    /// offering a new one there; the workspace not on this runner, offering Close alone; the
    /// terminal host not answering, in its words, offering Try Again; nothing when the listing names
    /// it live or fails otherwise.
    @Test func aRefusedReattachIsSaidFromTheWorkspacesListing() {
        let stopped = "Workspace dock of harbor was stopped by uday."
        let listing = Result<RunnerSessions, SailError>.success(RunnerSessions(sessions: [
            Self.session("app-a", live: false, ended: stopped), Self.session("app-b", live: false, ended: "exited(0)"),
            Self.session("app-c", live: true), Self.session("app-d", live: false),
        ]))
        #expect(RunnerReturn.of("app-a", listing) == RunnerReturn(said: "This terminal ended: \(stopped)", offer: .openANewOne))
        #expect(RunnerReturn.of("app-b", listing)?.said == "This terminal ended: exited(0).")
        #expect(RunnerReturn.of("app-c", listing) == nil)
        #expect(RunnerReturn.of("app-d", listing) == RunnerReturn(said: RunnerReturn.gone, offer: .openANewOne))
        #expect(RunnerReturn.of("app-gone", listing)?.said == RunnerReturn.gone)
        for code in [ErrorCode.notFound, .projectNotInCatalog] {
            let notHere = Result<RunnerSessions, SailError>.failure(.refused(Refusal(code: .known(code), message: "Project 'harbor' has no workspace 'dock' on this box.")))
            #expect(RunnerReturn.of("app-a", notHere) == RunnerReturn(said: RunnerReturn.elsewhere, offer: .close))
        }
        let busy = Result<RunnerSessions, SailError>.failure(.refused(Refusal(code: .known(.terminalUnavailable), message: "The pty host is shutting down; reconnect and try again.")))
        #expect(RunnerReturn.of("app-a", busy) == RunnerReturn(said: "The pty host is shutting down; reconnect and try again.", offer: .tryAgain))
        #expect(RunnerReturn.of("app-a", .failure(.unreachable("no network"))) == nil)
    }

    /// The attach's own answer names this person when the keyboard came here; another person's
    /// name leaves it with them. After that, a change this client did not ask for takes it away,
    /// this person's other Mac included, and Take Keyboard's answer brings it back.
    @Test func theKeyboardIsElsewhereWhenSomeoneElseTookIt() {
        var keyboard = Keyboard()
        keyboard.writerChanged(to: "ana", me: "ana")
        #expect(keyboard.elsewhere == nil)
        keyboard.writerChanged(to: "ana", me: "ana")
        #expect(keyboard.said == "Typing here is paused: you took the keyboard in another window or on another Mac.")
        keyboard.taking()
        keyboard.writerChanged(to: "ana", me: "ana")
        #expect(keyboard.elsewhere == nil)
        keyboard.writerChanged(to: "uday", me: "ana")
        #expect(keyboard.elsewhere == "uday")
        #expect(keyboard.said == "Typing here is paused: the keyboard is with uday now.", "someone else is named")
        keyboard.attached()
        keyboard.writerChanged(to: "ana", me: "ana")
        #expect(keyboard.elsewhere == nil)

        var observer = Keyboard()
        observer.writerChanged(to: "ana", me: "uday")
        #expect(observer.elsewhere == "ana")
    }

    /// A signed-in source lists for its own person only, as it asks tickets.
    @Test func aSignedInSourceListsForItsOwnPersonAlone() async throws {
        let listing = RunnerSessions(sessions: [Self.session("app-a", live: true)])
        let source = SignedInTickets(for: "ana") { ("ana", Listing(listing: listing)) }
        #expect(try await source.sessions(in: "harbor", workspace: "dock") == listing)
        let other = SignedInTickets(for: "ana") { ("ben", Listing(listing: listing)) }
        await #expect(throws: SailError.signIn(.another(owner: "ana", signedIn: "ben"))) {
            try await other.sessions(in: "harbor", workspace: "dock")
        }
        await #expect(throws: SailError.self) { try await TicketOnly().sessions(in: "harbor", workspace: "dock") }
    }

    struct Listing: TicketSource {
        let listing: RunnerSessions
        func ticket(for _: TicketRequest) async throws(SailError) -> TerminalTicket {
            throw .unsendable("no tickets")
        }

        func sessions(in _: String, workspace _: String) async throws(SailError) -> RunnerSessions {
            listing
        }
    }

    struct TicketOnly: TicketSource {
        func ticket(for _: TicketRequest) async throws(SailError) -> TerminalTicket {
            throw .unsendable("no tickets")
        }
    }
}
