@testable import SailMeasures
import SailSurface
import Testing

/// The drive's one rule for a figure taken over tries on a hosted runner: a try the machine
/// starved does not count, more are taken up to twice the count, the first that count are judged
/// at the ceiling, and with none counting all are judged at twice it and said so.
@Suite struct TimingGateTests {
    static let load = TimingGate(name: "loading it, the least of three", statistic: .least, wanted: 3, budget: 8, ceiling: 16)

    static func clean(_ values: Double...) -> [TimedTry] {
        values.map { TimedTry(value: $0, idle: 40) }
    }

    static func starved(_ values: Double...) -> [TimedTry] {
        values.map { TimedTry(value: $0, idle: 0) }
    }

    @Test func aStarvedTryDoesNotCount() {
        let verdict = Self.load.judge(Self.starved(40) + Self.clean(9, 10, 11))
        #expect(verdict.met && verdict.value == 9 && verdict.clean, "\(verdict)")
        #expect(verdict.said == "loading it, the least of three 9.0 ms (budget 8, ceiling 16); 1 of 4 tries starved (the machine's CPUs 0% idle), not counted")
    }

    @Test func theFirstThatCountAreJudgedAndNoMore() {
        let verdict = Self.load.judge(Self.clean(12, 13) + Self.starved(30) + Self.clean(14, 5))
        #expect(verdict.value == 12, "a try past the first three that count was judged: \(verdict)")
    }

    @Test func moreTriesAreWantedUntilEnoughCountOrTwiceTheCount() {
        #expect(Self.load.wantsMore([]))
        #expect(Self.load.wantsMore(Self.clean(9, 10)))
        #expect(!Self.load.wantsMore(Self.clean(9, 10, 11)))
        #expect(Self.load.wantsMore(Self.clean(9, 10) + Self.starved(30)))
        #expect(!Self.load.wantsMore(Self.clean(9, 10) + Self.starved(30) + Self.clean(11)))
        #expect(Self.load.wantsMore(Self.starved(30, 31, 32, 33, 34)))
        #expect(!Self.load.wantsMore(Self.starved(30, 31, 32, 33, 34, 35)), "more than six tries were wanted")
    }

    @Test func withNoTryCountingAllAreJudgedAtTwiceTheCeilingAndSaidSo() {
        let starved = Self.load.judge(Self.starved(31, 30, 40, 35, 33, 32))
        #expect(starved.met && starved.value == 30 && !starved.clean, "\(starved)")
        #expect(starved.said == "loading it, the least of three 30.0 ms (budget 8, ceiling 16); not measured cleanly: machine starved in 6 of 6 tries, judged at twice its ceiling (32 ms)")
        let gross = Self.load.judge(Self.starved(1200, 1210, 1190, 1205, 1201, 1199))
        #expect(!gross.met && gross.missed == "loading it, the least of three 1190.0 ms over twice its ceiling (32 ms), not measured cleanly", "\(gross)")
    }

    @Test func someCountingAfterTheMostAreJudgedAtTheCeiling() {
        let verdict = Self.load.judge(Self.starved(10, 11) + Self.clean(17) + Self.starved(12, 13) + Self.clean(18))
        #expect(!verdict.met && verdict.value == 17 && verdict.clean, "\(verdict)")
        #expect(verdict.missed == "loading it, the least of three 17.0 ms over its 16 ms ceiling")
    }

    @Test func aSpanReadFewerThanTwiceCountsAndSaysSo() {
        let verdict = Self.load.judge(Self.clean(9, 10) + [TimedTry(value: 11, idle: nil)])
        #expect(verdict.met && verdict.value == 9 && verdict.clean)
        #expect(verdict.said == "loading it, the least of three 9.0 ms (budget 8, ceiling 16); the machine's ticks read fewer than twice in 1 of 3 tries, counted")
    }

    @Test func aP95GateTakesItsOwnCountAndTwiceIt() {
        let gate = TimingGate(name: "⌃⌘S to the first rows drawn p95", statistic: .p95, wanted: 10, budget: 50, ceiling: 120)
        let tries = Self.clean(10, 11, 12, 13) + Self.starved(500, 600) + Self.clean(14, 15, 16, 17, 18, 19, 900)
        #expect(!gate.wantsMore(tries) && gate.wantsMore(Array(tries.prefix(11))))
        #expect(gate.judge(tries).value == 19, "the p95 was not of the first ten that count")
        #expect(gate.wantsMore(Array(repeating: TimedTry(value: 1, idle: 0), count: 19)) && !gate.wantsMore(Array(repeating: TimedTry(value: 1, idle: 0), count: 20)))
    }

    @Test func triesCutShortByTheDrivesCapAreSaidSo() {
        let verdict = Self.load.judge(Self.clean(9) + Self.starved(30, 31))
        #expect(verdict.met && verdict.value == 9)
        #expect(verdict.said == "loading it, the least of three 9.0 ms (budget 8, ceiling 16); 2 of 3 tries starved (the machine's CPUs 0% idle), not counted; "
            + "1 of 3 counting when the drive's extra tries were spent")
    }

    /// The first tries of a loop are always taken; another past them only while the drive's time
    /// for extra tries lasts and some gate still wants one.
    @Test func anotherTryIsTakenWithinTheDrivesCap() {
        let key = TimingGate(name: "a key", statistic: .least, wanted: 3, budget: 8, ceiling: 16)
        let short = ["loading it, the least of three": Self.clean(9, 10), "a key": Self.clean(8, 8)]
        #expect(TimingGate.another([Self.load, key], tries: short, taken: 2, extraLeft: 0))
        let starved = ["loading it, the least of three": Self.clean(9, 10) + Self.starved(30), "a key": Self.clean(8, 8, 8)]
        #expect(TimingGate.another([Self.load, key], tries: starved, taken: 3, extraLeft: 1))
        #expect(!TimingGate.another([Self.load, key], tries: starved, taken: 3, extraLeft: 0), "an extra try was taken with the drive's cap spent")
        let enough = ["loading it, the least of three": Self.clean(9, 10, 11), "a key": Self.clean(8, 8, 8)]
        #expect(!TimingGate.another([Self.load, key], tries: enough, taken: 3, extraLeft: 100))
    }

    static func look(_ time: Double, idle: UInt32?, busy: UInt32?) -> ThreadLook {
        ThreadLook(time: time, runnable: idle != nil, cpu: 0, ourCPU: nil, idleTicks: idle, busyTicks: busy)
    }

    /// The machine's idle share over a span, from its first and last looks that read the ticks:
    /// none with fewer than two, and no share where the ticks did not move.
    @Test func theMachinesIdleShareIsReadFromTheSpansTicks() throws {
        let looks = [Self.look(0.9, idle: 0, busy: 0), Self.look(1.0, idle: 100, busy: 50), Self.look(1.01, idle: nil, busy: nil),
                     Self.look(1.05, idle: 101, busy: 53), Self.look(1.1, idle: 101, busy: 56), Self.look(1.3, idle: 900, busy: 900)]
        let idle = try #require(MachineShare.idle(in: Span(1.0, 1.1), looks: looks))
        #expect(abs((idle.percent ?? 0) - 100.0 / 7) < 1e-9 && idle.idleTicks == 1 && idle.busyTicks == 6 && idle.from == 1.0 && idle.to == 1.1, "\(idle)")
        #expect(MachineShare.idle(in: Span(1.06, 1.2), looks: looks) == nil, "one reading made a share")
        let still = try #require(MachineShare.idle(in: Span(1.09, 1.11), looks: looks + [Self.look(1.11, idle: 101, busy: 56)]))
        #expect(still.percent == nil, "ticks that did not move made a share")
        let starved = try #require(MachineShare.idle(in: Span(1.05, 1.1), looks: looks))
        #expect(starved.percent == 0 && TimedTry(value: 1, idle: starved.percent).starved)
    }
}
