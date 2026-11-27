import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The left panel's tree: an open project lists the person's own workspaces from the one listing its
/// page shows, other people's counted in one row; which projects are open is the person's
/// arrangement, asked again on a refresh; a listing that fails says so under its own project.
@Suite(.timeLimit(.minutes(1))) struct WorkspacesPanelTests {
    static let uday = WorkspacesModelTests.uday
    static let at = WorkspacesModelTests.at
    static let two = ProjectList(projects: ["harbor", "lighthouse"].map { ProjectSummary(name: $0, containerStatus: .known(.running), template: .known(.ready)) }, total: 2)
    static let beacon = WorkspaceRow(name: "beacon", instance: "lighthouse-beacon", state: .known(.running), owner: "uday")
    static let keeper = WorkspaceRow(name: "keeper", instance: "lighthouse-keeper", state: .known(.stopped), owner: "uday")

    static func harbor() -> Result<WorkspaceList, SailError> {
        WorkspacesModelTests.answer(.workspaces(project: "harbor"), "workspaces-three")
    }

    static func lighthouse(_ rows: [WorkspaceRow] = [beacon]) -> Result<WorkspaceList, SailError> {
        .success(WorkspaceList(project: "lighthouse", workspaces: rows))
    }

    /// The model with both projects listed, harbor chosen and listed, and `open` open beside it.
    static func model(open: [String] = []) throws -> WorkspacesModel {
        var model = WorkspacesModel(person: uday, chosen: "harbor", expanded: open)
        let listed = model.projectsListed(.success(two))
        let first = try #require(listed)
        model.listed(harbor(), request: first, at: at)
        return model
    }

    static func project(_ name: String, in model: WorkspacesModel) -> WorkspacesModel.Project? {
        model.panel.projects.first { $0.name == name }
    }

    /// 1, 2: an open project holds the person's own workspaces in their page's order, each with its
    /// page row's state and what its row offers, and one count of other people's; a shut one shows
    /// nothing under it.
    @Test func anOpenProjectListsTheirOwnWorkspacesAndCountsOtherPeoples() throws {
        let model = try Self.model()
        let harbor = try #require(Self.project("harbor", in: model)), lighthouse = try #require(Self.project("lighthouse", in: model))
        #expect(harbor.expanded && !lighthouse.expanded && lighthouse.holds == nil)
        let holds = try #require(harbor.holds)
        #expect(holds.yours.map(\.name) == ["dock", "pier"] && holds.yours.map(\.state) == [.known(.running), .known(.stopped)])
        #expect(holds.yours.map(\.primary) == [.open, .startAndOpen] && holds.yours.allSatisfy(\.newSession))
        #expect(holds.others == 1 && holds.othersSaid == "Other people's · 1" && holds.failed == nil)
        let page = try #require(WorkspacesModelTests.page(model))
        let lines = try #require(WorkspacesModelTests.table(model)).yours
        #expect(page.name == "harbor" && lines.map(\.name) == holds.yours.map(\.name) && lines.map(\.state) == holds.yours.map(\.state), "the panel and the page disagree")
    }

    /// 1: a row's + is offered exactly where its page's row offers New Session: on the person's own
    /// running or stopped workspace (a start there starts it, as Start and Open does), never while a
    /// stop or delete is under way on it.
    @Test func thePlusIsOfferedWhereThePageOffersNewSession() throws {
        var model = try Self.model()
        #expect(model.ask(.stop, "dock", terminals: 0) != nil)
        let yours = try #require(Self.project("harbor", in: model)?.holds?.yours), lines = try #require(WorkspacesModelTests.table(model)).yours
        #expect(yours.map(\.newSession) == lines.map { $0.menu.contains(.newSession) } && yours.map(\.newSession) == [false, true], "the panel's + and the page's New Session disagree")
    }

    /// 3: the person's own workspace chosen is the panel's row chosen; someone else's, or This Mac,
    /// leaves the project's row chosen.
    @Test func theirOwnWorkspaceChosenIsThePanelsRowChosen() throws {
        var model = try Self.model()
        #expect(model.panel.chosenRow == nil)
        model.chooseRow("dock")
        #expect(model.panel.chosen == "harbor" && model.panel.chosenRow == "dock")
        model.chooseRow("quay")
        #expect(model.panel.chosenRow == nil, "ana's quay is not in the panel")
        model.chooseRow("pier")
        model.chooseThisMac()
        #expect(model.panel.chosen == WorkspacesModel.thisMac && model.panel.chosenRow == nil)
    }

    /// 5: opening a project asks its workspaces once; the answer is the panel's and, chosen, its
    /// page's at once, before its own answer; an answer overtaken for its project is dropped, while
    /// another project's request overtakes none.
    @Test func oneListingServesThePanelAndThePage() throws {
        var model = try Self.model()
        let expanded = model.expand("lighthouse")
        let opened = try #require(expanded)
        #expect(model.project(of: opened) == "lighthouse")
        #expect(model.expand("lighthouse") == nil, "asked again while its answer is on the way")
        let harborAgain = model.listAgain()
        model.listed(Self.lighthouse(), request: opened, at: Self.at)
        #expect(Self.project("lighthouse", in: model)?.holds?.yours.map(\.name) == ["beacon"], "harbor's later request overtook lighthouse's answer")
        #expect(WorkspacesModelTests.page(model)?.name == "harbor" && WorkspacesModelTests.table(model)?.yours.map(\.name) == ["dock", "pier"])
        model.listed(Self.harbor(), request: harborAgain, at: Self.at)
        model.collapse("lighthouse")
        #expect(model.expand("lighthouse") == nil, "listed once, opened again, asked again")
        let chose = model.choose("lighthouse")
        #expect(model.project(of: chose) == "lighthouse" && WorkspacesModelTests.table(model)?.yours.map(\.name) == ["beacon"], "the page waited for an answer the panel had")
        let newer = model.listAgain()
        model.listed(Self.lighthouse([Self.beacon, Self.keeper]), request: chose, at: Self.at)
        #expect(model.project(of: chose) == nil && WorkspacesModelTests.table(model)?.yours.map(\.name) == ["beacon"], "an overtaken answer was taken")
        model.listed(Self.lighthouse([Self.beacon, Self.keeper]), request: newer, at: Self.at)
        #expect(Self.project("lighthouse", in: model)?.holds?.yours.map(\.name) == ["beacon", "keeper"] && WorkspacesModelTests.table(model)?.yours.map(\.name) == ["beacon", "keeper"])
    }

    /// 5: a refresh asks each open project's workspaces again, the chosen one's by the projects'
    /// answer; a shut project is asked nothing.
    @Test func aRefreshAsksEachOpenProjectAgain() throws {
        var model = try Self.model(open: ["lighthouse"])
        let asked = model.askExpanded()
        #expect(asked.compactMap(model.project(of:)) == ["lighthouse"], "the open lighthouse was not asked, or harbor twice")
        model.askingProjects()
        let listed = model.projectsListed(.success(Self.two))
        let chosen = try #require(listed)
        #expect(model.project(of: chosen) == "harbor")
        model.collapse("lighthouse")
        #expect(model.askExpanded().isEmpty, "a shut project was asked")
        model.collapse("harbor")
        #expect(model.listAgain() > chosen && model.askExpanded().isEmpty, "the chosen project, shut, is still the page's")
    }

    /// 4: the projects open are the person's arrangement, kept by the app; the project chosen last
    /// opens too at launch.
    @Test func whichProjectsAreOpenIsRemembered() throws {
        var model = WorkspacesModel(person: Self.uday, chosen: "harbor", expanded: ["lighthouse"])
        #expect(model.expanded == ["harbor", "lighthouse"])
        model.collapse("harbor")
        #expect(model.expanded == ["lighthouse"])
        _ = model.expand("harbor")
        #expect(model.expanded == ["harbor", "lighthouse"])
        #expect(WorkspacesModel(person: Self.uday, chosen: WorkspacesModel.thisMac, expanded: ["lighthouse"]).expanded == ["lighthouse"])
        #expect(WorkspacesModel(person: Self.uday).expanded.isEmpty)
    }

    /// 5: a listing that fails says so under its project, keeping what it listed before and every
    /// other project open as it was; opening it again asks again.
    @Test func aFailedListingIsSaidUnderItsProjectAlone() throws {
        var model = try Self.model(open: ["lighthouse"])
        let asked = model.askExpanded()
        let first = try #require(asked.first)
        model.listed(Self.lighthouse(), request: first, at: Self.at)
        let refreshed = model.askExpanded()
        let again = try #require(refreshed.first)
        model.listed(.failure(.unexpected(route: "GET /v1/projects/lighthouse/workspaces", status: 502)), request: again, at: Self.at)
        let lighthouse = try #require(Self.project("lighthouse", in: model)?.holds)
        #expect(lighthouse.failed == "Couldn’t list its workspaces" && lighthouse.yours.map(\.name) == ["beacon"])
        let harbor = try #require(Self.project("harbor", in: model))
        #expect(harbor.expanded && harbor.holds?.failed == nil && harbor.holds?.yours.map(\.name) == ["dock", "pier"] && !model.panel.stale)
        #expect(WorkspacesModelTests.page(model)?.banner == nil, "lighthouse's failure is said over harbor's page")
        model.collapse("lighthouse")
        let reopened = model.expand("lighthouse")
        let retried = try #require(reopened)
        model.listed(Self.lighthouse([Self.keeper]), request: retried, at: Self.at)
        #expect(Self.project("lighthouse", in: model)?.holds == WorkspacesModel.Nest(yours: [Self.keeperShown], others: 0, failed: nil))
        var never = try Self.model()
        let opened = never.expand("lighthouse")
        let none = try #require(opened)
        never.listed(.failure(.unreachable("lost")), request: none, at: Self.at)
        #expect(Self.project("lighthouse", in: never)?.holds == WorkspacesModel.Nest(yours: [], others: 0, failed: "Couldn’t list its workspaces"))
    }

    static func dock(_ model: WorkspacesModel) -> WorkspacesModel.Workspace? {
        project("harbor", in: model)?.holds?.yours.first { $0.name == "dock" }
    }

    /// The Ruling: a stop answered after the person left its project settles its row under that
    /// project at once and asks the project again, so a listing already on its way cannot bring the
    /// row back running; its refusal still waits for the person's return.
    @Test func aStopAnsweredAfterThePersonLeftSettlesItsRowAndAsksAgain() throws {
        var model = try Self.model(open: ["lighthouse"])
        #expect(model.ask(.stop, "dock", terminals: 0) != nil)
        #expect(Self.dock(model)?.busy == .stop)
        _ = model.choose("lighthouse")
        let refreshed = model.askExpanded()
        let older = try #require(refreshed.first)
        #expect(model.project(of: older) == "harbor")
        model.collapse("harbor")
        let request = model.changed(.stop, "dock", project: "harbor", WorkspacesModelTests.stopped)
        #expect(request.flatMap(model.project(of:)) == "harbor" && model.project(of: older) == nil, "harbor is not asked again")
        model.listed(Self.harbor(), request: older, at: Self.at)
        #expect(model.expand("harbor") == nil)
        #expect(Self.dock(model)?.state == .known(.stopped) && Self.dock(model)?.busy == nil, "harbor's row says \(String(describing: Self.dock(model))) after its stop answered")
    }

    /// The Ruling: a delete answered after the person left takes its row out of the panel at once.
    @Test func aDeleteAnsweredAfterThePersonLeftTakesItsRowOut() throws {
        var model = try Self.model(open: ["lighthouse"])
        let question = try WorkspacesModelTests.asks(&model, .delete(force: false), "dock", terminals: nil)
        _ = try WorkspacesModelTests.confirms(&model, question)
        _ = model.choose("lighthouse")
        let request = model.changed(.delete(force: false), "dock", project: "harbor", WorkspacesModelTests.deleted("dock"))
        #expect(request.flatMap(model.project(of:)) == "harbor")
        #expect(Self.dock(model) == nil, "deleted dock still under harbor in the panel")
    }

    /// The Ruling: a create answered after the person left asks its project again, its new row
    /// under it in the panel once listed, and chosen when the person comes back.
    @Test func aCreateAnsweredAfterThePersonLeftListsItsProjectAgain() throws {
        var model = try Self.model(open: ["lighthouse"])
        WorkspacesModelTests.send("new", &model)
        _ = model.choose("lighthouse")
        let made = model.created(WorkspacesModelTests.answer(try Endpoint.createWorkspace("new", project: "harbor"), "workspace-create"), project: "harbor")
        let request = try #require(made, "harbor is not asked again")
        #expect(model.project(of: request) == "harbor")
        model.listed(WorkspacesModelTests.answer(.workspaces(project: "harbor"), "workspaces-made"), request: request, at: Self.at)
        #expect(Self.project("harbor", in: model)?.holds?.yours.contains { $0.name == "new" } == true, "the created row is not under harbor")
        let back = model.choose("harbor")
        model.listed(WorkspacesModelTests.answer(.workspaces(project: "harbor"), "workspaces-made"), request: back, at: Self.at)
        #expect(model.panel.chosenRow == "new")
    }

    /// The Ruling: a refusal in a project left survives the relist its answer asked, landing while
    /// the person is elsewhere, and is said when they come back.
    @Test func aRelistWhileAwayKeepsTheRefusalForTheReturn() throws {
        var model = WorkspacesModelTests.two()
        let pier = try WorkspacesModelTests.asks(&model, .delete(force: false), "pier", terminals: nil)
        _ = try WorkspacesModelTests.confirms(&model, pier)
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: demo, at: Self.at)
        let answered = model.changed(.delete(force: false), "pier", project: "harbor", try WorkspacesModelTests.holdsWork("pier"))
        let elsewhere = try #require(answered)
        model.listed(Self.harbor(), request: elsewhere, at: Self.at)
        let back = model.choose("harbor")
        model.listed(Self.harbor(), request: back, at: Self.at)
        #expect(WorkspacesModelTests.page(model)?.banner?.title.contains("holds work") == true, "harbor's relist while away took its refusal before the return")
    }

    /// The Ruling: a create answered in a project left, listed while the person is elsewhere, chooses
    /// nothing in the project shown, though it holds a row of that name.
    @Test func aCreateListedWhileAwayChoosesNothingInTheProjectShown() throws {
        var model = WorkspacesModelTests.two()
        let demo = model.choose("demo")
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [])), request: demo, at: Self.at)
        WorkspacesModelTests.send("pier", &model)
        let harbor = model.choose("harbor")
        model.listed(Self.harbor(), request: harbor, at: Self.at)
        let timings = WorkspaceTimings(cloneMs: 0, startMs: 0, readyMs: 0, totalMs: 0, readySteps: [:])
        let made = WorkspaceCreated(project: "demo", workspace: "pier", instance: "demo-pier", owner: "uday", template: "v1", stale: false, timings: timings)
        let createdAgain = model.created(.success(made), project: "demo")
        let demoAgain = try #require(createdAgain)
        model.listed(.success(WorkspaceList(project: "demo", workspaces: [WorkspaceRow(name: "pier", instance: "demo-pier", state: .known(.running), owner: "uday")])), request: demoAgain, at: Self.at)
        #expect(WorkspacesModelTests.table(model)?.yours.first(where: \.chosen) == nil && model.panel.chosenRow == nil, "demo's created pier was chosen in harbor")
    }

    /// 5: a refresh whose projects could not be listed asks no open project, which would say the one
    /// failure again under each; the next that lists them does.
    @Test func aFailedProjectsRefreshAsksNoOpenProject() throws {
        var model = try Self.model(open: ["lighthouse"])
        let failing = model.askingProjects()
        model.projectsListed(.failure(.unreachable("lost")), request: failing)
        #expect(model.askExpanded().isEmpty, "an open project was asked while the projects could not be listed")
        let listing = model.askingProjects()
        model.projectsListed(.success(Self.two), request: listing)
        #expect(model.askExpanded().compactMap(model.project(of:)) == ["lighthouse"])
    }

    /// The Ruling: a shut project's rows are not the panel's, so an answer for it changes nothing
    /// the panel shows.
    @Test func aShutProjectsAnswerChangesNothingThePanelShows() throws {
        var model = try Self.model()
        let before = model.panel
        let expanded = model.expand("lighthouse")
        let opened = try #require(expanded)
        model.collapse("lighthouse")
        model.listed(Self.lighthouse(), request: opened, at: Self.at)
        #expect(model.panel == before, "a shut project's answer changed the panel")
        #expect(model.expand("lighthouse") == nil && Self.project("lighthouse", in: model)?.holds?.yours.map(\.name) == ["beacon"])
    }

    /// n1: choosing a project whose listing failed keeps saying so until an answer comes.
    @Test func choosingAProjectKeepsItsFailureLineUntilAnAnswer() throws {
        var model = try Self.model(open: ["lighthouse"])
        let refreshed = model.askExpanded()
        let asked = try #require(refreshed.first)
        model.listed(.failure(.unreachable("lost")), request: asked, at: Self.at)
        let chose = model.choose("lighthouse")
        #expect(Self.project("lighthouse", in: model)?.holds?.failed == WorkspacesModel.unlisted, "the failure line went on choose, before any answer")
        model.listed(Self.lighthouse(), request: chose, at: Self.at)
        #expect(Self.project("lighthouse", in: model)?.holds?.failed == nil)
    }

    /// 4: a project no longer listed is no longer open, so the arrangement kept holds only projects
    /// that are.
    @Test func aProjectNoLongerListedIsNoLongerOpen() throws {
        var model = WorkspacesModel(person: Self.uday, chosen: "harbor", expanded: ["gone", "lighthouse"])
        model.projectsListed(.success(Self.two))
        #expect(model.expanded == ["harbor", "lighthouse"])
    }

    /// 4: the listing that chooses a project just created prunes the arrangement too.
    @Test func theListingThatChoosesACreatedProjectPrunesTheOpenOnes() throws {
        var model = WorkspacesModel(person: Self.uday, chosen: "harbor", expanded: ["gone"])
        model.projectCreated("lighthouse")
        let request = model.projectsListed(.success(Self.two))
        #expect(request.flatMap(model.project(of:)) == "lighthouse" && model.expanded == ["harbor"], "open: \(model.expanded)")
    }

    static let keeperShown = WorkspacesModel.Workspace(name: "keeper", state: .known(.stopped), busy: nil, primary: .startAndOpen, newSession: true)
}
