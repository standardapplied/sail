import AppKit
import SailLook
@testable import SailShell
import Testing

/// What may lie over a terminal (``OverTerminals``): main's own views over its panes keep the rule
/// in both looks, and the two shapes #491's branch put over them (the card's edge as one full-card
/// bitmap view, then as one full-card shape layer) each break it. No window is made.
@MainActor @Suite struct OverTerminalsTests {
    /// A card of two panes side by side, as the workbench lays them: each pane's terminal, the
    /// second pane dimmed by its scrim, the divider between them, and the card's edge around it.
    @MainActor struct Bench {
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 900, height: 700))
        let card = NSView(frame: NSRect(x: 20, y: 20, width: 860, height: 660))
        let panes = [NSView(frame: NSRect(x: 0, y: 0, width: 427, height: 660)), NSView(frame: NSRect(x: 433, y: 0, width: 427, height: 660))]
        let terminals = [NSView(frame: NSRect(x: 0, y: 0, width: 427, height: 660)), NSView(frame: NSRect(x: 0, y: 0, width: 427, height: 660))]
        let scrim = Scrim(frame: NSRect(x: 0, y: 0, width: 427, height: 660))
        let divider = DividerView(frame: NSRect(x: 425, y: 0, width: 10, height: 660))
        let edge: CardEdge

        init(_ look: Look) {
            edge = CardEdge(look: look)
            root.wantsLayer = true
            root.addSubview(card)
            for (pane, terminal) in zip(panes, terminals) {
                card.addSubview(pane)
                pane.addSubview(terminal)
            }
            scrim.wantsLayer = true
            scrim.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.3).cgColor
            panes[1].addSubview(scrim)
            card.addSubview(divider)
            edge.place(around: card, in: root)
            root.layoutSubtreeIfNeeded()
        }

        func misses() -> [String] {
            zip(panes, terminals).flatMap { pane, terminal in OverTerminals.misses(over: terminal, of: pane, in: root) }
        }

        /// Whether each pane's terminal has exactly one miss, `view`'s, covering all of it, saying `why`.
        func missedOnce(by view: String, saying why: String) -> Bool {
            let said = misses()
            return said.count == terminals.count && said.allSatisfy { $0.hasPrefix("\(view) 427x660") && $0.hasSuffix(why) }
        }
    }

    /// The card's edge as #491's branch first drew it: one view the card's size painting a bitmap.
    final class BitmapEdge: NSView {
        override func draw(_: NSRect) {
            NSColor.gray.setStroke()
            NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: 6, yRadius: 6).stroke()
        }
    }

    /// The card's edge as #491's branch drew it next: one view the card's size whose layer is a shape.
    final class ShapeEdge: NSView {
        let shape = CAShapeLayer()

        override init(frame: NSRect) {
            super.init(frame: frame)
            shape.fillColor = nil
            shape.path = CGPath(roundedRect: CGRect(origin: .zero, size: frame.size).insetBy(dx: 0.5, dy: 0.5), cornerWidth: 6, cornerHeight: 6, transform: nil)
            layer = shape
            wantsLayer = true
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            nil
        }
    }

    @Test(arguments: [Look.light, .dark])
    func mainsOwnViewsOverThePanesKeepTheRule(look: Look) {
        let bench = Bench(look)
        let named = bench.terminals.flatMap { OverTerminals.covers(of: $0, in: bench.root) }.map { String(describing: type(of: $0.view)) }
        #expect(named.contains("Scrim") && named.contains("DividerView"))
        #expect(look == .light || named.contains("Line") && named.contains("Arc"))
        #expect(bench.misses() == [], "\(bench.misses())")
    }

    @Test(arguments: [Look.light, .dark])
    func aCardSizedBitmapOverThePanesBreaksIt(look: Look) {
        let bench = Bench(look)
        bench.root.addSubview(BitmapEdge(frame: bench.card.frame), positioned: .above, relativeTo: bench.card)
        #expect(bench.missedOnce(by: "BitmapEdge", saying: "covers more than 1 pt or 8 × 8 pt of it"), "\(bench.misses())")
    }

    @Test(arguments: [Look.light, .dark])
    func aCardSizedShapeLayerOverThePanesBreaksIt(look: Look) {
        let bench = Bench(look)
        bench.root.addSubview(ShapeEdge(frame: bench.card.frame), positioned: .above, relativeTo: bench.card)
        #expect(bench.missedOnce(by: "ShapeEdge", saying: "covers more than 1 pt or 8 × 8 pt of it"), "\(bench.misses())")
    }

    @Test func aKnownViewThatPaintsBreaksIt() {
        let bench = Bench(.dark)
        let bitmap = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)?.makeImage()
        bench.scrim.layer?.contents = bitmap
        #expect(bitmap != nil)
        #expect(bench.misses() == ["Scrim 427x660, a layer paints a bitmap or a shape"])
    }

    @Test func aSliverOrACornerIsSmallAndNothingMoreIs() {
        #expect(OverTerminals.small(NSRect(x: 0, y: 0, width: 844, height: 1)))
        #expect(OverTerminals.small(NSRect(x: 0, y: 0, width: 1, height: 654)))
        #expect(OverTerminals.small(NSRect(x: 0, y: 0, width: 8, height: 8)))
        #expect(!OverTerminals.small(NSRect(x: 0, y: 0, width: 9, height: 8)))
        #expect(!OverTerminals.small(NSRect(x: 0, y: 0, width: 2, height: 600)))
    }
}
