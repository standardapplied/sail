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
/// changes far more than 0.1% of these small grids. Emoji are bounded looser (48 of 255 on 5%),
/// since the system's emoji font changes between releases. Every run leaves its images in
/// `SAIL_FRAMES_OUT`, and a difference image beside a failing one.
@Suite struct GoldenTests {
    static let themes: [(name: String, palette: Palette)] = [("dark", .dark), ("light", .light)]

    @Test(arguments: try VTFixtures.all(), [0, 1])
    func eachFixtureMatchesItsGolden(fixture: VTFixtures.Fixture, theme: Int) throws {
        let (themeName, palette) = Self.themes[theme]
        let name = "\(fixture.name)-\(themeName)"
        let screen = try makeScreen(fixture.columns, fixture.rows, palette: palette)
        _ = screen.feed(try fixture.bytes)
        let actual = try makeRenderer().image(of: screen)
        actual.write(name)
        if Paths.recording { return }
        let goldenURL = Paths.goldens.appending(path: name + ".png")
        guard let data = try? Data(contentsOf: goldenURL), let golden = RenderedImage(png: data) else {
            Issue.record("no golden \(goldenURL.lastPathComponent): the frame drawn is \(Paths.out.appending(path: name + ".png").path)")
            return
        }
        let emoji = fixture.name.contains("emoji")
        let (differing, total, picture) = actual.difference(from: golden, tolerance: emoji ? 48 : 8)
        let allowed = Double(total) * (emoji ? 0.05 : 0.001)
        if Double(differing) > allowed { picture.write(name + "-diff") }
        #expect(Double(differing) <= allowed, "\(name): \(differing) of \(total) pixels differ (\(Int(allowed)) allowed)")
    }
}
