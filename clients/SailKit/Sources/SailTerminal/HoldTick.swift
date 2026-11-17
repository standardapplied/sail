import Foundation
import Synchronization

/// Work set to run once, which can be called off before it runs.
package protocol Scheduled: Sendable {
    func cancel()
}

/// Runs work once a delay has passed, on a timer of its own: the tick that ends a render hold left
/// open must come on time whatever Swift's shared pool is doing, as the screen's changes do.
package enum HoldTick {
    /// Runs `work` once `delay` has passed; what it returns calls it off.
    package typealias After = @Sendable (Duration, @escaping @Sendable () -> Void) -> any Scheduled

    private static let queue = DispatchQueue(label: "sh.standardapplied.sail.terminal.hold", qos: .userInteractive)

    /// A dispatch timer on a queue of its own.
    package static let dispatch: After = { delay, work in
        let timer = Timer()
        let (seconds, attoseconds) = delay.components
        let nanoseconds = Int(seconds) * 1_000_000_000 + Int(attoseconds / 1_000_000_000)
        queue.asyncAfter(deadline: .now() + .nanoseconds(nanoseconds)) {
            if !timer.cancelled.withLock({ $0 }) { work() }
        }
        return timer
    }

    private final class Timer: Scheduled {
        let cancelled = Mutex(false)

        func cancel() {
            cancelled.withLock { $0 = true }
        }
    }
}
