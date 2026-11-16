@testable import SailDrive
import SailSurface
import Testing

/// The drive's allowance for a hosted runner: a wait less only the drive's own thread's stall over
/// that same wait, less the heartbeat's tick, never below zero and never more than the overlap.
@Suite struct StallsTests {
    static let wait = Span(10.0, 10.2)

    static func own(_ gaps: Span...) -> Stalls {
        var stalls = Stalls(tick: 0.01)
        gaps.forEach { stalls.record($0) }
        return stalls
    }

    @Test func aStallThatDoesNotOverlapTheWaitTakesNothing() {
        #expect(Self.own(Span(9.0, 9.5), Span(10.3, 10.9)).net(of: Self.wait) == Self.wait.length)
    }

    @Test func aStallElsewhereInTheRunTakesNothingFromThisWait() {
        let stalls = Self.own(Span(2.0, 2.5), Span(10.05, 10.07))
        #expect(abs(stalls.net(of: Self.wait) - 0.19) < 1e-9, "the 500 ms stall at second 2 says nothing of a wait at second 10")
    }

    @Test func aPartlyOverlappingStallTakesTheOverlapLessTheTickAtMost() {
        #expect(abs(Self.own(Span(10.15, 10.3)).net(of: Self.wait) - 0.15) < 1e-9, "only the 50 ms inside the wait")
        #expect(abs(Self.own(Span(10.15, 10.17)).net(of: Self.wait) - 0.19) < 1e-9, "a 20 ms gap held the thread 10 ms")
    }

    @Test func aStallCoveringTheWaitTakesAllOfIt() {
        #expect(Self.own(Span(9.9, 10.4)).net(of: Self.wait) == 0)
        #expect(abs(Self.own(Span(10.0, 10.2)).net(of: Self.wait) - 0.01) < 1e-9, "a gap the length of the wait leaves its tick")
    }

    /// Under load every tick runs a little late; twenty ticks 5 ms late are not a 100 ms stall.
    @Test func lateTicksDoNotAddUpToAStall() {
        let late = (0 ..< 20).map { Span(10.0 + Double($0) * 0.015, 10.0 + Double($0 + 1) * 0.015) }
        var stalls = Stalls(tick: 0.01)
        late.forEach { stalls.record($0) }
        #expect(abs(stalls.net(of: Span(10.0, 10.3)) - 0.295) < 1e-9)
        #expect(abs(Self.own(Span(10.0, 10.05), Span(10.1, 10.2)).net(of: Self.wait) - 0.11) < 1e-9, "of two stalls, the longer counts")
    }

    @Test func aMainThreadStallAloneTakesNothing() {
        #expect(Self.own().net(of: Self.wait) == Self.wait.length)
        #expect(Self.own(Span(10.0, 10.01), Span(10.01, 10.02)).gaps.isEmpty, "ticks on time are not stalls")
    }
}
