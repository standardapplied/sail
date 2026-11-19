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
        guard case let .unreachable(card) = model.screen else { Issue.record("no card: \(model.screen)"); return }
        #expect(card.title == "Sail can’t reach the control plane" && card.action == .checkAgain)
        #expect(card.body == "main.acme.dev did not answer. If it is on your private network, check that the network is connected. "
            + "Your terminals on this Mac keep working; your workspaces come back when the control plane answers.")
        #expect(!card.body.contains("network connection was lost"), "the transport's own text reached the person")
        model.projectsListed(.failure(.unexpected(route: "GET /v1/projects", status: 502)))
        guard case let .unreachable(proxied) = model.screen else { Issue.record("no card: \(model.screen)"); return }
        #expect(proxied.title == "Sail can’t list your projects")
        model.askingProjects()
        #expect(model.screen == .loading, "Try Again kept the failure up while it asked")
        model.projectsListed(Self.answer(.projects, "projects"))
        model.projectsListed(.failure(.unreachable("The network connection was lost.")))
        model.askingProjects()
        guard case .shown = model.screen else { Issue.record("a listed panel went for a failed refresh: \(model.screen)"); return }
        #expect(proxied.body.hasPrefix("The control plane at main.acme.dev answered with an error (502). Your terminals") && !proxied.body.contains("/v1/"))
    }

    /// Frame "No project is open to you": the panel stays, empty, and the identity line says who asks.
    @Test func noProjectSaysWhoIsAsking() {
        var model = WorkspacesModel(person: Self.ana)
        model.projectsListed(.success(ProjectList(projects: [], total: 0)))
        guard case let .shown(panel, .noProjects(empty)) = model.screen else { Issue.record("no empty card: \(model.screen)"); return }
        #expect(panel.projects.isEmpty && panel.who == "ana · member" && panel.controlPlane == "main.acme.dev" && panel.emptyLine == "No project is open to you yet.")
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
        #expect(table.yours.allSatisfy { $0.mine && $0.owner == nil } && table.others.allSatisfy { !$0.mine && $0.owner != nil }, "an owner is said only on someone else's row")
        let member = try #require(Self.table(Self.listed(Self.ana)))
        let theirs = try #require(member.others.first)
        #expect(theirs.primary == nil && theirs.owner == .person("uday") && theirs.why == "Only uday or an admin opens it" && theirs.menu == [.copyName])
        let admin = try #require(table.others.first)
        #expect(admin.primary != nil && admin.why == nil, "an admin opens anyone's")
    }

    /// The panel lists every project with its mark: no template, building, build failed, or none.
    /// Only `ready` names a current version, so a project whose template the server does not say,
    /// or says in a word this app does not know, is marked as having none, as its page says.
    @Test func thePanelMarksAProjectWhoseTemplateIsNotReady() throws {
        var model = WorkspacesModel(person: Self.ana)
        var projects = ["ready", "missing", "building", "failed", "rebuilding"].enumerated().map { index, state in
            ProjectSummary(name: "p\(index)", containerStatus: .known(.running), template: .init(rawValue: state))
        }
        projects.append(ProjectSummary(name: "p5", containerStatus: .known(.running)))
        model.projectsListed(.success(ProjectList(projects: projects, total: 6)))
        guard case let .shown(panel, _) = model.screen else { Issue.record("no panel"); return }
        #expect(panel.projects.map(\.mark) == [nil, .noTemplate, .building, .buildFailed, .noTemplate, .noTemplate])
        #expect(panel.chosen == "p0", "the first project is chosen when none was")
        for name in ["p4", "p5"] {
            let request = model.choose(name)
            model.listed(.success(WorkspaceList(project: name, workspaces: [])), request: request, at: Self.at)
            let page = try #require(Self.page(model))
            #expect(!page.canCreate && page.createWhy == "\(name) has no template yet", "\(name)'s page and its pill disagree")
        }
    }

    /// Frame "No template yet": the reason in the header, Create unavailable, and who acts next.
    @Test func aProjectWithNoTemplateSaysWhoBuildsIt() throws {
        var model = WorkspacesModel(person: Self.uday)
        let first = model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "demo", containerStatus: .known(.notCreated), template: .known(.missing))], total: 1)))
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: try #require(first), at: Self.at)
        let page = try #require(Self.page(model))
        #expect(!page.canCreate && page.lede == "demo has no template yet, so no workspace can be created in it.")
        #expect(page.createWhy == "demo has no template yet", "New Workspace is off without a reason beside it")
        let again = model.listAgain()
        model.listed(.failure(.unreachable("lost")), request: again, at: Self.at)
        #expect(Self.page(model)?.banner?.title == "Couldn’t list demo’s workspaces", "a failed list is said over a project with no template too (r3-1)")
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

    /// Listing the projects again (the section shown again, ⌘R) lists the chosen project again too.
    @Test func theProjectsListedAgainListTheChosenOneAgain() throws {
        var model = Self.listed(Self.uday, "workspaces")
        #expect(Self.table(model)?.yours.map(\.name) == ["dock"])
        let asked = model.projectsListed(Self.answer(.projects, "projects"))
        let again = try #require(asked, "the chosen project is not listed again")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: again, at: Self.at)
        #expect(Self.table(model)?.yours.map(\.name) == ["dock", "pier"] && model.chosen == "harbor")
    }

    /// Frame "A refresh fails while a list is shown": the list stays, dimmed, under an error banner.
    @Test func aFailedRefreshKeepsTheListUnderABanner() throws {
        var model = Self.listed(Self.ana)
        let again = model.listAgain()
        model.listed(.failure(.unreachable("The network connection was lost.")), request: again, at: Self.at.addingTimeInterval(240))
        let page = try #require(Self.page(model))
        #expect(page.banner == WorkspacesModel.Notice(tone: .error, title: "Couldn’t list harbor’s workspaces", line: "Sail cannot reach the control plane at main.acme.dev.", tryAgain: true))
        #expect(page.stale && page.listedAt == Self.at && Self.table(model)?.yours.isEmpty == false, "the old list stays, dimmed")
        let recovered = model.listAgain()
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: recovered, at: Self.at.addingTimeInterval(300))
        #expect(Self.page(model)?.banner == nil && Self.page(model)?.stale == false)
    }

    /// Frames "Creating a workspace", "under way" and the row that appears chosen.
    @Test func aCreateGoesFromTheRowToTheListedRow() throws {
        var model = Self.listed(Self.uday, "workspaces")
        model.chooseRow("dock")
        model.beginCreate()
        #expect(Self.table(model)?.create == WorkspacesModel.CreateLine(name: "", underWay: false, refusal: [], existing: nil))
        #expect(Self.table(model)?.yours.contains(where: \.chosen) == false, "a row stays chosen beside the open create row")
        #expect(Self.page(model)?.canCreate == false, "New Workspace offered while its row is open")
        #expect(Self.page(model)?.createWhy == "A new workspace is open below" && Self.table(model)?.creating == nil)
        model.type("new")
        let sent = model.sendCreate()
        let name = try #require(sent)
        #expect(name == "new" && Self.table(model)?.create?.underWay == true && Self.page(model)?.canCreate == false)
        #expect(Self.page(model)?.createWhy == "The runner creates one workspace at a time")
        let creating = try #require(Self.table(model)?.creating, "the create under way is not drawn as the row it will be")
        #expect(creating.name == "new" && creating.instance == "harbor-new" && creating.state == .known(.notCreated) && creating.mine)
        #expect(creating.owner == nil && !creating.chosen && creating.primary == nil && creating.why == "about 3 s" && creating.menu.isEmpty)
        let second = model.sendCreate()
        #expect(second == nil, "one create at a time")
        model.type("other")
        #expect(Self.table(model)?.create?.name == "new", "typing changed a create under way")
        model.projectsListed(Self.answer(.projects, "projects"))
        #expect(Self.table(model)?.create?.underWay == true, "listing the projects again lost the create")
        let answered = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor")
        let request = try #require(answered)
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-made"), request: request, at: Self.at)
        let table = try #require(Self.table(model))
        #expect(table.create == nil && table.creating == nil && table.yours.first { $0.chosen }?.name == "new")
        #expect(Self.page(model)?.createWhy == nil)
    }

    /// Frame "Creating, refused": the server's sentence under the name, kept to be fixed, and the
    /// existing row offered when the name is taken.
    @Test func aRefusedCreateKeepsTheNameAndSaysWhy() throws {
        var model = Self.listed(Self.uday, "workspaces")
        model.beginCreate()
        model.type("dock")
        _ = model.sendCreate()
        let request = model.created(Self.answer(try Endpoint.createWorkspace("dock", project: "harbor"), "refusal-conflict-workspace-exists"), project: "harbor")
        #expect(request == nil, "nothing to list again after a refusal")
        let create = try #require(Self.table(model)?.create)
        #expect(create.name == "dock" && !create.underWay && create.existing == "dock")
        #expect(create.refusal == ["Workspace 'dock' of harbor exists already, as the instance 'harbor-dock'."])
        model.chooseRow("dock")
        #expect(Self.table(model)?.yours.first(where: \.chosen)?.name == "dock" && Self.table(model)?.create?.existing == "dock", "Show dock lost the create row")
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
        _ = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), golden), project: "harbor")
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
    // MARK: Review round 1

    static func two(_ person: WorkspacesModel.Person = Self.uday) -> WorkspacesModel {
        var model = WorkspacesModel(person: person)
        let ready = { (name: String) in ProjectSummary(name: name, containerStatus: .known(.running), template: .known(.ready)) }
        model.projectsListed(.success(ProjectList(projects: [ready("harbor"), ready("demo")], total: 2)))
        let request = model.choose("harbor")
        model.listed(answer(.workspaces(project: "harbor"), "workspaces-three"), request: request, at: at)
        return model
    }

    static func send(_ name: String, _ model: inout WorkspacesModel) {
        model.beginCreate()
        model.type(name)
        _ = model.sendCreate()
    }

    /// M1: a refusal lands on the project it was asked for, never under another's field.
    @Test func aRefusalLandsOnItsOwnProject() throws {
        var model = Self.two()
        Self.send("dock", &model)
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [
            WorkspaceRow(name: "a", instance: "demo-a", state: .known(.running), owner: "uday"),
        ])), request: demo, at: Self.at)
        model.beginCreate()
        model.type("fresh")
        let request = model.created(Self.answer(try Endpoint.createWorkspace("dock", project: "harbor"), "refusal-conflict-workspace-exists"), project: "harbor")
        #expect(request == nil)
        #expect(Self.page(model)?.name == "demo")
        let typed = try #require(Self.table(model)?.create)
        #expect(typed == WorkspacesModel.CreateLine(name: "fresh", underWay: false, refusal: [], existing: nil), "harbor's refusal landed in demo: \(typed)")
        let harbor = model.choose("harbor")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: harbor, at: Self.at)
        let back = try #require(Self.table(model)?.create)
        #expect(back.name == "dock" && back.refusal.first?.contains("exists already") == true && back.existing == "dock")
    }

    /// M1: a success in one project leaves another's half-typed name alone, and chooses its row only
    /// in its own project.
    @Test func aSuccessKeepsAnotherProjectsTypedName() throws {
        var model = Self.two()
        Self.send("new", &model)
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [
            WorkspaceRow(name: "a", instance: "demo-a", state: .known(.running), owner: "uday"),
        ])), request: demo, at: Self.at)
        model.beginCreate()
        model.type("half-typed")
        let request = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor")
        #expect(request == nil, "harbor's success listed demo again")
        #expect(Self.table(model)?.create?.name == "half-typed", "harbor's success took demo's typed name")
        let harbor = model.choose("harbor")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-made"), request: harbor, at: Self.at)
        #expect(Self.table(model)?.yours.first(where: \.chosen)?.name == "new", "the created row was not chosen in its own project")
    }

    /// M1: one create at a time in a project, wherever the person went meanwhile; its answer never
    /// erases a row typed since.
    @Test func oneCreateAtATimeInAProjectAcrossChoices() throws {
        var model = Self.two()
        Self.send("new", &model)
        _ = model.choose("demo")
        _ = model.choose("harbor")
        #expect(Self.page(model)?.canCreate == false && Self.page(model)?.createWhy == "The runner creates one workspace at a time")
        model.beginCreate()
        model.type("new2")
        #expect(model.sendCreate() == nil, "a second create was sent while the first was under way")
        #expect(Self.table(model)?.creating?.name == "new", "choosing away and back lost the create under way")
    }

    /// M2: the projects asked again and refused while projects are shown: a banner over the page and
    /// the panel dimmed, never silence; an older answer is dropped.
    @Test func aFailedProjectsRefreshIsSaid() throws {
        var model = Self.listed(Self.ana)
        let older = model.askingProjects(), newer = model.askingProjects()
        model.projectsListed(.failure(.unreachable("lost")), request: newer)
        guard case let .shown(panel, .project(page)) = model.screen else { Issue.record("\(model.screen)"); return }
        #expect(panel.stale && page.banner == WorkspacesModel.Notice(tone: .error, title: "Couldn’t list your projects", line: "Sail cannot reach the control plane at main.acme.dev.", tryAgain: true))
        model.projectsListed(.success(ProjectList(projects: [], total: 0)), request: older)
        guard case .shown(_, .project) = model.screen else { Issue.record("an older answer was taken: \(model.screen)"); return }
        let again = model.askingProjects()
        model.projectsListed(Self.answer(.projects, "projects"), request: again)
        guard case let .shown(fresh, _) = model.screen else { return }
        #expect(!fresh.stale && Self.page(model)?.banner == nil)
    }

    /// M3: only a current template offers New Workspace; each other state says what is true.
    @Test(arguments: [
        (TemplateState.missing, "demo isn’t ready yet", "demo has no template yet"),
        (.failed, "demo’s template could not be built", "demo’s template could not be built"),
        (.building, "demo’s template is being built", "demo’s template is being built"),
    ])
    func onlyACurrentTemplateOffersCreate(_ state: TemplateState, _ title: String, _ why: String) throws {
        var model = WorkspacesModel(person: Self.uday)
        let first = model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "demo", containerStatus: .known(.running), template: .known(state))], total: 1)))
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: try #require(first), at: Self.at)
        let page = try #require(Self.page(model))
        guard case let .empty(empty) = page.body else { Issue.record("\(page.body)"); return }
        #expect(!page.canCreate && page.createWhy == why && empty.title == title && empty.action == .checkAgain)
        #expect(!empty.body.contains("about 3 s") && !page.lede.contains("Your own copies"))
        model.beginCreate()
        #expect(model.sendCreate() == nil)
    }

    /// M5, N3: rows say only what the server's owners mean: no one's, or an account that is not
    /// active (removed or disabled, which the server does not tell apart), never "removed" as a handle.
    @Test func ownersAreSaidAsTheyAre() throws {
        var model = WorkspacesModel(person: Self.ana)
        model.projectsListed(Self.answer(.projects, "projects"))
        let request = model.choose("harbor")
        model.listed(.success(WorkspaceList(project: "harbor", workspaces: [
            WorkspaceRow(name: "orphan", instance: "harbor-orphan", state: .known(.stopped), owner: nil),
            WorkspaceRow(name: "left", instance: "harbor-left", state: .known(.error), owner: WorkspaceRow.removedOwner),
            WorkspaceRow(name: "made", instance: "harbor-made", state: .known(.notCreated), owner: "uday"),
        ])), request: request, at: Self.at)
        let others = try #require(Self.table(model)).others
        let byName = Dictionary(uniqueKeysWithValues: others.map { ($0.name, $0) })
        #expect(byName["orphan"]?.owner == .nobody && byName["orphan"]?.why == "No one owns it: only an admin opens it")
        #expect(byName["left"]?.owner == .inactive && byName["left"]?.why == "Its owner’s account is not active: only an admin opens it")
        #expect(byName["left"]?.state == .known(.error))
        #expect(byName["made"]?.creating == false, "a listed not-created row was drawn as being created here")
    }

    /// m11: a role confirmed or changed keeps what is shown and under way.
    @Test func aNewRoleKeepsWhatIsShown() throws {
        var model = Self.listed(Self.uday)
        Self.send("new", &model)
        model.person = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: false, controlPlane: "main.acme.dev")
        #expect(model.chosen == "harbor" && Self.table(model)?.creating?.name == "new")
    }

    /// m17: an admin with no project is told what they can do, not to wait for an admin.
    @Test func anAdminWithNoProjectIsToldToAddOne() {
        var model = WorkspacesModel(person: Self.uday)
        model.projectsListed(.success(ProjectList(projects: [], total: 0)))
        guard case let .shown(_, .noProjects(empty)) = model.screen else { Issue.record("\(model.screen)"); return }
        #expect(empty.body.hasPrefix("Add a project on main.acme.dev") && !empty.body.contains("An admin adds you"))
    }

    /// m23: the reviewer's surviving mutants, each a behaviour.
    @Test func whatChoosingAndCreatingKeepAndDrop() throws {
        var model = Self.two()
        Self.send("new", &model)
        model.cancelCreate()
        #expect(Self.table(model)?.creating?.name == "new", "Cancel took away a create under way")
        model.chooseRow("dock")
        let failed = model.listAgain()
        model.listed(.failure(.unreachable("lost")), request: failed, at: Self.at)
        let demo = model.choose("demo")
        #expect(model.chosenRow == nil, "the chosen row followed into another project")
        #expect(Self.page(model)?.banner == nil, "harbor's failure was carried into demo")
        model.listed(.failure(.unreachable("lost")), request: demo, at: Self.at)
        #expect(Self.page(model)?.body == .unlisted, "a failed first list was drawn as loading")
        let harbor = model.choose("harbor")
        model.listed(.success(WorkspaceList(project: "harbor", workspaces: [
            WorkspaceRow(name: "paused", instance: "harbor-paused", state: .known(.frozen), owner: "uday"),
        ])), request: harbor, at: Self.at)
        #expect(Self.table(model)?.yours.first?.primary == .startAndOpen, "a paused workspace offered no Start and Open")
        model.chooseRow("paused")
        _ = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor")
        #expect(Self.table(model)?.yours.first(where: \.chosen)?.name == "paused", "the created row was chosen before a list showed it")
    }
}

extension WorkspacesModelTests {
    /// N2: a template being built again keeps the project's workspaces listed, each with Open or
    /// Start and Open as before; only New Workspace waits, saying why. With none listed, the page
    /// says what is missing in their place.
    @Test(arguments: [
        (TemplateState.building, "harbor’s template is being rebuilt; new workspaces wait for it.", "harbor’s template is being built"),
        (.failed, "harbor’s template could not be built, so no new workspace can be created in it.", "harbor’s template could not be built"),
    ])
    func aTemplateNotCurrentKeepsTheWorkspacesListed(_ state: TemplateState, _ lede: String, _ why: String) throws {
        var model = Self.listed(Self.uday)
        let ready = model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "harbor", containerStatus: .known(.running), template: .known(state))], total: 1)))
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: try #require(ready), at: Self.at)
        let page = try #require(Self.page(model))
        let table = try #require(Self.table(model), "the workspaces went while the template is \(state): \(page.body)")
        #expect(table.count == 3 && (table.yours + table.others).allSatisfy { $0.primary != nil }, "Open went from the rows")
        #expect(!page.canCreate && page.createWhy == why && page.lede == lede)
        model.beginCreate()
        model.type("new")
        #expect(model.sendCreate() == nil, "a create was sent while the template is \(state)")
    }

    /// n8: a create's row is chosen once a list shows it; a list from before that does not show it
    /// keeps the row the person chose meanwhile.
    @Test func aCreatedRowIsChosenOnlyOnceListed() throws {
        var model = Self.listed(Self.uday)
        Self.send("new", &model)
        model.chooseRow("pier")
        let made = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: try #require(made), at: Self.at)
        #expect(Self.table(model)?.yours.first(where: \.chosen)?.name == "pier", "a list without the new row dropped the row chosen")
        let again = model.listAgain()
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-made"), request: again, at: Self.at)
        #expect(Self.table(model)?.yours.first(where: \.chosen)?.name == "new")
    }

    /// n1: two projects creating at once: each created row is chosen in its own project's list.
    @Test func twoCreatesAtOnceEachChooseTheirOwnRow() throws {
        var model = Self.two()
        Self.send("new", &model)
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: demo, at: Self.at)
        Self.send("lab", &model)
        let timings = WorkspaceTimings(cloneMs: 0, startMs: 0, readyMs: 0, totalMs: 0, readySteps: [:])
        let lab = WorkspaceCreated(project: "demo", workspace: "lab", instance: "demo-lab", owner: "uday", template: "v1", stale: false, timings: timings)
        #expect(model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor") == nil)
        let demoAgain = model.created(.success(lab), project: "demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [
            WorkspaceRow(name: "lab", instance: "demo-lab", state: .known(.running), owner: "uday"),
        ])), request: try #require(demoAgain), at: Self.at)
        #expect(Self.table(model)?.yours.first(where: \.chosen)?.name == "lab", "demo's created row was not chosen")
        let harbor = model.choose("harbor")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-made"), request: harbor, at: Self.at)
        #expect(Self.table(model)?.yours.first(where: \.chosen)?.name == "new", "harbor's created row was not chosen after demo's")
    }

    /// n8: a row created in demo is never chosen in harbor's list, though harbor has a row of its name.
    @Test func aCreatedRowIsChosenOnlyInItsOwnProject() throws {
        var model = Self.two()
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: demo, at: Self.at)
        Self.send("pier", &model)
        let harbor = model.choose("harbor")
        let timings = WorkspaceTimings(cloneMs: 0, startMs: 0, readyMs: 0, totalMs: 0, readySteps: [:])
        let pier = WorkspaceCreated(project: "demo", workspace: "pier", instance: "demo-pier", owner: "uday", template: "v1", stale: false, timings: timings)
        #expect(model.created(.success(pier), project: "demo") == nil, "demo's create listed harbor")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: harbor, at: Self.at)
        #expect(Self.table(model)?.yours.first(where: \.chosen) == nil, "demo's created pier was chosen in harbor's list")
    }

    /// n8: a list that fails dims the panel too, as a projects failure does.
    @Test func aFailedListDimsThePanel() {
        var model = Self.listed(Self.ana)
        let again = model.listAgain()
        model.listed(.failure(.unreachable("lost")), request: again, at: Self.at)
        guard case let .shown(panel, _) = model.screen else { Issue.record("no panel"); return }
        #expect(panel.stale, "the panel stayed bright over a list that failed")
    }

    /// n8: a projects refresh that fails is said over a project not ready too.
    @Test func aFailedProjectsRefreshIsSaidOverAnUnreadyProject() throws {
        var model = WorkspacesModel(person: Self.ana)
        let first = model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "demo", containerStatus: .known(.running), template: .known(.missing))], total: 1)))
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: try #require(first), at: Self.at)
        let again = model.askingProjects()
        model.projectsListed(.failure(.unreachable("lost")), request: again)
        #expect(Self.page(model)?.banner?.title == "Couldn’t list your projects")
    }

    /// n8: "Show <name>" is offered only for a name the server refused as taken, not for one
    /// typed that is also listed.
    @Test func showIsOfferedOnlyForARefusedName() {
        var model = Self.listed(Self.uday)
        model.beginCreate()
        model.type("dock")
        #expect(Self.table(model)?.create?.existing == nil, "Show dock offered before any refusal")
    }

    /// n8: once a create is answered, a second answer to it changes nothing: the refusal and its
    /// name stay, and nothing is listed again.
    @Test func aSecondAnswerToACreateIsDropped() throws {
        var model = Self.listed(Self.uday)
        Self.send("dock", &model)
        _ = model.created(Self.answer(try Endpoint.createWorkspace("dock", project: "harbor"), "refusal-conflict-workspace-exists"), project: "harbor")
        let late = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor")
        #expect(late == nil && Self.table(model)?.create?.refusal.isEmpty == false && Self.table(model)?.create?.name == "dock")
    }
}

extension WorkspacesModelTests {
    /// r3-1: a project whose template is not current says when its list fails, over the rows it
    /// had or none, as any project does; it never hides the failure behind its template's words.
    @Test func aFailedListIsSaidOverAProjectWhoseTemplateIsNotCurrent() throws {
        let building = ProjectList(projects: [ProjectSummary(name: "harbor", containerStatus: .known(.running), template: .known(.building))], total: 1)
        var never = WorkspacesModel(person: Self.uday)
        let first = never.projectsListed(.success(building))
        never.listed(.failure(.unexpected(route: "GET /v1/projects/harbor/workspaces", status: 502)), request: try #require(first), at: Self.at)
        let unlisted = try #require(Self.page(never))
        #expect(unlisted.banner?.title == "Couldn’t list harbor’s workspaces" && unlisted.body == .unlisted, "\(unlisted.body)")
        var listed = Self.listed(Self.uday)
        let again = listed.projectsListed(.success(building))
        listed.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: try #require(again), at: Self.at)
        let failed = listed.listAgain()
        listed.listed(.failure(.unexpected(route: "GET /v1/projects/harbor/workspaces", status: 502)), request: failed, at: Self.at)
        let kept = try #require(Self.page(listed))
        #expect(kept.banner?.title == "Couldn’t list harbor’s workspaces" && kept.stale && Self.table(listed)?.count == 3, "the rows it had went: \(kept.body)")
        var empty = WorkspacesModel(person: Self.uday)
        let none = empty.projectsListed(.success(building))
        empty.listed(.success(WorkspaceList(project: "harbor", workspaces: [])), request: try #require(none), at: Self.at)
        let retry = empty.listAgain()
        empty.listed(.failure(.unexpected(route: "GET /v1/projects/harbor/workspaces", status: 502)), request: retry, at: Self.at)
        let said = try #require(Self.page(empty))
        guard case let .empty(state) = said.body else { Issue.record("\(said.body)"); return }
        #expect(said.banner?.title == "Couldn’t list harbor’s workspaces" && state.title == "harbor’s template is being built" && state.action == .checkAgain)
    }
}
