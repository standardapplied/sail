import AppKit
import Foundation
import SailAPI
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailTestPasskey
import SailWorkbench
import Synchronization
import Testing

/// Tickets as a control plane that refuses or cannot be reached hands them out: every request kept.
final class RecordingTickets: TicketSource {
    private let asked = Mutex<[TicketRequest]>([])
    private let answer: SailError

    init(answering answer: SailError) {
        self.answer = answer
    }

    var requests: [TicketRequest] {
        asked.withLock { $0 }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        asked.withLock { $0.append(request) }
        throw answer
    }
}

/// A terminal in a workspace: a tab like any other whose session's ticket names the tab's
/// workspace, at the first open and at Open Again; refused, it says the server's words and what
/// the person can do; closed or quit, its session is ended at the runner, and what could not be
/// ended is said.
@MainActor @Suite struct RunnerTabTests {
    static let notYours = Refusal(
        code: .known(.forbiddenNotAssignee), message: "Workspace 'dock' belongs to 'uday', not you.",
        action: "Ask uday to enter it, or have an admin do it."
    )

    static func until(_ seconds: Double = 5, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in DispatchQueue.main.async { done.resume() } }
        }
        return true
    }

    static func pane(_ place: Place, runner: (any TicketSource)?) throws -> TerminalPane {
        let shell = try WorkbenchLookTests.shell(.light)
        var bench = Workbench()
        let id = bench.newTab(session: place.newSessionName, place: place)
        return try TerminalPane(
            try #require(bench.panes[id]), start: .open(folder: place.folder), host: shell.thisMac.host, runner: runner,
            fonts: TerminalFonts(directory: LookTests.fonts), points: 15, look: .light, palette: Look.light.palette
        )
    }

    /// A3, A5, A13: the session asks a ticket naming the tab's project and workspace; refused, the
    /// banner says the server's sentence and the fix in words; Open Again asks a new ticket for
    /// the same workspace.
    @Test func aRunnerTabsTicketNamesItsWorkspaceAtEveryOpen() async throws {
        let tickets = RecordingTickets(answering: .refused(Self.notYours))
        let pane = try Self.pane(.workspace(project: "acme", name: "try"), runner: tickets)
        #expect(pane.status == nil)
        pane.begin()
        #expect(await Self.until { pane.status == "Workspace 'dock' belongs to 'uday', not you. Ask uday to enter it, or have an admin do it." },
                "the banner says \(pane.status ?? "nothing")")
        pane.openAgain()
        #expect(await Self.until { tickets.requests.count == 2 })
        #expect(tickets.requests == [.open(project: "acme", workspace: "try"), .open(project: "acme", workspace: "try")])
    }

    /// A tab in a workspace is made only for a person signed in: its tickets are theirs.
    @Test func aWorkspaceTabNeedsSomeoneSignedIn() throws {
        #expect(throws: ShellError.host("Sign in to open a terminal in a workspace.")) {
            try Self.pane(.workspace(project: "acme", name: "try"), runner: nil)
        }
    }

    /// Ruling 6: a runner tab's session does not outlive the app. Ended at the runner on its own
    /// connection, through an attach ticket for it; a runner that cannot be reached is said, the tab
    /// named, so the quit can say which it could not end.
    @Test func runnerTerminalsAreEndedAtTheRunnerAndThoseThatCannotBeAreSaid() async throws {
        let tickets = RecordingTickets(answering: .unreachable("the runner does not answer"))
        let runner = try Self.pane(.workspace(project: "acme", name: "try"), runner: tickets)
        runner.begin()
        #expect(await Self.until { !tickets.requests.isEmpty })
        let ending = try #require(runner.end(), "a session not attached is ended on a connection of its own")
        let stopped = await ending.value
        #expect(stopped != nil, "a runner that cannot be reached is said")
        #expect(tickets.requests.contains(.attach(session: runner.name)), "\(tickets.requests)")
    }

    /// The Workspaces item is on the rail only while someone is signed in.
    @Test func theWorkspacesItemIsOfferedOnlySignedIn() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        #expect(shell.rail.button(.workspaces)?.isHidden == true)
        shell.rail.offer(.workspaces, true)
        #expect(shell.rail.button(.workspaces)?.isHidden == false)
    }

    /// A1, A2, A7: the pane lists the person's workspaces from the server's own answers, theirs
    /// first; Make says it is under way; a refusal is the server's sentence and the fix in words.
    @Test func thePaneListsMakesAndRefusesInTheServersWords() async throws {
        let pane = WorkspacesPane(look: .light, title: try TitleFont(directory: LookTests.fonts))
        pane.person = ("uday", true)
        pane.client = SailClient(transport: RecordedServer([try Exchange("projects"), try Exchange("workspaces"), try Exchange("workspace-create")]), token: "tok")
        await pane.refresh()
        #expect(pane.rows.map(\.name) == ["dock"], "the project's own container is not shown")
        #expect(pane.text.contains("dock · running"), pane.text)
        pane.name.stringValue = "new"
        let before = pane.ended
        pane.makeWorkspace(nil)
        #expect(pane.text.contains("Making new (about 3 s)"))
        #expect(await Self.until { pane.ended > before })
        #expect(!pane.text.contains("Making"), pane.text)
        let viewer = WorkspacesPane(look: .light, title: try TitleFont(directory: LookTests.fonts))
        viewer.client = SailClient(
            transport: RecordedServer([try Exchange("projects"), try Exchange("workspaces"), try Exchange("refusal-read-only-credential-workspace")]),
            token: "tok"
        )
        await viewer.refresh()
        viewer.name.stringValue = "new"
        let asked = viewer.ended
        viewer.makeWorkspace(nil)
        #expect(await Self.until { viewer.ended > asked })
        #expect(viewer.text.hasSuffix("Your credential is read-only and cannot enter workspaces.\nAsk an admin for a member or admin credential."), viewer.text)
    }
}
