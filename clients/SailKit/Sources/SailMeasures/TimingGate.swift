import Foundation
import SailSurface
import SailTerminal

/// The machine's CPUs as read once over a drive step's whole measuring span: the union of its
/// tries' spans. A span of a few tens of milliseconds cannot be judged from the machine's 10 ms
/// ticks, so a reading of fewer than ``leastTicks`` judges nothing; nor does one whose spans the
/// sampler's window did not hold whole, or one without our processes' share. Starved is what the
/// other processes left us, the idle ticks and our own, under ``starvedBelow`` percent: the app's
/// own work, however much, never excuses itself.
public enum MachineReading: Sendable, Equatable {
    /// Over `ticks`, the share idle and the share our processes used, in percent of all of them.
    case read(idle: Double, ours: Double, ticks: Double)
    case unjudged(String)

    /// One CPU-second of ticks (a third of a second on a three-CPU runner): one tick in it is 1%.
    public static let leastTicks = 100.0
    /// Short spans on hosted runners read either no idle tick at all or 17% and more; the first
    /// is the starved machine, and under 5% leaves room for a stray tick in a long reading.
    public static let starvedBelow = 5.0

    /// `idle` read over the step's spans; `whole` false when the sampler's window began after the
    /// step's first try.
    public init(_ idle: MachineIdle?, whole: Bool = true) {
        let ticks = (idle?.idleTicks ?? 0) + (idle?.busyTicks ?? 0)
        guard whole else {
            self = .unjudged("incomplete (the sampler's window began after the step's first try)")
            return
        }
        guard let idle, ticks >= Self.leastTicks else {
            self = .unjudged(String(format: "too short (%.0f ticks across the step's tries, %.0f wanted)", ticks, Self.leastTicks))
            return
        }
        guard let ours = idle.ourTicks else {
            self = .unjudged("without our processes' share")
            return
        }
        self = .read(idle: idle.idleTicks / ticks * 100, ours: min(ours, idle.busyTicks) / ticks * 100, ticks: ticks)
    }

    public var starved: Bool {
        if case let .read(idle, ours, _) = self { idle + ours < Self.starvedBelow } else { false }
    }

    /// The reading as the step's line says it.
    public var said: String {
        switch self {
        case let .read(idle, ours, ticks):
            String(format: "the machine's CPUs %.1f%% idle over %.0f ticks across the step's tries, our processes %.1f%% of them, others leaving us %.1f%%%@",
                   idle, ticks, ours, idle + ours, starved ? ": starved" : "")
        case let .unjudged(why):
            "the machine's reading \(why), judged at the ceilings"
        }
    }
}

/// A drive step's timing gate over its tries: their statistic (the least, or the p95) at the
/// ceiling, the budget said, never gated; with the machine read starved over the step, at twice
/// the ceiling, so a gross regression still fails, and said not measured cleanly. A single long
/// wait is not a try: it is judged by ``MachineShare``.
public struct TimingGate: Sendable, Equatable {
    public enum Statistic: Sendable, Equatable {
        case least
        case p95
    }

    public struct Verdict: Sendable, Equatable {
        public let name: String
        public let met: Bool
        public let value: Double
        public let said: String
        /// What missed, nil when met.
        public let missed: String?
    }

    public let name: String
    public let statistic: Statistic
    /// How many tries the step takes.
    public let tries: Int
    public let budget: Double
    /// A tighter mark said beside the figure and never gated: the ceiling it once had.
    public let mark: Double?
    public let ceiling: Double

    public init(name: String, statistic: Statistic, tries: Int, budget: Double, mark: Double? = nil, ceiling: Double) {
        self.name = name
        self.statistic = statistic
        self.tries = tries
        self.budget = budget
        self.mark = mark
        self.ceiling = ceiling
    }

    /// The verdict on `values`, in milliseconds, with the machine as `machine` read it.
    public func judge(_ values: [Double], machine: MachineReading) -> Verdict {
        let value = switch statistic {
        case .least: values.min() ?? .infinity
        case .p95: values.isEmpty ? .infinity : Percentiles(milliseconds: values).p95
        }
        let limit = machine.starved ? 2 * ceiling : ceiling, met = value <= limit
        var said = "\(name) \(Self.ms(value)) ms (budget \(Self.whole(budget)), ceiling \(Self.whole(ceiling)))"
        if machine.starved { said += "; not measured cleanly: machine starved, judged at twice its ceiling (\(Self.whole(limit)) ms)" }
        let missed = met ? nil
            : machine.starved ? "\(name) \(Self.ms(value)) ms over twice its ceiling (\(Self.whole(limit)) ms), not measured cleanly: machine starved"
            : "\(name) \(Self.ms(value)) ms over its \(Self.whole(ceiling)) ms ceiling"
        return Verdict(name: name, met: met, value: value, said: said, missed: missed)
    }

    private static func ms(_ value: Double) -> String {
        String(format: "%.1f", value)
    }

    private static func whole(_ value: Double) -> String {
        String(format: "%g", value)
    }
}

/// The machine's CPU ticks over one or more spans, each between its first and last looks that
/// read them, and the CPU our processes used over the same looks.
public struct MachineIdle: Sendable, Equatable {
    public let idleTicks: Double
    public let busyTicks: Double
    /// Our processes' CPU in ticks; nil where a span's looks did not read it.
    public let ourTicks: Double?
    public let from: Double
    public let to: Double

    /// The CPUs' idle share in percent; nil where the ticks did not move.
    public var percent: Double? {
        idleTicks + busyTicks > 0 ? idleTicks / (idleTicks + busyTicks) * 100 : nil
    }
}

extension MachineShare {
    /// Our processes' share of the machine's busy ticks between two looks, 0 to 1: the one way
    /// both a starved wait (``Starved``) and a step's reading take it. Nil when the busy ticks did
    /// not move or our processes' CPU was not read at either look.
    public static func ourShare(from first: ThreadLook, to last: ThreadLook) -> Double? {
        guard let ours = ourTicks(from: first, to: last), let from = first.busyTicks, let to = last.busyTicks, to &- from > 0 else { return nil }
        return min(1, ours / Double(to &- from))
    }

    /// Our processes' CPU between two looks, in the machine's ticks; nil where either did not read it.
    static func ourTicks(from first: ThreadLook, to last: ThreadLook) -> Double? {
        guard let from = first.ourCPU, let to = last.ourCPU else { return nil }
        return max(0, to - from) / ThreadLook.tick
    }

    /// How idle the machine's CPUs were over `spans`, overlapping ones taken once: each span's
    /// ticks between its first and last looks that read them (a sampler reads them only on a
    /// runnable look), and our processes' CPU between the same looks, summed; nil when no span
    /// was read twice.
    public static func idle(in spans: [Span], looks: [ThreadLook]) -> MachineIdle? {
        var merged: [Span] = []
        for span in spans.sorted(by: { $0.start < $1.start }) {
            if let last = merged.last, span.start <= last.end {
                merged[merged.count - 1].end = max(last.end, span.end)
            } else {
                merged.append(span)
            }
        }
        let read = looks.filter { $0.idleTicks != nil && $0.busyTicks != nil }
        let parts = merged.compactMap { span -> MachineIdle? in
            let inside = read.filter { $0.time >= span.start && $0.time <= span.end }
            guard inside.count > 1, let first = inside.first, let last = inside.last else { return nil }
            return MachineIdle(
                idleTicks: Double((last.idleTicks ?? 0) &- (first.idleTicks ?? 0)), busyTicks: Double((last.busyTicks ?? 0) &- (first.busyTicks ?? 0)),
                ourTicks: ourTicks(from: first, to: last), from: first.time, to: last.time
            )
        }
        guard let first = parts.first, let last = parts.last else { return nil }
        let ours = parts.allSatisfy { $0.ourTicks != nil } ? parts.compactMap(\.ourTicks).reduce(0, +) : nil
        return MachineIdle(
            idleTicks: parts.map(\.idleTicks).reduce(0, +), busyTicks: parts.map(\.busyTicks).reduce(0, +), ourTicks: ours, from: first.from, to: last.to
        )
    }
}
