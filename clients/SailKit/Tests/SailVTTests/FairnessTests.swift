import Foundation
@testable import SailVT
import Synchronization
import Testing

/// The feeder hands the lock off to callers: a caller waits behind at most ``Screen/handOffEvery``
/// slices (the reviewer saw 192-361 ms frame waits before hand-offs), and callers that come back as
/// fast as they can get a hand-off at most every that many slices (the reviewer saw two such
/// readers cut output to 0.33 MiB/s when the feeder let them in at every slice). Slices and
/// hand-offs are counted rather than timed: a wait in time also counts waiting for a CPU on a busy
/// runner.
@Suite(.timeLimit(.minutes(1))) struct FairnessTests {
    static let line = Array("\u{1b}[32mcompiling\u{1b}[0m a line of build output that scrolls the screen\r\n".utf8)

    static func chunk(_ bytes: Int) -> [UInt8] {
        Array([[UInt8]](repeating: line, count: bytes / line.count).joined())
    }

    static func screen() throws -> Screen {
        try Screen(size: GridSize(columns: 200, rows: 60), scrollbackBytes: 1 << 20, palette: .dark)
    }

    /// Feeds 1 MiB outputs back to back on another thread until the returned stop is called.
    static func flooding(_ screen: Screen, _ chunk: [UInt8] = chunk(1 << 20)) -> @Sendable () -> Void {
        let stop = Atomic<Bool>(false)
        let stopped = Atomic<Bool>(false)
        Thread {
            while !stop.load(ordering: .acquiring) {
                _ = screen.feed(chunk)
            }
            stopped.store(true, ordering: .releasing)
        }.start()
        return {
            stop.store(true, ordering: .releasing)
            while !stopped.load(ordering: .acquiring) {
                sched_yield()
            }
        }
    }

    @Test func aFrameWaitsBehindAtMostAHandOffsSlicesOfBackToBackFeeds() throws {
        let screen = try Self.screen()
        let stop = Self.flooding(screen)
        defer { stop() }
        while screen.slices == 0 {
            sched_yield()
        }
        let waits = try (0 ..< 300).map { _ in try screen.timedFrame().wait.slices }
        #expect(waits.allSatisfy { $0 <= Screen.handOffEvery }, "most slices waited behind: \(waits.max() ?? 0)")
    }

    @Test func readersThatNeverPauseGetAHandOffAtMostEveryHandOffsWorthOfOutput() throws {
        let screen = try Self.screen()
        let stop = Atomic<Bool>(false)
        let stopped = Atomic<Int>(0)
        for reader in 0 ..< 2 {
            Thread {
                while !stop.load(ordering: .acquiring) {
                    if reader == 0 { _ = try? screen.frame() } else { _ = try? screen.text() }
                }
                stopped.add(1, ordering: .releasing)
            }.start()
        }
        let chunk = Self.chunk(64 * 1024)
        let feeds = 128
        let before = screen.handOffs.load(ordering: .sequentiallyConsistent)
        for _ in 0 ..< feeds {
            _ = screen.feed(chunk)
        }
        let handOffs = screen.handOffs.load(ordering: .sequentiallyConsistent) - before
        stop.store(true, ordering: .releasing)
        while stopped.load(ordering: .acquiring) < 2 {
            sched_yield()
        }
        let slices = feeds * ((chunk.count + Screen.feedSlice - 1) / Screen.feedSlice)
        #expect(handOffs <= slices / Screen.handOffEvery + 1, "\(handOffs) hand-offs for \(slices) slices")
    }

    @Test func aKeysEncodingIsLetInDuringAFlood() throws {
        let screen = try Self.screen()
        let stop = Self.flooding(screen, Self.chunk(8 << 20))
        defer { stop() }
        while screen.slices == 0 {
            sched_yield()
        }
        let key = KeyInput(key: .x, text: "x", unshifted: "x")
        var during: [Int] = []
        for _ in 0 ..< 50 {
            let before = screen.slices
            _ = screen.encode(key)
            during.append(screen.slices - before)
        }
        let median = during.sorted()[during.count / 2]
        #expect(median <= 2 * Screen.handOffEvery, "slices fed during a key's encoding, median \(median)")
    }

    @Test func aFrameWithNothingNewFedDoesNotAnnounceItself() throws {
        let screen = try Self.screen()
        _ = screen.feed(Array("hello".utf8))
        #expect(try screen.timedFrame().wait.announced)
        #expect(try !screen.timedFrame().wait.announced)
        _ = screen.feed(Array(" again".utf8))
        #expect(try screen.timedFrame().wait.announced)
    }
}
