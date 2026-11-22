import Foundation
import SailSurface
import SailTerminal

/// One try of a figure: its value in milliseconds, and the machine's CPUs' idle share over the
/// try's span in percent, nil where the sampler read the machine's ticks fewer than twice in it.
public struct TimedTry: Sendable, Equatable {
    public let value: Double
    public let idle: Double?

    public init(value: Double, idle: Double?) {
        self.value = value
        self.idle = idle
    }

    /// The machine had no CPU idle over the try: the figure is the runner's, not ours.
    public var starved: Bool {
        idle == 0
    }
}

/// The drive's one rule for a figure taken over tries on a hosted runner. A try the machine
/// starved does not count; tries go on, up to twice ``wanted``, until ``wanted`` count; the first
/// ``wanted`` that count are judged by the ``statistic`` at the ``ceiling``. With some counting
/// and no more tries to take, those are judged at the ceiling; with none counting, every try is
/// judged at twice the ceiling, so a gross regression still fails, and the verdict says it was
/// not measured cleanly. The ``budget`` is said, never gated. A single long wait is not a try: it
/// is judged by ``MachineShare``.
public struct TimingGate: Sendable, Equatable {
    public enum Statistic: Sendable, Equatable {
        case least
        case p95
    }

    public struct Verdict: Sendable, Equatable {
        public let met: Bool
        public let value: Double
        /// Whether any try counted.
        public let clean: Bool
        public let said: String
        /// What missed, nil when met.
        public let missed: String?
    }

    public let name: String
    public let statistic: Statistic
    public let wanted: Int
    public let budget: Double
    public let ceiling: Double

    public init(name: String, statistic: Statistic, wanted: Int, budget: Double, ceiling: Double) {
        self.name = name
        self.statistic = statistic
        self.wanted = wanted
        self.budget = budget
        self.ceiling = ceiling
    }

    /// The most tries taken.
    public var most: Int {
        2 * wanted
    }

    /// Whether another try is wanted: fewer than ``wanted`` count, and fewer than ``most`` taken.
    public func wantsMore(_ tries: [TimedTry]) -> Bool {
        tries.count < most && tries.count(where: { !$0.starved }) < wanted
    }

    /// Whether a loop of `gates`, `taken` tries in, takes another: its first tries always, one past
    /// them only while some gate wants it and the drive's time for extra tries (`extraLeft`, in
    /// seconds) lasts.
    public static func another(_ gates: [TimingGate], tries: [String: [TimedTry]], taken: Int, extraLeft: Double) -> Bool {
        guard gates.contains(where: { $0.wantsMore(tries[$0.name] ?? []) }) else { return false }
        return taken < (gates.map(\.wanted).max() ?? 0) || extraLeft > 0
    }

    /// The verdict on `tries`; fewer than ``most`` taken and fewer than ``wanted`` counting, the
    /// tries were cut short, for the reason `stopped` names.
    public func judge(_ tries: [TimedTry], stopped: String = "the drive's extra tries were spent") -> Verdict {
        let counted = Array(tries.filter { !$0.starved }.prefix(wanted))
        let starved = tries.count(where: \.starved), unread = tries.count(where: { $0.idle == nil })
        let clean = !counted.isEmpty, limit = clean ? ceiling : 2 * ceiling
        let value = clean ? figure(counted.map(\.value)) : figure(tries.map(\.value))
        let met = !tries.isEmpty && value <= limit
        var said = "\(name) \(Self.ms(value)) ms (budget \(Self.whole(budget)), ceiling \(Self.whole(ceiling)))"
        if !clean {
            said += "; not measured cleanly: machine starved in \(starved) of \(tries.count) tries, judged at twice its ceiling (\(Self.whole(limit)) ms)"
        } else if starved > 0 {
            said += "; \(starved) of \(tries.count) tries starved (the machine's CPUs 0% idle), not counted"
        }
        if unread > 0 { said += "; the machine's ticks read fewer than twice in \(unread) of \(tries.count) tries, counted" }
        if clean, counted.count < wanted, tries.count < most {
            said += "; \(counted.count) of \(wanted) counting when \(stopped)"
        }
        let missed = met ? nil
            : clean ? "\(name) \(Self.ms(value)) ms over its \(Self.whole(ceiling)) ms ceiling"
            : "\(name) \(Self.ms(value)) ms over twice its ceiling (\(Self.whole(limit)) ms), not measured cleanly"
        return Verdict(met: met, value: value, clean: clean, said: said, missed: missed)
    }

    private func figure(_ values: [Double]) -> Double {
        switch statistic {
        case .least: values.min() ?? .infinity
        case .p95: values.isEmpty ? .infinity : Percentiles(milliseconds: values).p95
        }
    }

    private static func ms(_ value: Double) -> String {
        String(format: "%.1f", value)
    }

    private static func whole(_ value: Double) -> String {
        String(format: "%g", value)
    }
}

/// The machine's CPU ticks over a span, between its first and last looks that read them.
public struct MachineIdle: Sendable, Equatable {
    public let idleTicks: Double
    public let busyTicks: Double
    public let from: Double
    public let to: Double

    /// The CPUs' idle share in percent; nil where the ticks did not move.
    public var percent: Double? {
        idleTicks + busyTicks > 0 ? idleTicks / (idleTicks + busyTicks) * 100 : nil
    }
}

extension MachineShare {
    /// How idle the machine's CPUs were over `span`, from its first and last looks that read the
    /// ticks (a sampler reads them only on a runnable look); nil with fewer than two.
    public static func idle(in span: Span, looks: [ThreadLook]) -> MachineIdle? {
        let read = looks.filter { $0.time >= span.start && $0.time <= span.end && $0.idleTicks != nil && $0.busyTicks != nil }
        guard read.count > 1, let first = read.first, let last = read.last else { return nil }
        return MachineIdle(
            idleTicks: Double((last.idleTicks ?? 0) &- (first.idleTicks ?? 0)), busyTicks: Double((last.busyTicks ?? 0) &- (first.busyTicks ?? 0)),
            from: first.time, to: last.time
        )
    }
}
