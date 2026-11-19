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

    /// The keyboard is here exactly when the host says it is this client's, whoever it names: a
    /// change naming this person on another of their clients takes it away, and one naming someone
    /// else in answer to Take Keyboard is never read as the grant. Closing while it is elsewhere
    /// says where the session goes on.
    @Test func theKeyboardIsHereOnlyWhenTheHostSaysItIsThisClients() {
        var keyboard = Keyboard()
        keyboard.writerChanged(to: "ana", yours: true, me: "ana")
        #expect(keyboard.elsewhere == nil && keyboard.said == nil && keyboard.stillRunning("harbor · dock") == nil)
        keyboard.writerChanged(to: "ana", yours: false, me: "ana")
        #expect(keyboard.said == "Typing here is paused: you took the keyboard in another window or on another Mac.")
        #expect(keyboard.stillRunning("harbor · dock 2") == "harbor · dock 2 is still running: the keyboard is with your other window or Mac.")
        keyboard.writerChanged(to: "uday", yours: false, me: "ana")
        #expect(keyboard.elsewhere == "uday", "someone else taking it as Take Keyboard is pressed is not the grant")
        #expect(keyboard.said == "Typing here is paused: the keyboard is with uday now.", "someone else is named")
        #expect(keyboard.stillRunning("harbor · dock") == "harbor · dock is still running: the keyboard is with uday.")
        keyboard.writerChanged(to: "ana", yours: true, me: "ana")
        #expect(keyboard.elsewhere == nil)
        keyboard.writerChanged(to: "ana", yours: false, me: "ana")
        keyboard.writerChanged(to: "", yours: false, me: "ana")
        #expect(keyboard.elsewhere == nil && keyboard.said == nil, "the holder left: no one holds it, and nothing is paused")
        #expect(keyboard.stillRunning("harbor · dock") == nil, "closing then ends by the close rule")

        var first = Keyboard(), second = Keyboard()
        first.writerChanged(to: "ana", yours: false, me: "ana")
        second.writerChanged(to: "ana", yours: true, me: "ana")
        #expect(first.said != nil && second.said == nil, "of two of one person's clients pressing at once, the one that holds it never says paused")
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
