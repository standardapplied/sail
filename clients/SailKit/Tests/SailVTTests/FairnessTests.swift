import Foundation
@testable import SailVT
import Synchronization
import Testing

/// A renderer reading frames while 1 MiB outputs are fed back to back with no gap is let in
/// between slices: it waits behind at most the slice already being fed, not a whole feed's 64 or
/// until the feeder runs dry (the reviewer saw 192-361 ms waits without the hand-off). Slices are
/// counted rather than timed, since a wait in time also counts waiting for a CPU on a busy runner.
@Suite(.timeLimit(.minutes(1))) struct FairnessTests {
    @Test func aFrameIsLetInBetweenSlicesOfBackToBackFeeds() throws {
        let screen = try Screen(size: GridSize(columns: 200, rows: 60), scrollbackBytes: 1 << 20, palette: .dark)
        let line = Array("\u{1b}[32mcompiling\u{1b}[0m a line of build output that scrolls the screen\r\n".utf8)
        let chunk = Array([[UInt8]](repeating: line, count: (1 << 20) / line.count).joined())
        let stop = Atomic<Bool>(false)
        let stopped = Atomic<Bool>(false)
        Thread {
            while !stop.load(ordering: .acquiring) {
                _ = screen.feed(chunk)
            }
            stopped.store(true, ordering: .releasing)
        }.start()
        defer {
            stop.store(true, ordering: .releasing)
            while !stopped.load(ordering: .acquiring) {}
        }
        _ = screen.feed(chunk)
        let waits = try (0 ..< 1000).map { _ in try screen.timedFrame().slicesWaited }
        #expect(waits.allSatisfy { $0 <= 1 }, "most slices waited behind: \(waits.max() ?? 0)")
    }
}
