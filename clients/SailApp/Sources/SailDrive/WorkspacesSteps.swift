import AppKit
import Foundation
import SailAPI
import SailControls
import SailKitTestSupport
import SailShell
import SailTerminal
import SailTerminalWire
import SailWorkbench

/// The control plane of the drive's workspaces run: every route answered as the server answered
/// it in the goldens its `ClientGoldensTest` writes, a step choosing which golden answers a route
/// from then on; a ticket answered so carries a ticket of the drive's ``FakeRunnerDoor`` and its
/// address, so a runner tab attaches to a door that refuses what the real door refuses, and an attach
/// ticket for a session the door no longer holds is refused as the API refused one. A workspace's
/// sessions are the door's own listing. A step can put a proxy's 502 in front of one route. No other
/// answer is written here.
actor GoldenDesk: Transport {
    static let shared = GoldenDesk()
    static let door = try? FakeRunnerDoor(person: "uday")
    private var answers: [String: HTTPResponse] = [:]
    private var held: [String: Duration] = [:]
    private var failing: String?
    /// The failing route cannot be reached at all, in place of the proxy's 502.
    private var unreached = false
    private(set) var asked: [HTTPRequest] = []
    /// Each route as it is answered, after any hold: what the app has been told, in order.
    private(set) var answered: [String] = []
    private var holding: Set<String> = []
    private var heldAnswers: [String: [CheckedContinuation<Void, Never>]] = [:]

    /// From now on `route` is answered only once ``release(_:)`` names it.
    func hold(_ route: String) {
        holding.insert(route)
    }

    /// Gives `route`'s held answers now, and its later ones at once.
    func release(_ route: String) {
        holding.remove(route)
        heldAnswers.removeValue(forKey: route)?.forEach { $0.resume() }
    }

    init() {
        let first = [
            "health", "passkey-register-start", "passkey-register-finish", "passkey-login-start", "passkey-login-finish", "whoami", "projects", "workspaces",
            "workspace-create", "terminal-ticket-workspace",
        ]
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

    /// From now on a sign-in ends `days` from now: the recorded sign-in's answer, its `expires_at`
    /// moved.
    func signInEnds(in days: Double) throws {
        let ends = Date(timeIntervalSinceNow: days * 86_400).formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))
        let exchange = try Exchange("passkey-login-finish").altered(at: ["expires_at"], to: ends)
        answers["\(exchange.method) \(exchange.path)"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
        held["\(exchange.method) \(exchange.path)"] = .zero
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

    /// From now on dock's agents are the golden `workspace-agents-dock-no-login`'s with a third
    /// harness after them that no word of the app names: its own sign-in, words and key, as a
    /// server declaring it would.
    func answerMadeUp(_ madeUp: Step.MadeUpAgents) throws {
        let offers = madeUp.offers.map { name in
            Exchange.madeUpOffer(name, risky: madeUp.risky?.contains(name) == true, unavailable: madeUp.unavailable?.contains(name) == true ? "Not installed in harbor" : nil)
        }
        let exchange = try Exchange("workspace-agents-dock-no-login").offering(offers)
        answers["GET /v1/projects/harbor/workspaces/dock/agents"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
        held["GET /v1/projects/harbor/workspaces/dock/agents"] = .zero
    }

    /// From now on harbor's list is the one answered now with `name` stopped, or left out ("gone").
    func relist(_ name: String, as change: String) throws {
        let route = "GET /v1/projects/harbor/workspaces"
        guard let answer = answers[route], var body = try JSONSerialization.jsonObject(with: answer.body) as? [String: Any], var rows = body["workspaces"] as? [[String: Any]] else { return }
        if change == "gone" {
            rows.removeAll { $0["name"] as? String == name }
        } else if change.hasPrefix("owner:") {
            rows = rows.map { row in
                guard row["name"] as? String == name else { return row }
                var changed = row
                changed["owner"] = String(change.dropFirst("owner:".count))
                return changed
            }
        } else {
            rows = rows.map { row in
                guard row["name"] as? String == name else { return row }
                var changed = row
                changed["state"] = change
                return changed
            }
        }
        body["workspaces"] = rows
        answers[route] = HTTPResponse(status: answer.status, headers: answer.headers, body: try JSONSerialization.data(withJSONObject: body))
    }

    /// From now on a plain delete of `name` is refused as holding work, in the recorded sentence for
    /// dock with its name replaced.
    func refuseDelete(_ name: String) throws {
        let refusal = try Exchange("refusal-workspace-holds-work")
        let said = String(decoding: refusal.body, as: UTF8.self).replacingOccurrences(of: "dock", with: name)
        answers["DELETE /v1/projects/harbor/workspaces/\(name)"] = HTTPResponse(status: refusal.status, headers: refusal.headers, body: Data(said.utf8))
        held["DELETE /v1/projects/harbor/workspaces/\(name)"] = .zero
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
        if holding.contains(request.route) { await withCheckedContinuation { heldAnswers[request.route, default: []].append($0) } }
        defer { answered.append(request.route) }
        guard request.route != failing else { throw unreached ? SailError.unreachable("Could not connect to the server.") : SailError.unexpected(route: request.route, status: 502) }
        if request.method == .get, request.path.hasSuffix("/sessions"), let door = Self.door {
            return HTTPResponse(status: 200, headers: [:], body: Self.encoded(door.listing))
        }
        guard let answer = answers[request.route] else { throw .unreachable("no golden answers \(request.route)") }
        guard request.route == "POST /v1/terminal/tickets", answer.status == 201, let door = Self.door,
              var body = (try? JSONSerialization.jsonObject(with: answer.body)) as? [String: Any] else { return answer }
        let asked = Self.ticketRequest(request)
        if case let .attach(session) = asked, !door.sessions.contains(session), let gone = try? Exchange("refusal-not-found-session") {
            return HTTPResponse(status: gone.status, headers: gone.headers, body: gone.body)
        }
        let ticket = door.issue(for: asked)
        body["ticket"] = ticket.ticket
        body["address"] = ticket.address
        return HTTPResponse(status: answer.status, headers: answer.headers, body: (try? JSONSerialization.data(withJSONObject: body)) ?? answer.body)
    }

    /// `listing` as the API writes it: snake-case keys and instants with milliseconds.
    static func encoded(_ listing: RunnerSessions) -> Data {
        let encoder = JSONEncoder()
        let instant = ISO8601DateFormatter()
        instant.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        encoder.dateEncodingStrategy = .custom { date, to in
            var value = to.singleValueContainer()
            try value.encode(instant.string(from: date))
        }
        return (try? encoder.encode(listing)) ?? Data()
    }

    /// What a ticket request asks, read from its body as the API reads it: a door's ticket covers
    /// that and nothing else.
    static func ticketRequest(_ request: HTTPRequest) -> TicketRequest {
        let asked = (request.body.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]
        let project = asked["project"] as? String ?? "", workspace = asked["workspace"] as? String ?? ""
        switch asked["action"] as? String {
        case "attach": return .attach(session: asked["session"] as? String ?? "")
        case "files": return .files(project: project, workspace: workspace)
        default: return .open(project: project, workspace: workspace)
        }
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
        if let files = await performFiles(step) { return files }
        if let started = await performNewSession(step) { return started }
        if let stored = await performLoginSheet(step) { return stored }
        let pane = shell.workspaces
        if let madeUp = step.agentsMadeUp {
            do {
                try await GoldenDesk.shared.answerMadeUp(madeUp)
                return (true, "")
            } catch {
                return (false, "workspace-agents-dock-no-login: \(error)")
            }
        }
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
        if let change = step.relistAs, change.count == 2 {
            do {
                try await GoldenDesk.shared.relist(change[0], as: change[1])
                return (true, "")
            } catch {
                return (false, "the list could not be changed: \(error)")
            }
        }
        if let route = step.holdAnswers {
            await GoldenDesk.shared.hold(route)
            return (true, "")
        }
        if let route = step.releaseAnswers {
            await GoldenDesk.shared.release(route)
            return (true, "")
        }
        if let pair = step.saysWithoutFirst, pair.count == 2 {
            var first: String?
            let said = await waitUntil(step.seconds ?? 10) {
                if first == nil, pane.text.contains(pair[1]) { first = pane.text }
                return first != nil || pane.text.contains(pair[0])
            }
            let shown = said && first == nil
            return (shown, (first.map { "said '\(pair[1])' first: " + $0 } ?? "the pane says: " + pane.text).replacingOccurrences(of: "\n", with: " | "))
        }
        if let name = step.refuseDelete {
            do {
                try await GoldenDesk.shared.refuseDelete(name)
                return (true, "")
            } catch {
                return (false, "the refusal: \(error)")
            }
        }
        if let pair = step.answeredWhileSheetShows, pair.count == 2 {
            let said = { Self.texts(self.shell.window.attachedSheet?.contentView).joined(separator: " ") }
            let settled = await waitUntilAsync(step.seconds ?? 10) { await GoldenDesk.shared.answered.contains(pair[0]) || !said().contains(pair[1]) }
            let answered = await GoldenDesk.shared.answered.contains(pair[0])
            let shown = said().contains(pair[1])
            guard settled, answered, shown else {
                return (false, "not shown: \(answered ? "answered" : "not answered") \(pair[0]); the sheet says '\(said())'")
            }
            return (true, "\(pair[0]) answered while the sheet says '\(said())'")
        }
        if let wanted = step.deletesAsked {
            let deletes = { await GoldenDesk.shared.asked.map(\.route).filter { $0.hasPrefix("DELETE ") } }
            _ = await waitUntilAsync(step.seconds ?? 5) { await deletes().count >= wanted.count }
            let asked = await deletes()
            return (asked == wanted, "deletes asked: \(asked)")
        }
        if let count = step.questionsWaiting {
            let waiting = await waitUntil(step.seconds ?? 10) { pane.questionsWaiting == count }
            return (waiting, "\(pane.questionsWaiting) questions wait behind the sheet")
        }
        if let wanted = step.mayEndAProgram {
            let said = await waitUntil(step.seconds ?? 10) { self.workbench.focused?.mayEndAProgram == wanted }
            return (said, "the focused pane says ending it \(workbench.focused?.mayEndAProgram == true ? "may" : "may not") end a program")
        }
        if let pick = step.menuItem, pick.count == 2 {
            let list = pane.project.list
            guard await clickRow(pick[0], in: list), let row = Self.row(pick[0], in: list), let view = list.rowView(atRow: row, makeIfNecessary: false) else {
                return (false, "no row \(pick[0]) to open")
            }
            view.layoutSubtreeIfNeeded()
            guard let more = Self.views(in: view).compactMap({ $0 as? SailButton }).first(where: { $0.menu != nil && !$0.isHiddenOrHasHiddenAncestor }), let menu = more.menu,
                  let index = menu.items.firstIndex(where: { $0.title == pick[1] })
            else { return (false, "\(pick[0])'s ⋯ menu has no \(pick[1])") }
            var opened = false
            let choose = Timer(timeInterval: 0.3, repeats: false) { _ in
                MainActor.assumeIsolated {
                    opened = NSApp.windows.contains { $0.isVisible && $0.level.rawValue >= NSWindow.Level.popUpMenu.rawValue }
                    menu.cancelTracking()
                    menu.performActionForItem(at: index)
                }
            }
            RunLoop.main.add(choose, forMode: .common)
            guard await click(more, at: NSPoint(x: more.bounds.midX, y: more.bounds.midY)) else { return (false, "⋯ could not be clicked") }
            return (opened, opened ? "chose \(pick[1]) from \(pick[0])'s menu" : "the click on ⋯ opened no menu")
        }
        if let title = step.sheetClick {
            let asked = await waitUntil(step.seconds ?? 5) { self.shell.window.attachedSheet != nil }
            guard asked, let sheet = shell.window.attachedSheet, let content = sheet.contentView,
                  let button = Self.views(in: content).compactMap({ $0 as? NSButton }).first(where: { $0.title == title })
            else { return (false, "no sheet asks with \(title)") }
            guard await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY)) else { return (false, "\(title) could not be clicked") }
            let gone = await waitUntil(5) { self.shell.window.attachedSheet !== sheet }
            return (gone, gone ? "clicked \(title); the sheet went" : "clicked \(title); the sheet stayed")
        }
        if let which = step.dragThumb {
            guard let view: QuietScrollView = which == "panel" ? pane.panel.scroll : pane.project.listScroll as? QuietScrollView, let window = view.window else {
                return (false, "no \(which) to drag")
            }
            view.flashScrollers()
            let scroller = view.scroller, thumb = scroller.thumb, drags = scroller.drags, before = view.contentView.bounds.minY
            guard thumb.height > 1 else { return (false, "the \(which)'s thumb is \(thumb): nothing to drag") }
            let from = clickPoint(NSPoint(x: thumb.midX, y: thumb.midY), in: scroller), to = NSPoint(x: Double(from.x), y: Double(from.y) - 30)
            func mouse(_ type: NSEvent.EventType, at point: NSPoint) -> NSEvent? {
                NSEvent.mouseEvent(
                    with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil,
                    eventNumber: 0, clickCount: 1, pressure: type == .leftMouseUp ? 0 : 1
                )
            }
            guard let down = mouse(.leftMouseDown, at: from), let drag = mouse(.leftMouseDragged, at: to), let up = mouse(.leftMouseUp, at: to) else { return (false, "AppKit gave no mouse events") }
            // AppKit's tracking loop runs the common modes' timers: one samples the thumb while the
            // drag is held, one lets go of it.
            var sampled = 0, drawnDragged = 0
            let sampler = Timer(timeInterval: 0.005, repeats: true) { _ in
                MainActor.assumeIsolated {
                    sampled += 1
                    if scroller.dragging { drawnDragged += 1 }
                }
            }
            let letGo = Timer(timeInterval: 0.08, repeats: false) { _ in
                MainActor.assumeIsolated { NSApp.postEvent(up, atStart: false) }
            }
            RunLoop.main.add(sampler, forMode: .common)
            RunLoop.main.add(letGo, forMode: .common)
            NSApp.postEvent(drag, atStart: false)
            window.sendEvent(down)
            sampler.invalidate()
            await Task.yield()
            let moved = view.contentView.bounds.minY != before
            let held = drawnDragged > 0, released = !scroller.dragging
            return (scroller.drags == drags + 1 && held && released && moved, "the thumb drawn dragged in \(drawnDragged) of \(sampled) samples while held, \(released ? "released" : "still dragged") after; the list \(moved ? "moved" : "did not move") from \(before) to \(view.contentView.bounds.minY)")
        }
        if let scroll = step.scrollTo, scroll.count == 2, let fraction = Double(scroll[1]) {
            guard let view: NSScrollView = scroll[0] == "panel" ? pane.panel.scroll : pane.project.listScroll else { return (false, "no list to scroll") }
            let scrollable = await waitUntil(step.seconds ?? 10) {
                Double(view.documentView?.frame.height ?? 0) > Double(view.contentSize.height) + 1
            }
            guard scrollable else { return (false, "nothing to scroll: \(view.documentView?.frame.height ?? 0) pt of rows in \(view.contentSize.height) pt") }
            return await turnWheel(over: view, to: fraction)
        }
        if step.panelRowsWhole == true {
            let blank = pane.panel.list.blankRows, shown = pane.panel.list.rows(in: pane.panel.list.visibleRect).length
            return (blank.isEmpty, blank.isEmpty ? "each of the \(shown) sidebar rows on screen holds its own content" : "sidebar rows gone blank: \(blank)")
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
        if let session = step.doorHolds {
            guard let door = GoldenDesk.door else { return (false, "the drive's runner door did not listen") }
            door.hold(session)
            return (true, "")
        }
        if step.doorHolderLeaves == true {
            guard let door = GoldenDesk.door, let focused = workbench.focused else { return (false, "no door or no focused terminal") }
            door.leaveKeyboard(focused.name)
            return (true, "")
        }
        if step.doorTakesKeyboard == true {
            guard let door = GoldenDesk.door, let focused = workbench.focused else { return (false, "no door or no focused terminal") }
            door.takeKeyboard(focused.name, by: "uday")
            return (true, "")
        }
        if let reason = step.doorEnds {
            guard let door = GoldenDesk.door else { return (false, "the drive's runner door did not listen") }
            door.sessions.forEach { door.end($0, saying: reason) }
            return (true, "")
        }
        if let title = step.pressStatus {
            guard let focused = workbench.focused else { return (false, "no focused terminal") }
            let said = focused.status.says ?? "nothing"
            focused.layoutSubtreeIfNeeded()
            guard let button = Self.views(in: focused).compactMap({ $0 as? NSButton }).first(where: { $0.title == title && !$0.isHiddenOrHasHiddenAncestor }),
                  await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY))
            else { return (false, "no \(title) on the card, which said \(said)") }
            return (true, "clicked \(title) on the card, which said \(said)")
        }
        if step.signOutNow == true {
            shell.account.signOutHere(nil)
            return (true, "")
        }
        if let name = step.askingWhoIsSignedIn {
            return await askingWhoIsSignedIn(name)
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
        if let size = step.window ?? (step.leastWindow == true ? [Double(ShellApp.leastFrame.width), Double(ShellApp.leastFrame.height)] : nil), size.count == 2 {
            let frame = shell.window.frame
            layoutBeforeResize = workspacesLayout
            shell.window.setFrame(NSRect(x: frame.minX, y: frame.maxY - size[1], width: size[0], height: size[1]), display: true)
            pane.layoutSubtreeIfNeeded()
            let now = shell.window.frame.size, reached = abs(Double(now.width) - size[0]) < 0.5 && abs(Double(now.height) - size[1]) < 0.5
            let sections: [(String, NSView)] = [
                ("the rail", shell.rail as NSView), ("Terminals", shell.thisMac as NSView), ("Workspaces", pane as NSView), ("the welcome", shell.welcome as NSView),
                ("Account", shell.account as NSView),
            ]
            let least = sections.map { "\($0.0) \($0.1.fittingSize.width) × \($0.1.fittingSize.height)" }.joined(separator: ", ")
            let room = shell.window.screen?.visibleFrame.size ?? .zero
            return (reached, "the window is \(now.width) × \(now.height)"
                + (reached ? "" : "; the screen leaves windows \(room.width) × \(room.height); the least each section asks: " + least))
        }
        if let gone = step.notSaid {
            let went = await waitUntil(step.seconds ?? 10) { !pane.busy && !pane.text.contains(gone) }
            return (went, "the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let title = step.clickButton {
            return await clickButton(title)
        }
        if let wanted = step.chooseProject {
            let listed = await waitUntil(step.seconds ?? 10) { Self.row(wanted, in: pane.panel.list) != nil }
            guard listed else { return (false, "the panel never listed \(wanted): chosen \(pane.panel.list.chosen ?? "none")") }
            guard await clickRow(wanted, in: pane.panel.list) else { return (false, "the panel's \(wanted) row could not be clicked") }
            let named = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.text.hasPrefix("Workspaces\n\(wanted)\n") }
            return (named, "chosen: \(pane.panel.list.chosen ?? "none"); the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let wanted = step.panelHeads {
            let heads = {
                pane.panel.list.entries.compactMap { entry -> String? in
                    guard case let .head(title, count, _) = entry else { return nil }
                    return count.map { "\(title) \($0)" } ?? title
                }
            }
            let found = await waitUntil(step.seconds ?? 10) { !pane.panel.isHidden && heads() == wanted }
            return (found, "the panel's heads: \(heads())")
        }
        if let host = step.panelHost {
            let signedIn = WorkspacesModel.Person(shell.flow.standing)
            pane.person = host.isEmpty ? signedIn : WorkspacesModel.Person(handle: signedIn.handle, admin: signedIn.admin, confirmed: signedIn.confirmed, controlPlane: host)
            return (!signedIn.handle.isEmpty, "Workspaces is \(signedIn.handle)'s at \(pane.person.controlPlane)")
        }
        if let title = step.chooseSession {
            let list = pane.local.list, chosen = await chooseSession(title)
            return (chosen, "chosen: \(list.chosen ?? "none"), the list \(list.window?.firstResponder === list ? "holding" : "not holding") the keys; the pane says: "
                + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let title = step.openSession {
            guard await chooseSession(title), let enter = Keys.named("enter", modifiers: []) else { return (false, "This Mac lists no session \(title) to open") }
            send(enter)
            let bench = shell.thisMac
            let opened = await waitUntil(step.seconds ?? 5) { self.shell.rail.selected == .terminals && bench.model.selectedTab.map { bench.model.title(of: $0.id) } == title }
            return (opened, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing"); \(arrangementSaid)")
        }
        if step.nothingNamesThisMac == true {
            let names = [WorkbenchView.hostName, pane.local.macName].filter { !$0.isEmpty }, asked = await GoldenDesk.shared.asked
            let named = asked.filter { request in
                [request.path, String(decoding: request.body ?? Data(), as: UTF8.self)].contains { sent in
                    (["This Mac", "This%20Mac", "This+Mac"] + names).contains { sent.contains($0) }
                }
            }
            return (named.isEmpty, "of \(asked.count) requests, these name This Mac or this Mac's host or name: \(named.map(\.route))")
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
            return (shell.rail.selected == .terminals, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing"); \(arrangementSaid)")
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
        return await performPanel(step)
    }

    /// The left panel's tree: a project opened or shut, its rows, a row chosen or under the pointer,
    /// and a row's + opening New Session beside it.
    private func performPanel(_ step: Step) async -> (Bool, String)? {
        let pane = shell.workspaces, panel = pane.panel, list = panel.list
        let rows = { list.entries.compactMap { entry -> String? in if case let .item(item) = entry, item.id != WorkspacesModel.thisMac { item.name } else { nil } } }
        let cell = { (id: String) -> SidebarRowCell? in
            Self.row(id, in: list).flatMap { list.rowView(atRow: $0, makeIfNecessary: false) }.flatMap { Self.found(SidebarRowCell.self, in: $0).first }
        }
        if let name = step.panelDisclose {
            guard let disclosure = cell(name)?.disclosure else { return (false, "\(name) shows no disclosure; the panel's rows: \(rows())") }
            let was = disclosure.open
            guard await click(disclosure, at: NSPoint(x: disclosure.bounds.midX, y: disclosure.bounds.midY)) else { return (false, "\(name)'s disclosure could not be clicked") }
            let turned = await waitUntil(step.seconds ?? 10) { cell(name)?.disclosure?.open == !was }
            return (turned, "\(name) \(cell(name)?.disclosure?.open == true ? "open" : "shut"); the panel's rows: \(rows())")
        }
        if let wanted = step.panelRows {
            let found = await waitUntil(step.seconds ?? 10) { rows() == wanted }
            return (found, "the panel's rows: \(rows())")
        }
        if let id = step.panelChoose {
            guard await clickRow(id, in: list) else { return (false, "the panel's \(id) could not be clicked; its rows: \(rows())") }
            let workspace = id.split(separator: "/").last.map(String.init)
            let chosen = await waitUntil(step.seconds ?? 5) { list.chosen == id && pane.project.list.chosen == workspace }
            return (chosen, "the panel chose \(list.chosen ?? "none"), the page \(pane.project.list.chosen ?? "none")")
        }
        if let id = step.panelHover {
            list.point(at: id.isEmpty ? nil : id)
            guard !id.isEmpty else { return (true, "the pointer given back") }
            let shown = await waitUntil(step.seconds ?? 5) { cell(id)?.plus?.isHidden == false }
            return (shown, "\(id)'s + \(shown ? "shows" : "does not show") under the pointer")
        }
        if let id = step.panelPlus {
            let parts = id.split(separator: "/").map(String.init)
            guard parts.count == 2, let plus = panel.plus(parts[0], parts[1]) else { return (false, "\(id) shows no +; the panel's rows: \(rows())") }
            guard await click(plus, at: NSPoint(x: plus.bounds.midX, y: plus.bounds.midY)) else { return (false, "\(id)'s + could not be clicked") }
            let opened = await waitUntil(step.seconds ?? 5) { !pane.busy && pane.newSession.isOpen }
            return (opened, "New Session \(opened ? "open for \(pane.newSession.workspace)" : "closed"); \(workspacesLayout)")
        }
        if let id = step.newSessionBeside {
            let parts = id.split(separator: "/").map(String.init), box = pane.newSession
            let fault = { () -> String? in
                guard parts.count == 2, box.isOpen, let window = pane.window, let row = panel.rowFrame(parts[0], parts[1]).map(window.convertToScreen) else { return "closed, or no row \(id)" }
                let shown = Self.shown(pane, in: window).insetBy(dx: NewSessionPanel.inset - 0.5, dy: NewSessionPanel.inset - 0.5), frame = box.popover.frame
                guard shown.contains(frame) else { return "the box \(frame) leaves the window less 8 pt \(shown)" }
                return abs(Double(frame.maxY) - Double(row.maxY)) < 0.5 ? nil : "the box's top \(frame.maxY) is not the row's \(row.maxY)"
            }
            let stands = await waitUntil(step.seconds ?? 5) { fault() == nil }
            return (stands, (fault() ?? "the box \(box.popover.frame) stands beside \(id)") + "; \(workspacesLayout)")
        }
        return nil
    }

    /// What the window shows of `page` on screen, measured apart from the New session panel's own
    /// measure, so the check checks it: the page's bounds, cut by the window's content.
    static func shown(_ page: NSView, in window: NSWindow) -> NSRect {
        window.convertToScreen(page.convert(page.bounds, to: nil)).intersection(window.convertToScreen(window.contentLayoutRect))
    }

    /// Where the Workspaces section stands, every frame in window coordinates: the window's on
    /// screen, the card's, the section's, the sidebar's, the page's and what the window shows of it
    /// (on screen, `shown`), the page's direction, the backing scale and the panel's box while it
    /// is open.
    var workspacesLayout: String {
        let window = shell.window, pane = shell.workspaces, project = pane.project
        let frame = { (view: NSView?) in view.map { "\($0.convert($0.bounds, to: nil))" } ?? "none" }
        let shows = Self.shown(project, in: window)
        return "the window \(window.frame), the card \(frame(pane.superview)), the section \(frame(pane)), the sidebar "
            + (pane.panel.isHidden ? "hidden" : frame(pane.panel)) + ", the page \(frame(project)), what shows of it \(shows), "
            + (project.userInterfaceLayoutDirection == .rightToLeft ? "right to left" : "left to right") + ", scale \(window.backingScaleFactor), the box "
            + (pane.newSession.isOpen ? "\(pane.newSession.popover.frame)" : "closed")
    }

    /// A1's steps: a row's + and ⋯, the New Session panel and what its Start asks of the runner.
    private func performNewSession(_ step: Step) async -> (Bool, String)? {
        let pane = shell.workspaces, panel = pane.newSession, list = pane.project.list
        let said = { panel.isOpen ? "the panel says: " + panel.view.words.joined(separator: " | ") : "the panel is closed" }
        panel.onOpen = { [weak self] in self?.layoutAtOpen = self?.workspacesLayout ?? "" }
        if let shows = step.rowShows, let name = shows.first {
            guard await clickRow(name, in: list) else { return (false, "no row \(name) to click") }
            let titles = { Self.rowButtons(name, in: list).map(\.title) }
            let found = await waitUntil(step.seconds ?? 5) { titles() == Array(shows.dropFirst()) }
            return (found, "\(name) shows \(titles())")
        }
        if let pick = step.clickRowButton, pick.count == 2 {
            guard let button = Self.rowButtons(pick[0], in: list).first(where: { $0.title == pick[1] }) else {
                return (false, "\(pick[0]) shows no \(pick[1]): \(Self.rowButtons(pick[0], in: list).map(\.title))")
            }
            guard await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY)) else { return (false, "\(pick[1]) could not be clicked") }
            return (true, "clicked \(pick[1]) on \(pick[0])")
        }
        if let words = step.newSessionSays {
            let found = await waitUntil(step.seconds ?? 5) { !pane.busy && panel.isOpen && Self.inOrder(words, in: panel.view.words) }
            return (found, said())
        }
        if let name = step.newSessionChosen {
            let chosen = { panel.view.rows.first(where: \.chosen)?.name.stringValue }
            let found = await waitUntil(step.seconds ?? 5) { panel.isOpen && chosen() == name }
            return (found, "chosen: \(chosen() ?? "none"); " + said())
        }
        if step.newSessionNoPrimary == true {
            let none = await waitUntil(step.seconds ?? 5) { panel.isOpen && panel.view.primary == nil }
            return (none, "the primary: \(panel.view.primary?.title ?? "none"); " + said())
        }
        if let names = step.newSessionRows {
            let rows = { panel.view.rows.map(\.name.stringValue) }
            let found = await waitUntil(step.seconds ?? 5) { panel.isOpen && rows() == names }
            return (found, "the rows: \(rows()); " + said())
        }
        if let words = step.newSessionWarns {
            let found = await waitUntil(step.seconds ?? 5) { panel.isOpen && (panel.view.warned ?? "") == words }
            return (found, "warned: \(panel.view.warned ?? "nothing"); " + said())
        }
        if let name = step.newSessionHover {
            let view = panel.view, row = { view.rows.first { $0.name.stringValue == name } }
            guard !name.isEmpty else {
                view.pointer = nil
                return (true, "the pointer given back")
            }
            guard panel.isOpen, row() != nil else { return (false, "no row \(name) to hold the pointer over; " + said()) }
            view.pointer = { row().map { $0.convert(NSPoint(x: $0.bounds.midX, y: $0.bounds.midY), to: nil) } }
            view.pointed()
            let hovered = await waitUntil(step.seconds ?? 5) { row()?.hovered == true }
            return (hovered, "\(name) \(hovered ? "is" : "is not") under the pointer; hovered: \(view.rows.filter(\.hovered).map(\.name.stringValue))")
        }
        if step.newSessionInPage == true {
            let project = pane.project
            let inPage = { () -> String? in
                guard panel.isOpen, let window = project.window else { return "the panel is closed" }
                let page = Self.shown(project, in: window).insetBy(dx: NewSessionPanel.inset - 0.5, dy: NewSessionPanel.inset - 0.5), box = panel.popover.frame
                guard page.contains(box) else { return "the box \(box) leaves the page less 8 pt \(page)" }
                guard let chosen = panel.view.rows.first(where: \.chosen), panel.view.list.contentView.documentVisibleRect.insetBy(dx: -0.5, dy: -0.5).contains(chosen.frame) else { return "the chosen row is not whole in view" }
                return nil
            }
            let stands = await waitUntil(step.seconds ?? 5) { inPage() == nil }
            let layouts = "; before the last resize: \(layoutBeforeResize); at its opening: \(layoutAtOpen); now: \(workspacesLayout)"
            return (stands, (inPage() ?? "the box \(panel.popover.frame) stands in the page") + layouts)
        }
        if let primary = step.newSessionPrimary, primary.count == 2 || primary.count == 3 {
            let button = { panel.view.primary }
            let keys = primary.count == 3
            let found = await waitUntil(step.seconds ?? 5) { button()?.title == primary[0] && button()?.isEnabled == (primary[1] == "on") && button()?.holdsKeys == keys }
            return (found, "the primary: \(button().map { "\($0.title), \($0.isEnabled ? "on" : "off")\($0.holdsKeys ? ", holding the keys" : "")" } ?? "none")")
        }
        if let title = step.newSessionClick {
            let view = panel.view
            let controls: [NSView] = view.rows + [view.primary, view.secondary, view.modes, view.signIn].compactMap { $0 }
            let named = { (control: NSView) -> String in (control as? NewSessionRow)?.name.stringValue ?? (control as? NSButton)?.title ?? "" }
            guard panel.isOpen, let target = controls.first(where: { named($0) == title }) else { return (false, "the panel shows no \(title); " + said()) }
            guard await click(target, at: NSPoint(x: target.bounds.midX, y: target.bounds.midY)) else { return (false, "\(title) could not be clicked") }
            return (true, "clicked \(title)")
        }
        if let open = step.newSessionOpen {
            let found = await waitUntil(step.seconds ?? 5) { panel.isOpen == open }
            let keys = shell.window.firstResponder.map { String(describing: type(of: $0)) } ?? "nothing"
            return (found, said() + "; the keys are with \(keys)")
        }
        if let label = step.newSessionMode {
            guard panel.isOpen, let menu = panel.view.modes?.menu, let index = menu.items.firstIndex(where: { $0.title == label }), let key = Keys.named("m", modifiers: ["command"]) else {
                return (false, "no mode \(label) to choose; " + said())
            }
            let (opened, chosen) = await pressing(key, choose: index, in: menu, seconds: step.seconds ?? 5) { panel.view.modes?.title == label }
            return (opened && chosen, (opened ? "⌘M opened the modes" : "⌘M opened no menu") + "; " + said())
        }
        if let title = step.newSessionSignIn {
            return await signInByKeys(panel, choosing: title, said: said)
        }
        if let name = step.newSessionSignInShot {
            guard panel.isOpen, panel.view.signIn != nil else { return (false, "no Sign in menu to shoot; " + said()) }
            return await menuShot(name) { panel.view.signIn }
        }
        if let name = step.newSessionModesShot {
            guard panel.isOpen, panel.view.modes != nil else { return (false, "no mode menu to shoot; " + said()) }
            return await menuShot(name) { panel.view.modes }
        }
        if let wanted = step.doorCreated, wanted.count == 3 {
            guard let door = GoldenDesk.door, let name = workbench.focused?.name else { return (false, "no door or no focused tab") }
            let found = await waitUntil(step.seconds ?? 10) { door.create(of: name) != nil }
            guard found, let create = door.create(of: name) else { return (false, "the door was asked no Create for \(name)") }
            let agent = [create.agent.harness, create.agent.mode, create.agent.inside ? "inside" : "stored"]
            return (agent == wanted && create.command.isEmpty, "\(name)'s Create named \(agent) and the command \(create.command)")
        }
        if let holding = step.doorHoldsCreates {
            guard let door = GoldenDesk.door else { return (false, "the drive's runner door did not listen") }
            if holding { door.holdCreates() } else { door.releaseCreates() }
            return (true, "")
        }
        if let refusal = step.doorRefusesCreates {
            guard let door = GoldenDesk.door, refusal.isEmpty || refusal.count == 2 else { return (false, "no door, or not [code, sentence]") }
            door.refuseCreates(with: refusal.isEmpty ? nil : HostRefusal(message: refusal[1], remedy: [], code: refusal[0]))
            return (true, "")
        }
        if step.newSessionModesDismissed == true {
            guard panel.isOpen, let menu = panel.view.modes?.menu, let before = panel.view.modes?.title, let key = Keys.named("m", modifiers: ["command"]) else { return (false, "no mode menu; " + said()) }
            var opened = false
            let dismiss = Timer(timeInterval: 0.3, repeats: false) { _ in
                MainActor.assumeIsolated {
                    opened = NSApp.windows.contains { $0.isVisible && $0.level.rawValue >= NSWindow.Level.popUpMenu.rawValue }
                    menu.cancelTracking()
                }
            }
            RunLoop.main.add(dismiss, forMode: .common)
            send(key)
            let kept = await waitUntil(step.seconds ?? 2) { panel.isOpen && panel.view.modes?.title == before }
            return (opened && kept, (opened ? "the mode menu opened and was put away" : "⌘M opened no menu") + "; " + said())
        }
        if let title = step.newSessionStartTwice {
            guard panel.isOpen, let enter = Keys.named("enter", modifiers: []) else { return (false, said()) }
            send(enter)
            send(enter)
            let titles = { self.workbench.model.tabs.map { self.workbench.model.title(of: $0.id) } }
            let once = await waitUntil(step.seconds ?? 5) { !panel.isOpen && titles() == [title] }
            return (once, "two Returns sent at once; the tabs are \(titles()); the keys are with \(shell.window.firstResponder.map { String(describing: type(of: $0)) } ?? "nothing")")
        }
        if step.newSessionModesEscape == true {
            return await modesEscape(panel)
        }
        if let pick = step.shadowWhole, pick.count == 2 {
            let popover = pick[1] == "userMenu" ? shell.userMenu.popover : panel.popover
            guard popover.isOpen else { return (false, "no \(pick[1]) open") }
            var faults: [String] = [], read: [String] = []
            for look in ["light", "dark"] {
                let file = out.deletingLastPathComponent().appending(path: "window").appending(path: "\(pick[0])-\(look).png")
                guard let data = try? Data(contentsOf: file), let bitmap = NSBitmapImageRep(data: data) else {
                    faults.append("no \(file.lastPathComponent)")
                    continue
                }
                faults += Self.shadowCut(in: bitmap, of: popover, over: shell.window).map { "\(look): \($0)" }
                read.append(file.lastPathComponent)
            }
            return (faults.isEmpty, faults.isEmpty ? "the shadow is whole in \(read.joined(separator: " and "))" : faults.joined(separator: "; "))
        }
        if let rightToLeft = step.workspacesRightToLeft {
            Self.direct(pane, rightToLeft: rightToLeft)
            [list, pane.panel.list, pane.local.list].forEach { $0.reloadData() }
            pane.layoutSubtreeIfNeeded()
            return (true, "the section is laid out \(rightToLeft ? "right to left" : "left to right")")
        }
        return nil
    }

    /// ⇥ to Sign in, its ring checked, then Space to open its menu, and the item titled `title`
    /// chosen while AppKit tracks it: Sign in then reads it, or for Store a Key, the sheet is asked.
    private func signInByKeys(_ panel: NewSessionPanel, choosing title: String, said: () -> String) async -> (Bool, String) {
        guard panel.isOpen, let tab = Keys.named("tab", modifiers: []), let space = Keys.named(" ", modifiers: []) else { return (false, "no panel, or no ⇥ or Space; " + said()) }
        send(tab)
        let ringed = await waitUntil(2) { panel.signInFocused && panel.view.signIn?.holdsKeys == true }
        guard ringed, let menu = panel.view.signIn?.menu, let index = menu.items.firstIndex(where: { $0.title == title }) else {
            return (false, (ringed ? "Sign in offers no \(title)" : "⇥ did not reach Sign in") + "; " + said())
        }
        let (opened, chosen) = await pressing(space, choose: index, in: menu, seconds: 5) {
            title == NewSessionWords.storeKey ? panel.loginSheet != nil : panel.view.signIn?.title == title
        }
        return (opened && chosen, (opened ? "Space opened Sign in" : "Space opened no menu") + "; " + said())
    }

    /// `key` pressed to open `menu`, and its item at `index` chosen while AppKit tracks it: whether
    /// a menu opened, and whether `chosen` came true within `seconds`.
    private func pressing(_ key: Keys.Key, choose index: Int, in menu: NSMenu, seconds: Double, until chosen: @MainActor () -> Bool) async -> (opened: Bool, chosen: Bool) {
        var opened = false
        let choose = Timer(timeInterval: 0.3, repeats: false) { _ in
            MainActor.assumeIsolated {
                opened = NSApp.windows.contains { $0.isVisible && $0.level.rawValue >= NSWindow.Level.popUpMenu.rawValue }
                menu.cancelTracking()
                menu.performActionForItem(at: index)
            }
        }
        RunLoop.main.add(choose, forMode: .common)
        send(key)
        let done = await waitUntil(seconds, chosen)
        return (opened, done)
    }

    /// The menu of the pop-up `button` gives, shot open over the whole screen in both looks.
    private func menuShot(_ name: String, of button: @MainActor () -> SailButton?) async -> (Bool, String) {
        await inBothLooks(name) { file in
            guard let button = button(), let menu = button.menu, let shot = Shot.begin(.menu, to: file) else { return }
            menu.perform(#selector(NSMenu.cancelTracking), with: nil, afterDelay: 2.5, inModes: [.common])
            MenuOpener.shared.open(button)
            await shot.taken()
        }
    }

    /// The Workspaces card while the app checks the session it holds, on the pane already chosen
    /// (`waitForSignIn(at:)` is what a launch calls; the launch's own order, the mark made under
    /// the hidden pane and then shown, is `ControlsTests.theMarkDrawsOnlyWhileItCanBeSeen`'s): Sail's
    /// mark alone in the card, the same mark in each look and in that look, and what it draws centred
    /// on the card's centre: the sigma's ink and the label's, each, as AppKit draws the window's
    /// content at 1x and at 2x, within half a backing pixel (or half a drawn pixel where that is
    /// coarser), so a one-point miss fails at either. The 2x drawing is `<name>-<look>.png`. Then the
    /// mark draws itself with motion on and holds still with it reduced, whatever the runner's
    /// setting (GitHub's macOS images turn Reduce Motion on), which is put back after.
    private func askingWhoIsSignedIn(_ name: String) async -> (Bool, String) {
        let pane = shell.workspaces, host = shell.flow.memory.plane?.named ?? ""
        pane.waitForSignIn(at: host)
        shell.select(.workspaces)
        guard pane.text == "Workspaces\n\(WorkspacesView.connecting(to: host))", !pane.isHiddenOrHasHiddenAncestor, pane.card.subviews.count == 1,
              let mark = pane.card.subviews.first as? BrandMark
        else {
            return (false, "asking, the card holds \(pane.card.subviews) and says \(pane.text)")
        }
        var held = true, said: [String] = []
        let (taken, shots) = await inBothLooks(name) { file in
            pane.layoutSubtreeIfNeeded()
            let same = pane.card.subviews.count == 1 && pane.card.subviews.first === mark && mark.look == self.shell.look
            let centring = [1.0, 2.0].map { scale -> (Bool, String) in
                let image = Self.drawn(self.shell.window.contentView, at: scale)
                if scale == 2 { try? image?.representation(using: .png, properties: [:])?.write(to: file) }
                return self.centring(of: mark, on: pane.card, in: image, at: scale)
            }
            held = held && same && centring.allSatisfy(\.0)
            said.append("\(self.shell.look == .dark ? "dark" : "light"): " + centring.map(\.1).joined(separator: "; ") + ", \(mark.isDrawing ? "drawing" : "still")"
                + (same ? "" : ", not the same mark in this look"))
        }
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = false
        let draws = mark.isDrawing
        motion.reduced = true
        let stands = !mark.isDrawing
        let moves = "Reduce Motion \(was ? "on" : "off") here; with motion on the mark \(draws ? "draws" : "does not draw"), reduced it \(stands ? "stands still" : "moves")"
        return (held && taken && draws && stands, said.joined(separator: "; ") + "; " + moves + "; " + shots)
    }

    /// Where the sigma's ink and the label's stand from `card`'s centre, in points, in `image` (the
    /// window's content drawn at `scale`), and whether each is within half a backing pixel, or half
    /// a drawn pixel where that is coarser.
    private func centring(of mark: BrandMark, on card: NSView, in image: NSBitmapImageRep?, at scale: Double) -> (Bool, String) {
        guard let image, let content = shell.window.contentView else { return (false, "at \(Int(scale))x nothing was drawn") }
        let across = card.convert(card.bounds, to: mark), centre = Double(card.convert(card.bounds, to: content).midX)
        let bound = 0.5 / min(Double(shell.window.backingScaleFactor), scale)
        let bands = [
            ("sigma", NSRect(x: across.minX, y: 0, width: across.width, height: mark.markSide)),
            ("label", NSRect(x: across.minX, y: mark.markSide, width: across.width, height: Double(mark.bounds.height) - mark.markSide)),
        ]
        var within = true
        let found = bands.map { name, band -> String in
            guard let ink = Self.inkCentre(in: image, of: content, region: mark.convert(band, to: content), scale: scale) else {
                within = false
                return "the \(name) drew nothing"
            }
            within = within && abs(ink - centre) <= bound
            return String(format: "the %@'s ink %+.2f", name, ink - centre)
        }
        return (within, "at \(Int(scale))x " + found.joined(separator: " and ") + String(format: " pt from the card's centre (within %.2f)", bound))
    }

    /// The horizontal centre, in `view`'s points, of the ink in `region` (`view`'s points) of `image`,
    /// `view` drawn at `scale`: the columns holding a pixel that differs from the region's first by
    /// more than 16 of 255 in a channel. Nil where nothing differs.
    private static func inkCentre(in image: NSBitmapImageRep, of view: NSView, region: NSRect, scale: Double) -> Double? {
        let top = view.isFlipped ? Double(region.minY) : Double(view.bounds.height - region.maxY)
        let rows = max(0, Int((top * scale).rounded())) ..< min(image.pixelsHigh, Int(((top + Double(region.height)) * scale).rounded()))
        let columns = max(0, Int((Double(region.minX) * scale).rounded())) ..< min(image.pixelsWide, Int((Double(region.maxX) * scale).rounded()))
        guard let first = rows.first, let left = columns.first else { return nil }
        var ground = [Int](repeating: 0, count: 4), pixel = ground
        image.getPixel(&ground, atX: left, y: first)
        var inked: ClosedRange<Int>?
        for y in rows {
            for x in columns {
                image.getPixel(&pixel, atX: x, y: y)
                guard zip(pixel.prefix(3), ground.prefix(3)).contains(where: { abs($0 - $1) > 16 }) else { continue }
                inked = inked.map { min($0.lowerBound, x) ... max($0.upperBound, x) } ?? x ... x
            }
        }
        return inked.map { Double($0.lowerBound + $0.upperBound + 1) / 2 / scale }
    }

    /// `view` as AppKit draws it (`cacheDisplay`), at `scale` times its size.
    private static func drawn(_ view: NSView?, at scale: Double) -> NSBitmapImageRep? {
        guard let view, view.bounds.width > 0, view.bounds.height > 0, let image = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: Int(Double(view.bounds.width) * scale), pixelsHigh: Int(Double(view.bounds.height) * scale), bitsPerSample: 8,
            samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        ) else { return nil }
        image.size = view.bounds.size
        view.cacheDisplay(in: view.bounds, to: image)
        return image
    }

    /// Store a Key's steps: a key pasted as a person pastes it, the sheet read, and the clipboard.
    /// A step that acts on the fake door waits for the app to have heard by the sheet's own state.
    private func performLoginSheet(_ step: Step) async -> (Bool, String)? {
        let panel = shell.workspaces.newSession
        let sheet = { self.shell.window.attachedSheet as? LoginSheet }
        if let value = step.pasteLogin {
            guard let up = sheet(), let paste = Keys.named("v", modifiers: ["command"]) else { return (false, "no Store a Key sheet to paste into") }
            if !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { pastedLogins.insert(value) }
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(value, forType: .string)
            send(paste)
            let pasted = await waitUntil(step.seconds ?? 5) { up.store.isEnabled }
            return (pasted, "pasted \(value.count) characters; the sheet: " + Self.facts(of: up, over: panel).joined(separator: ", "))
        }
        if let words = step.loginSheetSays {
            let found = await waitUntil(step.seconds ?? 5) { sheet().map { Self.inOrder(words, in: Self.words(in: $0.contentView)) } == true }
            return (found, sheet().map { "the sheet says: " + Self.words(in: $0.contentView).joined(separator: " | ") } ?? "no sheet is up")
        }
        if let wanted = step.loginSheetIs {
            let facts = { sheet().map { Self.facts(of: $0, over: panel) } ?? ["closed", panel.isOpen ? "the panel open" : "the panel closed"] }
            let found = await waitUntil(step.seconds ?? 5) { Set(wanted).isSubset(of: facts()) }
            return (found, "the sheet: " + facts().joined(separator: ", "))
        }
        if let title = step.loginSheetClick {
            guard let up = sheet(), let button = Self.views(in: up.contentView ?? NSView()).compactMap({ $0 as? NSButton }).first(where: { $0.title == title && !$0.isHiddenOrHasHiddenAncestor })
            else { return (false, "the sheet shows no \(title)") }
            guard await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY)) else { return (false, "\(title) could not be clicked") }
            return (true, "clicked \(title); the sheet: " + Self.facts(of: up, over: panel).joined(separator: ", "))
        }
        if let held = step.pasteboardHolds {
            let now = { NSPasteboard.general.string(forType: .string) ?? "" }
            let found = await waitUntil(step.seconds ?? 5) { now() == held }
            let said = now().isEmpty ? "nothing" : pastedLogins.contains(now()) ? "a key the drive pasted" : "'\(now())'"
            return (found, "the pasteboard holds \(said)")
        }
        if let text = step.setPasteboard {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            return (true, "")
        }
        return nil
    }

    /// Whether window `one` is in front of window `other`, as the window server orders them on screen.
    private static func inFront(_ one: Int, of other: Int) -> Bool {
        let listed = (CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []).compactMap { $0[kCGWindowNumber as String] as? Int }
        guard let front = listed.firstIndex(of: one), let back = listed.firstIndex(of: other) else { return false }
        return front < back
    }

    /// Every window the window server shows in front of `sheet` and over it, by its owner and name: a
    /// suggestion such as Passwords' AutoFill, whoever draws it; nil when the sheet is not on screen,
    /// so nothing was compared and nothing is claimed.
    private static func inFrontOf(_ sheet: NSWindow) -> [String]? {
        let listed = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
        guard let at = listed.firstIndex(where: { $0[kCGWindowNumber as String] as? Int == sheet.windowNumber }), let screen = sheet.screen else { return nil }
        let frame = sheet.frame, top = Double(screen.frame.maxY)
        let shown = CGRect(x: Double(frame.minX), y: top - Double(frame.maxY), width: Double(frame.width), height: Double(frame.height))
        return listed[..<at].compactMap { window in
            guard let bounds = (window[kCGWindowBounds as String] as? NSDictionary).flatMap({ CGRect(dictionaryRepresentation: $0 as CFDictionary) }),
                  bounds.intersects(shown), (window[kCGWindowAlpha as String] as? Double ?? 1) > 0 else { return nil }
            return "\(window[kCGWindowOwnerName as String] as? String ?? "?") \(window[kCGWindowName as String] as? String ?? "")"
        }
    }

    /// The sheet as the drive reads it, a fact each; never the field's text.
    private static func facts(of sheet: LoginSheet, over panel: NewSessionPanel) -> [String] {
        let keys = sheet.firstResponder, field = sheet.field.field
        var facts = [sheet.store.isEnabled ? "Store on" : "Store off", field.stringValue.isEmpty ? "the field empty" : "the field filled"]
        facts.append(panel.isOpen ? "the panel open behind" : "the panel closed")
        facts.append(sheet.model.asksName ? "the name \(sheet.model.name)" : "no name asked")
        if case .naming = sheet.model.stage { facts.append("naming") }
        if sheet.field.superview == nil { facts.append("no key field") }
        if let name = sheet.name?.field, keys === name || (keys as? NSText)?.delegate === name { facts.append("the keys in the name") }
        if sheet.model.stage == .storing { facts.append("Storing") }
        if field is NSSecureTextField { facts.append("dots only") }
        if keys === field || (keys as? NSText)?.delegate === field { facts.append("the keys in the field") }
        if let error = sheet.field.error { facts.append("refused: \(error)") } else if let help = sheet.field.help { facts.append("help: \(help)") }
        if let error = sheet.name?.error, sheet.name?.superview != nil { facts.append("the name refused: \(error)") }
        if panel.isOpen, Self.inFront(sheet.windowNumber, of: panel.popover.panel.windowNumber) { facts.append("over the panel") }
        switch Self.inFrontOf(sheet) {
        case nil: facts.append("the sheet is not on screen")
        case let over? where over.isEmpty: facts.append("no suggestion window is showing")
        case let over?: facts.append("in front of the sheet: " + over.joined(separator: ", "))
        }
        return facts
    }

    /// Where a screen shot shows `popover`'s shadow cut: at the middle of each side of its window, a
    /// pixel just inside that differs from one just outside, the window's edge drawn as a step. A
    /// side is judged only where both pixels lie on `window`'s content, the same surface under both
    /// (a floating window may reach past it, over the desktop); a shot judging no side is a fault.
    private static func shadowCut(in bitmap: NSBitmapImageRep, of popover: Popover, over window: NSWindow) -> [String] {
        guard let screen = window.screen else { return ["the window is on no screen"] }
        let scale = Double(bitmap.pixelsWide) / Double(screen.frame.width), content = window.convertToScreen(window.contentLayoutRect).insetBy(dx: 4, dy: 4)
        func ink(_ point: NSPoint) -> [Double]? {
            guard content.contains(point) else { return nil }
            let x = Int((Double(point.x) - Double(screen.frame.minX)) * scale), y = Int((Double(screen.frame.maxY) - Double(point.y)) * scale)
            guard x >= 0, y >= 0, x < bitmap.pixelsWide, y < bitmap.pixelsHigh, let colour = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.sRGB) else { return nil }
            return [colour.redComponent, colour.greenComponent, colour.blueComponent].map { Double($0) * 255 }
        }
        let outer = popover.panel.frame
        let sides = [
            (NSPoint(x: outer.minX + 1, y: outer.midY), NSPoint(x: outer.minX - 2, y: outer.midY)), (NSPoint(x: outer.maxX - 1, y: outer.midY), NSPoint(x: outer.maxX + 2, y: outer.midY)),
            (NSPoint(x: outer.midX, y: outer.minY + 1), NSPoint(x: outer.midX, y: outer.minY - 2)), (NSPoint(x: outer.midX, y: outer.maxY - 1), NSPoint(x: outer.midX, y: outer.maxY + 2)),
        ]
        var faults: [String] = [], judged = 0
        for (inside, outside) in sides {
            guard let one = ink(inside), let other = ink(outside) else { continue }
            judged += 1
            if zip(one, other).contains(where: { abs($0 - $1) > 4 }) { faults.append("the window's edge shows at \(inside): \(one.map(Int.init)) inside, \(other.map(Int.init)) outside") }
        }
        return judged == 0 ? ["no side of the floating window lies on the window's content"] : faults
    }

    /// ⌘M, then a real Escape while AppKit tracks the mode menu, then a second Escape. A menu's
    /// tracking loop reads its own events, so the Escape is posted the two ways one can reach it:
    /// first to the app's event queue, then, if the menu is still up, to this process through the
    /// window server. Each way is tried only while the menu is up and none once it has gone, so the
    /// result names the way that put it away. The step passes only when one of them did: should
    /// neither reach it, it fails, the menu put away by its `cancelTracking` so no later step is
    /// left under it; gone before any was posted, it fails too.
    private func modesEscape(_ panel: NewSessionPanel) async -> (Bool, String) {
        guard panel.isOpen, let menu = panel.view.modes?.menu, let before = panel.view.modes?.title,
              let open = Keys.named("m", modifiers: ["command"]), let escape = Keys.named("escape", modifiers: [])
        else { return (false, "no mode menu to open") }
        let menuShown = { NSApp.windows.contains { $0.isVisible && $0.level.rawValue >= NSWindow.Level.popUpMenu.rawValue } }
        var opened = false, way: String?, unreached = false
        let queued = Timer(timeInterval: 0.3, repeats: false) { _ in
            MainActor.assumeIsolated {
                opened = menuShown()
                for type in [NSEvent.EventType.keyDown, .keyUp] {
                    guard let event = NSEvent.keyEvent(
                        with: type, location: .zero, modifierFlags: escape.flags, timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: 0, context: nil,
                        characters: escape.characters, charactersIgnoringModifiers: escape.ignoring, isARepeat: false, keyCode: escape.code
                    ) else { continue }
                    NSApp.postEvent(event, atStart: false)
                }
                way = "an Escape posted to the app's event queue"
            }
        }
        let windowServer = Timer(timeInterval: 0.9, repeats: false) { _ in
            MainActor.assumeIsolated {
                guard menuShown() else { return }
                for down in [true, false] { CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(escape.code), keyDown: down)?.postToPid(getpid()) }
                way = "an Escape posted to this process through the window server"
            }
        }
        let last = Timer(timeInterval: 1.6, repeats: false) { _ in
            MainActor.assumeIsolated {
                guard menuShown() else { return }
                unreached = true
                menu.cancelTracking()
            }
        }
        [queued, windowServer, last].forEach { RunLoop.main.add($0, forMode: .common) }
        send(open)
        let kept = await waitUntil(3) { !menuShown() && panel.isOpen && panel.view.modes?.title == before }
        [queued, windowServer, last].forEach { $0.invalidate() }
        send(escape)
        let closed = await waitUntil(3) { !panel.isOpen }
        let went = unreached
            ? "no posted Escape (the app's queue, then the window server to this process) put it away; the step put it away by its cancelTracking"
            : "it went by \(way ?? "nothing: it was gone before any Escape was posted")"
        return (opened && way != nil && !unreached && kept && closed, "\(opened ? "the mode menu opened" : "⌘M opened no menu"); \(went); the panel \(kept ? "stayed open, its mode \(before)" : "did not stay as it was"); a second Escape \(closed ? "closed it" : "left it open")")
    }

    /// The buttons row `name` shows now, in order.
    private static func rowButtons(_ name: String, in list: SailList) -> [SailButton] {
        guard let index = row(name, in: list), let view = list.rowView(atRow: index, makeIfNecessary: false) else { return [] }
        view.layoutSubtreeIfNeeded()
        return found(SailButton.self, in: view).filter { !$0.isHiddenOrHasHiddenAncestor }
    }

    /// Whether `wanted` are among `words` in that order.
    private static func inOrder(_ wanted: [String], in words: [String]) -> Bool {
        var rest = words[...]
        for word in wanted {
            guard let at = rest.firstIndex(of: word) else { return false }
            rest = rest[(at + 1)...]
        }
        return true
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
    func turnWheel(over view: NSScrollView, to fraction: Double) async -> (Bool, String) {
        guard let window = view.window, let document = view.documentView else { return (false, "the scroll view is not in a window") }
        let room = Double(document.frame.height) - Double(view.contentSize.height)
        guard room > 0 else { return (false, "nothing to scroll: the rows are \(document.frame.height) pt in \(view.contentSize.height) pt") }
        let wanted = room * min(max(fraction, 0), 1)
        let middle = window.convertPoint(toScreen: clickPoint(NSPoint(x: view.bounds.midX, y: view.bounds.midY), in: view))
        let top = Double(NSScreen.screens.first?.frame.maxY ?? 0)
        var routed = true
        while abs(Double(view.contentView.bounds.minY) - wanted) > 0.5 {
            let left = wanted - Double(view.contentView.bounds.minY)
            let step = Int32((left > 0 ? -min(40, left) : min(40, -left)).rounded(left > 0 ? .down : .up))
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
    /// Clicks, as a person does, the button titled `title` shown in the window's content.
    private func clickButton(_ title: String) async -> (Bool, String) {
        guard let content = shell.window.contentView else { return (false, "the window has no content") }
        content.layoutSubtreeIfNeeded()
        guard let button = Self.views(in: content).compactMap({ $0 as? NSButton }).first(where: { $0.title == title && !$0.isHiddenOrHasHiddenAncestor }),
              await click(button, at: NSPoint(x: button.bounds.midX, y: button.bounds.midY))
        else { return (false, "no \(title) shown") }
        return (true, "clicked \(title)")
    }

    /// This Mac's session row of `title` clicked: true once it is chosen with the list holding the keys.
    private func chooseSession(_ title: String) async -> Bool {
        let list = shell.workspaces.local.list
        let id = list.entries.lazy.compactMap { entry -> String? in
            guard case let .item(item) = entry, item.name == title else { return nil }
            return item.id
        }.first
        guard let id, await clickRow(id, in: list) else { return false }
        return await waitUntil(5) { list.chosen == id && list.window?.firstResponder === list }
    }

    /// Clicks the row of `id` in `list` 24 pt in from its leading edge in the list's direction,
    /// clear of the actions at its end.
    /// A click on row `id` of `list` once the row is where a person would click it: its point,
    /// taken with the window laid out (``clickPoint(_:in:)``), reaches the list.
    func clickRow(_ id: String, in list: SailList) async -> Bool {
        var point = NSPoint.zero
        let reachable = await waitUntil(5) {
            guard let row = Self.row(id, in: list) else { return false }
            let rect = list.rect(ofRow: row)
            point = NSPoint(x: list.userInterfaceLayoutDirection == .rightToLeft ? rect.maxX - 24 : rect.minX + 24, y: rect.minY + min(rect.height, 52) / 2)
            return list.window?.contentView?.superview?.hitTest(clickPoint(point, in: list))?.isDescendant(of: list) == true
        }
        guard reachable else { return false }
        return await click(list, at: point)
    }

    private static func row(_ id: String, in list: SailList) -> Int? {
        list.entries.firstIndex { entry in
            guard case let .item(item) = entry else { return false }
            return item.id == id
        }
    }

    /// A click at `point` in `view` as a person makes it: a mouse-down sent through the app, so its
    /// monitors see it as they see a person's, with its mouse-up queued behind it, so the view's own
    /// tracking takes both.
    func click(_ view: NSView, at point: NSPoint, modifiers: NSEvent.ModifierFlags = []) async -> Bool {
        guard let window = view.window else { return false }
        let location = clickPoint(point, in: view)
        func event(_ type: NSEvent.EventType) -> NSEvent? {
            NSEvent.mouseEvent(
                with: type, location: location, modifierFlags: modifiers, timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil,
                eventNumber: 0, clickCount: 1, pressure: type == .leftMouseDown ? 1 : 0
            )
        }
        guard let down = event(.leftMouseDown), let up = event(.leftMouseUp) else { return false }
        NSApp.postEvent(up, atStart: false)
        NSApp.sendEvent(down)
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
