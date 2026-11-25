import Foundation
import SailAPI
import SailKitTestSupport
import SailTerminalWire
@testable import SailWorkbench
import Testing

/// The New Session panel's model, read from the agents route's goldens: it lists exactly what the
/// route offers, in order, then the Shell; the first mode is the harness's own default, the first
/// way in its own sign-in, and the last choice of each is remembered per harness; Start sends
/// exactly the chosen harness and mode, `inside` for the sign-in and not for the key; Start and Sign
/// In only where the workspace is not known to hold the sign-in; what stops a start is the server's
/// sentence, never a command; and every word of a sign-in is the server's.
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
        #expect(started == AgentStart(harness: claude.harness, display: claude.display, mode: other, inside: true))
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
        #expect(create.agent == SessionAgent(harness: start.harness, mode: start.mode, inside: true))
        #expect(Place.workspace(project: "harbor", name: "berth").sessionCreate(session: "app-2", size: TerminalSize(columns: 80, rows: 24)).agent.isNone)
    }

    @Test func aHarnessNotInstalledIsSaidInPlaceAndStartsNothing() throws {
        let agents = try Self.agents("workspace-agents")
        let codex = agents.offered[1]
        var panel = NewSession(agents, memory: ModeMemory())
        panel.choose(.harness(codex.harness))
        guard case let .refused(refusal) = panel.readiness else { Issue.record("not refused: \(panel.readiness)"); return }
        #expect(refusal.message.hasPrefix("Codex CLI is not installed in harbor's workspaces"))
        #expect(NewSessionWords.line(codex, way: panel.way(of: codex.harness)) == "not installed here")
        #expect(NewSessionWords.remedy(refusal) == "An admin adds codex to harbor's agent install list and builds its template again.")
        #expect(NewSessionWords.primary(panel.readiness, signsIn: panel.signsIn) == ("Start", false))
        let made = panel.start()
        #expect(made == nil && panel.memory == ModeMemory())
    }

    /// With a key stored and nothing chosen, the harness's own sign-in is chosen: the row says its
    /// name, Start signs in where the workspace does not hold it, and the start is `inside`.
    @Test func withNothingChosenTheSignInIsChosenEvenWithAKeyStored() throws {
        let agents = try Self.agents("workspace-agents-dock")
        let claude = agents.offered[0]
        var panel = NewSession(agents, memory: ModeMemory())
        #expect(claude.login?.name == "default" && panel.way(of: claude.harness) == .signIn)
        #expect(NewSessionWords.line(claude, way: .signIn) == "Your Claude account")
        #expect(panel.signsIn && NewSessionWords.primary(panel.readiness, signsIn: panel.signsIn) == ("Start and Sign In", true))
        #expect(claude.signIn?.said == "Claude Code asks you to sign in with your Claude account when it starts, once in each workspace.")
        let made = panel.start()
        #expect(made?.wire == SessionAgent(harness: claude.harness, mode: claude.modeDefault, inside: true))
        #expect(panel.memory.ways == [claude.harness: .signIn])
    }

    /// The key chosen: the row says its kind's label and name, Start is plain, the start passes the
    /// key (`inside` false), and the choice is remembered for that harness alone.
    @Test func theKeyChosenStartsWithItAndIsRememberedPerHarness() throws {
        let agents = try Self.agents("workspace-agents-dock")
        let claude = agents.offered[0], codex = agents.offered[1]
        var panel = NewSession(agents, memory: ModeMemory())
        panel.choose(way: .key)
        #expect(panel.way(of: claude.harness) == .key && NewSessionWords.line(claude, way: .key) == "API key “default”")
        #expect(!panel.signsIn && NewSessionWords.primary(panel.readiness, signsIn: panel.signsIn) == ("Start", true))
        let made = panel.start()
        #expect(made?.wire == SessionAgent(harness: claude.harness, mode: claude.modeDefault, inside: false))
        let again = NewSession(agents, memory: panel.memory)
        #expect(again.way(of: claude.harness) == .key && again.way(of: codex.harness) == .signIn)
    }

    /// A workspace that holds the sign-in starts plainly, saying nothing more; one not known to hold
    /// it (a stopped workspace, not read) signs in.
    @Test func startAndSignInOnlyWhereTheWorkspaceIsNotKnownToHoldTheSignIn() throws {
        let held = NewSession(try Self.agents("workspace-agents-dock-signed-in"), memory: ModeMemory())
        #expect(held.way(of: "claude-code") == .signIn && !held.signsIn)
        #expect(NewSessionWords.primary(held.readiness, signsIn: held.signsIn) == ("Start", true))
        let stopped = try Self.agents("workspace-agents-stopped-no-login")
        #expect(stopped.offered[0].signIn?.signedIn == nil && NewSession(stopped, memory: ModeMemory()).signsIn)
    }

    /// With no key stored the key cannot be chosen, nor a remembered key taken: the sign-in starts.
    @Test func withNoKeyTheSignInIsTheWayIn() throws {
        let agents = try Self.agents("workspace-agents-dock-no-login")
        let claude = agents.offered[0]
        var remembered = ModeMemory()
        remembered.choose(.key, for: claude.harness)
        var panel = NewSession(agents, memory: remembered)
        #expect(claude.login == nil && panel.way(of: claude.harness) == .signIn && NewSessionWords.key(claude) == nil)
        panel.choose(way: .key)
        #expect(panel.way(of: claude.harness) == .signIn)
        #expect(claude.storable?.kind == "api-key" && claude.storable?.said == "Create an API key in the Claude Console.", "Store a Key offers the API key and says how to make it")
        #expect(claude.kinds.map(\.said) == ["Create an API key in the Claude Console."], "its one kind is a key whose how is words alone")
        let made = panel.start()
        #expect(made?.wire == SessionAgent(harness: claude.harness, mode: claude.modeDefault, inside: true))
    }

    /// Storing a key chooses it: the person's starts take it for that harness without a start.
    @Test func storingAKeyChoosesIt() throws {
        let agents = try Self.agents("workspace-agents-dock")
        var memory = ModeMemory()
        memory.choose(.key, for: "claude-code")
        let panel = NewSession(agents, memory: memory)
        #expect(panel.way(of: "claude-code") == .key && memory.last == nil)
        #expect(NewSessionWords.ready(agents.offered[0]) == "Claude Code ready, API key default.")
    }

    /// The sign-in's name and sentence are the server's alone: a harness the app has never heard of
    /// is drawn from its declaration, and changed words are said as they are.
    @Test func everyWordOfASignInIsTheServers() throws {
        let made = try Exchange("workspace-agents-dock")
            .altered(at: ["offered", 0, "harness"], to: "made-up")
            .altered(at: ["offered", 0, "display"], to: "Made Up")
            .altered(at: ["offered", 0, "sign_in", "name"], to: "Your Made-up account")
            .altered(at: ["offered", 0, "sign_in", "how"], to: [["text": "Made Up asks you once"]])
            .altered(at: ["offered", 0, "kinds", 0, "label"], to: "Made-up token")
        let agents = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: made.body))
        let offer = agents.offered[0]
        var panel = NewSession(agents, memory: ModeMemory())
        #expect(NewSessionWords.line(offer, way: .signIn) == "Your Made-up account" && offer.signIn?.said == "Made Up asks you once.")
        #expect(NewSessionWords.key(offer) == "Made-up token “default”" && panel.signsIn)
        #expect(panel.start()?.wire == SessionAgent(harness: "made-up", mode: offer.modeDefault, inside: true))
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

    /// A harness with a key is heard ready with its kind's label and name, as its row says them; one
    /// with none is not.
    @Test func aHarnessWithAKeyIsHeardReady() throws {
        let dock = try Self.agents("workspace-agents-dock"), noLogin = try Self.agents("workspace-agents-dock-no-login")
        #expect(NewSessionWords.ready(dock.offered[0]) == "Claude Code ready, API key default.")
        #expect(NewSessionWords.line(dock.offered[0], way: .key) == "API key “default”")
        #expect(NewSessionWords.ready(noLogin.offered[0]) == nil)
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

    /// The row's quick item starts the last start's harness again at once, in its remembered mode
    /// and way in, while the route still offers both: on the key where the key was chosen and is
    /// still stored, on the sign-in where it was chosen; otherwise the panel opens at that harness.
    @Test func theQuickItemStartsAgainInTheRememberedWayWhileTheHarnessStillStarts() throws {
        let stored = try Self.agents("workspace-agents-dock"), noLogin = try Self.agents("workspace-agents-dock-no-login"), notInstalled = try Self.agents("workspace-agents-dock-not-installed")
        let plan = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan"), inside = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan", inside: true)
        func memory(_ last: AgentStart) -> ModeMemory {
            var memory = ModeMemory()
            memory.remember(last)
            return memory
        }
        var onTheKey = NewSession(stored, memory: memory(plan))
        #expect(onTheKey.startsAgain && onTheKey.start() == plan, "the key's last start does not start again on the key")
        var insideAgain = NewSession(stored, memory: memory(inside))
        #expect(insideAgain.startsAgain && insideAgain.start() == inside, "a start that signed in does not start again signing in")
        #expect(!NewSession(noLogin, memory: memory(plan)).startsAgain, "the key chosen and gone, the harness starts another way unasked")
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

    /// The last start is remembered whole, its way in included.
    @Test func theLastStartIsRememberedWhole() {
        var memory = ModeMemory()
        let inside = AgentStart(harness: "claude-code", display: "Claude Code", mode: "plan", inside: true)
        memory.remember(inside)
        #expect(memory.last == inside && memory.modes == ["claude-code": "plan"] && memory.ways == ["claude-code": .signIn])
    }

    @Test func upAndDownMoveThroughTheRowsAndStopAtEitherEnd() throws {
        var panel = NewSession(try Self.agents("workspace-agents"), memory: ModeMemory())
        panel.move(by: -1)
        #expect(panel.chosen == panel.choices[0])
        panel.move(by: 1)
        panel.move(by: 1)
        panel.move(by: 1)
        #expect(panel.chosen == .shell)
        #expect(panel.readiness == .shell && NewSessionWords.primary(.shell, signsIn: panel.signsIn) == ("Start", true))
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

    @Test func aRowOnItsKeyNamesTheKindsLabelAndTheKey() throws {
        let claude = try Self.agents("workspace-agents").offered[0]
        #expect(NewSessionWords.line(claude, way: .key) == "API key “work”")
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
