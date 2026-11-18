import Foundation
import SailLook
import SailRenderer
import SailSurface
import SailTerminal
import SailVT

/// The renderer's CPU side, in the release build the app ships, on this thread's own CPU clock: a
/// full 200x60 redraw (reading the frame, laying out every row, writing the buffers, encoding the
/// pass) with every row changed, drawn offscreen with nothing read back, a round at a time; and a
/// screen of 600 distinct CJK characters drawn as the display draws it, a share of new glyphs a
/// frame, until every glyph shows. Warm from its first round to its last.
final class Redraws {
    static let grid = GridSize(columns: 200, rows: 60)
    static let warmup = 5

    private let fonts: URL
    private let renderer: TerminalRenderer
    private let screen: Screen
    private let size: PixelSize
    private var drawn = 0

    init(fonts: URL) throws {
        self.fonts = fonts
        renderer = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        screen = try Screen(size: Self.grid, scrollbackBytes: 1 << 20, palette: .dark)
        size = Self.pixels(renderer.metrics)
        _ = try round(Self.warmup)
    }

    /// `count` redraws, each its CPU time in milliseconds.
    func round(_ count: Int) throws -> [Double] {
        try (0 ..< count).map { _ in
            drawn += 1
            var text = "\u{1b}[H"
            for row in 0 ..< Self.grid.rows {
                text += "\u{1b}[\(31 + (row + drawn) % 7)m" + String(repeating: "lorem ipsum \(drawn) dolor sit amet ", count: 7).prefix(Self.grid.columns)
            }
            _ = screen.feed(Array(text.utf8))
            let started = Duration.threadCPU
            let frame = try screen.frame()
            let read = Duration.threadCPU - started
            try renderer.drawOffscreen(frame, overlay: Overlay(), size: size)
            return (read + renderer.lastEncodeTime) / .milliseconds(1)
        }
    }

    /// The CJK screen, reported: its first frame in a process that drew no CJK before and in one
    /// that had, the later frames, how many until every glyph shows, and the bytes the atlases took.
    func cjk() throws -> [String: Double] {
        let ideographs = (0 ..< Self.grid.columns * Self.grid.rows / 2).map { Character(Unicode.Scalar(0x4E00 + UInt32($0 % 600)) ?? "x") }
        let wide = try Screen(size: Self.grid, scrollbackBytes: 1 << 20, palette: .dark)
        _ = wide.feed(Array(String(ideographs).utf8))
        let frame = try wide.frame()
        let cold = try fill(frame), warm = try fill(frame)
        let later = Percentiles(milliseconds: Array(cold.frames.dropFirst()))
        return [
            "CJK first frame cpu ms, first CJK in the process": cold.frames.first ?? 0, "CJK first frame cpu ms, CJK drawn before": warm.frames.first ?? 0,
            "CJK later frames cpu p50 ms": later.p50, "CJK later frames cpu max ms": later.max, "CJK frames until every glyph shows": Double(cold.frames.count),
            "CJK screen atlas bytes": Double(cold.bytes),
        ]
    }

    /// A fresh renderer drawing `frame` a frame at a time, as the display does, until every glyph
    /// shows: each frame's CPU time and the bytes put into the atlases.
    private func fill(_ frame: ScreenFrame) throws -> (frames: [Double], bytes: Int) {
        let renderer = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        let before = renderer.atlasUploadBytes
        var frames: [Double] = []
        repeat {
            try renderer.drawOffscreen(frame, overlay: Overlay(), size: size, newItems: TerminalRenderer.newItemsPerFrame)
            frames.append(renderer.lastEncodeTime / .milliseconds(1))
        } while !renderer.lastFrameComplete && frames.count < 1000
        return (frames, renderer.atlasUploadBytes - before)
    }

    private static func pixels(_ metrics: CellMetrics) -> PixelSize {
        PixelSize(width: 2 * metrics.padding.width + grid.columns * metrics.width, height: 2 * metrics.padding.height + grid.rows * metrics.height)
    }
}
