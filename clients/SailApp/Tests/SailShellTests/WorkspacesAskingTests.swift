import AppKit
import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import Testing

/// The one wait that owns the Workspaces card today, asking the control plane who is signed in,
/// is Sail's mark drawing itself over what it waits for; the card's other states stay empty states.
@MainActor @Suite struct WorkspacesAskingTests {
    init() {
        ExitWatch.install()
    }

    @Test func askingWhoIsSignedInIsTheMarkDrawingItself() throws {
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        view.waitForSignIn()
        let mark = try #require(view.card.subviews.first as? BrandMark, "the card holds \(view.card.subviews)")
        #expect(view.card.subviews.count == 1 && mark.presentation == .drawing(label: "Asking who is signed in") && mark.markSide == BrandMark.side)
        #expect(view.text == "Workspaces\nAsking who is signed in")
        view.signedOut()
        #expect(view.card.subviews.count == 1 && view.card.subviews.first is EmptyState, "signed out, the card holds \(view.card.subviews)")
    }
}
