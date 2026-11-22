@testable import SailMeasures
import SailSurface
import Testing

/// The drive's rule for a step's figures over tries on a hosted runner: the machine read once over
/// the step's whole measuring span; starved, every gate of the step judged at twice its ceiling and
/// said not measured cleanly; a reading too short to mean anything judges nothing.
@Suite struct TimingGateTests {
    static let load = TimingGate(name: "loading it, the least of three", statistic: .least, tries: 3, budget: 16, ceiling: 32)
    static let quiet = MachineReading.read(idle: 40, ours: 20, ticks: 300)
    static let starved = MachineReading.read(idle: 2, ours: 1, ticks: 300)

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

    static func idle(_ idle: Double, busy: Double, ours: Double?) -> MachineIdle {
        MachineIdle(idleTicks: idle, busyTicks: busy, ourTicks: ours, from: 0, to: 1)
    }

    /// M1: starved is what the other processes left us, the idle ticks and our own, under 5% of at
    /// least 100 ticks; our own work, however much of the machine it takes, never reads starved.
    @Test func aReadingIsStarvedOnlyWhenOthersLeaveUsNearlyNothing() {
        #expect(!MachineReading(Self.idle(0, busy: 100, ours: 95)).starved, "our own work read the machine starved")
        #expect(MachineReading(Self.idle(0, busy: 100, ours: 4)).starved, "others taking 96% did not read starved")
        #expect(MachineReading(Self.idle(2, busy: 98, ours: 2)).starved)
        #expect(!MachineReading(Self.idle(2, busy: 98, ours: 3)).starved)
        #expect(!MachineReading(Self.idle(5, busy: 95, ours: 0)).starved)
        #expect(MachineReading(Self.idle(0, busy: 100, ours: 400)) == .read(idle: 0, ours: 100, ticks: 100), "our share was not bound by the busy ticks")
        #expect(MachineReading(Self.idle(0, busy: 100, ours: 3)).said
            == "the machine's CPUs 0.0% idle over 100 ticks across the step's tries, our processes 3.0% of them, others leaving us 3.0%: starved")
    }

    /// A reading too short, cut by the sampler's window, or without our share judges nothing: the
    /// step is judged at its ceilings, and says why.
    @Test func aReadingThatCannotBeJudgedSaysWhy() {
        #expect(MachineReading(Self.idle(0, busy: 99, ours: 0)) == .unjudged("too short (99 ticks across the step's tries, 100 wanted)"))
        #expect(MachineReading(nil) == .unjudged("too short (0 ticks across the step's tries, 100 wanted)"))
        #expect(MachineReading(Self.idle(0, busy: 300, ours: 0), whole: false) == .unjudged("incomplete (the sampler's window began after the step's first try)"))
        #expect(MachineReading(Self.idle(0, busy: 300, ours: nil)) == .unjudged("without our processes' share"))
        #expect(MachineReading.unjudged("too short (42 ticks across the step's tries, 100 wanted)").said
            == "the machine's reading too short (42 ticks across the step's tries, 100 wanted), judged at the ceilings")
        let tooShort = Self.load.judge([33, 34, 35], machine: MachineReading(nil))
        #expect(!tooShort.met && tooShort.missed == "loading it, the least of three 33.0 ms over its 32 ms ceiling")
    }

    static func look(_ time: Double, idle: UInt32?, busy: UInt32?, ours: Double? = nil) -> ThreadLook {
        ThreadLook(time: time, runnable: idle != nil, cpu: 0, ourCPU: ours ?? idle.map { _ in time }, idleTicks: idle, busyTicks: busy)
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
        #expect(abs((union.ourTicks ?? 0) - 20) < 1e-6, "our processes' CPU over the spans: \(union)")
        let contained = try #require(MachineShare.idle(in: [Span(1.0, 2.1), Span(1.05, 1.1)], looks: looks))
        #expect(contained.idleTicks == 103 && contained.busyTicks == 60 && contained.to == 2.1, "a span inside an earlier one shortened it: \(contained)")
        let unread = looks.map { ThreadLook(time: $0.time, runnable: $0.runnable, cpu: 0, ourCPU: nil, idleTicks: $0.idleTicks, busyTicks: $0.busyTicks) }
        #expect(MachineShare.idle(in: [Span(1.0, 1.1)], looks: unread)?.ourTicks == nil, "an unread share was taken as none of ours")
        #expect(MachineShare.idle(in: [Span(1.06, 1.2), Span(2.95, 3.05)], looks: looks) == nil, "spans read once made a share")
        let still = try #require(MachineShare.idle(in: [Span(1.09, 1.11)], looks: looks + [Self.look(1.11, idle: 101, busy: 56)]))
        #expect(still.percent == nil, "ticks that did not move made a share")
    }
}
