import Foundation
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
/// small feeds as much as large ones (measured 2.2 and 3.2 ms p95), which no lock can help.
enum Bench {
    static func run() throws -> [Budget] {
        let clock = ContinuousClock()
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
        var budgets = [
            Budget(name: "4 KiB output to screen updated at 200x60, p95", milliseconds: Percentiles(updates).p95, limit: 2),
            Budget(name: "20 MiB fed, milliseconds per MiB (50 MiB/s is 20)", milliseconds: 1000 / rate, limit: 20),
        ]
        for (label, size) in [("1 MiB", 1 << 20), ("13 KiB", 13 * 1024)] {
            let (frames, waits) = try framesWhileFeeding(stride(from: 0, to: stream.count, by: size).map {
                Array(stream[$0 ..< min(stream.count, $0 + size)])
            })
            let frame = Percentiles(frames)
            let wait = Percentiles(waits)
            budgets += [
                Budget(name: "frame() at 120 Hz, \(label) feeds back to back, p95", milliseconds: frame.p95, limit: 4),
                Budget(name: "frame() at 120 Hz, \(label) feeds back to back, max (reported)", milliseconds: frame.max, limit: .infinity),
                Budget(name: "frame()'s wait for the lock, \(label) feeds back to back, p95", milliseconds: wait.p95, limit: 1),
                Budget(name: "frame()'s wait for the lock, \(label) feeds back to back, max (reported)", milliseconds: wait.max, limit: .infinity),
            ]
        }
        return budgets
    }

    /// How many times the stream is fed, so the frames taken meanwhile are enough to rank.
    static let rounds = 20

    /// How long each `frame()` took, and waited for the lock, while `chunks` were fed ``rounds``
    /// times back to back on another thread.
    private static func framesWhileFeeding(_ chunks: [[UInt8]]) throws -> (frames: [Duration], waits: [Duration]) {
        let clock = ContinuousClock()
        let screen = try Screen(size: GridSize(columns: 200, rows: 60), scrollbackBytes: 10 << 20, palette: .dark)
        let fed = Atomic<Bool>(false)
        let feeder = Thread {
            for _ in 0 ..< rounds {
                for chunk in chunks {
                    _ = screen.feed(chunk)
                }
            }
            fed.store(true, ordering: .releasing)
        }
        feeder.start()
        var frames: [Duration] = []
        var waits: [Duration] = []
        while !fed.load(ordering: .acquiring) {
            let started = clock.now
            let waited = try screen.timedFrame().waited
            frames.append(clock.now - started)
            waits.append(waited)
            Thread.sleep(forTimeInterval: 1.0 / 120)
        }
        return (frames, waits)
    }

    /// Lines of coloured output as a build log or `ls --color` writes them.
    private static func lines(count: Int) -> String {
        (0 ..< count).map { index in
            "\u{1b}[3\(index % 8)m\(index)\u{1b}[0m compiling module \u{1b}[1msource-\(index % 97).swift\u{1b}[0m with \(index % 13) warnings\r\n"
        }.joined()
    }
}
