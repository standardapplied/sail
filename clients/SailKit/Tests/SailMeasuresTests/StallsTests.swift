#if canImport(Darwin)
import Darwin
#endif
import Foundation
@testable import SailMeasures
import SailSurface
import Synchronization
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
                time: time, runnable: runnable(time), cpu: Double(index) * cpuPerLook, ourCPU: Double(index) * processPerLook,
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
        let late = Self.looks(runnable: { $0 >= 10.12 - 1e-9 })
        #expect(abs(MachineShare.of(Self.wait, own: own, main: late) - 0.08) < 1e-9)
        var whole = Stalls(tick: 0.01)
        whole.record(Span(9.9, 10.4))
        #expect(abs(MachineShare.of(Span(10.0, 10.1), own: whole, main: []) - 0.1) < 1e-9, "never more than the wait")
    }

    /// Starvation proven over more than half a wait is inconclusive: none of it is excused; up to
    /// half, it is. The drive's own thread's stall is its own measured work and is never capped.
    @Test func starvationOverMostOfAWaitExcusesNothingButTheOwnThreadsStallStands() {
        let own = Stalls(tick: 0.01)
        let under = Self.looks(runnable: { $0 >= 10.12 - 1e-9 })
        #expect(!MachineShare.inconclusive(Self.wait, main: under))
        #expect(abs(MachineShare.of(Self.wait, own: own, main: under) - 0.08) < 1e-9, "under half the wait, the starvation is excused")
        let most = Self.looks(runnable: { $0 >= 10.08 - 1e-9 })
        #expect(MachineShare.inconclusive(Self.wait, main: most))
        #expect(MachineShare.of(Self.wait, own: own, main: most) == 0, "past the ceiling no starvation is excused")
        var stalled = Stalls(tick: 0.01)
        stalled.record(Span(10.0, 10.16))
        #expect(abs(MachineShare.of(Self.wait, own: stalled, main: most) - 0.15) < 1e-9, "the own thread's stall over most of the wait stands")
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

    /// The CI run that asked for this rule (PR #450's head e7e20062c, drive step 57): the output
    /// waited 154.4 ms and the main thread's longest hold was 39.3 ms. The sampler looked only
    /// once the heartbeat was 30 ms late, and in the hold it left one look, 46 ms in, of the main
    /// thread runnable in `mach_msg2_trap` with no CPU since the hold began; in the output's wait
    /// it left fewer than two. One look proves nothing: that run is not excused.
    @Test func theRunThatAskedForThisRuleLeftTooFewLooksToBeExcused() {
        let hold = Span(20.0, 20.0393)
        let theOne = ThreadLook(time: 20.046, runnable: true, cpu: 1.25, ourCPU: 9.0, idleTicks: 400, busyTicks: 900)
        #expect(MachineShare.starvation(in: Span(19.9, 20.06), looks: [theOne]).isEmpty)
        #expect(MachineShare.starved(in: hold, looks: [theOne]) == 0)
    }

    @Test func noLooksExcuseNothing() {
        #expect(MachineShare.starvation(in: Self.wait, looks: []).isEmpty)
    }

    /// Looks further apart than ``MachineShare/longestGap`` are missing samples: a thread runnable
    /// with flat CPU at two looks 150 ms apart may have run and waited many times between them.
    @Test func missingLooksExcuseNothing() {
        let apart = [
            ThreadLook(time: 10.0, runnable: true, cpu: 1, ourCPU: 1, idleTicks: 100, busyTicks: 500),
            ThreadLook(time: 10.15, runnable: true, cpu: 1, ourCPU: 1, idleTicks: 100, busyTicks: 545),
        ]
        #expect(MachineShare.starvation(in: Self.wait, looks: apart).isEmpty)
        let sparse = Self.looks(count: 11).enumerated().filter { $0.offset % 2 == 0 }.map(\.element)
        #expect(MachineShare.starvation(in: Self.wait, looks: sparse).isEmpty, "looks 20 ms apart prove nothing")
    }

    /// Three looks in a row are as likely a thread caught on its way into a wait; four prove it.
    @Test func itTakesFourLooksInARow() {
        #expect(MachineShare.starvation(in: Self.wait, looks: Self.looks(count: 3)).isEmpty)
        let four = MachineShare.starvation(in: Self.wait, looks: Self.looks(count: 4))
        #expect(four.count == 1)
        #expect(abs(four[0].machines - 0.03) < 1e-9)
    }

    /// A blocked thread, a busy one and one on an idle machine are each the app's, with dense looks.
    @Test func denseLooksOfABlockedABusyOrAnUnstarvedThreadExcuseNothing() {
        #expect(MachineShare.starvation(in: Self.wait, looks: Self.looks(runnable: { _ in false })).isEmpty, "blocked")
        #expect(MachineShare.starvation(in: Self.wait, looks: Self.looks(cpuPerLook: 0.002)).isEmpty, "used a fifth of the time")
        #expect(MachineShare.starvation(in: Self.wait, looks: Self.looks(idlePerLook: 1)).isEmpty, "a CPU was idle")
    }

    /// Each excused stretch says what proved it, so a reader can check it.
    @Test func anExcusedStretchSaysItsEvidence() {
        let proven = MachineShare.starvation(in: Self.wait, looks: Self.looks(processPerLook: 0.015))
        #expect(proven.count == 1)
        #expect(proven[0].told(cpus: 3) == "100.0 ms excused at 10.000–10.200 s: 21 looks at most 10.0 ms apart, run state running at each, the thread's CPU +0.00 ms, the machine's idle ticks +0 and busy ticks +60 (3 CPUs make 60.0 over the stretch), our processes 50% of that busy CPU")
    }

    /// A tail budget's samples, each net of the own thread's stall over it alone.
    @Test func eachSampleIsNetOfTheStallOverItAlone() {
        var own = Stalls(tick: 0.01)
        own.record(Span(10.0, 10.04))
        own.record(Span(10.04, 10.0505))
        let samples = [Span(10.0, 10.035), Span(10.0505, 10.0535), Span(10.02, 10.025), Span(10.0, 10.005), Span(10.0495, 10.0505), Span(10.03, 10.0505)]
        let nets = MachineShare.net(samples, own: own)
        #expect(nets.count == 6)
        #expect(abs(nets[0] - 0.01) < 1e-9, "a 35 ms sample over the 30 ms held keeps the 10 ms the thread slept")
        #expect(abs(nets[1] - 0.003) < 1e-9, "a sample after the stall keeps all its time")
        #expect(nets[2] == 0, "a sample wholly inside the held part keeps nothing")
        #expect(abs(nets[3] - 0.005) < 1e-9, "a sample while the thread slept keeps all its time")
        #expect(abs(nets[4] - 0.0005) < 1e-9, "a tick late by half a millisecond takes half a millisecond")
        #expect(abs(nets[5] - 0.0105) < 1e-9, "of two held parts over a sample, the longer is taken")
    }

    /// Of the busy CPU that kept the main thread waiting, the share our own processes used (the
    /// app's, its terminal host's and the host's session shims', summed in each look) is ours: a
    /// host spinning beside the app is never the machine's.
    @Test func theCPUOurProcessesUsedIsOurs() {
        #expect(abs(MachineShare.starved(in: Self.wait, looks: Self.looks(processPerLook: 0.015)) - 0.1) < 1e-9, "half the busy CPU was ours")
        #expect(MachineShare.starved(in: Self.wait, looks: Self.looks(processPerLook: 0.05)) == 0, "all of it was")
    }

    /// A stretch whose ends did not read our processes' CPU proves nothing: what they used is
    /// unknown, so none of it can be said to be the machine's.
    @Test func ourCPUUnreadAtAnEndExcusesNothing() {
        var looks = Self.looks()
        looks[looks.count - 1].ourCPU = nil
        let proven = MachineShare.starvation(in: Self.wait, looks: looks)
        #expect(proven.isEmpty)
    }

    /// The main run loop's modes as samples read them, counted, the most seen first: whether the
    /// loop was in a mode that runs the main queue's blocks; a sample that found it running none
    /// is said so.
    @Test func theMainRunLoopsModesAreCounted() {
        let seen: [String?] = ["kCFRunLoopDefaultMode", "NSEventTrackingRunLoopMode", "kCFRunLoopDefaultMode", nil]
        #expect(MachineShare.modes(seen) == "kCFRunLoopDefaultMode at 2, NSEventTrackingRunLoopMode at 1, not running at 1")
        #expect(MachineShare.modes([]) == "no sample")
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

@Suite struct HeartbeatTests {
    /// Each tick records the gap since the last on the clock it was given; a reset starts again.
    @Test func ticksRecordTheirGapsOnTheClockGiven() {
        let time = Mutex(100.0)
        let beat = Heartbeat { time.withLock { $0 } }
        for now in [100.008, 100.05, 100.059] {
            time.withLock { $0 = now }
            beat.tick()
        }
        #expect(beat.stalls.gaps == [Span(100.008, 100.05)], "only the gap over a tick is a stall")
        #expect(abs(beat.longest - 42) < 1e-6)
        #expect(beat.lastTick == 100.059)
        time.withLock { $0 = 200 }
        beat.reset()
        #expect(beat.stalls.gaps.isEmpty)
        #expect(beat.longest == 0)
        #expect(beat.lastTick == 200)
    }

    /// Beside each gap's wall-clock length, the ticking thread's own CPU time in it: what another
    /// process's preemption cannot inflate. The longest is kept; a reset starts again.
    @Test func ticksRecordTheThreadsOwnCPUInEachTurn() {
        let time = Mutex(100.0), cpu = Mutex(5.0)
        let beat = Heartbeat(now: { time.withLock { $0 } }, cpu: { cpu.withLock { $0 } })
        for (now, used) in [(100.01, 5.001), (100.08, 5.004), (100.09, 5.013)] {
            time.withLock { $0 = now }
            cpu.withLock { $0 = used }
            beat.tick()
        }
        #expect(abs(beat.longest - 70) < 1e-6 && abs(beat.longestCPU - 9) < 1e-6, "wall \(beat.longest), cpu \(beat.longestCPU)")
        beat.reset()
        #expect(beat.longestCPU == 0)
    }
}

/// A gated figure's verdict names it first, and a series' p95 tail is counted by nearest rank.
@Suite struct VerdictTests {
    @Test func aFractionalLimitIsPrintedAsItIs() {
        #expect(Verdict.of("x", milliseconds: 0.75, limit: 0.5).said == "missed: x 0.75 ms, over its 0.5 ms limit")
    }

    @Test func aMissedBudgetIsNamedFirstWithItsLimit() {
        let missed = Verdict.of("full redraw 200x60 cpu p95", milliseconds: 5.245, limit: 4)
        #expect(!missed.met && missed.said == "missed: full redraw 200x60 cpu p95 5.25 ms, over its 4 ms limit")
        let met = Verdict.of("full redraw 200x60 cpu p95", milliseconds: 1.51, limit: 4)
        #expect(met.met && met.said == "within its budget: full redraw 200x60 cpu p95 1.51 ms, limit 4 ms")
        #expect(Verdict.of("x", milliseconds: 4, limit: 4).met, "the limit itself is within")
    }

    @Test func theP95LeavesItsTailOfTheSharedCount() {
        #expect(Sampled.count == 400 && Sampled.tail(of: Sampled.count) == 20 && Sampled.tail(of: 300) == 15 && Sampled.tail(of: 54) == 2)
    }
}

/// A redraw on a CI runner: only one 120 Hz frame gates; the 4 ms a person's Mac is held to and
/// the redraw over the loop timed beside each sample are reported, every run. Made-up samples: a
/// mid runner's redraw of 1.4 ms beside a 0.42 ms loop, every tenth sample 3.3 ms.
@Suite struct SpeedGateTests {
    static let gate = SpeedGate(name: "full redraw 200x60 cpu")
    static let references = Array(repeating: 0.42, count: 400)
    static let work = (0 ..< 400).map { 1.4 + (($0 % 10 == 0) ? 1.9 : 0) }

    @Test func aRunnerTwiceAsSlowPassesAndSaysItIsOverTheBudget() {
        let judged = Self.gate.judge(work: Self.work.map { $0 * 2 }, references: Self.references.map { $0 * 2 })
        #expect(judged.met, "\(judged.said)")
        #expect(judged.said.hasPrefix("within its budget: full redraw 200x60 cpu p95 6.60 ms, hard ceiling 8 ms"), "\(judged.said)")
        #expect(judged.said.contains("full redraw 200x60 cpu p95 6.60 ms, over the 4 ms budget a person's Mac is held to; reported, not gated on this runner"))
        #expect(judged.figures["full redraw 200x60 cpu reference p50 ms"] == 0.84)
    }

    @Test func aRedrawThirtyPercentHeavierIsReportedNamedNotFailed() {
        let judged = Self.gate.judge(work: Self.work.map { $0 * 1.3 }, references: Self.references)
        #expect(judged.met, "\(judged.said)")
        #expect(judged.said.contains("full redraw 200x60 cpu p95 4.29 ms, over the 4 ms budget a person's Mac is held to; reported, not gated on this runner"))
        #expect(judged.said.contains("full redraw 200x60 cpu paired p50 4.33 and p95 10.21 in references (reported)"), "\(judged.said)")
    }

    @Test func aRunnerSlowedOnlyInMemoryIsReportedWithItsLoopSeenNormal() {
        let judged = Self.gate.judge(work: Self.work.map { $0 * 1.5 }, references: Self.references)
        #expect(judged.met && judged.said.contains("paired p50 5.00") && judged.said.contains("reference p50 0.42 ms"), "\(judged.said)")
    }

    @Test func aTransientSlowdownOfASampleAndItsReferenceCancels() {
        var work = Self.work, references = Self.references
        for index in stride(from: 0, to: 400, by: 7) {
            work[index] *= 3
            references[index] *= 3
        }
        let judged = Self.gate.judge(work: work, references: references)
        #expect(judged.met && abs((judged.figures["full redraw 200x60 cpu paired p50 in references"] ?? 0) - 1.4 / 0.42) < 1e-9, "\(judged.said)")
    }

    @Test func overTheCeilingFailsOnAnyRunner() {
        let judged = Self.gate.judge(work: Self.work.map { $0 * 6 }, references: Self.references.map { $0 * 6 })
        #expect(!judged.met && judged.said.hasPrefix("missed: full redraw 200x60 cpu p95 19.80 ms, over its 8 ms hard ceiling"), "\(judged.said)")
    }

    @Test func aHealthyRedrawIsWithinTheBudgetItIsReportedAgainst() {
        let judged = Self.gate.judge(work: Self.work, references: Self.references)
        #expect(judged.met && judged.said.contains("full redraw 200x60 cpu p95 3.30 ms, within the 4 ms budget a person's Mac is held to (reported)"), "\(judged.said)")
    }

    @Test func aMissingFigureFails() {
        #expect(!Self.gate.judge(work: [], references: []).met)
        #expect(!Self.gate.judge(work: [1, 2], references: [0.4]).met)
        let zero = Self.gate.judge(work: [1, 2], references: [0.4, 0])
        #expect(!zero.met && zero.said == "missed: full redraw 200x60 cpu: a sample without its reference, or none")
    }
}

#if canImport(Darwin)
/// The Mach and libproc reads a look is made of, on the machine itself.
@Suite struct MachTests {
    /// A process's CPU read by its pid is in seconds, as this process's own `getrusage` says:
    /// `proc_pid_rusage` answers in Mach time units, which are not nanoseconds on Apple silicon.
    @Test func aProcessesCPUByPidIsInSeconds() throws {
        let until = ProcessInfo.processInfo.systemUptime + 0.2
        var spin = 0
        while ProcessInfo.processInfo.systemUptime < until { spin &+= 1 }
        let byPid = try #require(Mach.cpu(ofProcess: getpid()))
        let own = Mach.ourCPU(others: [])
        #expect(spin != 0)
        #expect(own >= 0.15 && abs(byPid - own) < own * 0.2, "by pid \(byPid) s, by getrusage \(own) s")
    }

    /// A host's children are listed, and their CPU is added to ours.
    @Test func aChildIsListedAndItsCPUIsOurs() throws {
        let child = Process()
        child.executableURL = URL(fileURLWithPath: "/bin/sleep")
        child.arguments = ["5"]
        try child.run()
        defer { child.terminate() }
        let pid = child.processIdentifier
        let children = Mach.children(of: getpid())
        #expect(children.contains(pid), "the child \(pid) is not among \(children)")
        #expect(Mach.cpu(ofProcess: -1) == nil, "a pid that cannot be read adds nothing")
        let childBefore = try #require(Mach.cpu(ofProcess: pid), "the child's CPU could not be read")
        let before = Mach.ourCPU(others: [])
        let with = Mach.ourCPU(others: [pid])
        let after = Mach.ourCPU(others: [])
        let childAfter = try #require(Mach.cpu(ofProcess: pid))
        #expect(before + childBefore <= with + 1e-9 && with <= after + childAfter + 1e-9,
                "ours with the child \(with) s is not this process's (\(before)–\(after) s) plus the child's (\(childBefore)–\(childAfter) s)")
    }
}
#endif
