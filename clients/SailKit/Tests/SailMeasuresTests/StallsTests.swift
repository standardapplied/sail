import SailMeasures
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

/// The main thread's starvation, from a sampler's looks: only runnable, CPU-less time while the
/// machine had no idle CPU is the machine's.
@Suite struct MachineShareTests {
    static let wait = Span(10.0, 10.2)

    static func looks(runnable: Bool = true, cpuPerLook: Double = 0, idlePerLook: UInt32 = 0, busyPerLook: UInt32 = 1) -> [ThreadLook] {
        (0 ... 20).map { index in
            ThreadLook(
                time: 10.0 + Double(index) * 0.01, runnable: runnable, cpu: Double(index) * cpuPerLook,
                idleTicks: 100 + UInt32(index) * idlePerLook, busyTicks: 500 + UInt32(index) * busyPerLook
            )
        }
    }

    @Test func aRunnableThreadGettingNoCPUOnABusyMachineIsTheMachines() {
        #expect(abs(MachineShare.starved(in: Self.wait, looks: Self.looks()) - 0.2) < 1e-9)
    }

    @Test func aThreadWaitingOnSomethingOfOursIsNeverTheMachines() {
        #expect(MachineShare.starved(in: Self.wait, looks: Self.looks(runnable: false)) == 0)
    }

    @Test func aThreadThatGotCPUWasWorking() {
        #expect(MachineShare.starved(in: Self.wait, looks: Self.looks(cpuPerLook: 0.005)) == 0)
    }

    @Test func idleCPUOnTheMachineMeansTheThreadWasNotStarved() {
        #expect(MachineShare.starved(in: Self.wait, looks: Self.looks(idlePerLook: 1)) == 0)
    }

    @Test func countersThatDoNotMoveSayNothing() {
        #expect(MachineShare.starved(in: Self.wait, looks: Self.looks(busyPerLook: 0)) == 0)
    }

    @Test func onlyLooksInsideTheWaitCountAndTheShareIsClampedToIt() {
        let wait = Span(10.05, 10.1)
        #expect(abs(MachineShare.starved(in: wait, looks: Self.looks()) - 0.05) < 1e-9)
        #expect(MachineShare.starved(in: Span(11, 12), looks: Self.looks()) == 0)
    }

    @Test func theShareIsTheLargerOfTheOwnThreadsStallAndTheStarvation() {
        var own = Stalls(tick: 0.01)
        own.record(Span(10.0, 10.06))
        #expect(abs(MachineShare.of(Self.wait, own: own, main: []) - 0.05) < 1e-9)
        #expect(abs(MachineShare.of(Self.wait, own: own, main: Self.looks()) - 0.2) < 1e-9)
        #expect(abs(MachineShare.of(Span(10.0, 10.1), own: own, main: Self.looks()) - 0.1) < 1e-9, "never more than the wait")
    }
}
