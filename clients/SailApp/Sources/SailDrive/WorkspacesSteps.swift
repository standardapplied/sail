import AppKit
import Foundation
import SailAPI
import SailControls
import SailKitTestSupport
import SailShell
import SailTerminal

/// The control plane of the drive's workspaces run: every route answered as the server answered
/// it in the goldens its `ClientGoldensTest` writes, a step choosing which golden answers a route
/// from then on; a ticket answered so carries a ticket of the drive's ``FakeRunnerDoor`` and its
/// address, so a runner tab attaches to a door that refuses what the real door refuses. A step can
/// put a proxy's 502 in front of one route. No answer is written here.
actor GoldenDesk: Transport {
    static let shared = GoldenDesk()
    static let door = try? FakeRunnerDoor()
    private var answers: [String: HTTPResponse] = [:]
    private var held: [String: Duration] = [:]
    private var failing: String?
    /// The failing route cannot be reached at all, in place of the proxy's 502.
    private var unreached = false
    private(set) var asked: [HTTPRequest] = []

    init() {
        let first = ["passkey-login-start", "passkey-login-finish", "whoami", "projects", "workspaces", "workspace-create", "terminal-ticket-workspace"]
        for exchange in first.compactMap({ try? Exchange($0) }) {
            answers["\(exchange.method) \(exchange.path)"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
        }
    }

    /// From now on `route` is answered as the golden `name` was, `after` it was asked: a call
    /// under way long enough to be seen.
    func answer(_ route: String, as name: String, after: Duration = .zero) throws {
        let exchange = try Exchange(name)
        answers[route] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
        held[route] = after
    }

    /// From now on the projects are listed as the golden `projects` lists them, but these: each a
    /// name and its template's state.
    func answerProjects(_ projects: [[String]]) throws {
        let exchange = try Exchange("projects")
        guard var body = try JSONSerialization.jsonObject(with: exchange.body) as? [String: Any] else { return }
        body["projects"] = projects.map { ["name": $0.first ?? "", "container_status": "running", "template": $0.dropFirst().first ?? "ready"] }
        body["total"] = projects.count
        answers["GET /v1/projects"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: try JSONSerialization.data(withJSONObject: body))
        held["GET /v1/projects"] = .zero
    }

    /// From now on harbor's workspaces are `count` made-up rows: alternately the person's (uday's)
    /// and ana's, running, stopped, paused and not created in turn.
    func answerRows(_ count: Int) throws {
        let exchange = try Exchange("workspaces-three")
        let states = ["running", "stopped", "frozen", "not_created"]
        let rows = (1 ... max(count, 1)).map { index in
            let name = String(format: "ws-%02d", index)
            return ["name": name, "instance": "harbor-\(name)", "state": states[index % states.count], "owner": index.isMultiple(of: 2) ? "ana" : "uday"]
        }
        let body: [String: Any] = ["project": "harbor", "schema_version": 1, "workspaces": rows]
        answers["GET /v1/projects/harbor/workspaces"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: try JSONSerialization.data(withJSONObject: body))
        held["GET /v1/projects/harbor/workspaces"] = .zero
    }

    /// From now on harbor's workspaces are the three of the golden and one more of `name`, the
    /// person's; and a create is refused as the server refuses a name taken, naming `name`: the
    /// recorded refusal for dock, its name replaced.
    func answerTaken(_ name: String) throws {
        let refusal = try Exchange("refusal-conflict-workspace-exists")
        let said = String(decoding: refusal.body, as: UTF8.self).replacingOccurrences(of: "dock", with: name)
        answers["POST /v1/projects/harbor/workspaces"] = HTTPResponse(status: refusal.status, headers: refusal.headers, body: Data(said.utf8))
        held["POST /v1/projects/harbor/workspaces"] = .zero
        let exchange = try Exchange("workspaces-three")
        guard var body = try JSONSerialization.jsonObject(with: exchange.body) as? [String: Any], let rows = body["workspaces"] as? [[String: Any]] else { return }
        body["workspaces"] = rows + [["name": name, "instance": "harbor-\(name)", "state": "running", "owner": "uday"]]
        answers["GET /v1/projects/harbor/workspaces"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: try JSONSerialization.data(withJSONObject: body))
        held["GET /v1/projects/harbor/workspaces"] = .zero
    }

    /// From now on `project`'s workspaces are listed as none.
    func answerNoRows(_ project: String) throws {
        let exchange = try Exchange("workspaces-three")
        let body: [String: Any] = ["project": project, "schema_version": 1, "workspaces": [[String: String]]()]
        answers["GET /v1/projects/\(project)/workspaces"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: try JSONSerialization.data(withJSONObject: body))
        held["GET /v1/projects/\(project)/workspaces"] = .zero
    }

    /// From now on a proxy answers `route` with a 502 of its own; nil, nothing.
    func fail(_ route: String?, unreached: Bool = false) {
        failing = route
        self.unreached = unreached
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        asked.append(request)
        if let after = held[request.route], after > .zero { try? await Task.sleep(for: after) }
        guard request.route != failing else { throw unreached ? SailError.unreachable("Could not connect to the server.") : SailError.unexpected(route: request.route, status: 502) }
        guard let answer = answers[request.route] else { throw .unreachable("no golden answers \(request.route)") }
        guard request.route == "POST /v1/terminal/tickets", answer.status == 201, let door = Self.door,
              var body = (try? JSONSerialization.jsonObject(with: answer.body)) as? [String: Any] else { return answer }
        let ticket = door.issue(for: Self.ticketRequest(request))
        body["ticket"] = ticket.ticket
        body["address"] = ticket.address
        return HTTPResponse(status: answer.status, headers: answer.headers, body: (try? JSONSerialization.data(withJSONObject: body)) ?? answer.body)
    }

    /// What a ticket request asks, read from its body as the API reads it: a door's ticket covers
    /// that and nothing else.
    static func ticketRequest(_ request: HTTPRequest) -> TicketRequest {
        let asked = (request.body.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]
        guard asked["action"] as? String == "attach" else {
            return .open(project: asked["project"] as? String ?? "", workspace: asked["workspace"] as? String ?? "")
        }
        return .attach(session: asked["session"] as? String ?? "")
    }

    func open(_ request: HTTPRequest) throws(SailError) -> HTTPStream {
        throw .unreachable("no golden streams \(request.route)")
    }
}

/// The drive's steps on the Workspaces section and the runner's tabs, against ``GoldenDesk``.
extension Driver {
    /// Opens the create row and types `name` into its field as a person does: the field changes
    /// and says so.
    private static func found<Found: NSView>(_ kind: Found.Type, in view: NSView) -> [Found] {
        if let view = view as? Found { return [view] }
        return view.subviews.flatMap { found(kind, in: $0) }
    }

    func performWorkspaces(_ step: Step) async -> (Bool, String)? {
        let pane = shell.workspaces
        if let pair = step.golden {
            guard pair.count == 2 || pair.count == 3, let seconds = Int(pair.count == 3 ? pair[2] : "0") else {
                return (false, "golden takes a route, a golden's name and, to hold the answer, its seconds")
            }
            do {
                try await GoldenDesk.shared.answer(pair[0], as: pair[1], after: .seconds(seconds))
                return (true, "")
            } catch {
                return (false, "\(pair[1]): \(error)")
            }
        }
        if let text = step.workspaces {
            let found = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.text.contains(text) }
            let focused = pane.window?.firstResponder === pane.project.list
            return (found, "the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | ")
                + "; selected \(pane.project.list.chosen ?? "none")\(focused ? ", the list focused" : ""), New Workspace \(pane.project.newWorkspace.isEnabled ? "enabled" : "disabled")")
        }
        if let wanted = step.listChosen {
            let found = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.project.list.chosen == wanted }
            return (found, "the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let projects = step.projects {
            do {
                try await GoldenDesk.shared.answerProjects(projects)
                return (true, "")
            } catch {
                return (false, "the projects golden: \(error)")
            }
        }
        if let name = step.takenName {
            do {
                try await GoldenDesk.shared.answerTaken(name)
                return (true, "")
            } catch {
                return (false, "the taken name: \(error)")
            }
        }
        if let project = step.noRows {
            do {
                try await GoldenDesk.shared.answerNoRows(project)
                return (true, "")
            } catch {
                return (false, "no rows: \(error)")
            }
        }
        if let count = step.rows {
            do {
                try await GoldenDesk.shared.answerRows(count)
                return (true, "")
            } catch {
                return (false, "the rows: \(error)")
            }
        }
        if let which = step.dragThumb {
            guard let view: QuietScrollView = which == "panel" ? pane.panel.scroll : pane.project.listScroll as? QuietScrollView, let window = view.window else {
                return (false, "no \(which) to drag")
            }
            view.flashScrollers()
            let scroller = view.scroller, thumb = scroller.thumb, drags = scroller.drags, before = view.contentView.bounds.minY
            guard thumb.height > 1 else { return (false, "the \(which)'s thumb is \(thumb): nothing to drag") }
            let from = scroller.convert(NSPoint(x: thumb.midX, y: thumb.midY), to: nil), to = NSPoint(x: Double(from.x), y: Double(from.y) - 30)
            func mouse(_ type: NSEvent.EventType, at point: NSPoint) -> NSEvent? {
                NSEvent.mouseEvent(
                    with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil,
                    eventNumber: 0, clickCount: 1, pressure: type == .leftMouseUp ? 0 : 1
                )
            }
            guard let down = mouse(.leftMouseDown, at: from), let drag = mouse(.leftMouseDragged, at: to), let up = mouse(.leftMouseUp, at: to) else { return (false, "AppKit gave no mouse events") }
            NSApp.postEvent(drag, atStart: false)
            NSApp.postEvent(up, atStart: false)
            window.sendEvent(down)
            await Task.yield()
            let moved = view.contentView.bounds.minY != before
            return (scroller.drags == drags + 1 && !scroller.dragging && moved, "drags \(scroller.drags - drags), the thumb \(scroller.dragging ? "still" : "no longer") drawn dragged, the list \(moved ? "moved" : "did not move") from \(before) to \(view.contentView.bounds.minY)")
        }
        if let scroll = step.scrollTo, scroll.count == 2, let fraction = Double(scroll[1]) {
            guard let view: NSScrollView = scroll[0] == "panel" ? pane.panel.scroll : pane.project.listScroll else { return (false, "no list to scroll") }
            let scrollable = await waitUntil(step.seconds ?? 10) {
                Double(view.documentView?.frame.height ?? 0) > Double(view.contentSize.height) + 1
            }
            guard scrollable else { return (false, "nothing to scroll: \(view.documentView?.frame.height ?? 0) pt of rows in \(view.contentSize.height) pt") }
            return await turnWheel(over: view, to: fraction)
        }
        if let route = step.unreachable {
            await GoldenDesk.shared.fail(route, unreached: true)
            return (true, "")
        }
        if let route = step.proxyFails {
            await GoldenDesk.shared.fail(route.isEmpty ? nil : route)
            return (true, "")
        }
        if let count = step.doorLists {
            guard let door = GoldenDesk.door else { return (false, "the drive's runner door did not listen") }
            let found = await waitUntil(step.seconds ?? 15) { door.sessions.count == count }
            return (found, "the runner's sessions: \(door.sessions)")
        }
        if step.doorHoldsWrite == true || step.doorReleasesWrite == true {
            guard let door = GoldenDesk.door, let focused = workbench.focused else { return (false, "no door or no focused terminal") }
            if step.doorHoldsWrite == true { door.holdWrite(focused.name) } else { door.release(focused.name) }
            return (true, "")
        }
        if let title = step.pressStatus {
            guard let focused = workbench.focused else { return (false, "no focused terminal") }
            return (focused.press(title), "the banner said \(focused.status.says ?? "nothing")")
        }
        if step.signOutNow == true {
            shell.account.signOutHere(nil)
            return (true, "")
        }
        if let open = step.createRowOpen {
            let shown = await waitUntil(step.seconds ?? 5) { (pane.project.field.window != nil) == open }
            return (shown, "the create row \(pane.project.field.window != nil ? "open" : "gone")")
        }
        if let keys = step.keysInField {
            let held = await waitUntil(step.seconds ?? 5) {
                pane.project.fieldHasKeys == keys && step.fieldHolds.map { pane.project.field.field.stringValue == $0 } != false
            }
            return (held, "the field \(pane.project.fieldHasKeys ? "holds" : "does not hold") the keys and the name \(pane.project.field.field.stringValue); first responder \(String(describing: shell.window.firstResponder.map { type(of: $0) }))")
        }
        if let said = step.whileBusy {
            let seen = await waitUntil(step.seconds ?? 5) { pane.busy && pane.text.contains(said) }
            return (seen, "\(pane.busy ? "under way" : "nothing under way"); the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let held = step.keysInList {
            let list = await waitUntil(step.seconds ?? 5) { (shell.window.firstResponder === pane.project.list) == held }
            return (list, "first responder \(String(describing: shell.window.firstResponder.map { type(of: $0) }))")
        }
        if let size = step.window, size.count == 2 {
            let frame = shell.window.frame
            shell.window.setFrame(NSRect(x: frame.minX, y: frame.maxY - size[1], width: size[0], height: size[1]), display: true)
            pane.layoutSubtreeIfNeeded()
            let now = shell.window.frame.size, reached = abs(Double(now.width) - size[0]) < 0.5 && abs(Double(now.height) - size[1]) < 0.5
            let sections: [(String, NSView)] = [("the rail", shell.rail as NSView), ("This Mac", shell.thisMac as NSView), ("Workspaces", pane as NSView), ("Account", shell.account as NSView)]
            let least = sections.map { "\($0.0) \($0.1.fittingSize.width) × \($0.1.fittingSize.height)" }.joined(separator: ", ")
            return (reached, "the window is \(now.width) × \(now.height)" + (reached ? "" : "; the least each section asks: " + least))
        }
        if let gone = step.notSaid {
            let went = await waitUntil(step.seconds ?? 10) { !pane.busy && !pane.text.contains(gone) }
            return (went, "the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let title = step.clickButton {
            return await clickButton(title, in: pane)
        }
        if let wanted = step.chooseProject {
            let listed = await waitUntil(step.seconds ?? 10) { Self.row(wanted, in: pane.panel.list) != nil }
            guard listed else { return (false, "the panel never listed \(wanted): chosen \(pane.panel.list.chosen ?? "none")") }
            guard await clickRow(wanted, in: pane.panel.list) else { return (false, "the panel's \(wanted) row could not be clicked") }
            let named = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.text.hasPrefix("Workspaces\n\(wanted)\n") }
            return (named, "chosen: \(pane.panel.list.chosen ?? "none"); the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let wanted = step.chooseRow {
            guard await clickRow(wanted, in: pane.project.list) else { return (false, "no row \(wanted) to click") }
            let chosen = await waitUntil(step.seconds ?? 5) { pane.project.list.chosen == wanted }
            return (chosen, "chosen: \(pane.project.list.chosen ?? "none")")
        }
        if let name = step.open {
            pane.project.list.choose(name)
            guard pane.project.list.chosen == name, let enter = Keys.named("enter", modifiers: []) else { return (false, "no row \(name)") }
            shell.window.makeFirstResponder(pane.project.list)
            send(enter)
            return (shell.rail.selected == .thisMac, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing"); \(arrangementSaid)")
        }
        if let shown = step.skeletons {
            let visible = { Self.found(Skeleton.self, in: pane).filter { !$0.isHiddenOrHasHiddenAncestor } }
            let found = await waitUntil(step.seconds ?? 5) { shown ? !visible().isEmpty && visible().allSatisfy { $0.alphaValue == 1 } : visible().isEmpty }
            let bars = visible()
            return (found, "\(bars.filter { $0.alphaValue == 1 }.count) of \(bars.count) skeleton bars shown; the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let shown = step.showSidebar {
            guard let key = Keys.named("s", modifiers: ["control", "command"]) else { return (false, "no ⌃⌘S") }
            send(key)
            let done = await waitUntil(step.seconds ?? 5) { pane.panel.isHidden != shown }
            return (done, "the panel \(pane.panel.isHidden ? "hidden" : "shown") after ⌃⌘S")
        }
        if let wanted = step.ticketAsked {
            let found = await waitUntilAsync(step.seconds ?? 15) { await Self.tickets().contains { asked in wanted.allSatisfy { asked[$0.key] == $0.value } } }
            let asked = await Self.tickets()
            return (found, "tickets asked: \(asked)")
        }
        if step.quit == true {
            // As ⌘Q arrives, from the run loop: a terminate answered "later" waits in a nested run
            // loop, and from a main-queue block that loop would starve the main queue, the drive's
            // own steps with it.
            NSApp.perform(#selector(NSApplication.terminate(_:)), with: nil, afterDelay: 0)
            return (true, "")
        }
        if let name = step.shotScreen {
            return await inBothLooks(name) { file in await self.capture(.screen, to: file) }
        }
        return nil
    }

    /// The ticket requests the desk was sent, each as its fields.
    private static func tickets() async -> [[String: String]] {
        await GoldenDesk.shared.asked.filter { $0.route == "POST /v1/terminal/tickets" }.compactMap { request in
            request.body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: String] }
        }
    }

    /// Waits, at most `seconds`, until `condition` holds, asking it again every 50 ms.
    /// Scrolls `view` to `fraction` of the way down with real scroll-wheel events in pixel steps, as
    /// a trackpad's are, sent through the window at the view's middle as a person's are, so the
    /// window's hit-testing routes them. Where AppKit gives the event no window (one made from a
    /// `CGEvent` may carry none), it is handed to the scroll view, and the result says so.
    private func turnWheel(over view: NSScrollView, to fraction: Double) async -> (Bool, String) {
        guard let window = view.window, let document = view.documentView else { return (false, "the scroll view is not in a window") }
        let room = Double(document.frame.height) - Double(view.contentSize.height)
        guard room > 0 else { return (false, "nothing to scroll: the rows are \(document.frame.height) pt in \(view.contentSize.height) pt") }
        let wanted = room * min(max(fraction, 0), 1)
        let middle = window.convertPoint(toScreen: view.convert(NSPoint(x: view.bounds.midX, y: view.bounds.midY), to: nil))
        let top = Double(NSScreen.screens.first?.frame.maxY ?? 0)
        var routed = true
        while Double(view.contentView.bounds.minY) < wanted - 0.5 {
            let step = Int32(-min(40, wanted - Double(view.contentView.bounds.minY)).rounded(.up))
            guard let wheel = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 1, wheel1: step, wheel2: 0, wheel3: 0) else { return (false, "no scroll-wheel event") }
            wheel.location = CGPoint(x: Double(middle.x), y: top - Double(middle.y))
            wheel.setIntegerValueField(.mouseEventWindowUnderMousePointer, value: Int64(window.windowNumber))
            wheel.setIntegerValueField(.mouseEventWindowUnderMousePointerThatCanHandleThisEvent, value: Int64(window.windowNumber))
            guard let event = NSEvent(cgEvent: wheel) else { return (false, "AppKit took no scroll-wheel event") }
            let before = view.contentView.bounds.minY
            if event.window === window {
                window.sendEvent(event)
            } else {
                routed = false
                view.scrollWheel(with: event)
            }
            await Task.yield()
            guard view.contentView.bounds.minY != before else { return (false, "the wheel moved nothing at \(before) of \(room) pt\(routed ? " through the window" : "")") }
        }
        view.flashScrollers()
        return (true, "scrolled \(view.contentView.bounds.minY) of \(room) pt, \(routed ? "through the window" : "handed to the scroll view: AppKit gave the wheel no window")")
    }

    /// Clicks the shown button titled `title` in `pane` as a person does.
    private func clickButton(_ title: String, in pane: WorkspacesView) async -> (Bool, String) {
        pane.layoutSubtreeIfNeeded()
        guard let button = Self.views(in: pane).compactMap({ $0 as? NSButton }).first(where: { $0.title == title && !$0.isHiddenOrHasHiddenAncestor }),
              await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY))
        else { return (false, "no \(title) shown") }
        return (true, "clicked \(title)")
    }

    /// Clicks the row of `id` in `list` near its leading edge, clear of the actions at its end.
    private func clickRow(_ id: String, in list: SailList) async -> Bool {
        list.layoutSubtreeIfNeeded()
        guard let row = Self.row(id, in: list) else { return false }
        let rect = list.rect(ofRow: row)
        return await click(list, at: NSPoint(x: rect.minX + 24, y: rect.minY + min(rect.height, 52) / 2))
    }

    private static func row(_ id: String, in list: SailList) -> Int? {
        list.entries.firstIndex { entry in
            guard case let .item(item) = entry else { return false }
            return item.id == id
        }
    }

    /// A click at `point` in `view` as a person makes it: a mouse-down sent through the window with
    /// its mouse-up queued behind it, so the view's own tracking takes both.
    private func click(_ view: NSView, at point: NSPoint) async -> Bool {
        guard let window = view.window else { return false }
        let location = view.convert(point, to: nil)
        func event(_ type: NSEvent.EventType) -> NSEvent? {
            NSEvent.mouseEvent(
                with: type, location: location, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil,
                eventNumber: 0, clickCount: 1, pressure: type == .leftMouseDown ? 1 : 0
            )
        }
        guard let down = event(.leftMouseDown), let up = event(.leftMouseUp) else { return false }
        NSApp.postEvent(up, atStart: false)
        window.sendEvent(down)
        await Task.yield()
        return true
    }

    private func waitUntilAsync(_ seconds: Double, _ condition: () async -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(failedSoFar ? min(seconds, Self.afterAFailure) : seconds)
        while !(await condition()) {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return true
    }
}
