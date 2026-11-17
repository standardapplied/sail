import Dispatch
import Foundation
@testable import SailSurface
import Synchronization
import Testing

@Suite struct WorkThreadTests {
    /// Work runs on the thread, never the caller's, in the order it was handed over; the thread
    /// ends once let go of, after the work handed to it before.
    @Test func workRunsInOrderOnTheThreadAndItEndsWhenLetGo() throws {
        let order = Mutex<[Int]>([]), elsewhere = Mutex(true)
        let caller = ObjectIdentifier(Thread.current)
        let done = DispatchSemaphore(value: 0)
        var thread: WorkThread? = WorkThread(name: "test")
        let ended = try #require(thread?.ended)
        for index in 0 ..< 100 {
            thread?.run {
                order.withLock { $0.append(index) }
                elsewhere.withLock { $0 = $0 && ObjectIdentifier(Thread.current) != caller }
                if index == 99 { done.signal() }
            }
        }
        thread = nil
        #expect(done.wait(timeout: .now() + 10) == .success, "the work handed over before it was let go did not all run")
        #expect(order.withLock { $0 } == Array(0 ..< 100))
        #expect(elsewhere.withLock { $0 }, "work ran on the caller's thread")
        #expect(ended.wait(timeout: .now() + 10) == .success, "the thread did not end once let go of")
    }
}
