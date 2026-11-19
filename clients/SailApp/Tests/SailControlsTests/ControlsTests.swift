import AppKit
import Foundation
@testable import SailControls
import SailLook
import Testing

/// Each control as AppKit draws it, at 1x and 2x, against swatches AppKit composes the same way:
/// fills, edges on pixel boundaries, the halo outside the box, the field's caret and selection,
/// the pills' dots; and how each behaves: in flight, focus, VoiceOver, Reduce Motion.
@MainActor @Suite(.serialized) struct ControlsTests {
    static let fonts = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "Fonts")

    static func mono() throws -> LabelFont {
        try LabelFont(directory: fonts)
    }

    /// The controls' Medium face is apart from the terminal's: without it a terminal's fonts still
    /// load, and only the labels' face is missing.
    @Test func aMissingMediumNeverStopsATerminal() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-fonts-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        for file in TerminalFonts.files.values {
            try FileManager.default.copyItem(at: Self.fonts.appending(path: file), to: folder.appending(path: file))
        }
        #expect((try? TerminalFonts(directory: folder)) != nil, "the terminal's fonts need the controls' Medium")
        #expect((try? LabelFont(directory: folder)) == nil, "the labels' faces were read without their Medium")
        #expect((try? LabelFont(directory: Self.fonts)) != nil)
    }

    static func entered() throws -> NSEvent {
        try #require(NSEvent.enterExitEvent(
            with: .mouseEntered, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, eventNumber: 0, trackingNumber: 0, userData: nil
        ))
    }

    static func sized(_ button: SailButton) -> SailButton {
        let size = button.intrinsicContentSize
        button.frame = NSRect(x: 0, y: 0, width: size.width + 2 * Look.haloRoom, height: size.height + 2 * Look.haloRoom)
        return button
    }

    /// The colour at `point` (points from the top left of `view`'s first subview's frame) when
    /// `view` is drawn on `base` by AppKit at `scale`.
    static func colour(_ view: NSView, on base: NSColor, at point: NSPoint, scale: Double) -> [Double] {
        let backdrop = Backdrop(base, holding: view)
        return backdrop.colour(at: point, scale: scale)
    }

    /// `found` is `right` and not `wrong`, and the two differ: a test of a colour that could not
    /// tell the right one from a wrong one proves nothing.
    static func tells(_ found: [Double], _ right: [Double], from wrong: [Double]) -> Bool {
        found.near(right) && !found.near(wrong) && !right.near(wrong)
    }

    static func swatch(_ base: NSColor, _ layers: [NSColor], opacity: Double = 1, scale: Double) -> [Double] {
        Swatch(base, layers, opacity: opacity).colour(at: NSPoint(x: 2, y: 2), scale: scale)
    }

    // MARK: Button

    static func state(_ button: SailButton, _ name: String) throws {
        switch name {
        case "hovered": button.mouseEntered(with: try entered())
        case "pressed":
            button.mouseEntered(with: try entered())
            button.highlight(true)
        case "disabled": button.isEnabled = false
        default: break
        }
    }

    /// Fill and edge of every kind at rest, hovered, pressed and disabled: the fill inside the
    /// box, the edge on its first pixel column and only there, at 1x and 2x, in both looks.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachKindFillsAndEdgesItsBox(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        for kind in ButtonKind.allCases {
            for state in ["rest", "hovered", "pressed", "disabled"] {
                for scale in [1.0, 2.0] {
                    let button = Self.sized(SailButton("Open", kind: kind, look: look, target: nil, action: nil))
                    try Self.state(button, state)
                    let paint = button.paint
                    let box = button.box
                    let fills = [paint.fill].compactMap { $0 }.map { NSColor($0) }
                    let edged = fills + [paint.edge].compactMap { $0 }.map { NSColor($0) }
                    let inside = Self.colour(button, on: canvas, at: NSPoint(x: box.minX + 1.5, y: box.midY), scale: scale)
                    let edge = Self.colour(button, on: canvas, at: NSPoint(x: box.minX + 0.5, y: box.midY), scale: scale)
                    let inked = Self.swatch(canvas, fills + [NSColor(look.text)], opacity: paint.opacity, scale: scale)
                    let unedged = paint.edge == nil ? fills + [NSColor(look.separatorStrong)] : fills
                    #expect(Self.tells(inside, Self.swatch(canvas, fills, opacity: paint.opacity, scale: scale), from: inked), "\(name) \(kind) \(state) at \(scale)x: the fill is \(inside)")
                    #expect(Self.tells(edge, Self.swatch(canvas, edged, opacity: paint.opacity, scale: scale), from: Self.swatch(canvas, unedged, opacity: paint.opacity, scale: scale)),
                            "\(name) \(kind) \(state) at \(scale)x: the edge is \(edge)")
                }
            }
        }
    }

    /// R2-8: a default that is not primary, a destructive sheet's Cancel, is drawn focused with no
    /// keyboard focus, as Full Keyboard Access off leaves it: the halo shows which button Return
    /// presses. A primary default is not, and neither is any button in a window not key.
    @Test func aDefaultNotPrimaryIsDrawnFocused() {
        for (kind, drawn) in [(ButtonKind.secondary, true), (.primary, false)] {
            let button = Self.sized(SailButton("Cancel", kind: kind, look: .light, target: nil, action: nil))
            let (window, _) = Self.window(holding: button, on: .white)
            defer { window.orderOut(nil) }
            #expect(!button.drawnState.focused, "\(kind) drawn focused before it was the default")
            button.isDefault = true
            #expect(button.drawnState.focused == drawn, "\(kind) as the default drawn focused: \(button.drawnState.focused)")
            window.key = false
            #expect(!button.drawnState.focused, "\(kind) drawn focused in a window not key")
        }
    }

    /// Focus: on a surface (so the canvas gap differs from what is under it), the gap on the box's
    /// first point out, the halo on the next three and nothing on the fifth; gone when the focus goes,
    /// and never in a window that is not key.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aFocusedButtonHasTheGapAndTheHaloOutsideItsBox(_ name: String, _ look: Look) throws {
        let surface = NSColor(Tint(look.surface))
        for kind in [ButtonKind.primary, .secondary, .quiet] {
            for scale in [1.0, 2.0] {
                let button = Self.sized(SailButton("Open", kind: kind, look: look, target: nil, action: nil))
                let holder = NSView(frame: button.frame.insetBy(dx: -4, dy: -4))
                holder.addSubview(button)
                button.setFrameOrigin(NSPoint(x: 4, y: 4))
                let (window, backdrop) = Self.window(holding: holder, on: surface)
                defer { window.orderOut(nil) }
                let focused = button.becomeFirstResponder()
                #expect(focused)
                let box = button.box.offsetBy(dx: 4, dy: 4)
                func at(_ out: Double) -> [Double] {
                    backdrop.colour(at: NSPoint(x: Double(box.minX) - out, y: Double(box.midY)), scale: scale)
                }
                let under = Self.swatch(surface, [], scale: scale), gap = Self.swatch(surface, [NSColor(Tint(look.canvas))], scale: scale)
                let halo = Self.swatch(surface, [NSColor(look.focusHalo)], scale: scale)
                #expect(Self.tells(at(0.5), gap, from: under), "\(name) \(kind) at \(scale)x: the gap is \(at(0.5))")
                #expect(Self.tells(at(2.5), halo, from: gap) && Self.tells(at(3.5), halo, from: under), "\(name) \(kind) at \(scale)x: the halo is \(at(2.5)), \(at(3.5))")
                #expect(Self.tells(at(4.5), under, from: halo), "\(name) \(kind) at \(scale)x: the halo reaches past 4 pt: \(at(4.5))")
                window.key = false
                #expect(Self.tells(at(2.5), under, from: halo), "\(name) \(kind): the halo shows in a window that is not key")
                window.key = true
                let resigned = button.resignFirstResponder()
                #expect(resigned)
                #expect(Self.tells(at(2.5), under, from: halo), "\(name) \(kind): the halo stayed after the focus went")
            }
        }
    }

    @Test func aButtonTakesTheKeysOnlyWithKeyboardNavigationAndWhenEnabled() {
        let button = SailButton("Open", kind: .primary, look: .light, target: nil, action: nil)
        #expect(button.acceptsFirstResponder == NSApplication.shared.isFullKeyboardAccessEnabled)
        #expect(button.focusRingType == .none && !button.isBordered)
        button.isEnabled = false
        #expect(!button.acceptsFirstResponder)
    }

    /// In flight: the word and a spinner, the width never smaller, presses ignored (a click and
    /// VoiceOver's press), VoiceOver told it is busy; back again after.
    @Test func inFlightKeepsItsWidthAndIgnoresPresses() throws {
        let presses = Presses()
        let button = Self.sized(SailButton("Start and Open", kind: .secondary, look: .light, target: presses, action: #selector(Presses.press(_:))))
        let before = button.intrinsicContentSize.width
        button.fly("Starting")
        #expect(button.intrinsicContentSize.width >= before)
        #expect(button.accessibilityLabel() == "Starting, busy")
        button.layoutSubtreeIfNeeded()
        #expect(!button.spinner.layer.isHidden)
        button.performClick(nil)
        _ = button.accessibilityPerformPress()
        #expect(presses.count == 0, "a press reached the action in flight")
        button.fly(nil)
        button.layoutSubtreeIfNeeded()
        #expect(button.spinner.layer.isHidden && button.accessibilityLabel() == "Start and Open")
        button.performClick(nil)
        _ = button.accessibilityPerformPress()
        #expect(presses.count == 2)
    }

    @Test func aButtonReadsAsAButtonAndAnswersReturnOnlyAsTheDefault() {
        let button = SailButton("Open", kind: .primary, look: .light, target: nil, action: nil)
        #expect(button.accessibilityRole() == .button && button.accessibilityLabel() == "Open")
        #expect(button.keyEquivalent.isEmpty)
        button.isDefault = true
        #expect(button.keyEquivalent == "\r")
        let icon = SailButton(icon: .refresh, label: "List Again", look: .light, target: nil, action: nil)
        #expect(icon.accessibilityLabel() == "List Again" && icon.intrinsicContentSize == NSSize(width: 32, height: 32) && icon.tooltip != nil && icon.toolTip == nil)
        button.title = "Start and Open"
        #expect(button.accessibilityLabel() == "Start and Open", "VoiceOver reads the old title")
        _ = Self.sized(button)
        button.updateTrackingAreas()
        #expect(button.trackingAreas.contains { $0.rect == button.box && $0.owner === button }, "hover is not tracked on the box")
    }

    /// The icon leads the label by 6 pt and the hint trails it by 8, the three centred in the
    /// box; right to left they swap sides.
    @Test func theIconLeadsTheHintTrailsAndRightToLeftMirrors() throws {
        let button = SailButton("New Workspace", kind: .secondary, icon: .plus, look: .light, target: nil, action: nil)
        button.shortcut("n", modifiers: .command, mono: try Self.mono())
        _ = Self.sized(button)
        #expect(button.keyEquivalent == "n" && button.keyEquivalentModifierMask == .command && button.shortcutWords == "⌘N")
        button.keyEquivalent = "r"
        #expect(button.shortcutWords == "⌘R", "the hint does not follow the key equivalent")
        button.keyEquivalent = "n"
        let (mark, label, hint) = button.arrangement()
        let first = try #require(mark), last = try #require(hint)
        #expect(abs(Double(label.minX - first.maxX) - 6) < 0.5 && abs(Double(last.minX - label.maxX) - 8) < 0.5)
        let box = button.box
        #expect(abs(Double(first.minX - box.minX) - Double(box.maxX - last.maxX)) <= 1, "the content is not centred: \(first) \(last) in \(box)")
        button.userInterfaceLayoutDirection = .rightToLeft
        let (rtlMark, rtlLabel, rtlHint) = button.arrangement()
        #expect((rtlMark?.minX ?? 0) > rtlLabel.maxX && (rtlHint?.maxX ?? .infinity) < rtlLabel.minX)
    }

    /// The label is drawn, centred in the box within a point.
    @Test func theLabelsInkIsCentredInTheBox() {
        let button = Self.sized(SailButton("Start and Open", kind: .secondary, look: .light, target: nil, action: nil))
        let ink = Backdrop(NSColor(Tint(Look.light.surface)), holding: button).ink()
        let box = button.box
        #expect(ink != nil)
        if let ink {
            #expect(abs(Double(ink.midX - box.midX)) <= 1 && abs(Double(ink.midY - box.midY)) <= 2, "the label's ink \(ink) is not centred in \(box)")
        }
    }

    // MARK: Text field

    static func window(holding view: NSView, on base: NSColor) -> (TestWindow, Backdrop) {
        let window = TestWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 200), styleMask: [.titled], backing: .buffered, defer: false)
        let backdrop = Backdrop(base, holding: view)
        window.contentView = backdrop
        window.orderFront(nil)
        return (window, backdrop)
    }

    static func field(_ look: Look, size: FieldSize = .regular, secure: Bool = false) throws -> SailTextField {
        let field = SailTextField(label: "Name", placeholder: "name of a new workspace", help: "Lowercase letters, digits and dashes.", size: size, secure: secure, look: look, mono: try mono())
        field.frame = NSRect(x: 0, y: 0, width: 288, height: 100)
        field.frame.size.height = field.intrinsicContentSize.height + 2 * Look.haloRoom
        field.layoutSubtreeIfNeeded()
        return field
    }

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aFieldBeingEditedHasTheAccentEdgeTheHaloAndTheLooksCaret(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        let field = try Self.field(look)
        let (window, backdrop) = Self.window(holding: field, on: canvas)
        defer { window.orderOut(nil) }
        let editing = window.makeFirstResponder(field.field)
        #expect(editing)
        let editor = try #require(field.field.currentEditor() as? NSTextView)
        #expect(field.paint.edge == Tint(look.accent) && field.paint.eyebrow == look.text)
        #expect(editor.insertionPointColor == NSColor(Tint(look.accent)))
        #expect(editor.selectedTextAttributes[.backgroundColor] as? NSColor == NSColor(look.fillSelected))
        let box = field.box
        for scale in [1.0, 2.0] {
            let ring = backdrop.colour(at: NSPoint(x: box.minX - 1.5, y: box.midY), scale: scale)
            let edge = backdrop.colour(at: NSPoint(x: box.minX + 0.5, y: box.midY), scale: scale)
            let fill = backdrop.colour(at: NSPoint(x: box.minX + 1.5, y: box.midY), scale: scale)
            let surface = NSColor(Tint(look.surface)), bare = Self.swatch(canvas, [], scale: scale)
            #expect(Self.tells(ring, Self.swatch(canvas, [NSColor(look.focusHalo)], scale: scale), from: bare), "\(name) at \(scale)x: the halo is \(ring)")
            #expect(Self.tells(edge, Self.swatch(canvas, [surface, NSColor(Tint(look.accent))], scale: scale), from: Self.swatch(canvas, [surface, NSColor(look.separatorStrong)], scale: scale)),
                    "\(name) at \(scale)x: the edge is \(edge)")
            #expect(Self.tells(fill, Self.swatch(canvas, [surface], scale: scale), from: Self.swatch(canvas, [surface, NSColor(look.focusHalo)], scale: scale)), "\(name) at \(scale)x: the fill is \(fill)")
        }
        window.key = false
        let away = backdrop.colour(at: NSPoint(x: box.minX - 1.5, y: box.midY), scale: 2)
        #expect(field.paint.halo == nil && field.paint.edge == look.separatorStrong, "\(name): the field shows the keys in a window that is not key")
        #expect(Self.tells(away, Self.swatch(canvas, [], scale: 2), from: Self.swatch(canvas, [NSColor(look.focusHalo)], scale: 2)), "\(name): the halo is drawn while the window is not key")
        window.key = true
        let left = window.makeFirstResponder(nil)
        #expect(left)
        #expect(field.paint.halo == nil && field.paint.edge == look.separatorStrong)
    }

    /// A control moved to another window watches that window's key changes and no longer the old
    /// one's: when the window it is in stops being key, it is told (counted) and its halo is gone
    /// (read from its pixels); a key change of the window it left tells it nothing.
    @Test func aControlWatchesOnlyTheWindowItIsIn() throws {
        let look = Look.light, canvas = NSColor(Tint(look.canvas))
        let button = Self.sized(SailButton("Open", kind: .primary, look: look, target: nil, action: nil))
        let field = try Self.field(look)
        let halo = Self.swatch(canvas, [NSColor(look.focusHalo)], scale: 2), bare = Self.swatch(canvas, [], scale: 2)
        let cases: [(NSView, () -> Int, NSPoint)] = [
            (button, { button.keyChanges }, NSPoint(x: button.box.minX - 2.5, y: button.box.midY)), (field, { field.keyChanges }, NSPoint(x: field.box.minX - 1.5, y: field.box.midY)),
        ]
        for (view, count, at) in cases {
            let (first, _) = Self.window(holding: view, on: canvas)
            let (second, backdrop) = Self.window(holding: NSView(frame: view.frame), on: canvas)
            defer {
                first.orderOut(nil)
                second.orderOut(nil)
            }
            backdrop.addSubview(view)
            view.setFrameOrigin(.zero)
            let focused = view === field ? second.makeFirstResponder(field.field) : button.becomeFirstResponder()
            #expect(focused)
            #expect(Self.tells(backdrop.colour(at: at, scale: 2), halo, from: bare), "\(type(of: view)) shows no halo in its key window")
            let before = count()
            NotificationCenter.default.post(name: NSWindow.didResignKeyNotification, object: first)
            #expect(count() == before, "\(type(of: view)) still watches the window it left")
            second.key = false
            NotificationCenter.default.post(name: NSWindow.didResignKeyNotification, object: second)
            #expect(count() == before + 1, "\(type(of: view)) is not told its window stopped being key")
            #expect(Self.tells(backdrop.colour(at: at, scale: 2), bare, from: halo), "\(type(of: view)) keeps its halo in a window that is not key")
        }
    }

    /// The eyebrow's capitals stand on the design's baseline: 8 pt under its line's top, which is
    /// 1 pt higher than Core Text's own ascent would put them.
    @Test func theEyebrowStandsOnTheDesignsBaseline() throws {
        let field = try Self.field(.light)
        let ink = try #require(Backdrop(NSColor(Tint(Look.light.canvas)), holding: field).ink(in: NSRect(x: 0, y: 0, width: 200, height: Look.haloRoom + 10), darkerThan: 400))
        let baseline = TextLine.baseline(top: Look.haloRoom, points: 10, lineHeight: 10)
        #expect(abs(Double(ink.maxY) - baseline) <= 0.25, "the eyebrow's capitals end at \(ink.maxY), not on the baseline at \(baseline)")
    }

    /// An icon-only button's icon is the design's, 15 pt and centred on the box exactly: the ⋯'s
    /// middle dot is inked at the box's middle, and the canvas shows between the dots.
    @Test(arguments: [ButtonSize.small, .regular])
    func theIconIsCentredOnTheBox(_ size: ButtonSize) {
        let more = Self.sized(SailButton(icon: .more, label: "More", size: size, look: .light, target: nil, action: nil))
        let icon = more.arrangement().mark ?? .zero, box = more.box
        #expect(near(icon.width, ButtonSize.icon) && near(icon.midX, box.midX) && near(icon.midY, box.midY), "the icon is \(icon) in \(box)")
        let canvas = NSColor(Tint(Look.light.canvas)), ink = NSColor(ButtonPaint(.quiet, ButtonState(), look: .light).label)
        let middle = NSPoint(x: box.midX, y: box.midY), between = NSPoint(x: box.midX + 2, y: box.midY)
        let right = Self.swatch(canvas, [ink], scale: 2), wrong = Self.swatch(canvas, [], scale: 2)
        #expect(Self.tells(Self.colour(more, on: canvas, at: middle, scale: 2), right, from: wrong), "the middle dot is not at the box's middle")
        #expect(Self.tells(Self.colour(more, on: canvas, at: between, scale: 2), wrong, from: right), "ink between the dots")
    }

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aHoveredFieldsEdgeIsInk(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        let field = try Self.field(look)
        field.mouseEntered(with: try Self.entered())
        let edge = Self.colour(field, on: canvas, at: NSPoint(x: field.box.minX + 0.5, y: field.box.midY), scale: 2)
        let surface = NSColor(Tint(look.surface))
        #expect(Self.tells(edge, Self.swatch(canvas, [surface, NSColor(look.text)], scale: 2), from: Self.swatch(canvas, [surface, NSColor(look.separatorStrong)], scale: 2)),
                "\(name): the hovered edge is \(edge)")
    }

    /// A refusal: the error colour on the edge and the eyebrow, the error in place of the help as
    /// VoiceOver's help, wrapped under its mark; the help back once it is gone.
    @Test func anErrorTakesTheHelpsPlace() throws {
        let field = try Self.field(.light)
        let helped = field.intrinsicContentSize.height
        #expect(field.field.accessibilityHelp() == "Lowercase letters, digits and dashes.")
        field.error = "Use lowercase letters, digits and dashes, starting with a letter or digit."
        #expect(field.paint.edge == Tint(Look.light.error) && field.paint.eyebrow == Tint(Look.light.error))
        #expect(field.field.accessibilityHelp() == "Use lowercase letters, digits and dashes, starting with a letter or digit.")
        #expect(field.intrinsicContentSize.height > helped, "the error did not wrap to a second line at 280 pt")
        let line = try #require(field.lineRect(width: Double(field.bounds.width)))
        #expect(abs(Double(line.minX) - (Look.haloRoom + FieldSize.mark + FieldSize.gap)) < 0.5)
        field.error = nil
        #expect(field.field.accessibilityHelp() == "Lowercase letters, digits and dashes." && near(field.intrinsicContentSize.height, helped))
    }

    /// Disabled: the box and its text at 45%, the eyebrow and the help as they were.
    @Test func aDisabledFieldDimsOnlyItsBox() throws {
        let look = Look.light, canvas = NSColor(Tint(look.canvas))
        let field = try Self.field(look)
        field.isEnabled = false
        #expect(abs(Double(field.field.alphaValue) - 0.45) < 0.001 && field.paint.eyebrow == look.textTertiary)
        let fill = Self.colour(field, on: canvas, at: NSPoint(x: field.box.minX + 3, y: field.box.midY), scale: 2)
        #expect(Self.tells(fill, Self.swatch(canvas, [NSColor(Tint(look.surface))], opacity: 0.45, scale: 2), from: Self.swatch(canvas, [NSColor(look.separatorStrong)], scale: 2)),
                "the disabled box is \(fill)")
    }

    @Test func theSmallFieldIsTwentyEightOnTheCanvasAndSecureIsSecure() throws {
        let small = try Self.field(.light, size: .small)
        #expect(near(small.box.height, 28) && small.paint.fill == Tint(Look.light.canvas))
        let secure = try Self.field(.light, secure: true), plain = try Self.field(.light)
        #expect(secure.field is NSSecureTextField && !(plain.field is NSSecureTextField))
        #expect(plain.field.accessibilityLabel() == "Name")
    }

    // MARK: Pill, keycap, spinner, skeleton

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachPillsDotAndEdgeAreItsStates(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        for size in [PillSize.regular, .small] {
            for state in PillState.allCases {
                let pill = Pill(state, size: size, look: look, mono: try Self.mono())
                pill.frame = NSRect(origin: .zero, size: pill.intrinsicContentSize)
                pill.needsLayout = true
                pill.layoutSubtreeIfNeeded()
                #expect(near(pill.frame.height, size.height))
                let dot = pill.dot.frame
                #expect(near(dot.minX, size.leading), "\(name) \(state): the dot starts \(dot.minX) pt in, not \(size.leading)")
                let middle = Self.colour(pill, on: canvas, at: NSPoint(x: dot.midX, y: dot.midY), scale: 2)
                let wanted = pill.paint.hollow ? Self.swatch(canvas, [], scale: 2) : Self.swatch(canvas, [NSColor(pill.paint.dot)], scale: 2)
                #expect(Self.tells(middle, wanted, from: Self.swatch(canvas, [NSColor(look.text)], scale: 2)), "\(name) \(state) \(size): the dot is \(middle)")
                if pill.paint.hollow {
                    let ring = Self.colour(pill, on: canvas, at: NSPoint(x: dot.minX + 0.5, y: dot.midY), scale: 2)
                    #expect(Self.tells(ring, Self.swatch(canvas, [NSColor(look.textTertiary)], scale: 2), from: wanted), "\(name): the stopped dot's ring is \(ring)")
                }
                let top = Backdrop(canvas, holding: pill).colours(along: 0.5, from: 6, to: Double(pill.frame.width) - 6, scale: 2)
                let line = Self.swatch(canvas, [NSColor(pill.paint.edge)], scale: 2), bare = Self.swatch(canvas, [], scale: 2)
                let dashes = top.filter { Self.tells($0, line, from: bare) }.count, gaps = top.filter { Self.tells($0, bare, from: line) }.count
                #expect(dashes > 0 && (gaps > 0) == (state == .noTemplate), "\(name) \(state): the top edge has \(dashes) edge and \(gaps) bare pixels")
                guard state != .noTemplate else { continue }
                let edge = Self.colour(pill, on: canvas, at: NSPoint(x: 0.5, y: Double(pill.frame.height) / 2), scale: 2)
                let other = pill.paint.edge == look.separatorStrong ? Tint(look.error, alpha: 0.45) : look.separatorStrong
                #expect(Self.tells(edge, Self.swatch(canvas, [NSColor(pill.paint.edge)], scale: 2), from: Self.swatch(canvas, [NSColor(other)], scale: 2)), "\(name) \(state): the edge is \(edge)")
            }
        }
    }

    /// The keycap: a 1 pt edge on top, a 2 pt foot.
    @Test func theKeycapHasAFoot() throws {
        let look = Look.light, canvas = NSColor(Tint(look.canvas))
        let key = Keycap("⌘N", look: look, mono: try Self.mono())
        key.frame = NSRect(origin: .zero, size: key.intrinsicContentSize)
        let height = Double(key.frame.height), middle = Double(key.frame.width) / 2
        let edge = Self.swatch(canvas, [NSColor(Tint(look.surface)), NSColor(look.separatorStrong)], scale: 2)
        let face = Self.swatch(canvas, [NSColor(Tint(look.surface))], scale: 2)
        let top = Self.colour(key, on: canvas, at: NSPoint(x: middle, y: 0.5), scale: 2)
        let under = Self.colour(key, on: canvas, at: NSPoint(x: middle, y: 1.5), scale: 2)
        let foot = Self.colour(key, on: canvas, at: NSPoint(x: middle, y: height - 1.5), scale: 2)
        let over = Self.colour(key, on: canvas, at: NSPoint(x: middle, y: height - 2.5), scale: 2)
        #expect(Self.tells(top, edge, from: face) && Self.tells(under, face, from: edge) && Self.tells(foot, edge, from: face) && Self.tells(over, face, from: edge),
                "the keycap's edge, face, foot and face: \(top) \(under) \(foot) \(over)")
    }

    /// Starting pulses, running does not; a spinner turns; with Reduce Motion neither moves, and
    /// they go on once it is off again.
    @Test func pulsesAndSpinnersFollowReduceMotion() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = false
        let holder = NSView(frame: NSRect(x: 0, y: 0, width: 300, height: 100))
        let (window, _) = Self.window(holding: holder, on: .white)
        defer { window.orderOut(nil) }
        let starting = Pill(.starting, look: .light, mono: try Self.mono()), running = Pill(.running, look: .light, mono: try Self.mono())
        let spinner = Spinner(look: .light)
        let busy = SailButton("Open", kind: .primary, look: .light, target: nil, action: nil)
        for view in [starting, running, spinner, busy] as [NSView] {
            holder.addSubview(view)
        }
        busy.fly("Opening")
        func moving() -> [Bool] {
            [starting.dot.layer?.animation(forKey: Motion.pulseKey) != nil, running.dot.layer?.animation(forKey: Motion.pulseKey) != nil, spinner.ring.isTurning, busy.spinner.isTurning]
        }
        #expect(moving() == [true, false, true, true])
        motion.reduced = true
        #expect(moving() == [false, false, false, false])
        motion.reduced = false
        #expect(moving() == [true, false, true, true])
    }

    /// A skeleton shows only after 150 ms, then pulses.
    @Test func aSkeletonWaitsThenPulses() async throws {
        let holder = NSView(frame: NSRect(x: 0, y: 0, width: 300, height: 100))
        let (window, _) = Self.window(holding: holder, on: .white)
        defer { window.orderOut(nil) }
        let bar = Skeleton(width: 64, look: .light)
        holder.addSubview(bar)
        #expect(bar.alphaValue == 0)
        let shown = await Self.until(2) { bar.alphaValue == 1 }
        #expect(shown && (bar.layer?.animation(forKey: Motion.pulseKey) != nil) == !Motion.shared.reduced)
    }

    static func until(_ seconds: Double, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in DispatchQueue.main.async { done.resume() } }
        }
        return true
    }
}

/// A window that says it is key, as the person's front window is, or not; a test process's own
/// windows cannot be made key.
final class TestWindow: NSWindow {
    var key = true {
        didSet { contentView?.needsDisplay = true }
    }

    override var isKeyWindow: Bool {
        key
    }
}

final class Presses: NSObject {
    var count = 0

    @objc func press(_: Any?) {
        count += 1
    }
}

/// `view` on `base`, flipped, so points are read from the top left.
final class Backdrop: NSView {
    private let base: NSColor

    init(_ base: NSColor, holding view: NSView) {
        self.base = base
        super.init(frame: NSRect(origin: .zero, size: view.frame.size))
        view.setFrameOrigin(.zero)
        addSubview(view)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_: NSRect) {
        base.setFill()
        NSBezierPath(rect: bounds).fill()
    }

    func drawn(scale: Double) -> NSBitmapImageRep? {
        layoutSubtreeIfNeeded()
        guard bounds.width > 0, bounds.height > 0, let image = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: Int(bounds.width * scale), pixelsHigh: Int(bounds.height * scale), bitsPerSample: 8,
            samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        ) else { return nil }
        image.size = bounds.size
        cacheDisplay(in: bounds, to: image)
        return image
    }

    /// The 0–255 sRGB channels drawn at `point`, in points from the top left.
    func colour(at point: NSPoint, scale: Double) -> [Double] {
        guard let image = drawn(scale: scale),
              let colour = image.colorAt(x: Int(point.x * scale), y: Int(point.y * scale))?.usingColorSpace(.sRGB)
        else { return [] }
        return [colour.redComponent, colour.greenComponent, colour.blueComponent].map { ($0 * 255).rounded() }
    }

    /// The colours along the row `y` points down, from `start` to `end`, every pixel, drawn once.
    func colours(along y: Double, from start: Double, to end: Double, scale: Double) -> [[Double]] {
        guard let image = drawn(scale: scale) else { return [] }
        return stride(from: Int(start * scale), to: Int(end * scale), by: 1).compactMap { x in
            image.colorAt(x: x, y: Int(y * scale))?.usingColorSpace(.sRGB).map { [$0.redComponent, $0.greenComponent, $0.blueComponent].map { ($0 * 255).rounded() } }
        }
    }

    /// The rectangle, in points from the top left, of the pixels darker than mid grey: the ink.
    func ink(in region: NSRect? = nil, darkerThan limit: Double = 300) -> NSRect? {
        guard let image = drawn(scale: 2) else { return nil }
        var found: NSRect?
        for y in 0 ..< image.pixelsHigh {
            for x in 0 ..< image.pixelsWide {
                let pixel = NSRect(x: Double(x) / 2, y: Double(y) / 2, width: 0.5, height: 0.5)
                guard region.map({ $0.contains(NSPoint(x: pixel.midX, y: pixel.midY)) }) ?? true,
                      let colour = image.colorAt(x: x, y: y)?.usingColorSpace(.sRGB),
                      Double(colour.redComponent + colour.greenComponent + colour.blueComponent) * 255 < limit
                else { continue }
                found = found.map { $0.union(pixel) } ?? pixel
            }
        }
        return found
    }
}

/// `layers` composed over `base` as a control composes its drawing, at `opacity` as a whole.
final class Swatch: NSView {
    private let base: NSColor
    private let layers: [NSColor]
    private let opacity: Double

    init(_ base: NSColor, _ layers: [NSColor], opacity: Double) {
        self.base = base
        self.layers = layers
        self.opacity = opacity
        super.init(frame: NSRect(x: 0, y: 0, width: 4, height: 4))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func draw(_: NSRect) {
        base.setFill()
        NSBezierPath(rect: bounds).fill()
        guard let context = NSGraphicsContext.current?.cgContext else { return }
        context.saveGState()
        context.setAlpha(opacity)
        context.beginTransparencyLayer(auxiliaryInfo: nil)
        for layer in layers {
            layer.setFill()
            NSBezierPath(rect: bounds).fill()
        }
        context.endTransparencyLayer()
        context.restoreGState()
    }

    func colour(at point: NSPoint, scale: Double) -> [Double] {
        let holder = Backdrop(.clear, holding: self)
        return holder.colour(at: point, scale: scale)
    }
}

extension [Double] {
    /// Within 3 of `other` in every channel: a colour drawn and read back.
    func near(_ other: [Double]) -> Bool {
        count == other.count && zip(self, other).allSatisfy { abs($0 - $1) <= 3 }
    }
}

/// Measured geometry agrees within half a point: never compared exactly, as layout and conversions
/// round.
func near(_ a: CGFloat, _ b: CGFloat) -> Bool {
    abs(a - b) < 0.5
}

func near(_ a: NSPoint, _ b: NSPoint) -> Bool {
    near(a.x, b.x) && near(a.y, b.y)
}

func near(_ a: NSRect, _ b: NSRect) -> Bool {
    near(a.origin, b.origin) && near(a.width, b.width) && near(a.height, b.height)
}
