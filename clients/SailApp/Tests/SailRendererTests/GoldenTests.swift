import Foundation
@testable import SailRenderer
import SailKitTestSupport
import SailSurface
import SailVT
import Testing

/// Scenario 1: every SailKit fixture's bytes, drawn offscreen at scale 2 in JetBrains Mono 15 pt,
/// dark and light, match the golden PNG. A pixel differs when any channel differs by more than
/// 8 of 255; a frame fails when more than 0.1% of its pixels differ. The same OS and GPU draw
/// bit for bit the same; the bound absorbs Core Text's antialiasing changing between macOS point
/// releases and rounding between GPUs, while a wrong colour, a missing glyph or a cell out of place
/// changes far more than 0.1% of these small grids. An emoji's own pixels change with the system's
/// emoji font, so its cells are left out of that comparison and must instead hold colour: a missing
/// emoji fails, a redrawn one does not. Every run leaves its images in `SAIL_FRAMES_OUT`, and a
/// difference image beside a failing one.
@Suite struct GoldenTests {
    static let themes: [(name: String, palette: Palette)] = [("dark", .dark), ("light", .light)]

    @Test(arguments: try VTFixtures.all(), [0, 1])
    func eachFixtureMatchesItsGolden(fixture: VTFixtures.Fixture, theme: Int) throws {
        let (themeName, palette) = Self.themes[theme]
        let name = "\(fixture.name)-\(themeName)"
        let screen = try makeScreen(fixture.columns, fixture.rows, palette: palette)
        _ = screen.feed(try fixture.bytes)
        let renderer = try makeRenderer()
        let frame = try screen.frame()
        let actual = try renderer.image(of: frame, overlay: Overlay(), size: renderer.pixels(of: frame.size))
        actual.write(name)
        let emoji = Self.emojiCells(frame, renderer.metrics)
        for area in emoji {
            #expect(Self.holdsColour(actual, area), "\(name): an emoji at \(area) is missing")
        }
        if Paths.recording { return }
        let goldenURL = Paths.goldens.appending(path: name + ".png")
        guard let data = try? Data(contentsOf: goldenURL), let golden = RenderedImage(png: data) else {
            Issue.record("no golden \(goldenURL.lastPathComponent): the frame drawn is \(Paths.out.appending(path: name + ".png").path)")
            return
        }
        let (differing, total, picture) = Self.masked(actual, emoji).difference(from: Self.masked(golden, emoji), tolerance: 8)
        let allowed = Double(total) * 0.001
        if Double(differing) > allowed { picture.write(name + "-diff") }
        #expect(Double(differing) <= allowed, "\(name): \(differing) of \(total) pixels differ (\(Int(allowed)) allowed)")
    }

    typealias Area = (x: Range<Int>, y: Range<Int>)

    /// The pixel boxes of the cells holding an emoji (a cluster that starts with an emoji or a
    /// regional indicator).
    static func emojiCells(_ frame: ScreenFrame, _ metrics: CellMetrics) -> [Area] {
        frame.rows.enumerated().flatMap { y, row in
            row.cells.enumerated().compactMap { x, cell -> Area? in
                guard cell.width == .wide, let first = cell.grapheme.unicodeScalars.first,
                      first.properties.isEmojiPresentation || (0x1F1E6 ... 0x1F1FF).contains(first.value)
                else { return nil }
                let origin = metrics.origin(of: CellPoint(x: x, y: y))
                return (origin.x ..< origin.x + 2 * metrics.width, origin.y ..< origin.y + metrics.height)
            }
        }
    }

    static func holdsColour(_ image: RenderedImage, _ area: Area) -> Bool {
        area.y.contains { y in
            area.x.contains { x in
                let (r, g, b) = image.rgb(x, y)
                return max(abs(r - g), abs(g - b), abs(r - b)) > 60
            }
        }
    }

    static func masked(_ image: RenderedImage, _ areas: [Area]) -> RenderedImage {
        var bytes = image.bgra
        for area in areas {
            for y in area.y where y < image.height {
                for x in area.x where x < image.width {
                    bytes.replaceSubrange((y * image.width + x) * 4 ..< (y * image.width + x) * 4 + 4, with: [0, 0, 0, 255])
                }
            }
        }
        return RenderedImage(width: image.width, height: image.height, bgra: bytes)
    }
}
