import SailDesign
import SailVT
import Testing

/// What each control is in each state: the component sheet's fills, edges, labels, halos and
/// pills, and the button's measures.
@Suite struct ControlTests {
    struct Wanted {
        let fill: Tint?
        let edge: Tint?
        let label: Tint
    }

    /// The five kinds at rest, hovered and pressed, as the component sheet draws them.
    static func sheet(_ kind: ButtonKind, _ look: Look) -> [Wanted] {
        let surface = Tint(look.surface)
        switch kind {
        case .primary:
            return [look.accent, look.accentHover, look.accentPressed].map { Wanted(fill: Tint($0), edge: nil, label: Tint(look.onAccent)) }
        case .destructive:
            return [look.error, look.errorHover, look.errorPressed].map { Wanted(fill: Tint($0), edge: nil, label: Tint(look.onError)) }
        case .secondary:
            return [
                Wanted(fill: surface, edge: look.separatorStrong, label: look.text), Wanted(fill: surface, edge: look.text, label: look.text),
                Wanted(fill: look.fillPressed, edge: look.text, label: look.text),
            ]
        case .quiet:
            return [
                Wanted(fill: nil, edge: nil, label: look.textSecondary), Wanted(fill: look.fillHover, edge: nil, label: look.text),
                Wanted(fill: look.fillPressed, edge: nil, label: look.text),
            ]
        case .destructiveQuiet:
            let error = Tint(look.error)
            return [
                Wanted(fill: surface, edge: look.separatorStrong, label: error), Wanted(fill: surface, edge: error, label: error),
                Wanted(fill: surface, edge: error, label: error),
            ]
        }
    }

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachButtonKindAtRestHoveredAndPressedIsTheSheets(_ name: String, _ look: Look) {
        let states = [ButtonState(), ButtonState(hovered: true), ButtonState(hovered: true, pressed: true)]
        for kind in ButtonKind.allCases {
            for (state, wanted) in zip(states, Self.sheet(kind, look)) {
                let paint = ButtonPaint(kind, state, look: look)
                #expect(paint.fill == wanted.fill && paint.edge == wanted.edge && paint.label == wanted.label, "\(name) \(kind) \(state): \(paint)")
                #expect(paint.bold == (kind == .primary || kind == .destructive))
                #expect(paint.opacity == 1 && paint.halo == nil)
            }
        }
    }

    /// Disabled: 45%, and neither hover nor press shows; in flight a press shows nothing either.
    @Test func disabledIgnoresHoverAndPressAndInFlightIgnoresPress() {
        for kind in ButtonKind.allCases {
            let rest = ButtonPaint(kind, ButtonState(), look: .light)
            let disabled = ButtonPaint(kind, ButtonState(enabled: false, hovered: true, pressed: true, focused: true), look: .light)
            #expect(disabled.fill == rest.fill && disabled.edge == rest.edge && disabled.label == rest.label, "\(kind) disabled")
            #expect(disabled.opacity == 0.45 && disabled.halo == nil)
            let busy = ButtonPaint(kind, ButtonState(pressed: true, busy: true), look: .light)
            #expect(busy.fill == rest.fill && busy.edge == rest.edge, "\(kind) in flight took the press")
        }
    }

    /// Focus on any button: a 1 pt canvas gap, then the 3 pt halo, the edge unchanged.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func focusIsACanvasGapAndTheHalo(_ name: String, _ look: Look) {
        for kind in ButtonKind.allCases {
            let paint = ButtonPaint(kind, ButtonState(focused: true), look: look)
            #expect(paint.halo == Halo(ring: look.focusHalo, gap: Tint(look.canvas), gapWidth: 1), "\(name) \(kind)")
            #expect(paint.edge == ButtonPaint(kind, ButtonState(), look: look).edge)
            #expect((paint.halo?.reach ?? 0) <= Look.haloRoom)
        }
    }

    @Test func aSpinnerOnAFillIsTheFillsLabelAndElsewhereInk() {
        let look = Look.dark
        #expect(ButtonPaint(.primary, ButtonState(busy: true), look: look).spinner == SpinnerInk(track: Tint(look.onAccent, alpha: 0.35), arc: Tint(look.onAccent)))
        #expect(ButtonPaint(.destructive, ButtonState(busy: true), look: look).spinner.arc == Tint(look.onError))
        #expect(ButtonPaint(.secondary, ButtonState(busy: true), look: look).spinner == SpinnerInk(track: look.separatorStrong, arc: look.text))
    }

    @Test func sizesAreTheSheets() {
        let measures = [ButtonSize.small, .regular, .large].map { [$0.height, $0.padding, $0.label, $0.radius] }
        #expect(measures == [[26, 10, 12, 5], [32, 14, 13, 6], [36, 18, 14, 6]])
        #expect(ButtonSize.regular.width(label: 40, symbol: false, hint: nil) == 70)
        #expect(ButtonSize.regular.width(label: 40, symbol: true, hint: nil) == 90)
        #expect(ButtonSize.small.width(label: 40, symbol: false, hint: 7) == 77)
    }

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachFieldStateIsTheSheets(_ name: String, _ look: Look) {
        let rest = FieldPaint(.regular, FieldState(), look: look)
        #expect(rest.fill == Tint(look.surface) && rest.edge == look.separatorStrong && rest.eyebrow == look.textTertiary && rest.halo == nil)
        #expect(FieldPaint(.regular, FieldState(hovered: true), look: look).edge == look.text)
        let editing = FieldPaint(.regular, FieldState(hovered: true, editing: true), look: look)
        #expect(editing.edge == Tint(look.accent) && editing.eyebrow == look.text)
        #expect(editing.halo == Halo(ring: look.focusHalo, gap: nil, gapWidth: 0))
        let failed = FieldPaint(.regular, FieldState(failed: true), look: look)
        #expect(failed.edge == Tint(look.error) && failed.eyebrow == Tint(look.error) && failed.halo == nil)
        #expect(FieldPaint(.regular, FieldState(editing: true, failed: true), look: look).halo?.ring == Tint(look.error, alpha: 0.30))
        let disabled = FieldPaint(.regular, FieldState(enabled: false, hovered: true, editing: true), look: look)
        #expect(disabled.boxOpacity == 0.45 && disabled.edge == look.separatorStrong && disabled.halo == nil && disabled.eyebrow == look.textTertiary)
        #expect(FieldPaint(.small, FieldState(), look: look).fill == Tint(look.canvas))
        #expect([FieldSize.regular.height, FieldSize.regular.inset, FieldSize.regular.text] == [32, 10, 13])
        #expect([FieldSize.small.height, FieldSize.small.inset, FieldSize.small.text] == [28, 8, 12])
    }

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachPillIsTheSheets(_ name: String, _ look: Look) {
        let dots: [PillState: Tint] = [
            .running: Tint(look.success), .starting: Tint(look.info), .creating: Tint(look.info), .stopped: look.textTertiary,
            .paused: Tint(look.warning), .incomplete: Tint(look.error), .noTemplate: Tint(look.warning), .building: Tint(look.info),
            .buildFailed: Tint(look.error),
        ]
        for state in PillState.allCases {
            let paint = PillPaint(state, look: look)
            let failed = state == .incomplete || state == .buildFailed
            #expect(paint.dot == dots[state], "\(name) \(state)'s dot")
            #expect(paint.text == (failed ? Tint(look.error) : look.textSecondary), "\(name) \(state)'s label")
            #expect(paint.edge == (failed ? Tint(look.error, alpha: 0.45) : look.separatorStrong), "\(name) \(state)'s edge")
            #expect(paint.dashed == (state == .noTemplate) && paint.hollow == (state == .stopped))
            #expect(state.pulses == [.starting, .creating, .building].contains(state))
        }
        #expect(PillState.allCases.map(\.label) == ["Running", "Starting", "Creating", "Stopped", "Paused", "Not whole", "No template", "Building", "Build failed"])
        #expect([PillSize.regular.height, PillSize.regular.dot, PillSize.regular.text, PillSize.regular.tracking] == [20, 6, 10, 0.08])
        #expect([PillSize.small.height, PillSize.small.dot, PillSize.small.text, PillSize.small.tracking] == [18, 5, 9, 0.06])
    }

    /// The measures the sheet sets that no other test pins: inside each control's 1 pt edge, the
    /// pills' paddings and gaps (Running is 76.61 pt in the design's DOM: 1 + 7 + 6 + 6 + 47.61 + 8 + 1),
    /// the field's gap, inset and mark, the halo, the symbols, the inner corners, the eyebrow's
    /// tracking, the timings, and the destructive spinner's track.
    @Test func metricsAreTheSheets() {
        #expect(Look.hairline == 1 && Look.innerRadius == 4 && Look.smallRadius == 5 && Look.haloRoom == 4)
        #expect(Halo.width == 3 && Halo(ring: Look.light.focusHalo, gap: nil, gapWidth: 1).reach == 4)
        #expect([PillSize.regular.leading, PillSize.regular.trailing, PillSize.regular.gap] == [8, 9, 6])
        #expect([PillSize.small.leading, PillSize.small.trailing, PillSize.small.gap] == [6, 7, 5] && PillSize.dash == [3, 2])
        #expect(abs(PillSize.regular.width(label: 47.61) - 76.61) < 0.001)
        #expect([FieldSize.gap, FieldSize.mark, FieldSize.markPoints, FieldSize.regular.textInset, FieldSize.small.textInset] == [6, 14, 12, 11, 9])
        #expect([ButtonSize.symbol, ButtonSize.symbolGap, ButtonSize.hintGap, ButtonSize.iconPoints, ButtonSize.dot, ButtonSize.dotPitch] == [14, 6, 8, 12, 1.6, 4.33])
        #expect(ButtonSize.symbolPoints == 11 && ButtonSize.hintOpacity == 0.7)
        #expect(Look.Tracking.eyebrow == 0.14 && Look.Tracking.pill == 0.08 && Look.Tracking.smallPill == 0.06)
        #expect(Look.Timing.hover == 0.12 && Look.Timing.turn == 0.7 && Look.Timing.pulse == 1.2 && Look.Timing.skeletonDelay == 0.15)
        for look in [Look.light, Look.dark] {
            #expect(ButtonPaint(.destructive, ButtonState(busy: true), look: look).spinner == SpinnerInk(track: Tint(look.onError, alpha: 0.35), arc: Tint(look.onError)))
        }
    }

    /// Each mono label's baseline where the design's DOM puts it: an eyebrow 8 under its line's
    /// top, a pill's label at 13 in its 20 pt box (11.5 in the sidebar's 18), a keycap's at 12, a
    /// hint (the font's own line height) at 16.5 in a 26 pt button; and a 16.8 pt help line's
    /// extra height split above and below.
    @Test func monoLabelsSitOnTheDesignsBaselines() {
        #expect(TextLine.baseline(top: 0, points: 10, lineHeight: 10) == 8)
        #expect(TextLine.baseline(top: (20 - 10) / 2, points: 10, lineHeight: 10) == 13)
        #expect(TextLine.baseline(top: (18 - 9) / 2, points: 9, lineHeight: 9) == 11.5)
        #expect(TextLine.baseline(top: 1 + 3, points: 10, lineHeight: 10) == 12)
        #expect(TextLine.baseline(top: (26 - 13) / 2, points: 10) == 16.5)
        #expect(TextLine.monoAscent == 1.02 && TextLine.monoDescent == 0.30 && TextLine.height(points: 10) == 13)
        #expect(abs(TextLine.centring(lineHeight: 16.8, ascent: 11.6, descent: 2.9) - 1.15) < 1e-9)
    }

    /// A button in flight that holds the keys still shows where they are.
    @Test func aBusyFocusedButtonShowsItsHalo() {
        for kind in ButtonKind.allCases {
            #expect(ButtonPaint(kind, ButtonState(focused: true, busy: true), look: .light).halo != nil, "\(kind)")
        }
    }

    /// Each key as the hint says it: the platform's order, ⇧ for an upper-case letter as AppKit
    /// presses it, the named keys by glyph or name, and nothing for a key it cannot name.
    @Test(arguments: [
        ("\r", false, "⏎"), ("\u{3}", false, "⌤"), ("n", true, "⌘N"), ("N", true, "⇧⌘N"), ("D", false, "⇧D"), ("\u{1b}", false, "esc"),
        ("\u{7f}", true, "⌘⌫"), ("\u{8}", false, "⌫"), ("\t", false, "⇥"), (" ", false, "Space"), ("\u{F700}", false, "↑"), ("\u{F701}", true, "⌘↓"),
        ("\u{F702}", false, "←"), ("\u{F703}", false, "→"), ("\u{F728}", false, "⌦"), ("\u{F729}", false, "↖"), ("\u{F72B}", false, "↘"),
        ("\u{F72C}", false, "⇞"), ("\u{F72D}", false, "⇟"), ("\u{F704}", false, "F1"), ("\u{F70F}", false, "F12"), ("[", true, "⌘["), ("1", true, "⌘1"),
    ])
    func eachKeyIsSaidAsThePlatformSaysIt(_ key: String, _ command: Bool, _ said: String) {
        #expect(Shortcut.said(key, command: command) == said, "\(key.unicodeScalars.map { String($0.value, radix: 16) }) is said \(Shortcut.said(key, command: command) ?? "nothing")")
    }

    @Test func modifiersComeInThePlatformsOrderAndAnUnnamedKeyIsNotSaid() {
        #expect(Shortcut.said("k", control: true, option: true, shift: true, command: true) == "⌃⌥⇧⌘K")
        #expect(Shortcut.said("K", shift: true, command: true) == "⇧⌘K")
        for unnamed in ["", "ab", "\u{F750}", "\u{E000}", "\u{0}", "\u{1}"] {
            #expect(Shortcut.said(unnamed, command: true) == nil, "\(unnamed.unicodeScalars.map { String($0.value, radix: 16) }) is said")
        }
    }
}
