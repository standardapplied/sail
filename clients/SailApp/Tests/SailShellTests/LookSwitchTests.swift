import AppKit
import Foundation
import SailLook
@testable import SailShell
import SailVT
import SailWorkbench
import Testing

/// A change of look recolours and lays nothing out, and colours each symbol once per look (the
/// spec `sail-app-a-change-of-look-holds-the-main-thread-briefly`, I2 and I3).
@MainActor @Suite struct LookSwitchTests {
    /// Every view of the window, its frame view's included: the strip lives in the title bar.
    static func views(under view: NSView) -> [NSView] {
        [view] + view.subviews.flatMap { views(under: $0) }
    }

    static func named(_ views: [NSView]) -> String {
        views.map { "\(type(of: $0))\($0.identifier.map { " \($0.rawValue)" } ?? "")" }.joined(separator: ", ")
    }

    /// I2: with four panes in the workbench and the window laid out and drawn, a change of look
    /// leaves no stack view to declare and diff its constraints again, and laying the window out
    /// once more moves no view.
    @Test func aChangeOfLookLaysNothingOut() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.select(.thisMac)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let bench = shell.thisMac
        let place = Place.thisMac(folder: "/")
        for name in ["a", "b", "c", "d"] {
            let id = bench.model.newTab(session: "mac-look-\(name)", place: place)
            let pane = try #require(bench.model.panes[id])
            try #require(bench.makeView(pane, start: .attach, points: 15) != nil)
        }
        let frame = try #require(shell.window.contentView?.superview)
        for look in [Look.dark, Look.light, Look.dark] {
            frame.layoutSubtreeIfNeeded()
            shell.window.displayIfNeeded()
            let views = Self.views(under: frame)
            let flaggedBefore = Set(views.filter { $0.needsUpdateConstraints || $0.needsLayout }.map { ObjectIdentifier($0) })
            let before = views.map(\.frame)
            let laidOut = bench.tabLayouts
            shell.apply(look)
            #expect(bench.tabLayouts == laidOut, "a change of look laid the workbench's tabs out \(bench.tabLayouts - laidOut) times")
            let newly = views.filter { ($0.needsUpdateConstraints || $0.needsLayout) && !flaggedBefore.contains(ObjectIdentifier($0)) }
            let flagged = newly.filter { $0 is NSStackView }
            #expect(flagged.isEmpty, "a change of look has \(flagged.count) stack views declare their constraints again: \(Self.named(flagged))")
            print("a change of look to \(look.palette.isDark ? "dark" : "light") flags for constraints or layout: \(newly.isEmpty ? "nothing" : Self.named(newly))")
            frame.layoutSubtreeIfNeeded()
            let moved = zip(views, before).filter { $0.0.frame != $0.1 }.map(\.0)
            #expect(moved.isEmpty, "a change of look moved \(Self.named(moved))")
        }
    }

    /// I3: the rail's and the strip's symbols are non-template images coloured for the look, no
    /// button carries a tint for Core Animation to apply, and a look returned to hands back the
    /// very images it had.
    @Test func symbolsAreColouredOncePerLook() throws {
        let shell = try WorkbenchLookTests.shell(.dark)
        let strip = shell.thisMac.strip
        strip.showsTabs = true
        strip.show(WorkbenchLookTests.items(), selected: TabID(1))
        let rail = Rail.Item.allCases.compactMap { shell.rail.button($0) }
        let strips: [NSButton] = [strip.add as NSButton] + strip.tabs.map { $0.close as NSButton }
        let marks = strip.tabs.map(\.zoomMark)
        func images() -> [NSImage?] {
            rail.map(\.image) + strips.map(\.image) + marks.map(\.image)
        }
        shell.apply(.dark)
        let dark = images()
        shell.apply(.light)
        let light = images()
        shell.apply(.dark)
        let again = images()
        #expect(zip(dark, again).allSatisfy { $0 === $1 }, "a look returned to made its symbols again")
        #expect(zip(dark, light).allSatisfy { $0 !== $1 }, "a symbol kept its image across a change of look")
        for button in rail + strips {
            #expect(button.contentTintColor == nil, "\(button.toolTip ?? "a button") is tinted at each change of look")
            #expect(button.image?.isTemplate == false, "\(button.toolTip ?? "a button")'s symbol is a template")
        }
        #expect(marks.allSatisfy { $0.contentTintColor == nil && $0.image?.isTemplate == false })
    }

    /// The prominent button draws what the system's would: its fill, pressed and not, and a label
    /// too long for it cut short with an ellipsis inside its edges, not run past them.
    @Test func theProminentButtonDrawsItsPressedFillAndCutsALongLabel() throws {
        let button = AccentButton("Open", look: .dark, target: nil, action: #selector(NSResponder.cancelOperation(_:)))
        button.frame = NSRect(x: 0, y: 0, width: 120, height: 32)
        let corner = NSPoint(x: 10, y: 6)
        func near(_ found: [Double], _ wanted: RGB) -> Bool {
            found.count == 3 && zip(found, RunnerTabTests.channels(wanted)).allSatisfy { abs($0 - $1) <= 3 }
        }
        #expect(near(RunnerTabTests.colour(of: button, at: corner), Look.dark.accent), "at rest, the accent")
        button.isHighlighted = true
        #expect(near(RunnerTabTests.colour(of: button, at: corner), Look.dark.accentPressed), "pressed, the accent's pressed shade")
        button.isHighlighted = false
        button.setTitle(String(repeating: "Open the workspace ", count: 6))
        button.frame = NSRect(x: 0, y: 0, width: 120, height: 32)
        let ink = try #require(WorkbenchLookTests.ink(button, darkerThan: 300, except: Look.dark.accent), "the label is drawn")
        #expect(ink.minX >= Look.Space.s - 1 && ink.maxX <= 120 - Look.Space.s + 1, "a long label is cut short inside the button: \(ink)")
    }

    /// M3: a pane hidden at a change of look does no work for it, however many changes come; shown
    /// again, it paints once, in the look now.
    @Test func aHiddenPanePaintsNothingAtASwitchAndOnceWhenShownInTheLookNow() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        shell.select(.thisMac)
        #expect(shell.account.isHidden && shell.workspaces.isHidden)
        let account = shell.account.lookPaints, workspaces = shell.workspaces.lookPaints
        for look in [Look.dark, .light, .dark, .light, .dark] {
            shell.apply(look)
        }
        #expect(shell.account.lookPaints == account, "the hidden Account pane painted \(shell.account.lookPaints - account) times")
        #expect(shell.workspaces.lookPaints == workspaces, "the hidden Workspaces pane painted \(shell.workspaces.lookPaints - workspaces) times")
        let canvas = NSColor(Look.dark.canvas).cgColor
        shell.select(.account)
        #expect(shell.account.lookPaints == account + 1 && shell.account.layer?.backgroundColor == canvas, "shown, it paints once, in the look now")
        shell.select(.workspaces)
        #expect(shell.workspaces.lookPaints == workspaces + 1 && shell.workspaces.layer?.backgroundColor == canvas, "shown, it paints once, in the look now")
    }

    /// I3: once both looks were shown, a change of look resolves no symbol: every image it hands a
    /// button was made before (``SymbolInk/resolutions``).
    @Test func aChangeOfLookBetweenLooksShownResolvesNoSymbol() throws {
        let shell = try WorkbenchLookTests.shell(.dark)
        let strip = shell.thisMac.strip
        strip.showsTabs = true
        strip.show(WorkbenchLookTests.items(), selected: TabID(1))
        shell.apply(.light)
        shell.apply(.dark)
        let resolved = SymbolInk.resolutions
        for look in [Look.light, .dark, .light, .dark] {
            shell.apply(look)
        }
        #expect(SymbolInk.resolutions == resolved, "four changes of look resolved \(SymbolInk.resolutions - resolved) symbols")
    }
}
