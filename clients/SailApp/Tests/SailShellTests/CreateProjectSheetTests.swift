import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Testing

/// The New project sheet (`createSheet` of the first-impressions page, section 6): what a person
/// types reaches the model and the paths fill from the URLs, Create sends once, the server's
/// refusal stands under the field it names or over the foot, and the sheet is laid out whole, in
/// its window, at 600 pt and at the least window, left to right and right to left; nothing in it
/// acts while the create is under way, and none is sent while the agents are not listed; the
/// Workspaces section offers Create Project only to those who may create and build a project, and
/// a project created is listed and chosen, then built.
@MainActor @Suite(.serialized, .timeLimit(.minutes(1))) struct CreateProjectSheetTests {
    init() {
        ExitWatch.install()
    }

    static let harnesses = [NewProject.Agent(name: "claude-code", display: "Claude Code"), NewProject.Agent(name: "codex", display: "Codex CLI")]
    /// A definition, for the route a recorded answer is read as.
    static let harbor = ProjectDefinition(
        name: "harbor", repos: [], agent: ProjectDefinition.Agent(install: []), resources: ProjectDefinition.Resources(cpu: 4, memory: "8GB", disk: "50GB")
    )
    /// The least window's sheet: its 480 pt less 12 pt each side.
    static let least = 480.0 - 2 * CreateProjectSheet.inset

    static func sheet(
        _ model: NewProject = NewProject(agents: Self.harnesses), width: Double = CreateProjectSheet.width, room: Double = 720, direction: NSUserInterfaceLayoutDirection = .leftToRight
    ) throws -> CreateProjectSheet {
        let sheet = CreateProjectSheet(
            model, width: width, room: room, direction: direction, address: "sail.example.com", look: .light,
            titleFont: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts)
        )
        sheet.orderFront(nil)
        return sheet
    }

    /// `button` pressed as its click would press it, its action sent, with none of AppKit's own
    /// click handling, which ends a suite's process with no `NSApp.run` under it (#499).
    static func press(_ button: NSButton) {
        _ = button.sendAction(button.action, to: button.target)
    }

    static func type(_ text: String, into field: SailTextField, of sheet: CreateProjectSheet) {
        field.field.stringValue = text
        sheet.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: field.field))
    }

    /// `view` clicked at its middle as a mouse-up there tells it, with none of AppKit's own tracking.
    static func click(_ view: NSView) throws {
        let window = try #require(view.window)
        let at = view.convert(NSPoint(x: view.bounds.midX, y: view.bounds.midY), to: nil)
        let up = try #require(NSEvent.mouseEvent(
            with: .leftMouseUp, location: at, modifierFlags: [], timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 0
        ))
        view.mouseUp(with: up)
    }

    static func answer<Response>(_ endpoint: Endpoint<Response>, _ golden: String) throws -> Result<Response, SailError> {
        let exchange = try Exchange(golden)
        do throws(SailError) {
            return .success(try endpoint.decode(HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)))
        } catch {
            return .failure(error)
        }
    }

    /// Typing `github.com/acme/harbor-api` fills the path `harbor-api` after `~/workspace/`; once the
    /// path is typed it stops following the URL.
    @Test func aPathFollowsItsURLUntilItIsTyped() throws {
        let sheet = try Self.sheet()
        defer { sheet.orderOut(nil) }
        Self.type("github.com/acme/harbor-api", into: sheet.urls[0], of: sheet)
        #expect(sheet.paths[0].field.stringValue == "harbor-api" && sheet.paths[0].prefix == "~/workspace/")
        Self.type("api", into: sheet.paths[0], of: sheet)
        Self.type("github.com/acme/harbor-web", into: sheet.urls[0], of: sheet)
        #expect(sheet.paths[0].field.stringValue == "api" && sheet.model.repos[0].path == "api")
        Self.press(sheet.addRepo)
        try #require(sheet.urls.count == 2)
        #expect(sheet.paths[1].field.stringValue.isEmpty)
        Self.press(sheet.removes[0])
        try #require(sheet.urls.count == 1)
        #expect(sheet.urls[0].field.stringValue.isEmpty)
    }

    /// Return pressed twice sends one create; a taken name stands under NAME in the server's words
    /// with its remedy, nothing over the foot, the sheet open with what was typed.
    @Test func aTakenNameStandsUnderTheNameAndTheSheetStays() async throws {
        let sheet = try Self.sheet()
        defer { sheet.orderOut(nil) }
        var sent: [ProjectDefinition] = [], ended = false
        let taken = try Self.answer(.createProject(Self.harbor), "refusal-project-name-taken")
        sheet.send = { definition in
            sent.append(definition)
            return taken
        }
        sheet.onEnd = { ended = true }
        Self.type("harbor", into: sheet.name, of: sheet)
        Self.type("github.com/acme/harbor-api", into: sheet.urls[0], of: sheet)
        let editor = NSTextView()
        for _ in 0 ..< 2 { _ = sheet.control(sheet.name.field, textView: editor, doCommandBy: #selector(NSResponder.insertNewline(_:))) }
        await sheet.sending?.value
        #expect(sent.count == 1 && sent.first?.name == "harbor" && sent.first?.repos == [ProjectDefinition.Repo(url: "https://github.com/acme/harbor-api", path: "harbor-api")])
        #expect(sheet.name.error == "A project named harbor already exists." && sheet.name.remedy == "Choose another name, or open harbor.")
        #expect(sheet.refusal == nil && !ended && sheet.name.field.stringValue == "harbor" && sheet.urls[0].field.stringValue == "github.com/acme/harbor-api")
        #expect(sheet.create.isEnabled && sheet.create.busyWord == nil)
        Self.type("harbour", into: sheet.name, of: sheet)
        #expect(sheet.name.error == nil, "the refusal stayed under a name edited")
    }

    /// A refusal naming no field stands over the foot, inside the sheet, under the fields.
    @Test func aRefusalNamingNoFieldStandsOverTheFoot() async throws {
        let sheet = try Self.sheet(width: Self.least, room: 560)
        defer { sheet.orderOut(nil) }
        sheet.send = { _ in .failure(.unreachable("The network connection was lost.")) }
        Self.type("harbor", into: sheet.name, of: sheet)
        Self.press(sheet.create)
        await sheet.sending?.value
        let banner = try #require(sheet.refusal)
        #expect(sheet.name.error == nil)
        #expect(banner.frame.minY > sheet.sizes.frame.maxY && banner.frame.maxX <= Self.least, "the refusal at \(banner.frame)")
        #expect(WorkspacesReachTests.outOfReach(in: try #require(sheet.contentView)) == [])
    }

    /// Two repositories at one path: the server's refusal stands under each path, the first holding
    /// the keys, nothing over the foot; editing a path takes its refusal away.
    @Test func aRefusalNamingAPathStandsUnderIt() async throws {
        let sheet = try Self.sheet(width: Self.least, room: 560)
        defer { sheet.orderOut(nil) }
        let refused = try Self.answer(.createProject(Self.harbor), "refusal-project-repos-same-path")
        sheet.send = { _ in refused }
        Self.type("jetty", into: sheet.name, of: sheet)
        Self.type("https://github.com/harbor/app.git", into: sheet.urls[0], of: sheet)
        Self.press(sheet.addRepo)
        Self.type("https://github.com/harbor/site.git", into: sheet.urls[1], of: sheet)
        Self.type("app", into: sheet.paths[1], of: sheet)
        Self.press(sheet.create)
        await sheet.sending?.value
        let said = "More than one repository is cloned to app: give each its own path."
        #expect(sheet.paths.map(\.error) == [said, said] && sheet.refusal == nil && sheet.name.error == nil)
        #expect((sheet.firstResponder as? NSTextView)?.delegate === sheet.paths[0].field, "the keys are not in the first path refused")
        #expect(WorkspacesReachTests.outOfReach(in: try #require(sheet.contentView)) == [])
        Self.type("site", into: sheet.paths[1], of: sheet)
        #expect(sheet.paths[1].error == nil && sheet.paths[0].error == said)
    }

    /// An agent's check clicked off: the create sends the agents still checked.
    @Test func anAgentClickedOffIsNotInstalled() async throws {
        let sheet = try Self.sheet()
        defer { sheet.orderOut(nil) }
        var sent: [ProjectDefinition] = []
        sheet.send = { definition in
            sent.append(definition)
            return .failure(.unreachable("lost"))
        }
        Self.type("harbor", into: sheet.name, of: sheet)
        try Self.click(sheet.checks[1])
        #expect(!sheet.checks[1].checked && sheet.checks[0].checked)
        Self.press(sheet.create)
        await sheet.sending?.value
        #expect(sent.map(\.agent.install) == [["claude-code"]])
    }

    /// While the create is under way nothing in the sheet acts: Escape and Cancel do not end it, and
    /// the fields, ×, Add Repository, the sizes and the checks are off; its answer then ends it.
    @Test func nothingActsWhileTheCreateIsUnderWay() async throws {
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 760), styleMask: [.titled], backing: .buffered, defer: false)
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        let sheet = try Self.sheet()
        let created = try Self.answer(.createProject(Self.harbor), "project-created"), answer = Gate()
        var ended = false
        sheet.send = { _ in
            await answer.wait()
            return created
        }
        sheet.onEnd = { ended = true }
        window.beginSheet(sheet) { _ in }
        Self.type("marina", into: sheet.name, of: sheet)
        Self.press(sheet.create)
        _ = sheet.control(sheet.name.field, textView: NSTextView(), doCommandBy: #selector(NSResponder.cancelOperation(_:)))
        sheet.cancelOperation(nil)
        Self.press(sheet.cancel)
        #expect(!ended && window.attachedSheet === sheet, "the sheet ended while its create was under way")
        let controls: [NSControl] = [sheet.name.field, sheet.urls[0].field, sheet.paths[0].field, sheet.removes[0], sheet.addRepo, sheet.cancel]
        #expect(controls.allSatisfy { !$0.isEnabled } && !sheet.sizes.isEnabled && sheet.checks.allSatisfy { !$0.isEnabled })
        try Self.click(sheet.checks[0])
        #expect(sheet.checks[0].checked && sheet.model.agents[0].checked, "a check turned while the create was under way")
        answer.open()
        await sheet.sending?.value
        #expect(ended && window.attachedSheet == nil)
    }

    /// The agents not listed: AGENTS says why with Try Again and Create is off; Try Again lists them,
    /// each checked, and Create is on.
    @Test func noCreateUntilTheAgentsAreListed() async throws {
        var unlisted = NewProject(agents: [])
        unlisted.agentsListed(.failure(.unreachable("lost")), at: "sail.example.com")
        let sheet = try Self.sheet(unlisted)
        defer { sheet.orderOut(nil) }
        let known = try Self.answer(.knownAgents, "agents")
        sheet.askAgents = { known }
        Self.type("harbor", into: sheet.name, of: sheet)
        #expect(!sheet.create.isEnabled && sheet.checks.isEmpty && sheet.agentsAgain.superview != nil)
        #expect(Driverless.words(sheet.contentView).contains("Sail could not list the agents: Sail cannot reach the control plane at sail.example.com."))
        Self.press(sheet.agentsAgain)
        #expect(await RunnerTabTests.until { sheet.checks.count == 2 }, "Try Again listed no agents")
        #expect(sheet.checks.allSatisfy(\.checked) && sheet.create.isEnabled && sheet.agentsAgain.superview == nil)
    }

    /// Created: the sheet ends on its window and names the project the server created.
    @Test func aCreatedProjectEndsTheSheet() async throws {
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 760), styleMask: [.titled], backing: .buffered, defer: false)
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        let sheet = try Self.sheet()
        let created = try Self.answer(.createProject(Self.harbor), "project-created")
        var named: String?, ended = false
        sheet.send = { _ in created }
        sheet.onCreated = { named = $0 }
        sheet.onEnd = { ended = true }
        window.beginSheet(sheet) { _ in }
        Self.type("marina", into: sheet.name, of: sheet)
        _ = sheet.control(sheet.name.field, textView: NSTextView(), doCommandBy: #selector(NSResponder.insertNewline(_:)))
        await sheet.sending?.value
        #expect(named == "marina" && ended && window.attachedSheet == nil)
    }

    /// The sheet stays inside its window and nothing in it is cut or drawn over another, at 600 pt
    /// and at the least window, left to right and right to left, with Custom's fields shown and
    /// with more repositories than the window has room for, which then scroll above the foot.
    @Test(arguments: [456.0, 600.0])
    func theSheetIsLaidOutWhole(_ width: Double) throws {
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let sheet = try Self.sheet(width: width, room: 560, direction: direction)
            defer { sheet.orderOut(nil) }
            Self.type("the-longest-name-a-project-may-have-is-sixty-three-characters-x", into: sheet.name, of: sheet)
            for row in 0 ..< 6 {
                if row > 0 { Self.press(sheet.addRepo) }
                Self.type("github.com/standardapplied/harbor-api-\(row)", into: sheet.urls[row], of: sheet)
            }
            sheet.sizes.onChoose(.custom)
            let content = try #require(sheet.contentView)
            #expect(content.frame.width == width && content.frame.height <= 560, "\(direction.rawValue): the sheet is \(content.frame.size)")
            #expect(WorkspacesReachTests.outOfReach(in: content) == [], "\(width) \(direction.rawValue): \(WorkspacesReachTests.outOfReach(in: content))")
            let url = sheet.urls[0].frame, path = sheet.paths[0].frame
            if width < CreateProjectSheet.sideBySide + 2 * CreateProjectSheet.padding.sides {
                #expect(path.minY >= url.maxY - 2 * Look.haloRoom, "\(width): the path is not under its URL: \(url) \(path)")
            } else {
                #expect(abs(path.minY - url.minY) < 0.5 && (direction == .rightToLeft ? path.maxX <= url.minX : url.maxX <= path.minX), "\(width) \(direction.rawValue): \(url) \(path)")
            }
            let folder = sheet.paths[0]
            #expect(folder.userInterfaceLayoutDirection == .leftToRight && folder.field.frame.minX > folder.box.minX + 40, "\(direction.rawValue): the path's text at \(folder.field.frame), its box at \(folder.box)")
            let remove = sheet.removes[0].frame
            #expect(direction == .rightToLeft ? remove.minX < url.minX : remove.maxX > url.maxX, "\(direction.rawValue): × at \(remove), the URL at \(url)")
        }
    }

    /// SIZE says what each choice gives, and Custom shows its three fields only while chosen,
    /// starting at the size chosen before.
    @Test func eachSizeSaysWhatItGives() throws {
        let sheet = try Self.sheet()
        defer { sheet.orderOut(nil) }
        #expect(Driverless.words(sheet.contentView).contains("4 cores · 8 GB memory · 50 GB disk for each workspace."))
        #expect(sheet.custom.allSatisfy { $0.field.isHidden })
        sheet.sizes.onChoose(.large)
        #expect(Driverless.words(sheet.contentView).contains("8 cores · 16 GB memory · 100 GB disk for each workspace.") && sheet.sizes.chosen == .large)
        sheet.sizes.onChoose(.custom)
        #expect(sheet.custom.allSatisfy { !$0.field.isHidden } && sheet.custom.map(\.field.field.stringValue) == ["8", "16", "100"])
        Self.type("6 cores", into: sheet.custom[0].field, of: sheet)
        #expect(sheet.custom[0].field.field.stringValue == "6" && Driverless.words(sheet.contentView).contains("6 cores · 16 GB memory · 100 GB disk for each workspace."))
    }

    /// No project yet: Create Project is offered to a person who may create and build a project, and
    /// opens the sheet with the server's harnesses, each checked; others are told none is open to
    /// them yet, with Check Again and no Create Project.
    @Test func createProjectIsOfferedToThoseWhoMayCreateAndBuild() async throws {
        let server = RecordedServer([try Exchange("projects-none"), try Exchange("agents")])
        let (view, window) = try WorkspacesViewTests.view(server)
        defer {
            window.attachedSheet.map { window.endSheet($0) }
            window.orderOut(nil)
        }
        view.person = WorkspacesModel.Person(handle: "uday", admin: false, confirmed: true, controlPlane: "127.0.0.1", creates: false)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("No project is open to you yet") }, "\(view.text)")
        #expect(Driverless.button("Create Project", in: view.project) == nil && Driverless.button("Check Again", in: view.project) != nil && view.panel.createProject.isHidden)
        view.person = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: "127.0.0.1", creates: true)
        #expect(await RunnerTabTests.until { view.text.contains("127.0.0.1 · you can create projects as an admin") }, "\(view.text)")
        let create = try #require(Driverless.button("Create Project", in: view.project))
        #expect(view.panel.createProject.isHidden, "the panel offered Create Project with no project listed")
        Self.press(create)
        #expect(await RunnerTabTests.until { window.attachedSheet is CreateProjectSheet }, "Create Project opened no sheet")
        let sheet = try #require(window.attachedSheet as? CreateProjectSheet)
        #expect(sheet.checks.map(\.label) == ["Claude Code", "Codex CLI"] && sheet.checks.allSatisfy(\.checked))
        #expect(await server.requests.map(\.route) == ["GET /v1/projects", "GET /v1/agents"])
    }

    /// With projects listed, the panel's + offers Create Project to one who may write, and opens
    /// the sheet; to one who may not, it is not there.
    @Test func thePanelOffersCreateProjectBesideTheProjects() async throws {
        let server = RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("agents")])
        let (view, window) = try WorkspacesViewTests.view(server)
        defer {
            window.attachedSheet.map { window.endSheet($0) }
            window.orderOut(nil)
        }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        #expect(view.panel.createProject.isHidden, "offered to one who may not write")
        view.person = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: "127.0.0.1", creates: true)
        #expect(!view.panel.createProject.isHidden && Driverless.button("Create Project", in: view.project) == nil)
        window.displayIfNeeded()
        let button = view.panel.createProject, cell = try #require(button.superview as? HeadCell)
        let head = view.panel.list.rect(ofRow: 2), plus = cell.convert(button.alignmentRect(forFrame: button.frame), to: view.panel.list)
        #expect(head.contains(plus), "the + at \(plus), the control plane's head at \(head)")
        Self.press(view.panel.createProject)
        #expect(await RunnerTabTests.until { window.attachedSheet is CreateProjectSheet }, "the panel's + opened no sheet")
    }

    /// A head's action stands before its count, and right to left mirrors it; a long host yields to
    /// it, cut to end 8 pt before it, as it yields to the count.
    @Test func aHeadsActionStandsBeforeItsCount() throws {
        var frames: [NSRect] = []
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let plus = SailButton(icon: .plus, label: "Create Project", kind: .quiet, size: .small, look: .light, target: nil, action: nil)
            let host = WorkspacesScrollTests.longHost
            let cell = HeadCell(host, count: 1, icon: .cloud, at: (14, 18), look: .light, mono: try LabelFont(directory: LookTests.fonts), hairline: false, action: plus)
            cell.userInterfaceLayoutDirection = direction
            cell.frame = NSRect(x: 0, y: 0, width: 235, height: 30)
            frames.append(plus.alignmentRect(forFrame: plus.frame))
            let title = cell.titleFrame, room = direction == .rightToLeft ? Double(title.minX) - Double(plus.frame.maxX) : Double(plus.frame.minX) - Double(title.maxX)
            #expect(room > HeadCell.countGap - 0.5 && cell.fitted.string != host.uppercased(), "\(direction): \(cell.fitted.string) at \(title), the + at \(plus.frame)")
        }
        let ltr = frames[0], rtl = frames[1]
        #expect(ltr.maxX < 235 - 18 - 8 && ltr.minX > 235 / 2 && abs(ltr.midY - 15) < 1, "\(ltr)")
        #expect(abs(rtl.minX - (235 - ltr.maxX)) < 0.5 && abs(rtl.minY - ltr.minY) < 0.5, "right to left at \(rtl), left to right at \(ltr)")
    }

    /// The head's tooltip and its +'s never show together, and the + takes a click over a cut host.
    @Test func aHeadsTooltipGivesWayToItsAction() throws {
        let plus = SailButton(icon: .plus, label: "Create Project", kind: .quiet, size: .small, look: .light, target: nil, action: nil)
        let cell = HeadCell(WorkspacesScrollTests.longHost, count: 1, icon: .cloud, at: (14, 18), look: .light, mono: try LabelFont(directory: LookTests.fonts), hairline: false, action: plus)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 235, height: 30), styleMask: [.titled], backing: .buffered, defer: false)
        defer {
            Tooltip.putAway()
            window.orderOut(nil)
        }
        window.contentView = cell
        window.orderFront(nil)
        let own = try #require(plus.tooltip)
        try #require(cell.tooltip.shows, "the cut host has no tooltip")
        cell.tooltip.show()
        own.show()
        #expect(own.isShown && !cell.tooltip.isShown, "the + said \(own.isShown), the head \(cell.tooltip.isShown)")
        cell.tooltip.show()
        #expect(cell.tooltip.isShown && !own.isShown, "the head said \(cell.tooltip.isShown), the + \(own.isShown)")
        let middle = NSPoint(x: plus.frame.midX, y: plus.frame.midY)
        #expect(cell.hitTest(cell.convert(middle, to: cell.superview)) === plus, "a click on the + lands on \(String(describing: cell.hitTest(cell.convert(middle, to: cell.superview))))")
    }

    /// A project created is listed and chosen before its build is asked, once; a build that got no
    /// answer leaves Try Again over its page, which asks it again, and then the page says it builds.
    @Test func aCreatedProjectIsChosenThenBuilt() async throws {
        let build = "POST /v1/projects/lighthouse/template%3Abuild"
        let desk = try Desk(["projects-none", "agents"])
        let (view, window) = try WorkspacesViewTests.view(desk)
        defer {
            window.attachedSheet.map { window.endSheet($0) }
            window.orderOut(nil)
        }
        view.person = WorkspacesModel.Person(handle: "dana", admin: true, confirmed: true, controlPlane: "127.0.0.1", creates: true)
        view.shown()
        #expect(await RunnerTabTests.until { Driverless.button("Create Project", in: view.project) != nil }, "\(view.text)")
        await desk.answer(try Exchange("project-created").altered(at: ["name"], to: "lighthouse"))
        await desk.answer(try Exchange("projects").altered(at: ["projects"], to: [["name": "lighthouse", "container_status": "not_created", "template": "missing"]]))
        let none = try Exchange("workspaces-none")
        await desk.answer(Exchange(method: "GET", path: "/v1/projects/lighthouse/workspaces", status: none.status, body: none.body))
        await desk.hold(build)
        Self.press(try #require(Driverless.button("Create Project", in: view.project)))
        #expect(await RunnerTabTests.until { window.attachedSheet is CreateProjectSheet }, "Create Project opened no sheet")
        let sheet = try #require(window.attachedSheet as? CreateProjectSheet)
        Self.type("lighthouse", into: sheet.name, of: sheet)
        Self.press(sheet.create)
        await desk.arrived(during: Task { _ = await RunnerTabTests.until { false } })
        let asked = await desk.asked
        #expect(asked.filter { $0 == build }.count == 1 && asked.last == build, "\(asked)")
        #expect(asked.firstIndex(of: "POST /v1/projects").map { asked[$0...].contains("GET /v1/projects") } == true, "not listed before the build: \(asked)")
        #expect(view.panel.list.chosen == "lighthouse" && view.text.hasPrefix("Workspaces\nlighthouse\n"), "not chosen before the build: \(view.text)")
        await desk.release()
        #expect(await RunnerTabTests.until { view.text.contains("Couldn’t start building lighthouse’s template") }, "\(view.text)")
        await desk.answer(try Exchange("template-2f-joined"))
        await desk.answer(try Exchange("projects").altered(at: ["projects"], to: [["name": "lighthouse", "container_status": "not_created", "template": "building"]]))
        Self.press(try #require(Driverless.button("Try Again", in: view.project)))
        #expect(await RunnerTabTests.until { view.text.contains("lighthouse’s template is being built") && !view.text.contains("Couldn’t start") }, "\(view.text)")
        #expect(await desk.asked.filter { $0 == build }.count == 2)
    }

    /// Another person signed in while a project's create, or the list after it, is under way: the
    /// create carries nothing over to them, neither its build nor its project chosen.
    @Test(arguments: ["POST /v1/projects", "GET /v1/projects"])
    func aChangeOfPersonCarriesNoCreateOver(_ held: String) async throws {
        let build = "POST /v1/projects/lighthouse/template%3Abuild"
        let desk = try Desk(["projects-none", "agents"])
        let (view, window) = try WorkspacesViewTests.view(desk)
        defer {
            window.attachedSheet.map { window.endSheet($0) }
            window.orderOut(nil)
        }
        view.person = WorkspacesModel.Person(handle: "dana", admin: true, confirmed: true, controlPlane: "127.0.0.1", creates: true)
        view.shown()
        #expect(await RunnerTabTests.until { Driverless.button("Create Project", in: view.project) != nil }, "\(view.text)")
        await desk.answer(try Exchange("project-created").altered(at: ["name"], to: "lighthouse"))
        await desk.answer(try Exchange("projects").altered(at: ["projects"], to: ["harbor", "lighthouse"].map { ["name": $0, "container_status": "not_created", "template": "missing"] }))
        await desk.hold(held)
        Self.press(try #require(Driverless.button("Create Project", in: view.project)))
        #expect(await RunnerTabTests.until { window.attachedSheet is CreateProjectSheet }, "Create Project opened no sheet")
        let sheet = try #require(window.attachedSheet as? CreateProjectSheet)
        Self.type("lighthouse", into: sheet.name, of: sheet)
        Self.press(sheet.create)
        await desk.arrived(during: Task { _ = await RunnerTabTests.until { false } })
        view.person = WorkspacesViewTests.ana
        await desk.release()
        await sheet.sending?.value
        #expect(await RunnerTabTests.until { !view.busy }, "the create's work never ended")
        #expect(await desk.asked.contains(build) == false, "a build asked once another signed in")
        #expect(view.panel.list.chosen != "lighthouse", "dana's project chosen for ana: \(view.text)")
    }
}
