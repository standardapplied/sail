import Foundation
import SailTerminal

/// Main's renderer and the branch's, drawn in the same job on the same runner, alternating warm in
/// rounds: the runner's speed cancels by construction. Each pair's branch p50 over main's p50, the
/// median of the pairs at most 1 + X, X from the noise of main against itself; one stated allowance
/// in the PR's body widens it, and it is said. The branch's p95 fails over one 120 Hz frame, and
/// the 4 ms a person's Mac is held to is reported, a warning when over. A push to main reports and
/// never gates: its PR's run was the gate, and a stated regression must not red main.
public enum SameRunner {
    public static let ceiling = 8.0
    public static let budget = 4.0
    public static let fewestPairs = 5
    public static let field = "Renderer-Bench-Allowance:"

    /// One round of each, main's first: each sample's CPU time in milliseconds.
    public struct Pair: Sendable, Equatable {
        public let main: [Double]
        public let branch: [Double]

        public init(main: [Double], branch: [Double]) {
            self.main = main
            self.branch = branch
        }
    }

    /// What a PR's body states, on a line of its own.
    public enum Allowance: Equatable, Sendable {
        case slower(percent: Double, reason: String)
        case noneCompared(reason: String)
        case malformed(line: String)
    }

    /// Whether the step passes, what it says (verdicts missed first), and what it warns of.
    public struct Outcome: Sendable {
        public let met: Bool
        public let lines: [String]
        public let warnings: [String]

        public init(met: Bool, lines: [String], warnings: [String]) {
            self.met = met
            self.lines = lines
            self.warnings = warnings
        }
    }

    /// The first `Renderer-Bench-Allowance:` line of `body`, nil when it holds none.
    public static func allowance(in body: String) -> Allowance? {
        guard let line = body.components(separatedBy: .newlines).map({ $0.trimmingCharacters(in: .whitespaces) })
            .first(where: { $0.hasPrefix(field) })
        else { return nil }
        let stated = line.dropFirst(field.count).trimmingCharacters(in: .whitespaces)
        if stated.hasPrefix("none compared,") {
            let reason = stated.dropFirst("none compared,".count).trimmingCharacters(in: .whitespaces)
            return reason.isEmpty ? .malformed(line: line) : .noneCompared(reason: reason)
        }
        guard let sign = stated.firstIndex(of: "%"), let percent = Double(stated[..<sign].trimmingCharacters(in: .whitespaces)), percent > 0 else {
            return .malformed(line: line)
        }
        let reason = stated[stated.index(after: sign)...].trimmingCharacters(in: .whitespaces)
        return reason.isEmpty ? .malformed(line: line) : .slower(percent: percent, reason: reason)
    }

    /// `pairs` judged, the first a warm-up left out, against `noise` (X, a fraction) and what the PR
    /// states; `gating` false for a push to main, which reports only. `base` is main's commit.
    public static func judge(_ pairs: [Pair], noise: Double, allowance: Allowance?, gating: Bool, base: String) -> Outcome {
        let taken = Array(pairs.dropFirst())
        guard taken.count >= fewestPairs, taken.allSatisfy({ !$0.main.isEmpty && !$0.branch.isEmpty }) else {
            return refused("the comparison took \(taken.count) pairs after its warm-up, fewer than \(fewestPairs)", gating: gating)
        }
        if case let .malformed(line) = allowance {
            return refused("the PR's line '\(line)' is neither `\(field) N% <reason>` nor `\(field) none compared, <reason>`", gating: gating)
        }
        let ratios = taken.map { Percentiles(milliseconds: $0.branch).p50 / max(Percentiles(milliseconds: $0.main).p50, 1e-9) }
        let ratio = Percentiles(milliseconds: ratios), main = Percentiles(milliseconds: taken.flatMap(\.main))
        let branch = Percentiles(milliseconds: taken.flatMap(\.branch))
        var limit = 1 + noise, stated: [String] = []
        if case let .slower(percent, reason) = allowance {
            limit = 1 + percent / 100
            stated.append(String(format: "the PR states an allowance of %g %% slower: %@", percent, reason))
        }
        let compared = String(format: "the branch's redraw at %.2f times main's (the median of %ld pairs, %.2f to %.2f)", ratio.p50, ratios.count,
                              ratios.min() ?? 0, ratios.max() ?? 0)
        let ratioMet = ratio.p50 <= limit
        let ratioSaid = ratioMet ? String(format: "within its budget: %@, limit %g", compared, limit) : String(format: "missed: %@, over its %g limit", compared, limit)
        let ceiling = Verdict.of("the branch's redraw p95", milliseconds: branch.p95, limit: Self.ceiling, bound: "hard ceiling")
        var warnings: [String] = []
        if branch.p95 > budget {
            warnings.append(String(format: "the branch's redraw p95 %.2f ms is over the %g ms budget a person's Mac is held to; reported, not gated on this runner", branch.p95, budget))
        }
        let figures = String(format: "main at %@: p50 %.2f ms, p95 %.2f ms; the branch: p50 %.2f ms, p95 %.2f ms; X %g %% from main against itself",
                             base, main.p50, main.p95, branch.p50, branch.p95, noise * 100)
        if case let .noneCompared(reason) = allowance {
            return Outcome(met: ceiling.met || !gating, lines: [ceiling.said, "compared, not gated: the PR states none compared: \(reason)", compared] + [figures], warnings: warnings)
        }
        guard gating else {
            let over = (ratioMet ? [] : [compared + String(format: ", over its %g limit; reported, not gated: a push to main is gated by its PR's run", limit)])
                + (ceiling.met ? [] : [ceiling.said + "; reported, not gated on main"])
            return Outcome(met: true, lines: ["reported, not gated (a push to main): " + compared, ceiling.said] + stated + [figures], warnings: over + warnings)
        }
        let verdicts = [(ratioMet, ratioSaid), (ceiling.met, ceiling.said)]
        let ordered = verdicts.filter { !$0.0 } + verdicts.filter(\.0)
        return Outcome(met: ratioMet && ceiling.met, lines: ordered.map(\.1) + stated + [figures], warnings: warnings)
    }

    /// Main's side could not be drawn (`why`): a PR fails unless it states none compared; a push to
    /// main reports it with a warning.
    public static func uncompared(_ why: String, allowance: Allowance?, gating: Bool) -> Outcome {
        if case let .noneCompared(reason) = allowance {
            return Outcome(met: true, lines: ["compared nothing: \(why); the PR states: \(reason)"], warnings: [])
        }
        return refused("main's renderer could not be compared: \(why); a PR goes on without it only by stating `\(field) none compared, <reason>` in its body", gating: gating)
    }

    private static func refused(_ why: String, gating: Bool) -> Outcome {
        gating ? Outcome(met: false, lines: ["missed: " + why], warnings: []) : Outcome(met: true, lines: ["reported, not gated: " + why], warnings: [why])
    }
}
