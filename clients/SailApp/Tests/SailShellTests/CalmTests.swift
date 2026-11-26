import AppKit
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminalView
import SailWorkbench
import Testing

/// The calm look, counted by token so it cannot erode: terracotta fills only the one primary action
/// in view and marks where a person acts; no row, tab or rail item is tinted with it; and, paper and
/// ink, no area is filled grey at rest. The numbers are the approved pages': 4 marks on a plain
/// Workspaces page, 3 on a terminal page with a split, and 0 pt² of neutral fill on either.
@MainActor @Suite struct CalmTests {
    init() {
        ExitWatch.install()
    }

    struct Count: Equatable, CustomStringConvertible {
        var fill = 0, line = 0, tint = 0, error = 0, inverse = 0
        var neutral = 0.0

        init(_ marks: [Mark]) {
            for mark in marks {
                switch mark {
                case .accentFill: fill += 1
                case .accentLine: line += 1
                case .accentTint: tint += 1
                case .errorFill, .errorInk: error += 1
                case .inverseFill: inverse += 1
                case let .neutralFill(area): neutral += area
                }
            }
        }

        var accent: Int {
            fill + line + tint
        }

        var description: String {
            "\(fill) accent fills, \(line) accent lines, \(tint) accent tints, \(error) error marks, \(inverse) turned over to ink, \(neutral) pt² of neutral fill"
        }
    }

    /// The Account pane, signed out and signed in, in either look: every button is the library's
    /// (a system button's dark bezel is a grey slab the count cannot see), no grey is filled, and
    /// the one accent fill is Sign In with Passkey; signed in, Sign Out is outlined and none is.
    @Test(arguments: [Look.light, .dark])
    func theAccountPaneIsCalm(_ look: Look) async throws {
        let transport = Held(RecordedServer([try Exchange("whoami")]), holding: "nothing is held")
        let pane = try AccountPaneTests.pane(remembering: "uday", transport)
        pane.look = look
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 900, height: 700), styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        window.contentView = pane
        pane.layoutSubtreeIfNeeded()
        let system = WorkspacesViewTests.found(NSButton.self, in: pane).filter { !($0 is SailButton) && !($0 is LinkButton) }.map(\.title)
        #expect(system.isEmpty, "the pane holds system buttons: \(system)")
        let out = Count(marks(in: pane))
        #expect(out.neutral == 0 && out.fill == 1 && out.tint == 0, "signed out: \(out)")
        pane.signIn.performClick(nil)
        #expect(await RunnerTabTests.until { !pane.isActing && !pane.lines.isEmpty }, "the sign-in's refusal was never said")
        pane.layoutSubtreeIfNeeded()
        let refused = Count(marks(in: pane))
        #expect(refused.neutral == 0 && refused.fill == 1 && refused.tint == 0, "refused, a row is tinted or filled: \(refused)")
        await pane.refresh()
        pane.layoutSubtreeIfNeeded()
        #expect(pane.standing != nil && !pane.signOut.isHiddenOrHasHiddenAncestor && pane.signOut.kind == .secondary, "not signed in, or Sign Out is not the outlined secondary")
        let signedIn = Count(marks(in: pane))
        #expect(signedIn.neutral == 0 && signedIn.fill == 0 && signedIn.tint == 0, "signed in: \(signedIn)")
    }

    /// The rail's chosen item: one accent bar on an ink fill.
    static func rail(_ look: Look) -> Count {
        let rail = Rail(look: look)
        rail.show(.workspaces)
        return Count(rail.drawnMarks)
    }

    /// Every page the project's view can draw, in both looks, wide and narrow: never a tint of the
    /// accent, never two accent fills; a refusal is said in the error ink, never as a tinted row.
    @Test(arguments: [Look.light, .dark])
    func everyWorkspacesPageIsCalm(_ look: Look) throws {
        for width in [900.0, WorkspacesViewTests.narrowestCard] {
            let (project, window) = try WorkspacesViewTests.project(width: width)
            defer { window.orderOut(nil) }
            project.look = look
            for (name, show) in WorkspacesViewTests.scenes(project) {
                show()
                project.layoutSubtreeIfNeeded()
                project.list.layoutSubtreeIfNeeded()
                let count = Count(marks(in: window))
                #expect(count.tint == 0 && count.fill <= 1, "\(name) at \(width) pt: \(count)")
                if name == "refused" { #expect(count.error > 0, "refused at \(width) pt says nothing in the error ink: \(count)") }
            }
        }
    }

    /// A1: harbor and dock chosen, the workspaces list holding the keys: the rail's bar, dock's bar
    /// and Open in the accent, harbor's bar in the quiet ink; and the reverse with the keys in the
    /// panel. Never more than 4 marks, exactly one list's bar in the accent, no tinted fill.
    @Test(arguments: [Look.light, .dark])
    func thePlainWorkspacesPageHasFourMarks(_ look: Look) async throws {
        let (view, window, _) = try await WorkspacesViewTests.listed()
        defer { window.orderOut(nil) }
        view.look = look
        view.project.list.choose("dock")
        #expect(await RunnerTabTests.until { view.project.list.chosen == "dock" })
        view.layoutSubtreeIfNeeded()
        view.project.list.layoutSubtreeIfNeeded()
        let dock = try #require(view.project.list.row("dock")), harbor = try #require(view.panel.list.row("harbor"))
        let rail = Self.rail(look)
        #expect(rail == Count([.accentLine]), "the rail draws \(rail)")
        for listKeys in [true, false] {
            dock.view.isEmphasized = listKeys
            harbor.view.isEmphasized = !listKeys
            let section = Count(marks(in: window))
            #expect(section == Count([.accentLine, .accentFill]), "keys in the \(listKeys ? "list" : "panel"): the section draws \(section)")
            #expect(section.accent + rail.accent <= 4 && section.tint + rail.tint == 0)
            #expect(Count(marks(in: dock.view)).line == (listKeys ? 1 : 0), "dock's bar is in the accent \(listKeys ? "without" : "with") the keys elsewhere")
            #expect(section.neutral == 0 && rail.neutral == 0, "grey at rest: the section \(section.neutral) pt², the rail \(rail.neutral) pt²")
        }
        #expect(dock.paint.bar && dock.paint.strong, "dock, chosen, has no bar or no weight")
        let line = try #require(Self.found(WorkspaceLine.self, in: dock.view).first)
        #expect(Self.weight(line.title) == .semibold, "dock's name is not at full weight")
        let project = try #require(Self.found(SidebarRowCell.self, in: harbor.view).first)
        #expect(harbor.paint.bar && Self.weight(project.label) == .semibold && project.tile.chosen, "harbor, chosen, has no bar, no weight or no ink well")
        dock.view.isEmphasized = true
        harbor.view.isEmphasized = false
        #expect(window.makeFirstResponder(view.project.list))
        let open = try #require(Self.found(SailButton.self, in: dock.view).first { $0.title == "Open" })
        #expect(open.keyEquivalent == "\r", "the list holding the keys is not said by Open's ⏎")
    }

    /// A5: the real workbench, three tabs and a split attached through a host: the rail's bar, the
    /// chosen tab's underline, and the cursor of the pane with the keys; every other pane's cursor
    /// is ink, and nothing it draws (cards, scrim, the card's edge) adds a mark or grey. A6: once
    /// that pane's terminal has ended, its cursor is ink too, and its card's Open a New One is the
    /// only accent fill.
    @Test(arguments: [Look.light, .dark])
    func aTerminalPageWithASplitHasThreeMarks(_ look: Look) async throws {
        let door = try FakeRunnerDoor(thisMac: true)
        defer { door.stop() }
        let bench = try await ThisMacEndingTests.workbench(at: door, tabs: 3)
        bench.look = look
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = bench
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        bench.layoutSubtreeIfNeeded()
        bench.splitRight(nil)
        let split = await RunnerTabTests.until(10) { bench.panes.count == 4 && bench.panes.values.allSatisfy { $0.attachedOnce && $0.status.says == nil } }
        #expect(split, "the split did not attach")
        bench.strip.showsTabs = true
        bench.apply()
        let focused = try #require(bench.model.focusedPane.flatMap { bench.panes[$0.id] })
        #expect(window.makeFirstResponder(focused.terminal) && focused.terminal.holdsTheKeys, "the focused pane holds no keys")
        let rail = Rail(look: look)
        rail.show(.thisMac)
        let page = Count(marks(in: window)) + Count(marks(in: bench.strip)) + Count(rail.drawnMarks)
        #expect(page == Count([.accentLine, .accentLine, .accentLine]), "the terminal page draws \(page)")
        let chosen = try #require(bench.strip.tabs.first { !$0.bar.isHidden })
        #expect(chosen.label.font == NSFont.systemFont(ofSize: Look.Step.callout.points, weight: .semibold), "the chosen tab has no weight")
        focused.terminal.inert = true
        let card = Card(look: look)
        card.frame = NSRect(x: 0, y: 0, width: Card.width, height: 80)
        card.show("This terminal ended while you were away.", tone: .warning, button: "Open a New One", second: "Close")
        let ended = Count(marks(in: window)) + Count(marks(in: card))
        #expect(ended == Count([.accentFill]), "an ended terminal and its card draw \(ended)")
    }

    /// M2: the card's outline per look: none in light, so nothing lies over the panes; in dark,
    /// eight small views round the card, four 1 pt lines and four corners of the card's radius, none
    /// of them the card's size, none drawing a bitmap, none taking a click.
    @Test(arguments: [Look.light, .dark])
    func theCardEdgeIsNoneInLightAndSmallViewsInDark(_ look: Look) throws {
        let shell = try WorkbenchLookTests.shell(look)
        shell.window.contentView?.layoutSubtreeIfNeeded()
        let edge = shell.cardEdge
        #expect(edge.isHidden == (look.cardEdge == nil) && edge.isHidden == !look.palette.isDark, "the card's outline shows \(!edge.isHidden) in \(look.palette.isDark ? "dark" : "light")")
        #expect(edge.parts.count == 8 && edge.parts.allSatisfy { $0.isHidden == edge.isHidden }, "the outline's parts disagree on being shown")
        for part in edge.parts {
            let side = (Double(part.frame.width), Double(part.frame.height))
            #expect(min(side.0, side.1) <= 1 || max(side.0, side.1) <= Look.contentRadius, "a part of the outline is \(part.frame), a large view over the card")
            #expect(part.hitTest(NSPoint(x: part.frame.midX, y: part.frame.midY)) == nil && part.layer?.contents == nil, "a part of the outline takes a click or draws a bitmap")
        }
    }

    /// Minor 1: the count reads tokens, not exemptions: the chrome's colours inside the card are
    /// grey; ink at any strength is grey (no turned-over mark is built); a bar, a fill or a line in
    /// any of the accent's tokens is a mark; the error's tones are error marks.
    @Test(arguments: [Look.light, .dark])
    func theCountReadsEveryTokenOfTheAccentAndNoneOfTheChromeAsGround(_ look: Look) {
        #expect(look.marks(fill: Tint(look.chrome), area: 100) == [.neutralFill(100)] && look.marks(fill: Tint(look.chromeWash), area: 100) == [.neutralFill(100)])
        #expect(look.marks(fill: Tint(look.ink), area: 100) == [.inverseFill] && look.marks(fill: Tint(look.ink, alpha: 0.05), area: 100) == [.neutralFill(100)])
        for accent in [look.accent, look.accentHover, look.accentPressed] {
            #expect(look.marks(line: Tint(accent)) == [.accentLine] && look.marks(fill: Tint(accent), area: 100) == [.accentFill])
        }
        for error in [look.error, look.errorHover, look.errorPressed] {
            #expect(look.marks(line: Tint(error)) == [.errorInk] && look.marks(fill: Tint(error), area: 100) == [.errorFill])
        }
        #expect(look.marks(fill: Tint(look.surface), area: 100).isEmpty && look.marks(fill: Tint(look.canvas), area: 100).isEmpty)
    }
}

extension CalmTests {
    /// The terminal's cards (#486): information quiet on the paper, its fill the surface; a warning
    /// or an error in its tone, never grey; one primary at most, and the error ink only on an error.
    @Test(arguments: [Look.light, .dark])
    func everyCardIsQuietOrInItsTone(_ look: Look) {
        let said: [(String, BannerTone, String?)] = [
            ("Connecting to dock on the runner", .info, nil), ("No one holds the keyboard.", .info, "Take Keyboard"),
            ("Reconnecting to dock on the runner…", .info, "Reconnect Now"), ("This terminal ended: Workspace dock of harbor was stopped by ana.", .warning, "Open a New One"),
            ("The runner restarted, which ends every session it held.", .warning, "Open Again"), ("The terminal screen could not be made.", .error, "Try Again"),
        ]
        for (words, tone, button) in said {
            let card = Card(look: look)
            card.frame = NSRect(x: 0, y: 0, width: Card.width, height: 80)
            card.show(words, tone: tone, button: button)
            let count = Count(marks(in: card))
            #expect(count.neutral == 0 && count.tint == 0 && count.fill == (button == nil ? 0 : 1), "\(words): \(count)")
            #expect(count.error == (tone == .error ? 1 : 0), "\(words): \(count)")
            if tone == .info { #expect(card.bannerPaint.fill == look.surface && card.bannerPaint.edge == look.separator, "\(words) is not quiet on the paper") }
        }
    }

    static func found<View: NSView>(_: View.Type, in view: NSView) -> [View] {
        ((view as? View).map { [$0] } ?? []) + view.subviews.flatMap { found(View.self, in: $0) }
    }

    static func weight(_ label: NSTextField) -> NSFont.Weight? {
        guard let font = label.attributedStringValue.attribute(.font, at: 0, effectiveRange: nil) as? NSFont ?? label.font else { return nil }
        let traits = font.fontDescriptor.object(forKey: .traits) as? [NSFontDescriptor.TraitKey: Any]
        return (traits?[.weight] as? Double).map { NSFont.Weight(rawValue: $0) }
    }
}

extension CalmTests.Count {
    static func + (one: Self, other: Self) -> Self {
        var sum = one
        sum.fill += other.fill
        sum.line += other.line
        sum.tint += other.tint
        sum.error += other.error
        sum.inverse += other.inverse
        sum.neutral += other.neutral
        return sum
    }
}
