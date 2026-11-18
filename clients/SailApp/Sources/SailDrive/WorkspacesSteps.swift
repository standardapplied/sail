import AppKit
import Foundation
import SailAPI
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
    func fail(_ route: String?) {
        failing = route
    }

    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        asked.append(request)
        if let after = held[request.route], after > .zero { try? await Task.sleep(for: after) }
        guard request.route != failing else { throw .unexpected(route: request.route, status: 502) }
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

/// The drive's steps on the Workspaces pane and the runner's tabs, against ``GoldenDesk``.
extension Driver {
    /// Opens the create row and types `name` into its field as a person does: the field changes
    /// and says so.
    private func typeName(_ name: String, into pane: WorkspacesView) {
        pane.project.on(.newWorkspace)
        pane.project.field.field.stringValue = name
        pane.project.controlTextDidChange(Notification(name: NSControl.textDidChangeNotification, object: pane.project.field.field))
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
        if let wanted = step.chooseRow {
            pane.project.list.choose(wanted)
            return (pane.project.list.chosen == wanted, "chosen: \(pane.project.list.chosen ?? "none")")
        }
        if let wanted = step.createStarts {
            typeName(wanted, into: pane)
            pane.project.on(.create)
            return (pane.busy && !pane.project.newWorkspace.isEnabled, "while creating: \(pane.text.replacingOccurrences(of: "\n", with: " | "))")
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
            return (true, "")
        }
        if let width = step.cardWidth {
            let extra = Double(shell.window.frame.width) - Double(pane.frame.width)
            shell.window.setContentSize(NSSize(width: width + extra, height: Double(shell.window.contentLayoutRect.height)))
            pane.layoutSubtreeIfNeeded()
            return (Double(pane.frame.width) == width, "the card is \(pane.frame.width) pt; the panel \(pane.panel.isHidden ? "hidden" : "shown")")
        }
        if step.showSidebar == true {
            pane.toggleSidebar(nil)
            pane.layoutSubtreeIfNeeded()
            return (!pane.panel.isHidden, "the panel \(pane.panel.isHidden ? "hidden" : "shown")")
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
    private func waitUntilAsync(_ seconds: Double, _ condition: () async -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !(await condition()) {
            guard ContinuousClock.now < deadline else { return false }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return true
    }
}
