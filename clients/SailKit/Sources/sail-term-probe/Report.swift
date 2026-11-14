import Foundation
import SailTerminal

/// What a probe run leaves as evidence: each step and how it went, the latency numbers and the
/// budgets they are held to. Never a byte of the terminal, a ticket or a token.
struct Report: Encodable {
    var steps: [StepResult]
    var latency: LatencyReport?
    var droppedInputs: Int?
    var budgets: [Budget]

    var passed: Bool {
        steps.allSatisfy(\.ok) && budgets.allSatisfy(\.met)
    }

    private enum Keys: String, CodingKey {
        case passed, steps, latency, droppedInputs, budgets
    }

    func encode(to encoder: any Encoder) throws {
        var fields = encoder.container(keyedBy: Keys.self)
        try fields.encode(passed, forKey: .passed)
        try fields.encode(steps, forKey: .steps)
        try fields.encodeIfPresent(latency, forKey: .latency)
        try fields.encodeIfPresent(droppedInputs, forKey: .droppedInputs)
        try fields.encode(budgets, forKey: .budgets)
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

/// One of T10's budgets and what was measured against it.
struct Budget: Encodable {
    var name: String
    var milliseconds: Double
    var limit: Double

    var met: Bool {
        milliseconds <= limit
    }

    private enum Keys: String, CodingKey {
        case name, milliseconds, limit, met
    }

    func encode(to encoder: any Encoder) throws {
        var fields = encoder.container(keyedBy: Keys.self)
        try fields.encode(name, forKey: .name)
        try fields.encode(milliseconds, forKey: .milliseconds)
        try fields.encode(limit, forKey: .limit)
        try fields.encode(met, forKey: .met)
    }
}
