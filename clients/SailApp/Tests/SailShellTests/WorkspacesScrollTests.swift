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

    /// r3-2: the longest taken name at the narrowest card: "Show …" is cut at its end, and "or
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
        let show = try #require(WorkspacesViewTests.found(LinkButton.self, in: line).first)
        let rest = try #require(WorkspacesViewTests.found(NSTextField.self, in: line).first)
        #expect(Double(rest.frame.width) >= Double(rest.intrinsicContentSize.width) - 0.5, "the words after the link have \(rest.frame.width) of \(rest.intrinsicContentSize.width) pt")
        #expect(Double(show.frame.width) < Double(show.intrinsicContentSize.width) && show.frame.width > 20, "the link is \(show.frame.width) pt of \(show.intrinsicContentSize.width)")
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
