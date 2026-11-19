import Foundation
import SailAPI
@testable import SailTerminal
import Testing

/// What a reattach says, and who holds the keyboard, from what the server and the host say.
@Suite struct ReattachWordsTests {
    static func session(_ name: String, live: Bool, ended: String? = nil) -> RunnerSession {
        RunnerSession(name: name, live: live, attached: 0, writer: "", opener: "ana", command: [], createdAt: Date(), endedReason: ended)
    }

    @Test func aRefusedReattachIsSaidEndedInTheHostsWordsOrGone() {
        let stopped = "Workspace dock of harbor is being stopped by uday, which ends this terminal."
        let listing = RunnerSessions(sessions: [
            Self.session("app-a", live: false, ended: stopped), Self.session("app-b", live: false, ended: "exited(0)"),
            Self.session("app-c", live: true), Self.session("app-d", live: false),
        ])
        #expect(RunnerReturn.said("app-a", in: listing) == "This terminal ended: \(stopped)")
        #expect(RunnerReturn.said("app-b", in: listing) == "This terminal ended: exited(0).")
        #expect(RunnerReturn.said("app-c", in: listing) == nil)
        #expect(RunnerReturn.said("app-d", in: listing) == RunnerReturn.gone)
        #expect(RunnerReturn.said("app-gone", in: listing) == RunnerReturn.gone)
    }

    /// The attach's own answer names this person when the keyboard came here; another person's
    /// name leaves it with them. After that, a change this client did not ask for takes it away,
    /// this person's other Mac included, and Take Keyboard's answer brings it back.
    @Test func theKeyboardIsElsewhereWhenSomeoneElseTookIt() {
        var keyboard = Keyboard()
        keyboard.writerChanged(to: "ana", me: "ana")
        #expect(keyboard.elsewhere == nil)
        keyboard.writerChanged(to: "ana", me: "ana")
        #expect(keyboard.said == "Typing here is paused: the keyboard is with ana now.")
        keyboard.taking()
        keyboard.writerChanged(to: "ana", me: "ana")
        #expect(keyboard.elsewhere == nil)
        keyboard.writerChanged(to: "uday", me: "ana")
        #expect(keyboard.elsewhere == "uday")
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
