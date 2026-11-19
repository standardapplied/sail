import AppKit
import SailControls
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
    struct Count: Equatable, CustomStringConvertible {
        var fill = 0, line = 0, tint = 0, error = 0
        var neutral = 0.0

        init(_ marks: [Mark]) {
            for mark in marks {
                switch mark {
                case .accentFill: fill += 1
                case .accentLine: line += 1
                case .accentTint: tint += 1
                case .errorFill, .errorInk: error += 1
                case let .neutralFill(area): neutral += area
                }
            }
        }

        var accent: Int {
            fill + line + tint
        }

        var description: String {
            "\(fill) accent fills, \(line) accent lines, \(tint) accent tints, \(error) error marks, \(neutral) pt² of neutral fill"
        }
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

    /// A1: harbor and dock chosen: the rail's bar, the panel's project bar, dock's bar and Open, and
    /// no tinted fill.
    @Test(arguments: [Look.light, .dark])
    func thePlainWorkspacesPageHasFourMarks(_ look: Look) async throws {
        let (view, window, _) = try await WorkspacesViewTests.listed()
        defer { window.orderOut(nil) }
        view.look = look
        view.project.list.choose("dock")
        #expect(await RunnerTabTests.until { view.project.list.chosen == "dock" })
        view.layoutSubtreeIfNeeded()
        view.project.list.layoutSubtreeIfNeeded()
        let section = Count(marks(in: window)), rail = Self.rail(look)
        #expect(section == Count([.accentLine, .accentLine, .accentFill]), "the section draws \(section)")
        #expect(rail == Count([.accentLine]), "the rail draws \(rail)")
        #expect(section.accent + rail.accent == 4 && section.tint + rail.tint == 0)
        #expect(section.neutral == 0 && rail.neutral == 0, "grey at rest: the section \(section.neutral) pt², the rail \(rail.neutral) pt²")
        let dock = try #require(view.project.list.row("dock")), harbor = try #require(view.panel.list.row("harbor"))
        #expect(dock.paint.bar && dock.paint.strong, "dock, chosen, has no bar or no weight")
        let line = try #require(Self.found(WorkspaceLine.self, in: dock.view).first)
        #expect(Self.weight(line.title) == .semibold, "dock's name is not at full weight")
        let project = try #require(Self.found(SidebarRowCell.self, in: harbor.view).first)
        #expect(harbor.paint.bar && Self.weight(project.label) == .semibold && project.tile.chosen, "harbor, chosen, has no bar, no weight or no ink well")
        dock.view.isEmphasized = true
        let open = try #require(Self.found(SailButton.self, in: dock.view).first { $0.title == "Open" })
        #expect(open.keyEquivalent == "\r", "the list holding the keys is not said by Open's ⏎")
    }

    /// A5: three tabs and a split: the rail's bar, the chosen tab's bar, and the cursor of the pane
    /// with the keys; the other pane's cursor is ink. A6: an ended terminal's cursor is ink too, and
    /// the card's Open a New One is the only accent fill.
    @Test(arguments: [Look.light, .dark])
    func aTerminalPageWithASplitHasThreeMarks(_ look: Look) throws {
        let shell = try WorkbenchLookTests.shell(look)
        shell.select(.thisMac)
        let strip = shell.thisMac.strip
        strip.showsTabs = true
        strip.show(WorkbenchLookTests.items() + [TabStrip.Item(id: TabID(3), title: "web", zoomed: false)], selected: TabID(1))
        let fonts = try TerminalFonts(directory: LookTests.fonts)
        let typing = try TerminalView(fonts: fonts), other = try TerminalView(fonts: fonts)
        let split = NSStackView(views: [typing, other])
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 400), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = split
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        #expect(window.makeFirstResponder(typing) && typing.holdsTheKeys, "the typing pane holds no keys")
        let rail = Count(marks(in: shell.rail)), tabs = Count(marks(in: strip)), panes = Count(marks(in: window))
        #expect(rail == Count([.accentLine]) && tabs == Count([.accentLine]) && panes == Count([.accentLine]), "the rail draws \(rail), the tabs \(tabs), the panes \(panes)")
        #expect(rail.neutral + tabs.neutral + panes.neutral == 0, "grey at rest on the terminal page: \(rail), \(tabs), \(panes)")
        let chosen = try #require(strip.tabs.first)
        #expect(chosen.label.font == NSFont.systemFont(ofSize: Look.Step.callout.points, weight: .semibold) && !chosen.bar.isHidden, "the chosen tab has no weight or no underline")
        typing.inert = true
        let card = Card(look: look)
        card.frame = NSRect(x: 0, y: 0, width: Card.width, height: 80)
        card.show("This terminal ended while you were away.", tone: .warning, button: "Open a New One", second: "Close")
        let ended = Count(marks(in: window)) + Count(marks(in: card))
        #expect(ended == Count([.accentFill]), "an ended terminal and its card draw \(ended)")
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
        sum.neutral += other.neutral
        return sum
    }
}
