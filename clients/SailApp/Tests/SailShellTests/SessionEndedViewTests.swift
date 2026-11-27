import AppKit
import SailAPI
import SailFiles
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import Synchronization
import Testing

/// How many times a client said the session it was asked with ended.
final class Told: Sendable {
    private let count = Mutex(0)

    var times: Int {
        count.withLock { $0 }
    }

    @Sendable func tell() {
        count.withLock { $0 += 1 }
    }
}

/// Whatever call learns that the server ended the session, nothing of the server's words is drawn
/// where the call was, Sail's own are, the client tells it once a call, and the person is taken to
/// the ended-session welcome once, however many calls learnt it together.
@MainActor @Suite(.serialized, .timeLimit(.minutes(1))) struct SessionEndedViewTests {
    init() {
        ExitWatch.install()
    }

    static let ended = ["Your sign-in ended.", "Your sessions on the runner kept running.", "Sign in again with your passkey."]

    /// `route` answered as `golden` refused a session the server ended.
    static func ending(_ route: String, _ golden: String = "refusal-missing-bearer-token") throws -> Exchange {
        let refusal = try Exchange(golden)
        let parts = route.split(separator: " ", maxSplits: 1).map(String.init)
        return Exchange(method: parts[0], path: parts[1], status: refusal.status, body: refusal.body)
    }

    /// Whether `text` holds nothing of the server's refusal of a session.
    static func sailsOwn(_ text: String) -> Bool {
        !text.lowercased().contains("bearer") && !text.contains("Authorization")
    }

    @Test(arguments: [false, true])
    func theProjectsListingSaysTheEndInSailsWords(_ listedBefore: Bool) async throws {
        let desk = try Desk(["projects", "workspaces-three"])
        let told = Told()
        let (view, window) = try WorkspacesViewTests.view(desk, ended: told)
        defer { window.orderOut(nil) }
        if listedBefore {
            view.shown()
            try #require(await RunnerTabTests.until { !view.busy && view.text.contains("dock") }, "\(view.text)")
        }
        await desk.answer(try Self.ending("GET /v1/projects"))
        view.shown()
        await view.projectsAsked?.value
        #expect(Self.sailsOwn(view.text) && view.text.contains("Your sign-in ended."), "\(view.text)")
        #expect(told.times == 1)
    }

    @Test func aProjectsWorkspacesListingSaysTheEndInSailsWords() async throws {
        let desk = try Desk(["projects"])
        await desk.answer(try Self.ending("GET /v1/projects/harbor/workspaces"))
        let told = Told()
        let (view, window) = try WorkspacesViewTests.view(desk, ended: told)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { told.times == 1 && view.text.contains("Couldn’t list harbor’s workspaces") }, "\(view.text)")
        #expect(Self.sailsOwn(view.text) && view.text.contains("Your sign-in ended."), "\(view.text)")
    }

    @Test(arguments: [("Delete", "Delete Workspace", "DELETE /v1/projects/harbor/workspaces/dock"), ("Stop", "Stop", "POST /v1/projects/harbor/workspaces/dock/stop")])
    func aWorkspaceActionSaysTheEndInSailsWords(_ action: String, _ confirm: String, _ route: String) async throws {
        let desk = try Desk(["projects", "workspaces-three", "workspace-sessions"])
        await desk.answer(try Self.ending(route))
        let told = Told()
        let (view, window) = try WorkspacesViewTests.view(desk, ended: told)
        defer { window.orderOut(nil) }
        view.shown()
        try #require(await RunnerTabTests.until { !view.busy && view.text.contains("dock") }, "\(view.text)")
        view.project.on(action == "Delete" ? .delete("dock") : .stop("dock"))
        try #require(await RunnerTabTests.until { Driverless.button(confirm, in: window.attachedSheet?.contentView) != nil }, "\(action) asked nothing")
        Driverless.button(confirm, in: window.attachedSheet?.contentView)?.performClick(nil)
        #expect(await RunnerTabTests.until { !view.busy && window.attachedSheet == nil && view.text.contains("Your sign-in ended.") }, "\(view.text)")
        #expect(Self.sailsOwn(view.text) && told.times == 1, "told \(told.times): \(view.text)")
    }

    @Test func aCreateSaysTheEndInSailsWords() async throws {
        let desk = try Desk(["projects", "workspaces-three"])
        await desk.answer(try Self.ending("POST /v1/projects/harbor/workspaces"))
        let told = Told()
        let (view, window) = try WorkspacesViewTests.view(desk, ended: told)
        defer { window.orderOut(nil) }
        view.shown()
        try #require(await RunnerTabTests.until { !view.busy && view.text.contains("dock") }, "\(view.text)")
        view.project.on(.newWorkspace)
        view.project.field.field.stringValue = "quay"
        view.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification))
        view.project.on(.create)
        #expect(await RunnerTabTests.until { told.times == 1 && !view.busy && view.text.contains("Your sign-in ended.") }, "\(view.text)")
        #expect(Self.sailsOwn(view.text), "\(view.text)")
    }

    @Test func theNewSessionPopoverSaysTheEndInSailsWords() async throws {
        let (view, window, server) = try await NewSessionPanelTests.listed([], also: [try Self.ending("GET /v1/projects/harbor/workspaces/dock/agents")])
        defer { window.orderOut(nil) }
        let told = Told()
        view.client = SailClient(transport: server, token: "tok", ended: told.tell)
        let panel = try await NewSessionPanelTests.open("dock", in: view)
        let words = panel.view.words.joined(separator: "\n")
        #expect(Self.sailsOwn(words) && words.contains("Your sign-in ended.") && told.times == 1, "told \(told.times): \(words)")
    }

    @Test func storeAKeySaysTheEndInSailsWords() async throws {
        let (view, window, server) = try await NewSessionPanelTests.listed(["workspace-agents-dock-no-login"], also: [try Self.ending("GET /v1/logins")])
        defer { window.orderOut(nil) }
        let told = Told()
        view.client = SailClient(transport: server, token: "tok", ended: told.tell)
        let panel = try await NewSessionPanelTests.open("dock", in: view)
        try NewSessionPanelTests.storeKey(panel)
        try #require(await RunnerTabTests.until { !view.busy && panel.line != nil }, "the ask of the logins never ended")
        #expect(window.attachedSheet == nil && panel.line == .unread("Your sign-in ended."), "\(String(describing: panel.line))")
        #expect(Self.sailsOwn(panel.view.words.joined(separator: "\n")) && told.times == 1)
    }

    /// A runner tab's ticket refused for an ended session says, in the welcome's words, that its
    /// session kept running, and offers Sign In.
    @Test func aTerminalSaysItsSessionKeptRunning() async throws {
        let told = Told()
        let tickets = SailClient(transport: RecordedServer([try Self.ending("POST /v1/terminal/tickets", "refusal-invalid-bearer-token")]), token: "tok", ended: told.tell)
        let pane = try RunnerTabTests.pane(.workspace(project: "harbor", name: "dock"), runner: tickets, person: "uday")
        pane.begin()
        #expect(await RunnerTabTests.until { pane.status.says == Self.ended.joined(separator: " ") }, "the banner says \(pane.status.says ?? "nothing")")
        #expect(pane.status.offers == ["Sign In"] && told.times == 1, "told \(told.times), offers \(pane.status.offers)")
        pane.detach()
    }

    /// The files panel's ticket refused for an ended session says the same in its line.
    @Test func theFilesPanelSaysItsSessionsKeptRunning() async throws {
        let told = Told()
        let tickets = SailClient(transport: RecordedServer([try Self.ending("POST /v1/terminal/tickets")]), token: "tok", ended: told.tell)
        let panel = FilesPanel(project: "harbor", workspace: "dock", tickets: tickets, look: .light, mono: try LabelFont(directory: LookTests.fonts), titleFont: try TitleFont(directory: LookTests.fonts))
        panel.open()
        defer { panel.close() }
        #expect(await RunnerTabTests.until { panel.shownLine?.words == Self.ended.joined(separator: " ") }, "the line says \(panel.shownLine?.words ?? "nothing")")
        #expect(told.times == 1)
    }

    /// The projects listing and a Start in a workspace's files find the session ended together, with
    /// Terminals in front: each says it in Sail's words, who is signed in is asked once, and the
    /// welcome says the sign-in ended, in front. A call made with that sign-in afterwards asks
    /// nothing more.
    @Test func callsFindingTheSessionEndedTogetherShowTheWelcomeOnce() async throws {
        let desk = try Desk(["whoami", "projects", "workspaces-three"])
        let shell = try WelcomeViewTests.shell(desk, defaults: WelcomeViewTests.reached(), vault: OneSession(fde: "uday", lasts: AccountPaneTests.month))
        defer { shell.window.orderOut(nil) }
        await shell.checking?.value
        await shell.workspaces.refresh()
        try #require(shell.flow.standing != nil && shell.workspaces.text.contains("dock"), "\(shell.workspaces.text)")
        let client = try #require(shell.workspaces.client)
        shell.select(.terminals)
        for route in ["GET /v1/projects", "POST /v1/projects/harbor/workspaces/dock/start"] {
            await desk.answer(try Self.ending(route))
        }
        await desk.answer(try Self.ending("GET /v1/whoami", "refusal-invalid-bearer-token"))
        await desk.hold("GET /v1/whoami")
        await shell.workspaces.refresh()
        let started = await shell.thisMac.startWorkspace("harbor", "dock")
        #expect(started == ["Your sign-in ended."] && Self.sailsOwn(shell.workspaces.text) && shell.workspaces.text.contains("Your sign-in ended."), "\(shell.workspaces.text)")
        let ending = try #require(shell.flow.ending, "who is signed in was not asked")
        await desk.arrived(during: ending)
        await shell.workspaces.refresh()
        await desk.release()
        await ending.value
        let asked = await desk.asked
        #expect(asked.filter { $0 == "GET /v1/whoami" }.count == 2, "\(asked)")
        #expect(shell.flow.standing == nil && shell.rail.selected == .workspaces && !shell.welcome.isHidden, "the window shows \(shell.rail.selected?.rawValue ?? "nothing")")
        #expect(shell.welcome.text.contains("Your sign-in ended on ") && Self.sailsOwn(shell.welcome.text), "\(shell.welcome.text)")
        _ = try? await client.send(.projects)
        let checks = await desk.asked.filter { $0 == "GET /v1/whoami" }.count
        #expect(shell.flow.ending == nil && checks == 2, "a call after the check asked again: \(checks) checks")
    }
}
