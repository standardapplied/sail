import Dispatch
import Foundation
import Synchronization

/// A thread of its own that runs the work handed to it, in order. Handing it work only wakes a
/// thread already waiting; it never asks the system for a thread, as `async` on a global queue does
/// when no worker is idle. On the CI runner that ask held the main thread 250 ms inside
/// `_pthread_workqueue_addthreads`. The thread ends once its owner lets go of it, after the work
/// handed to it before.
public final class WorkThread: Sendable {
    /// What the thread takes its work from; the thread holds it, so the owner's letting go of the
    /// thread is what ends it.
    private final class Queue: Sendable {
        let pending = Mutex<[@Sendable () -> Void]>([])
        let ready = DispatchSemaphore(value: 0)
    }

    private let queue = Queue()

    public init(name: String) {
        let thread = Thread { [queue] in
            while true {
                queue.ready.wait()
                guard let next = queue.pending.withLock({ $0.isEmpty ? nil : $0.removeFirst() }) else { return }
                next()
            }
        }
        thread.name = name
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    deinit {
        queue.ready.signal()
    }

    /// Runs `work` on the thread, after any work handed to it before; returns at once.
    public func run(_ work: @escaping @Sendable () -> Void) {
        queue.pending.withLock { $0.append(work) }
        queue.ready.signal()
    }
}
