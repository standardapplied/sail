import AppKit
import Foundation
import SailAPI
import SailControls
import SailKitTestSupport
import SailShell

/// The drive's steps on creating a project: who is offered Create Project, the New project sheet
/// typed into and clicked as a person does, read as facts, and what the control plane was asked.
extension Driver {
    func performCreateProject(_ step: Step) async -> (Bool, String)? {
        let pane = shell.workspaces
        let sheet = { pane.projectSheet.flatMap { $0.sheetParent == nil ? nil : $0 } }
        if let pick = step.projectSheetType, pick.count == 2 {
            guard let up = sheet(), let field = Self.field(pick[0], in: up) else { return (false, "the sheet shows no field \(pick[0])") }
            guard await click(field.field, at: NSPoint(x: field.field.bounds.midX, y: field.field.bounds.midY)) else { return (false, "\(pick[0]) could not be clicked") }
            let editing = await waitUntil(step.seconds ?? 5) { (up.firstResponder as? NSTextView)?.delegate === field.field }
            guard editing, let editor = up.firstResponder as? NSTextView else { return (false, "a click on \(pick[0]) gave it no keys") }
            editor.selectAll(nil)
            for character in pick[1] {
                key(character)
                await Task.yield()
            }
            let typed = await waitUntil(step.seconds ?? 5) { field.field.stringValue == pick[1] }
            return (typed, "\(pick[0]) holds '\(field.field.stringValue)'; " + Self.facts(of: up, over: shell.window).joined(separator: ", "))
        }
        if let title = step.projectSheetClick {
            guard let up = sheet(), let found = Self.target(title, in: up) else { return (false, "the sheet shows no \(title)") }
            guard await click(found.view, at: found.point) else { return (false, "\(title) could not be clicked") }
            return (true, "clicked \(title)")
        }
        if let wanted = step.projectSheetIs {
            let facts = { sheet().map { Self.facts(of: $0, over: self.shell.window) } ?? ["closed"] }
            let found = await waitUntil(step.seconds ?? 5) { !pane.busy && Set(wanted).isSubset(of: facts()) }
            return (found, "the sheet: " + facts().joined(separator: ", "))
        }
        if step.projectSheetReturnTwice == true {
            guard sheet() != nil, let enter = Keys.named("enter", modifiers: []) else { return (false, "no sheet to press Return in") }
            send(enter)
            send(enter)
            let gone = await waitUntil(step.seconds ?? 10) { sheet() == nil && !pane.busy }
            return (gone, gone ? "two Returns at once; the sheet went" : "two Returns at once; the sheet says " + (sheet().map { Self.facts(of: $0, over: shell.window).joined(separator: ", ") } ?? ""))
        }
        if let offered = step.projectCreateOffered {
            let shown = { Self.views(in: pane).compactMap { $0 as? NSButton }.contains { $0.title == "Create Project" && !$0.isHiddenOrHasHiddenAncestor } }
            let found = await waitUntil(step.seconds ?? 5) { !pane.busy && shown() == offered }
            return (found, "Create Project \(shown() ? "offered" : "not offered"); the pane says: " + pane.text.replacingOccurrences(of: "\n", with: " | "))
        }
        if let role = step.signedInAs {
            return signIn(as: role)
        }
        if let renamed = step.goldenRenamed, renamed.count == 4 {
            do {
                try await GoldenDesk.shared.answer(renamed[0], as: renamed[1], renaming: renamed[2], to: renamed[3])
                return (true, "")
            } catch {
                return (false, "\(renamed[1]): \(error)")
            }
        }
        if let wanted = step.createAsked, wanted.count == 3, let count = Int(wanted[0]) {
            return await createAsked(count, body: wanted[1], then: wanted[2], seconds: step.seconds ?? 10)
        }
        return nil
    }

    /// Who is signed in shown again with the same session, their `whoami` a viewer's (`read` only)
    /// or the golden's (an admin), as a refreshed sign-in would show it.
    private func signIn(as role: String) -> (Bool, String) {
        guard let standing = shell.flow.standing else { return (false, "no one is signed in") }
        let whoami: Whoami
        if role == "viewer" {
            whoami = Whoami(fde: standing.fde, name: standing.fde, role: .known(.viewer), capabilities: [.known(.read)])
        } else {
            guard let golden = try? Exchange("whoami"), let decoded = try? Endpoint.whoami.decode(HTTPResponse(status: golden.status, body: golden.body)) else {
                return (false, "the golden whoami could not be read")
            }
            whoami = decoded
        }
        shell.flow.show(SignedIn(controlPlane: standing.controlPlane, fde: standing.fde, until: standing.until, whoami: .success(whoami)))
        return (true, "signed in as \(standing.fde), \(role)")
    }

    /// The control plane was asked to create `count` projects in all, the last with the definition
    /// `body` (as JSON), and then once for `build`, the template's build, after it.
    private func createAsked(_ count: Int, body: String, then build: String, seconds: Double) async -> (Bool, String) {
        let asked = { await GoldenDesk.shared.asked }
        _ = await waitUntilAsync(seconds) { await asked().contains { $0.route == build } }
        let all = await asked(), creates = all.enumerated().filter { $0.element.route == "POST /v1/projects" }
        let wanted = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? NSDictionary
        let last = creates.last.flatMap { $0.element.body }.flatMap { try? JSONSerialization.jsonObject(with: $0) as? NSDictionary }
        let builds = all.enumerated().filter { $0.element.route == build }
        let after = builds.count == 1 && (builds.first?.offset ?? -1) > (creates.last?.offset ?? Int.max)
        let sent = creates.last?.element.body.map { String(decoding: $0, as: UTF8.self) } ?? "nothing"
        return (creates.count == count && wanted != nil && last == wanted && after, "\(creates.count) creates asked, the last \(sent); \(builds.count) builds asked\(after ? " after it" : "")")
    }

    /// The field a step names: "name", "url N" or "path N" (from 1), "cores", "memory" or "disk".
    private static func field(_ named: String, in sheet: CreateProjectSheet) -> SailTextField? {
        let parts = named.split(separator: " "), row = parts.count == 2 ? Int(parts[1]).map { $0 - 1 } : nil
        switch (parts.first, row) {
        case ("name"?, nil): return sheet.name
        case let ("url"?, row?): return sheet.urls.indices.contains(row) ? sheet.urls[row] : nil
        case let ("path"?, row?): return sheet.paths.indices.contains(row) ? sheet.paths[row] : nil
        default: return sheet.custom.first { "\($0.part)" == named }?.field
        }
    }

    /// What a click titled `title` lands on: a button or link by its title, a size by its name, an
    /// agent's check by the harness's name; and the point in it.
    private static func target(_ title: String, in sheet: CreateProjectSheet) -> (view: NSView, point: NSPoint)? {
        guard let content = sheet.contentView else { return nil }
        if let button = views(in: content).compactMap({ $0 as? NSButton }).first(where: { $0.title == title && !$0.isHiddenOrHasHiddenAncestor }) {
            return (view: button, point: NSPoint(x: button.bounds.midX, y: button.bounds.midY))
        }
        if let size = NewProject.Size(rawValue: title), let index = NewProject.Size.allCases.firstIndex(of: size) {
            let rect = sheet.sizes.rects[index]
            return (view: sheet.sizes, point: NSPoint(x: rect.midX, y: rect.midY))
        }
        return sheet.checks.first { $0.label == title }.map { (view: $0, point: NSPoint(x: $0.bounds.midX, y: $0.bounds.midY)) }
    }

    /// The sheet as the drive reads it, a fact each.
    private static func facts(of sheet: CreateProjectSheet, over window: NSWindow) -> [String] {
        let model = sheet.model, keys = (sheet.firstResponder as? NSTextView)?.delegate as? NSTextField
        var facts = ["open", sheet.create.isEnabled && sheet.create.busyWord == nil ? "Create on" : "Create off", model.size.rawValue]
        facts.append("agents " + sheet.checks.filter(\.checked).map(\.label).joined(separator: ", ") + " checked")
        if keys === sheet.name.field { facts.append("the keys in name") }
        for (at, repo) in model.repos.enumerated() {
            facts += ["url \(at + 1) \(sheet.urls[at].field.stringValue)", "path \(at + 1) \(sheet.paths[at].field.stringValue)"]
            if repo.url != sheet.urls[at].field.stringValue || repo.path != sheet.paths[at].field.stringValue { facts.append("row \(at + 1) drawn apart from its model") }
        }
        if let error = sheet.name.error { facts.append("name refused \(error)") }
        if let remedy = sheet.name.remedy, sheet.name.error != nil { facts.append("remedy \(remedy)") }
        facts.append(model.refused.first.map { "over the foot \($0)" } ?? "nothing over the foot")
        if model.underWay { facts.append("Creating") }
        facts.append(window.frame.contains(sheet.frame) ? "inside the window" : "past the window: \(sheet.frame) over \(window.frame)")
        return facts
    }
}
