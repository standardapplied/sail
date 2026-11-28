import AppKit
import Foundation
import SailKitTestSupport
@testable import SailControls
import SailDesign
import SailLook
import Testing

/// The words a card, a sheet and an empty state show take no click: a selectable label clicked
/// becomes the first responder, takes the keys from what it lies over and hands its words to the
/// field editor, drawn in the field's own font and colour, without their ink or line height.
@MainActor @Suite(.timeLimit(.minutes(1))) struct InertWordsTests {
    init() {
        ExitWatch.install()
    }

    @Test func noSharedWordsTakeAClick() throws {
        let look = Look.light, titleFont = try TitleFont(directory: ControlsTests.fonts)
        let card = Card(look: look)
        card.show("harbor is still running.", tone: .warning)
        let sheet = Sheet(title: "Delete dock?", body: "Its files go with it.", confirm: "Delete", kind: .destructive, look: look, titleFont: titleFont)
        let empty = EmptyState(icon: .box, title: "No workspaces in harbor yet", body: "A workspace is your own copy of harbor.", actions: [], look: look, titleFont: titleFont, mono: try ControlsTests.mono())
        let shown: [(String, NSView)] = [("card", card), ("sheet", try #require(sheet.window.contentView)), ("empty state", empty)]
        let taking = shown.flatMap { name, view in Self.words(in: view).filter(\.acceptsFirstResponder).map { "\(name): “\($0.stringValue)”" } }
        #expect(shown.allSatisfy { !Self.words(in: $1).isEmpty } && taking.isEmpty, "these take a click: \(taking)")
    }

    static func words(in view: NSView) -> [NSTextField] {
        ((view as? NSTextField).map { [$0] } ?? []) + view.subviews.flatMap(words)
    }
}
