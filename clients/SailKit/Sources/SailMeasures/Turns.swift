import Foundation
import Synchronization

/// A run loop's turns, told by the run loop's own observers: each from its wake to its sleep, or,
/// while the loop goes round without sleeping, from one start of an iteration to the next. The
/// longest on the wall clock, and the most of the thread's own CPU time any one turn spent, which
/// preemption by another process does not lengthen. Both since the last reset.
public final class Turns: Sendable {
    private let now: @Sendable () -> Double
    private let cpu: @Sendable () -> Double
    private typealias State = (start: (wall: Double, cpu: Double)?, iterated: Bool, longest: Double, longestCPU: Double)
    private let state: Mutex<State>

    public init(now: @escaping @Sendable () -> Double, cpu: @escaping @Sendable () -> Double = Turns.threadCPU) {
        self.now = now
        self.cpu = cpu
        state = Mutex((nil, false, 0, 0))
    }

    /// The calling thread's own CPU time so far, in seconds.
    public static func threadCPU() -> Double {
        var spec = timespec()
        clock_gettime(CLOCK_THREAD_CPUTIME_ID, &spec)
        return Double(spec.tv_sec) + Double(spec.tv_nsec) / 1e9
    }

    /// A turn begins: the one under way, if any, ends here.
    public func woke() {
        let wall = now(), ran = cpu()
        state.withLock { state in
            Self.end(&state, wall, ran)
            state.start = (wall, ran)
            state.iterated = false
        }
    }

    /// The loop starts an iteration: a woken turn's first runs on in it; any other begins a turn.
    public func iterated() {
        let wall = now(), ran = cpu()
        state.withLock { state in
            if state.start != nil, !state.iterated {
                state.iterated = true
                return
            }
            Self.end(&state, wall, ran)
            state.start = (wall, ran)
            state.iterated = true
        }
    }

    /// The turn under way ends.
    public func slept() {
        let wall = now(), ran = cpu()
        state.withLock { state in
            Self.end(&state, wall, ran)
            state.start = nil
        }
    }

    /// Forgets every turn; one under way counts from here.
    public func reset() {
        let wall = now(), ran = cpu()
        state.withLock { state in
            state = (state.start.map { _ in (wall, ran) }, state.iterated, 0, 0)
        }
    }

    /// The longest turn on the wall clock, in milliseconds.
    public var longest: Double {
        state.withLock { $0.longest } * 1000
    }

    /// The most CPU time one turn spent, in milliseconds.
    public var longestCPU: Double {
        state.withLock { $0.longestCPU } * 1000
    }

    private static func end(_ state: inout State, _ wall: Double, _ ran: Double) {
        guard let start = state.start else { return }
        state.longest = max(state.longest, wall - start.wall)
        state.longestCPU = max(state.longestCPU, ran - start.cpu)
    }
}
