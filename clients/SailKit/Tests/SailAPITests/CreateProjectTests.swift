import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// A person creating a project (`first-impressions-mockup.html`, section 6): who is offered Create
/// Project, the sheet's presets and paths, the one definition Create sends, the server's refusal
/// under the field it names, and the project chosen once it is listed.
@Suite(.timeLimit(.minutes(1))) struct CreateProjectTests {
    static let harnesses = [NewProject.Agent(name: "claude-code", display: "Claude Code"), NewProject.Agent(name: "codex", display: "Codex CLI")]

    static func person(_ role: Role, _ capabilities: [Capability]) throws -> WorkspacesModel.Person {
        let whoami = Whoami(fde: "mara", name: "mara", role: .known(role), capabilities: capabilities.map { .known($0) })
        return WorkspacesModel.Person(SignedIn(controlPlane: try ControlPlane(address: "https://sail.example.com"), fde: "mara", until: .distantFuture, whoami: .success(whoami)))
    }

    static func none(_ person: WorkspacesModel.Person) -> WorkspacesModel {
        var model = WorkspacesModel(person: person)
        model.projectsListed(WorkspacesModelTests.answer(.projects, "projects-none"))
        return model
    }

    /// No project yet: the panel says so under the control plane's head, counting none, and the main
    /// area says what a project is, offers Create Project to those whose `whoami` names `write`, and
    /// names the host and who may; others are told to ask an admin, with no action.
    @Test func whoIsOfferedCreateProject() throws {
        for (role, capabilities, line, action) in [
            (Role.admin, [Capability.read, .write, .admin], "sail.example.com · you can create projects as an admin", WorkspacesModel.EmptyAction.createProject),
            (.member, [.read, .write], "sail.example.com · you can create projects as a member", .createProject),
            (.viewer, [.read], "sail.example.com · Ask an admin to create one.", nil),
        ] as [(Role, [Capability], String, WorkspacesModel.EmptyAction?)] {
            let model = Self.none(try Self.person(role, capabilities))
            guard case let .shown(panel, .noProjects(empty)) = model.screen else { Issue.record("no empty state: \(model.screen)"); return }
            #expect(panel.listed && panel.projects.isEmpty && panel.emptyLine == "No projects yet." && !panel.creates)
            #expect(empty.title == "Create your first project")
            #expect(empty.body == "A project holds the repositories, agents and size that every workspace starts from. Create one, and Sail builds its template on the runner.")
            #expect(empty.line == line && empty.action == action, "\(role)")
        }
    }

    /// With projects listed, Create Project is offered from the panel by the same rule; with none,
    /// only while This Mac stands where the main area would offer it.
    @Test func thePanelOffersCreateProjectByTheSameRule() throws {
        for (capabilities, offered) in [([Capability.read, .write], true), ([.read], false)] {
            var model = WorkspacesModel(person: try Self.person(.member, capabilities))
            #expect(!model.panel.creates, "offered before any project was listed")
            model.projectsListed(WorkspacesModelTests.answer(.projects, "projects"))
            #expect(model.panel.creates == offered)
            var none = Self.none(try Self.person(.member, capabilities))
            none.chooseThisMac()
            #expect(none.panel.creates == offered, "This Mac shown with no project: \(none.panel.creates)")
        }
    }

    /// The sizes are the app's words for `resources`, Medium chosen at first; SIZE's help names the
    /// choice; Custom starts at Medium's and sends what is typed, its digits alone.
    @Test func theSizePresets() {
        var sheet = NewProject(agents: Self.harnesses)
        #expect(sheet.size == .medium && sheet.sizeHelp == "4 cores · 8 GB memory · 50 GB disk for each workspace.")
        let sent = NewProject.Size.allCases.map { size -> ProjectDefinition.Resources in
            sheet.choose(size)
            return sheet.definition.resources
        }
        #expect(sent == [
            ProjectDefinition.Resources(cpu: 2, memory: "4GB", disk: "30GB"), ProjectDefinition.Resources(cpu: 4, memory: "8GB", disk: "50GB"),
            ProjectDefinition.Resources(cpu: 8, memory: "16GB", disk: "100GB"), ProjectDefinition.Resources(cpu: 4, memory: "8GB", disk: "50GB"),
        ])
        #expect(sheet.typeCustom(.cores, "1 core") == "1")
        sheet.typeCustom(.memory, "12")
        sheet.typeCustom(.disk, "")
        #expect(sheet.definition.resources == ProjectDefinition.Resources(cpu: 1, memory: "12GB", disk: "0GB"))
        #expect(sheet.sizeHelp == "1 core · 12 GB memory · 0 GB disk for each workspace.")
        sheet.choose(.small)
        #expect(sheet.sizeHelp == "2 cores · 4 GB memory · 30 GB disk for each workspace.")
    }

    /// A repository's path fills from its URL's last part, less `.git`, until the person edits it;
    /// then it stays as they typed it.
    @Test func thePathFollowsTheURLUntilEdited() {
        var sheet = NewProject(agents: Self.harnesses)
        sheet.typeURL("github.com/acme/harbor-api", row: 0)
        #expect(sheet.repos[0].path == "harbor-api")
        sheet.typeURL("https://github.com/acme/harbor-web.git", row: 0)
        #expect(sheet.repos[0].path == "harbor-web")
        sheet.typePath("web", row: 0)
        sheet.typeURL("github.com/acme/harbor-api", row: 0)
        #expect(sheet.repos[0].path == "web")
        sheet.addRepo()
        sheet.typeURL("github.com/acme/docs/", row: 1)
        #expect(sheet.repos.map(\.path) == ["web", "docs"])
        sheet.removeRepo(0)
        #expect(sheet.repos.map(\.url) == ["github.com/acme/docs/"])
    }

    /// Create sends one definition, whole, as the server's golden create took it: the name, each
    /// repository given a URL (a bare host and path over https), the agents checked, the size; a
    /// second press while it is under way sends nothing.
    @Test func createSendsTheWholeDefinitionOnce() throws {
        var sheet = NewProject(agents: Self.harnesses + [NewProject.Agent(name: "pi", display: "Pi")])
        #expect(!sheet.creates && sheet.send() == nil, "Create offered with no name")
        sheet.named("marina")
        sheet.typeURL("https://github.com/harbor/app.git", row: 0)
        sheet.addRepo()
        sheet.typeURL("github.com/harbor/docs.git", row: 1)
        sheet.addRepo()
        sheet.check("pi", false)
        let sent = sheet.send()
        #expect(sent == marina)
        let golden = try #require(try Exchange("project-created").requestBody)
        #expect(try json(SailJSON.encode(sent)) == json(golden))
        #expect(sheet.send() == nil && !sheet.creates)
    }

    /// A taken name: the server's sentence under NAME with its remedy in words, nothing over the
    /// foot, and what was typed kept; editing the name takes the refusal away, editing anything
    /// else does not.
    @Test func aRefusalNamingAFieldStandsUnderIt() throws {
        var sheet = NewProject(agents: Self.harnesses)
        sheet.named("harbor")
        sheet.typeURL("github.com/acme/harbor-api", row: 0)
        _ = sheet.send()
        sheet.refuse(try Self.taken(), at: "sail.example.com")
        #expect(sheet.nameRefused == NewProject.Refused(said: "A project named harbor already exists.", remedy: "Choose another name, or open harbor."))
        #expect(sheet.refused.isEmpty && !sheet.underWay && sheet.name == "harbor" && sheet.repos[0].url == "github.com/acme/harbor-api" && sheet.creates)
        sheet.choose(.large)
        #expect(sheet.nameRefused != nil)
        sheet.named("harbour")
        #expect(sheet.nameRefused == nil)
    }

    /// A refusal that names no field of the sheet stands over its foot, in the server's words, until
    /// the next edit; one that could not reach the server says so.
    @Test func aRefusalNamingNoFieldStandsOverTheFoot() throws {
        var sheet = NewProject(agents: Self.harnesses)
        sheet.named("harbor")
        _ = sheet.send()
        let unnamed = try Exchange("refusal-project-name-taken").altered(at: ["error", "field_errors"], to: [Any]())
        sheet.refuse(SailJSON.refusal(response(unnamed), route: "POST /v1/projects"), at: "sail.example.com")
        #expect(sheet.refused == ["A project named harbor already exists.", "Choose another name, or open harbor."] && sheet.nameRefused == nil)
        sheet.check("codex", false)
        #expect(sheet.refused.isEmpty)
        _ = sheet.send()
        sheet.refuse(.unreachable("The network connection was lost."), at: "sail.example.com")
        #expect(sheet.refused == ["Sail cannot reach the control plane at sail.example.com."])
    }

    /// The created project is chosen once the projects listed show it, in This Mac's place too, and
    /// a build refused is said over its page in words, never a command.
    @Test func theCreatedProjectIsChosenOnceListed() throws {
        var model = Self.none(try Self.person(.admin, [.read, .write, .admin]))
        model.chooseThisMac()
        model.projectCreated("lighthouse")
        model.buildRefused("lighthouse", try #require(WorkspacesModelTests.answer(.buildTemplate(project: "lighthouse"), "refusal-template-2d-login-not-stored").failure))
        let listed = ProjectList(projects: ["harbor", "lighthouse"].map { ProjectSummary(name: $0, containerStatus: .known(.notCreated), template: .known(.missing)) }, total: 2)
        let request = model.projectsListed(.success(listed))
        #expect(model.chosen == "lighthouse" && !model.thisMacChosen && request != nil)
        model.listed(WorkspacesModelTests.answer(.workspaces(project: "lighthouse"), "workspaces-none"), request: try #require(request), at: WorkspacesModelTests.at)
        let page = try #require(WorkspacesModelTests.page(model))
        #expect(page.banner == WorkspacesModel.Notice(
            tone: .error, title: "github.com asks for credentials to fetch lighthouse's repo lamp, and dana keeps no token for it.", line: "An admin stores it on the runner for now.",
            tryAgain: false
        ))
        model.projectsListed(.success(listed))
        #expect(model.chosen == "lighthouse", "a later list chose again")
    }

    /// The template's build answers with its state and each step by name, in the build's order.
    @Test func aBuildAnswersWithItsStepsByName() throws {
        let joined = try Endpoint.buildTemplate(project: "lighthouse").decode(response(try Exchange("template-2f-joined")))
        #expect(joined.project == "lighthouse" && joined.state == .known(.building) && joined.stepNames.count == 20)
        #expect(joined.stepNames.first == "container_launched" && joined.stepNames.last == "template_finished")
    }

    static func taken() throws -> SailError {
        SailJSON.refusal(response(try Exchange("refusal-project-name-taken")), route: "POST /v1/projects")
    }
}

extension Result {
    var failure: Failure? {
        if case let .failure(error) = self { error } else { nil }
    }
}
