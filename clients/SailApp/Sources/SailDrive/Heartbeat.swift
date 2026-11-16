import Foundation
import QuartzCore
import SailMeasures
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

    /// When it last ticked, on the host clock.
    var lastTick: Double {
        gaps.withLock { $0.last }
    }

    /// The longest gap in milliseconds.
    var longest: Double {
        gaps.withLock { $0.longest } * 1000
    }

    var stalls: Stalls {
        gaps.withLock { $0.stalls }
    }
}
