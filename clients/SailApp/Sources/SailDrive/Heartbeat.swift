import Foundation
import QuartzCore
import SailSurface
import Synchronization

/// When a clock's ticks, due every 10 ms, went apart since the last reset, on the host clock: how
/// long, and when, the thread that ticks it was kept from running. A wait the drive's own thread
/// was held through too was the machine's; one only the main thread shares, the main thread's.
final class Heartbeat: Sendable {
    static let interval = 0.01
    private let gaps = Mutex((last: CACurrentMediaTime(), longest: 0.0, stalls: Stalls(tick: Heartbeat.interval)))

    /// Ticked by the main run loop in every mode.
    @MainActor static func onTheMainThread() -> Heartbeat {
        let beat = Heartbeat()
        RunLoop.main.add(Timer(timeInterval: interval, repeats: true) { _ in beat.tick() }, forMode: .common)
        return beat
    }

    /// Ticked by a thread of its own, which nothing else runs on.
    static func onItsOwnThread() -> Heartbeat {
        let beat = Heartbeat()
        let thread = Thread {
            while true {
                Thread.sleep(forTimeInterval: interval)
                beat.tick()
            }
        }
        thread.qualityOfService = .userInteractive
        thread.start()
        return beat
    }

    func tick() {
        let now = CACurrentMediaTime()
        gaps.withLock { gaps in
            gaps.stalls.record(Span(gaps.last, now))
            gaps.longest = max(gaps.longest, now - gaps.last)
            gaps.last = now
        }
    }

    func reset() {
        gaps.withLock { $0 = (CACurrentMediaTime(), 0, Stalls(tick: Self.interval)) }
    }

    /// The longest gap in milliseconds.
    var longest: Double {
        gaps.withLock { $0.longest } * 1000
    }

    var stalls: Stalls {
        gaps.withLock { $0.stalls }
    }
}

/// When a thread that only ticks, once every `tick` seconds, was kept from running: each gap
/// between its ticks held it for the gap less one tick.
struct Stalls: Sendable {
    static let capacity = 100_000

    let tick: Double
    private(set) var gaps: [Span] = []

    init(tick: Double) {
        self.tick = tick
    }

    mutating func record(_ gap: Span) {
        guard gap.length > tick else { return }
        gaps.append(gap)
        if gaps.count > Self.capacity { gaps.removeFirst() }
    }

    /// How much of `wait` this thread was held too, in seconds: the longest stall that overlaps
    /// it, less one tick, never below zero and never more than the overlap. A stall the machine held
    /// every thread through is the machine's; one at another moment says nothing of this wait, and
    /// ticks merely late on a busy machine do not add up to one.
    func shared(with wait: Span) -> Double {
        gaps.reduce(0) { held, gap in max(held, min(gap.overlap(wait), max(0, gap.length - tick))) }
    }

    /// The longest gap, or the longest that overlaps `span`.
    func longest(in span: Span? = nil) -> Span? {
        gaps.filter { gap in span.map { gap.overlap($0) > 0 } ?? true }.max { $0.length < $1.length }
    }

    func net(of wait: Span) -> Double {
        max(0, wait.length - shared(with: wait))
    }
}
