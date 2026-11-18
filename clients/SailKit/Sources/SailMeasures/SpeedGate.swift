import SailTerminal

/// A figure measured on a machine of unknown speed, each sample beside a fixed reference timed on
/// the same thread just before it, and judged on what the machine could do at that moment: the
/// p50 (``gatesMedian``) or p95 of each sample over its own reference, at most ``ratioLimit``, set
/// from the runs that measured it. Paired, a slowdown that takes a sample and its reference alike
/// cancels. ``budget`` is the figure for a person's Mac, reported; ``ceiling`` fails on any machine.
/// Until a ratio limit is set from data, the absolute budget gates and the ratio is reported.
public struct SpeedGate: Sendable {
    public static let budget = 4.0
    public static let ceiling = 8.0

    public let name: String
    public let ratioLimit: Double?
    public let gatesMedian: Bool

    public init(name: String, ratioLimit: Double?, gatesMedian: Bool) {
        self.name = name
        self.ratioLimit = ratioLimit
        self.gatesMedian = gatesMedian
    }

    /// `work` and the `references` timed beside it, sample by sample, in milliseconds: whether every
    /// gated figure was met, the verdicts (missed first) and what is reported, and the figures.
    public func judge(work: [Double], references: [Double]) -> (met: Bool, said: String, figures: [String: Double]) {
        guard !work.isEmpty, work.count == references.count, references.allSatisfy({ $0 > 0 }) else {
            return (false, "missed: \(name): a sample without its reference, or none", [:])
        }
        let absolute = Percentiles(milliseconds: work), reference = Percentiles(milliseconds: references)
        let paired = Percentiles(milliseconds: zip(work, references).map { $0 / $1 })
        let ratioName = "\(name) paired \(gatesMedian ? "p50" : "p95") in references", ratio = gatesMedian ? paired.p50 : paired.p95
        var gated: [(met: Bool, said: String)] = []
        var reported: [String] = []
        if let ratioLimit {
            gated.append(Verdict.of(ratioName, value: ratio, limit: ratioLimit, unit: ""))
            reported.append(String(format: "%@ p95 %.2f ms against the %g ms budget for a person's Mac (reported)", name, absolute.p95, Self.budget))
        } else {
            gated.append(Verdict.of("\(name) p95", milliseconds: absolute.p95, limit: Self.budget))
            reported.append(String(format: "%@ %.2f (reported, its limit not yet set from data)", ratioName, ratio))
        }
        gated.append(Verdict.of("\(name) p95", milliseconds: absolute.p95, limit: Self.ceiling, bound: "hard ceiling"))
        reported.append(String(format: "reference p50 %.2f ms", reference.p50))
        let figures = [
            "\(name) p50 ms": absolute.p50, "\(name) p95 ms": absolute.p95, "\(name) samples": Double(work.count),
            "\(name) paired p50 in references": paired.p50, "\(name) paired p95 in references": paired.p95,
            "\(name) reference p50 ms": reference.p50,
        ]
        let said = (gated.filter { !$0.met } + gated.filter(\.met)).map(\.said) + reported
        return (gated.allSatisfy(\.met), said.joined(separator: "; "), figures)
    }
}
