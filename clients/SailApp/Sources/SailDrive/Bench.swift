import Foundation
import SailLook
import SailRenderer
import SailSurface
import SailVT

/// The renderer's CPU side, measured in the release build the app ships: a full 200x60 redraw
/// (reading the frame, laying out every row, writing the buffers, encoding the pass) 60 times with
/// every row changed, and the first frame of a screen of 600 distinct CJK characters (its time and
/// the bytes it puts into the atlases).
enum Bench {
    static let grid = GridSize(columns: 200, rows: 60)

    static func run(fonts: URL) throws -> [String: Double] {
        let renderer = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        let screen = try Screen(size: grid, scrollbackBytes: 1 << 20, palette: .dark)
        let size = pixels(renderer.metrics)
        var timings: [Double] = []
        for round in 0 ..< 65 {
            var text = "\u{1b}[H"
            for row in 0 ..< grid.rows {
                text += "\u{1b}[\(31 + (row + round) % 7)m" + String(repeating: "lorem ipsum \(round) dolor sit amet ", count: 7).prefix(grid.columns)
            }
            _ = screen.feed(Array(text.utf8))
            let started = ContinuousClock.now
            let frame = try screen.frame()
            let read = ContinuousClock.now - started
            _ = try renderer.image(of: frame, overlay: Overlay(), size: size)
            timings.append(milliseconds(read + renderer.lastEncodeTime))
        }
        let warm = Array(timings.dropFirst(5)).sorted()

        let fresh = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        let wide = try Screen(size: grid, scrollbackBytes: 1 << 20, palette: .dark)
        let ideographs = (0 ..< grid.columns * grid.rows / 2).map { Character(Unicode.Scalar(0x4E00 + UInt32($0 % 600)) ?? "x") }
        _ = wide.feed(Array(String(ideographs).utf8))
        let before = fresh.atlasUploadBytes
        _ = try fresh.image(of: try wide.frame(), overlay: Overlay(), size: size)
        return [
            "full redraw 200x60 cpu p50 ms": warm[warm.count / 2],
            "full redraw 200x60 cpu p95 ms": warm[Int((0.95 * Double(warm.count)).rounded(.up)) - 1],
            "full redraw 200x60 cpu max ms": warm.last ?? 0,
            "first CJK frame cpu ms": milliseconds(fresh.lastEncodeTime),
            "first CJK frame atlas bytes": Double(fresh.atlasUploadBytes - before),
        ]
    }

    private static func pixels(_ metrics: CellMetrics) -> PixelSize {
        PixelSize(
            width: 2 * metrics.padding.width + grid.columns * metrics.width, height: 2 * metrics.padding.height + grid.rows * metrics.height
        )
    }

    private static func milliseconds(_ duration: Duration) -> Double {
        let (seconds, attoseconds) = duration.components
        return Double(seconds) * 1000 + Double(attoseconds) / 1e15
    }
}
