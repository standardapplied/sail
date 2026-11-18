import Foundation
import SailMeasures
import SailSurface
import SailTerminal

/// What a probe run leaves as evidence: each step and how it went, the latency numbers and the
/// budgets they are held to. Never a byte of the terminal, a ticket or a token.
struct Report: Encodable {
    var steps: [StepResult]
    var latency: LatencyReport?
    var droppedInputs: Int?
    var budgets: [Budget]
    /// What the bench measured, frame by frame, beside its budgets: none for a scripted run.
    var samples: [BenchSeries]? = nil

    var passed: Bool {
        steps.allSatisfy(\.ok) && budgets.allSatisfy(\.met)
    }

    private enum Keys: String, CodingKey {
        case passed, steps, latency, droppedInputs, budgets, samples
    }

    func encode(to encoder: any Encoder) throws {
        var fields = encoder.container(keyedBy: Keys.self)
        try fields.encode(passed, forKey: .passed)
        try fields.encode(steps, forKey: .steps)
        try fields.encodeIfPresent(latency, forKey: .latency)
        try fields.encodeIfPresent(droppedInputs, forKey: .droppedInputs)
        try fields.encode(budgets, forKey: .budgets)
        try fields.encodeIfPresent(samples, forKey: .samples)
    }

    func write(to path: String?) throws {
        guard let path else { return }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(self).write(to: URL(filePath: path))
    }
}

struct StepResult: Encodable {
    var step: String
    var ok: Bool
    var milliseconds: Double
    var detail: String?
}

/// One gated `frame()` series of the bench, each frame as it was measured, and the stalls of the
/// probe's own reference thread over the series: an over-limit run says where its time went.
struct BenchSeries: Encodable, Equatable {
    var name: String
    var frames: [FrameSample]
    var stalls: [Span]
    /// The looks at the frames' thread their excuses were proven from.
    var looks: [ThreadLook] = []
}

/// One `frame()` taken while another thread fed: when it began (seconds since the bench began),
/// how long it took, how long of that it waited for the lock, how many slices the feeder fed while
/// it waited, how much of its wait the reference thread was held over too, the CPU time its own
/// thread used in it, and how much of it that thread was proven starved.
struct FrameSample: Encodable, Equatable {
    var at: Double
    var milliseconds: Double
    var waitMilliseconds: Double
    var slicesWaited: Int
    var heldMilliseconds: Double
    var cpuMilliseconds: Double = 0
    var excusedMilliseconds: Double = 0
}

/// One of T10's budgets and what was measured against it.
struct Budget: Encodable {
    var name: String
    var milliseconds: Double
    var limit: Double

    var met: Bool {
        milliseconds <= limit
    }

    /// A number measured and reported, held to no limit.
    var reported: Bool {
        limit == .infinity
    }

    private enum Keys: String, CodingKey {
        case name, milliseconds, limit, met
    }

    func encode(to encoder: any Encoder) throws {
        var fields = encoder.container(keyedBy: Keys.self)
        try fields.encode(name, forKey: .name)
        try fields.encode(milliseconds, forKey: .milliseconds)
        try fields.encodeIfPresent(reported ? nil : limit, forKey: .limit)
        try fields.encode(met, forKey: .met)
    }
}
