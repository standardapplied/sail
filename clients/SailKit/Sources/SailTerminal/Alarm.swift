import Synchronization

/// A wait that a ring cuts short: the reconnect supervisor's, which ``TerminalSession/reconnectNow()``
/// rings. A ring with nobody waiting is kept for the next wait, and dropped by ``clear()``.
package final class Alarm: Sendable {
    private struct State {
        var waiter: CheckedContinuation<Void, Never>?
        var rung = false
    }

    private let state = Mutex(State())
    private let sleep: @Sendable (Duration) async throws -> Void

    package init(sleep: @escaping @Sendable (Duration) async throws -> Void) {
        self.sleep = sleep
    }

    /// Returns after `duration`, or at the first ring, or when the task is cancelled; nil waits for
    /// the ring alone.
    package func wait(_ duration: Duration?) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.rang() }
            if let duration {
                group.addTask { try? await self.sleep(duration) }
            }
            await group.next()
            group.cancelAll()
        }
    }

    package func ring() {
        let waiter: CheckedContinuation<Void, Never>? = state.withLock { state in
            guard let waiter = state.waiter else {
                state.rung = true
                return nil
            }
            state.waiter = nil
            return waiter
        }
        waiter?.resume()
    }

    /// Forgets a ring nobody waited for.
    package func clear() {
        state.withLock { $0.rung = false }
    }

    private func rang() async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { (waiter: CheckedContinuation<Void, Never>) in
                let now: Bool = state.withLock { state in
                    if state.rung || Task.isCancelled {
                        state.rung = false
                        return true
                    }
                    state.waiter = waiter
                    return false
                }
                if now { waiter.resume() }
            }
        } onCancel: {
            let waiter: CheckedContinuation<Void, Never>? = state.withLock { state in
                defer { state.waiter = nil }
                return state.waiter
            }
            waiter?.resume()
        }
    }
}
