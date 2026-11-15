import Foundation
import Synchronization

/// The longest a clock's ticks, due every 10 ms, went apart since the last reset: how long the
/// thread that ticks it was kept from running. Beside the view's figures it says whose an output's
/// wait was: a wait the drive's own thread shares was the process's or the machine's; one only the
/// main thread shares, the main thread's (inside a refresh when the longest refresh shares it too,
/// such as a wait for a drawable); one neither shares, the view's.
final class Heartbeat: Sendable {
    static let interval = 0.01
    private let gaps = Mutex((last: ContinuousClock.now, longest: Duration.zero))

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
        let now = ContinuousClock.now
        gaps.withLock { $0 = (now, max($0.longest, now - $0.last)) }
    }

    func reset() {
        gaps.withLock { $0 = (ContinuousClock.now, .zero) }
    }

    /// The longest gap in milliseconds.
    var longest: Double {
        gaps.withLock { $0.longest } / .milliseconds(1)
    }
}
