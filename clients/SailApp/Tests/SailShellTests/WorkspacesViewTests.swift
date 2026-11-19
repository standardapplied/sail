import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Testing

/// The Workspaces section drawn from the server's recorded answers: the left panel and a project's
/// list, what a refresh holds (nothing), and the panel hidden under a narrow card.
@MainActor @Suite struct WorkspacesViewTests {
    static let uday = WorkspacesModel.Person(handle: "uday", admin: true, confirmed: true, controlPlane: "127.0.0.1")
    static let ana = WorkspacesModel.Person(handle: "ana", admin: false, confirmed: true, controlPlane: "127.0.0.1")

    static func view(_ transport: any Transport, width: Double = 1000) throws -> (WorkspacesView, KeyWindow) {
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        view.person = Self.uday
        view.client = SailClient(transport: transport, token: "tok")
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = view
        window.orderFront(nil)
        return (view, window)
    }

    static func listed(_ golden: String = "workspaces-three") async throws -> (WorkspacesView, KeyWindow, RecordedServer) {
        let server = RecordedServer([try Exchange("projects"), try Exchange(golden)])
        let (view, window) = try Self.view(server)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "nothing listed: \(view.text)")
        return (view, window, server)
    }

    /// The narrowest card: the window's least 480 pt, less the rail and the card's margins.
    static let narrowestCard = 480.0 - Rail.width - 4

    /// A project view alone, `width` wide.
    static func project(width: Double) throws -> (ProjectView, NSWindow) {
        let project = ProjectView(look: .light, mono: try LabelFont(directory: LookTests.fonts), titleFont: try TitleFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = project
        window.orderFront(nil)
        return (project, window)
    }

    /// The page `rows` make of harbor, listed for `person` (uday, an admin).
    static func page(_ rows: [WorkspaceRow], template: TemplateState = .ready, for person: WorkspacesModel.Person = Self.uday) -> WorkspacesModel.Page? {
        var model = WorkspacesModel(person: person)
        let harbor = ProjectSummary(name: "harbor", containerStatus: .known(.running), template: .known(template))
        let request = model.projectsListed(.success(ProjectList(projects: [harbor], total: 1))) ?? 0
        model.listed(.success(WorkspaceList(project: "harbor", workspaces: rows)), request: request, at: Date())
        guard case let .shown(_, .project(page)) = model.screen else { return nil }
        return page
    }

    /// The list's rows as drawn, each a workspace's line.
    static func lines(_ list: SailList) -> [WorkspaceLine] {
        list.layoutSubtreeIfNeeded()
        let rows = (0 ..< list.numberOfRows).compactMap { list.rowView(atRow: $0, makeIfNecessary: true) }
        for row in rows { row.layoutSubtreeIfNeeded() }
        return rows.flatMap { Self.found(WorkspaceLine.self, in: $0) }
    }

    static func found<Found: NSView>(_ kind: Found.Type, in view: NSView) -> [Found] {
        if let view = view as? Found { return [view] }
        return view.subviews.flatMap { Self.found(kind, in: $0) }
    }

    /// The panel lists the project chosen; the list is yours, then other people's, in words.
    @Test func theListIsSaidAsAPersonReadsIt() async throws {
        let (view, _, _) = try await Self.listed()
        #expect(view.text.contains("harbor\nYour own copies of harbor on the runner. Each one opens as a terminal tab."), "\(view.text)")
        #expect(view.text.contains("dock · running") && view.text.contains("pier · stopped"), "\(view.text)")
        #expect(view.panel.list.chosen == "harbor" && view.project.newWorkspace.isEnabled)
        #expect(!view.text.contains("/v1/") && !view.text.contains("sail "))
    }

    /// A listing under way (the section shown again) holds nothing: only a person's action does (#462).
    @Test func aListingUnderWayHoldsNothing() async throws {
        let exchanges = [try Exchange("projects"), try Exchange("workspaces"), try Exchange("projects"), try Exchange("workspaces")]
        let transport = Held(RecordedServer(exchanges), holding: "workspaces", after: 1)
        let (view, _) = try Self.view(transport)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        let listing = Task { await view.refresh() }
        #expect(await RunnerTabTests.until { transport.routes.filter { $0.contains("workspaces") }.count == 2 }, "the listing asked nothing")
        #expect(!view.busy && view.project.newWorkspace.isEnabled, "a listing under way held New Workspace")
        transport.release()
        await listing.value
        #expect(view.project.newWorkspace.isEnabled)
    }

    /// Under a 720 pt card the panel hides and the content takes the width; ⌃⌘S slides it over,
    /// casting the design system's pop shadow in either look (floating layers are shadowed); beside
    /// the content it casts none.
    @Test func aNarrowCardHidesThePanelAndShowSidebarSlidesItOver() async throws {
        let (view, window, _) = try await Self.listed()
        #expect(!view.panel.isHidden && view.panelShadow.isHidden, "the panel beside the content casts a shadow")
        window.setContentSize(NSSize(width: 700, height: 640))
        view.layoutSubtreeIfNeeded()
        #expect(view.panel.isHidden && near(view.project.frame.minX, 0))
        view.toggleSidebar(nil)
        view.layoutSubtreeIfNeeded()
        #expect(!view.panel.isHidden && near(view.project.frame.minX, 0), "the panel slides over the content")
        #expect(!view.panelShadow.isHidden && near(view.panelShadow.frame, view.panel.frame), "the panel over the content casts no shadow: \(view.panelShadow.frame)")
        #expect(view.subviews.firstIndex(of: view.panelShadow) ?? 0 < view.subviews.firstIndex(of: view.panel) ?? 0, "the shadow is drawn over the panel")
        for look in [Look.light, .dark] {
            view.look = look
            view.layoutSubtreeIfNeeded()
            let cast = view.panelShadow.cast.map { Shadowed(y: -Double($0.shadowOffset.height), blur: 2 * Double($0.shadowRadius), alpha: Double($0.shadowOpacity)) }
            let wanted = PopShadow.layers(look).map { Shadowed(y: $0.y, blur: $0.blur, alpha: $0.color.alpha) }
            #expect(cast.count == wanted.count && zip(cast, wanted).allSatisfy { near($0.y, $1.y) && near($0.blur, $1.blur) && near($0.alpha, $1.alpha) }, "\(look.palette): \(cast)")
        }
        view.toggleSidebar(nil)
        window.setContentSize(NSSize(width: 1000, height: 640))
        view.layoutSubtreeIfNeeded()
        #expect(!view.panel.isHidden && near(view.project.frame.minX, view.panel.frame.maxX) && view.panelShadow.isHidden)
    }

    /// The section shown in `window` beside a terminal of another section, as the shell holds both.
    static func besideATerminal(_ view: WorkspacesView, in window: NSWindow) -> NSTextView {
        let frame = window.contentLayoutRect
        let shell = NSView(frame: frame), terminal = NSTextView(frame: NSRect(x: 0, y: 0, width: 200, height: 40))
        window.contentView = shell
        view.frame = shell.bounds
        shell.addSubview(view)
        shell.addSubview(terminal)
        return terminal
    }

    /// Two projects, harbor and demo, each with a template, and demo with no workspace.
    static let twoProjects = [
        Exchange(method: "GET", path: "/v1/projects", status: 200, body: Data("""
        {"projects": [{"name": "harbor", "container_status": "running", "template": "ready"}, {"name": "demo", "container_status": "running", "template": "ready"}], "total": 2, "schema_version": 1}
        """.utf8)),
        Exchange(method: "GET", path: "/v1/projects/demo/workspaces", status: 200, body: Data(#"{"project": "demo", "workspaces": [], "schema_version": 1}"#.utf8)),
    ]

    /// Opens harbor's create row, types `name` and presses Create; the create held by `transport`.
    static func create(_ name: String, in view: WorkspacesView, _ transport: Held) async {
        view.project.on(.newWorkspace)
        view.project.field.field.stringValue = name
        view.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification))
        view.project.on(.create)
        #expect(await RunnerTabTests.until { transport.routes.contains { $0.hasPrefix("POST") } }, "the create was never asked")
    }

    /// N1: a create answered while the person is elsewhere (another section shown, or typing in a
    /// terminal beside it) takes nothing from them: a create can wait up to 60 s for another. The
    /// person's own New Workspace still gives the field the keys from wherever they were.
    @Test(arguments: [true, false])
    func aCreateAnsweredWhileThePersonIsElsewhereMovesNoKeys(_ hidden: Bool) async throws {
        let exchanges = [try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-create"), try Exchange("workspaces-made")]
        let transport = Held(RecordedServer(exchanges), holding: "POST")
        let (view, window) = try Self.view(transport)
        let terminal = Self.besideATerminal(view, in: window)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        await Self.create("new", in: view, transport)
        view.isHidden = hidden
        #expect(window.makeFirstResponder(terminal))
        transport.release()
        #expect(await RunnerTabTests.until { !view.busy && !view.text.contains("creating") }, "\(view.text)")
        #expect(window.firstResponder === terminal, "the create's answer took the keys from the terminal (section hidden: \(hidden))")
        guard !hidden else { return }
        view.project.on(.newWorkspace)
        #expect(view.project.fieldHasKeys, "New Workspace, the person's own action, left the field without the keys")
    }

    /// N1: a refusal for a project the person has left keeps no claim on the keys: the person
    /// arrowing in the panel keeps them there, then and on coming back to the refused project.
    @Test func aRefusalInAProjectLeftKeepsNoClaimOnTheKeys() async throws {
        let exchanges = Self.twoProjects + [try Exchange("workspaces-three"), try Exchange("refusal-conflict-workspace-exists")]
        let transport = Held(RecordedServer(exchanges), holding: "POST")
        let (view, window) = try Self.view(transport)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        await Self.create("dock", in: view, transport)
        view.panel.list.choose("demo")
        #expect(await RunnerTabTests.until { view.text.hasPrefix("Workspaces\ndemo\n") }, "\(view.text)")
        #expect(window.makeFirstResponder(view.panel.list))
        transport.release()
        #expect(await RunnerTabTests.until { !view.busy }, "the create never ended")
        #expect(window.firstResponder === view.panel.list, "harbor's refusal took the keys from the panel while demo is shown")
        view.panel.list.choose("harbor")
        #expect(await RunnerTabTests.until { view.text.contains("exists already") }, "\(view.text)")
        #expect(window.firstResponder === view.panel.list, "coming back to harbor, its old refusal pulled the keys out of the panel")
    }

    /// N1, n9: a create sent by someone signed in before is dropped when its answer comes, though
    /// the new person is shown the same project: it claims no keys and lists nothing for them.
    @Test func aCreateFromBeforeASignInAsSomeoneElseIsDropped() async throws {
        let exchanges = [try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-create"), try Exchange("workspaces-made")]
        let transport = Held(RecordedServer(exchanges), holding: "POST")
        let (view, window) = try Self.view(transport)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        await Self.create("new", in: view, transport)
        view.person = Self.ana
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "harbor never listed for ana: \(view.text)")
        let lists = transport.routes.filter { $0.hasPrefix("GET /v1/projects/harbor") }.count
        transport.release()
        #expect(await RunnerTabTests.until { !view.busy }, "the create never ended")
        #expect(window.firstResponder !== view.project.list, "the earlier person's create gave the list the keys")
        #expect(transport.routes.filter { $0.hasPrefix("GET /v1/projects/harbor") }.count == lists, "the earlier person's create listed harbor again")
    }

    /// n4: a narrow row keeps who may open it, as the note at its end cut short, never dropped.
    @Test func aNarrowRowKeepsWhoMayOpenIt() throws {
        let page = try #require(Self.page([WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.running), owner: "uday")], for: Self.ana))
        let (project, window) = try Self.project(width: Self.narrowestCard)
        defer { window.orderOut(nil) }
        project.render(page)
        let notes = project.list.entries.compactMap { entry -> String? in
            guard case let .item(item) = entry else { return nil }
            return item.note
        }
        #expect(notes == ["Only uday or an admin opens it"], "\(notes)")
    }

    private struct Shadowed {
        let y: Double, blur: Double, alpha: Double
    }

    /// Someone else's row names its owner; the person's own rows leave that column empty, at its
    /// 104 pt, so the columns still line up.
    @Test func anOwnerIsShownOnlyOnSomeoneElsesRow() async throws {
        let (view, _, _) = try await Self.listed()
        view.layoutSubtreeIfNeeded()
        let lines = Self.lines(view.project.list)
        #expect(lines.map(\.title.stringValue) == ["dock", "pier", "quay"])
        for line in lines {
            let whose = try #require(line.whose)
            let words = Self.found(NSTextField.self, in: whose).map(\.stringValue)
            #expect(abs(Double(whose.frame.width) - WorkspaceLine.ownerColumn) < 0.5, "\(line.title.stringValue): the owner's column is \(whose.frame.width) pt")
            #expect(words == (line.title.stringValue == "quay" ? ["ana"] : []), "\(line.title.stringValue)'s owner cell says \(words)")
            #expect(line.ownerTip?.shows != true, "\(line.title.stringValue): a handle shown in full says itself again on hover")
        }
    }

    /// The rows as the frames draw them, at every width they are drawn: the narrowest card, the
    /// least width drawn wide, and the drive's (a 1024 pt window). With a short name and the longest
    /// the server allows (63), each row's line runs to its actions column, its pill sits where the
    /// column puts it (the same x on every row: after the name column wide, at the line's end
    /// narrow), a name is cut short before the pill, never under it, and nothing wraps; narrow, a row
    /// drops its owner's column and says whose it is in its place.
    @Test func theRowsKeepTheirColumnsAtEveryWidth() throws {
        let long = String(repeating: "w", count: 63), handle = String(repeating: "k", count: 63)
        let page = try #require(Self.page([
            WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.running), owner: "uday"),
            WorkspaceRow(name: long, instance: "harbor-" + long, state: .known(.running), owner: "uday"),
            WorkspaceRow(name: "x" + long.dropFirst(), instance: "harbor-x", state: .known(.stopped), owner: handle),
        ]))
        let drive = 1024.0 - Rail.width - 4 - LeftPanel.width.standard
        for (width, narrow) in [(Self.narrowestCard, true), (ProjectView.wide, false), (drive, false)] {
            let (project, window) = try Self.project(width: width)
            defer { window.orderOut(nil) }
            project.render(page)
            project.layoutSubtreeIfNeeded()
            #expect(project.narrow == narrow, "\(width) pt is drawn \(project.narrow ? "narrow" : "wide")")
            let lines = Self.lines(project.list)
            #expect(lines.count == 3, "\(width) pt: \(lines.count) rows drawn")
            var pills: [NSRect] = []
            for line in lines {
                let cell = try #require(line.superview)
                let pill = cell.convert(line.pill.alignmentRect(forFrame: line.pill.frame), from: line.pill.superview)
                let reach = Double(cell.bounds.width) - RowStyle.list.trailing - (narrow ? project.narrowActionsColumn : ProjectView.actionsColumn)
                #expect(near(line.frame.maxX, reach), "\(width) pt: \(line.title.stringValue.prefix(12))'s line ends at \(line.frame.maxX), not at its actions column \(reach)")
                for label in [line.title, line.place] {
                    let words = cell.convert(label.alignmentRect(forFrame: label.frame), from: label.superview)
                    #expect(words.maxX <= pill.minX + 0.5 && words.width > 0, "\(width) pt: \(label.stringValue.prefix(12))… ends at \(words.maxX), the pill starts at \(pill.minX)")
                }
                #expect(line.place.frame.height <= line.title.frame.height + 0.5, "\(width) pt: \(line.place.stringValue.prefix(12)) wraps")
                #expect(pill.maxX <= line.frame.maxX + 0.5, "\(width) pt: the pill runs past its line")
                #expect((line.whose == nil) == narrow)
                if let whose = line.whose, let label = Self.found(NSTextField.self, in: whose).first {
                    let end = whose.convert(label.alignmentRect(forFrame: label.frame), from: label.superview).maxX
                    #expect(end <= whose.bounds.maxX + 0.5 && label.frame.height <= line.title.frame.height + 0.5, "\(width) pt: the owner runs to \(end) in its \(whose.bounds.width) pt column")
                }
                pills.append(pill)
            }
            let edges = pills.map { narrow ? $0.maxX : $0.minX }
            #expect(edges.allSatisfy { near($0, edges[0]) }, "\(width) pt: the pills' \(narrow ? "ends" : "starts") are \(edges)")
            if narrow { #expect(lines.map(\.place.stringValue) == ["harbor-dock", "harbor-" + long, handle + "'s"]) }
        }
    }

    /// Narrow, a row still runs to its end: its pill sits there whatever the name's length, as the
    /// narrow frame draws it, not just after the name.
    @Test func narrowRowsPutThePillAtTheirEnd() throws {
        let page = try #require(Self.page([
            WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.running), owner: "uday"),
            WorkspaceRow(name: "lighthouse-keeper", instance: "harbor-lighthouse-keeper", state: .known(.running), owner: "uday"),
        ]))
        let (project, window) = try Self.project(width: Self.narrowestCard)
        defer { window.orderOut(nil) }
        project.render(page)
        project.layoutSubtreeIfNeeded()
        let ends = try Self.lines(project.list).map { line in
            let cell = try #require(line.superview)
            return Double(cell.convert(line.pill.alignmentRect(forFrame: line.pill.frame), from: line.pill.superview).maxX)
        }
        #expect(ends.count == 2 && abs(ends[0] - ends[1]) < 0.5, "the pills end at \(ends)")
        for line in Self.lines(project.list) {
            #expect(line.place.frame.height <= line.title.frame.height, "\(line.place.stringValue) wraps to \(line.place.frame.height) pt")
        }
    }

    /// The line under the name takes all the room the buttons leave, less the design's 20 pt, as the
    /// approved head (a flex row) gives it: one line at the app's designed width (a 1180 pt window),
    /// wrapped only where its words are wider than that room, never cut short; gone at the narrowest
    /// card.
    @Test func theLedeIsOneLineWhereItFits() throws {
        let page = try #require(Self.page([WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.running), owner: "uday")]))
        let font = NSFont.systemFont(ofSize: Look.Step.callout.points), line = Double(NSLayoutManager().defaultLineHeight(for: font))
        let designed = 1180.0 - Rail.width - 4 - LeftPanel.width.standard
        for width in [designed, 1024.0 - Rail.width - 4 - LeftPanel.width.standard, ProjectView.wide] {
            let (project, window) = try Self.project(width: width)
            defer { window.orderOut(nil) }
            project.render(page)
            project.layoutSubtreeIfNeeded()
            project.layoutSubtreeIfNeeded()
            let words = project.words.frame, buttons = project.buttons.frame, lede = project.convert(project.lede.frame, from: project.lede.superview)
            let head = try #require(project.words.superview)
            #expect(abs(Double(buttons.minX - words.maxX) - ProjectView.headGap) < 0.5, "\(width) pt: the words end \(buttons.minX - words.maxX) pt before the buttons")
            #expect(abs(Double(words.minX)) < 0.5 && abs(Double(buttons.maxX - head.bounds.width)) < 0.5, "the head runs \(words.minX) to \(buttons.maxX) of \(head.bounds.width)")
            let natural = Double(project.lede.attributedStringValue.size().width), lines = natural > Double(words.width) ? 2.0 : 1.0
            #expect(width != designed || lines == 1, "at the designed width the line (\(natural) pt) is wider than its \(words.width) pt")
            #expect(abs(Double(lede.height) - lines * line) < line / 2, "\(width) pt: the line (\(natural) pt in \(words.width) pt) is \(lede.height) pt high, not \(lines) line(s)")
        }
        let (project, window) = try Self.project(width: Self.narrowestCard)
        defer { window.orderOut(nil) }
        project.render(page)
        project.layoutSubtreeIfNeeded()
        #expect(project.lede.isHidden && project.newWorkspace.isHidden && !project.newWorkspaceIcon.isHidden)
    }

    /// A refused name keeps its box at 250 pt with the refusal under it across the column, and
    /// "Show dock or choose another name." on a line under that; Cancel and Create stay level with
    /// the box at the row's end, clear of both lines. The open row is the design's 89 pt with its
    /// field 12 pt under the hairline. The field holds the keys when it opens, when the refusal
    /// comes back, after a redraw (the app made active again), and as typing takes the refusal away.
    @Test func aRefusalSaysWhatToDoUnderTheField() async throws {
        let server = RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("refusal-conflict-workspace-exists")])
        let (view, _) = try Self.view(server, width: 1000)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") })
        view.project.on(.newWorkspace)
        let typing = try #require(view.window?.firstResponder as? NSTextView, "New Workspace left the field without the keys")
        #expect(typing.delegate === view.project.field.field)
        typing.insertText("dock", replacementRange: NSRange(location: NSNotFound, length: 0))
        #expect(view.project.field.field.stringValue == "dock")
        view.project.list.layoutSubtreeIfNeeded()
        let open = try #require(view.project.list.rowView(atRow: 1, makeIfNecessary: true))
        open.layoutSubtreeIfNeeded()
        let openRow = try #require(Self.found(CreateRow.self, in: open).first)
        let box = open.convert(openRow.field.alignmentRect(forFrame: openRow.field.frame), from: openRow)
        #expect(near(open.frame.height, 89) && near(box.minY, 1 + CreateRow.padding), "the open row is \(open.frame.height) pt, its field at \(box.minY)")
        view.project.on(.create)
        #expect(await RunnerTabTests.until { view.text.contains("exists already") }, "\(view.text)")
        #expect(view.project.fieldHasKeys, "the refusal came back to a field without the keys")
        await view.refresh()
        #expect(view.project.fieldHasKeys, "the projects asked again (the app made active) took the keys from the field")
        view.layoutSubtreeIfNeeded()
        view.project.list.layoutSubtreeIfNeeded()
        let row = try #require(view.project.list.rowView(atRow: 1, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        let create = try #require(Self.found(CreateRow.self, in: row).first)
        let showLine = try #require(create.showLine)
        let words = Self.found(NSTextField.self, in: showLine).map(\.stringValue) + Self.found(NSButton.self, in: showLine).map(\.title)
        #expect(Set(words) == ["or choose another name.", "Show dock"], "\(words)")
        let field = create.field.alignmentRect(forFrame: create.field.frame), line = showLine.frame
        let buttons = create.buttons.alignmentRect(forFrame: create.buttons.frame)
        let fieldBox = create.convert(create.field.box, from: create.field)
        #expect(fieldBox.width <= CreateRow.box + 0.5 && near(field.width, min(CreateRow.column, create.bounds.width)), "the box is \(fieldBox.width) pt, the field's lines \(field.width) pt in a \(create.bounds.width) pt row")
        #expect(line.minY >= field.maxY + CreateRow.below - 0.5 && abs(Double(line.minX - field.minX)) < 0.5, "the show line is at \(line), the field \(field)")
        #expect(buttons.minX >= fieldBox.maxX + CreateRow.gap - 0.5, "Cancel starts at \(buttons.minX), the box ends at \(fieldBox.maxX)")
        #expect(buttons.maxY <= line.minY + 0.5 || buttons.minX >= line.maxX + CreateRow.gap - 0.5, "the show line runs under Cancel and Create: \(line), the buttons \(buttons)")
        #expect(buttons.maxY <= field.minY + create.field.size.height + 0.5, "the buttons hang below the box")
        #expect(Double(row.frame.height) >= 1 + Double(line.maxY) - 0.5, "the row (\(row.frame.height) pt) is shorter than its words")
        let show = try #require(Self.found(NSButton.self, in: showLine).first)
        let middle = create.superview?.convert(NSPoint(x: show.bounds.midX, y: show.bounds.midY), from: show) ?? .zero
        #expect(create.hitTest(middle) === show, "a click on Show dock's middle reaches \(String(describing: create.hitTest(middle))), not the button")
        view.project.on(.show("dock"))
        #expect(view.project.list.chosen == "dock")
        let again = try #require(view.window?.firstResponder as? NSTextView, "Show dock took the keys from the field")
        again.insertText("s", replacementRange: NSRange(location: NSNotFound, length: 0))
        #expect(view.project.field.field.stringValue == "docks")
        #expect(!view.text.contains("exists already"), "the refusal stayed once the name changed")
        let editor = view.window?.firstResponder as? NSTextView
        #expect(editor?.delegate === view.project.field.field && editor?.selectedRange() == NSRange(location: 5, length: 0), "the name field lost the keys as its refusal went")
    }

    /// A create under way is the row it will be, as the approved frame draws it: its name over its
    /// instance, the Creating pill, "about 3 s" and a spinner shown at its end, a 2 pt line along its
    /// foot; no field. New Workspace says why it waits; the created row then takes its place.
    @Test func aCreateUnderWayIsTheRowItWillBe() async throws {
        let exchanges = [try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-create"), try Exchange("workspaces-made")]
        let transport = Held(RecordedServer(exchanges), holding: "workspaces", after: 1)
        let (view, _) = try Self.view(transport)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        view.project.on(.newWorkspace)
        view.project.field.field.stringValue = "new"
        view.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification))
        view.project.on(.create)
        #expect(await RunnerTabTests.until { transport.routes.filter { $0.contains("workspaces") }.count == 2 }, "the create was never asked")
        #expect(view.text.contains("new · creating"), "\(view.text)")
        view.layoutSubtreeIfNeeded()
        let row = try #require(view.project.list.rowView(atRow: 1, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        let line = try #require(Self.found(WorkspaceLine.self, in: row).first)
        #expect(line.title.stringValue == "new" && line.place.stringValue == "harbor-new" && line.pill.state == .creating)
        #expect(Self.found(SailTextField.self, in: row).isEmpty, "the field is still drawn while the create is under way")
        let spinner = try #require(Self.found(Spinner.self, in: row).first)
        let note = try #require(Self.found(NSTextField.self, in: row).first { $0.stringValue == "about 3 s" })
        #expect(!spinner.isHiddenOrHasHiddenAncestor && !note.isHiddenOrHasHiddenAncestor, "the row's end is hidden")
        let pill = row.convert(line.pill.bounds, from: line.pill), end = row.convert(note.bounds, from: note)
        #expect(end.minX > pill.maxX && row.convert(spinner.bounds, from: spinner).minX > end.maxX, "about 3 s and the spinner are not after the pill")
        let foot = try #require(row.subviews.first { String(describing: type(of: $0)) == "ProgressLine" }, "no line along the row's foot")
        #expect(near(foot.frame.height, 2) && near(foot.frame.maxY, row.bounds.height) && near(foot.frame.width, row.bounds.width), "the line is at \(foot.frame)")
        #expect(!view.project.newWorkspace.isEnabled && view.project.newWorkspace.why == "The runner creates one workspace at a time")
        transport.release()
        #expect(await RunnerTabTests.until { !view.text.contains("creating") }, "\(view.text)")
        #expect(view.project.newWorkspace.isEnabled && view.project.newWorkspace.why == nil)
        #expect(await RunnerTabTests.until { view.window?.firstResponder === view.project.list }, "after the create the list does not hold the keys, so ⏎ opens nothing")
    }

    /// Check Again on a project with no template asks for the projects again, where its template's
    /// state is said, not only for its workspaces.
    @Test func checkAgainAsksForTheProjects() async throws {
        let server = RecordedServer([try Exchange("projects"), try Exchange("workspaces-three")])
        let (view, _) = try Self.view(server)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") })
        let before = await server.requests.filter { $0.route == "GET /v1/projects" }.count
        view.project.on(.checkAgain)
        await view.refresh()
        let after = await server.requests.filter { $0.route == "GET /v1/projects" }.count
        #expect(after >= before + 2, "Check Again asked the projects \(after - before) times, counting the refresh's own")
    }

    /// The longest handle the server allows (63) in the owner column, and in the panel's foot with
    /// its role unconfirmed beside a 60-character control plane: each cut short inside its column,
    /// on one line, in full on hover.
    @Test func longHandlesAndHostsStayInTheirColumns() throws {
        let handle = String(repeating: "k", count: 63), host = String(repeating: "h", count: 52) + ".acme.dev"
        let page = try #require(Self.page([WorkspaceRow(name: "quay", instance: "harbor-quay", state: .known(.running), owner: handle)]))
        let drive = 1024.0 - Rail.width - 4 - LeftPanel.width.standard
        for width in [ProjectView.wide, drive] {
            let (project, window) = try Self.project(width: width)
            defer { window.orderOut(nil) }
            project.render(page)
            project.layoutSubtreeIfNeeded()
            let line = try #require(Self.lines(project.list).first)
            let whose = try #require(line.whose)
            let label = try #require(Self.found(NSTextField.self, in: whose).first)
            let end = whose.convert(label.alignmentRect(forFrame: label.frame), from: label.superview).maxX
            #expect(end <= whose.bounds.maxX + 0.5 && label.frame.height < 2 * 16, "\(width) pt: the handle ends at \(end) in a \(whose.bounds.width) pt column")
            #expect(line.ownerTip?.text == handle && line.ownerTip?.shows == true, "\(width) pt: the cut handle is not said in full on hover")
        }
        let panel = LeftPanel(look: .light, mono: try LabelFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: LeftPanel.width.standard, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        window.contentView = panel
        panel.renderLoading(who: "\(handle) · role not yet confirmed", handle: handle, controlPlane: host)
        panel.layoutSubtreeIfNeeded()
        for label in [panel.who, panel.place] {
            let frame = panel.convert(label.alignmentRect(forFrame: label.frame), from: label.superview)
            #expect(frame.maxX <= panel.bounds.width - 14 + 0.5 && frame.height < 2 * 16, "the foot's \(label.stringValue.prefix(12))… runs to \(frame.maxX) of \(panel.bounds.width)")
        }
        #expect(panel.whoTip.shows && panel.placeTip.shows, "a cut foot is not said in full on hover")
        panel.renderLoading(who: "ana · member", handle: "ana", controlPlane: "main.acme.dev")
        panel.layoutSubtreeIfNeeded()
        #expect(!panel.whoTip.shows && !panel.placeTip.shows, "a foot shown in full says itself again on hover")
    }

    /// New Workspace off in a project with no template says why, through the library's tooltip.
    @Test func newWorkspaceSaysWhyItIsOff() throws {
        let page = try #require(Self.page([], template: .missing))
        let (project, window) = try Self.project(width: 900)
        defer { window.orderOut(nil) }
        project.render(page)
        #expect(!project.newWorkspace.isEnabled && project.newWorkspace.why == "harbor has no template yet")
        #expect(project.newWorkspace.tooltip?.text == "harbor has no template yet" && project.newWorkspace.tooltip?.isOn == true)
    }

    /// The card goes down to the narrowest the window allows (416 pt) whatever the section shows: no
    /// width it would like (its card's 440 pt, a centred empty state's) holds the window wider.
    @Test func theNarrowestCardIsReachedWhateverIsShown() async throws {
        let (view, window, _) = try await Self.listed()
        var none = WorkspacesModel(person: Self.uday)
        none.projectsListed(.success(ProjectList(projects: [], total: 0)))
        guard case let .shown(_, .noProjects(empty)) = none.screen else { Issue.record("no empty state: \(none.screen)"); return }
        let shown: [(String, () -> Void)] = [
            ("a list", {}), ("no project", { view.project.renderEmpty(empty) }), ("loading", { view.project.renderLoading() }),
            ("the card", { view.signedOut() }),
        ]
        for (what, show) in shown {
            show()
            window.setContentSize(NSSize(width: 1000, height: 640))
            view.layoutSubtreeIfNeeded()
            window.setContentSize(NSSize(width: Self.narrowestCard, height: 640))
            view.layoutSubtreeIfNeeded()
            #expect(abs(Double(view.frame.width) - Self.narrowestCard) < 0.5, "with \(what) shown the card stays \(view.frame.width) pt")
        }
        view.panel.resize(to: LeftPanel.width.most)
        view.project.renderLoading()
        window.setContentSize(NSSize(width: 740, height: 640))
        view.layoutSubtreeIfNeeded()
        #expect(near(view.frame.width, 740), "the loading bars held the card at \(view.frame.width) pt beside the widest panel")
    }

    /// The light look's colours that the dark look does not share: any of them left on a view after
    /// the change is a view that kept the light look.
    static let lightOnly: [NSColor] = {
        let dark = Dictionary(uniqueKeysWithValues: Mirror(reflecting: Look.dark).children.compactMap { child in child.label.map { ($0, child.value) } })
        return Mirror(reflecting: Look.light).children.compactMap { child -> NSColor? in
            guard let label = child.label, let light = Self.tint(child.value), let other = dark[label].flatMap({ Self.tint($0) }), light != other else { return nil }
            return NSColor(light)
        }
    }()

    /// A colour token's tint: a tint, or an sRGB colour (the type a tint is made of) at full opacity.
    nonisolated static func tint(_ value: Any) -> Tint? {
        func cast<Wanted>(_ value: Any, like _: Wanted) -> Wanted? {
            value as? Wanted
        }
        return (value as? Tint) ?? cast(value, like: Tint(0).rgb).map { Tint($0) }
    }

    /// A hidden section paints nothing however many looks are switched under it (`PaintWhenShown`);
    /// shown, it paints once, in the look now, everywhere.
    @Test func aLookSwitchedWhileHiddenIsTakenOnShowing() async throws {
        let (view, _, _) = try await Self.listed()
        let paints = view.lookPaints
        view.isHidden = true
        for look in [Look.dark, .light, .dark] {
            view.look = look
        }
        #expect(view.lookPaints == paints && view.project.look == .light, "a hidden section painted \(view.lookPaints - paints) times for looks nobody saw")
        view.isHidden = false
        #expect(view.lookPaints == paints + 1, "shown, the section painted \(view.lookPaints - paints) times, not once")
        #expect(view.project.look == .dark && view.panel.look == .dark && Self.keptLight(under: view).isEmpty)
    }

    /// Everything `view` shows that still carries the light look: under it, and in its window's
    /// attached sheet and child windows (a tooltip up), each of which floats over it.
    static func keptLightAnywhere(_ view: NSView) -> [String] {
        let floating = ([view.window?.attachedSheet].compactMap { $0 } + (view.window?.childWindows ?? [])).compactMap(\.contentView)
        return keptLight(under: view) + floating.flatMap { keptLight(under: $0).map { "a floating window: \($0)" } }
    }

    /// Every view shown under `root` that still carries the light look: a stored look that is not
    /// the dark one (anywhere in its class chain), words in a light-only colour, or a light ground.
    static func keptLight(under root: NSView) -> [String] {
        var kept: [String] = []
        func walk(_ view: NSView) {
            guard !view.isHidden else { return }
            var mirror: Mirror? = Mirror(reflecting: view)
            while let current = mirror {
                for child in current.children where (child.value as? Look).map({ $0 != .dark }) == true {
                    kept.append("\(type(of: view)).\(child.label ?? "look")")
                }
                mirror = current.superclassMirror
            }
            if let label = view as? NSTextField {
                let words = label.attributedStringValue
                var colours = [label.textColor].compactMap { $0 }
                words.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: words.length)) { value, _, _ in
                    if let colour = value as? NSColor { colours.append(colour) }
                }
                if !words.string.isEmpty, colours.contains(where: { colour in Self.lightOnly.contains(colour) }) {
                    kept.append("the words \"\(words.string.prefix(24))\"")
                }
            }
            if let ground = view.layer?.backgroundColor, Self.lightOnly.contains(where: { $0.cgColor == ground }) {
                kept.append("\(type(of: view))'s ground")
            }
            for subview in view.subviews {
                walk(subview)
            }
        }
        walk(root)
        return kept
    }

    /// The pages a project's view can draw, each from the model: listed, a row chosen, the create row
    /// open, refused, under way, a stale list under its banner, no workspaces, no template, loading,
    /// no project, and narrow.
    static func scenes(_ project: ProjectView) -> [(String, () -> Void)] {
        let rows = [
            WorkspaceRow(name: "dock", instance: "harbor-dock", state: .known(.running), owner: "uday"),
            WorkspaceRow(name: "pier", instance: "harbor-pier", state: .known(.stopped), owner: "uday"),
            WorkspaceRow(name: "quay", instance: "harbor-quay", state: .known(.running), owner: "ana"),
        ]
        func model(_ steps: (inout WorkspacesModel) -> Void = { _ in }) -> WorkspacesModel.Page? {
            var model = WorkspacesModel(person: Self.uday)
            let harbor = ProjectSummary(name: "harbor", containerStatus: .known(.running), template: .known(.ready))
            let request = model.projectsListed(.success(ProjectList(projects: [harbor], total: 1))) ?? 0
            model.listed(.success(WorkspaceList(project: "harbor", workspaces: rows)), request: request, at: Date())
            steps(&model)
            guard case let .shown(_, .project(page)) = model.screen else { return nil }
            return page
        }
        let pages: [(String, WorkspacesModel.Page?)] = [
            ("listed", model { $0.chooseRow("dock") }),
            ("the create row", model { $0.beginCreate() }),
            ("refused", model { model in
                model.beginCreate()
                model.type("dock")
                _ = model.sendCreate()
                _ = model.created(.failure(.unreachable("lost")), project: "harbor")
            }),
            ("under way", model { model in
                model.beginCreate()
                model.type("new")
                _ = model.sendCreate()
            }),
            ("stale", model { model in
                let again = model.listAgain()
                model.listed(.failure(.unreachable("lost")), request: again, at: Date())
            }),
            ("no workspaces", Self.page([])),
            ("no template", Self.page([], template: .missing)),
        ]
        var none = WorkspacesModel(person: Self.uday)
        none.projectsListed(.success(ProjectList(projects: [], total: 0)))
        var scenes: [(String, () -> Void)] = pages.map { name, page in (name, { if let page { project.render(page) } }) }
        scenes.append(("loading", { project.renderLoading() }))
        if case let .shown(_, .noProjects(empty)) = none.screen { scenes.append(("no project", { project.renderEmpty(empty) })) }
        return scenes
    }

    /// A change of look reaches every view the project's view can show, found by walking the view
    /// tree in each of its pages, wide and narrow: drawn in the light look, then changed to the dark.
    @Test func aChangeOfLookReachesEveryViewTheProjectShows() throws {
        for width in [900.0, Self.narrowestCard] {
            let (project, window) = try Self.project(width: width)
            defer { window.orderOut(nil) }
            for (name, show) in Self.scenes(project) {
                project.look = .light
                show()
                project.layoutSubtreeIfNeeded()
                project.look = .dark
                project.layoutSubtreeIfNeeded()
                project.list.layoutSubtreeIfNeeded()
                for row in 0 ..< project.list.numberOfRows {
                    project.list.rowView(atRow: row, makeIfNecessary: true)?.layoutSubtreeIfNeeded()
                }
                let kept = Self.keptLight(under: project)
                #expect(kept.isEmpty, "\(width) pt, \(name): kept the light look: \(kept)")
            }
        }
    }

    /// The section's own states keep no light either: the panel and a list, loading, and the card
    /// signed out and out of reach.
    @Test func aChangeOfLookReachesTheWholeSection() async throws {
        let (listed, _, _) = try await Self.listed()
        let (loading, _) = try Self.view(Held(RecordedServer([try Exchange("projects")]), holding: "projects"))
        loading.shown()
        let (unreached, _) = try Self.view(RecordedServer([]))
        unreached.shown()
        #expect(await RunnerTabTests.until { unreached.text.contains("reach") }, "\(unreached.text)")
        let (signedOut, _) = try Self.view(RecordedServer([]))
        signedOut.signedOut()
        let (asking, askingWindow) = try Self.view(RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-sessions")]))
        asking.shown()
        #expect(await RunnerTabTests.until { asking.text.contains("dock") }, "\(asking.text)")
        asking.project.on(.delete("dock"))
        #expect(await RunnerTabTests.until { askingWindow.attachedSheet != nil }, "Delete asked nothing")
        for (name, view) in [("listed", listed), ("loading", loading), ("out of reach", unreached), ("signed out", signedOut), ("a sheet asking", asking)] {
            view.look = .dark
            view.layoutSubtreeIfNeeded()
            let kept = Self.keptLightAnywhere(view)
            #expect(kept.isEmpty, "\(name): kept the light look: \(kept)")
        }
    }

    /// A failed refresh draws the panel's names in the secondary ink, as the stale frame does, and
    /// leaves its tiles and its chosen row's bar as they are.
    @Test func aStalePanelSaysItsNamesInTheSecondaryInk() throws {
        var model = WorkspacesModel(person: Self.uday)
        model.projectsListed(.success(ProjectList(projects: [ProjectSummary(name: "harbor", containerStatus: .known(.running), template: .known(.ready))], total: 1)))
        let asked = model.askingProjects()
        model.projectsListed(.failure(.unreachable("lost")), request: asked)
        guard case let .shown(stale, _) = model.screen else { Issue.record("\(model.screen)"); return }
        let panel = LeftPanel(look: .light, mono: try LabelFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: LeftPanel.width.standard, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        window.contentView = panel
        panel.render(stale)
        panel.layoutSubtreeIfNeeded()
        let row = try #require(panel.list.rowView(atRow: 1, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        let cell = try #require(Self.found(SidebarRowCell.self, in: row).first)
        let name = try #require(Self.found(NSTextField.self, in: cell).first)
        #expect(name.attributedStringValue.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor == NSColor(Look.light.textSecondary))
        #expect(panel.list.alphaValue == 1, "the panel is dimmed whole, its tiles and bar too")
    }

    /// The panel's edge drags it between 200 and 360 pt.
    @Test func thePanelIsResizedWithinItsBounds() throws {
        let panel = LeftPanel(look: .light, mono: try LabelFont(directory: LookTests.fonts))
        panel.resize(to: 500)
        #expect(panel.widthConstraint.constant == 360)
        panel.resize(to: 120)
        #expect(panel.widthConstraint.constant == 200)
        panel.resize(to: 260)
        #expect(panel.widthConstraint.constant == 260)
    }
}

/// Measured geometry agrees within half a point: never compared exactly, as layout and conversions
/// round.
func near(_ a: CGFloat, _ b: CGFloat) -> Bool {
    abs(a - b) < 0.5
}

func near(_ a: NSPoint, _ b: NSPoint) -> Bool {
    near(a.x, b.x) && near(a.y, b.y)
}

func near(_ a: NSRect, _ b: NSRect) -> Bool {
    near(a.origin, b.origin) && near(a.width, b.width) && near(a.height, b.height)
}

/// A window that says it is key, as the person's front window is; a test process's own windows
/// cannot be made key.
final class KeyWindow: NSWindow {
    override var isKeyWindow: Bool {
        true
    }
}
