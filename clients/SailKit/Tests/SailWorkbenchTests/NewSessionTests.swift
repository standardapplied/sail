import Foundation
import SailAPI
import SailKitTestSupport
import SailTerminalWire
@testable import SailWorkbench
import Testing

/// The New Session panel's model, read from the agents route's goldens: it lists exactly what the
/// route offers, in order, then the Shell; the first mode is the harness's own default, the first
/// way in its own sign-in, and the last choice of each is remembered per harness; Start sends
/// exactly the chosen harness and mode, `inside` for the sign-in and not for the key, and names the
/// choice; the sign-in's help says whether the workspace holds it; what stops a start is the
/// server's sentence, never a command; every word of a sign-in is the server's; a mode warns by the
/// server's `risky` alone; and past seven rows typing narrows them.
@Suite(.timeLimit(.minutes(1))) struct NewSessionTests {
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
        #expect(NewSessionWords.line(codex) == "Not installed in harbor")
        #expect(NewSessionWords.remedy(refusal) == "An admin adds codex to harbor's agent install list and builds its template again.")
        #expect(NewSessionWords.primary(panel.readiness) == nil, "a harness that does not start offers a Start")
        let made = panel.start()
        #expect(made == nil && panel.memory == ModeMemory())
    }

    /// With a key stored and nothing chosen, the harness's own sign-in is chosen: the row says the
    /// key, not what Sign in shows, the help says the agent will ask where the workspace does not
    /// hold the sign-in, Start names the harness, and the start is `inside`.
    @Test func withNothingChosenTheSignInIsChosenEvenWithAKeyStored() throws {
        let agents = try Self.agents("workspace-agents-dock")
        let claude = agents.offered[0]
        var panel = NewSession(agents, memory: ModeMemory())
        #expect(claude.login?.name == "default" && panel.way(of: claude.harness) == .signIn)
        #expect(NewSessionWords.line(claude) == "API key “default”", "the row repeats Sign in's choice")
        #expect(NewSessionWords.signInHelp(claude, way: .signIn, in: "dock") == "Claude Code asks you to sign in with your Claude account when it starts, once in each workspace.")
        #expect(NewSessionWords.primary(panel.readiness) == "Start Claude Code")
        let made = panel.start()
        #expect(made?.wire == SessionAgent(harness: claude.harness, mode: claude.modeDefault, inside: true))
        #expect(panel.memory.ways == [claude.harness: .signIn])
    }

    /// The key chosen: the help offers the sign-in by its name, Start names the harness, the start
    /// passes the key (`inside` false), and the choice is remembered for that harness alone.
    @Test func theKeyChosenStartsWithItAndIsRememberedPerHarness() throws {
        let agents = try Self.agents("workspace-agents-dock")
        let claude = agents.offered[0], codex = agents.offered[1]
        var panel = NewSession(agents, memory: ModeMemory())
        panel.choose(way: .key)
        #expect(panel.way(of: claude.harness) == .key && NewSessionWords.line(claude) == "API key “default”")
        #expect(NewSessionWords.signInHelp(claude, way: .key, in: "dock") == "Or your Claude account, chosen here.")
        #expect(NewSessionWords.signInHelp(codex, way: .key, in: "dock") == "Or your ChatGPT account, chosen here.")
        #expect(NewSessionWords.primary(panel.readiness) == "Start Claude Code")
        let made = panel.start()
        #expect(made?.wire == SessionAgent(harness: claude.harness, mode: claude.modeDefault, inside: false))
        let again = NewSession(agents, memory: panel.memory)
        #expect(again.way(of: claude.harness) == .key && again.way(of: codex.harness) == .signIn)
    }

    /// The sign-in chosen, its help says the workspace holds it already where it does; the
    /// sign-in's own sentence, that the agent will ask, where it does not or is not known to (a
    /// stopped workspace, not read). Start names the harness either way.
    @Test func theSignInsHelpSaysWhetherTheWorkspaceHoldsIt() throws {
        let held = NewSession(try Self.agents("workspace-agents-dock-signed-in"), memory: ModeMemory())
        let claude = held.agents.offered[0]
        #expect(held.way(of: "claude-code") == .signIn && NewSessionWords.signInHelp(claude, way: .signIn, in: "dock") == "Already signed in in dock.")
        #expect(NewSessionWords.primary(held.readiness) == "Start Claude Code")
        let stopped = try Self.agents("workspace-agents-stopped-no-login")
        #expect(stopped.offered[0].signIn?.signedIn == nil)
        #expect(NewSessionWords.signInHelp(stopped.offered[0], way: .signIn, in: "quay") == "Claude Code asks you to sign in with your Claude account when it starts, once in each workspace.")
        #expect(NewSessionWords.signInHelp(stopped.offered[1], way: .signIn, in: "quay") == nil, "a harness with no sign-in of its own says one")
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
        #expect(NewSessionWords.line(offer) == "Made-up token “default”" && offer.signIn?.said == "Made Up asks you once.")
        #expect(NewSessionWords.signInHelp(offer, way: .signIn, in: "dock") == "Made Up asks you once.")
        #expect(NewSessionWords.signInHelp(offer, way: .key, in: "dock") == "Or your Made-up account, chosen here.")
        #expect(NewSessionWords.primary(panel.readiness) == "Start Made Up" && NewSessionWords.monogram(offer.display) == "Ma")
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
        #expect(NewSessionWords.line(dock.offered[0]) == "API key “default”")
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
        #expect(panel.readiness == .shell && NewSessionWords.primary(panel.readiness) == "Open Shell")
    }

    /// The foot names a key only while it does what it says for what holds the panel's keys: ↑↓
    /// while the rows do, ⌘M while a mode menu shows and the panel holds them, nothing while it does not.
    @Test func theFootNamesTheKeysOfWhatHoldsThem() {
        #expect(NewSessionWords.hint(.rows, modes: true) == "↑↓ choose · ⌘M mode")
        #expect(NewSessionWords.hint(.rows, modes: false) == "↑↓ choose")
        #expect(NewSessionWords.hint(.signIn, modes: true) == "⌘M mode", "↑↓ named while they open Sign in's menu")
        #expect(NewSessionWords.hint(.signIn, modes: false) == "")
        #expect(NewSessionWords.hint(.none, modes: true) == "", "keys named while nothing of the panel holds them")
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
        #expect(NewSessionWords.line(claude) == "API key “work”")
    }

    /// A row says how its kind signs in: the stored default key, else the sign-in's name, whichever
    /// way Sign in is set; the Shell that it is a terminal in the workspace.
    @Test func aRowSaysTheKeyElseTheSignInAndNeverFollowsTheChoice() throws {
        let dock = try Self.agents("workspace-agents-dock"), noLogin = try Self.agents("workspace-agents-dock-no-login")
        #expect(NewSessionWords.line(dock.offered[0]) == "API key “default”" && NewSessionWords.line(dock.offered[1]) == "API key “work”")
        #expect(NewSessionWords.line(noLogin.offered[0]) == "Your Claude account")
        #expect(NewSessionWords.shell == "Shell" && NewSessionWords.shellLine("dock") == "A terminal in dock, no agent")
    }

    /// The primary names the choice in every state: the harness by its display name, the Shell
    /// opened, and nothing where the chosen harness does not start.
    @Test func thePrimaryNamesTheChoice() throws {
        var panel = NewSession(try Self.agents("workspace-agents-dock-signed-out"), memory: ModeMemory())
        #expect(NewSessionWords.primary(panel.readiness) == "Start Claude Code")
        panel.move(by: 1)
        #expect(NewSessionWords.primary(panel.readiness) == "Start Codex CLI")
        panel.move(by: 1)
        #expect(NewSessionWords.primary(panel.readiness) == "Open Shell")
    }

    /// A tile's monogram: the first two letters of the display name, the first upper case.
    @Test func aMonogramIsTheFirstTwoLettersOfTheName() {
        #expect(["Claude Code", "Codex CLI", "Pi", "OpenCode", "gemini", "X", "  4o mini", ""].map(NewSessionWords.monogram) == ["Cl", "Co", "Pi", "Op", "Ge", "X", "4o", ""])
    }

    /// A mode warns by the server's flag alone: Claude Code's Bypass permissions while the server
    /// says it is risky and not once it says otherwise, and a made-up harness's risky mode under a
    /// name the app has never seen.
    @Test func aModeWarnsByTheServersFlagAlone() throws {
        let dock = try Exchange("workspace-agents-dock")
        var claude = NewSession(try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: dock.body)), memory: ModeMemory())
        #expect(!claude.risky)
        claude.choose(mode: "bypassPermissions")
        #expect(claude.risky)
        let calm = try dock.altered(at: ["offered", 0, "modes", 6, "risky"], to: false)
        var unflagged = NewSession(try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: calm.body)), memory: ModeMemory())
        unflagged.choose(mode: "bypassPermissions")
        #expect(!unflagged.risky, "a mode warned by its name")
        let tide = try dock.offering([Exchange.madeUpOffer("Tide", risky: true)])
        var made = NewSession(try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: tide.body)), memory: ModeMemory())
        made.choose(.harness("tide"))
        #expect(!made.risky)
        made.choose(mode: "reckless")
        #expect(made.risky)
        made.choose(.shell)
        #expect(!made.risky)
    }

    /// Eight offers and the Shell are past seven rows: typing narrows them to those whose name
    /// starts with what was typed, whatever its case, and chooses the first; what no row matches is
    /// not taken; erasing widens them again. Seven rows are never narrowed.
    @Test func pastSevenRowsTypingNarrowsThemAndChoosesTheFirst() throws {
        let names = ["Pi", "OpenCode", "Gemini CLI", "Aider", "Goose", "Opal"]
        let eight = try Exchange("workspace-agents-dock").offering(names.map { Exchange.madeUpOffer($0) })
        var panel = NewSession(try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: eight.body)), memory: ModeMemory())
        #expect(panel.filters && panel.choices.count == 9)
        let o = panel.narrow(to: "o")
        #expect(o && panel.choices == [.harness("opencode"), .harness("opal")] && panel.chosen == .harness("opencode") && panel.filter == "o")
        let opa = panel.narrow(to: "OpA")
        #expect(opa && panel.choices == [.harness("opal")] && panel.chosen == .harness("opal"))
        let opaz = panel.narrow(to: "opaz")
        #expect(!opaz && panel.filter == "OpA" && panel.choices == [.harness("opal")], "what matches nothing was taken")
        let shell = panel.narrow(to: "s")
        #expect(shell && panel.choices == [.shell] && panel.readiness == .shell)
        let erased = panel.narrow(to: "")
        #expect(erased && panel.choices.count == 9 && panel.chosen == .harness("claude-code"))
        let five = try Exchange("workspace-agents-dock").offering(names.prefix(4).map { Exchange.madeUpOffer($0) })
        var seven = NewSession(try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: five.body)), memory: ModeMemory())
        let typed = seven.narrow(to: "o")
        #expect(!seven.filters && !typed && seven.choices.count == 7 && seven.chosen == .harness("claude-code"), "seven rows were narrowed")
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
