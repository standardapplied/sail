import Foundation

/// What the Workspaces view shows, from what the person did and what the server answered: the left
/// panel's projects, This Mac's first, and the chosen project's workspaces (yours, then other
/// people's) with opening and creating one. The view only draws ``screen`` and sends the person's
/// actions here; every word a person reads is the server's or this model's, never a command or a
/// route.
public struct WorkspacesModel: Sendable {
    /// The one local project: this Mac's own shells, which exists on this Mac alone and is never
    /// named to the control plane. No server project is called so (a name there is lowercase).
    public static let thisMac = "This Mac"
    /// What This Mac's page says it is not.
    public static let thisMacNote = "This Mac has no repositories, agents, size or template, and cannot be renamed or deleted. Agents run in workspaces on the runner."

    /// The line under This Mac's name: the Mac it is, and the control plane it is kept from.
    public static func thisMacLede(host: String, controlPlane: String) -> String {
        "Shells on \(host). Only this Mac sees them; nothing here is sent to \(controlPlane)."
    }

    public struct Person: Equatable, Sendable {
        public let handle: String
        public let admin: Bool
        /// Whether the role was confirmed by the control plane this time; not yet, the panel says so
        /// and a refusal says it may follow from it. Keeping the last confirmed role for what is
        /// offered belongs to the reach spec (`sail-app-says-when-it-cannot-reach-the-control-plane`).
        public let confirmed: Bool
        public let controlPlane: String

        public init(handle: String, admin: Bool, confirmed: Bool, controlPlane: String) {
            self.handle = handle
            self.admin = admin
            self.confirmed = confirmed
            self.controlPlane = controlPlane
        }

        /// The control plane as a person reads it: its host, and its port when it has one.
        public var named: String {
            (try? ControlPlane(address: controlPlane))?.named ?? controlPlane
        }

        /// Who `signedIn` is, as its `whoami` confirmed this time, at the control plane's full
        /// address (two control planes on one host are two places); no one, signed out.
        public init(_ signedIn: SignedIn?) {
            let whoami = signedIn.flatMap { try? $0.whoami.get() }
            self.init(
                handle: whoami?.fde ?? signedIn?.fde ?? "", admin: whoami?.role == .known(.admin), confirmed: whoami != nil,
                controlPlane: signedIn?.controlPlane.description ?? ""
            )
        }
    }

    public enum Screen: Equatable, Sendable {
        /// Nothing listed yet.
        case loading
        /// The projects could not be listed and nothing is shown: what went wrong, and that the
        /// terminals here are untouched.
        case unreachable(Empty)
        case shown(Panel, Content)
    }

    /// The left panel: This Mac's group, then the control plane's projects under its host.
    public struct Panel: Equatable, Sendable {
        public let projects: [Project]
        /// The project chosen, ``WorkspacesModel/thisMac`` while This Mac is.
        public let chosen: String?
        /// The control plane's host, which heads its projects' group.
        public let controlPlane: String
        /// Who is signed in and their role ("ana · member"), which the empty card's line says.
        public let who: String
        /// What the panel says when it lists no project.
        public let emptyLine: String?
        /// What is shown is the last listed, its refresh having failed: the panel is drawn dimmed.
        public let stale: Bool
    }

    public struct Project: Equatable, Sendable {
        public let name: String
        public let mark: Mark?
    }

    /// A project's template when no version is current, as its row's pill says it.
    public enum Mark: Equatable, Sendable {
        case noTemplate, building, buildFailed
    }

    /// Whose a row is, as its owner column says it.
    public enum Owner: Equatable, Sendable {
        case person(String)
        /// The server names no one: the owner's account is not active (removed or disabled, which
        /// the server does not tell apart).
        case inactive
        /// No one owns it.
        case nobody
    }

    public enum Content: Equatable, Sendable {
        case noProjects(Empty)
        case project(Page)
        /// This Mac's page, its sessions this Mac's own.
        case thisMac
    }

    public struct Page: Equatable, Sendable {
        public let name: String
        /// The line under the project's name.
        public let lede: String
        public let canCreate: Bool
        /// Why New Workspace is unavailable, said beside it; nil while it is offered.
        public let createWhy: String?
        public let banner: Notice?
        public let body: Body
        /// The list is the last one taken, its refresh having failed: drawn dimmed.
        public let stale: Bool
        public let listedAt: Date?

        /// Workspace row `name`'s line, nil while it is not listed.
        public func line(_ name: String?) -> Line? {
            guard case let .list(table) = body else { return nil }
            return (table.yours + table.others).first { $0.name == name }
        }

        /// The keys its foot names with row `chosen` chosen and the keys `held` so, each only while it
        /// does what it says: none with no workspace row chosen, nor while nothing of the window holds
        /// them; Return and ⌥⌘T, the list's own keys, only while the list holds them, Return only where
        /// the row opens (its Open or Start and Open, which Return presses); ⌘N, New Workspace's key
        /// equivalent, which reaches it from anywhere in the window, while a workspace can be made.
        public func keys(chosen: String?, held: Held) -> [Key] {
            guard let line = line(chosen), held != .none else { return [] }
            let listed: [Key] = held == .list ? (line.primary == nil ? [] : [.open]) + [.newSession] : []
            return listed + (canCreate ? [.newWorkspace] : [])
        }
    }

    /// A key a page's foot names.
    public enum Key: Equatable, Sendable {
        case open, newSession, newWorkspace
    }

    /// What holds the keys, as the app's one rule for it says (the first responder of a window that
    /// is key): the list; another view of the page's window, which is key; or nothing of that
    /// window, a sheet over it or another window being key.
    public enum Held: Equatable, Sendable {
        case list, window, none
    }

    public enum Body: Equatable, Sendable {
        case loading
        /// The list could not be had; the banner says why.
        case unlisted
        case empty(Empty)
        case list(Table)
    }

    public struct Empty: Equatable, Sendable {
        public let title: String
        public let body: String
        public let line: String?
        public let action: EmptyAction
        /// Something is missing rather than empty: the tile is dashed.
        public let missing: Bool
    }

    public enum EmptyAction: Equatable, Sendable {
        case newWorkspace, checkAgain
    }

    public struct Notice: Equatable, Sendable {
        public enum Tone: Equatable, Sendable {
            case warning, error
        }

        public let tone: Tone
        public let title: String
        public let line: String
        public let tryAgain: Bool

        public init(tone: Tone, title: String, line: String, tryAgain: Bool) {
            self.tone = tone
            self.title = title
            self.line = line
            self.tryAgain = tryAgain
        }
    }

    public struct Table: Equatable, Sendable {
        public let yours: [Line]
        public let others: [Line]
        public let create: CreateLine?
        /// The create under way, drawn as the row it will be: its name, its instance, Creating.
        public let creating: Line?
        public let count: Int
    }

    public struct Line: Equatable, Sendable {
        public let name: String
        public let instance: String
        public let state: Open<WorkspaceState>
        /// It is the create under way here, not a row the server listed.
        public let creating: Bool
        /// It is the person's own.
        public let mine: Bool
        /// Whose it is, shown only when it is not the person's own.
        public let owner: Owner?
        public let chosen: Bool
        public let primary: Primary?
        /// Why it offers no Open: whose it is.
        public let why: String?
        /// Its ⋯ menu: on the person's own row that opens, New Session, after the harness they started
        /// last.
        public let menu: [Entry]
        /// How many of the person's terminal sessions run in it, on their own running workspace.
        public let sessions: Int

        /// What the row says of its sessions beside its state: nothing for none.
        public var sessionsSaid: String? {
            sessions == 0 ? nil : sessions == 1 ? "1 session" : "\(sessions) sessions"
        }
        /// What is under way on it, its pill said so and its actions waiting.
        public let busy: Action?
        /// What can start in it is being asked, for its New Session panel.
        public let askingAgents: Bool
    }

    /// What a person does to a workspace that asks first and waits for the runner.
    public enum Action: Equatable, Sendable {
        case stop
        case delete(force: Bool)
    }

    /// One question asked before a stop or a delete, a value the sheet is made from and holds: its
    /// project, its workspace, what it does, and the words the person reads. Confirming sends exactly
    /// this; a forced delete is only ever the question made from the server's "holds work" refusal of
    /// that workspace.
    public struct Question: Equatable, Sendable {
        public let project: String
        public let name: String
        public let action: Action
        public let title: String
        public let body: String
        public let confirm: String
        /// The person's own terminals counted when it was asked (a stop of their own workspace).
        public let terminals: Int?
        /// What the server said beyond the sentence shown, kept for the log.
        public let detail: String?
        /// Asked once each, so a question asked again in the same words is still another.
        let asked: Int
    }

    /// What a person's choice of Stop or Delete does: sends at once (a stop that ends nothing), asks
    /// this question, or nothing (the row is under way, or another question is open).
    public enum Asked: Equatable, Sendable {
        case send(project: String, name: String, action: Action)
        case question(Question)
    }

    public enum Primary: Equatable, Sendable {
        case open, startAndOpen
    }

    /// A row's action. New Session opens the panel of what can start in it; starting the harness
    /// last started (`startLast`, by that harness's name) starts it again without the panel.
    public enum Entry: Equatable, Sendable {
        case open, startAndOpen, startLast(String), newSession, copyName, stop, delete
    }

    /// The server's rule for a workspace's name, said under the name field.
    public static let nameRule = "Lowercase letters, digits and dashes."

    /// A project's create row: the name typed, sent, or refused. Each project keeps its own, so an
    /// answer lands on the project it was asked for wherever the person is by then.
    public struct CreateLine: Equatable, Sendable {
        public var name: String
        public var underWay: Bool
        /// The server's refusal, a line each; empty when none.
        public var refusal: [String]
        /// A workspace of that name in the project's list as shown, which the refusal offers to show.
        public var existing: String?

        public init(name: String, underWay: Bool, refusal: [String], existing: String?) {
            self.name = name
            self.underWay = underWay
            self.refusal = refusal
            self.existing = existing
        }
    }

    /// Who is signed in; a new role or its confirmation keeps what is shown and under way.
    public var person: Person
    /// The harness the person started last, by its name: their own rows offer to start it again.
    public var lastStarted: String?
    /// The workspace whose agents route is being asked, for its New Session panel.
    public var askingAgentsIn: String?
    /// The control plane's project chosen, kept while This Mac is shown in its place.
    public private(set) var chosen: String?
    /// This Mac is shown, not ``chosen``.
    public private(set) var thisMacChosen = false
    public private(set) var chosenRow: String?
    private var projects: [ProjectSummary]?
    private var projectsFailure: SailError?
    private var projectsAsked = 0
    private var listing = WorkspaceListing()
    private var listedAt: Date?
    private var listFailure: String?
    private var creates: [String: CreateLine] = [:]
    /// The person's live sessions in each of their workspaces, by workspace, as last listed.
    private var sessions: [String: Int] = [:]
    /// The newest request for each workspace's sessions, so an older answer never replaces it.
    private var sessionsAsked: [String: Int] = [:]
    private var sessionsAskedCount = 0
    /// The workspace created in this project, chosen once a list shows it.
    private var choosingAfterList: [String: String] = [:]

    /// The chosen project's create row.
    private var create: CreateLine? {
        get { chosen.flatMap { creates[$0] } }
        set { if let chosen { creates[chosen] = newValue } }
    }

    /// The model for `person`, the project they chose last (`chosen`) kept chosen while it is listed.
    public init(person: Person, chosen: String? = nil) {
        self.person = person
        thisMacChosen = chosen == Self.thisMac
        self.chosen = thisMacChosen ? nil : chosen
    }

    // MARK: Projects and lists

    /// Takes the projects listed; the first is chosen when none still is. Returns the list request
    /// the chosen project needs: a project chosen again is listed again too, as what was shown
    /// before may have changed, keeping what is under way on it.
    @discardableResult
    public mutating func projectsListed(_ result: Result<ProjectList, SailError>, request: Int? = nil) -> Int? {
        if let request, request != projectsAsked { return nil }
        switch result {
        case let .success(list):
            projects = list.projects
            projectsFailure = nil
            guard let name = list.projects.first(where: { $0.name == chosen })?.name ?? list.projects.first?.name else {
                chosen = nil
                listing.clear()
                return nil
            }
            return name == chosen ? listAgain() : select(name)
        case let .failure(error):
            projectsFailure = error
            return nil
        }
    }

    /// The projects asked for again, the request's number: an older answer is dropped. With none ever
    /// listed, the loading frame, not the last failure, shows until the answer comes.
    @discardableResult
    public mutating func askingProjects() -> Int {
        if projects == nil { projectsFailure = nil }
        projectsAsked += 1
        return projectsAsked
    }

    /// Chooses project `name` in This Mac's place, its list's request. A create row typed or sent in
    /// a project stays with it.
    public mutating func choose(_ name: String) -> Int {
        thisMacChosen = false
        return select(name)
    }

    /// Shows This Mac: nothing is asked, and the control plane's project chosen stays so.
    public mutating func chooseThisMac() {
        thisMacChosen = true
    }

    private mutating func select(_ name: String) -> Int {
        chosen = name
        chosenRow = nil
        sessions = [:]
        listFailure = nil
        listedAt = nil
        let request = listing.ask(name)
        if refused[name] != nil, refused[name]?.since == nil { refused[name]?.since = request }
        askNextWaiting()
        return request
    }

    /// The chosen project's list asked again (⌘R, Try Again, after an action).
    public mutating func listAgain() -> Int {
        listing.ask(chosen)
    }

    /// Takes a list answered for request `request` when it is still the latest; `at` is when.
    public mutating func listed(_ result: Result<WorkspaceList, SailError>, request: Int, at: Date) {
        switch result {
        case let .success(list):
            guard listing.take(list.workspaces, request: request) else { return }
            listedAt = at
            listFailure = nil
            if let project = listing.listed, let since = refused[project]?.since, request > since { refused[project] = nil }
            if let project = listing.listed { forgetGone(in: project, request: request) }
            if let project = listing.listed, let wanted = choosingAfterList[project], listing.rows.contains(where: { $0.name == wanted }) {
                chosenRow = wanted
                choosingAfterList[project] = nil
            }
        case let .failure(error):
            guard listing.isLatest(request) else { return }
            listFailure = error.said(at: person.controlPlane)
        }
    }

    /// The number of a new request for `workspace`'s sessions in the project shown.
    public mutating func askingSessions(of workspace: String) -> Int {
        sessionsAskedCount += 1
        sessionsAsked[workspace] = sessionsAskedCount
        return sessionsAskedCount
    }

    /// Takes how many live sessions of the person's own `listing`, the answer to `request`, holds in
    /// workspace `workspace` of the project shown, for its row; a listing for a project no longer
    /// shown, or one older than the newest asked, is dropped.
    public mutating func sessionsListed(_ listing: RunnerSessions, project: String, workspace: String, request: Int) {
        guard project == chosen, sessionsAsked[workspace] == request else { return }
        sessions[workspace] = listing.live(for: person.handle).count
    }

    public mutating func chooseRow(_ name: String?) {
        chosenRow = name
    }

    // MARK: Create

    /// Opens the create row, the one row marked while it is open.
    public mutating func beginCreate() {
        guard canCreate else { return }
        create = CreateLine(name: "", underWay: false, refusal: [], existing: nil)
        chosenRow = nil
    }

    public mutating func type(_ name: String) {
        guard create?.underWay == false else { return }
        create = CreateLine(name: name, underWay: false, refusal: [], existing: nil)
    }

    /// The name to create, now under way; nil when nothing can be sent.
    public mutating func sendCreate() -> String? {
        guard let line = create, !line.underWay, !line.name.isEmpty else { return nil }
        create?.underWay = true
        return line.name
    }

    /// Takes the answer to the create sent in `project`, wherever the person is now: a created
    /// workspace is chosen once listed (the request to list it, when that project is shown); a
    /// refusal is said under the name in that project's row, kept to be fixed.
    public mutating func created(_ result: Result<WorkspaceCreated, SailError>, project: String) -> Int? {
        guard let sent = creates[project], sent.underWay else { return nil }
        switch result {
        case let .success(created):
            creates[project] = nil
            choosingAfterList[project] = created.workspace
            return project == chosen ? listAgain() : nil
        case let .failure(error):
            creates[project] = CreateLine(name: sent.name, underWay: false, refusal: lines(error), existing: nil)
            return nil
        }
    }

    // MARK: Stop and delete

    /// The question open now; nil when none is. Never replaced while it is open.
    public private(set) var asking: Question?
    /// Delete Anyway questions that came while another was open, each waiting its turn.
    private var waiting: [Question] = []
    /// Each project's actions under way, by workspace: an answer lands on its own project.
    private var busy: [String: [String: Action]] = [:]
    /// A stop's or a delete's refusal, said over its project's page until a listing asked after the
    /// person has seen it (`since`; nil until they come back to that project).
    private var refused: [String: (notice: Notice, since: Int?)] = [:]
    private var questionsAsked = 0

    /// How many Delete Anyway questions wait behind the one open, for the drive.
    public var questionsWaiting: Int {
        waiting.count
    }

    /// The person chose `action` (Stop, or Delete: never a forced delete) on `name` in the project
    /// shown; `terminals` is how many of their own terminals run in it (nil: not known). A stop of
    /// their own workspace that ends none is sent at once. A stop of someone else's always asks: the
    /// runner tells the person only of their own terminals. Nothing is asked while a question is
    /// open or the row is under way.
    public mutating func ask(_ action: Action, _ name: String, terminals: Int?) -> Asked? {
        guard let project = chosen, busy[project]?[name] == nil, let row = listing.rows.first(where: { $0.name == name }) else { return nil }
        guard asking == nil else {
            notAsked(action, name, project: project)
            return nil
        }
        let mine = row.owner == person.handle
        switch action {
        case .stop:
            if mine, terminals == 0 { return send(.stop, name, project: project) ? .send(project: project, name: name, action: .stop) : nil }
            let owner = row.owner.flatMap { $0 == WorkspaceRow.removedOwner ? nil : $0 }
            return .question(open(Self.stop(name, project: project, ends: mine ? Self.yours(terminals) : owner.map { "Any terminal \($0) has open in it ends." } ?? Self.yours(nil), terminals: mine ? terminals : nil)))
        case .delete:
            return .question(open(Question(
                project: project, name: name, action: .delete(force: false), title: "Delete \(name)?",
                body: "Its files on the runner go with it, and commits you pushed are kept by the repo host. If anything runs in it, or it holds work not pushed, Sail asks again before deleting it.",
                confirm: "Delete Workspace", terminals: nil, detail: nil, asked: 0
            )))
        }
    }

    private static func stop(_ name: String, project: String, ends: String, terminals: Int?) -> Question {
        Question(
            project: project, name: name, action: .stop, title: "Stop \(name)?", body: "\(ends) Its files stay, and Start and Open brings it back.", confirm: "Stop",
            terminals: terminals, detail: nil, asked: 0
        )
    }

    private static func yours(_ terminals: Int?) -> String {
        switch terminals {
        case 1?: "Your terminal in it ends."
        case let count?: "Your \(count) terminals in it end."
        case nil: "Any terminal open in it ends."
        }
    }

    /// A choice not taken because another question is open, said over the page (never over a
    /// refusal) until the next list: choosing it again once that one is answered asks it.
    private mutating func notAsked(_ action: Action, _ name: String, project: String) {
        guard refused[project] == nil else { return }
        let verb = action == .stop ? "Stop" : "Delete"
        refused[project] = (Notice(tone: .warning, title: "\(name) is not asked about yet: answer the question open, then choose \(verb) again.", line: "", tryAgain: false), listing.latest)
    }

    private mutating func open(_ question: Question) -> Question {
        questionsAsked += 1
        let asked = Question(
            project: question.project, name: question.name, action: question.action, title: question.title, body: question.body, confirm: question.confirm,
            terminals: question.terminals, detail: question.detail, asked: questionsAsked
        )
        asking = asked
        return asked
    }

    /// `question` confirmed: what to send, exactly as asked; nil when it is not the question open. The
    /// next question waiting in the project shown is then asked.
    public mutating func confirm(_ question: Question) -> (project: String, name: String, action: Action)? {
        guard asking == question else { return nil }
        asking = nil
        defer { askNextWaiting() }
        return send(question.action, question.name, project: question.project) ? (question.project, question.name, question.action) : nil
    }

    /// `question`, an open stop, asked again in its new words: the person's terminals in it grew to
    /// `terminals` while it was asked. Nil, and nothing changed, when it is not the question open.
    public mutating func askAgain(_ question: Question, terminals: Int) -> Question? {
        guard asking == question, question.action == .stop, question.terminals != nil else { return nil }
        return open(Self.stop(question.name, project: question.project, ends: Self.yours(terminals), terminals: terminals))
    }

    /// `question` cancelled (or answered): the next waiting in the project shown, if any, is asked.
    public mutating func cancel(_ question: Question) {
        guard asking == question else { return }
        asking = nil
        askNextWaiting()
    }

    /// Questions about workspaces no longer in `project`'s list are not asked: a waiting one is
    /// dropped, and an open one is withdrawn and said.
    private mutating func forgetGone(in project: String, request: Int) {
        let names = Set(listing.rows.map(\.name))
        waiting.removeAll { $0.project == project && !names.contains($0.name) }
        guard let open = asking, open.project == project, !names.contains(open.name) else { return }
        asking = nil
        refused[project] = (Notice(tone: .warning, title: "\(open.name) is no longer in \(project), so Sail stopped asking about it.", line: "", tryAgain: false), request)
        askNextWaiting()
    }

    private mutating func askNextWaiting() {
        guard asking == nil, let next = waiting.firstIndex(where: { $0.project == chosen }) else { return }
        asking = waiting.remove(at: next)
    }

    private mutating func send(_ action: Action, _ name: String, project: String) -> Bool {
        guard busy[project]?[name] == nil else { return false }
        busy[project, default: [:]][name] = action
        refused[project] = nil
        return true
    }

    /// Takes the answer to `action` on `name` in `project`, wherever the person is now, and the
    /// request to list that project again when it is shown. A plain delete refused for the work it
    /// holds asks Delete Anyway in the server's sentence (after the question open, if one is); in a
    /// project left, and any other refusal, it is said over that project's page.
    public mutating func changed(_ action: Action, _ name: String, project: String, _ result: Result<WorkspaceChanged, SailError>) -> Int? {
        guard busy[project]?[name] == action else { return nil }
        busy[project]?[name] = nil
        let request = project == chosen ? listAgain() : nil
        switch result {
        case let .success(answer):
            guard request != nil, listing.listed == project else { break }
            switch answer.change {
            case .known(.deleted): listing.settle(name, as: nil)
            case .known(.stopped), .known(.alreadyStopped): listing.settle(name, as: .stopped)
            default: break
            }
        case let .failure(.refused(refusal)) where refusal.code == .known(.workspaceHoldsWork) && action == .delete(force: false) && project == chosen:
            let (said, detail) = Self.holdsWork(refusal.message, name)
            questionsAsked += 1
            let anyway = Question(
                project: project, name: name, action: .delete(force: true), title: "Delete \(name) anyway?", body: said, confirm: "Delete Anyway",
                terminals: nil, detail: detail, asked: questionsAsked
            )
            if asking == nil { asking = anyway } else { waiting.append(anyway) }
        case let .failure(.refused(refusal)) where refusal.code == .known(.workspaceHoldsWork):
            refused[project] = (Notice(tone: .error, title: Self.holdsWork(refusal.message, name).said, line: "", tryAgain: false), request)
        case let .failure(error):
            let said = lines(error)
            refused[project] = (Notice(tone: .error, title: said.first ?? "", line: said.dropFirst().joined(separator: " "), tryAgain: false), request)
        }
        return request
    }

    /// The server's "holds work" sentence as a person reads it: whole when it says what the work is;
    /// "could not tell" in Sail's words when the server could not look, its own text kept for the log.
    private static func holdsWork(_ message: String, _ name: String) -> (said: String, detail: String?) {
        guard message.contains("cannot be told") else { return (message, nil) }
        return ("Sail could not tell whether \(name) holds work, so it does not delete it unasked.", message)
    }

    public mutating func cancelCreate() {
        guard create?.underWay != true else { return }
        create = nil
    }

    // MARK: What is shown

    public var screen: Screen {
        guard let projects else { return projectsFailure.map { .unreachable(unreachable($0)) } ?? .loading }
        let panel = Panel(
            projects: projects.map { Project(name: $0.name, mark: Self.mark($0.template)) }, chosen: thisMacChosen ? Self.thisMac : chosen,
            controlPlane: person.named, who: who,
            emptyLine: projects.isEmpty ? "No project is open to you yet." : nil,
            stale: projectsFailure != nil || (chosen != nil && listing.listed == chosen && listFailure != nil)
        )
        if thisMacChosen { return .shown(panel, .thisMac) }
        guard let project = projects.first(where: { $0.name == chosen }) else {
            let body = person.admin
                ? "Add a project on \(person.controlPlane), or add yourself to one. It then appears on the left, and you can create your own workspaces in it."
                : "An admin adds you to a project on \(person.controlPlane). Once they do, it appears on the left, and you can create your own workspaces in it."
            return .shown(panel, .noProjects(Empty(
                title: "No project is open to you yet", body: body, line: "signed in as \(panel.who) · \(person.controlPlane)", action: .checkAgain, missing: false
            )))
        }
        return .shown(panel, .project(page(project)))
    }

    /// Who is signed in and their role, as the panel's foot says it ("ana · member").
    public var who: String {
        "\(person.handle) · \(person.confirmed ? person.admin ? "admin" : "member" : "role not yet confirmed")"
    }

    /// The projects' refresh that failed while projects are shown, said over the page.
    private var projectsBanner: Notice? {
        projectsFailure.map { Notice(tone: .error, title: "Couldn’t list your projects", line: $0.said(at: person.controlPlane), tryAgain: true) }
    }

    /// The card when no project could be listed: what is true, in Sail's words with the address and
    /// not again what its title says, and that this Mac's terminals are untouched.
    private func unreachable(_ error: SailError) -> Empty {
        var unreached = false
        if case .unreachable = error { unreached = true }
        let network = unreached ? " If it is on your private network, check that the network is connected." : ""
        return Empty(
            title: unreached ? "Sail can’t reach the control plane" : "Sail can’t list your projects",
            body: (unreached && !person.controlPlane.isEmpty ? "\(person.controlPlane) did not answer." : error.said(at: person.controlPlane)) + network
                + " Your terminals on this Mac keep working; your workspaces come back when the control plane answers.",
            line: nil, action: .checkAgain, missing: false
        )
    }

    /// Why New Workspace is not offered in `project`; nil when it is.
    private func createWhy(_ project: ProjectSummary) -> String? {
        if let unready = Self.unready(project) { return unready.why }
        guard let create = creates[project.name] else { return nil }
        return create.underWay ? "The runner creates one workspace at a time" : "A new workspace is open below"
    }

    private var canCreate: Bool {
        guard let project = projects?.first(where: { $0.name == chosen }) else { return false }
        return Self.unready(project) == nil && create == nil
    }

    /// What a project with no current template says, by its template's state: only `ready` names a
    /// current version, so any other state, or none, offers no create.
    private static func unready(_ project: ProjectSummary) -> Unready? {
        let name = project.name
        switch mark(project.template) {
        case nil: return nil
        case .building?:
            return Unready(
                lede: "\(name)’s template is being built; workspaces come after it.", why: "\(name)’s template is being built",
                title: "\(name)’s template is being built", body: "You can create workspaces in \(name) once it is built.",
                withRows: "\(name)’s template is being rebuilt; new workspaces wait for it."
            )
        case .buildFailed?:
            return Unready(
                lede: "\(name) has no template yet, so no workspace can be created in it.", why: "\(name)’s template could not be built",
                title: "\(name)’s template could not be built",
                body: "Its last build failed, so no version of it is current. An admin builds it again on the runner; then you can create workspaces here.",
                withRows: "\(name)’s template could not be built, so no new workspace can be created in it."
            )
        case .noTemplate?:
            return Unready(
                lede: "\(name) has no template yet, so no workspace can be created in it.", why: "\(name) has no template yet", title: "\(name) isn’t ready yet",
                body: "\(name) has no template yet. An admin builds \(name)’s template on the runner first; then you can create workspaces here.",
                withRows: "\(name) has no template now, so no new workspace can be created in it."
            )
        }
    }

    /// What a project whose template is not current says: its line and New Workspace's reason, the
    /// empty state with no workspace listed, and its line over the workspaces it has.
    private struct Unready {
        let lede: String, why: String, title: String, body: String, withRows: String
    }

    /// A project's page. A template not current offers no create, but never hides the workspaces
    /// the project already has: during a rebuild they stay listed, with Open, and New Workspace
    /// says why it waits; only a project with none listed says what is missing in its place.
    private func page(_ project: ProjectSummary) -> Page {
        let name = project.name, listed = listing.listed == name, unready = Self.unready(project)
        let banner = refused[name]?.notice ?? listFailure.map { Notice(tone: .error, title: "Couldn’t list \(name)’s workspaces", line: $0, tryAgain: true) } ?? projectsBanner
        let rows = listed ? WorkspaceList(project: name, workspaces: listing.rows).rows(for: person.handle) : []
        if let unready, rows.isEmpty, listed, listFailure == nil {
            return Page(
                name: name, lede: unready.lede, canCreate: false, createWhy: unready.why, banner: projectsBanner,
                body: .empty(Empty(title: unready.title, body: unready.body, line: nil, action: .checkAgain, missing: true)), stale: false, listedAt: nil
            )
        }
        let body: Body
        let create = creates[name]
        if !listed {
            body = listFailure == nil ? .loading : .unlisted
        } else {
            if rows.isEmpty, create == nil, let unready {
                body = .empty(Empty(title: unready.title, body: unready.body, line: nil, action: .checkAgain, missing: true))
            } else if rows.isEmpty, create == nil {
                body = .empty(Empty(
                    title: "No workspaces in \(name) yet",
                    body: "A workspace is your own copy of \(name) on the runner, created from its template in about 3 s. Open it and it becomes a terminal tab.",
                    line: nil, action: .newWorkspace, missing: false
                ))
            } else {
                let lines = rows.map(line)
                let yours = lines.filter(\.mine), others = lines.filter { !$0.mine }
                let shown = create.map { line in
                    let taken = !line.refusal.isEmpty && listing.rows.contains { $0.name == line.name }
                    return CreateLine(name: line.name, underWay: line.underWay, refusal: line.refusal, existing: taken ? line.name : nil)
                }
                body = .list(Table(yours: yours, others: others, create: shown, creating: creating(in: name), count: lines.count))
            }
        }
        return Page(
            name: name, lede: unready.map { rows.isEmpty ? $0.lede : $0.withRows } ?? "Your own copies of \(name) on the runner. Each one opens as a terminal tab.", canCreate: canCreate,
            createWhy: createWhy(project), banner: banner,
            body: body, stale: listed && listFailure != nil, listedAt: listedAt
        )
    }

    /// The create under way in `project` as its row: Creating, about the server's measured median.
    private func creating(in project: String) -> Line? {
        guard let create = creates[project], create.underWay else { return nil }
        return Line(
            name: create.name, instance: "\(project)-\(create.name)", state: .known(.notCreated), creating: true, mine: true, owner: nil, chosen: false,
            primary: nil, why: "about 3 s", menu: [], sessions: 0, busy: nil, askingAgents: false
        )
    }

    private func line(_ row: WorkspaceRow) -> Line {
        let mine = row.owner == person.handle, opens = row.opens(for: person.handle, admin: person.admin)
        let primary: Primary? = switch row.state {
        case .known(.running): opens ? .open : nil
        case .known(.stopped), .known(.frozen): opens ? .startAndOpen : nil
        default: nil
        }
        let underWay = chosen.flatMap { busy[$0]?[row.name] }
        let entry: [Entry] = primary.map { [$0 == .open ? .open : .startAndOpen] } ?? []
        let acts: [Entry] = opens ? (row.state == .known(.running) || row.state == .known(.frozen) ? [.stop, .delete] : [.delete]) : []
        let agents: [Entry] = mine && primary != nil ? lastStarted.map { [.startLast($0), .newSession] } ?? [.newSession] : []
        let owner: Owner? = switch row.owner {
        case _ where mine: nil
        case nil: .nobody
        case WorkspaceRow.removedOwner?: .inactive
        case let handle?: .person(handle)
        }
        return Line(
            name: row.name, instance: row.instance, state: row.state, creating: false, mine: mine, owner: owner, chosen: row.name == chosenRow,
            primary: underWay == nil ? primary : nil, why: row.whyNotOpened(for: person.handle, admin: person.admin),
            menu: underWay == nil ? entry + agents + [.copyName] + acts : [.copyName],
            sessions: mine && row.state == .known(.running) ? sessions[row.name] ?? 0 : 0, busy: underWay, askingAgents: row.name == askingAgentsIn
        )
    }

    /// What a person reads of a failure, a line each: the server's sentence and what they can do,
    /// and, while their role is not yet confirmed, that the refusal may follow from it.
    private func lines(_ error: SailError) -> [String] {
        var said: [String]
        if case let .refused(refusal) = error { said = refusal.lines } else { said = [error.said(at: person.controlPlane)] }
        if !person.confirmed, case .refused = error {
            said.append("Your role is not yet confirmed: Sail has not reached \(person.controlPlane) to check it.")
        }
        return said
    }

    /// A project's template as both its pill and its page say it: only `ready` names a current
    /// version, so no field, or a word this app does not know, is no template.
    static func mark(_ template: Open<TemplateState>?) -> Mark? {
        switch template {
        case .known(.ready): nil
        case .known(.building): .building
        case .known(.failed): .buildFailed
        default: .noTemplate
        }
    }
}
