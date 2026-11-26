import AppKit
@testable import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailWorkbench
import Testing

/// The title bar's strip right to left, and the one wait that owns the Workspaces card today. (The
/// band's geometry in a window is `WorkbenchLookTests`'.)
@MainActor @Suite struct TitleBarAndMarkTests {
    init() {
        ExitWatch.install()
    }

    /// Asking the control plane who is signed in is Sail's mark drawing itself over what it waits
    /// for; the card's other states stay empty states.
    @Test func askingWhoIsSignedInIsTheMarkDrawingItself() throws {
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        view.waitForSignIn()
        let mark = try #require(view.card.subviews.first as? BrandMark, "the card holds \(view.card.subviews)")
        #expect(view.card.subviews.count == 1 && mark.presentation == .drawing(label: "Asking who is signed in") && mark.markSide == BrandMark.side)
        #expect(view.text == "Workspaces\nAsking who is signed in")
        view.signedOut()
        #expect(view.card.subviews.count == 1 && view.card.subviews.first is EmptyState, "signed out, the card holds \(view.card.subviews)")
    }

    /// A redraw while asking, for a change of look or the same wait told again, keeps the mark that
    /// is drawing and its cycle: only its look changes.
    @Test func aRedrawWhileAskingKeepsTheMarkDrawingOn() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = false
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 480), styleMask: [.borderless], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = view
        view.waitForSignIn()
        let mark = try #require(view.card.subviews.first as? BrandMark, "the card holds \(view.card.subviews)")
        let cycle = try #require(mark.sigma.animation(forKey: BrandMark.drawKey), "in a window, asking, the mark does not draw")
        view.look = .dark
        view.waitForSignIn()
        #expect(view.card.subviews.count == 1 && view.card.subviews.first === mark, "redrawn, the card holds \(view.card.subviews), not the mark it drew")
        #expect(mark.look == .dark && mark.sigma.animation(forKey: BrandMark.drawKey) === cycle, "redrawn, the mark is not in the dark look or began its cycle again")
    }

    /// Right to left is AppKit's one answer, the window's title bar's direction: whatever the
    /// strip's own direction was, the strip is placed and runs its tabs that way, its first tab
    /// `buttonsGap` from the window buttons.
    @Test func theStripRunsTheWayTheTitleBarIsLaidOut() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        let window = shell.window
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        shell.select(.thisMac)
        let strip = shell.thisMac.strip, titleBar = window.windowTitlebarLayoutDirection
        strip.userInterfaceLayoutDirection = titleBar == .rightToLeft ? .leftToRight : .rightToLeft
        shell.placeTheStrip()
        strip.show(WorkbenchLookTests.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        let buttons = try #require(ShellApp.buttons(of: window)), first = try #require(strip.tabs.first)
        let tab = first.convert(first.bounds, to: nil)
        let gap = titleBar == .rightToLeft ? Double(buttons.minX - tab.maxX) : Double(tab.minX - buttons.maxX)
        #expect(strip.userInterfaceLayoutDirection == titleBar, "the strip runs \(strip.userInterfaceLayoutDirection.rawValue), the title bar \(titleBar.rawValue)")
        #expect(abs(gap - ShellApp.buttonsGap) < 0.5, "the first tab \(tab) is \(gap) pt from the window buttons \(buttons)")
    }

    /// Right to left the strip runs from its right edge, beside the window buttons AppKit puts
    /// there: the first tab ends `leading` from it, the next tab and `+` follow leftward, and a
    /// dragged tab is placed by its middle read from that edge.
    @Test func rightToLeftTheTabsRunFromTheRight() throws {
        let strip = TabStrip(look: .light)
        strip.userInterfaceLayoutDirection = .rightToLeft
        strip.frame = NSRect(x: 0, y: 0, width: 600, height: ShellApp.bandHeight)
        strip.leading = 16
        strip.show(WorkbenchLookTests.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        let first = try #require(strip.tabs.first), second = try #require(strip.tabs.last)
        #expect(abs(Double(first.frame.maxX) - 584) < 0.5, "the first tab ends at \(first.frame.maxX), not 16 pt from the strip's right edge")
        #expect(second.frame.maxX <= first.frame.minX && strip.add.frame.maxX <= second.frame.minX, "the tabs \(first.frame) \(second.frame) and + \(strip.add.frame) do not run leftward")
        var moved: (TabID, Int)?
        strip.onMove = { moved = ($0, $1) }
        first.dragging = true
        first.setFrameOrigin(NSPoint(x: second.frame.minX, y: first.frame.minY))
        first.onDrop(first)
        #expect(moved?.0 == TabID(1) && moved?.1 == 1, "the first tab dropped on the second's place moved to \(String(describing: moved))")
    }
}
