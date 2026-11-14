import Foundation
import SailLook
import SailRenderer
import SailSurface
import SailTerminal
import SailVT

/// The renderer's CPU side, measured in the release build the app ships on the main thread's own
/// CPU clock (a shared runner's other work would otherwise be counted): a full 200x60 redraw
/// (reading the frame, laying out every row, writing the buffers, encoding the pass) 60 times with
/// every row changed; and a screen of 600 distinct CJK characters drawn as the display draws it, a
/// share of new glyphs a frame: the first frame's time, how many frames until every glyph shows,
/// and the bytes it put into the atlases.
enum Bench {
    static let grid = GridSize(columns: 200, rows: 60)

    static func run(fonts: URL) throws -> [String: Double] {
        let renderer = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        let screen = try Screen(size: grid, scrollbackBytes: 1 << 20, palette: .dark)
        let size = pixels(renderer.metrics)
        var redraws: [Duration] = []
        var reads: [Duration] = []
        for round in 0 ..< 65 {
            var text = "\u{1b}[H"
            for row in 0 ..< grid.rows {
                text += "\u{1b}[\(31 + (row + round) % 7)m" + String(repeating: "lorem ipsum \(round) dolor sit amet ", count: 7).prefix(grid.columns)
            }
            _ = screen.feed(Array(text.utf8))
            let started = Duration.threadCPU
            let frame = try screen.frame()
            let read = Duration.threadCPU - started
            _ = try renderer.image(of: frame, overlay: Overlay(), size: size)
            if round >= 5 {
                redraws.append(read + renderer.lastEncodeTime)
                reads.append(read)
            }
        }
        let redraw = Percentiles(redraws)

        let fresh = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        let wide = try Screen(size: grid, scrollbackBytes: 1 << 20, palette: .dark)
        let ideographs = (0 ..< grid.columns * grid.rows / 2).map { Character(Unicode.Scalar(0x4E00 + UInt32($0 % 600)) ?? "x") }
        _ = wide.feed(Array(String(ideographs).utf8))
        let cjk = try wide.frame()
        let before = fresh.atlasUploadBytes
        var frames: [Duration] = []
        repeat {
            _ = try fresh.image(of: cjk, overlay: Overlay(), size: size, newItems: TerminalRenderer.newItemsPerFrame)
            frames.append(fresh.lastEncodeTime)
        } while !fresh.lastFrameComplete && frames.count < 1000
        let cjkFrames = Percentiles(frames)
        return [
            "full redraw 200x60 cpu p50 ms": redraw.p50, "full redraw 200x60 cpu p95 ms": redraw.p95,
            "full redraw 200x60 cpu max ms": redraw.max, "of which reading the frame p95 ms": Percentiles(reads).p95,
            "first CJK frame cpu ms": Percentiles(Array(frames.prefix(1))).max,
            "CJK screen frames until every glyph shows": Double(frames.count),
            "CJK screen frame cpu max ms": cjkFrames.max,
            "CJK screen atlas bytes": Double(fresh.atlasUploadBytes - before),
        ]
    }

    private static func pixels(_ metrics: CellMetrics) -> PixelSize {
        PixelSize(
            width: 2 * metrics.padding.width + grid.columns * metrics.width, height: 2 * metrics.padding.height + grid.rows * metrics.height
        )
    }
}
