#if canImport(CoreGraphics)
import CoreGraphics
#endif
import Foundation
import SailDesign
import SailVT
import Testing

/// Rows, tiles, banners, empty tiles and where a tooltip goes, as the component sheet draws them.
@Suite struct RowAndNoticeTests {
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachRowStateIsTheSheets(_ name: String, _ look: Look) {
        let rest = RowPaint(RowState(), look: look)
        #expect(rest.fill == nil && !rest.bar && !rest.actions)
        let hovered = RowPaint(RowState(hovered: true), look: look)
        #expect(hovered.fill == look.fillHover && !hovered.bar && hovered.actions)
        let focused = RowPaint(RowState(chosen: true, emphasized: true, hovered: true), look: look)
        #expect(focused.fill == look.fillSelected && focused.bar && focused.actions, "\(name): the chosen row of the list with the keys")
        let quiet = RowPaint(RowState(chosen: true), look: look)
        #expect(quiet.fill == look.fillSelectedQuiet && quiet.bar && quiet.actions, "\(name): the chosen row of a list without the keys")
        #expect(look.fillSelectedQuiet.alpha < look.fillSelected.alpha && look.fillSelectedQuiet.rgb == look.fillSelected.rgb)
    }

    /// Calm: no row is filled with the accent at any strength; a row being created has no fill and
    /// its line is teal (`info`, in the view); a refused one has no fill and no bar.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func noRowIsFilledWithTheAccent(_ name: String, _ look: Look) {
        let states = [RowState(), RowState(hovered: true), RowState(chosen: true), RowState(chosen: true, emphasized: true, hovered: true)]
        let paints = states.map { RowPaint($0, look: look) } + [.naming(look), .refused, .creating]
        #expect(paints.allSatisfy { $0.fill?.rgb != look.accent }, "\(name): a row is filled with the accent")
        #expect(RowPaint.refused.fill == nil && !RowPaint.refused.bar && RowPaint.creating.fill == nil && RowPaint.creating.progress)
        #expect(RowPaint.naming(look).fill == look.fillSelectedQuiet && RowPaint.naming(look).bar)
    }

    @Test func theTwoRowStylesAreTheSheets() {
        let sidebar = RowStyle.sidebar, list = RowStyle.list
        #expect([sidebar.height, sidebar.margin, sidebar.leading, sidebar.radius, sidebar.bar] == [30, 6, 6, 5, 14])
        #expect(sidebar.trailing == 6 && RowStyle.disclosure == 12 && RowStyle.gap == 7)
        #expect([list.height, list.margin, list.leading, list.trailing, list.radius, list.bar] == [52, 0, 20, 12, 0, 24])
        #expect(list.hairline && !sidebar.hairline && RowStyle.groupHeight == 30 && RowStyle.barWidth == 2)
    }

    /// Every project's tile is the same neutral well (the owner, 19 Nov 2026): the secondary ink on
    /// a quiet ink fill with a hairline ring, and no accent; the initial is drawn, not an icon.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func everyTileIsTheSameNeutralWell(_ name: String, _ look: Look) {
        let tile = TilePaint(look: look)
        #expect(tile.ink == look.textSecondary && tile.fill.rgb == look.ink && tile.ring == look.separator, "\(name)")
        #expect(tile.fill == look.well && tile.ink.rgb != look.accent && tile.fill.rgb != look.accent)
        #expect(TilePaint.mark == .initial && [TilePaint.side, TilePaint.points, TilePaint.radius] == [20, 11, 4])
    }

    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachBannerToneIsTheSheets(_ name: String, _ look: Look) {
        let inks: [BannerTone: RGB] = [.info: look.info, .warning: look.warning, .error: look.error]
        for tone in BannerTone.allCases {
            let paint = BannerPaint(tone, look: look)
            let ink = inks[tone] ?? look.text.rgb
            #expect(paint.fill == look.surface.blended(toward: ink, by: 0.08) && paint.edge == Tint(ink, alpha: 0.35) && paint.mark == Tint(ink), "\(name) \(tone)")
        }
        #expect(BannerTone.allCases.map(\.icon) == [.info, .warning, .error])
    }

    @Test func anEmptyTileIsDashedOnlyWhenSomethingIsMissing() {
        let look = Look.dark
        let empty = EmptyTilePaint(missing: false, look: look), missing = EmptyTilePaint(missing: true, look: look)
        #expect(!empty.dashed && empty.edge == look.separator && missing.dashed && missing.edge == look.separatorStrong)
        #expect(empty.grid == look.gridLine && empty.fill == Tint(look.surface) && EmptyTilePaint.size == 56 && EmptyTilePaint.grid == 8)
    }

    static let screen = CGRect(x: 0, y: 0, width: 1000, height: 800)
    static let chip = CGSize(width: 120, height: 28)
    /// A window larger than the screen, so only the screen bounds the chip.
    static let wide = CGRect(x: -100, y: -100, width: 1200, height: 1000)

    @Test func aTooltipGoesUnderItsAnchorCentred() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 400, y: 500, width: 32, height: 32), window: Self.wide, screen: Self.screen, beside: false)
        #expect(place.side == .below && place.frame == CGRect(x: 356, y: 467, width: 120, height: 28) && place.arrow == 60)
    }

    @Test func aTooltipWithNoRoomBelowGoesAbove() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 400, y: 10, width: 32, height: 32), window: Self.wide, screen: Self.screen, beside: false)
        #expect(place.side == .above && place.frame.minY == 47)
    }

    /// Exactly enough room below the control is room: the chip goes below.
    @Test func exactlyEnoughRoomBelowIsRoom() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 400, y: 33, width: 32, height: 32), window: Self.wide, screen: Self.screen, beside: false)
        #expect(place.side == .below && place.frame.minY == 0)
    }

    /// At the screen's edge the chip is kept inside and its arrow still points at the anchor.
    @Test func aTooltipIsClampedToTheScreenItsArrowOnTheAnchor() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 975, y: 500, width: 24, height: 24), window: Self.wide, screen: Self.screen, beside: false)
        #expect(place.frame.maxX == 1000 && place.arrow == 107)
        let left = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 0, y: 500, width: 10, height: 24), window: Self.wide, screen: Self.screen, beside: false)
        #expect(left.frame.minX == 0 && left.arrow == TooltipPlacement.arrowInset)
    }

    /// The keycap tooltip, value for value.
    @Test func theTooltipIsTheSheets() {
        #expect([TooltipStyle.radius, TooltipStyle.vertical, TooltipStyle.leading, TooltipStyle.trailing, TooltipStyle.arrow, TooltipStyle.gap] == [6, 5, 9, 8, 8, 5])
        #expect(TooltipStyle.shadows == [TooltipStyle.Shadow(y: 1, blur: 2, alpha: 0.12), TooltipStyle.Shadow(y: 6, blur: 16, alpha: 0.16)])
        #expect([TooltipStyle.delay, TooltipStyle.warm, TooltipStyle.hintGap, TooltipStyle.keycapFill, TooltipStyle.keycapText, TooltipStyle.keycapRadius] == [0.6, 1, 8, 0.16, 0.88, 4])
        #expect(TooltipStyle.keycapPadding.vertical == 2 && TooltipStyle.keycapPadding.horizontal == 5)
    }

    @Test func aFoldedTilesTooltipGoesBesideIt() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 70, y: 600, width: 32, height: 32), window: Self.wide, screen: Self.screen, beside: true)
        #expect(place.side == .right && place.frame == CGRect(x: 107, y: 602, width: 120, height: 28) && place.arrow == 14)
    }

    /// Right to left a rail item's chip goes to its left, the arrow on the chip's right edge.
    @Test func aBesideTooltipGoesLeftRightToLeft() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 900, y: 600, width: 32, height: 32), window: Self.wide, screen: Self.screen, beside: true, rightToLeft: true)
        #expect(place.side == .left && place.frame == CGRect(x: 775, y: 602, width: 120, height: 28) && place.arrow == 14)
    }

    /// Beside its anchor the chip still stays on the screen, across and up, its arrow on the anchor.
    @Test func aBesideTooltipIsClampedToTheScreen() {
        let across = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 950, y: 400, width: 32, height: 32), window: Self.wide, screen: Self.screen, beside: true)
        #expect(across.frame.maxX == 1000 && across.frame.minY == 402)
        let up = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 70, y: 785, width: 20, height: 20), window: Self.wide, screen: Self.screen, beside: true)
        #expect(up.frame.maxY == 800 && up.frame.minX == 95 && up.arrow == 23)
        let down = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 70, y: -10, width: 20, height: 20), window: Self.wide, screen: Self.screen, beside: true)
        #expect(down.frame.minY == 0 && down.arrow == TooltipPlacement.arrowInset / 2)
    }

    /// The chip stays 10 pt inside its window, as the design keeps it, before the screen's edge.
    @Test func aTooltipStaysTenPointsInsideItsWindow() {
        let window = CGRect(x: 100, y: 100, width: 600, height: 500)
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 640, y: 400, width: 24, height: 24), window: window, screen: Self.screen, beside: false)
        #expect(place.frame.maxX == 690 && place.arrow == 82)
        let left = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 104, y: 400, width: 24, height: 24), window: window, screen: Self.screen, beside: false)
        #expect(left.frame.minX == 110 && left.arrow == TooltipPlacement.arrowInset)
        #expect(TooltipPlacement.windowInset == 10)
    }
}
