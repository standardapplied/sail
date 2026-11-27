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

    /// No project yet, to those whose `whoami` names `write` and `build` (who may also build its
    /// template): the panel says there is none under the control plane's head, counting none, and the
    /// main area says what a project is, offers Create Project and names the host and their role. Everyone else,
    /// whatever their role is called or while it is not confirmed, lists only what is granted to
    /// them, so both say none is open to them yet, with Check Again.
    @Test func whoIsOfferedCreateProject() throws {
        for (role, capabilities, creates) in [
            (Role.admin, [Capability.read, .write, .admin, .build], true), (.member, [.read, .write, .build], true),
            (.admin, [.read, .write, .admin], false), (.member, [.read, .write], false), (.viewer, [.read], false),
        ] as [(Role, [Capability], Bool)] {
            let model = Self.none(try Self.person(role, capabilities))
            guard case let .shown(panel, .noProjects(empty)) = model.screen else { Issue.record("no empty state: \(model.screen)"); return }
            #expect(panel.listed && panel.projects.isEmpty && !panel.creates)
            #expect(panel.emptyLine == (creates ? "No projects yet." : "No project is open to you yet."), "\(role) \(capabilities)")
            #expect(empty.title == (creates ? "Create your first project" : "No project is open to you yet"), "\(role) \(capabilities)")
            #expect(empty.action == (creates ? .createProject : .checkAgain), "\(role) \(capabilities)")
            guard creates else { continue }
            #expect(empty.body == "A project holds the repositories, agents and size that every workspace starts from. Create one, and Sail builds its template on the runner.")
            #expect(empty.line == "sail.example.com · you can create projects as \(role == .admin ? "an admin" : "a member")", "\(role)")
        }
        let plane = try ControlPlane(address: "https://sail.example.com")
        let unconfirmed = WorkspacesModel.Person(SignedIn(controlPlane: plane, fde: "mara", until: .distantFuture, whoami: .failure(AccountRefusal(.unreachable, at: plane))))
        guard case let .shown(panel, .noProjects(empty)) = Self.none(unconfirmed).screen else { Issue.record("no empty state"); return }
        #expect(panel.emptyLine == "No project is open to you yet." && empty.title == "No project is open to you yet" && empty.action == .checkAgain)
        #expect(empty.line == "signed in as mara · role not yet confirmed · https://sail.example.com")
    }

    /// With projects listed, Create Project is offered from the panel by the same rule; with none,
    /// only while This Mac stands where the main area would offer it.
    @Test func thePanelOffersCreateProjectByTheSameRule() throws {
        for (capabilities, offered) in [([Capability.read, .write, .build], true), ([.read, .write, .admin], false), ([.read, .build], false), ([.read], false)] {
            var model = WorkspacesModel(person: try Self.person(.member, capabilities))
            #expect(!model.panel.creates, "offered before any project was listed")
            model.chooseThisMac()
            #expect(!model.panel.creates, "offered on This Mac before any project was listed")
            model.projectsListed(WorkspacesModelTests.answer(.projects, "projects"))
            #expect(model.panel.creates == offered)
            var none = Self.none(try Self.person(.member, capabilities))
            none.chooseThisMac()
            #expect(none.panel.creates == offered, "This Mac shown with no project: \(none.panel.creates)")
        }
    }

    /// The sizes are the app's words for `resources`, Medium chosen at first; SIZE's help names the
    /// choice; Custom starts at the size chosen before and sends what is typed, its digits alone.
    @Test func theSizePresets() {
        var sheet = NewProject(agents: Self.harnesses)
        #expect(sheet.size == .medium && sheet.sizeHelp == "4 cores · 8 GB memory · 50 GB disk for each workspace.")
        let sent = NewProject.Size.allCases.map { size -> ProjectDefinition.Resources in
            sheet.choose(size)
            return sheet.definition.resources
        }
        #expect(sent == [
            ProjectDefinition.Resources(cpu: 2, memory: "4GB", disk: "30GB"), ProjectDefinition.Resources(cpu: 4, memory: "8GB", disk: "50GB"),
            ProjectDefinition.Resources(cpu: 8, memory: "16GB", disk: "100GB"), ProjectDefinition.Resources(cpu: 8, memory: "16GB", disk: "100GB"),
        ])
        #expect(sheet.typeCustom(.cores, "1 core") == "1")
        sheet.typeCustom(.memory, "12")
        sheet.typeCustom(.disk, "")
        #expect(sheet.definition.resources == ProjectDefinition.Resources(cpu: 1, memory: "12GB", disk: "0GB"))
        #expect(sheet.sizeHelp == "1 core · 12 GB memory · 0 GB disk for each workspace.")
        sheet.choose(.small)
        #expect(sheet.sizeHelp == "2 cores · 4 GB memory · 30 GB disk for each workspace.")
        sheet.choose(.large)
        sheet.choose(.custom)
        #expect(sheet.custom == [.cores: "8", .memory: "16", .disk: "100"], "Custom did not start from the size chosen before")
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
        for (url, path) in [
            ("github.com/acme/app?ref=main", "app"), ("github.com/acme/app.git#readme", "app"), ("git@github.com:acme/app.git", "app"), ("git@host:app.git", "app"),
            ("git@host:app", "app"), ("github.com/acme/app  ", "app"),
        ] {
            sheet.typeURL(url, row: 0)
            #expect(sheet.repos[0].path == path, "\(url) filled \(sheet.repos[0].path)")
        }
        sheet.addRepo()
        sheet.typeURL("github.com/other/app", row: 1)
        sheet.addRepo()
        sheet.typeURL("github.com/third/app.git", row: 2)
        #expect(sheet.repos.map(\.path) == ["app", "app-2", "app-3"])
    }

    /// Create sends one definition, whole, as the server's golden create took it: the name, each
    /// repository given a URL (a bare host and path over https), the agents checked, the size; a
    /// second press while it is under way sends nothing.
    @Test func createSendsTheWholeDefinitionOnce() throws {
        var sheet = NewProject(agents: Self.harnesses + [NewProject.Agent(name: "pi", display: "Pi")])
        #expect(!sheet.creates && sheet.send() == nil, "Create offered with no name")
        sheet.named("   ")
        #expect(!sheet.creates, "Create offered for a name of spaces")
        sheet.typePath("app", row: 0)
        sheet.named("marina")
        #expect(!sheet.creates, "Create offered with a path and no URL to clone into it")
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
        var ssh = NewProject(agents: Self.harnesses)
        ssh.named("app")
        ssh.typeURL("git@github.com:acme/app.git", row: 0)
        #expect(ssh.send()?.repos == [ProjectDefinition.Repo(url: "git@github.com:acme/app.git", path: "app")], "an ssh address not sent as typed")
    }

    /// While the server's harnesses could not be listed, Create is off and AGENTS says why; listed
    /// again, each is shown checked and Create is on: no project is created with agents the person
    /// did not see.
    @Test func noCreateWhileTheAgentsAreNotListed() throws {
        var sheet = NewProject(agents: [])
        sheet.agentsListed(.failure(.unreachable("lost")), at: "sail.example.com")
        sheet.named("harbor")
        #expect(sheet.agentsUnlisted == "Sail could not list the agents: Sail cannot reach the control plane at sail.example.com." && !sheet.creates && sheet.send() == nil)
        sheet.agentsListed(WorkspacesModelTests.answer(.knownAgents, "agents"), at: "sail.example.com")
        #expect(sheet.agentsUnlisted == nil && sheet.agents.map(\.name) == ["claude-code", "codex"] && sheet.agents.allSatisfy(\.checked) && sheet.creates)
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
        _ = sheet.send()
        #expect(sheet.nameRefused == nil, "a refusal stood under a name sent again")
        sheet.refuse(try Self.taken(), at: "sail.example.com")
        sheet.named("harbour")
        #expect(sheet.nameRefused == nil)
    }

    /// A refusal naming repositories' paths stands under each path it names, the server counting only
    /// the repositories sent (those with a URL), until that row is edited; an invalid name stands
    /// under the name.
    @Test func aRefusalNamingAPathStandsUnderIt() throws {
        var sheet = NewProject(agents: Self.harnesses)
        sheet.named("jetty")
        sheet.typeURL("https://github.com/harbor/app.git", row: 0)
        sheet.addRepo()
        sheet.addRepo()
        sheet.typeURL("https://github.com/harbor/site.git", row: 2)
        sheet.typePath("app", row: 2)
        let sent = sheet.send()
        #expect(sent?.repos.map(\.path) == ["app", "app"])
        sheet.refuse(SailJSON.refusal(response(try Exchange("refusal-project-repos-same-path")), route: "POST /v1/projects"), at: "sail.example.com")
        let said = "More than one repository is cloned to app: give each its own path."
        #expect(sheet.pathsRefused == [0: NewProject.Refused(said: said, remedy: nil), 2: NewProject.Refused(said: said, remedy: nil)])
        #expect(sheet.refused.isEmpty && sheet.nameRefused == nil)
        sheet.typePath("site", row: 2)
        #expect(sheet.pathsRefused.keys.sorted() == [0], "an edited path kept its refusal")
        sheet.removeRepo(1)
        #expect(sheet.pathsRefused.isEmpty, "a refusal stayed on a row moved")
        _ = sheet.send()
        sheet.refuse(SailJSON.refusal(response(try Exchange("refusal-project-name-invalid")), route: "POST /v1/projects"), at: "sail.example.com")
        #expect(sheet.nameRefused?.said == "Invalid project name: 'Harbor Docks'. Must match [a-z0-9][a-z0-9-]*, max 63 characters." && sheet.refused.isEmpty)
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
        sheet.typeURL("github.com/acme/app", row: 0)
        #expect(sheet.refused.isEmpty, "the refusal over the foot stayed after the URL was edited")
        _ = sheet.send()
        sheet.refuse(.unreachable("The network connection was lost."), at: "sail.example.com")
        #expect(sheet.refused == ["Sail cannot reach the control plane at sail.example.com."])
    }

    /// The created project is chosen once the projects listed show it, in This Mac's place too, and
    /// stays chosen once the person chooses another; a build refused is said over its page in words,
    /// never a command; a build that got no answer is said with Try Again, which asks it again,
    /// until the build is asked.
    @Test func theCreatedProjectIsChosenOnceListed() throws {
        var model = Self.none(try Self.person(.admin, [.read, .write, .admin, .build]))
        model.chooseThisMac()
        model.projectCreated("lighthouse")
        let listed = ProjectList(projects: ["harbor", "lighthouse"].map { ProjectSummary(name: $0, containerStatus: .known(.notCreated), template: .known(.missing)) }, total: 2)
        let request = model.projectsListed(.success(listed))
        #expect(model.chosen == "lighthouse" && !model.thisMacChosen && request != nil)
        model.listed(WorkspacesModelTests.answer(.workspaces(project: "lighthouse"), "workspaces-none"), request: try #require(request), at: WorkspacesModelTests.at)
        model.built("lighthouse", .failure(.unreachable("The request timed out.")))
        #expect(WorkspacesModelTests.page(model)?.banner == WorkspacesModel.Notice(
            tone: .error, title: "Couldn’t start building lighthouse’s template", line: "Sail cannot reach the control plane at https://sail.example.com.", tryAgain: .build
        ))
        model.listed(WorkspacesModelTests.answer(.workspaces(project: "lighthouse"), "workspaces-none"), request: model.listAgain(), at: WorkspacesModelTests.at)
        #expect(WorkspacesModelTests.page(model)?.banner?.tryAgain == .build, "a later list took away the build still owed")
        model.built("lighthouse", WorkspacesModelTests.answer(.buildTemplate(project: "lighthouse"), "refusal-template-2d-login-not-stored"))
        #expect(WorkspacesModelTests.page(model)?.banner == WorkspacesModel.Notice(
            tone: .error, title: "github.com asks for credentials to fetch lighthouse's repo lamp, and dana keeps no token for it.", line: "An admin stores it on the runner for now.",
            tryAgain: nil
        ))
        model.built("lighthouse", WorkspacesModelTests.answer(.buildTemplate(project: "lighthouse"), "template-2f-joined"))
        #expect(WorkspacesModelTests.page(model)?.banner == nil, "a build asked left the refusal standing")
        model.built("lighthouse", .failure(.unexpected(route: "POST /v1/projects/lighthouse/template:build", status: 502)))
        let building = ProjectList(projects: [ProjectSummary(name: "lighthouse", containerStatus: .known(.notCreated), template: .known(.building))], total: 1)
        model.projectsListed(.success(building))
        #expect(WorkspacesModelTests.page(model)?.banner == nil, "the build owed stayed once the list showed it building")
        model.projectsListed(.success(listed))
        #expect(model.chosen == "lighthouse", "a later list chose again")
        _ = model.choose("harbor")
        model.projectsListed(.success(listed))
        #expect(model.chosen == "harbor", "a later list chose the created project over the person's choice")
        model.projectCreated("gone")
        model.projectsListed(.success(listed))
        model.projectsListed(.success(ProjectList(projects: listed.projects + [ProjectSummary(name: "gone", containerStatus: .known(.notCreated), template: .known(.missing))], total: 3)))
        #expect(model.chosen == "harbor", "a project created and gone from the list was chosen once another of its name was listed")
    }

    /// Create's answer from the server; a name taken by a project the person wrote last with this very
    /// definition (their create that landed while its answer was lost) answers as that create did;
    /// one written by another, or to another definition, stays the refusal.
    @Test func aCreateThatLandedUnansweredIsTheirs() async throws {
        let created = await SailClient(transport: RecordedServer([try Exchange("project-created")]), token: "tok").create(marina, by: "dana")
        #expect(try created.get().name == "marina")
        let taken = try Exchange("refusal-project-name-taken")
        let landed = RecordedServer([Exchange(method: "POST", path: "/v1/projects", status: taken.status, body: taken.body), try Exchange("project-definition")])
        let theirs = await SailClient(transport: landed, token: "tok").create(marina, by: "dana")
        #expect(try theirs.get().name == "marina" && (try theirs.get().updatedBy) == "dana")
        #expect(await landed.requests.map(\.route) == ["POST /v1/projects", "GET /v1/projects/marina/definition"])
        let another = await SailClient(transport: landed, token: "tok").create(marina, by: "uday")
        #expect(another.failure.flatMap(NewProject.nameRefusal) != nil, "another's project taken as the person's own")
        let changed = ProjectDefinition(name: "marina", repos: marina.repos, agent: ProjectDefinition.Agent(install: ["codex"]), resources: marina.resources)
        let other = await SailClient(transport: landed, token: "tok").create(changed, by: "dana")
        #expect(other.failure.flatMap(NewProject.nameRefusal) != nil, "a project of another definition taken as this create")
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
