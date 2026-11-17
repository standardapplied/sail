import AppKit
import Foundation
import SailAPI
import SailKitTestSupport
import SailShell

/// The control plane of the drive's workspaces run: every route answered as the server answered
/// it in the goldens its `ClientGoldensTest` writes, a step choosing which golden answers a route
/// from then on. No answer is written here.
actor GoldenDesk: Transport {
    static let shared = GoldenDesk()
    private var answers: [String: HTTPResponse] = [:]
    private(set) var asked: [HTTPRequest] = []

    init() {
        let first = ["passkey-login-start", "passkey-login-finish", "whoami", "projects", "workspaces", "workspace-create", "terminal-ticket-workspace"]
        for exchange in first.compactMap({ try? Exchange($0) }) {
            answers["\(exchange.method) \(exchange.path)"] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
        }
    }

    /// From now on `route` is answered as the golden `name` was.
    func answer(_ route: String, as name: String) throws {
        let exchange = try Exchange(name)
        answers[route] = HTTPResponse(status: exchange.status, headers: exchange.headers, body: exchange.body)
    }

    func send(_ request: HTTPRequest) throws(SailError) -> HTTPResponse {
        asked.append(request)
        guard let answer = answers[request.route] else { throw .unreachable("no golden answers \(request.route)") }
        return answer
    }

    func open(_ request: HTTPRequest) throws(SailError) -> HTTPStream {
        throw .unreachable("no golden streams \(request.route)")
    }
}

/// The drive's steps on the Workspaces pane and the runner's tabs, against ``GoldenDesk``.
extension Driver {
    func performWorkspaces(_ step: Step) async -> (Bool, String)? {
        let pane = shell.workspaces
        if let pair = step.golden {
            guard pair.count == 2 else { return (false, "golden takes a route and a golden's name") }
            do {
                try await GoldenDesk.shared.answer(pair[0], as: pair[1])
                return (true, "")
            } catch {
                return (false, "\(pair[1]): \(error)")
            }
        }
        if let text = step.workspaces {
            let found = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.text.contains(text) }
            let chosen = pane.rows.indices.contains(pane.list.selectedRow) ? pane.rows[pane.list.selectedRow].name : "none"
            let focused = pane.window?.firstResponder === pane.list
            return (found, "the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | ")
                + "; selected \(chosen)\(focused ? ", the list focused" : ""), Open \(pane.open.isEnabled ? "enabled" : "disabled")")
        }
        if let wanted = step.make {
            let before = pane.ended
            pane.name.stringValue = wanted
            pane.make.performClick(nil)
            let said = pane.text
            let done = await waitUntil(step.seconds ?? 10) { pane.ended > before }
            return (done, "while making: \(said.replacingOccurrences(of: "\n", with: " | "))")
        }
        if let name = step.open {
            guard let index = pane.rows.firstIndex(where: { $0.name == name }) else { return (false, "no row \(name)") }
            pane.list.selectRowIndexes([index], byExtendingSelection: false)
            pane.open.performClick(nil)
            return (shell.rail.selected == .thisMac, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing"); \(arrangementSaid)")
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
            return await inBothLooks(name) { file in await self.capture([], to: file) }
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
