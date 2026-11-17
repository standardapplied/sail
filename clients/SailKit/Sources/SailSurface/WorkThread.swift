import Dispatch
import Foundation
import Synchronization

/// A thread of its own that runs the work handed to it, in order. Handing it work is one
/// semaphore signal to a thread already waiting. `async` on a global queue, when no worker is idle,
/// also asks the kernel to add one (`_pthread_workqueue_addthreads`) from the caller's thread. On an
/// overloaded CI runner the main thread was sampled inside that call for 250 ms, runnable and given
/// 0.1 ms of CPU: starved there, as it could be anywhere. A signal can be pre-empted as well, so
/// this does not make the main thread immune to an overloaded machine; it removes one kernel
/// request from every frame's path. Each piece of work runs in an autorelease pool of its own, as
/// a dispatch worker's does: a Metal drawable is autoreleased, and one never released starves the
/// layer of drawables. The thread ends once its owner lets go of it, after the work handed to it
/// before.
public final class WorkThread: Sendable {
    /// What the thread takes its work from; the thread holds it, so the owner's letting go of the
    /// thread is what ends it.
    private final class Queue: Sendable {
        let pending = Mutex<[@Sendable () -> Void]>([])
        let ready = DispatchSemaphore(value: 0)
        let ended = DispatchSemaphore(value: 0)
    }

    /// Signalled once when the thread has ended: what a test waits on.
    var ended: DispatchSemaphore {
        queue.ended
    }

    private let queue = Queue()

    public init(name: String) {
        let thread = Thread { [queue] in
            while true {
                queue.ready.wait()
                guard let next = queue.pending.withLock({ $0.isEmpty ? nil : $0.removeFirst() }) else {
                    queue.ended.signal()
                    return
                }
                #if canImport(ObjectiveC)
                autoreleasepool { next() }
                #else
                next()
                #endif
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
