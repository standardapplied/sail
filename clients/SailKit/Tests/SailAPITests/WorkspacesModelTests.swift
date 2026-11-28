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

    /// The project a person chose last opens while it is listed; one no longer listed gives way to
    /// the first.
    @Test func theProjectChosenLastOpensWhileItIsListed() {
        let list = ProjectList(projects: ["harbor", "lighthouse"].map { ProjectSummary(name: $0, containerStatus: .known(.running), template: .known(.ready)) }, total: 2)
        var kept = WorkspacesModel(person: Self.uday, chosen: "lighthouse")
        kept.projectsListed(.success(list))
        var gone = WorkspacesModel(person: Self.uday, chosen: "wreck")
        gone.projectsListed(.success(list))
        #expect(kept.chosen == "lighthouse" && gone.chosen == "harbor")
    }
    static let at = Date(timeIntervalSince1970: 1_800_000_000)

    /// The panel's two groups: This Mac, the one local project, first, and chosen when it is; then
    /// the control plane's projects under its host as a person reads it, with their count.
    @Test func thisMacIsListedFirstApartFromTheControlPlanesProjects() throws {
        var model = WorkspacesModel(person: WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: "https://main.acme.dev:8443"))
        model.projectsListed(Self.answer(.projects, "projects"))
        guard case let .shown(panel, .project) = model.screen else {
            Issue.record("no project shown: \(model.screen)")
            return
        }
        #expect(panel.controlPlane == "main.acme.dev:8443" && panel.projects.map(\.name) == ["harbor"] && panel.chosen == "harbor")
        model.chooseThisMac()
        guard case let .shown(local, .thisMac) = model.screen else {
            Issue.record("This Mac not shown: \(model.screen)")
            return
        }
        #expect(local.chosen == WorkspacesModel.thisMac && local.controlPlane == "main.acme.dev:8443" && local.projects.map(\.name) == ["harbor"])
    }

    /// Choosing This Mac asks the control plane nothing and sends no name of it anywhere: the
    /// project chosen there stays the one its requests name, and listing the projects again lists
    /// that project, never This Mac. Chosen last, This Mac opens again; choosing a project leaves it.
    @Test func thisMacIsChosenWithoutAskingTheControlPlane() throws {
        var model = Self.listed(Self.uday)
        model.chooseThisMac()
        #expect(model.chosen == "harbor", "This Mac took the control plane's project: \(model.chosen ?? "none")")
        let again = model.projectsListed(Self.answer(.projects, "projects"))
        #expect(again != nil && model.chosen == "harbor")
        guard case .shown(_, .thisMac) = model.screen else {
            Issue.record("listing the projects left This Mac: \(model.screen)")
            return
        }
        var reopened = WorkspacesModel(person: Self.uday, chosen: WorkspacesModel.thisMac)
        reopened.projectsListed(Self.answer(.projects, "projects"))
        guard case let .shown(panel, .thisMac) = reopened.screen else {
            Issue.record("This Mac chosen last did not open: \(reopened.screen)")
            return
        }
        #expect(panel.chosen == WorkspacesModel.thisMac && reopened.chosen == "harbor")
        _ = reopened.choose("harbor")
        guard case .shown(_, .project) = reopened.screen else {
            Issue.record("harbor chosen, still \(reopened.screen)")
            return
        }
    }

    /// This Mac needs nothing from the control plane: chosen last it opens before any project is
    /// listed, chosen while they load it shows at once, and it stays when their first listing fails,
    /// the control plane's group then counting nothing and saying why under its head. Not chosen,
    /// the section waits or says why as before, the same panel beside it.
    @Test func thisMacIsShownWhileNoProjectIsListed() {
        var reopened = WorkspacesModel(person: Self.uday, chosen: WorkspacesModel.thisMac)
        guard case let .shown(loading, .thisMac) = reopened.screen else {
            Issue.record("This Mac chosen last waited for the control plane: \(reopened.screen)")
            return
        }
        #expect(!loading.listed && loading.projects.isEmpty && loading.emptyLine == nil && loading.chosen == WorkspacesModel.thisMac)
        reopened.projectsListed(.failure(.unreachable("The network connection was lost.")))
        guard case let .shown(failed, .thisMac) = reopened.screen else {
            Issue.record("a failed listing took This Mac away: \(reopened.screen)")
            return
        }
        #expect(!failed.listed && !failed.stale && failed.emptyLine == "Can’t reach the control plane.")
        reopened.projectsListed(.failure(.unexpected(route: "GET /v1/projects", status: 502)))
        #expect(reopened.panel.emptyLine == "Couldn’t list your projects.")
        reopened.projectsListed(Self.answer(.projects, "projects"))
        #expect(reopened.panel.listed && reopened.panel.emptyLine == nil && reopened.panel.projects.map(\.name) == ["harbor"])

        var clicked = WorkspacesModel(person: Self.uday)
        #expect(clicked.screen == .loading && !clicked.panel.listed && clicked.panel.emptyLine == nil)
        clicked.chooseThisMac()
        guard case .shown(_, .thisMac) = clicked.screen else {
            Issue.record("This Mac chosen while the projects load is not shown: \(clicked.screen)")
            return
        }
        clicked.projectsListed(.failure(.unreachable("lost")))
        guard case .shown(_, .thisMac) = clicked.screen else {
            Issue.record("the listing then failed and took This Mac away: \(clicked.screen)")
            return
        }

        var away = WorkspacesModel(person: Self.uday)
        away.projectsListed(.failure(.unreachable("lost")))
        guard case .unreachable = away.screen else {
            Issue.record("no card while nothing could be listed: \(away.screen)")
            return
        }
        #expect(!away.panel.listed && away.panel.emptyLine == "Can’t reach the control plane.")
    }

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

    /// R7: a person's own running workspace says how many of their live sessions run in it, beside
    /// its state, "1 session" for one; an ended one, someone else's, another workspace's, another
    /// project's of the same name, or someone else's row count nothing.
    @Test func aRunningWorkspaceOfTheirsSaysHowManyOfTheirSessionsRunInIt() {
        func session(_ name: String, live: Bool = true, opener: String = "uday") -> RunnerSession {
            RunnerSession(name: name, live: live, attached: 0, writer: "", opener: opener, command: [], createdAt: Self.at, endedReason: nil)
        }
        var model = Self.listed(Self.uday)
        func listed(_ sessions: [RunnerSession], project: String = "harbor", workspace: String) {
            model.sessionsListed(RunnerSessions(sessions: sessions), project: project, workspace: workspace, request: model.askingSessions(of: workspace))
        }
        listed([session("app-a"), session("app-b"), session("app-c", live: false), session("app-d", opener: "ana")], workspace: "dock")
        listed([session("app-e")], workspace: "pier")
        listed([session("app-f")], workspace: "quay")
        listed([session("app-g"), session("app-h"), session("app-i")], project: "other", workspace: "dock")
        let lines = (Self.table(model)?.yours ?? []) + (Self.table(model)?.others ?? [])
        #expect(lines.map { "\($0.name) \($0.sessionsSaid ?? "-")" } == ["dock 2 sessions", "pier -", "quay -"], "a late count from another project's dock lands nowhere")
        listed([session("app-a")], workspace: "dock")
        #expect(Self.table(model)?.yours.first { $0.name == "dock" }?.sessionsSaid == "1 session")
        _ = model.choose("harbor")
        #expect(Self.table(model)?.yours.allSatisfy { $0.sessions == 0 } != false, "a new choice forgets the counts")
    }

    /// Of two counts asked of one workspace, an older answer arriving last never replaces the newer.
    @Test func anOlderCountNeverReplacesANewerOne() {
        func session(_ name: String) -> RunnerSession {
            RunnerSession(name: name, live: true, attached: 0, writer: "", opener: "uday", command: [], createdAt: Self.at, endedReason: nil)
        }
        var model = Self.listed(Self.uday)
        let older = model.askingSessions(of: "dock"), newer = model.askingSessions(of: "dock")
        model.sessionsListed(RunnerSessions(sessions: [session("app-a"), session("app-b")]), project: "harbor", workspace: "dock", request: newer)
        model.sessionsListed(RunnerSessions(sessions: [session("app-a")]), project: "harbor", workspace: "dock", request: older)
        #expect(Self.table(model)?.yours.first { $0.name == "dock" }?.sessions == 2)
    }

    /// A person's terminals in a listing are its live sessions they opened, newest first as listed:
    /// not an ended one, not someone else's.
    @Test func aPersonsTerminalsAreTheLiveSessionsTheyOpened() {
        func session(_ name: String, live: Bool = true, opener: String = "uday") -> RunnerSession {
            RunnerSession(name: name, live: live, attached: 0, writer: "", opener: opener, command: [], createdAt: Self.at, endedReason: nil)
        }
        let listing = RunnerSessions(sessions: [session("app-b"), session("app-c", live: false), session("app-d", opener: "ana"), session("app-a")])
        #expect(listing.live(for: "uday").map(\.name) == ["app-b", "app-a"])
        #expect(listing.live(for: "ana").map(\.name) == ["app-d"])
        #expect(listing.live(for: "bo").isEmpty)
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
        #expect(panel.projects.isEmpty && panel.who == "ana · member" && panel.emptyLine == "No project is open to you yet.")
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
        #expect(table.yours[0].menu == [.open, .newSession, .copyName, .stop, .delete] && table.yours[1].menu == [.startAndOpen, .newSession, .copyName, .delete])
        #expect(!table.others.contains { $0.name == "main" }, "the project's own container is no workspace")
        #expect(table.yours.allSatisfy { $0.mine && $0.owner == nil } && table.others.allSatisfy { !$0.mine && $0.owner != nil }, "an owner is said only on someone else's row")
        let member = try #require(Self.table(Self.listed(Self.ana)))
        let theirs = try #require(member.others.first)
        #expect(theirs.primary == nil && theirs.owner == .person("uday") && theirs.why == "Only uday or an admin opens it" && theirs.menu == [.copyName])
        let admin = try #require(table.others.first)
        #expect(admin.primary != nil && admin.why == nil, "an admin opens anyone's")
    }

    /// The foot's keys, each only while it does what it says: none with no row chosen, a row not
    /// listed or the row a create under way draws; Return on a row it opens, never one under way or
    /// someone else's a member cannot open; Return and ⌥⌘T only while the list holds the keys; ⌘N,
    /// New Workspace's, wherever in the window they are, only while a workspace can be made; none
    /// while a sheet over the window, or another window, holds them.
    @Test func theFootNamesAKeyOnlyWhileItWorks() throws {
        var model = Self.listed(Self.uday)
        let page = try #require(Self.page(model))
        #expect(page.keys(chosen: nil, held: .list) == [] && page.keys(chosen: "gone", held: .list) == [])
        #expect(page.keys(chosen: "dock", held: .list) == [.open, .newSession, .newWorkspace])
        #expect(page.keys(chosen: "pier", held: .list) == [.open, .newSession, .newWorkspace], "Return starts and opens pier")
        #expect(page.keys(chosen: "dock", held: .window) == [.newWorkspace], "the list's keys are named while another of the window holds them")
        #expect(page.keys(chosen: "dock", held: .none) == [], "keys are named while a sheet or another window holds them")
        let member = Self.listed(Self.ana)
        let theirs = try #require(Self.table(member)?.others.first?.name)
        #expect(Self.page(member)?.keys(chosen: theirs, held: .list) == [.newSession, .newWorkspace], "Return is named on a row it presses nothing on")
        _ = model.ask(.stop, "dock", terminals: 0)
        #expect(Self.page(model)?.keys(chosen: "dock", held: .list) == [.newSession, .newWorkspace], "Return is named on a row under way")
        Self.send("new", &model)
        let creating = try #require(Self.page(model))
        #expect(creating.keys(chosen: "new", held: .list) == [], "the row a create under way draws names keys")
        #expect(creating.keys(chosen: "pier", held: .list) == [.open, .newSession], "⌘N is named while no workspace can be made")
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
        #expect(request.flatMap(model.project(of:)) == "harbor", "harbor's success listed demo again")
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

    /// M3: only a current template offers New Workspace; each other state says what is true: no
    /// template says what is missing, and a build under way or failed waits for its template's read,
    /// which draws its card (`BuildCardTests`).
    @Test(arguments: [
        (TemplateState.missing, "demo has no template yet"), (.failed, "demo’s template could not be built"), (.building, "demo’s template is being built"),
    ])
    func onlyACurrentTemplateOffersCreate(_ state: TemplateState, _ why: String) throws {
        var model = WorkspacesModel(person: Self.uday)
        let first = model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "demo", containerStatus: .known(.running), template: .known(state))], total: 1)))
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: try #require(first), at: Self.at)
        let page = try #require(Self.page(model))
        #expect(!page.canCreate && page.createWhy == why && !page.lede.contains("Your own copies"))
        if state == .missing {
            guard case let .empty(empty) = page.body else { Issue.record("\(page.body)"); return }
            #expect(empty.title == "demo isn’t ready yet" && empty.action == .checkAgain && !empty.body.contains("about 3 s"))
        } else {
            #expect(page.body == .loading && page.lede == "Workspaces open once the template is ready.", "\(page.body)")
        }
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

    /// m17: an admin with no project, whose sign-in may not create one, is told so, not to wait for
    /// an admin.
    @Test func anAdminWithNoProjectIsToldWhatIsTrue() {
        var model = WorkspacesModel(person: Self.uday)
        model.projectsListed(.success(ProjectList(projects: [], total: 0)))
        guard case let .shown(_, .noProjects(empty)) = model.screen else { Issue.record("\(model.screen)"); return }
        #expect(empty.body == "Your sign-in may not create projects on main.acme.dev. Once one is added, it appears on the left, and you can create your own workspaces in it.")
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
        let harborAgain = model.created(Self.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor")
        #expect(harborAgain.flatMap(model.project(of:)) == "harbor", "harbor's create did not list harbor again")
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
        let demoAgain = model.created(.success(pier), project: "demo")
        #expect(demoAgain.flatMap(model.project(of:)) == "demo", "demo's create listed another project")
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
        #expect(Self.page(empty)?.banner?.title == "Couldn’t list harbor’s workspaces" && Self.page(empty)?.body == .loading)
        empty.templateRead("harbor", Self.answer(.template(project: "harbor"), "template-2e-building"))
        let said = try #require(Self.page(empty))
        guard case let .build(card) = said.body else { Issue.record("\(said.body)"); return }
        #expect(said.banner?.title == "Couldn’t list harbor’s workspaces" && card.title == "Creating harbor")
    }
}

/// 1c: Stop and Delete ask in a question that is a value: confirming sends exactly the question
/// shown, never another; a refusal that comes while one is open waits its turn; a forced delete is
/// only ever the question made from the server's "holds work" sentence for that workspace.
extension WorkspacesModelTests {
    static func row(_ model: WorkspacesModel, _ name: String) -> WorkspacesModel.Line? {
        table(model).flatMap { ($0.yours + $0.others).first { $0.name == name } }
    }

    static func question(_ asked: WorkspacesModel.Asked?) -> WorkspacesModel.Question? {
        guard case let .question(question)? = asked else { return nil }
        return question
    }

    /// The question `model` asks for `action` on `name`, or a failure.
    static func asks(_ model: inout WorkspacesModel, _ action: WorkspacesModel.Action, _ name: String, terminals: Int?) throws -> WorkspacesModel.Question {
        let asked = model.ask(action, name, terminals: terminals)
        return try #require(question(asked), "\(name): \(String(describing: asked))")
    }

    /// `question` confirmed in `model`, what it sends, or a failure.
    static func confirms(_ model: inout WorkspacesModel, _ question: WorkspacesModel.Question) throws -> (project: String, name: String, action: WorkspacesModel.Action) {
        let sent = model.confirm(question)
        return try #require(sent, "\(question.title) confirmed nothing")
    }

    static func holdsWork(_ name: String) throws -> Result<WorkspaceChanged, SailError> {
        Self.answer(try Endpoint.deleteWorkspace(name, project: "harbor"), "refusal-workspace-holds-work")
    }

    static let stopped = Result<WorkspaceChanged, SailError>.success(WorkspaceChanged(project: "harbor", workspace: "dock", instance: "harbor-dock", change: .known(.stopped), timings: nil))

    @Test func whoeverOpensARowMayStopOrDeleteIt() throws {
        let admin = Self.listed(Self.uday), member = Self.listed(Self.ana)
        #expect(Self.row(admin, "dock")?.menu == [.open, .newSession, .copyName, .stop, .delete])
        #expect(Self.row(admin, "pier")?.menu == [.startAndOpen, .newSession, .copyName, .delete], "a stopped row offers a stop")
        #expect(Self.row(admin, "quay")?.menu == [.open, .copyName, .stop, .delete], "an admin may stop or delete anyone's")
        #expect(Self.row(member, "dock")?.menu == [.copyName], "a member may stop or delete another's")
        var paused = WorkspacesModel(person: Self.uday)
        let first = paused.projectsListed(Self.answer(.projects, "projects"))
        paused.listed(.success(WorkspaceList(project: "harbor", workspaces: [WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.frozen), owner: "uday")])), request: try #require(first), at: Self.at)
        #expect(Self.row(paused, "dock")?.menu == [.startAndOpen, .newSession, .copyName, .stop, .delete], "a paused workspace, which can be stopped, offers no Stop")
    }

    /// While what starts in a workspace is asked, its row says so, and no other row does.
    @Test func aRowSaysWhatStartsInItIsBeingAsked() throws {
        var model = Self.listed(Self.uday)
        #expect(Self.row(model, "dock")?.askingAgents == false)
        model.askingAgentsIn = "dock"
        #expect(Self.row(model, "dock")?.askingAgents == true && Self.row(model, "pier")?.askingAgents == false)
        model.askingAgentsIn = nil
        #expect(Self.row(model, "dock")?.askingAgents == false)
    }

    /// An agent starts only in the person's own workspace: their own rows that open offer New
    /// Session, and, once a harness was started, that harness again before it; someone else's row
    /// offers neither, an admin's included, nor does a row under way or one that does not open.
    @Test func onlyThePersonsOwnRowsOfferANewSession() throws {
        var admin = Self.listed(Self.uday)
        #expect(Self.row(admin, "quay")?.menu.contains(.newSession) == false, "an admin is offered an agent in someone else's workspace")
        admin.lastStarted = "Claude Code"
        #expect(Self.row(admin, "dock")?.menu == [.open, .startLast("Claude Code"), .newSession, .copyName, .stop, .delete])
        #expect(Self.row(admin, "quay")?.menu == [.open, .copyName, .stop, .delete])
        var member = Self.listed(Self.ana)
        member.lastStarted = "Claude Code"
        #expect(Self.row(member, "dock")?.menu == [.copyName])
        _ = admin.ask(.stop, "dock", terminals: 0)
        #expect(Self.row(admin, "dock")?.menu == [.copyName], "a row under way offers a new session")
        var broken = WorkspacesModel(person: Self.uday)
        let first = broken.projectsListed(Self.answer(.projects, "projects"))
        broken.listed(.success(WorkspaceList(project: "harbor", workspaces: [WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.error), owner: "uday")])), request: try #require(first), at: Self.at)
        #expect(Self.row(broken, "dock")?.menu == [.copyName, .delete], "a workspace in error offers a new session")
    }

    @Test func aStopOfYourOwnThatEndsNothingIsSentAtOnceAndItsRowWaits() throws {
        var model = Self.listed(Self.uday)
        let asked = model.ask(.stop, "dock", terminals: 0)
        #expect(asked == .send(project: "harbor", name: "dock", action: .stop) && model.asking == nil)
        let busy = try #require(Self.row(model, "dock"))
        #expect(busy.busy == .stop && busy.primary == nil && busy.menu == [.copyName])
        let second = model.ask(.delete(force: false), "dock", terminals: 0)
        #expect(second == nil, "a second action was asked on a row under way")
        let other = model.changed(.delete(force: false), "dock", project: "harbor", Self.stopped)
        #expect(other == nil, "an answer for another action was taken")
        let own = model.changed(.stop, "dock", project: "harbor", Self.stopped)
        #expect(own != nil && Self.row(model, "dock")?.busy == nil)
    }

    @Test(arguments: [
        (Int?.some(1), "Your terminal in it ends. Its files stay, and Start and Open brings it back."),
        (2, "Your 2 terminals in it end. Its files stay, and Start and Open brings it back."),
        (nil, "Any terminal open in it ends. Its files stay, and Start and Open brings it back."),
    ])
    func aStopOfYourOwnThatEndsTerminalsAsks(_ terminals: Int?, _ body: String) throws {
        var model = Self.listed(Self.uday)
        let asked = try Self.asks(&model, .stop, "dock", terminals: terminals)
        #expect(asked.title == "Stop dock?" && asked.body == body && asked.confirm == "Stop" && asked.project == "harbor" && asked.terminals == terminals)
        let sent = try Self.confirms(&model, asked)
        #expect(sent.action == .stop && sent.name == "dock" && sent.project == "harbor" && model.asking == nil && Self.row(model, "dock")?.busy == .stop)
    }

    /// Minor 6: a stop whose terminals grew while it was asked is asked again in its new words; an
    /// old question is never asked again.
    @Test func aStopAsksAgainWhenItsTerminalsGrew() throws {
        var model = Self.listed(Self.uday)
        let asked = try Self.asks(&model, .stop, "dock", terminals: 1)
        let grew = model.askAgain(asked, terminals: 2)
        let again = try #require(grew)
        #expect(again.body.hasPrefix("Your 2 terminals in it end.") && model.asking == again)
        let stale = model.askAgain(asked, terminals: 3)
        let old = model.confirm(asked)
        #expect(stale == nil && old == nil && model.asking == again, "the old question was asked or confirmed again")
    }

    /// M1: an admin's Stop on another person's workspace always asks, saying whose terminals end:
    /// the runner tells the admin only of their own, so a count of none says nothing of ana's.
    @Test func aStopOfSomeoneElsesAlwaysAsks() throws {
        var model = Self.listed(Self.uday)
        let asked = try Self.asks(&model, .stop, "quay", terminals: 0)
        #expect(asked.body == "Any terminal ana has open in it ends. Its files stay, and Start and Open brings it back." && asked.terminals == nil)
    }

    /// Minor 1: Delete says what this delete does: it never ends a terminal or loses work unasked,
    /// as the server refuses it first; so it promises no ending.
    @Test func aDeleteAsksWhatItDoesNotWhatItWouldEnd() throws {
        var model = Self.listed(Self.uday)
        let asked = try Self.asks(&model, .delete(force: true), "dock", terminals: 3)
        #expect(asked.action == .delete(force: false), "a person's Delete asked a forced delete")
        #expect(asked.title == "Delete dock?" && asked.confirm == "Delete Workspace")
        #expect(asked.body == "Its files on the runner go with it, and commits you pushed are kept by the repo host. If anything runs in it, or it holds work not pushed, Sail asks again before deleting it.")
        model.cancel(asked)
        #expect(model.asking == nil && Self.row(model, "dock")?.busy == nil)
    }

    /// B1, the first interleaving: pier's delete is refused for its work while dock's Delete is
    /// asked; confirming dock deletes dock, plainly, and pier's Delete Anyway is asked after, in the
    /// server's sentence.
    @Test func aRefusalWhileAnotherQuestionIsOpenWaitsItsTurn() throws {
        var model = Self.listed(Self.uday)
        let pier = try Self.asks(&model, .delete(force: false), "pier", terminals: nil)
        _ = try Self.confirms(&model, pier)
        let dock = try Self.asks(&model, .delete(force: false), "dock", terminals: nil)
        _ = model.changed(.delete(force: false), "pier", project: "harbor", try Self.holdsWork("pier"))
        #expect(model.asking == dock && model.questionsWaiting == 1, "pier's refusal replaced the question shown")
        let sent = try Self.confirms(&model, dock)
        #expect(sent.name == "dock" && sent.action == .delete(force: false), "confirming 'Delete dock?' sent \(sent)")
        let anyway = try #require(model.asking, "pier's Delete Anyway was not asked after dock's question")
        #expect(anyway.name == "pier" && anyway.action == .delete(force: true) && anyway.title == "Delete pier anyway?" && anyway.body.contains("holds work"))
    }

    /// B1, the second: a second choice asks nothing while the first is open, and confirming the
    /// first sends only it.
    @Test func aSecondChoiceAsksNothingWhileAQuestionIsOpen() throws {
        var model = Self.listed(Self.uday)
        let dock = try Self.asks(&model, .delete(force: false), "dock", terminals: nil)
        let second = model.ask(.stop, "pier", terminals: 2)
        #expect(second == nil && model.asking == dock, "a second choice replaced the question shown")
        let sent = try Self.confirms(&model, dock)
        #expect(sent.name == "dock" && sent.action == .delete(force: false))
    }

    /// B1, the third: the chosen project changes under the sheet; confirming acts on the question's
    /// own project, not on a workspace of the same name in the one now shown; and a question no
    /// longer open confirms nothing.
    @Test func aQuestionActsOnItsOwnProjectAndOnlyWhileOpen() throws {
        var model = Self.two()
        let dock = try Self.asks(&model, .delete(force: false), "dock", terminals: nil)
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [WorkspaceRow(name: "dock", instance: "demo-dock", state: .known(.running), owner: "uday")])), request: demo, at: Self.at)
        let sent = try Self.confirms(&model, dock)
        #expect(sent.project == "harbor" && sent.name == "dock", "the delete went to \(sent.project)")
        let twice = model.confirm(dock)
        #expect(twice == nil, "a question confirmed twice")
        let back = model.choose("harbor")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: back, at: Self.at)
        let pier = try Self.asks(&model, .delete(force: false), "pier", terminals: nil)
        let stale = model.confirm(dock)
        #expect(stale == nil && model.asking == pier, "an old question confirmed sent the one open now")
    }

    /// M3: Delete Anyway is asked only for a plain delete refused with the "holds work" code: never
    /// for another refusal, never after a forced delete; each of those is said on the page.
    @Test func deleteAnywayOnlyForAPlainDeleteThatHoldsWork() throws {
        var forbidden = Self.listed(Self.uday)
        let plain = try Self.asks(&forbidden, .delete(force: false), "dock", terminals: nil)
        _ = try Self.confirms(&forbidden, plain)
        let notFound = Refusal(code: .known(.notFound), message: "Workspace dock of harbor was not found.")
        _ = forbidden.changed(.delete(force: false), "dock", project: "harbor", .failure(.refused(notFound)))
        #expect(forbidden.asking == nil && Self.page(forbidden)?.banner?.title == "Workspace dock of harbor was not found.", "another refusal offered Delete Anyway")
        var again = Self.listed(Self.uday)
        let first = try Self.asks(&again, .delete(force: false), "dock", terminals: nil)
        _ = try Self.confirms(&again, first)
        _ = again.changed(.delete(force: false), "dock", project: "harbor", try Self.holdsWork("dock"))
        let anyway = try #require(again.asking)
        _ = try Self.confirms(&again, anyway)
        _ = again.changed(.delete(force: true), "dock", project: "harbor", try Self.holdsWork("dock"))
        #expect(again.asking == nil && Self.page(again)?.banner?.title.contains("holds work") == true, "a forced delete refused asked Delete Anyway again")
    }

    /// M2 and minors 3 and 4: a refusal in a project the person left is said when they come back,
    /// through the listing that answers their return, and goes with the listing after; every answer
    /// in the project shown lists it again.
    @Test func aRefusalIsSaidWhereItHappenedUntilListedAgain() throws {
        var model = Self.two()
        let pier = try Self.asks(&model, .delete(force: false), "pier", terminals: nil)
        _ = try Self.confirms(&model, pier)
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: demo, at: Self.at)
        let elsewhere = model.changed(.delete(force: false), "pier", project: "harbor", try Self.holdsWork("pier"))
        #expect(elsewhere.flatMap(model.project(of:)) == "harbor" && Self.page(model)?.banner == nil && model.asking == nil, "harbor's refusal was said or asked over demo")
        let back = model.choose("harbor")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: back, at: Self.at)
        #expect(Self.page(model)?.banner?.title.contains("holds work") == true, "coming back, harbor said nothing of pier's refusal")
        let later = model.listAgain()
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: later, at: Self.at)
        #expect(Self.page(model)?.banner == nil, "the refusal stayed past the next listing")
        let dock = try Self.asks(&model, .stop, "dock", terminals: nil)
        _ = try Self.confirms(&model, dock)
        let refused = model.changed(.stop, "dock", project: "harbor", .failure(.unreachable("lost")))
        #expect(refused != nil, "a refused stop did not list again")
    }

    static func harbor(_ rows: [(String, WorkspaceState, String)]) -> Result<WorkspaceList, SailError> {
        .success(WorkspaceList(project: "harbor", workspaces: rows.map { WorkspaceRow(name: $0.0, instance: "harbor-\($0.0)", state: .known($0.1), owner: $0.2) }))
    }

    static func deleted(_ name: String) -> Result<WorkspaceChanged, SailError> {
        .success(WorkspaceChanged(project: "harbor", workspace: name, instance: "harbor-\(name)", change: .known(.deleted), timings: nil))
    }

    /// pier's Delete Anyway waiting behind dock's open question.
    static func pierWaitsBehindDock(_ model: inout WorkspacesModel) throws -> WorkspacesModel.Question {
        let pier = try asks(&model, .delete(force: false), "pier", terminals: nil)
        _ = try confirms(&model, pier)
        let dock = try asks(&model, .delete(force: false), "dock", terminals: nil)
        _ = model.changed(.delete(force: false), "pier", project: "harbor", try holdsWork("pier"))
        #expect(model.asking == dock && model.questionsWaiting == 1)
        return dock
    }

    /// R2-2: cancelling the open question asks the one waiting.
    @Test func cancellingAsksTheQuestionWaiting() throws {
        var model = Self.listed(Self.uday)
        let dock = try Self.pierWaitsBehindDock(&model)
        model.cancel(dock)
        #expect(model.asking?.title == "Delete pier anyway?" && model.questionsWaiting == 0, "cancelling dock did not ask pier's Delete Anyway")
    }

    /// R2-2: a question waiting for harbor is never asked over demo, and is asked when harbor is
    /// chosen again.
    @Test func aWaitingQuestionIsAskedOnlyInItsOwnProject() throws {
        var model = Self.two()
        let dock = try Self.pierWaitsBehindDock(&model)
        _ = model.choose("demo")
        model.cancel(dock)
        #expect(model.asking == nil && model.questionsWaiting == 1, "harbor's pier was asked over demo")
        _ = model.choose("harbor")
        #expect(model.asking?.title == "Delete pier anyway?" && model.asking?.project == "harbor", "choosing harbor did not ask pier's Delete Anyway")
    }

    /// R2-2: a new action clears the banner an old one left.
    @Test func aNewActionClearsAnOldRefusal() throws {
        var model = Self.listed(Self.uday)
        _ = model.ask(.stop, "dock", terminals: 0)
        _ = model.changed(.stop, "dock", project: "harbor", .failure(.unreachable("lost")))
        #expect(Self.page(model)?.banner != nil)
        _ = model.ask(.stop, "dock", terminals: 0)
        #expect(Self.page(model)?.banner == nil, "the old refusal stayed over a new stop")
    }

    /// A stop's or a delete's answer sets its row at once, stopped or gone, and a list asked before
    /// that answer, still in flight, cannot bring "running" back.
    @Test func anAnswerSetsItsRowUntilAListAskedAfterIt() throws {
        var model = Self.listed(Self.uday)
        let inFlight = model.listAgain()
        _ = model.ask(.stop, "dock", terminals: 0)
        let after = model.changed(.stop, "dock", project: "harbor", Self.stopped)
        #expect(Self.row(model, "dock")?.state == .known(.stopped) && Self.row(model, "dock")?.busy == nil, "dock reads \(String(describing: Self.row(model, "dock")?.state)) after its stop")
        model.listed(Self.answer(.workspaces(project: "harbor"), "workspaces-three"), request: inFlight, at: Self.at)
        #expect(Self.row(model, "dock")?.state == .known(.stopped), "a list asked before the stop brought running back")
        model.listed(Self.harbor([("dock", .stopped, "uday"), ("pier", .stopped, "uday")]), request: try #require(after), at: Self.at)
        #expect(Self.row(model, "dock")?.state == .known(.stopped))
        let delete = try Self.asks(&model, .delete(force: false), "pier", terminals: nil)
        _ = try Self.confirms(&model, delete)
        _ = model.changed(.delete(force: false), "pier", project: "harbor", Self.deleted("pier"))
        #expect(Self.row(model, "pier") == nil, "pier stayed listed after it was deleted")
    }

    /// R2-3: a choice not taken because a question is open is said, not dropped silently.
    @Test func aChoiceNotTakenWhileAQuestionIsOpenIsSaid() throws {
        var model = Self.listed(Self.uday)
        _ = try Self.asks(&model, .delete(force: false), "dock", terminals: nil)
        let second = model.ask(.stop, "pier", terminals: 2)
        #expect(second == nil && Self.page(model)?.banner?.title == "pier is not asked about yet: answer the question open, then choose Stop again.", "\(String(describing: Self.page(model)?.banner))")
    }

    /// R2-4: a stop whose terminals grew is asked again in its own project's words, whichever is shown.
    @Test func aStopIsAskedAgainInItsOwnProject() throws {
        var model = Self.two()
        let asked = try Self.asks(&model, .stop, "dock", terminals: 1)
        _ = model.choose("demo")
        let grew = model.askAgain(asked, terminals: 2)
        let again = try #require(grew, "the stop was not asked again once demo was shown")
        #expect(again.project == "harbor" && again.body.hasPrefix("Your 2 terminals in it end."))
    }

    /// R2-5: a question about a workspace no longer listed is not asked: waiting, it is dropped;
    /// open, it is withdrawn and said.
    @Test func aQuestionAboutAWorkspaceGoneIsNotAsked() throws {
        var model = Self.listed(Self.uday)
        let dock = try Self.pierWaitsBehindDock(&model)
        let again = model.listAgain()
        model.listed(Self.harbor([("dock", .running, "uday")]), request: again, at: Self.at)
        #expect(model.questionsWaiting == 0, "pier's Delete Anyway still waits, pier gone")
        #expect(model.asking == dock)
        let later = model.listAgain()
        model.listed(Self.harbor([]), request: later, at: Self.at)
        #expect(model.asking == nil && Self.page(model)?.banner?.title == "dock is no longer in harbor, so Sail stopped asking about it.", "\(String(describing: Self.page(model)?.banner))")
    }

    /// Minor 2: a server that could not look says so in Sail's words; its own text goes to the log.
    @Test func couldNotTellIsSaidInSailsWords() throws {
        var model = Self.listed(Self.uday)
        let asked = try Self.asks(&model, .delete(force: false), "dock", terminals: nil)
        _ = try Self.confirms(&model, asked)
        let raw = "Whether workspace dock of harbor holds work cannot be told: java.io.IOException: git exited 128"
        _ = model.changed(.delete(force: false), "dock", project: "harbor", .failure(.refused(Refusal(code: .known(.workspaceHoldsWork), message: raw))))
        let anyway = try #require(model.asking)
        #expect(anyway.body == "Sail could not tell whether dock holds work, so it does not delete it unasked." && anyway.detail == raw)
    }
}
