import AppKit
import SailControls
import SailLook
@testable import SailShell
import Testing

/// What may lie over a terminal (``OverTerminals``): main's own views over its panes keep the rule
/// in both looks and are found; the two shapes #491's branch put over them (the card's edge as one
/// full-card bitmap view, then as one full-card shape layer) each break it; each way the rule names
/// a paint counts; and a bitmap given during a change of look is counted. No window is made.
@MainActor @Suite struct OverTerminalsTests {
    /// A card of two panes side by side, as the workbench lays them: each pane's terminal, the
    /// second pane dimmed by its scrim, the divider's 1 pt line between them with its 7 pt hit area
    /// over 3 pt of each, and the card's edge around it.
    @MainActor struct Bench {
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 900, height: 700))
        let card = NSView(frame: NSRect(x: 20, y: 20, width: 855, height: 660))
        let panes = [NSView(frame: NSRect(x: 0, y: 0, width: 427, height: 660)), NSView(frame: NSRect(x: 428, y: 0, width: 427, height: 660))]
        let terminals = [NSView(frame: NSRect(x: 0, y: 0, width: 427, height: 660)), NSView(frame: NSRect(x: 0, y: 0, width: 427, height: 660))]
        let scrim = Scrim(frame: NSRect(x: 0, y: 0, width: 427, height: 660))
        let divider = DividerView(frame: NSRect(x: 424, y: 0, width: 7, height: 660))
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

        func unfound(edged: Bool) -> [String] {
            zip(panes, terminals).enumerated().flatMap { index, pair in
                OverTerminals.unfound(over: pair.1, of: pair.0, in: root, dimmed: index == 1, edged: edged)
            }
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

    static let bitmap = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)?.makeImage()

    @Test(arguments: [Look.light, .dark])
    func mainsOwnViewsOverThePanesKeepTheRuleAndAreFound(look: Look) {
        let bench = Bench(look)
        let named = bench.terminals.flatMap { OverTerminals.covers(of: $0, in: bench.root) }.compactMap(\.view).map { String(describing: type(of: $0)) }
        #expect(named.contains("Scrim") && named.contains("DividerView"))
        #expect(look == .light || named.contains("Line") && named.contains("Arc"))
        #expect(bench.misses() == [], "\(bench.misses())")
        #expect(bench.unfound(edged: look == .dark) == [])
    }

    @Test func aWalkThatFindsNothingCannotPass() {
        let bench = Bench(.dark)
        [bench.scrim, bench.divider].forEach { $0.removeFromSuperview() }
        bench.edge.parts.forEach { $0.removeFromSuperview() }
        #expect(bench.misses() == [])
        #expect(bench.unfound(edged: true) == ["a divider", "a part of the card's edge", "its scrim", "a divider", "a part of the card's edge"])
    }

    @Test func anotherPanesScrimOverThisPaneBreaksIt() {
        let bench = Bench(.light)
        let stray = Scrim(frame: bench.panes[0].frame)
        stray.wantsLayer = true
        bench.panes[1].addSubview(stray)
        stray.frame = NSRect(x: -428, y: 0, width: 427, height: 660)
        #expect(bench.misses() == ["Scrim 427x660, a layer covers more than 1 pt or 8 × 8 pt of it"])
    }

    @Test func aDividerThickerThanItsHitAreaBreaksIt() {
        let bench = Bench(.light)
        bench.divider.frame = NSRect(x: 0, y: 0, width: 855, height: 660)
        #expect(bench.missedOnce(by: "DividerView", saying: "covers more than 1 pt or 8 × 8 pt of it"), "\(bench.misses())")
    }

    @Test func aBareLayerOnTheCardAboveThePanesBreaksIt() {
        let bench = Bench(.light)
        bench.card.wantsLayer = true
        let shape = CAShapeLayer()
        shape.frame = bench.card.bounds
        bench.card.layer?.addSublayer(shape)
        #expect(bench.missedOnce(by: "a bare CAShapeLayer", saying: "covers more than 1 pt or 8 × 8 pt of it"), "\(bench.misses())")
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

    @Test func aKnownViewHoldingABitmapBreaksIt() {
        let bench = Bench(.dark)
        bench.scrim.layer?.contents = Self.bitmap
        #expect(Self.bitmap != nil)
        #expect(bench.misses() == ["Scrim 427x660, a layer paints a bitmap or a shape"])
    }

    @Test func aKnownViewWithAShapeLayerBreaksIt() {
        let bench = Bench(.dark)
        let shape = CAShapeLayer()
        shape.frame = bench.scrim.bounds
        bench.scrim.layer?.addSublayer(shape)
        #expect(bench.misses() == ["Scrim 427x660, a layer paints a bitmap or a shape", "a bare CAShapeLayer 427x660 covers more than 1 pt or 8 × 8 pt of it"])
    }

    @Test func eachWayAViewPaintsCounts() {
        let colour = NSView(frame: NSRect(x: 0, y: 0, width: 10, height: 10))
        colour.wantsLayer = true
        colour.layer?.backgroundColor = NSColor.black.cgColor
        let bitmap = NSView(frame: colour.frame)
        bitmap.wantsLayer = true
        bitmap.layer?.contents = Self.bitmap
        #expect(!OverTerminals.paints(colour))
        #expect(OverTerminals.paints(BitmapEdge(frame: colour.frame)))
        #expect(OverTerminals.paints(ShapeEdge(frame: colour.frame)))
        #expect(OverTerminals.paints(bitmap))
    }

    @Test func aBitmapGivenToALargeLayerWhileWatchedIsCounted() {
        let bench = Bench(.dark)
        let watch = PaintWatch()
        watch.watch(OverTerminals.largeLayers(over: bench.terminals[1], of: bench.panes[1], in: bench.root))
        bench.scrim.layer?.backgroundColor = NSColor.white.cgColor
        #expect(watch.paints == 0)
        bench.scrim.layer?.contents = Self.bitmap
        #expect(watch.paints == 1)
        watch.stop()
        bench.scrim.layer?.contents = Self.bitmap
        #expect(watch.paints == 1)
    }

    @Test func thePanesOwnCardMayCoverItButABitmapGivenItDuringASwitchIsCounted() {
        let bench = Bench(.dark)
        let card = Card(look: .dark)
        card.show("This terminal ended.")
        card.wantsLayer = true
        card.frame = NSRect(x: 40, y: 200, width: 347, height: 120)
        bench.panes[0].addSubview(card)
        #expect(bench.misses() == [])
        let watch = PaintWatch()
        watch.watch(OverTerminals.largeLayers(over: bench.terminals[0], of: bench.panes[0], in: bench.root))
        card.layer?.contents = Self.bitmap
        #expect(watch.paints == 1)
        watch.stop()
    }

    /// The pane's own card is excepted from covering it only because a change of look paints it
    /// nothing: its layers take new colours, and no bitmap, through both looks and back.
    @Test func aChangeOfLookGivesTheCardNewColoursAndNoBitmap() {
        let bench = Bench(.dark)
        let card = Card(look: .dark)
        card.show("This terminal ended.", tone: .warning, button: "Open a New One")
        card.frame = NSRect(x: 40, y: 200, width: 347, height: 120)
        bench.panes[0].addSubview(card)
        card.layoutSubtreeIfNeeded()
        card.displayIfNeeded()
        let watch = PaintWatch()
        watch.watch(OverTerminals.largeLayers(over: bench.terminals[0], of: bench.panes[0], in: bench.root) + [card.layer].compactMap { $0 } + card.subviews.compactMap(\.layer))
        let dark = card.layer?.backgroundColor
        card.look = .light
        card.displayIfNeeded()
        #expect(card.layer?.backgroundColor != dark && card.layer?.backgroundColor == NSColor(Tint(BannerPaint(.warning, look: .light).fill)).cgColor)
        card.look = .dark
        card.displayIfNeeded()
        #expect(watch.paints == 0, "a change of look gave the card's layers \(watch.paints) bitmaps")
        watch.stop()
    }

    @Test func anotherPanesCardIsNoException() {
        let bench = Bench(.dark)
        let card = Card(look: .dark)
        card.show("This terminal ended.")
        card.frame = NSRect(x: -393, y: 200, width: 347, height: 120)
        bench.panes[1].addSubview(card)
        #expect(bench.misses().first?.hasPrefix("Card 347x120") == true, "\(bench.misses())")
    }

    @Test func aSliverOrACornerIsSmallAndNothingMoreIs() {
        #expect(OverTerminals.small(NSRect(x: 0, y: 0, width: 844, height: 1)))
        #expect(OverTerminals.small(NSRect(x: 0, y: 0, width: 1, height: 844)))
        #expect(!OverTerminals.small(NSRect(x: 0, y: 0, width: 844, height: 2)))
        #expect(!OverTerminals.small(NSRect(x: 0, y: 0, width: 2, height: 844)))
        #expect(OverTerminals.small(NSRect(x: 0, y: 0, width: 8, height: 8)))
        #expect(!OverTerminals.small(NSRect(x: 0, y: 0, width: 9, height: 8)))
    }
}
