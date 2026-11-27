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
/// its window, at 600 pt and at the least window, left to right and right to left; and the
/// Workspaces section offers Create Project only to those who may write.
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

    static func sheet(width: Double = CreateProjectSheet.width, room: Double = 720, direction: NSUserInterfaceLayoutDirection = .leftToRight) throws -> CreateProjectSheet {
        let sheet = CreateProjectSheet(
            NewProject(agents: Self.harnesses), width: width, room: room, direction: direction, address: "sail.example.com", look: .light,
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
            let remove = sheet.removes[0].frame
            #expect(direction == .rightToLeft ? remove.minX < url.minX : remove.maxX > url.maxX, "\(direction.rawValue): × at \(remove), the URL at \(url)")
        }
    }

    /// SIZE says what each choice gives, and Custom shows its three fields only while chosen.
    @Test func eachSizeSaysWhatItGives() throws {
        let sheet = try Self.sheet()
        defer { sheet.orderOut(nil) }
        #expect(Driverless.words(sheet.contentView).contains("4 cores · 8 GB memory · 50 GB disk for each workspace."))
        #expect(sheet.custom.allSatisfy { $0.field.isHidden })
        sheet.sizes.onChoose(.large)
        #expect(Driverless.words(sheet.contentView).contains("8 cores · 16 GB memory · 100 GB disk for each workspace.") && sheet.sizes.chosen == .large)
        sheet.sizes.onChoose(.custom)
        #expect(sheet.custom.allSatisfy { !$0.field.isHidden })
        Self.type("6 cores", into: sheet.custom[0].field, of: sheet)
        #expect(sheet.custom[0].field.field.stringValue == "6" && Driverless.words(sheet.contentView).contains("6 cores · 8 GB memory · 50 GB disk for each workspace."))
    }

    /// No project yet: Create Project is offered to a person whose `whoami` names `write`, and opens
    /// the sheet with the server's harnesses, each checked; a viewer is told to ask an admin, with no
    /// button.
    @Test func createProjectIsOfferedToThoseWhoMayWrite() async throws {
        let server = RecordedServer([try Exchange("projects-none"), try Exchange("agents")])
        let (view, window) = try WorkspacesViewTests.view(server)
        defer {
            window.attachedSheet.map { window.endSheet($0) }
            window.orderOut(nil)
        }
        view.person = WorkspacesModel.Person(handle: "uday", admin: false, confirmed: true, controlPlane: "127.0.0.1", creates: false)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("127.0.0.1 · Ask an admin to create one.") }, "\(view.text)")
        #expect(Driverless.button("Create Project", in: view.project) == nil && view.panel.createProject.isHidden)
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
        Self.press(view.panel.createProject)
        #expect(await RunnerTabTests.until { window.attachedSheet is CreateProjectSheet }, "the panel's + opened no sheet")
    }
}
