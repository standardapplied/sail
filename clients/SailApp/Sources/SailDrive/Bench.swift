import Foundation
import SailLook
import SailMeasures
import SailRenderer
import SailSurface
import SailTerminal
import SailVT

/// The renderer's CPU side, measured in the release build the app ships on the main thread's own
/// CPU clock (a shared runner's other work would otherwise be counted), the wall clock beside it:
/// a full 200x60 redraw (reading the frame, laying out every row, writing the buffers, encoding the
/// pass) ``Sampled/count`` times with every row changed, drawn offscreen with nothing read back;
/// and a screen of 600 distinct CJK characters drawn as the display draws it, a share of new glyphs
/// a frame: each frame until every glyph shows, the first in a process that has drawn no CJK yet
/// and again in one that has, and the bytes it put into the atlases.
///
/// The redraw's p95 is judged on the thread's CPU time, the floor of the probe's rule: a sample
/// the machine kept off a core is not counted, and one whose thread used the time is, so there is
/// nothing a look could excuse. A runner whose CPU runs slow uses more of it for the same work
/// (main's red at 29de429d: its builds took 2.7 times as long and every figure here about twice),
/// so each sample is taken beside a fixed reference loop on the same thread, and the reference's
/// p50 and the redraw's p95 in references are said: what the machine's speed was when it missed.
enum Bench {
    static let grid = GridSize(columns: 200, rows: 60)
    static let warmup = 5
    /// The redraw's budget: its CPU p95 within half a 120 Hz frame.
    static let judged = "full redraw 200x60 cpu p95", limit = 4.0

    /// The redraw's p95 against ``limit``, named first, then every figure.
    static func verdict(_ figures: [String: Double]) -> (met: Bool, said: String) {
        let verdict = Verdict.of(judged, milliseconds: figures[judged + " ms"] ?? .infinity, limit: limit)
        return (verdict.met, verdict.said + "; " + figures.sorted { $0.key < $1.key }.map { "\($0.key) \(String(format: "%.2f", $0.value))" }.joined(separator: ", "))
    }

    /// A fixed piece of CPU work, the same on every machine, and the thread CPU time it took: how
    /// fast this thread ran at that moment.
    static func reference(_ seed: Int) -> Duration {
        let started = Duration.threadCPU
        var value = UInt64(truncatingIfNeeded: seed) | 1
        for _ in 0 ..< 200_000 {
            value ^= value << 13
            value ^= value >> 7
            value ^= value << 17
        }
        precondition(value != 0)
        return Duration.threadCPU - started
    }

    static func run(fonts: URL) throws -> [String: Double] {
        let renderer = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        let screen = try Screen(size: grid, scrollbackBytes: 1 << 20, palette: .dark)
        let size = pixels(renderer.metrics)
        var cpu: [Duration] = []
        var wall: [Duration] = []
        var reads: [Duration] = []
        var references: [Duration] = []
        for round in 0 ..< warmup + Sampled.count {
            var text = "\u{1b}[H"
            for row in 0 ..< grid.rows {
                text += "\u{1b}[\(31 + (row + round) % 7)m" + String(repeating: "lorem ipsum \(round) dolor sit amet ", count: 7).prefix(grid.columns)
            }
            _ = screen.feed(Array(text.utf8))
            let speed = reference(round)
            let started = (cpu: Duration.threadCPU, wall: ContinuousClock.now)
            let frame = try screen.frame()
            let read = (cpu: Duration.threadCPU - started.cpu, wall: ContinuousClock.now - started.wall)
            try renderer.drawOffscreen(frame, overlay: Overlay(), size: size)
            guard round >= warmup else { continue }
            cpu.append(read.cpu + renderer.lastEncodeTime)
            wall.append(read.wall + renderer.lastEncodeWallTime)
            reads.append(read.cpu)
            references.append(speed)
        }
        let redraw = Percentiles(cpu)
        let machine = Percentiles(references)
        let redrawWall = Percentiles(wall)

        let ideographs = (0 ..< grid.columns * grid.rows / 2).map { Character(Unicode.Scalar(0x4E00 + UInt32($0 % 600)) ?? "x") }
        let wide = try Screen(size: grid, scrollbackBytes: 1 << 20, palette: .dark)
        _ = wide.feed(Array(String(ideographs).utf8))
        let cjk = try wide.frame()
        let cold = try fill(cjk, fonts: fonts, size: size)
        let warm = try fill(cjk, fonts: fonts, size: size)
        let later = Percentiles(Array(cold.frames.dropFirst()))
        return [
            "full redraw 200x60 cpu p50 ms": redraw.p50, "full redraw 200x60 cpu p95 ms": redraw.p95,
            "full redraw 200x60 cpu max ms": redraw.max, "full redraw 200x60 samples": Double(redraw.count),
            "full redraw 200x60 wall p50 ms": redrawWall.p50, "full redraw 200x60 wall p95 ms": redrawWall.p95,
            "of which reading the frame cpu p95 ms": Percentiles(reads).p95,
            "reference loop cpu p50 ms": machine.p50, "full redraw 200x60 cpu p95 in references": redraw.p95 / max(machine.p50, 1e-6),
            "CJK first frame cpu ms, first CJK in the process": Percentiles(Array(cold.frames.prefix(1))).max,
            "CJK first frame cpu ms, CJK drawn before": Percentiles(Array(warm.frames.prefix(1))).max,
            "CJK later frames cpu p50 ms": later.p50, "CJK later frames cpu max ms": later.max,
            "CJK frames until every glyph shows": Double(cold.frames.count),
            "CJK screen atlas bytes": Double(cold.bytes),
        ]
    }

    /// A fresh renderer drawing `frame` a frame at a time, as the display does, until every glyph
    /// shows: each frame's CPU time and the bytes put into the atlases.
    private static func fill(_ frame: ScreenFrame, fonts: URL, size: PixelSize) throws -> (frames: [Duration], bytes: Int) {
        let renderer = try TerminalRenderer(fonts: TerminalFonts(directory: fonts), points: 15, scale: 2)
        let before = renderer.atlasUploadBytes
        var frames: [Duration] = []
        repeat {
            try renderer.drawOffscreen(frame, overlay: Overlay(), size: size, newItems: TerminalRenderer.newItemsPerFrame)
            frames.append(renderer.lastEncodeTime)
        } while !renderer.lastFrameComplete && frames.count < 1000
        return (frames, renderer.atlasUploadBytes - before)
    }

    private static func pixels(_ metrics: CellMetrics) -> PixelSize {
        PixelSize(
            width: 2 * metrics.padding.width + grid.columns * metrics.width, height: 2 * metrics.padding.height + grid.rows * metrics.height
        )
    }
}
