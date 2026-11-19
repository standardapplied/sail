import AppKit
import Foundation
@testable import SailControls
import SailLook
import Testing

/// `Card.fitting` keeps a card's words to two lines by cutting a name in its middle, measured as the
/// card draws them, at the width the card takes: the widest card, and the card at the least window
/// (480 pt, less the rail and the card's margins). No window is shown.
@MainActor @Suite struct CardFittingTests {
    static let sentence = { (tab: String) in "\(tab) is still running: the keyboard is with your other window or Mac." }
    nonisolated static let widths = [420.0, 384.0]

    /// A card of `width` saying `words`, laid out as the workbench lays it out.
    static func card(saying words: String, width: Double) -> Card {
        let card = Card(look: .light)
        card.show(words)
        let room = NSView(frame: NSRect(x: 0, y: 0, width: width, height: 400))
        card.translatesAutoresizingMaskIntoConstraints = false
        room.addSubview(card)
        NSLayoutConstraint.activate([
            card.leadingAnchor.constraint(equalTo: room.leadingAnchor), card.topAnchor.constraint(equalTo: room.topAnchor),
            card.widthAnchor.constraint(equalToConstant: width),
        ])
        room.layoutSubtreeIfNeeded()
        return card
    }

    /// A tab of the longest workspace name the server allows, and a long rename, are cut in the
    /// middle so the card keeps to two lines (60 pt) at each width.
    @Test(arguments: widths)
    func aLongNameIsCutSoTheCardKeepsToTwoLines(_ width: Double) {
        for name in ["harbor · " + String(repeating: "n", count: 63), String(repeating: "a renamed tab that goes on and on ", count: 4)] {
            let said = Card.fitting(name, width: width, in: Self.sentence)
            let card = Self.card(saying: said, width: width)
            #expect(said.contains("…") && said.hasSuffix(" is still running: the keyboard is with your other window or Mac."), "\(said)")
            #expect(Double(card.frame.height) <= 60, "at \(width) pt the card is \(card.frame.height) pt: \(said)")
        }
        #expect(Card.fitting("harbor · dock", width: width, in: Self.sentence) == Self.sentence("harbor · dock"), "a short name is left whole")
    }

    /// When the words around the name need more than two lines on their own, no cut can win, so the
    /// name is left whole rather than cut to its ends.
    @Test(arguments: widths)
    func aNameIsLeftWholeWhenNoCutCanFit(_ width: Double) {
        let long = { (name: String) in "\(name) " + String(repeating: "and the words around it go on ", count: 12) }
        let name = "harbor · " + String(repeating: "n", count: 63)
        #expect(Card.fitting(name, width: width, in: long) == long(name))
    }
}
