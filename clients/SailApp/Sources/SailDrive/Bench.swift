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
/// The redraw is measured on the thread's CPU time, the floor of the probe's rule, each sample
/// beside a fixed loop timed on the same thread just before it. On CI only one 120 Hz frame, 8 ms,
/// gates (the worst healthy run seen: 5.25 ms): the 4 ms a person's Mac is held to failed two of
/// nine healthy runs, and no reference tracked the redraw closely enough to judge it on the
/// machine's speed (over ten runs the redraw ran 3.22 to 4.02 loops, and a loop 22 % slower came
/// with a redraw 60 % slower). So CI no longer catches a redraw regression under about twice on its
/// own; the 4 ms figure and the redraw in loops are said every run, and are the trend. The
/// principled fix, main's renderer and the branch's drawn in the same job, is the spec's next step.
enum Bench {
    static let grid = GridSize(columns: 200, rows: 60)
    static let warmup = 5
    static let gate = SpeedGate(name: "full redraw 200x60 cpu")

    /// The redraw's verdict, its budgets named first, then every figure.
    static func verdict(_ judged: (met: Bool, said: String, figures: [String: Double])) -> (met: Bool, said: String) {
        (judged.met, judged.said + "; " + judged.figures.sorted { $0.key < $1.key }.map { "\($0.key) \(String(format: "%.2f", $0.value))" }.joined(separator: ", "))
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

    static func run(fonts: URL) throws -> (met: Bool, said: String, figures: [String: Double]) {
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
        let judged = gate.judge(work: cpu.map { $0 / .milliseconds(1) }, references: references.map { $0 / .milliseconds(1) })
        let redrawWall = Percentiles(wall)

        let ideographs = (0 ..< grid.columns * grid.rows / 2).map { Character(Unicode.Scalar(0x4E00 + UInt32($0 % 600)) ?? "x") }
        let wide = try Screen(size: grid, scrollbackBytes: 1 << 20, palette: .dark)
        _ = wide.feed(Array(String(ideographs).utf8))
        let cjk = try wide.frame()
        let cold = try fill(cjk, fonts: fonts, size: size)
        let warm = try fill(cjk, fonts: fonts, size: size)
        let later = Percentiles(Array(cold.frames.dropFirst()))
        let figures: [String: Double] = [
            "full redraw 200x60 cpu p50 ms": redraw.p50, "full redraw 200x60 cpu p95 ms": redraw.p95,
            "full redraw 200x60 cpu max ms": redraw.max, "full redraw 200x60 samples": Double(redraw.count),
            "full redraw 200x60 wall p50 ms": redrawWall.p50, "full redraw 200x60 wall p95 ms": redrawWall.p95,
            "of which reading the frame cpu p95 ms": Percentiles(reads).p95,
            "CJK first frame cpu ms, first CJK in the process": Percentiles(Array(cold.frames.prefix(1))).max,
            "CJK first frame cpu ms, CJK drawn before": Percentiles(Array(warm.frames.prefix(1))).max,
            "CJK later frames cpu p50 ms": later.p50, "CJK later frames cpu max ms": later.max,
            "CJK frames until every glyph shows": Double(cold.frames.count),
            "CJK screen atlas bytes": Double(cold.bytes),
        ]
        return (judged.met, judged.said, figures.merging(judged.figures) { kept, _ in kept })
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
