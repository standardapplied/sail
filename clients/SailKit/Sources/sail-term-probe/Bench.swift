import Foundation
import SailTerminal
import SailVT
import Synchronization

/// T10's budgets for the screen's own overhead, measured in process: an output frame of 4 KiB to
/// the screen updated at 200x60, the rate a 20 MiB stream feeds at, and how long a renderer's
/// `frame()` takes while that stream arrives twenty times, called at 120 Hz on a thread of its own:
/// within half a 60 Hz frame (8 ms) at p95, so a 60 Hz display keeps up while a program floods the
/// screen, where at 120 Hz a flood may cost a refresh now and then. Its maximum is reported.
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
        let mebibytes = Double(stream.count) / 1_048_576
        let rate = mebibytes / (elapsed / 1000)
        let burst = Percentiles(try framesDuringBurst(stream))
        return [
            Budget(name: "4 KiB output to screen updated at 200x60, p95", milliseconds: Percentiles(updates).p95, limit: 2),
            Budget(name: "20 MiB fed, milliseconds per MiB (50 MiB/s is 20)", milliseconds: 1000 / rate, limit: 20),
            Budget(name: "frame() at 120 Hz during a 20 MiB burst at 200x60, p95", milliseconds: burst.p95, limit: 8),
            Budget(name: "frame() at 120 Hz during a 20 MiB burst at 200x60, max (reported)", milliseconds: burst.max, limit: .infinity),
        ]
    }

    /// How many times the burst is fed, so the frames taken meanwhile are enough to rank.
    static let bursts = 20

    /// The time each `frame()` took while `stream` was fed ``bursts`` times in 1 MiB output frames,
    /// the most a host sends at once, on another thread.
    private static func framesDuringBurst(_ stream: [UInt8]) throws -> [Duration] {
        let clock = ContinuousClock()
        let screen = try Screen(size: GridSize(columns: 200, rows: 60), scrollbackBytes: 10 << 20, palette: .dark)
        let fed = Mutex(false)
        let feeder = Thread {
            for _ in 0 ..< bursts {
                for start in stride(from: 0, to: stream.count, by: 1 << 20) {
                    _ = screen.feed(Array(stream[start ..< min(stream.count, start + (1 << 20))]))
                }
            }
            fed.withLock { $0 = true }
        }
        feeder.start()
        var frames: [Duration] = []
        while !fed.withLock({ $0 }) {
            let started = clock.now
            _ = try screen.frame()
            frames.append(clock.now - started)
            Thread.sleep(forTimeInterval: 1.0 / 120)
        }
        return frames
    }

    /// Lines of coloured output as a build log or `ls --color` writes them.
    private static func lines(count: Int) -> String {
        (0 ..< count).map { index in
            "\u{1b}[3\(index % 8)m\(index)\u{1b}[0m compiling module \u{1b}[1msource-\(index % 97).swift\u{1b}[0m with \(index % 13) warnings\r\n"
        }.joined()
    }
}
