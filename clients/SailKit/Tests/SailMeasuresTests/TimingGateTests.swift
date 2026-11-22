@testable import SailMeasures
import SailSurface
import Testing

/// The drive's rule for a step's figures over tries on a hosted runner: the machine read once over
/// the step's whole measuring span; starved, every gate of the step judged at twice its ceiling and
/// said not measured cleanly; a reading too short to mean anything judges nothing.
@Suite struct TimingGateTests {
    static let load = TimingGate(name: "loading it, the least of three", statistic: .least, tries: 3, budget: 16, ceiling: 32)
    static let quiet = MachineReading.read(idle: 40, ticks: 300)
    static let starved = MachineReading.read(idle: 2, ticks: 300)

    @Test func aGateJudgesItsStatisticAtItsCeilingOnAMachineNotStarved() {
        let verdict = Self.load.judge([30, 12, 20], machine: Self.quiet)
        #expect(verdict.met && verdict.value == 12 && verdict.said == "loading it, the least of three 12.0 ms (budget 16, ceiling 32)", "\(verdict)")
        let over = Self.load.judge([33, 40, 35], machine: Self.quiet)
        #expect(!over.met && over.missed == "loading it, the least of three 33.0 ms over its 32 ms ceiling")
        let p95 = TimingGate(name: "⌃⌘S p95", statistic: .p95, tries: 10, budget: 50, ceiling: 120)
        #expect(p95.judge([10, 11, 12, 13, 14, 15, 16, 17, 18, 130], machine: Self.quiet).value == 130)
        #expect(!Self.load.judge([], machine: Self.quiet).met, "no try met its gate")
    }

    @Test func onAStarvedMachineEveryGateIsJudgedAtTwiceItsCeilingAndSaidSo() {
        let verdict = Self.load.judge([40, 50, 45], machine: Self.starved)
        #expect(verdict.met && verdict.value == 40)
        #expect(verdict.said == "loading it, the least of three 40.0 ms (budget 16, ceiling 32); not measured cleanly: machine starved, judged at twice its ceiling (64 ms)")
        let gross = Self.load.judge([1200, 1190, 1210], machine: Self.starved)
        #expect(!gross.met && gross.missed == "loading it, the least of three 1190.0 ms over twice its ceiling (64 ms), not measured cleanly: machine starved")
    }

    /// Starved is under 5% idle from at least 100 ticks; fewer ticks is too short, judged as if
    /// the machine were not starved.
    @Test func aReadingIsStarvedOnlyWhenLongEnoughAndNearlyNeverIdle() {
        #expect(MachineReading(MachineIdle(idleTicks: 4, busyTicks: 96, from: 0, to: 1)).starved)
        #expect(!MachineReading(MachineIdle(idleTicks: 5, busyTicks: 95, from: 0, to: 1)).starved)
        #expect(MachineReading(MachineIdle(idleTicks: 0, busyTicks: 99, from: 0, to: 1)) == .tooShort(ticks: 99))
        #expect(MachineReading(nil) == .tooShort(ticks: 0))
        #expect(MachineReading(MachineIdle(idleTicks: 0, busyTicks: 100, from: 0, to: 1)).said == "the machine's CPUs 0.0% idle over 100 ticks across the step's tries: starved")
        #expect(MachineReading.tooShort(ticks: 42).said == "the machine's reading too short (42 ticks across the step's tries, 100 wanted), judged at the ceilings")
        let tooShort = Self.load.judge([33, 34, 35], machine: .tooShort(ticks: 42))
        #expect(!tooShort.met && tooShort.missed == "loading it, the least of three 33.0 ms over its 32 ms ceiling")
    }

    static func look(_ time: Double, idle: UInt32?, busy: UInt32?) -> ThreadLook {
        ThreadLook(time: time, runnable: idle != nil, cpu: 0, ourCPU: nil, idleTicks: idle, busyTicks: busy)
    }

    /// The machine's idle share over a step's spans: each span's ticks between its first and last
    /// looks that read them, summed, overlapping spans taken once, a span read fewer than twice
    /// adding nothing.
    @Test func theMachinesIdleShareIsReadOverTheUnionOfTheSpans() throws {
        let looks = [Self.look(0.9, idle: 0, busy: 0), Self.look(1.0, idle: 100, busy: 50), Self.look(1.01, idle: nil, busy: nil),
                     Self.look(1.05, idle: 101, busy: 53), Self.look(1.1, idle: 101, busy: 56), Self.look(2.0, idle: 200, busy: 100),
                     Self.look(2.1, idle: 203, busy: 110), Self.look(3.0, idle: 300, busy: 300)]
        let one = try #require(MachineShare.idle(in: [Span(1.0, 1.1)], looks: looks))
        #expect(one.idleTicks == 1 && one.busyTicks == 6 && one.from == 1.0 && one.to == 1.1 && abs((one.percent ?? 0) - 100.0 / 7) < 1e-9, "\(one)")
        let union = try #require(MachineShare.idle(in: [Span(1.05, 1.1), Span(1.0, 1.1), Span(1.0, 1.06), Span(2.0, 2.1), Span(2.95, 3.05)], looks: looks))
        #expect(union.idleTicks == 4 && union.busyTicks == 16 && union.from == 1.0 && union.to == 2.1, "overlapping spans counted twice, or a span read once added: \(union)")
        #expect(MachineShare.idle(in: [Span(1.06, 1.2), Span(2.95, 3.05)], looks: looks) == nil, "spans read once made a share")
        let still = try #require(MachineShare.idle(in: [Span(1.09, 1.11)], looks: looks + [Self.look(1.11, idle: 101, busy: 56)]))
        #expect(still.percent == nil, "ticks that did not move made a share")
    }
}
