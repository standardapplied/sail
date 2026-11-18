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
        #expect(look.fillSelectedQuiet.alpha == look.fillSelected.alpha / 2 && look.fillSelectedQuiet.rgb == look.fillSelected.rgb)
    }

    @Test func theTwoRowStylesAreTheSheets() {
        let sidebar = RowStyle.sidebar, list = RowStyle.list
        #expect([sidebar.height, sidebar.margin, sidebar.leading, sidebar.radius, sidebar.bar, sidebar.spacing] == [30, 6, 6, 5, 14, 0])
        #expect(sidebar.trailing == 6 && RowStyle.disclosure == 12 && RowStyle.gap == 7)
        #expect([list.height, list.margin, list.leading, list.trailing, list.radius, list.bar] == [52, 0, 20, 12, 0, 24])
        #expect(list.hairline && !sidebar.hairline && RowStyle.groupHeight == 30 && RowStyle.barWidth == 2)
    }

    /// Every name the design shows takes the design's hue, from its first letter.
    @Test(arguments: [Look.light, .dark])
    func aTileTakesTheDesignsHue(_ look: Look) {
        let designs: [(String, Tint)] = [
            ("harbor", Tint(look.accentText)), ("demo", Tint(look.warning)), ("lighthouse", Tint(look.info)), ("marina", Tint(look.success)),
            ("atlas", look.textSecondary), ("keel", Tint(look.info)), ("quarry", Tint(look.warning)), ("beacon", Tint(look.success)),
            ("tidewater", Tint(look.accentText)), ("Harbor", Tint(look.accentText)),
        ]
        for (name, hue) in designs {
            let tile = TilePaint(name, look: look)
            #expect(tile.ink == hue && tile.fill == Tint(hue.rgb, alpha: hue.alpha * 0.14) && tile.ring == Tint(hue.rgb, alpha: hue.alpha * 0.22), "\(name)")
        }
    }

    /// A letter the design does not show takes a hue by its place in the alphabet, always the same;
    /// anything but a Latin letter, or no name, is secondary.
    @Test func otherLettersTakeAHueByTheirPlace() {
        let look = Look.light, four = [look.accentText, look.warning, look.info, look.success]
        #expect(TilePaint("cove", look: look).ink == Tint(four[2]) && TilePaint("eddy", look: look).ink == Tint(four[0]))
        #expect(TilePaint("fjord", look: look).ink == Tint(four[1]) && TilePaint("gull", look: look).ink == Tint(four[2]))
        #expect(TilePaint("zephyr", look: look).ink == Tint(four[1]) && TilePaint("cove", look: look) == TilePaint("Cove", look: look))
        for name in ["7seas", "", "éclat"] {
            #expect(TilePaint(name, look: look).ink == look.textSecondary, "\(name)")
        }
        #expect([TilePaint.side, TilePaint.points, TilePaint.radius] == [20, 11, 4])
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

    @Test func aTooltipGoesUnderItsAnchorCentred() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 400, y: 500, width: 32, height: 32), screen: Self.screen, beside: false)
        #expect(place.side == .below && place.frame == CGRect(x: 356, y: 467, width: 120, height: 28) && place.arrow == 60)
    }

    @Test func aTooltipWithNoRoomBelowGoesAbove() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 400, y: 10, width: 32, height: 32), screen: Self.screen, beside: false)
        #expect(place.side == .above && place.frame.minY == 47)
    }

    /// At the screen's edge the chip is kept inside and its arrow still points at the anchor.
    @Test func aTooltipIsClampedToTheScreenItsArrowOnTheAnchor() {
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 975, y: 500, width: 24, height: 24), screen: Self.screen, beside: false)
        #expect(place.frame.maxX == 1000 && place.arrow == 107)
        let left = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 0, y: 500, width: 10, height: 24), screen: Self.screen, beside: false)
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
        let place = TooltipPlacement(chip: Self.chip, anchor: CGRect(x: 70, y: 600, width: 32, height: 32), screen: Self.screen, beside: true)
        #expect(place.side == .trailing && place.frame == CGRect(x: 107, y: 602, width: 120, height: 28) && place.arrow == 14)
    }
}
