import Foundation

/// What the Workspaces view shows, from what the person did and what the server answered: the left
/// panel's projects, and the chosen project's workspaces (yours, then other people's) with opening
/// and creating one. The view only draws ``screen`` and sends the person's actions
/// here; every word a person reads is the server's or this model's, never a command or a route.
public struct WorkspacesModel: Sendable {
    public struct Person: Equatable, Sendable {
        public let handle: String
        public let admin: Bool
        /// Whether the role was confirmed by the control plane this time; not yet, the last confirmed
        /// role is kept for what is offered (#462).
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
        /// The projects could not be listed and nothing is shown: the transport's sentence.
        case unreachable(String)
        case shown(Panel, Content)
    }

    public struct Panel: Equatable, Sendable {
        public let projects: [Project]
        public let chosen: String?
        /// Who is signed in and their role ("ana · member").
        public let who: String
        public let controlPlane: String
    }

    public struct Project: Equatable, Sendable {
        public let name: String
        public let mark: Mark?
    }

    /// A project's template when it is not ready, as its row's pill says it.
    public enum Mark: Equatable, Sendable {
        case noTemplate, building, buildFailed
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
        public let count: Int
    }

    public struct Line: Equatable, Sendable {
        public let name: String
        public let instance: String
        public let state: Open<WorkspaceState>
        public let owner: String?
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

    public struct CreateLine: Equatable, Sendable {
        public var name: String
        public var underWay: Bool
        /// The server's refusal, a line each; empty when none.
        public var refusal: [String]
        /// A workspace of that name already listed, which the refusal offers to show.
        public var existing: String?

        public init(name: String, underWay: Bool, refusal: [String], existing: String?) {
            self.name = name
            self.underWay = underWay
            self.refusal = refusal
            self.existing = existing
        }
    }

    public let person: Person
    public private(set) var chosen: String?
    public private(set) var chosenRow: String?
    private var projects: [ProjectSummary]?
    private var projectsFailure: String?
    private var listing = WorkspaceListing()
    private var listedAt: Date?
    private var listFailure: String?
    private var create: CreateLine?
    private var choosingAfterList: String?

    public init(person: Person) {
        self.person = person
    }

    // MARK: Projects and lists

    /// Takes the projects listed; the first is chosen when none still is. Returns the list request
    /// the chosen project needs: a project chosen again is listed again too, as what was shown
    /// before may have changed, keeping what is under way on it.
    @discardableResult
    public mutating func projectsListed(_ result: Result<ProjectList, SailError>) -> Int? {
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
            projectsFailure = error.description
            return nil
        }
    }

    /// Chooses project `name`, forgetting what was under way on the last one; its list's request.
    public mutating func choose(_ name: String) -> Int {
        chosen = name
        chosenRow = nil
        create = nil
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
            if let wanted = choosingAfterList, listing.rows.contains(where: { $0.name == wanted }) {
                chosenRow = wanted
                choosingAfterList = nil
            }
        case let .failure(error):
            guard listing.isLatest(request) else { return }
            listFailure = error.description
        }
    }

    public mutating func chooseRow(_ name: String?) {
        chosenRow = name
    }

    // MARK: Create

    public mutating func beginCreate() {
        guard create == nil, canCreate else { return }
        create = CreateLine(name: "", underWay: false, refusal: [], existing: nil)
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

    /// Takes the create's answer: a created workspace is listed and chosen (the request to list it),
    /// a refusal is said under the name, kept to be fixed.
    public mutating func created(_ result: Result<WorkspaceCreated, SailError>) -> Int? {
        switch result {
        case let .success(created):
            create = nil
            choosingAfterList = created.workspace
            return listAgain()
        case let .failure(error):
            guard let name = create?.name else { return nil }
            let taken = listing.rows.contains { $0.name == name }
            create = CreateLine(name: name, underWay: false, refusal: lines(error), existing: taken ? name : nil)
            return nil
        }
    }

    public mutating func cancelCreate() {
        guard create?.underWay != true else { return }
        create = nil
    }

    // MARK: What is shown

    public var screen: Screen {
        guard let projects else { return projectsFailure.map(Screen.unreachable) ?? .loading }
        let panel = Panel(
            projects: projects.map { Project(name: $0.name, mark: Self.mark($0.template)) }, chosen: chosen,
            who: "\(person.handle) · \(person.confirmed ? person.admin ? "admin" : "member" : "role not yet confirmed")", controlPlane: person.controlPlane
        )
        guard let project = projects.first(where: { $0.name == chosen }) else {
            return .shown(panel, .noProjects(Empty(
                title: "No project is open to you yet",
                body: "An admin adds you to a project on \(person.controlPlane). Once they do, it appears on the left, and you can create your own workspaces in it.",
                line: "signed in as \(panel.who) · \(person.controlPlane)", action: .checkAgain, missing: false
            )))
        }
        return .shown(panel, .project(page(project)))
    }

    private var canCreate: Bool {
        guard let project = projects?.first(where: { $0.name == chosen }) else { return false }
        return Self.mark(project.template) != .noTemplate && create?.underWay != true
    }

    private func page(_ project: ProjectSummary) -> Page {
        let name = project.name, listed = listing.listed == name
        let banner = listFailure.map { Notice(tone: .error, title: "Couldn’t list \(name)’s workspaces", line: $0, tryAgain: true) }
        guard Self.mark(project.template) != .noTemplate else {
            return Page(
                name: name, lede: "\(name) has no template yet, so no workspace can be created in it.", canCreate: false, banner: banner,
                body: .empty(Empty(
                    title: "\(name) isn’t ready yet",
                    body: "\(name) has no template yet. An admin builds \(name)’s template on the runner first; then you can create workspaces here.",
                    line: nil, action: .checkAgain, missing: true
                )), stale: false, listedAt: nil
            )
        }
        let body: Body
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
                let yours = lines.filter { $0.owner == person.handle }, others = lines.filter { $0.owner != person.handle }
                body = .list(Table(yours: yours, others: others, create: create, count: lines.count))
            }
        }
        return Page(
            name: name, lede: "Your own copies of \(name) on the runner. Each one opens as a terminal tab.", canCreate: canCreate, banner: banner,
            body: body, stale: listed && listFailure != nil, listedAt: listedAt
        )
    }

    private func line(_ row: WorkspaceRow) -> Line {
        let opens = row.owner == person.handle || person.admin
        let primary: Primary? = switch row.state {
        case .known(.running): opens ? .open : nil
        case .known(.stopped), .known(.frozen): opens ? .startAndOpen : nil
        default: nil
        }
        let entry: [Entry] = primary.map { [$0 == .open ? .open : .startAndOpen] } ?? []
        return Line(
            name: row.name, instance: row.instance, state: row.state, owner: row.owner, chosen: row.name == chosenRow, primary: primary,
            why: opens ? nil : "Only \(row.owner ?? "its owner") or an admin opens it", menu: entry + [.copyName]
        )
    }

    /// What a person reads of a failure, a line each: the server's sentence and what they can do,
    /// and, while their role is not yet confirmed, that the refusal may follow from it.
    private func lines(_ error: SailError) -> [String] {
        var said: [String]
        if case let .refused(refusal) = error { said = refusal.lines } else { said = [error.description] }
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
