import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailWorkbench
import Testing

/// The left panel's tree: an open project lists the person's own workspaces under it, each with
/// its state square and, chosen or under the pointer, its +; other people's in one quiet row; the
/// keys open and shut projects and open workspaces; the + opens New Session beside the panel's row;
/// a long name yields in its middle.
///
/// One test at a time: each waits on the one main actor every AppKit test shares for the recorded
/// answers, as `WorkspacesViewTests` do, whose suite was red side by side and green one at a time.
@MainActor @Suite(.serialized, .timeLimit(.minutes(1))) struct PanelTreeTests {
    init() {
        ExitWatch.install()
    }

    static let longest = "the-longest-name-a-workspace-may-have-is-sixty-three-characters"

    static func projects(_ names: [String]) throws -> Exchange {
        let listed = names.map { ["name": $0, "container_status": "running", "template": "ready"] }
        let body = try JSONSerialization.data(withJSONObject: ["projects": listed, "schema_version": 1, "total": names.count] as [String: Any])
        return Exchange(method: "GET", path: "/v1/projects", status: 200, body: body)
    }

    static func rows(_ project: String, _ rows: [[String: String]]) throws -> Exchange {
        let body = try JSONSerialization.data(withJSONObject: ["project": project, "schema_version": 1, "workspaces": rows] as [String: Any])
        return Exchange(method: "GET", path: "/v1/projects/\(project)/workspaces", status: 200, body: body)
    }

    static let lighthouse = [["name": "beacon", "instance": "lighthouse-beacon", "state": "running", "owner": "uday"]]

    /// harbor (dock and pier uday's, quay ana's) and lighthouse listed for uday, harbor chosen last
    /// and so open, with `open` left open too; each open project shown with what it holds.
    static func shown(
        open: [String] = [], harbor: Exchange? = nil, also: [Exchange] = [], width: Double = 1000
    ) async throws -> (view: WorkspacesView, window: KeyWindow, server: RecordedServer, kept: Kept) {
        let server = RecordedServer([try Self.projects(["harbor", "lighthouse"]), try harbor ?? Exchange("workspaces-three"), try Self.rows("lighthouse", Self.lighthouse)] + also)
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        let kept = Kept()
        view.lastChosen = { _ in "harbor" }
        view.lastExpanded = { _ in open }
        view.keepExpanded = { names, _ in kept.expanded.append(names) }
        view.onOpen = { project, name in kept.opened.append("\(project)/\(name)") }
        view.person = WorkspacesViewTests.uday
        view.client = SailClient(transport: server, token: "tok")
        view.panel.list.pointer = { nil }
        view.project.list.pointer = { nil }
        view.newSession.view.pointer = { nil }
        view.newSession.popover.keysLeft = Notification.Name("sail.tests.keys-left.\(UUID().uuidString)")
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = view
        window.orderFront(nil)
        view.shown()
        try #require(await RunnerTabTests.until { !view.busy && Self.ids(view.panel).contains("harbor/dock") }, "harbor's workspaces not under it: \(Self.ids(view.panel))")
        view.layoutSubtreeIfNeeded()
        return (view, window, server, kept)
    }

    final class Kept {
        var expanded: [[String]] = []
        var opened: [String] = []
    }

    static func ids(_ panel: LeftPanel) -> [String] {
        panel.list.entries.compactMap { if case let .item(item) = $0 { item.id } else { nil } }
    }

    /// Row `id`'s cell and row view, laid out.
    static func cell(_ id: String, in panel: LeftPanel) throws -> (cell: SidebarRowCell, row: NSTableRowView) {
        let index = try #require(panel.list.entries.firstIndex { if case let .item(item) = $0 { item.id == id } else { false } }, "no row \(id)")
        let row = try #require(panel.list.rowView(atRow: index, makeIfNecessary: true))
        row.layoutSubtreeIfNeeded()
        return (try #require(WorkspacesViewTests.found(SidebarRowCell.self, in: row).first), row)
    }

    static func asked(_ server: RecordedServer, _ project: String) async -> Int {
        await server.requests.filter { $0.route == "GET /v1/projects/\(project)/workspaces" }.count
    }

    static func arrow(_ code: UInt16) throws -> NSEvent {
        let key = code == 123 ? NSLeftArrowFunctionKey : NSRightArrowFunctionKey
        return try NewSessionPanelTests.key(code, String(Character(try #require(UnicodeScalar(UInt16(key))))), [.function, .numericPad])
    }

    /// 1, 2: harbor, open, holds uday's dock and pier, 14 pt in, each with its page row's state as
    /// its square, then one quiet row of other people's; lighthouse, shut, holds nothing shown and
    /// was asked nothing.
    @Test func anOpenProjectListsTheirOwnWorkspacesUnderIt() async throws {
        let (view, window, server, _) = try await Self.shown()
        defer { window.orderOut(nil) }
        #expect(Self.ids(view.panel) == [WorkspacesModel.thisMac, "harbor", "harbor/dock", "harbor/pier", "harbor\u{0}others", "lighthouse"])
        #expect(await Self.asked(server, "lighthouse") == 0, "a shut project was asked")
        let harbor = try Self.cell("harbor", in: view.panel), dock = try Self.cell("harbor/dock", in: view.panel), pier = try Self.cell("harbor/pier", in: view.panel)
        let others = try Self.cell("harbor\u{0}others", in: view.panel), lighthouse = try Self.cell("lighthouse", in: view.panel)
        #expect(harbor.cell.disclosure?.open == true && lighthouse.cell.disclosure?.open == false && others.cell.disclosure?.open == false && dock.cell.disclosure == nil)
        #expect(others.cell.label.stringValue == "Other people's · 1" && others.cell.plus == nil)
        let running = PillPaint(.running, look: .light), stopped = PillPaint(.stopped, look: .light)
        #expect(dock.cell.square?.ink == running.dot && dock.cell.square?.hollow == false && pier.cell.square?.ink == stopped.dot && pier.cell.square?.hollow == true)
        #expect(near(dock.row.frame.height, LeftPanel.nested.height) && near(harbor.row.frame.height, RowStyle.sidebar.height))
        let tile = try #require(harbor.cell.tile), square = try #require(dock.cell.square)
        let tileX = harbor.cell.convert(tile.frame, to: harbor.row).minX, squareX = dock.cell.convert(square.frame, to: dock.row).minX
        #expect(near(squareX - tileX, LeftPanel.nested.indent) && near(square.frame.width, 7), "the square stands at \(squareX), the tile at \(tileX)")
        #expect(view.panel.list.chosen == "harbor")
    }

    /// 3: choosing a workspace's row shows its project's page with its row chosen; choosing the
    /// project's row shows the page with none; a workspace of the project not shown shows that one.
    @Test func choosingAWorkspaceShowsItsProjectsPageWithItsRowChosen() async throws {
        let (view, window, _, _) = try await Self.shown(open: ["lighthouse"])
        defer { window.orderOut(nil) }
        view.panel.list.choose("harbor/pier")
        #expect(view.text.hasPrefix("Workspaces\nharbor\n") && view.project.list.chosen == "pier", "\(view.project.list.chosen ?? "none") chosen")
        view.panel.list.choose("harbor")
        #expect(view.project.list.chosen == nil && view.panel.list.chosen == "harbor")
        try #require(await RunnerTabTests.until { Self.ids(view.panel).contains("lighthouse/beacon") }, "lighthouse's workspaces not under it: \(Self.ids(view.panel))")
        view.panel.list.choose("lighthouse/beacon")
        #expect(view.text.hasPrefix("Workspaces\nlighthouse\n") && view.project.list.chosen == "beacon", "the page: \(view.text)")
        #expect(await RunnerTabTests.until { !view.busy } && view.panel.list.chosen == "lighthouse/beacon")
        view.project.list.choose(nil)
        #expect(view.panel.list.chosen == "lighthouse", "the page's choice left the panel's")
    }

    /// 4, 5: opening a project asks its workspaces once, kept as the person's arrangement; a
    /// refresh asks each open project again.
    @Test func openingAProjectAsksOnceAndARefreshAsksAgain() async throws {
        let (view, window, server, kept) = try await Self.shown()
        defer { window.orderOut(nil) }
        let lighthouse = try Self.cell("lighthouse", in: view.panel)
        let disclosure = try #require(lighthouse.cell.disclosure)
        let press = try #require(NSEvent.mouseEvent(with: .leftMouseDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1))
        disclosure.mouseDown(with: press)
        #expect(await RunnerTabTests.until { Self.ids(view.panel).contains("lighthouse/beacon") }, "lighthouse opened shows nothing: \(Self.ids(view.panel))")
        #expect(kept.expanded.last == ["harbor", "lighthouse"])
        view.panel.list.choose("lighthouse")
        window.makeFirstResponder(view.panel.list)
        #expect(view.panel.list.onKey?(try Self.arrow(123)) == true && !Self.ids(view.panel).contains("lighthouse/beacon"), "← left lighthouse open")
        #expect(view.panel.list.onKey?(try Self.arrow(124)) == true && Self.ids(view.panel).contains("lighthouse/beacon"), "→ left lighthouse shut")
        #expect(await Self.asked(server, "lighthouse") == 1, "opened again, lighthouse was asked again")
        let harbor = await Self.asked(server, "harbor")
        await view.refresh()
        #expect(await RunnerTabTests.until { !view.busy })
        let (lighthouseAgain, harborAgain) = (await Self.asked(server, "lighthouse"), await Self.asked(server, "harbor"))
        #expect(lighthouseAgain == 2 && harborAgain == harbor + 1, "a refresh asked lighthouse \(lighthouseAgain) times and harbor \(harborAgain - harbor)")
        #expect(kept.expanded == [["harbor", "lighthouse"], ["harbor"], ["harbor", "lighthouse"]])
    }

    /// 6: → goes into an open project, ← back to it and shuts it, Return opens a workspace as its
    /// page's row's Open or Start and Open does.
    @Test func theKeysMoveThroughTheTreeAndReturnOpens() async throws {
        let (view, window, _, kept) = try await Self.shown()
        defer { window.orderOut(nil) }
        let list = view.panel.list
        window.makeFirstResponder(list)
        list.choose("harbor")
        #expect(list.onKey?(try Self.arrow(124)) == true && list.chosen == "harbor/dock", "→ on open harbor chose \(list.chosen ?? "none")")
        #expect(view.project.list.chosen == "dock")
        #expect(list.onKey?(try NewSessionPanelTests.returnKey()) == true && kept.opened == ["harbor/dock"])
        list.choose("harbor/pier")
        #expect(list.onKey?(try NewSessionPanelTests.returnKey()) == true && kept.opened == ["harbor/dock", "harbor/pier"])
        #expect(list.onKey?(try Self.arrow(123)) == true && list.chosen == "harbor" && view.project.list.chosen == nil, "← on pier chose \(list.chosen ?? "none")")
        #expect(list.onKey?(try Self.arrow(123)) == true && !Self.ids(view.panel).contains("harbor/dock") && list.chosen == "harbor", "← shut nothing")
        #expect(list.onKey?(try Self.arrow(123)) == false, "← on a shut project was taken")
    }

    /// 1: the + shows on the row chosen or under the pointer, on the person's own rows alone; it
    /// opens New Session for its workspace beside the panel's row, over the content, its top on the
    /// row's; pressed again it closes; pressed on another row it chooses that row first.
    @Test func thePlusOpensNewSessionBesideThePanelsRow() async throws {
        let (view, window, _, _) = try await Self.shown(also: [try Exchange("workspace-agents-dock"), try Exchange("workspace-agents-pier")])
        defer { window.orderOut(nil) }
        view.panel.list.choose("harbor/dock")
        let dock = try Self.cell("harbor/dock", in: view.panel), pier = try Self.cell("harbor/pier", in: view.panel)
        let plus = try #require(dock.cell.plus)
        #expect(!plus.isHidden && pier.cell.plus?.isHidden == true, "the + shows on a row neither chosen nor hovered, or not on the chosen one")
        try NewSessionPanelTests.press(plus)
        try #require(await RunnerTabTests.until { view.newSession.isOpen }, "the + opened nothing")
        let row = try #require(view.panel.rowFrame("harbor", "dock").map { window.convertToScreen($0) }), box = view.newSession.popover.frame
        let page = NewSessionPanel.shows(view, in: window).insetBy(dx: NewSessionPanel.inset - 0.5, dy: NewSessionPanel.inset - 0.5)
        #expect(near(box.minX, row.maxX + NewSessionPanel.gap) && near(box.maxY, row.maxY) && page.contains(box), "the box \(box) beside the row \(row) in \(page)")
        #expect(view.newSession.workspace == "dock" && view.project.list.primaryElsewhere)
        try NewSessionPanelTests.press(plus)
        #expect(!view.newSession.isOpen, "the + pressed again left it open")
        try NewSessionPanelTests.press(try #require(pier.cell.plus))
        try #require(await RunnerTabTests.until { view.newSession.isOpen }, "pier's + opened nothing")
        #expect(view.newSession.workspace == "pier" && view.project.list.chosen == "pier" && view.panel.list.chosen == "harbor/pier")
    }

    /// 1: at the least window, with the panel slid over the content, the box stays in the window.
    @Test func atTheLeastWindowTheBoxStaysInTheWindow() async throws {
        let (view, window, _, _) = try await Self.shown(also: [try Exchange("workspace-agents-dock")], width: WorkspacesViewTests.narrowestCard)
        defer { window.orderOut(nil) }
        view.toggleSidebar(nil)
        view.layoutSubtreeIfNeeded()
        view.panel.list.choose("harbor/dock")
        try NewSessionPanelTests.press(try #require(try Self.cell("harbor/dock", in: view.panel).cell.plus))
        try #require(await RunnerTabTests.until { view.newSession.isOpen }, "the + opened nothing")
        let page = NewSessionPanel.shows(view, in: window).insetBy(dx: NewSessionPanel.inset - 0.5, dy: NewSessionPanel.inset - 0.5)
        #expect(page.contains(view.newSession.popover.frame), "the box \(view.newSession.popover.frame) leaves \(page)")
    }

    /// 7: at the panel's default width a long name yields in its middle, its tooltip whole; a name
    /// that fits is whole and says nothing twice.
    @Test func aLongNameYieldsInItsMiddle() async throws {
        let rows = try WorkspacesReachTests.list([
            ["name": "dock", "instance": "harbor-dock", "state": "running", "owner": "uday"], ["name": Self.longest, "instance": "harbor-\(Self.longest)", "state": "running", "owner": "uday"],
        ])
        let (view, window, _, _) = try await Self.shown(harbor: rows)
        defer { window.orderOut(nil) }
        #expect(near(view.panel.frame.width, LeftPanel.width.standard))
        let long = try Self.cell("harbor/\(Self.longest)", in: view.panel).cell, dock = try Self.cell("harbor/dock", in: view.panel).cell
        let style = long.label.attributedStringValue.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle
        #expect(style?.lineBreakMode == .byTruncatingMiddle && long.label.frame.maxX <= long.bounds.maxX + 0.5, "the name runs to \(long.label.frame.maxX) in \(long.bounds.width)")
        #expect(long.nameTip?.text == Self.longest && long.nameTip?.shows == true, "the cut name is not said whole on hover")
        #expect(dock.nameTip?.shows == false, "dock, whole, says itself again on hover")
    }
}
