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

    static func view(_ transport: any Transport, width: Double = 1000) throws -> (WorkspacesView, NSWindow) {
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        view.person = Self.uday
        view.client = SailClient(transport: transport, token: "tok")
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = view
        window.orderFront(nil)
        return (view, window)
    }

    static func listed(_ golden: String = "workspaces-three") async throws -> (WorkspacesView, NSWindow, RecordedServer) {
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

    /// The page `rows` make of harbor, listed for uday.
    static func page(_ rows: [WorkspaceRow], template: TemplateState = .ready) -> WorkspacesModel.Page? {
        var model = WorkspacesModel(person: Self.uday)
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

    /// Under a 720 pt card the panel hides and the content takes the width; ⌃⌘S slides it over.
    @Test func aNarrowCardHidesThePanelAndShowSidebarSlidesItOver() async throws {
        let (view, window, _) = try await Self.listed()
        #expect(!view.panel.isHidden)
        window.setContentSize(NSSize(width: 700, height: 640))
        view.layoutSubtreeIfNeeded()
        #expect(view.panel.isHidden && view.project.frame.minX == 0)
        view.toggleSidebar(nil)
        view.layoutSubtreeIfNeeded()
        #expect(!view.panel.isHidden && view.project.frame.minX == 0, "the panel slides over the content")
        view.toggleSidebar(nil)
        window.setContentSize(NSSize(width: 1000, height: 640))
        view.layoutSubtreeIfNeeded()
        #expect(!view.panel.isHidden && view.project.frame.minX == view.panel.frame.maxX)
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
        }
    }

    /// The name's column takes what the pill and the actions leave; a name as long as the server
    /// allows (63) is cut short before the pill, never under it, at the least width drawn wide and at
    /// the narrowest card, where a row drops its owner's column and says whose it is in its place.
    @Test func theLongestNameStopsBeforeThePill() throws {
        let name = String(repeating: "w", count: 63)
        let page = try #require(Self.page([
            WorkspaceRow(name: name, instance: "harbor-" + name, state: .known(.running), owner: "uday"),
            WorkspaceRow(name: "x" + name.dropFirst(), instance: "harbor-x", state: .known(.stopped), owner: "ana"),
        ]))
        for (width, narrow) in [(ProjectView.wide, false), (Self.narrowestCard, true)] {
            let (project, window) = try Self.project(width: width)
            defer { window.orderOut(nil) }
            project.render(page)
            project.layoutSubtreeIfNeeded()
            #expect(project.narrow == narrow, "\(width) pt is drawn \(project.narrow ? "narrow" : "wide")")
            let lines = Self.lines(project.list)
            #expect(lines.count == 2, "\(width) pt: \(lines.count) rows drawn")
            for line in lines {
                let pill = line.convert(line.pill.alignmentRect(forFrame: line.pill.frame), from: line.pill.superview)
                let row = try #require(line.superview).convert(line.frame, to: nil), cell = try #require(line.superview).convert(try #require(line.superview).bounds, to: nil)
                for label in [line.title, line.place] {
                    let words = line.convert(label.alignmentRect(forFrame: label.frame), from: label.superview)
                    #expect(words.maxX <= pill.minX + 0.5 && words.width > 0, "\(width) pt: \(label.stringValue.prefix(12))… ends at \(words.maxX), the pill starts at \(pill.minX)")
                }
                #expect(line.convert(pill, to: nil).maxX <= row.maxX + 0.5 && row.maxX <= cell.maxX + 0.5, "\(width) pt: the pill (to \(line.convert(pill, to: nil).maxX)) runs past its line (to \(row.maxX)) or the line past its cell (to \(cell.maxX))")
                #expect((line.whose == nil) == narrow)
            }
            if narrow { #expect(lines.map(\.place.stringValue) == ["harbor-" + name, "ana's"]) }
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
    /// the box at the row's end, clear of both lines. Typing on takes the refusal away and keeps the
    /// keys in the field.
    @Test func aRefusalSaysWhatToDoUnderTheField() async throws {
        let server = RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("refusal-conflict-workspace-exists")])
        let (view, _) = try Self.view(server, width: 1000)
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") })
        view.project.on(.newWorkspace)
        view.project.field.field.stringValue = "dock"
        view.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification))
        view.project.on(.create)
        #expect(await RunnerTabTests.until { view.text.contains("exists already") }, "\(view.text)")
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
        #expect(field.width > CreateRow.box && field.width <= CreateRow.column, "the field's column is \(field.width) pt")
        #expect(line.minY >= field.maxY + CreateRow.below - 0.5 && abs(Double(line.minX - field.minX)) < 0.5, "the show line is at \(line), the field \(field)")
        #expect(buttons.minX >= max(field.maxX, line.maxX) + CreateRow.gap - 0.5, "Cancel starts at \(buttons.minX), the words end at \(max(field.maxX, line.maxX))")
        #expect(buttons.maxY <= field.minY + create.field.size.height + 0.5, "the buttons hang below the box")
        #expect(Double(row.frame.height) >= Double(line.maxY) + 2 * CreateRow.padding - 0.5, "the row (\(row.frame.height) pt) is shorter than its words")
        view.project.on(.show("dock"))
        #expect(view.project.list.chosen == "dock")
        view.project.field.field.stringValue = "docks"
        view.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification))
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
        #expect(foot.frame.height == 2 && abs(Double(foot.frame.maxY - row.bounds.height)) < 0.5 && foot.frame.width == row.bounds.width, "the line is at \(foot.frame)")
        #expect(!view.project.newWorkspace.isEnabled && view.project.newWorkspace.why == "The runner creates one workspace at a time")
        transport.release()
        #expect(await RunnerTabTests.until { !view.text.contains("creating") }, "\(view.text)")
        #expect(view.project.newWorkspace.isEnabled && view.project.newWorkspace.why == nil)
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
            ("a list", {}), ("no project", { view.project.renderEmpty(empty) }),
            ("the card", { view.signedOut() }),
        ]
        for (what, show) in shown {
            show()
            window.setContentSize(NSSize(width: 1000, height: 640))
            view.layoutSubtreeIfNeeded()
            window.setContentSize(NSSize(width: Self.narrowestCard, height: 640))
            view.layoutSubtreeIfNeeded()
            #expect(view.frame.width == Self.narrowestCard, "with \(what) shown the card stays \(view.frame.width) pt")
        }
    }

    /// The light look's colours that the dark look does not share: any of them left on a view after
    /// the change is a view that kept the light look.
    static let lightOnly: [NSColor] = {
        let dark = Dictionary(uniqueKeysWithValues: Mirror(reflecting: Look.dark).children.compactMap { child in child.label.map { ($0, child.value) } })
        return Mirror(reflecting: Look.light).children.compactMap { child -> NSColor? in
            guard let label = child.label, let light = Self.tint(child.value), let other = dark[label].flatMap(Self.tint), light != other else { return nil }
            return NSColor(light)
        }
    }()

    static func tint(_ value: Any) -> Tint? {
        (value as? Tint) ?? (value as? RGB).map { Tint($0) }
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
            view.subviews.forEach(walk)
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
                _ = model.created(.failure(.unreachable("lost")))
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
                show()
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
        for (name, view) in [("listed", listed), ("loading", loading), ("out of reach", unreached), ("signed out", signedOut)] {
            view.look = .dark
            view.layoutSubtreeIfNeeded()
            let kept = Self.keptLight(under: view)
            #expect(kept.isEmpty, "\(name): kept the light look: \(kept)")
        }
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
