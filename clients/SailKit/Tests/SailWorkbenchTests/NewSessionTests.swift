import Foundation
import SailAPI
import SailKitTestSupport
import SailTerminalWire
@testable import SailWorkbench
import Testing

/// The New Session panel's model, read from the agents route's goldens: it lists exactly what the
/// route offers, in order, then the Shell; the first mode is the harness's own default and the last
/// choice is remembered per harness; Start sends exactly the chosen harness and mode, `inside` only
/// for a start that signs in there; what stops a start is the server's sentence, never a command.
@Suite struct NewSessionTests {
    static func agents(_ name: String) throws -> WorkspaceAgents {
        try Endpoint.agents(workspace: "any", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: try Exchange(name).body))
    }

    @Test func itListsExactlyWhatTheRouteOffersInItsOrderThenTheShell() throws {
        let agents = try Self.agents("workspace-agents")
        let panel = NewSession(agents, memory: ModeMemory())
        #expect(panel.choices == agents.offered.map { .harness($0.harness) } + [.shell])
        #expect(panel.chosen == .harness(agents.offered[0].harness))
        #expect(panel.refusal == nil)
    }

    @Test func theFirstModeIsTheHarnessesOwnDefaultThenTheLastChoiceIsRememberedPerHarness() throws {
        let agents = try Self.agents("workspace-agents")
        let claude = agents.offered[0]
        var panel = NewSession(agents, memory: ModeMemory())
        #expect(panel.mode(of: claude.harness) == claude.modeDefault)
        let other = try #require(claude.modes.last?.mode)
        panel.choose(mode: other)
        let made = panel.start()
        let started = try #require(made)
        #expect(started == AgentStart(harness: claude.harness, display: claude.display, mode: other))
        let again = NewSession(agents, memory: panel.memory)
        #expect(again.mode(of: claude.harness) == other)
        #expect(again.memory.last == started)
    }

    @Test func aModeTheHarnessDoesNotOfferIsNeitherChosenNorRemembered() throws {
        let agents = try Self.agents("workspace-agents")
        let claude = agents.offered[0]
        var panel = NewSession(agents, memory: ModeMemory(modes: [claude.harness: "never-offered"]))
        #expect(panel.mode(of: claude.harness) == claude.modeDefault)
        panel.choose(mode: "never-offered")
        #expect(panel.mode(of: claude.harness) == claude.modeDefault)
    }

    @Test func startSendsExactlyTheChosenHarnessAndModeAndNoCommand() throws {
        let agents = try Self.agents("workspace-agents")
        var panel = NewSession(agents, memory: ModeMemory())
        let made = panel.start()
        let start = try #require(made)
        let create = Place.workspace(project: "harbor", name: "berth").sessionCreate(session: "app-1", size: TerminalSize(columns: 80, rows: 24), agent: start)
        #expect(create.command.isEmpty && create.workspace == "berth" && create.project == "harbor")
        #expect(create.agent == SessionAgent(harness: start.harness, mode: start.mode, inside: false))
        #expect(Place.workspace(project: "harbor", name: "berth").sessionCreate(session: "app-2", size: TerminalSize(columns: 80, rows: 24)).agent.isNone)
    }

    @Test func aHarnessNotInstalledIsSaidInPlaceAndStartsNothing() throws {
        let agents = try Self.agents("workspace-agents")
        let codex = agents.offered[1]
        var panel = NewSession(agents, memory: ModeMemory())
        panel.choose(.harness(codex.harness))
        guard case let .refused(refusal) = panel.readiness else { Issue.record("not refused: \(panel.readiness)"); return }
        #expect(refusal.message.hasPrefix("Codex CLI is not installed in harbor's workspaces"))
        #expect(NewSessionWords.line(codex) == "not installed here")
        #expect(NewSessionWords.remedy(refusal) == "An admin adds codex to harbor's agent install list and builds its template again.")
        #expect(NewSessionWords.primary(panel.readiness) == ("Start", false))
        let made = panel.start()
        #expect(made == nil && panel.memory == ModeMemory())
    }

    @Test func noLoginStoredOffersToSignInThereAndNamesNoCommand() throws {
        let agents = try Self.agents("workspace-agents-stopped-no-login")
        var panel = NewSession(agents, memory: ModeMemory())
        let claude = agents.offered[0]
        guard case let .signInThere(start, refusal) = panel.readiness else { Issue.record("not sign-in-there: \(panel.readiness)"); return }
        #expect(start.inside && start.harness == claude.harness)
        #expect(refusal.message == "No Claude Code login stored for ana, so a Claude Code session cannot start in quay.")
        #expect(NewSessionWords.remedy(refusal) == nil)
        #expect(NewSessionWords.line(claude) == "no login stored")
        #expect(NewSessionWords.primary(panel.readiness) == ("Start and Sign In There", true))
        #expect(NewSessionWords.storeLogin(claude) == "Store a Claude Code Login")
        #expect(NewSessionWords.kinds(claude) == claude.kinds.map(\.label).joined(separator: " or "))
        #expect(NewSessionWords.how(claude) == "Create an API key in the Claude Console.", "Store a Login says the kind the server names first, the API key")
        let made = panel.start()
        #expect(made?.wire == SessionAgent(harness: claude.harness, mode: claude.modeDefault, inside: true))
    }

    @Test func aLoginRefusedWithNoStartInsideStartsNothing() throws {
        let exchange = try Exchange("workspace-agents-stopped-no-login").altered(at: ["offered", 0, "inside"], to: false)
        let agents = try Endpoint.agents(workspace: "quay", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: exchange.body))
        var panel = NewSession(agents, memory: ModeMemory())
        guard case .needsLogin = panel.readiness else { Issue.record("not refused: \(panel.readiness)"); return }
        #expect(NewSessionWords.primary(panel.readiness) == ("Start", false))
        let made = panel.start()
        #expect(made == nil)
    }

    @Test func aStoppedWorkspaceIsAFactNotARefusal() throws {
        let agents = try Self.agents("workspace-agents-stopped-no-login")
        #expect(!agents.running && agents.refusal == nil)
        #expect(NewSessionWords.stopped("quay") == "quay is stopped. Starting a session starts it.")
    }

    @Test func anotherPersonsWorkspaceListsNothingAndSaysWhyWithItsWords() throws {
        let agents = try Self.agents("workspace-agents-not-yours")
        var panel = NewSession(agents, memory: ModeMemory())
        #expect(panel.choices == [.shell] && panel.chosen == .shell)
        let refusal = try #require(panel.refusal)
        #expect(refusal.message == "dock is uday's workspace, so an agent starts in it only as uday, with uday's login.")
        #expect(NewSessionWords.remedy(refusal) == "Open a shell in it, or start the agent in a workspace of your own.")
        let made = panel.start()
        #expect(made == nil)
    }

    @Test func aGoneOwnersRemedyNamesACommandSoNoneOfItIsShown() throws {
        let refusal = try #require(try Self.agents("workspace-agents-owner-gone").refusal)
        #expect(NewSessionWords.remedy(refusal) == nil)
    }

    @Test func theHarnessUsedLastIsChosenFirstWhileItIsOffered() throws {
        let agents = try Self.agents("workspace-agents")
        let codex = agents.offered[1]
        let remembered = ModeMemory(modes: [:], last: AgentStart(harness: codex.harness, display: codex.display, mode: codex.modeDefault))
        #expect(NewSession(agents, memory: remembered).chosen == .harness(codex.harness))
        let gone = ModeMemory(modes: [:], last: AgentStart(harness: "not-offered", display: "Gone", mode: "x"))
        #expect(NewSession(agents, memory: gone).chosen == .harness(agents.offered[0].harness))
        #expect(NewSessionWords.quickStart(codex.display) == "New Codex CLI Session")
    }

    /// The row's quick item starts the last start again at once only while the route still offers
    /// its harness and mode and it can start as it did: with a stored login, or signing in there
    /// when that is how it last started; otherwise the panel opens at that harness, saying why.
    @Test func theQuickItemStartsAgainOnlyWhileTheLastStartStillCan() throws {
        let stored = try Self.agents("workspace-agents-dock"), noLogin = try Self.agents("workspace-agents-dock-no-login"), notInstalled = try Self.agents("workspace-agents-dock-not-installed")
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan"), inside = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan", inside: true)
        func memory(_ last: AgentStart) -> ModeMemory {
            var memory = ModeMemory()
            memory.remember(last)
            return memory
        }
        var signedIn = NewSession(stored, memory: memory(plan))
        #expect(signedIn.startsAgain && signedIn.start() == plan, "a stored login's last start does not start again")
        var insideAgain = NewSession(noLogin, memory: memory(inside))
        #expect(insideAgain.startsAgain && insideAgain.start() == inside, "a start that signed in there does not start again signing in there")
        #expect(!NewSession(noLogin, memory: memory(plan)).startsAgain, "a stored login's last start starts again with the login gone")
        #expect(NewSession(stored, memory: memory(inside)).startsAgain, "a login stored since a sign-in there does not start")
        let ghost = AgentStart(harness: "claude-code", display: "Claude Code", mode: "ghost")
        let stale = NewSession(stored, memory: memory(ghost))
        #expect(!stale.startsAgain && stale.chosen == .harness("claude-code"), "a mode no longer offered starts")
        let codex = AgentStart(harness: "codex", display: "Codex CLI", mode: "full-access")
        let missing = NewSession(notInstalled, memory: memory(codex))
        #expect(!missing.startsAgain && missing.chosen == .harness("codex"), "a harness not installed starts, or the panel is not at it")
        let gone = AgentStart(harness: "gone", display: "Gone", mode: "x")
        #expect(!NewSession(stored, memory: memory(gone)).startsAgain, "a harness not offered starts")
        #expect(!NewSession(try Self.agents("workspace-agents-not-yours"), memory: memory(plan)).startsAgain, "someone else's workspace starts")
    }

    /// The last start is remembered whole, signing in there included.
    @Test func theLastStartIsRememberedWhole() {
        var memory = ModeMemory()
        let inside = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan", inside: true)
        memory.remember(inside)
        #expect(memory.last == inside && memory.modes == ["claude-code": "plan"])
    }

    @Test func upAndDownMoveThroughTheRowsAndStopAtEitherEnd() throws {
        var panel = NewSession(try Self.agents("workspace-agents"), memory: ModeMemory())
        panel.move(by: -1)
        #expect(panel.chosen == panel.choices[0])
        panel.move(by: 1)
        panel.move(by: 1)
        panel.move(by: 1)
        #expect(panel.chosen == .shell)
        #expect(panel.readiness == .shell && NewSessionWords.primary(.shell) == ("Start", true))
    }

    @Test func aStartTheHostRefusedIsSaidInItsSentenceAndNeverItsCommand() {
        let noLogin = HostRefusal(
            message: "No Codex CLI login stored for ana, so a Codex CLI session cannot start in dock.",
            remedy: [.words("Create an API key at platform.openai.com/api-keys, then "), .command(run: .known(.sail), verb: "agent login", args: ["codex"]), .words(".")],
            code: ErrorCode.loginNotStored.rawValue
        )
        #expect(NewSessionWords.refused(noLogin) == noLogin.message)
        let notInstalled = HostRefusal(message: "Codex CLI is not installed in harbor's workspaces.", remedy: [.words("An admin adds codex to harbor's agent install list.")])
        #expect(NewSessionWords.refused(notInstalled) == "Codex CLI is not installed in harbor's workspaces. An admin adds codex to harbor's agent install list.")
    }

    /// A harness of one mode says the server's note on why it has no other; one of many says the
    /// chosen mode's own words.
    @Test func theChosenModeSaysItsWordsAndAHarnessOfOneModeItsNote() throws {
        let agents = try Self.agents("workspace-agents-dock")
        let claude = agents.offered[0], codex = agents.offered[1]
        #expect(NewSessionWords.about(claude, mode: "plan") == "Researches and proposes changes, and edits nothing until you approve the plan.")
        #expect(codex.modes.count == 1 && NewSessionWords.about(codex, mode: codex.modeDefault) == codex.note)
    }

    @Test func aSignedInRowNamesItsLoginAndTheKindsLabel() throws {
        let claude = try Self.agents("workspace-agents").offered[0]
        #expect(NewSessionWords.line(claude) == "login “work” · API key")
    }

    @Test func aListedAgentSessionIsTheStartItWasSoItsTabIsTitledByItsHarness() {
        let listed = RunnerAgent(harness: "codex", display: "Codex CLI", mode: "full-access", login: "inside")
        #expect(listed.start == AgentStart(harness: "codex", display: "Codex CLI", mode: "full-access", inside: true))
    }

    @Test func anAgentTabIsTitledByItsHarnessAndWorkspaceAndKeepsItAcrossALaunch() throws {
        var bench = Workbench()
        let start = AgentStart(harness: "claude-code", display: "Claude Code", mode: "default")
        bench.newTab(session: "app-a", place: .workspace(project: "harbor", name: "dock"), agent: start)
        bench.newTab(session: "app-b", place: .workspace(project: "harbor", name: "dock"))
        #expect(bench.tabs.map { bench.title(of: $0.id) } == ["Claude Code · dock", "harbor · dock"])
        let kept = try JSONDecoder().decode(Arrangement.self, from: try JSONEncoder().encode(bench.arrangement(runner: "uday", holding: [])))
        let back = Workbench.restore(kept, live: ["app-a", "app-b"], home: "/")
        #expect(back.tabs.map { back.title(of: $0.id) } == ["Claude Code · dock", "harbor · dock"])
        #expect(back.panes.values.first { $0.session == "app-a" }?.agent == start)
    }
}
