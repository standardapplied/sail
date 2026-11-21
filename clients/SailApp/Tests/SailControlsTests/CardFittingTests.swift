import AppKit
import Foundation
import SailKitTestSupport
@testable import SailControls
import SailDesign
import SailLook
import Testing

/// `Card.fitting` keeps a card's words to two lines by cutting a name in its middle, measured as the
/// card draws them, at the width the card takes: the widest card, and the card at the least window
/// (480 pt, less the rail and the card's margins). No window is shown.
@MainActor @Suite struct CardFittingTests {
    init() {
        ExitWatch.install()
    }

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

    /// A shown card's mark is drawn there and then, on the main thread, at the screen's scale: its
    /// layer holds the bitmap, never a drawing handler for AppKit to call later from another thread.
    @Test func theMarkIsDrawnWhenShownAndNotLeftForLater() throws {
        for tone in BannerTone.allCases {
            let card = Self.card(saying: "harbor is still running.", width: 420)
            card.show("harbor is still running.", tone: tone)
            let contents = try #require(card.markContents as CFTypeRef?, "the \(tone) card's mark has no contents")
            #expect(CFGetTypeID(contents) == CGImage.typeID, "the \(tone) card's mark is \(type(of: card.markContents!)), not a bitmap")
            #expect((contents as! CGImage).width == Int(Banner.mark * 2), "the \(tone) card's mark is not at the scale of 2")
        }
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
