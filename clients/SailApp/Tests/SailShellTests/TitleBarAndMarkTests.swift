import AppKit
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailWorkbench
import Testing

/// The title bar's strip right to left, and the one wait that owns the Workspaces card today; both
/// with no window, so they run wherever the suite does. (The band's geometry in a window is
/// `WorkbenchLookTests`'.)
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
