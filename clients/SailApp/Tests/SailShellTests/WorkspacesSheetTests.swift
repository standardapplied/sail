import AppKit
import SailAPI
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Testing

/// Stop and Delete ask in the library's sheet: each question laid out whole in its 440 pt in both
/// looks, nothing out of reach; a destructive confirm never takes Return and the keys start on
/// Cancel; and the section asks with the person's terminals counted, then, refused for the work it
/// holds, asks Delete Anyway in the server's words.
@MainActor @Suite struct WorkspacesSheetTests {
    init() {
        ExitWatch.install()
    }

    static let questions: [(title: String, body: String, confirm: String, kind: ButtonKind)] = [
        ("Stop dock?", "Your 2 terminals in it end. Its files stay, and Start and Open brings it back.", "Stop", .destructiveQuiet),
        ("Delete exp-ui?", "Its files on the runner go with it, and any terminal open in it ends. Commits you pushed are kept by the repo host.", "Delete Workspace", .destructive),
        ("Delete dock anyway?", "Workspace dock of harbor, uday's, holds work: app holds uncommitted changes.", "Delete Anyway", .destructive),
    ]

    @Test(arguments: [Look.light, .dark])
    func eachQuestionIsLaidOutWholeAndInReach(_ look: Look) throws {
        for asked in Self.questions {
            let sheet = Sheet(title: asked.title, body: asked.body, confirm: asked.confirm, kind: asked.kind, look: look, titleFont: try TitleFont(directory: LookTests.fonts))
            let content = sheet.laidOut()
            sheet.window.orderFront(nil)
            defer { sheet.window.orderOut(nil) }
            content.layoutSubtreeIfNeeded()
            #expect(near(content.frame.width, Sheet.width), "\(asked.title): the sheet is \(content.frame.width) pt")
            #expect(WorkspacesReachTests.outOfReach(in: content) == [], "\(asked.title): \(WorkspacesReachTests.outOfReach(in: content))")
            for label in [sheet.title, sheet.body] {
                let needs = Double(label.cell?.cellSize(forBounds: NSRect(x: 0, y: 0, width: label.frame.width, height: .greatestFiniteMagnitude)).height ?? 0)
                #expect(needs <= Double(label.frame.height) + 0.5, "\(asked.title): \(label.stringValue.prefix(20))… needs \(needs) pt in \(label.frame.height)")
            }
            let cancel = content.convert(sheet.cancel.alignmentRect(forFrame: sheet.cancel.frame), from: sheet.cancel.superview)
            let confirm = content.convert(sheet.confirm.alignmentRect(forFrame: sheet.confirm.frame), from: sheet.confirm.superview)
            #expect(near(confirm.minX - cancel.maxX, 8) && near(confirm.maxX, content.bounds.maxX - 16), "Cancel at \(cancel), \(asked.confirm) at \(confirm)")
            #expect(sheet.confirm.keyEquivalent == "" && sheet.cancel.keyEquivalent == "\r", "\(asked.confirm) takes Return, or Return does not cancel")
            #expect(sheet.window.initialFirstResponder === sheet.cancel && sheet.window.title == asked.title, "the keys do not start on Cancel, or VoiceOver cannot name the sheet")
        }
    }

    /// A sheet asking while the look changes takes the new look: its ground, words and buttons.
    @Test func aSheetTakesALookChangedWhileItAsks() throws {
        let sheet = Sheet(title: "Delete dock?", body: "Its files on the runner go with it.", confirm: "Delete Workspace", kind: .destructive, look: .light, titleFont: try TitleFont(directory: LookTests.fonts))
        _ = sheet.laidOut()
        sheet.look = .dark
        let ink = { (label: NSTextField) in label.attributedStringValue.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor }
        #expect(ink(sheet.title) == NSColor(Look.dark.text) && ink(sheet.body) == NSColor(Look.dark.textSecondary), "the sheet's words kept the light look")
        #expect(sheet.cancel.look == .dark && sheet.confirm.look == .dark, "the sheet's buttons kept the light look")
    }

    /// Delete asks what it does; confirmed and refused for the work dock holds, it asks Delete Anyway
    /// in the server's own words; Esc cancels it.
    @Test func deleteAsksThenAsksAnywayInTheServersWords() async throws {
        let server = RecordedServer([
            try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-sessions"), try Exchange("refusal-workspace-holds-work"),
        ])
        let (view, window) = try WorkspacesViewTests.view(server)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        view.project.on(.delete("dock"))
        #expect(await RunnerTabTests.until { window.attachedSheet != nil }, "Delete asked nothing")
        let asked = Driverless.words(window.attachedSheet?.contentView)
        #expect(asked.contains("Delete dock?") && asked.contains("Sail asks again before deleting it"), "\(asked)")
        Driverless.button("Delete Workspace", in: window.attachedSheet?.contentView)?.performClick(nil)
        #expect(await RunnerTabTests.until { Driverless.words(window.attachedSheet?.contentView).contains("Delete dock anyway?") }, "a delete refused for its work did not ask again")
        let anyway = Driverless.words(window.attachedSheet?.contentView)
        #expect(anyway.contains("holds work: app holds uncommitted changes.") && !anyway.contains("--force"), "\(anyway)")
        window.attachedSheet?.cancelOperation(nil)
        #expect(await RunnerTabTests.until { window.attachedSheet == nil && !view.busy }, "Esc left the sheet up")
    }
}

extension WorkspacesSheetTests {
    /// R2-1: someone else signs in while a confirmed stop counts its terminals again: nothing is
    /// sent, and the next Delete still asks.
    @Test func aSignInDuringAStopsRecountLeavesNothingHeld() async throws {
        let server = RecordedServer([
            try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("workspace-sessions"), try Exchange("workspace-stop"),
        ])
        let (view, window) = try WorkspacesViewTests.view(server)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        view.project.on(.stop("dock"))
        #expect(await RunnerTabTests.until { Driverless.words(window.attachedSheet?.contentView).contains("Stop dock?") }, "a stop with a terminal open asked nothing")
        Driverless.button("Stop", in: window.attachedSheet?.contentView)?.performClick(nil)
        view.person = WorkspacesViewTests.ana
        view.person = WorkspacesViewTests.uday
        #expect(await RunnerTabTests.until { !view.busy && window.attachedSheet == nil }, "the recount never ended")
        let routes = await server.requests.map(\.route)
        #expect(!routes.contains { $0.hasSuffix("/dock/stop") }, "the stop went out for someone no longer signed in: \(routes)")
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        view.project.on(.delete("dock"))
        #expect(await RunnerTabTests.until { Driverless.words(window.attachedSheet?.contentView).contains("Delete dock?") }, "after the sign-in, Delete asked nothing")
        window.attachedSheet?.cancelOperation(nil)
    }

    /// R2-6: a Delete Anyway answered while This Mac is shown waits, and asks once Workspaces is.
    @Test func aQuestionWaitsForTheSectionToBeShown() async throws {
        let server = RecordedServer([try Exchange("projects"), try Exchange("workspaces-three"), try Exchange("refusal-workspace-holds-work")])
        let (view, window) = try WorkspacesViewTests.view(server)
        defer { window.orderOut(nil) }
        view.shown()
        #expect(await RunnerTabTests.until { view.text.contains("dock") }, "\(view.text)")
        view.project.on(.delete("dock"))
        #expect(await RunnerTabTests.until { window.attachedSheet != nil }, "Delete asked nothing")
        Driverless.button("Delete Workspace", in: window.attachedSheet?.contentView)?.performClick(nil)
        view.isHidden = true
        #expect(await RunnerTabTests.until { !view.busy }, "the delete never ended")
        #expect(window.attachedSheet == nil, "Delete Anyway asked over another section")
        view.isHidden = false
        #expect(await RunnerTabTests.until { Driverless.words(window.attachedSheet?.contentView).contains("Delete dock anyway?") }, "shown again, the section did not ask Delete Anyway")
        window.attachedSheet?.cancelOperation(nil)
    }
}

/// A sheet's words and buttons, found in its view tree.
@MainActor enum Driverless {
    static func words(_ view: NSView?) -> String {
        guard let view else { return "" }
        return (((view as? NSTextField).map { [$0.stringValue] } ?? []) + view.subviews.map { words($0) }).joined(separator: " ")
    }

    static func button(_ title: String, in view: NSView?) -> NSButton? {
        guard let view else { return nil }
        if let button = view as? NSButton, button.title == title { return button }
        return view.subviews.lazy.compactMap { button(title, in: $0) }.first
    }
}
