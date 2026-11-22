import Foundation
import SailSurface
import SailTerminal

/// The machine's CPUs as read once over a drive step's whole measuring span: the union of its
/// tries' spans. A span of a few tens of milliseconds cannot be judged from the machine's 10 ms
/// ticks, so a reading of fewer than ``leastTicks`` is too short and judges nothing; one under
/// ``starvedBelow`` percent idle is the machine starved.
public enum MachineReading: Sendable, Equatable {
    case read(idle: Double, ticks: Double)
    case tooShort(ticks: Double)

    /// One CPU-second of ticks (a third of a second on a three-CPU runner): one idle tick in it is
    /// 1%.
    public static let leastTicks = 100.0
    /// Short spans on hosted runners read either no idle tick at all or 17% and more; the first
    /// is the starved machine, and under 5% leaves room for a stray tick in a long reading.
    public static let starvedBelow = 5.0

    public init(_ idle: MachineIdle?) {
        let ticks = (idle?.idleTicks ?? 0) + (idle?.busyTicks ?? 0)
        guard let idle, ticks >= Self.leastTicks, let percent = idle.percent else {
            self = .tooShort(ticks: ticks)
            return
        }
        self = .read(idle: percent, ticks: ticks)
    }

    public var starved: Bool {
        if case let .read(idle, _) = self { idle < Self.starvedBelow } else { false }
    }

    /// The reading as the step's line says it.
    public var said: String {
        switch self {
        case let .read(idle, ticks):
            String(format: "the machine's CPUs %.1f%% idle over %.0f ticks across the step's tries%@", idle, ticks, starved ? ": starved" : "")
        case let .tooShort(ticks):
            String(format: "the machine's reading too short (%.0f ticks across the step's tries, %.0f wanted), judged at the ceilings", ticks, Self.leastTicks)
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
    public let ceiling: Double

    public init(name: String, statistic: Statistic, tries: Int, budget: Double, ceiling: Double) {
        self.name = name
        self.statistic = statistic
        self.tries = tries
        self.budget = budget
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
/// read them.
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
    /// How idle the machine's CPUs were over `spans`, overlapping ones taken once: each span's
    /// ticks between its first and last looks that read them (a sampler reads them only on a
    /// runnable look), summed; nil when no span was read twice.
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
                from: first.time, to: last.time
            )
        }
        guard let first = parts.first, let last = parts.last else { return nil }
        return MachineIdle(idleTicks: parts.map(\.idleTicks).reduce(0, +), busyTicks: parts.map(\.busyTicks).reduce(0, +), from: first.from, to: last.to)
    }
}
