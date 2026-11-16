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

    static func looks(
        from start: Double = 10.0, count: Int = 21, runnable: (Double) -> Bool = { _ in true }, cpuPerLook: Double = 0,
        processPerLook: Double = 0, idlePerLook: UInt32 = 0, busyPerLook: UInt32 = 3
    ) -> [ThreadLook] {
        (0 ..< count).map { index in
            let time = ((start * 100).rounded() + Double(index)) / 100
            return ThreadLook(
                time: time, runnable: runnable(time), cpu: Double(index) * cpuPerLook, processCPU: Double(index) * processPerLook,
                idleTicks: 100 + UInt32(index) * idlePerLook, busyTicks: 500 + UInt32(index) * busyPerLook
            )
        }
    }

    @Test func aRunnableThreadGettingNoCPUOnABusyMachineIsTheMachines() {
        #expect(abs(MachineShare.starved(in: Self.wait, looks: Self.looks()) - 0.2) < 1e-9)
    }

    @Test func aThreadWaitingOnSomethingOfOursIsNeverTheMachines() {
        #expect(MachineShare.starved(in: Self.wait, looks: Self.looks(runnable: { _ in false })) == 0)
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

    /// The two shares are not added: an own-thread stall of 50 ms early in a 200 ms wait and 50 ms
    /// of starvation late in it make 50 ms of the machine's, not 100.
    @Test func theLargerShareIsTakenNotTheSum() {
        var own = Stalls(tick: 0.01)
        own.record(Span(10.0, 10.06))
        let late = Self.looks(runnable: { $0 >= 10.15 - 1e-9 })
        #expect(abs(MachineShare.starved(in: Self.wait, looks: late) - 0.05) < 1e-9)
        #expect(abs(MachineShare.of(Self.wait, own: own, main: late) - 0.05) < 1e-9)
    }

    /// The thread must be runnable at both ends of an interval: a look that finds it waiting ends
    /// the starved stretch.
    @Test func anIntervalCountsOnlyWhenTheThreadWasRunnableAtBothLooks() {
        let alternating = Self.looks(runnable: { Int((($0 - 10.0) / 0.01).rounded()) % 2 == 0 })
        #expect(MachineShare.starved(in: Self.wait, looks: alternating) == 0)
    }

    /// Looks outside the wait say nothing of it, even next to its edges.
    @Test func looksJustOutsideTheWaitDoNotCount() {
        let edges = Self.looks(from: 9.97, count: 27, runnable: { $0 < 10.0 - 1e-9 || $0 >= 10.1 - 1e-9 })
        #expect(abs(MachineShare.starved(in: Self.wait, looks: edges) - 0.1) < 1e-9)
    }

    /// Of the busy CPU that kept the main thread waiting, the share our own process used is ours.
    @Test func theCPUOurProcessUsedIsOurs() {
        #expect(abs(MachineShare.starved(in: Self.wait, looks: Self.looks(processPerLook: 0.015)) - 0.1) < 1e-9, "half the busy CPU was ours")
        #expect(MachineShare.starved(in: Self.wait, looks: Self.looks(processPerLook: 0.05)) == 0, "all of it was")
    }
}

/// A flood is judged on its output's wait and the main thread's hold, never on a gap between
/// commits when no output waited.
@Suite struct FloodVerdictTests {
    static func figures(outputAt output: Double, shownAt shown: Double, gapUntil next: Double) -> LatencyFigures {
        var marks = LatencyMarks()
        marks.committed(frame: 1, at: 10.0, read: 9.999, target: 10.016)
        marks.completed(frame: 1, at: 10.001, ok: true)
        marks.output(at: output)
        marks.committed(frame: 2, at: shown, read: shown - 0.001, target: shown + 0.016)
        marks.completed(frame: 2, at: shown + 0.001, ok: true)
        marks.committed(frame: 3, at: next, read: next - 0.001, target: next + 0.016)
        marks.completed(frame: 3, at: next + 0.001, ok: true)
        return marks.figures
    }

    @Test func aLongGapBetweenCommitsWithNoOutputWaitingIsSilence() {
        let quiet = Self.figures(outputAt: 10.005, shownAt: 10.02, gapUntil: 10.17)
        #expect(abs((quiet.longestBetweenCommits?.length ?? 0) - 0.15) < 1e-9)
        #expect(quiet.longestGapOutputs == 0, "no output reached the screen in the gap")
        let verdict = FloodVerdict.ours(quiet, mainHold: nil, tick: 0.01, own: Stalls(tick: 0.01), main: [])
        #expect(verdict.shown < 0.1 && verdict.held == 0, "the gap is the producer's, not the view's")
    }

    @Test func anOutputThatWaitedIsTheViewsAndAMainThreadHoldToo() {
        let withheld = Self.figures(outputAt: 10.005, shownAt: 10.205, gapUntil: 10.22)
        let verdict = FloodVerdict.ours(withheld, mainHold: nil, tick: 0.01, own: Stalls(tick: 0.01), main: [])
        #expect(abs(verdict.shown - 0.201) < 1e-9, "a drawable withheld 200 ms while output waited, shown at its completion")
        let quiet = Self.figures(outputAt: 10.005, shownAt: 10.02, gapUntil: 10.03)
        #expect(abs(FloodVerdict.ours(quiet, mainHold: Span(10.0, 10.21), tick: 0.01, own: Stalls(tick: 0.01), main: []).held - 0.2) < 1e-9)
    }
}
