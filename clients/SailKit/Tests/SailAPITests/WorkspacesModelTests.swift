import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// What the Workspaces view shows, frame by frame of the approved design, from the server's recorded
/// answers: the left panel's projects, a project's workspaces (yours, then other people's), creating,
/// starting, stopping and deleting one, and what is said when the server refuses or cannot be reached.
@Suite struct WorkspacesModelTests {
    static let ana = WorkspacesModel.Person(handle: "ana", admin: false, confirmed: true, controlPlane: "main.acme.dev")
    static let uday = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: "main.acme.dev")
    static let at = Date(timeIntervalSince1970: 1_800_000_000)

    static func answer<Response>(_ endpoint: Endpoint<Response>, _ golden: String) -> Result<Response, SailError> {
        do {
            return .success(try endpoint.decode(response(try Exchange(golden))))
        } catch let error as SailError {
            return .failure(error)
        } catch {
            return .failure(.unreachable("the golden \(golden) could not be read"))
        }
    }

    /// The model with `golden`'s projects listed and harbor chosen and listed from `workspaces`.
    static func listed(_ person: WorkspacesModel.Person, _ workspaces: String = "workspaces-three") -> WorkspacesModel {
        var model = WorkspacesModel(person: person)
        model.projectsListed(answer(.projects, "projects"))
        let request = model.choose("harbor")
        model.listed(answer(.workspaces(project: "harbor"), workspaces), request: request, at: at)
        return model
    }

    static func page(_ model: WorkspacesModel) -> WorkspacesModel.Page? {
        guard case let .shown(_, .project(page)) = model.screen else { return nil }
        return page
    }

    static func table(_ model: WorkspacesModel) -> WorkspacesModel.Table? {
        guard case let .list(table)? = page(model)?.body else { return nil }
        return table
    }

    /// Frame "Loading", then "The control plane cannot be reached": nothing listed yet, the card says why.
    @Test func beforeAnyProjectTheCardSaysWhatIsHappening() {
        var model = WorkspacesModel(person: Self.ana)
        #expect(model.screen == .loading)
        model.projectsListed(.failure(.unreachable("The network connection was lost.")))
        #expect(model.screen == .unreachable("Sail could not reach the control plane: The network connection was lost."))
    }

    /// Frame "No project is open to you": the panel stays, empty, and the identity line says who asks.
    @Test func noProjectSaysWhoIsAsking() {
        var model = WorkspacesModel(person: Self.ana)
        model.projectsListed(.success(ProjectList(projects: [], total: 0)))
        guard case let .shown(panel, .noProjects(empty)) = model.screen else { Issue.record("no empty card: \(model.screen)"); return }
        #expect(panel.projects.isEmpty && panel.who == "ana · member" && panel.controlPlane == "main.acme.dev")
        #expect(empty.title == "No project is open to you yet")
        #expect(empty.body == "An admin adds you to a project on main.acme.dev. Once they do, it appears on the left, and you can create your own workspaces in it.")
        #expect(empty.line == "signed in as ana · member · main.acme.dev" && empty.action == .checkAgain)
    }

    /// Frame "The pane: a project chosen, a row chosen": yours first, then other people's; Open on a
    /// running row, Start and Open on a stopped one; another's row says who may open it.
    @Test func theListIsYoursThenOtherPeoples() throws {
        let model = Self.listed(Self.uday)
        let page = try #require(Self.page(model))
        #expect(page.name == "harbor" && page.lede == "Your own copies of harbor on the runner. Each one opens as a terminal tab." && page.canCreate)
        let table = try #require(Self.table(model))
        #expect(table.yours.map(\.name) == ["dock", "pier"] && table.count == table.yours.count + table.others.count)
        #expect(table.yours[0].primary == .open && table.yours[1].primary == .startAndOpen)
        #expect(table.yours[0].menu == [.open, .copyName] && table.yours[1].menu == [.startAndOpen, .copyName])
        #expect(!table.others.contains { $0.name == "main" }, "the project's own container is no workspace")
        let member = try #require(Self.table(Self.listed(Self.ana)))
        let theirs = try #require(member.others.first)
        #expect(theirs.primary == nil && theirs.why == "Only \(theirs.owner ?? "") or an admin opens it" && theirs.menu == [.copyName])
        let admin = try #require(table.others.first)
        #expect(admin.primary != nil && admin.why == nil, "an admin opens anyone's")
    }

    /// The panel lists every project with its mark: no template, building, build failed, or none.
    @Test func thePanelMarksAProjectWhoseTemplateIsNotReady() {
        var model = WorkspacesModel(person: Self.ana)
        let projects = ["ready", "missing", "building", "failed"].enumerated().map { index, state in
            ProjectSummary(name: "p\(index)", containerStatus: .known(.running), template: .init(rawValue: state))
        }
        model.projectsListed(.success(ProjectList(projects: projects, total: 4)))
        guard case let .shown(panel, _) = model.screen else { Issue.record("no panel"); return }
        #expect(panel.projects.map(\.mark) == [nil, .noTemplate, .building, .buildFailed])
        #expect(panel.chosen == "p0", "the first project is chosen when none was")
    }

    /// Frame "No template yet": the reason in the header, Create unavailable, and who acts next.
    @Test func aProjectWithNoTemplateSaysWhoBuildsIt() throws {
        var model = WorkspacesModel(person: Self.uday)
        model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "demo", containerStatus: .known(.notCreated), template: .known(.missing))], total: 1)))
        let page = try #require(Self.page(model))
        #expect(!page.canCreate && page.lede == "demo has no template yet, so no workspace can be created in it.")
        guard case let .empty(empty) = page.body else { Issue.record("no empty state: \(page.body)"); return }
        #expect(empty.title == "demo isn’t ready yet" && empty.missing && empty.action == .checkAgain)
        #expect(empty.body == "demo has no template yet. An admin builds demo’s template on the runner first; then you can create workspaces here.")
        model.beginCreate()
        model.type("new")
        let sent = model.sendCreate()
        #expect(sent == nil, "a create began where no workspace can be created")
    }

    /// Frame "One project, no workspaces yet".
    @Test func aProjectWithNoWorkspaceOffersTheFirst() throws {
        let page = try #require(Self.page(Self.listed(Self.ana, "workspaces-none")))
        guard case let .empty(empty) = page.body else { Issue.record("no empty state: \(page.body)"); return }
        #expect(empty.title == "No workspaces in harbor yet" && !empty.missing && empty.action == .newWorkspace)
        var model = Self.listed(Self.ana, "workspaces-none")
        model.beginCreate()
        #expect(Self.table(model)?.create != nil, "the first workspace's create row is not shown")
        #expect(empty.body == "A workspace is your own copy of harbor on the runner, created from its template in about 3 s. Open it and it becomes a terminal tab.")
    }

    /// An answer for a project no longer chosen, or overtaken by a later request, is dropped.
    @Test func theLatestListWins() throws {
        var model = WorkspacesModel(person: Self.uday)
        model.projectsListed(.success(ProjectList(projects: [
            ProjectSummary(name: "harbor", containerStatus: .known(.running), template: .known(.ready)),
            ProjectSummary(name: "beacon", containerStatus: .known(.running), template: .known(.ready)),
        ], total: 2)))
        let first = model.choose("harbor")
        let second = model.choose("beacon")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: first, at: Self.at)
        #expect(Self.page(model)?.body == .loading && Self.page(model)?.name == "beacon")
        model.listed(.failure(.unreachable("late")), request: first, at: Self.at)
        #expect(Self.page(model)?.banner == nil, "a failure overtaken by a later request was taken")
        model.listed(.success(WorkspaceList(project: "beacon", workspaces: [])), request: second, at: Self.at)
        guard case .empty? = Self.page(model)?.body else { Issue.record("beacon's list was not taken"); return }
    }

    /// Frame "A refresh fails while a list is shown": the list stays, dimmed, under an error banner.
    @Test func aFailedRefreshKeepsTheListUnderABanner() throws {
        var model = Self.listed(Self.ana)
        let again = model.listAgain()
        model.listed(.failure(.unreachable("The network connection was lost.")), request: again, at: Self.at.addingTimeInterval(240))
        let page = try #require(Self.page(model))
        #expect(page.banner == WorkspacesModel.Notice(tone: .error, title: "Couldn’t list harbor’s workspaces", line: "Sail could not reach the control plane: The network connection was lost.", tryAgain: true))
        #expect(page.stale && page.listedAt == Self.at && Self.table(model)?.yours.isEmpty == false, "the old list stays, dimmed")
        let recovered = model.listAgain()
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: recovered, at: Self.at.addingTimeInterval(300))
        #expect(Self.page(model)?.banner == nil && Self.page(model)?.stale == false)
    }

    /// Frames "Creating a workspace", "under way" and the row that appears chosen.
    @Test func aCreateGoesFromTheRowToTheListedRow() throws {
        var model = Self.listed(Self.uday, "workspaces")
        model.beginCreate()
        #expect(Self.table(model)?.create == WorkspacesModel.CreateLine(name: "", underWay: false, refusal: [], existing: nil))
        model.type("new")
        let sent = model.sendCreate()
        let name = try #require(sent)
        #expect(name == "new" && Self.table(model)?.create?.underWay == true && Self.page(model)?.canCreate == false)
        let second = model.sendCreate()
        #expect(second == nil, "one create at a time")
        model.type("other")
        #expect(Self.table(model)?.create?.name == "new", "typing changed a create under way")
        model.projectsListed(Self.answer(.projects, "projects"))
        #expect(Self.table(model)?.create?.underWay == true, "listing the projects again lost the create")
        let answered = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"))
        let request = try #require(answered)
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-made"), request: request, at: Self.at)
        let table = try #require(Self.table(model))
        #expect(table.create == nil && table.yours.first { $0.chosen }?.name == "new")
    }

    /// Frame "Creating, refused": the server's sentence under the name, kept to be fixed, and the
    /// existing row offered when the name is taken.
    @Test func aRefusedCreateKeepsTheNameAndSaysWhy() throws {
        var model = Self.listed(Self.uday, "workspaces")
        model.beginCreate()
        model.type("dock")
        _ = model.sendCreate()
        let request = model.created(Self.answer(try Endpoint.createWorkspace("dock", project: "harbor"), "refusal-conflict-workspace-exists"))
        #expect(request == nil, "nothing to list again after a refusal")
        let create = try #require(Self.table(model)?.create)
        #expect(create.name == "dock" && !create.underWay && create.existing == "dock")
        #expect(create.refusal == ["Workspace 'dock' of harbor exists already, as the instance 'harbor-dock'."])
        model.type("dock2")
        #expect(Self.table(model)?.create?.refusal == [], "typing clears the refusal")
        model.cancelCreate()
        #expect(Self.table(model)?.create == nil)
    }

    static func refusedCreate(_ person: WorkspacesModel.Person, _ golden: String) throws -> WorkspacesModel.CreateLine? {
        var model = Self.listed(person, "workspaces")
        model.beginCreate()
        model.type("new")
        _ = model.sendCreate()
        _ = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), golden))
        return Self.table(model)?.create
    }

    /// A role not yet confirmed keeps the offers of the role known, the panel says it is unconfirmed,
    /// and a refusal that follows says so (#462); a confirmed role hears nothing of it.
    @Test func anUnconfirmedRoleIsToldWhyARefusalMayFollow() throws {
        let unconfirmed = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: false, controlPlane: "main.acme.dev")
        let model = Self.listed(unconfirmed)
        #expect(Self.table(model)?.others.first?.primary != nil, "the last confirmed role's offers stay")
        guard case let .shown(panel, _) = model.screen else { Issue.record("no panel"); return }
        #expect(panel.who == "uday · role not yet confirmed")
        let refused = try #require(try Self.refusedCreate(unconfirmed, "refusal-read-only-credential-workspace"))
        #expect(refused.refusal.last == "Your role is not yet confirmed: Sail has not reached main.acme.dev to check it.")
        let confirmed = try #require(try Self.refusedCreate(Self.uday, "refusal-read-only-credential-workspace"))
        #expect(!confirmed.refusal.contains { $0.contains("not yet confirmed") })
    }

    /// Nothing a person reads of a refused create names a command, a route or the CLI.
    @Test func noWordsNameACommand() throws {
        for golden in ["refusal-conflict-workspace-exists", "refusal-read-only-credential-workspace", "refusal-project-not-created", "refusal-forbidden"] {
            let refused = try #require(try Self.refusedCreate(Self.uday, golden))
            #expect(!refused.refusal.isEmpty, "\(golden) said nothing")
            for line in refused.refusal {
                #expect(!line.contains("sail ") && !line.contains("/v1/") && !line.contains("--force") && !line.contains("project "), "\(golden): \(line)")
            }
        }
    }
}
