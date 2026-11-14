import SailSurface
import SailVT
import Testing

@Suite struct MetricsTests {
    @Test func aCellIsTheFontsAdvanceByItsLineInWholePixels() throws {
        let cell = try metrics(points: 15)
        #expect((cell.width, cell.height, cell.baseline, cell.stroke) == (18, 40, 31, 2))
        #expect((cell.underlineTop, cell.strikethroughTop) == (35, 22))
        #expect(cell.padding == PixelSize(width: 20, height: 16))
    }

    @Test func theGridIsTheWholeCellsInsideThePadding() throws {
        let cell = try metrics(points: 15)
        #expect(cell.grid(fitting: PixelSize(width: 2360, height: 1600)) == GridSize(columns: 128, rows: 39))
        #expect(cell.grid(fitting: PixelSize(width: 10, height: 10)) == GridSize(columns: 1, rows: 1))
    }

    @Test func aPointIsTheCellUnderItHeldInsideTheGrid() throws {
        let cell = try metrics(points: 15)
        let grid = GridSize(columns: 80, rows: 24)
        #expect(cell.cell(atX: 20, y: 16, in: grid) == CellPoint(x: 0, y: 0))
        #expect(cell.cell(atX: 20 + 18 * 5 + 17, y: 16 + 40 * 3 + 39, in: grid) == CellPoint(x: 5, y: 3))
        #expect(cell.cell(atX: -50, y: 1e9, in: grid) == CellPoint(x: 0, y: 23))
        #expect(cell.cell(atX: .nan, y: .infinity, in: grid) == CellPoint(x: 0, y: 0))
        #expect(cell.origin(of: CellPoint(x: 2, y: 1)) == (56, 56))
    }

    @Test func aFontWithoutACellIsRefused() {
        let broken = FontMetrics(advance: 0, ascent: 10, descent: 2, leading: 0, underlineOffset: 1, underlineThickness: 1, xHeight: 5)
        #expect(throws: SurfaceError.self) { try CellMetrics(font: broken, scale: 2) }
        let fine = FontMetrics(advance: 9, ascent: 10, descent: 2, leading: 0, underlineOffset: 1, underlineThickness: 1, xHeight: 5)
        #expect(throws: SurfaceError.self) { try CellMetrics(font: fine, scale: 0) }
        #expect(throws: SurfaceError.self) { try CellMetrics(font: fine, scale: .nan) }
    }

    /// Scenario 14's sizes: ⌘+ three times from 15 is 18; ⌘0 is 15; the ladder ends hold.
    @Test func theLadderStepsAsMastsDid() {
        var size = FontLadder.standard
        for _ in 0 ..< 3 { size = FontLadder.larger(than: size) }
        #expect(size == 18)
        #expect(FontLadder.larger(than: 18) == 20)
        #expect(FontLadder.larger(than: 32) == 32)
        #expect(FontLadder.smaller(than: 10) == 10)
        #expect(FontLadder.smaller(than: 15) == 14)
        #expect(FontLadder.smaller(than: 19) == 18)
    }
}
