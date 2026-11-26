import AppKit
@testable import SailControls
import SailLook
import Testing

/// Sail's mark: the design's paths, strokes and inks in both presentations and both looks, drawn
/// upright; its label the eyebrow and its VoiceOver words; Mast's cycle, whole; and still under
/// Reduce Motion. In `ControlsTests`, which runs serialized, since these set `Motion.shared`.
extension ControlsTests {
    struct Frames: Equatable, CustomStringConvertible {
        let path: String
        let values: [Double]
        let times: [Double]
        let eased: Bool
        let duration: Double

        var description: String {
            "\(path) \(values) at \(times)\(eased ? " eased" : "") over \(duration) s"
        }
    }

    static func frames(_ animation: CAAnimation?) -> [Frames] {
        if let group = animation as? CAAnimationGroup { return (group.animations ?? []).flatMap { frames($0) } }
        guard let keyframes = animation as? CAKeyframeAnimation else { return [] }
        func points(_ function: CAMediaTimingFunction) -> [Float] {
            (1 ... 2).flatMap { index in
                var point: [Float] = [0, 0]
                function.getControlPoint(at: index, values: &point)
                return point
            }
        }
        let ease = points(CAMediaTimingFunction(name: .easeInEaseOut))
        let functions = keyframes.timingFunctions ?? []
        return [Frames(
            path: keyframes.keyPath ?? "", values: (keyframes.values ?? []).compactMap { ($0 as? NSNumber)?.doubleValue },
            times: (keyframes.keyTimes ?? []).map(\.doubleValue), eased: !functions.isEmpty && functions.allSatisfy { points($0) == ease }, duration: keyframes.duration
        )]
    }

    static func shown(_ mark: BrandMark) -> NSWindow {
        let content = NSView(frame: NSRect(origin: .zero, size: mark.intrinsicContentSize))
        content.wantsLayer = true
        let window = NSWindow(contentRect: content.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = content
        mark.frame = content.bounds
        content.addSubview(mark)
        window.orderFront(nil)
        content.layoutSubtreeIfNeeded()
        return window
    }

    static func channels(_ tint: Tint) -> [Double] {
        [Double(tint.rgb.red), Double(tint.rgb.green), Double(tint.rgb.blue)]
    }

    /// One cycle of 1.8 s, repeating, each step eased in and out: the sigma's end drawn by 45 %, held
    /// to 70 %, its start following it off; its opacity .35, full, full, .35; the line's .2, full, .2.
    @Test func theMarksCycleIsMastsWhole() throws {
        #expect([Look.Timing.draw, Look.Timing.drawn, Look.Timing.held] == [1.8, 0.45, 0.7])
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = false
        let mark = BrandMark(.drawing(label: nil), look: .light, mono: try Self.mono())
        let window = Self.shown(mark)
        defer { window.orderOut(nil) }
        let draw = try #require(mark.sigma.animation(forKey: BrandMark.drawKey)), flare = try #require(mark.line.animation(forKey: BrandMark.drawKey))
        let sigma = [0, 0.45, 0.7, 1.0]
        #expect(Self.frames(draw) == [
            Frames(path: "strokeEnd", values: [0, 1, 1, 1], times: sigma, eased: true, duration: 1.8),
            Frames(path: "strokeStart", values: [0, 0, 0, 1], times: sigma, eased: true, duration: 1.8),
            Frames(path: "opacity", values: [0.35, 1, 1, 0.35], times: sigma, eased: true, duration: 1.8),
        ])
        #expect(Self.frames(flare) == [Frames(path: "opacity", values: [0.2, 1, 0.2], times: [0, 0.45, 1], eased: true, duration: 1.8)])
        for animation in [draw, flare] {
            #expect(animation.duration == 1.8 && animation.repeatCount == .infinity && animation.speed == 1, "\(animation) is not one repeating 1.8 s cycle")
        }
    }

    /// Under Reduce Motion the drawing mark is whole and still: no animation on any of its layers,
    /// the sigma drawn end to end and both at full opacity; it draws again once motion is back, and
    /// holds still again live. Held at a point of its cycle (the kit's shots), it stands there.
    @Test func reduceMotionLeavesTheMarkStill() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = true
        let mark = BrandMark(.drawing(label: "Connecting"), look: .dark, mono: try Self.mono())
        let window = Self.shown(mark)
        defer { window.orderOut(nil) }
        func animated() -> [String] {
            [mark.glyph.layer, mark.stage, mark.tile, mark.sigma, mark.line].flatMap { $0?.animationKeys() ?? [] }
        }
        func whole() -> Bool {
            mark.sigma.strokeStart == 0 && mark.sigma.strokeEnd == 1 && mark.sigma.opacity == 1 && mark.line.opacity == 1
        }
        #expect(animated().isEmpty && whole(), "shown under Reduce Motion the mark moves: \(animated())")
        motion.reduced = false
        #expect(mark.isDrawing, "motion back, the mark does not draw")
        motion.reduced = true
        #expect(animated().isEmpty && whole(), "Reduce Motion turned on live left \(animated())")
        mark.held = 0.85
        let held = try #require(mark.sigma.animation(forKey: BrandMark.drawKey))
        #expect(held.speed == 0 && abs(held.timeOffset - 0.85 * 1.8) < 1e-9, "held at 85 % the mark is at speed \(held.speed), \(held.timeOffset) s in")
        motion.reduced = false
        let still = BrandMark(.still, look: .dark, mono: try Self.mono())
        let other = Self.shown(still)
        defer { other.orderOut(nil) }
        #expect(!still.isDrawing, "the still mark moves")
    }

    /// Held at a point of its cycle and then let go, the mark draws itself again from the start of
    /// its cycle, at full speed.
    @Test func aHeldMarkLetGoDrawsAgain() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = false
        let mark = BrandMark(.drawing(label: nil), look: .light, mono: try Self.mono())
        let window = Self.shown(mark)
        defer { window.orderOut(nil) }
        mark.held = 0.45
        mark.held = nil
        for (name, layer) in [("sigma", mark.sigma), ("line", mark.line)] {
            let animation = try #require(layer.animation(forKey: BrandMark.drawKey), "let go, the \(name) has no animation")
            #expect(animation.speed == 1 && animation.timeOffset == 0, "let go, the \(name) runs at speed \(animation.speed), \(animation.timeOffset) s in")
        }
    }

    /// The drawing mark moves only while it can be seen: hidden, under a hidden ancestor (a pane
    /// not chosen) or out of its window it has no animation, and shown again it draws.
    @Test func theMarkDrawsOnlyWhileItCanBeSeen() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = false
        let mark = BrandMark(.drawing(label: nil), look: .light, mono: try Self.mono())
        let window = Self.shown(mark)
        defer { window.orderOut(nil) }
        let pane = try #require(mark.superview)
        func animated() -> [String] {
            [mark.sigma, mark.line].flatMap { $0.animationKeys() ?? [] }
        }
        #expect(mark.isDrawing, "shown, the mark does not draw")
        mark.isHidden = true
        #expect(animated().isEmpty, "hidden, the mark keeps \(animated())")
        mark.isHidden = false
        #expect(mark.isDrawing, "shown again, the mark does not draw")
        pane.isHidden = true
        #expect(animated().isEmpty, "under a hidden pane, the mark keeps \(animated())")
        pane.isHidden = false
        #expect(mark.isDrawing, "its pane shown again, the mark does not draw")
        mark.removeFromSuperview()
        #expect(animated().isEmpty, "out of its window, the mark keeps \(animated())")
    }

    /// The design's mark at 44 pt: the 34/150 tile in `inverse` under the sigma in `onInverse`, or no
    /// tile and the sigma in `textSecondary`; the line in the accent; 14 and 6 of 150 with round caps
    /// and joins; the line above the sigma as drawn, not under it.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func theMarkIsSailsMark(_ name: String, _ look: Look) throws {
        let scale = 44.0 / 150
        for presentation in [BrandMark.Presentation.still, .drawing(label: nil)] {
            let mark = BrandMark(presentation, look: look, mono: try Self.mono())
            let window = Self.shown(mark)
            defer { window.orderOut(nil) }
            let still = presentation == .still
            #expect(mark.tile.isHidden == !still, "\(name) \(presentation): the tile is \(mark.tile.isHidden ? "hidden" : "shown")")
            let tile = try #require(mark.tile.path), sigma = try #require(mark.sigma.path), line = try #require(mark.line.path)
            #expect(mark.tile.fillColor == NSColor(Tint(look.inverse)).cgColor && near(tile.boundingBox, CGRect(x: 0, y: 0, width: 44, height: 44)))
            #expect(mark.sigma.strokeColor == (still ? NSColor(Tint(look.onInverse)).cgColor : NSColor(look.textSecondary).cgColor), "\(name) \(presentation): the sigma's ink")
            #expect(mark.line.strokeColor == NSColor(Tint(look.accent)).cgColor, "\(name) \(presentation): the line is not the accent")
            #expect(near(sigma.boundingBox, CGRect(x: 39 * scale, y: 53 * scale, width: 72 * scale, height: 60 * scale)))
            #expect(near(line.boundingBox, CGRect(x: 57 * scale, y: 33 * scale, width: 36 * scale, height: 0)))
            #expect(abs(Double(mark.sigma.lineWidth) - 14 * scale) < 1e-9 && abs(Double(mark.line.lineWidth) - 6 * scale) < 1e-9)
            for shape in [mark.sigma, mark.line] {
                #expect(shape.lineCap == .round && shape.lineJoin == .round && shape.fillColor == nil)
            }
            window.orderOut(nil)
            let canvas = NSColor.white, accent = Self.channels(Tint(look.accent))
            let top = Self.colour(mark, on: canvas, at: NSPoint(x: 22, y: 33 * scale), scale: 2), bottom = Self.colour(mark, on: canvas, at: NSPoint(x: 22, y: 44 - 33 * scale), scale: 2)
            #expect(top.near(accent) && !bottom.near(accent), "\(name) \(presentation): the line is drawn at \(top) above and \(bottom) below (flipped \(mark.stage.contentsAreFlipped()), stage \(mark.stage.frame), glyph \(mark.glyph.frame))")
        }
    }

    /// The drawing mark's label is the eyebrow (mono micro, capitals, its tracking, `textTertiary`)
    /// 16 pt under the mark, and VoiceOver's status in its own words; with none it is a busy
    /// indicator; still, the image "Sail".
    @Test func theLabelIsTheEyebrowAndWhatVoiceOverSays() throws {
        let mono = try Self.mono()
        let labelled = BrandMark(.drawing(label: "Asking who is signed in"), look: .light, mono: mono)
        let text = try #require(labelled.labelText)
        let font = try #require(text.attribute(.font, at: 0, effectiveRange: nil) as? NSFont)
        #expect(text.string == "ASKING WHO IS SIGNED IN" && font.pointSize == 11 && font.fontName == "JetBrainsMono-Medium")
        #expect(text.attribute(.kern, at: 0, effectiveRange: nil) as? Double == 0.2 * 11)
        #expect(text.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor == NSColor(Look.light.textTertiary))
        #expect(labelled.intrinsicContentSize.height == 44 + 16 + 14 && labelled.intrinsicContentSize.width >= 44)
        #expect(labelled.accessibilityRole() == .progressIndicator && labelled.accessibilityLabel() == "Asking who is signed in")
        let bare = BrandMark(.drawing(label: nil), side: 28, look: .light, mono: mono)
        #expect(bare.labelText == nil && bare.intrinsicContentSize == NSSize(width: 28, height: 28) && bare.accessibilityLabel() == "Busy")
        let still = BrandMark(.still, look: .light, mono: mono)
        #expect(still.accessibilityRole() == .image && still.accessibilityLabel() == "Sail" && still.labelText == nil)
    }
}
