import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Testing

/// Nothing the Workspaces section shows is out of a person's reach, in every frame it draws, at
/// every width the frames draw, with short names and the longest the server allows: no view drawn
/// outside its parent (seen, never hit), no label or button squeezed to nothing, no two of its
/// words or buttons over each other, no wrapped words taller than their frame, and every button
/// taking a click across its face, the actions shown only on hover or choice among them.
@MainActor @Suite struct WorkspacesReachTests {
    /// The longest name and handle the server allows.
    static let long = String(repeating: "n", count: 63), handle = String(repeating: "k", count: 63)

    /// The section's widths the frames draw: the narrowest card; 720 pt, the panel beside a narrow
    /// page; the least drawn wide beside the panel; the drive's; and the drive's beside the widest panel.
    static let widths: [(section: Double, panel: Double)] = [
        (WorkspacesViewTests.narrowestCard, LeftPanel.width.standard), (720, LeftPanel.width.standard),
        (ProjectView.wide + LeftPanel.width.standard, LeftPanel.width.standard), (1024 - Rail.width - 4, LeftPanel.width.standard),
        (1024 - Rail.width - 4, LeftPanel.width.most),
    ]

    @Test(arguments: 0 ..< 5)
    func nothingShownIsOutOfReach(_ index: Int) async throws {
        let (width, panel) = Self.widths[index]
        var faults: [String] = []
        for scene in Scene.allCases {
            faults += try await scene.faults(width: width, panel: panel).map { "\(scene) at \(width) pt beside a \(panel) pt panel: \($0)" }
        }
        #expect(faults == [], "\(faults.joined(separator: "\n"))")
    }

    @MainActor enum Scene: CaseIterable {
        case rows, refused, stale, creating, loading, noProject, unreachable, unready, thirtyRows, overThePanel

        func faults(width: Double, panel: Double) async throws -> [String] {
            switch self {
            case .rows:
                let (view, window) = try await WorkspacesReachTests.shown(RecordedServer([try Exchange("projects"), try WorkspacesReachTests.longRows()]), width: width, panel: panel, saying: "dock")
                defer { window.orderOut(nil) }
                var faults: [String] = []
                for id in WorkspacesReachTests.ids(view.project.list) {
                    view.project.list.choose(id)
                    faults += WorkspacesReachTests.outOfReach(in: view).map { "\(id.prefix(12)) chosen: \($0)" }
                }
                return faults
            case .refused:
                let server = RecordedServer([try Exchange("projects"), try WorkspacesReachTests.longRows(), try Exchange("refusal-conflict-workspace-exists")])
                let (view, window) = try await WorkspacesReachTests.shown(server, width: width, panel: panel, saying: "dock")
                defer { window.orderOut(nil) }
                view.project.on(.newWorkspace)
                view.project.field.field.stringValue = WorkspacesReachTests.long
                view.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification))
                view.project.on(.create)
                let reached = await RunnerTabTests.until { !view.busy && view.text.contains("exists already") }
                guard reached else { return ["never refused: \(view.text)"] }
                return WorkspacesReachTests.outOfReach(in: view)
            case .stale:
                let server = RecordedServer([try Exchange("projects"), try WorkspacesReachTests.longRows()])
                let (view, window) = try await WorkspacesReachTests.shown(server, width: width, panel: panel, saying: "dock")
                defer { window.orderOut(nil) }
                view.client = SailClient(transport: Proxied(server, failing: "GET /v1/projects/harbor/workspaces"), token: "tok")
                view.project.on(.listAgain)
                let reached = await RunnerTabTests.until { view.text.contains("Couldn’t list harbor’s workspaces") }
                guard reached else { return ["never stale: \(view.text)"] }
                return WorkspacesReachTests.outOfReach(in: view)
            case .creating:
                let transport = Held(RecordedServer([try Exchange("projects"), try WorkspacesReachTests.longRows(), try Exchange("workspace-create")]), holding: "POST")
                let (view, window) = try await WorkspacesReachTests.shown(transport, width: width, panel: panel, saying: "dock")
                defer { window.orderOut(nil) }
                await WorkspacesViewTests.create("new", in: view, transport)
                let reached = await RunnerTabTests.until { view.text.contains("new · creating") }
                guard reached else { return ["never creating: \(view.text)"] }
                let faults = WorkspacesReachTests.outOfReach(in: view)
                transport.release()
                _ = await RunnerTabTests.until { !view.busy }
                return faults
            case .loading:
                let transport = Held(RecordedServer([try Exchange("projects")]), holding: "GET /v1/projects")
                let (view, window) = try WorkspacesReachTests.view(transport, width: width, panel: panel)
                defer { window.orderOut(nil) }
                view.shown()
                let faults = WorkspacesReachTests.outOfReach(in: view)
                transport.release()
                return faults
            case .noProject:
                let none = Exchange(method: "GET", path: "/v1/projects", status: 200, body: Data(#"{"projects": [], "total": 0, "schema_version": 1}"#.utf8))
                let (view, window) = try await WorkspacesReachTests.shown(RecordedServer([none]), width: width, panel: panel, saying: "No project is open")
                defer { window.orderOut(nil) }
                return WorkspacesReachTests.outOfReach(in: view)
            case .unreachable:
                let (view, window) = try await WorkspacesReachTests.shown(RecordedServer([]), width: width, panel: panel, saying: "did not answer")
                defer { window.orderOut(nil) }
                return WorkspacesReachTests.outOfReach(in: view)
            case .unready:
                let demo = Exchange(method: "GET", path: "/v1/projects", status: 200, body: Data(#"{"projects": [{"name": "demo", "container_status": "running", "template": "missing"}], "total": 1, "schema_version": 1}"#.utf8))
                let none = Exchange(method: "GET", path: "/v1/projects/demo/workspaces", status: 200, body: Data(#"{"project": "demo", "workspaces": [], "schema_version": 1}"#.utf8))
                let (view, window) = try await WorkspacesReachTests.shown(RecordedServer([demo, none]), width: width, panel: panel, saying: "demo isn’t ready yet")
                defer { window.orderOut(nil) }
                return WorkspacesReachTests.outOfReach(in: view)
            case .thirtyRows:
                let rows = (1 ... 30).map { ["name": String(format: "ws-%02d", $0), "instance": String(format: "harbor-ws-%02d", $0), "state": "running", "owner": $0.isMultiple(of: 2) ? "ana" : "uday"] }
                let (view, window) = try await WorkspacesReachTests.shown(RecordedServer([try Exchange("projects"), try WorkspacesReachTests.list(rows)]), width: width, panel: panel, saying: "ws-30")
                defer { window.orderOut(nil) }
                guard let scroll = view.project.listScroll else { return ["the list does not scroll"] }
                view.layoutSubtreeIfNeeded()
                let room = Double(scroll.documentView?.frame.height ?? 0) - Double(scroll.contentSize.height)
                scroll.contentView.scroll(to: NSPoint(x: 0, y: max(room / 2, 0)))
                scroll.reflectScrolledClipView(scroll.contentView)
                return WorkspacesReachTests.outOfReach(in: view)
            case .overThePanel:
                guard width < WorkspacesView.narrow else { return [] }
                let (view, window) = try await WorkspacesReachTests.shown(RecordedServer([try Exchange("projects"), try WorkspacesReachTests.longRows()]), width: width, panel: panel, saying: "dock")
                defer { window.orderOut(nil) }
                view.toggleSidebar(nil)
                view.layoutSubtreeIfNeeded()
                return WorkspacesReachTests.outOfReach(in: view.panel)
            }
        }
    }

    /// harbor's rows with the longest name, the longest handle, an owner whose account is not
    /// active, no owner, and the person's own.
    static func longRows() throws -> Exchange {
        try list([
        ["name": "dock", "instance": "harbor-dock", "state": "running", "owner": "uday"],
        ["name": long, "instance": "harbor-\(long)", "state": "stopped", "owner": "uday"],
        ["name": "quay", "instance": "harbor-quay", "state": "running", "owner": handle],
        ["name": "gone", "instance": "harbor-gone", "state": "stopped", "owner": "removed"],
        ["name": "orphan", "instance": "harbor-orphan", "state": "error"],
        ])
    }

    static func list(_ rows: [[String: String]]) throws -> Exchange {
        let body = try JSONSerialization.data(withJSONObject: ["project": "harbor", "schema_version": 1, "workspaces": rows] as [String: Any])
        return Exchange(method: "GET", path: "/v1/projects/harbor/workspaces", status: 200, body: body)
    }

    static func view(_ transport: any Transport, width: Double, panel: Double) throws -> (WorkspacesView, KeyWindow) {
        let (view, window) = try WorkspacesViewTests.view(transport, width: width)
        view.person = WorkspacesViewTests.ana
        view.panel.widthConstraint.constant = panel
        return (view, window)
    }

    /// The section shown for ana (no admin: other people's rows say who opens them), once it says `words`.
    static func shown(_ transport: any Transport, width: Double, panel: Double, saying words: String) async throws -> (WorkspacesView, KeyWindow) {
        let (view, window) = try Self.view(transport, width: width, panel: panel)
        view.shown()
        #expect(await RunnerTabTests.until { !view.busy && view.text.contains(words) }, "never said \(words): \(view.text)")
        return (view, window)
    }

    static func ids(_ list: SailList) -> [String] {
        list.entries.compactMap { entry in
            guard case let .item(item) = entry else { return nil }
            return item.id
        }
    }

    /// What in `root` a person could see but not reach. A text field's own insides (the field
    /// editor's clip and viewport) are AppKit's, and overhang by design; rows scrolled out of view
    /// are judged only where they show.
    static func outOfReach(in root: NSView) -> [String] {
        root.layoutSubtreeIfNeeded()
        guard let window = root.window, let content = window.contentView, let frameView = content.superview else { return ["no window"] }
        var faults: [String] = []
        var leaves: [(view: NSView, rect: NSRect)] = []
        func named(_ view: NSView) -> String {
            let words = (view as? NSTextField)?.stringValue ?? (view as? NSButton)?.title ?? ""
            return words.isEmpty ? String(describing: type(of: view)) : "\"\(words.prefix(24))\""
        }
        func walk(_ view: NSView) {
            for child in view.subviews where !child.isHidden {
                let shown = child.visibleRect
                let words = (child as? NSTextField)?.stringValue ?? (child as? NSButton)?.title ?? ""
                if child.frame.width < 1 || child.frame.height < 1 {
                    if !words.isEmpty, view.visibleRect.width > 0 { faults.append("\(named(child)) squeezed to nothing") }
                    continue
                }
                // A label's cell draws its words 2 pt inside its frame, which may hang 2 pt out to keep them.
                let inset = child is NSTextField ? 2.0 : 0
                let drawn = child.alignmentRect(forFrame: child.frame).insetBy(dx: inset, dy: 0)
                if !(view is NSClipView), !view.bounds.insetBy(dx: -0.5, dy: -0.5).contains(drawn) {
                    faults.append("\(named(child)) at \(drawn) outside its \(type(of: view)) \(view.bounds)")
                }
                if let label = child as? NSTextField, !words.isEmpty, label.maximumNumberOfLines != 1, label.lineBreakMode == .byWordWrapping || label.lineBreakMode == .byCharWrapping,
                   let cell = label.cell, Double(cell.cellSize(forBounds: NSRect(x: 0, y: 0, width: label.frame.width, height: .greatestFiniteMagnitude)).height) > Double(label.frame.height) + 1
                {
                    faults.append("\(named(label)) clipped: its words need more than its \(label.frame.height) pt")
                }
                if !shown.isEmpty, child is NSButton || child is Pill || (child is NSTextField && !words.isEmpty) {
                    leaves.append((child, child.convert(shown, to: root)))
                }
                if let button = child as? NSButton, !shown.isEmpty {
                    let face = button.alignmentRect(forFrame: button.bounds).insetBy(dx: 2, dy: 2)
                    if shown.contains(face) {
                        for point in [NSPoint(x: face.midX, y: face.midY), NSPoint(x: face.minX, y: face.midY), NSPoint(x: face.maxX, y: face.midY), NSPoint(x: face.midX, y: face.minY), NSPoint(x: face.midX, y: face.maxY)] {
                            let hit = content.hitTest(frameView.convert(button.convert(point, to: nil), from: nil))
                            if hit !== button, hit?.isDescendant(of: button) != true {
                                faults.append("\(named(button)) at \(point) reaches \(hit.map { String(describing: type(of: $0)) } ?? "nothing")")
                            }
                        }
                    }
                }
                if !(child is NSTextField) { walk(child) }
            }
        }
        walk(root)
        for (index, one) in leaves.enumerated() {
            for other in leaves.dropFirst(index + 1) where !one.view.isDescendant(of: other.view) && !other.view.isDescendant(of: one.view) {
                let both = one.rect.intersection(other.rect)
                if both.width > 1, both.height > 1 { faults.append("\(named(one.view)) and \(named(other.view)) overlap at \(both)") }
            }
        }
        return faults
    }
}
