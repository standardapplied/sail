import SailTerminal

/// A figure measured on a CI runner of unknown speed, each sample beside a fixed reference timed on
/// the same thread just before it. Only ``ceiling`` gates: on these runners no reference tracked the
/// figure closely enough to judge it against the machine's speed (spec
/// `sail-app-renderer-bench-gates-on-the-machines-speed`). The ``budget`` a person's Mac is held to,
/// and each sample over its own reference, are reported every run: they are the trend.
public struct SpeedGate: Sendable {
    public static let budget = 4.0
    public static let ceiling = 8.0

    public let name: String

    public init(name: String) {
        self.name = name
    }

    /// `work` and the `references` timed beside it, sample by sample, in milliseconds: whether the
    /// ceiling was met, the verdict and what is reported, and the figures.
    public func judge(work: [Double], references: [Double]) -> (met: Bool, said: String, figures: [String: Double]) {
        guard !work.isEmpty, work.count == references.count, references.allSatisfy({ $0 > 0 }) else {
            return (false, "missed: \(name): a sample without its reference, or none", [:])
        }
        let absolute = Percentiles(milliseconds: work), reference = Percentiles(milliseconds: references)
        let paired = Percentiles(milliseconds: zip(work, references).map { $0 / $1 })
        let ceiling = Verdict.of("\(name) p95", milliseconds: absolute.p95, limit: Self.ceiling, bound: "hard ceiling")
        let budget = absolute.p95 <= Self.budget
            ? String(format: "%@ p95 %.2f ms, within the %g ms budget a person's Mac is held to (reported)", name, absolute.p95, Self.budget)
            : String(format: "%@ p95 %.2f ms, over the %g ms budget a person's Mac is held to; reported, not gated on this runner: see the spec "
                + "sail-app-renderer-bench-gates-on-the-machines-speed", name, absolute.p95, Self.budget)
        let said = [
            ceiling.said, budget, String(format: "%@ paired p50 %.2f and p95 %.2f in references (reported)", name, paired.p50, paired.p95),
            String(format: "reference p50 %.2f ms", reference.p50),
        ]
        let figures = [
            "\(name) p50 ms": absolute.p50, "\(name) p95 ms": absolute.p95, "\(name) samples": Double(work.count),
            "\(name) paired p50 in references": paired.p50, "\(name) paired p95 in references": paired.p95,
            "\(name) reference p50 ms": reference.p50,
        ]
        return (ceiling.met, said.joined(separator: "; "), figures)
    }
}
