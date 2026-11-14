import Foundation
@testable import SailVT
import Synchronization
import Testing

/// The feeder and every other caller take turns: a caller waits behind at most
/// ``Screen/handOffEvery`` slices (the reviewer saw 192-361 ms frame waits and a 372 ms p95 key
/// encode before turns), and callers that come back as fast as they can get a turn at most every
/// that many slices or ``Screen/turnEvery`` (the reviewer saw two such readers cut output to
/// 0.33 MiB/s). Slices and turns are counted rather than timed where they can be: a wait in time
/// also counts waiting for a CPU on a busy runner.
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

    @Test func aFrameWaitsBehindAtMostATurnsSlicesOfBackToBackFeeds() throws {
        let screen = try Self.screen()
        let stop = Self.flooding(screen)
        defer { stop() }
        _ = screen.feed(Self.chunk(1 << 20))
        let waits = try (0 ..< 1000).map { _ in try screen.timedFrame().wait.slices }
        #expect(waits.allSatisfy { $0 <= Screen.handOffEvery }, "most slices waited behind: \(waits.max() ?? 0)")
    }

    /// With turns due by output alone (``Screen/turnEvery`` set past the test's length), two readers
    /// that never pause get a turn at most every ``Screen/handOffEvery`` slices, and take the lock
    /// only in their turns, once each.
    @Test func readersThatNeverPauseGetATurnAtMostEveryTurnsWorthOfOutput() throws {
        let screen = try Screen(
            size: GridSize(columns: 200, rows: 60), scrollbackBytes: 1 << 20, palette: .dark, now: { ContinuousClock.now },
            turnEvery: .seconds(3600)
        )
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
        let turnsBefore = screen.turns.load(ordering: .sequentiallyConsistent)
        let servedBefore = screen.ticketsServed
        for _ in 0 ..< feeds {
            _ = screen.feed(chunk)
        }
        let turns = screen.turns.load(ordering: .sequentiallyConsistent) - turnsBefore
        let served = screen.ticketsServed - servedBefore
        stop.store(true, ordering: .releasing)
        while stopped.load(ordering: .acquiring) < 2 {
            _ = screen.feed(Self.chunk(1 << 20))
        }
        let slices = feeds * ((chunk.count + Screen.feedSlice - 1) / Screen.feedSlice)
        #expect(turns <= slices / Screen.handOffEvery + 2, "\(turns) turns for \(slices) slices")
        #expect(served <= 2 * turns + 2, "\(served) readings in \(turns) turns")
    }

    @Test func aKeysEncodingIsLetInDuringAFlood() throws {
        let screen = try Self.screen()
        let stop = Self.flooding(screen, Self.chunk(8 << 20))
        defer { stop() }
        _ = screen.feed(Self.chunk(1 << 20))
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

    @Test func aFrameWithNothingNewFedTakesNoTicket() throws {
        let screen = try Self.screen()
        _ = screen.feed(Array("hello".utf8))
        #expect(try screen.timedFrame().wait.ticketed)
        #expect(try !screen.timedFrame().wait.ticketed)
        _ = screen.feed(Array(" again".utf8))
        #expect(try screen.timedFrame().wait.ticketed)
    }
}
