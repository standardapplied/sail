import Foundation
import SailMeasures
import SailSurface
import SailTerminal
import SailVT
import Synchronization

/// T10's budgets for the screen's own overhead, measured in process: an output frame of 4 KiB to
/// the screen updated at 200x60, the rate a 20 MiB stream feeds at, and what a renderer meets while
/// a program floods the screen. For that, `frame()` is called at 120 Hz on a thread of its own while
/// another feeds back to back, with no gap between feeds: 1 MiB at a time (the most one `Output`
/// carries) and 13 KiB at a time (a busy pty). A frame stays within half a 120 Hz frame (4 ms) at
/// p95, so the display keeps up while a program floods the screen; the time it waits for the lock
/// stays within 1 ms at p95, since the feeder lets it in between slices of ``Screen/feedSlice``.
/// Maxima are reported. The wait includes the reader's wait for a CPU after the lock is free, so
/// these are budgets for a machine with a core to spare: with every core busy the waits rise for
/// small feeds as much as large ones (measured 2.2 and 3.2 ms p95), which no lock can help. So each
/// wait is judged less the stall of a thread of the probe's own over it, before its p95 is taken,
/// and the raw p95 is reported beside it: a stall of the whole process is the machine's.
///
/// A frame is judged the same way, on the drive's rule: less only the time its own thread is
/// proven starved over it (``MachineShare/starvation(in:looks:)``: a real-time ``Looker`` saw it
/// runnable at four looks or more in a row, no more than 15 ms apart, gaining no CPU while no CPU
/// of the machine was idle), never less its own CPU time. Each frame over the limit is said, with
/// its thread's CPU, its wait and what was proven or why nothing was, and each excuse is counted.
/// A frame whose thread used the time fails as it always did. Four looks 5 ms apart prove at least
/// 15 ms, so a frame a starved runner made late by less (#439's red run had a 7.5 ms one) is never
/// excused: that is answered with frames, not machinery. Each series takes ``frames`` frames, so
/// its nearest-rank p95 leaves ``frames`` − ⌈0.95 × ``frames``⌉ = 20 of them over the limit; #439's
/// red run had 3 of 54 over (its p95 allowed 2), and every series kept since, 4 of 338 (1.2 %): some
/// 5 of 400 expected, a quarter of the tail. A series that excuses more frames than that tail is
/// inconclusive and fails, saying so: a measurement that excused most of itself measured nothing.
/// At 120 Hz the 400 frames take about 3.6 s a series; a runner that wakes the frame thread 30 ms
/// late, as CI's macOS runners do, takes about 13 s.
enum Bench {
    static let frameLimit = 4.0
    /// The frames each `frame()` series takes.
    static let frames = 400

    /// How many of `count` frames the nearest-rank p95 leaves above it.
    static func tail(of count: Int) -> Int {
        count - Int((0.95 * Double(count)).rounded(.up))
    }

    static func run() throws -> (budgets: [Budget], samples: [BenchSeries], said: [String]) {
        let clock = ContinuousClock()
        let origin = clock.now
        let own = Heartbeat.onItsOwnThread { (ContinuousClock.now - origin) / .seconds(1) }
        let chunk = Array(lines(count: 64).utf8.prefix(4096))
        let screen = try Screen(size: GridSize(columns: 200, rows: 60), scrollbackBytes: 10 << 20, palette: .dark)
        _ = try screen.frame()
        var updates: [Duration] = []
        for _ in 0 ..< 1000 {
            let started = clock.now
            _ = screen.feed(chunk)
            _ = try screen.frame()
            updates.append(clock.now - started)
        }
        let stream = Array(lines(count: 330_000).utf8.prefix(20 << 20))
        let fed = try Screen(size: GridSize(columns: 200, rows: 60), scrollbackBytes: 10 << 20, palette: .dark)
        let started = clock.now
        for start in stride(from: 0, to: stream.count, by: 64 * 1024) {
            _ = fed.feed(Array(stream[start ..< min(stream.count, start + 64 * 1024)]))
        }
        let elapsed: Double = (clock.now - started).milliseconds
        let rate = Double(stream.count) / 1_048_576 / (elapsed / 1000)
        var samples: [BenchSeries] = []
        var said: [String] = []
        var budgets = [
            Budget(name: "4 KiB output to screen updated at 200x60, p95", milliseconds: Percentiles(updates).p95, limit: 2),
            Budget(name: "20 MiB fed, milliseconds per MiB (50 MiB/s is 20)", milliseconds: 1000 / rate, limit: 20),
        ]
        for (label, size) in [("1 MiB", 1 << 20), ("13 KiB", 13 * 1024)] {
            own.reset()
            let measured = try framesWhileFeeding(stride(from: 0, to: stream.count, by: size).map {
                Array(stream[$0 ..< min(stream.count, $0 + size)])
            }, origin: origin)
            let name = "frame() at 120 Hz, \(label) feeds back to back"
            let taken = series(name, measured, own: own.stalls)
            let frame = Percentiles(measured.frames)
            let net = Percentiles(taken.frames.map { Duration.seconds(($0.milliseconds - $0.excusedMilliseconds) / 1000) })
            let wait = Percentiles(MachineShare.net(measured.waits, own: own.stalls).map { Duration.seconds($0) })
            let raw = Percentiles(measured.waits.map { Duration.seconds($0.length) })
            samples.append(taken)
            said += told(taken, realTime: measured.realTime)
            budgets += [
                Budget(name: "\(name), p95, each net of the time its thread was proven starved", milliseconds: net.p95, limit: frameLimit),
                excuses(taken),
                Budget(name: "\(name), p95 raw (reported)", milliseconds: frame.p95, limit: .infinity),
                Budget(name: "\(name), max raw (reported)", milliseconds: frame.max, limit: .infinity),
                Budget(name: "frame()'s wait for the lock, \(label) feeds back to back, p95, each net of the probe's own stall", milliseconds: wait.p95, limit: 1),
                Budget(name: "frame()'s wait for the lock, \(label) feeds back to back, p95 raw (reported)", milliseconds: raw.p95, limit: .infinity),
                Budget(name: "frame()'s wait for the lock, \(label) feeds back to back, max raw (reported)", milliseconds: raw.max, limit: .infinity),
            ]
        }
        return (budgets, samples, said)
    }

    /// What one series of frames measured: each frame's length, its wait for the lock (seconds since
    /// the bench began), the slices the feeder fed meanwhile and its thread's CPU time over it, and
    /// the looks at that thread, taken real-time or not.
    struct Measured {
        var frames: [Duration]
        var waits: [Span]
        var slices: [Int]
        var cpus: [Double]
        var looks: [ThreadLook]
        var realTime: Bool?
    }

    /// The frames of `measured`, each with the stall of `own` over its wait and the time its thread
    /// was proven starved over it, and the looks and stalls beside them: what an over-limit series
    /// is read from.
    static func series(_ name: String, _ measured: Measured, own: Stalls) -> BenchSeries {
        BenchSeries(
            name: name,
            frames: measured.frames.indices.map { index in
                let frame = measured.frames[index], wait = measured.waits[index], cpu = measured.cpus[index]
                return FrameSample(
                    at: wait.start, milliseconds: frame.milliseconds, waitMilliseconds: wait.length * 1000,
                    slicesWaited: measured.slices[index], heldMilliseconds: own.held(over: wait) * 1000, cpuMilliseconds: cpu * 1000,
                    excusedMilliseconds: excused(Span(wait.start, wait.start + frame.milliseconds / 1000), cpu: cpu, looks: measured.looks) * 1000
                )
            },
            stalls: own.gaps,
            looks: measured.looks
        )
    }

    /// The time of `frame` its thread was proven starved over (``MachineShare/starved(in:looks:)``),
    /// in seconds, never more than the frame less the CPU its thread used in it.
    static func excused(_ frame: Span, cpu: Double, looks: [ThreadLook]) -> Double {
        min(MachineShare.starved(in: frame, looks: looks), max(0, frame.length - cpu))
    }

    /// How many frames of `series` were excused, held to the p95's tail: a series that excused
    /// more is inconclusive.
    static func excuses(_ series: BenchSeries) -> Budget {
        Budget(
            name: "\(series.name), frames excused as starved, a count (more than the p95's tail of \(tail(of: series.frames.count)) leaves the series inconclusive)",
            milliseconds: Double(series.frames.filter { $0.excusedMilliseconds > 0 }.count), limit: Double(tail(of: series.frames.count))
        )
    }

    /// The series in sentences: how many frames were excused and how much, then each frame over
    /// ``frameLimit`` with its thread's CPU, its wait, and what was proven of it or why nothing was.
    static func told(_ series: BenchSeries, realTime: Bool?) -> [String] {
        let excused = series.frames.filter { $0.excusedMilliseconds > 0 }
        let looker = realTime.map { $0 ? "real-time" : "REFUSED its real-time policy" } ?? "never ran (no looks where Mach is not)"
        let head = String(
            format: "%@: %ld frames, %ld excused, %.1f ms in all; %ld looks at the frame thread, the looker %@",
            series.name, series.frames.count, excused.count, excused.reduce(0) { $0 + $1.excusedMilliseconds }, series.looks.count, looker
        )
        let tail = Self.tail(of: series.frames.count)
        let inconclusive = excused.count > tail
            ? [String(format: "  INCONCLUSIVE: frames excused: %ld, more than the p95's tail of %ld: a measurement that excused most of itself measured nothing", excused.count, tail)]
            : []
        return [head] + inconclusive + series.frames.enumerated().filter { $0.element.milliseconds > frameLimit }.map { index, frame in
            let span = Span(frame.at, frame.at + frame.milliseconds / 1000)
            let proven = MachineShare.starvation(in: span, looks: series.looks)
            let inside = series.looks.filter { $0.time >= span.start && $0.time <= span.end }
            let why = proven.isEmpty
                ? String(format: "nothing excused: %ld looks in it, runnable at %ld", inside.count, inside.filter(\.runnable).count)
                : String(format: "%.1f ms excused: ", frame.excusedMilliseconds) + proven.map { $0.told(cpus: ProcessInfo.processInfo.activeProcessorCount) }.joined(separator: "; ")
            return String(
                format: "  frame %ld at %.3f s: %.2f ms, its thread's CPU %.2f ms, its wait for the lock %.2f ms; %@",
                index, frame.at, frame.milliseconds, frame.cpuMilliseconds, frame.waitMilliseconds, why
            )
        }
    }

    /// How long each of ``frames`` `frame()`s took, when it waited for the lock (seconds since
    /// `origin`), how many slices the feeder fed meanwhile and the CPU time its thread used, while
    /// `chunks` were fed over and over, back to back, on another thread until the last frame was
    /// taken; and a ``Looker``'s looks at its thread.
    private static func framesWhileFeeding(_ chunks: [[UInt8]], origin: ContinuousClock.Instant) throws -> Measured {
        let clock = ContinuousClock()
        let screen = try Screen(size: GridSize(columns: 200, rows: 60), scrollbackBytes: 10 << 20, palette: .dark)
        let enough = Atomic<Bool>(false)
        let stopped = DispatchSemaphore(value: 0)
        let feeder = Thread {
            while !enough.load(ordering: .acquiring) {
                for chunk in chunks {
                    guard !enough.load(ordering: .acquiring) else { break }
                    _ = screen.feed(chunk)
                }
            }
            stopped.signal()
        }
        feeder.start()
        defer {
            enough.store(true, ordering: .releasing)
            stopped.wait()
        }
        let looker = Looker { (ContinuousClock.now - origin) / .seconds(1) }
        defer { looker.stop() }
        var frames: [Duration] = []
        var waits: [Span] = []
        var slices: [Int] = []
        var cpus: [Double] = []
        while frames.count < Self.frames {
            let cpu = Looker.thisThreadsCPU()
            let started = clock.now
            let timed = try screen.timedFrame()
            frames.append(clock.now - started)
            cpus.append(Looker.thisThreadsCPU() - cpu)
            slices.append(timed.slicesWaited)
            let at = (started - origin) / .seconds(1)
            waits.append(Span(at, at + timed.waited / .seconds(1)))
            Thread.sleep(forTimeInterval: 1.0 / 120)
        }
        return Measured(frames: frames, waits: waits, slices: slices, cpus: cpus, looks: looker.looks, realTime: looker.realTime)
    }

    /// Lines of coloured output as a build log or `ls --color` writes them.
    private static func lines(count: Int) -> String {
        (0 ..< count).map { index in
            "\u{1b}[3\(index % 8)m\(index)\u{1b}[0m compiling module \u{1b}[1msource-\(index % 97).swift\u{1b}[0m with \(index % 13) warnings\r\n"
        }.joined()
    }
}
