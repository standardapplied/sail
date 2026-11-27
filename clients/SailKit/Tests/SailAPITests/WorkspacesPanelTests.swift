import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The left panel's tree: an open project lists the person's own workspaces from the one listing its
/// page shows, other people's counted in one row; which projects are open is the person's
/// arrangement, asked again on a refresh; a listing that fails says so under its own project.
@Suite struct WorkspacesPanelTests {
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

    static let keeperShown = WorkspacesModel.Workspace(name: "keeper", state: .known(.stopped), busy: nil, primary: .startAndOpen, newSession: true)
}
