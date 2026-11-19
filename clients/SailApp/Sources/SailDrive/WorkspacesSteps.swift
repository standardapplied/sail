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
    private func typeName(_ name: String, into pane: WorkspacesView) {
        pane.project.on(.newWorkspace)
        pane.project.field.field.stringValue = name
        pane.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: pane.project.field.field))
    }

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
        if let size = step.window, size.count == 2 {
            let frame = shell.window.frame
            shell.window.setFrame(NSRect(x: frame.minX, y: frame.maxY - size[1], width: size[0], height: size[1]), display: true)
            pane.layoutSubtreeIfNeeded()
            let now = shell.window.frame.size
            return (abs(Double(now.width) - size[0]) < 0.5 && abs(Double(now.height) - size[1]) < 0.5, "the window is \(now.width) × \(now.height)")
        }
        if let gone = step.notSaid {
            let went = await waitUntil(step.seconds ?? 10) { !pane.busy && !pane.text.contains(gone) }
            return (went, "the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let title = step.clickButton {
            return await clickButton(title, in: pane)
        }
        if let wanted = step.chooseProject {
            let listed = await waitUntil(step.seconds ?? 10) {
                pane.panel.list.choose(wanted)
                return pane.panel.list.chosen == wanted
            }
            guard listed else { return (false, "the panel never listed \(wanted): chosen \(pane.panel.list.chosen ?? "none")") }
            let named = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.text.hasPrefix("Workspaces\n\(wanted)\n") }
            return (named, "chosen: \(pane.panel.list.chosen ?? "none"); the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let wanted = step.chooseRow {
            shell.window.makeFirstResponder(pane.project.list)
            pane.project.list.choose(wanted)
            return (pane.project.list.chosen == wanted, "chosen: \(pane.project.list.chosen ?? "none")")
        }
        if let wanted = step.create {
            let before = pane.ended
            typeName(wanted, into: pane)
            pane.project.on(.create)
            let said = pane.text
            let done = await waitUntil(step.seconds ?? 10) { pane.ended > before }
            return (done, "while creating: \(said.replacingOccurrences(of: "\n", with: " | "))")
        }
        if let name = step.open {
            pane.project.list.choose(name)
            guard pane.project.list.chosen == name, let enter = Keys.named("enter", modifiers: []) else { return (false, "no row \(name)") }
            shell.window.makeFirstResponder(pane.project.list)
            send(enter)
            return (shell.rail.selected == .thisMac, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing"); \(arrangementSaid)")
        }
        if let act = step.act, let what = act.first {
            let name = act.dropFirst().first ?? ""
            if what == "tryAgain" {
                pane.askAgain(nil)
                return (true, "")
            }
            if what == "type" {
                typeName(name, into: pane)
                return (pane.project.field.field.stringValue == name, "the name field holds \(pane.project.field.field.stringValue)")
            }
            let done: ProjectView.Act? = switch what {
            case "startAndOpen": .startAndOpen(name)
            case "newWorkspace": .newWorkspace
            case "cancelCreate": .cancelCreate
            case "show": .show(name)
            case "listAgain": .listAgain
            default: nil
            }
            guard let done else { return (false, "no act \(what)") }
            pane.project.on(done)
            let open = { pane.project.field.window != nil }
            let shown = await waitUntil(step.seconds ?? 5) {
                switch done {
                case .newWorkspace: open()
                case .cancelCreate: !open()
                case let .show(name): pane.project.list.chosen == name
                default: true
                }
            }
            return (shown, "the create row \(open() ? "open" : "closed"); chosen \(pane.project.list.chosen ?? "none")")
        }
        if let shown = step.skeletons {
            let visible = { Self.found(Skeleton.self, in: pane).filter { !$0.isHiddenOrHasHiddenAncestor } }
            let found = await waitUntil(step.seconds ?? 5) { shown ? !visible().isEmpty && visible().allSatisfy { $0.alphaValue == 1 } : visible().isEmpty }
            let bars = visible()
            return (found, "\(bars.filter { $0.alphaValue == 1 }.count) of \(bars.count) skeleton bars shown; the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let shown = step.showSidebar {
            pane.toggleSidebar(nil)
            pane.layoutSubtreeIfNeeded()
            return (pane.panel.isHidden != shown, "the panel \(pane.panel.isHidden ? "hidden" : "shown")")
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
    /// Clicks the shown button titled `title` in `pane` as a person does: a mouse-down sent through
    /// the window with its mouse-up queued behind it, so the button's own tracking takes both.
    private func clickButton(_ title: String, in pane: WorkspacesView) async -> (Bool, String) {
        pane.layoutSubtreeIfNeeded()
        guard let button = Self.views(in: pane).compactMap({ $0 as? SailButton }).first(where: { $0.title == title && !$0.isHiddenOrHasHiddenAncestor }),
              let window = button.window
        else { return (false, "no \(title) shown") }
        let middle = button.convert(NSPoint(x: button.bounds.midX, y: button.bounds.midY), to: nil)
        func event(_ type: NSEvent.EventType) -> NSEvent? {
            NSEvent.mouseEvent(
                with: type, location: middle, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil,
                eventNumber: 0, clickCount: 1, pressure: type == .leftMouseDown ? 1 : 0
            )
        }
        guard let down = event(.leftMouseDown), let up = event(.leftMouseUp) else { return (false, "AppKit gave no mouse events for the click") }
        NSApp.postEvent(up, atStart: false)
        window.sendEvent(down)
        await Task.yield()
        return (true, "clicked \(title)")
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
