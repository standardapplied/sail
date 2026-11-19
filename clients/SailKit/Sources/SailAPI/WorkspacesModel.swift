import Foundation

/// What the Workspaces view shows, from what the person did and what the server answered: the left
/// panel's projects, and the chosen project's workspaces (yours, then other people's) with opening
/// and creating one. The view only draws ``screen`` and sends the person's actions
/// here; every word a person reads is the server's or this model's, never a command or a route.
public struct WorkspacesModel: Sendable {
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
    }

    public enum Screen: Equatable, Sendable {
        /// Nothing listed yet.
        case loading
        /// The projects could not be listed and nothing is shown: what went wrong, and that the
        /// terminals here are untouched.
        case unreachable(Empty)
        case shown(Panel, Content)
    }

    public struct Panel: Equatable, Sendable {
        public let projects: [Project]
        public let chosen: String?
        /// Who is signed in and their role ("ana · member").
        public let who: String
        public let controlPlane: String
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
        /// The server names no one: the workspace's owner is no longer a member.
        case formerMember
        /// No one owns it.
        case nobody
    }

    public enum Content: Equatable, Sendable {
        case noProjects(Empty)
        case project(Page)
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
        /// Its ⋯ menu.
        public let menu: [Entry]
    }

    public enum Primary: Equatable, Sendable {
        case open, startAndOpen
    }

    public enum Entry: Equatable, Sendable {
        case open, startAndOpen, copyName
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
    public private(set) var chosen: String?
    public private(set) var chosenRow: String?
    private var projects: [ProjectSummary]?
    private var projectsFailure: SailError?
    private var projectsAsked = 0
    private var listing = WorkspaceListing()
    private var listedAt: Date?
    private var listFailure: String?
    private var creates: [String: CreateLine] = [:]
    /// The workspace created in this project, chosen once a list shows it.
    private var choosingAfterList: (project: String, name: String)?

    /// The chosen project's create row.
    private var create: CreateLine? {
        get { chosen.flatMap { creates[$0] } }
        set { if let chosen { creates[chosen] = newValue } }
    }

    public init(person: Person) {
        self.person = person
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
            return name == chosen ? listAgain() : choose(name)
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

    /// Chooses project `name`, its list's request. A create row typed or sent in a project stays
    /// with it.
    public mutating func choose(_ name: String) -> Int {
        chosen = name
        chosenRow = nil
        listFailure = nil
        listedAt = nil
        return listing.ask(name)
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
            if let wanted = choosingAfterList, wanted.project == listing.listed, listing.rows.contains(where: { $0.name == wanted.name }) {
                chosenRow = wanted.name
                choosingAfterList = nil
            }
        case let .failure(error):
            guard listing.isLatest(request) else { return }
            listFailure = error.said(at: person.controlPlane)
        }
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
            choosingAfterList = (project, created.workspace)
            return project == chosen ? listAgain() : nil
        case let .failure(error):
            creates[project] = CreateLine(name: sent.name, underWay: false, refusal: lines(error), existing: nil)
            return nil
        }
    }

    public mutating func cancelCreate() {
        guard create?.underWay != true else { return }
        create = nil
    }

    // MARK: What is shown

    public var screen: Screen {
        guard let projects else { return projectsFailure.map { .unreachable(unreachable($0)) } ?? .loading }
        let panel = Panel(
            projects: projects.map { Project(name: $0.name, mark: Self.mark($0.template)) }, chosen: chosen, who: who, controlPlane: person.controlPlane,
            emptyLine: projects.isEmpty ? "No project is open to you yet." : nil,
            stale: projectsFailure != nil || (chosen != nil && listing.listed == chosen && listFailure != nil)
        )
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
    private static func unready(_ project: ProjectSummary) -> (lede: String, why: String, title: String, body: String)? {
        let name = project.name
        switch project.template {
        case .known(.ready): return nil
        case .known(.building):
            return (
                "\(name)’s template is being built; workspaces come after it.", "\(name)’s template is being built", "\(name)’s template is being built",
                "You can create workspaces in \(name) once it is built."
            )
        case .known(.failed):
            return (
                "\(name) has no template yet, so no workspace can be created in it.", "\(name)’s template could not be built",
                "\(name)’s template could not be built", "Its last build failed, so no version of it is current. An admin builds it again on the runner; then you can create workspaces here."
            )
        default:
            return (
                "\(name) has no template yet, so no workspace can be created in it.", "\(name) has no template yet", "\(name) isn’t ready yet",
                "\(name) has no template yet. An admin builds \(name)’s template on the runner first; then you can create workspaces here."
            )
        }
    }

    private func page(_ project: ProjectSummary) -> Page {
        let name = project.name, listed = listing.listed == name
        let banner = listFailure.map { Notice(tone: .error, title: "Couldn’t list \(name)’s workspaces", line: $0, tryAgain: true) } ?? projectsBanner
        if let unready = Self.unready(project) {
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
            let rows = WorkspaceList(project: name, workspaces: listing.rows).rows(for: person.handle)
            if rows.isEmpty, create == nil {
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
            name: name, lede: "Your own copies of \(name) on the runner. Each one opens as a terminal tab.", canCreate: canCreate,
            createWhy: createWhy(project), banner: banner,
            body: body, stale: listed && listFailure != nil, listedAt: listedAt
        )
    }

    /// The create under way in `project` as its row: Creating, about the server's measured median.
    private func creating(in project: String) -> Line? {
        guard let create = creates[project], create.underWay else { return nil }
        return Line(
            name: create.name, instance: "\(project)-\(create.name)", state: .known(.notCreated), creating: true, mine: true, owner: nil, chosen: false,
            primary: nil, why: "about 3 s", menu: []
        )
    }

    private func line(_ row: WorkspaceRow) -> Line {
        let mine = row.owner == person.handle, opens = row.opens(for: person.handle, admin: person.admin)
        let primary: Primary? = switch row.state {
        case .known(.running): opens ? .open : nil
        case .known(.stopped), .known(.frozen): opens ? .startAndOpen : nil
        default: nil
        }
        let entry: [Entry] = primary.map { [$0 == .open ? .open : .startAndOpen] } ?? []
        let owner: Owner? = switch row.owner {
        case _ where mine: nil
        case nil: .nobody
        case WorkspaceRow.removedOwner?: .formerMember
        case let handle?: .person(handle)
        }
        return Line(
            name: row.name, instance: row.instance, state: row.state, creating: false, mine: mine, owner: owner, chosen: row.name == chosenRow,
            primary: primary, why: row.whyNotOpened(for: person.handle, admin: person.admin), menu: entry + [.copyName]
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

    static func mark(_ template: Open<TemplateState>?) -> Mark? {
        switch template {
        case .known(.missing): .noTemplate
        case .known(.building): .building
        case .known(.failed): .buildFailed
        default: nil
        }
    }
}
