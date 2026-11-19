import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Testing

/// The list and the left panel scroll with the quiet scroller while their heads and feet stay put;
/// the panel's foot and loading head as the frames draw them; and the words that must fit, fit.
@MainActor @Suite struct WorkspacesScrollTests {
    static func rows(_ count: Int) throws -> Exchange {
        try WorkspacesReachTests.list((1 ... count).map { ["name": String(format: "ws-%02d", $0), "instance": String(format: "harbor-ws-%02d", $0), "state": "running", "owner": "uday"] })
    }

    /// Thirty rows: the list scrolls in its frame with the quiet scroller, the page's foot stays in
    /// the card at the drive's size and at the narrowest card, and the last row scrolls into view;
    /// three rows: the frame is no taller than its rows.
    @Test(arguments: [true, false])
    func theListScrollsInItsFrameAndTheFootStays(_ narrowest: Bool) async throws {
        let width = narrowest ? WorkspacesViewTests.narrowestCard : 1024 - Rail.width - 4
        let (view, window) = try WorkspacesViewTests.view(RecordedServer([try Exchange("projects"), try Self.rows(30)]), width: width)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("ws-30") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        let scroll = try #require(view.project.listScroll)
        #expect(scroll.verticalScroller is QuietScroller, "the list's scroller is \(String(describing: scroll.verticalScroller))")
        let document = Double(scroll.documentView?.frame.height ?? 0), shown = Double(scroll.contentSize.height)
        #expect(document > shown + 1, "thirty rows (\(document) pt) all fit in \(shown) pt: nothing scrolls")
        let foot = view.convert(view.project.foot.bounds, from: view.project.foot)
        #expect(foot.maxY <= view.bounds.maxY - ProjectView.padding.bottom + 0.5 && foot.height > 0, "the foot runs to \(foot.maxY) of \(view.bounds.height)")
        let list = view.project.list
        list.scrollRowToVisible(list.numberOfRows - 1)
        let last = list.rect(ofRow: list.numberOfRows - 1)
        #expect(scroll.contentView.documentVisibleRect.insetBy(dx: -0.5, dy: -0.5).contains(last), "the last row at \(last) cannot be scrolled into \(scroll.contentView.documentVisibleRect)")
        let (few, fewWindow, _) = try await WorkspacesViewTests.listed()
        defer { fewWindow.orderOut(nil) }
        few.layoutSubtreeIfNeeded()
        let frame = try #require(few.project.listScroll?.superview)
        let rows = Double(few.project.list.rect(ofRow: few.project.list.numberOfRows - 1).maxY)
        #expect(abs(Double(frame.frame.height) - rows - 2) < 0.5, "three rows' frame is \(frame.frame.height) pt for \(rows) pt of rows")
    }

    /// A change of look redraws both lists where the person left them: the list part way down its
    /// thirty rows, and the panel part way down its twenty projects, neither jumping to its top or
    /// to the chosen row.
    @Test func aChangeOfLookKeepsBothScrolls() async throws {
        let names = (["harbor"] + (2 ... 20).map { "project-\($0)" }).map { #"{"name": "\#($0)", "container_status": "running", "template": "ready"}"# }
        let projects = Exchange(method: "GET", path: "/v1/projects", status: 200, body: Data(#"{"projects": [\#(names.joined(separator: ", "))], "total": 20, "schema_version": 1}"#.utf8))
        let (view, window) = try WorkspacesViewTests.view(RecordedServer([projects, try Self.rows(30)]), width: 1024 - Rail.width - 4)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("ws-30") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        let list = try #require(view.project.listScroll), panel = view.panel.scroll
        for scroll in [list, panel] {
            let room = Double(scroll.documentView?.frame.height ?? 0) - Double(scroll.contentSize.height)
            #expect(room > 40, "nothing to scroll: \(room) pt")
            scroll.contentView.scroll(to: NSPoint(x: 0, y: (room / 2).rounded()))
            scroll.reflectScrolledClipView(scroll.contentView)
        }
        let before = [list, panel].map { Double($0.contentView.bounds.minY) }
        view.look = .dark
        view.layoutSubtreeIfNeeded()
        let after = [list, panel].map { Double($0.contentView.bounds.minY) }
        #expect(zip(before, after).allSatisfy { abs($0 - $1) < 0.5 }, "a change of look moved the scroll from \(before) to \(after) (the list's, the panel's)")
    }

    /// The same rows drawn again (the app made active, a change of look) keep the chosen row and the
    /// list's keys, the scroll view between them notwithstanding.
    @Test func aRedrawKeepsTheChosenRowAndTheKeys() async throws {
        let (view, window, _) = try await WorkspacesViewTests.listed()
        defer { window.orderOut(nil) }
        view.project.list.choose("pier")
        #expect(window.makeFirstResponder(view.project.list))
        await view.refresh()
        #expect(view.project.list.chosen == "pier" && window.firstResponder === view.project.list, "a refresh lost the chosen row or the keys")
        view.look = .dark
        #expect(view.project.list.chosen == "pier" && window.firstResponder === view.project.list, "a change of look lost the chosen row or the keys")
    }

    static func rows(_ count: Int, of project: String) throws -> Exchange {
        let rows = (1 ... count).map { ["name": String(format: "ws-%02d", $0), "instance": String(format: "\(project)-ws-%02d", $0), "state": "running", "owner": "uday"] }
        let body = try JSONSerialization.data(withJSONObject: ["project": project, "schema_version": 1, "workspaces": rows] as [String: Any])
        return Exchange(method: "GET", path: "/v1/projects/\(project)/workspaces", status: 200, body: body)
    }

    /// M1: another project's list starts at its top, not where the last project's was left; and so
    /// does the list of someone signed in after, though their project has the same name.
    @Test func anotherProjectOrPersonStartsAtTheTop() async throws {
        let projects = Exchange(method: "GET", path: "/v1/projects", status: 200, body: Data(#"{"projects": [{"name": "harbor", "container_status": "running", "template": "ready"}, {"name": "demo", "container_status": "running", "template": "ready"}], "total": 2, "schema_version": 1}"#.utf8))
        let (view, window) = try WorkspacesViewTests.view(RecordedServer([projects, try Self.rows(30, of: "harbor"), try Self.rows(30, of: "demo")]), width: 1024 - Rail.width - 4)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.hasPrefix("Workspaces\nharbor\n") && view.text.contains("ws-30") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        let scroll = try #require(view.project.listScroll)
        func scrolledHalfWay() {
            scroll.contentView.scroll(to: NSPoint(x: 0, y: ((Double(scroll.documentView?.frame.height ?? 0) - Double(scroll.contentSize.height)) / 2).rounded()))
            scroll.reflectScrolledClipView(scroll.contentView)
        }
        scrolledHalfWay()
        view.panel.list.choose("demo")
        #expect(await RunnerTabTests.until { view.text.hasPrefix("Workspaces\ndemo\n") && view.text.contains("ws-30") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        #expect(abs(Double(scroll.contentView.bounds.minY)) < 0.5, "demo's list opened \(scroll.contentView.bounds.minY) pt down, where harbor's was left")
        view.panel.list.choose("harbor")
        #expect(await RunnerTabTests.until { view.text.hasPrefix("Workspaces\nharbor\n") && view.text.contains("ws-30") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        scrolledHalfWay()
        view.person = WorkspacesViewTests.ana
        view.shown()
        #expect(await RunnerTabTests.until { view.text.hasPrefix("Workspaces\nharbor\n") && view.text.contains("ws-30") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        #expect(abs(Double(scroll.contentView.bounds.minY)) < 0.5, "ana's harbor opened \(scroll.contentView.bounds.minY) pt down, where uday left a list")
    }

    /// Minor 1: the open create row is brought into view as it opens, not on every redraw: scrolled
    /// away from it, a refresh leaves the scroll where the person put it.
    @Test func anOpenCreateRowDoesNotPullTheListBack() async throws {
        let (view, window) = try WorkspacesViewTests.view(RecordedServer([try Exchange("projects"), try Self.rows(30)]), width: 1024 - Rail.width - 4)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("ws-30") }, "\(view.text)")
        view.project.on(.newWorkspace)
        view.layoutSubtreeIfNeeded()
        let scroll = try #require(view.project.listScroll)
        #expect(abs(Double(scroll.contentView.bounds.minY)) < 0.5, "the create row opened out of view")
        scroll.contentView.scroll(to: NSPoint(x: 0, y: 300))
        scroll.reflectScrolledClipView(scroll.contentView)
        await view.refresh()
        view.layoutSubtreeIfNeeded()
        #expect(abs(Double(scroll.contentView.bounds.minY) - 300) < 0.5, "a refresh pulled the list to \(scroll.contentView.bounds.minY) pt, back to the create row")
    }

    /// R2-1: a half-typed name survives the create row being scrolled out of the window and back
    /// (the list makes only the rows on screen): the field holds the whole name and the keys.
    @Test func aHalfTypedNameSurvivesScrollingAwayAndBack() async throws {
        let (view, window) = try WorkspacesViewTests.view(RecordedServer([try Exchange("projects"), try Self.rows(30)]), width: 1024 - Rail.width - 4)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("ws-30") }, "\(view.text)")
        view.project.on(.newWorkspace)
        let half = try #require(window.firstResponder as? NSTextView, "the field never took the keys")
        half.insertText("half-", replacementRange: NSRange(location: NSNotFound, length: 0))
        let scroll = try #require(view.project.listScroll)
        for offset in [Double(scroll.documentView?.frame.height ?? 0) - Double(scroll.contentSize.height), 0] {
            scroll.contentView.scroll(to: NSPoint(x: 0, y: offset))
            scroll.reflectScrolledClipView(scroll.contentView)
            view.layoutSubtreeIfNeeded()
            window.displayIfNeeded()
        }
        let rest = try #require(window.firstResponder as? NSTextView, "scrolled away and back, the field lost the keys to \(String(describing: window.firstResponder))")
        rest.insertText("name", replacementRange: NSRange(location: NSNotFound, length: 0))
        #expect(view.project.fieldHasKeys && view.project.field.field.stringValue == "half-name", "the field holds \(view.project.field.field.stringValue)")
    }

    /// Minor 7: a list that fits does not bounce; one that runs past its frame does.
    @Test func onlyAScrollingListBounces() async throws {
        let (few, fewWindow, _) = try await WorkspacesViewTests.listed()
        defer { fewWindow.orderOut(nil) }
        few.layoutSubtreeIfNeeded()
        #expect(few.project.listScroll?.verticalScrollElasticity == NSScrollView.Elasticity.none, "three rows that fit bounce in their frame")
        let (many, manyWindow) = try WorkspacesViewTests.view(RecordedServer([try Exchange("projects"), try Self.rows(30)]), width: 1024 - Rail.width - 4)
        defer { manyWindow.orderOut(nil) }
        many.shown()
        #expect(await RunnerTabTests.until { many.text.contains("ws-30") }, "\(many.text)")
        many.layoutSubtreeIfNeeded()
        #expect(many.project.listScroll?.verticalScrollElasticity == NSScrollView.Elasticity.automatic, "thirty rows do not bounce")
    }

    /// Minor 4: a redraw (a list's answer, a change of look; never per frame) builds only the rows on
    /// screen: about a screenful of rows whatever the list's length, and the words of no row it did
    /// not make, so a return to building every row fails by count. The times for three hundred rows
    /// and for thirty are said, not bounded: a shared runner measured the same path from 20 to 61 ms.
    @Test func aLongListRedrawsOnlyWhatIsOnScreen() throws {
        func best(_ count: Int) throws -> (time: Duration, made: Int, words: Int) {
            let rows = (1 ... count).map { WorkspaceRow(name: String(format: "ws-%03d", $0), instance: String(format: "harbor-ws-%03d", $0), state: .known(.running), owner: $0.isMultiple(of: 2) ? "ana" : "uday") }
            let page = try #require(WorkspacesViewTests.page(rows))
            let (project, window) = try WorkspacesViewTests.project(width: 1024 - Rail.width - 4 - LeftPanel.width.standard)
            defer { window.orderOut(nil) }
            project.render(page)
            project.layoutSubtreeIfNeeded()
            var times: [Duration] = []
            let made = project.list.rowsMade, words = WorkspaceLine.made
            for _ in 0 ..< 3 {
                let start = ContinuousClock.now
                project.render(page)
                project.layoutSubtreeIfNeeded()
                times.append(ContinuousClock.now - start)
            }
            return (times.min() ?? .zero, (project.list.rowsMade - made) / 3, (WorkspaceLine.made - words) / 3)
        }
        let many = try best(300), few = try best(30)
        print("workspaces: a redraw makes \(many.made) rows in \(many.time) for three hundred, \(few.made) in \(few.time) for thirty")
        #expect(many.made <= few.made + 2 && many.made < 30, "a redraw of three hundred rows makes \(many.made) of them, thirty make \(few.made): not only those on screen")
        #expect(many.words == many.made && few.words == few.made, "a redraw made the words of \(many.words) rows for \(many.made) rows made of three hundred, \(few.words) for \(few.made) of thirty")
    }

    /// The narrow rows' actions column is the widest actions a row shows, measured and said; the
    /// reach sweep, with the list holding the keys, checks a chosen row's actions fit it.
    @Test func theNarrowActionsColumnIsTheWidestActions() throws {
        let page = try #require(WorkspacesViewTests.page([WorkspaceRow(name: "pier", instance: "harbor-pier", state: .known(.stopped), owner: "uday")]))
        let (project, window) = try WorkspacesViewTests.project(width: WorkspacesViewTests.narrowestCard)
        defer { window.orderOut(nil) }
        project.render(page)
        #expect(window.makeFirstResponder(project.list))
        project.list.choose("pier")
        project.layoutSubtreeIfNeeded()
        let row = try #require(project.list.rowView(atRow: 1, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        let buttons = WorkspacesViewTests.found(SailButton.self, in: row).filter { !$0.isHiddenOrHasHiddenAncestor }
        let boxes = buttons.map { row.convert($0.alignmentRect(forFrame: $0.frame), from: $0.superview) }
        let shown = (boxes.map(\.maxX).max() ?? 0) - (boxes.map(\.minX).min() ?? 0)
        print("workspaces: the narrow actions column is \(project.narrowActionsColumn) pt; a chosen stopped row's actions take \(shown) pt")
        #expect(buttons.count == 2 && abs(Double(shown) - project.narrowActionsColumn) < 0.5, "the column is \(project.narrowActionsColumn) pt, the widest actions take \(shown) pt")
    }

    /// Twenty projects scroll between the panel's header and its foot, which stays put.
    @Test func twentyProjectsScrollAboveThePanelsFoot() throws {
        let panel = LeftPanel(look: .light, mono: try LabelFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: LeftPanel.width.standard, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        window.contentView = panel
        var model = WorkspacesModel(person: WorkspacesViewTests.ana)
        let projects = (1 ... 20).map { ProjectSummary(name: "project-\($0)", containerStatus: .known(.running), template: .known(.ready)) }
        _ = model.projectsListed(.success(ProjectList(projects: projects, total: 20)))
        _ = model.choose("project-20")
        guard case let .shown(shown, _) = model.screen else { Issue.record("no panel: \(model.screen)"); return }
        panel.render(shown)
        panel.layoutSubtreeIfNeeded()
        let document = Double(panel.scroll.documentView?.frame.height ?? 0), room = Double(panel.scroll.frame.height)
        #expect(document > room + 1 && panel.scroll.verticalScroller is QuietScroller, "twenty projects (\(document) pt) in \(room) pt")
        let foot = panel.convert(panel.who.bounds, from: panel.who)
        #expect(panel.scroll.frame.maxY <= foot.minY && foot.maxY <= panel.bounds.maxY, "the projects run to \(panel.scroll.frame.maxY), the foot is at \(foot)")
        let chosen = panel.list.rect(ofRow: panel.list.selectedRow)
        #expect(panel.scroll.contentView.documentVisibleRect.insetBy(dx: -0.5, dy: -0.5).contains(chosen), "the chosen project is not scrolled into view")
    }

    /// The loading panel keeps its PROJECTS head, with no count, over its skeleton rows; its foot
    /// sets the handle apart: weight 500 in the secondary ink, the rest in the tertiary.
    @Test(arguments: [Look.light, .dark])
    func theLoadingPanelHasItsHeadAndTheFootSetsTheHandleApart(_ look: Look) throws {
        let panel = LeftPanel(look: look, mono: try LabelFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: LeftPanel.width.standard, height: 600), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        window.contentView = panel
        panel.renderLoading(who: "ana · member", handle: "ana", controlPlane: "main.acme.dev")
        panel.layoutSubtreeIfNeeded()
        #expect(!panel.scroll.isHidden && panel.list.numberOfRows == 1, "the loading panel shows \(panel.list.numberOfRows) rows")
        let head = try #require(panel.list.view(atColumn: 0, row: 0, makeIfNecessary: true))
        #expect(head.accessibilityLabel() == "Projects", "the head says \(head.accessibilityLabel() ?? "nothing")")
        let words = panel.who.attributedStringValue
        let handle = words.attributes(at: 0, effectiveRange: nil), rest = words.attributes(at: words.length - 1, effectiveRange: nil)
        #expect((handle[.foregroundColor] as? NSColor) == NSColor(look.textSecondary) && (rest[.foregroundColor] as? NSColor) == NSColor(look.textTertiary))
        let handleFont = (handle[.font] as? NSFont)?.fontName ?? "none", restFont = (rest[.font] as? NSFont)?.fontName ?? "none"
        #expect(handleFont.contains("Medium") && !restFont.contains("Medium"), "the handle is \(handleFont), the rest \(restFont)")
    }

    /// r3-2: the longest taken name at the narrowest card: "Show …" is cut at its end yet reads as a
    /// link (the line runs under Cancel and Create, which sit level with the box above it), and "or
    /// choose another name." keeps its whole width on the one line.
    @Test func aLongTakenNameNeverSqueezesTheWordsAfterIt() async throws {
        let server = RecordedServer([try Exchange("projects"), try WorkspacesReachTests.longRows(), try Exchange("refusal-conflict-workspace-exists")])
        let (view, window) = try WorkspacesViewTests.view(server, width: WorkspacesViewTests.narrowestCard)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        view.project.on(.newWorkspace)
        view.project.field.field.stringValue = WorkspacesReachTests.long
        view.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification))
        view.project.on(.create)
        #expect(await RunnerTabTests.until { !view.busy && view.text.contains("exists already") }, "\(view.text)")
        view.layoutSubtreeIfNeeded()
        view.project.list.layoutSubtreeIfNeeded()
        let row = try #require(view.project.list.rowView(atRow: 1, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        let line = try #require(WorkspacesViewTests.found(CreateRow.self, in: row).first?.showLine)
        let show = try #require(line.arrangedSubviews.first as? LinkButton)
        let rest = try #require(line.arrangedSubviews.last as? NSTextField)
        #expect(Double(rest.frame.width) >= Double(rest.intrinsicContentSize.width) - 0.5, "the words after the link have \(rest.frame.width) of \(rest.intrinsicContentSize.width) pt")
        #expect(Double(show.frame.width) < Double(show.intrinsicContentSize.width) && show.frame.width > 40, "the link is \(show.frame.width) pt of \(show.intrinsicContentSize.width)")
        #expect(Double(line.frame.height) < 2 * Look.Step.footnote.points, "the show line wraps to \(line.frame.height) pt")
    }

    /// r3-3: an owner whose account is not active fits the owner's 104 pt column, never cut.
    @Test func anInactiveOwnerFitsItsColumn() throws {
        let page = try #require(WorkspacesViewTests.page([WorkspaceRow(name: "gone", instance: "harbor-gone", state: .known(.stopped), owner: WorkspaceRow.removedOwner)], for: WorkspacesViewTests.ana))
        let (project, window) = try WorkspacesViewTests.project(width: 1024 - Rail.width - 4 - LeftPanel.width.standard)
        defer { window.orderOut(nil) }
        project.render(page)
        project.layoutSubtreeIfNeeded()
        let line = try #require(WorkspacesViewTests.lines(project.list).first)
        let whose = try #require(line.whose)
        let label = try #require(WorkspacesViewTests.found(NSTextField.self, in: whose).first)
        #expect(label.stringValue == "inactive" && Double(label.frame.width) >= Double(label.intrinsicContentSize.width) - 0.5, "\(label.stringValue) has \(label.frame.width) of \(label.intrinsicContentSize.width) pt")
    }
}
