import Foundation
import SailSurface
import Synchronization

/// When a clock's ticks, due every 10 ms, went apart since the last reset, on the measuring
/// process's clock: how long, and when, the thread that ticks it was kept from running. A wait the
/// measuring process's own thread was held through too was the machine's; one only the main thread
/// shares, the main thread's.
public final class Heartbeat: Sendable {
    public static let interval = 0.01
    private let now: @Sendable () -> Double
    private let gaps: Mutex<(last: Double, longest: Double, stalls: Stalls)>
    /// The main run loop's timer that ticks it, kept so its kind and tolerance can be read; nil
    /// for one ticked by a thread of its own. Set once, on the main thread, before any reader.
    nonisolated(unsafe) public private(set) var timer: Timer?

    init(now: @escaping @Sendable () -> Double) {
        self.now = now
        gaps = Mutex((now(), 0, Stalls(tick: Self.interval)))
    }

    /// Ticked by the main run loop in every mode.
    @MainActor public static func onTheMainThread(now: @escaping @Sendable () -> Double) -> Heartbeat {
        let beat = Heartbeat(now: now)
        let timer = Timer(timeInterval: interval, repeats: true) { _ in beat.tick() }
        RunLoop.main.add(timer, forMode: .common)
        beat.timer = timer
        return beat
    }

    /// The name of the thread a heartbeat of its own ticks on.
    public static let threadName = "sh.standardapplied.sail.heartbeat"

    /// Ticked by a thread of its own, which nothing else runs on.
    public static func onItsOwnThread(now: @escaping @Sendable () -> Double) -> Heartbeat {
        let beat = Heartbeat(now: now)
        let thread = Thread {
            while true {
                Thread.sleep(forTimeInterval: interval)
                beat.tick()
            }
        }
        thread.qualityOfService = .userInteractive
        thread.name = threadName
        thread.start()
        return beat
    }

    func tick() {
        let time = now()
        gaps.withLock { gaps in
            gaps.stalls.record(Span(gaps.last, time))
            gaps.longest = max(gaps.longest, time - gaps.last)
            gaps.last = time
        }
    }

    public func reset() {
        let time = now()
        gaps.withLock { $0 = (time, 0, Stalls(tick: Self.interval)) }
    }

    /// When it last ticked.
    public var lastTick: Double {
        gaps.withLock { $0.last }
    }

    /// The longest gap in milliseconds.
    public var longest: Double {
        gaps.withLock { $0.longest } * 1000
    }

    public var stalls: Stalls {
        gaps.withLock { $0.stalls }
    }
}
