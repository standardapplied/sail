import Foundation

/// What a probe run does: the session it opens, then its steps in order. Each step is an object of
/// one key:
///
/// ```json
/// {"size": [80, 24], "command": ["cat"], "steps": [
///   {"type": "echo hi\r"}, {"wait": "hi", "within": 5}, {"resize": [100, 30]},
///   {"snapshot": "before"}, {"drop": true}, {"phase": "attached"}, {"same": "before"},
///   {"detach": true}, {"attach": true}, {"killHost": true}, {"phase": "ended"}, {"measure": 200}
/// ]}
/// ```
struct Script: Decodable, Equatable {
    var size: [Int]
    var command: [String]
    var steps: [Step]

    init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: Keys.self)
        size = try fields.decodeIfPresent([Int].self, forKey: .size) ?? [80, 24]
        command = try fields.decodeIfPresent([String].self, forKey: .command) ?? []
        steps = try fields.decode([Step].self, forKey: .steps)
        guard size.count == 2, size.allSatisfy({ (1 ... 65535).contains($0) }) else {
            throw DecodingError.dataCorruptedError(forKey: .size, in: fields, debugDescription: "size is [columns, rows]")
        }
    }

    static func read(_ path: String) throws -> Script {
        try JSONDecoder().decode(Script.self, from: Data(contentsOf: URL(filePath: path)))
    }

    private enum Keys: String, CodingKey {
        case size, command, steps
    }
}

enum Step: Decodable, Equatable, CustomStringConvertible {
    /// Typed as keys, a character at a time.
    case type(String)
    /// Waits until the screen's text matches the pattern, within the seconds given (default 10).
    case wait(pattern: String, within: Int)
    case resize(columns: Int, rows: Int)
    /// Keeps the screen's text under a name, for ``same(_:)``: taken while the program is at rest,
    /// or a replay shows what it wrote since.
    case snapshot(String)
    /// A replay finished since the last drop or attach (the first attach's, with neither) shows
    /// the text kept under the name, within 5 s: the screen before the replay arrives, or partway
    /// through it, proves nothing.
    case same(String)
    /// Drops the connection; the session reconnects.
    case drop
    case detach
    case attach
    /// Kills the local host the probe owns, as a crash would, and starts another.
    case killHost
    /// Waits for the session to reach a phase: attached, detached, parked or ended, optionally
    /// ended for a reason containing the text after `ended:`.
    case phase(String, within: Int)
    /// Types this many keys, each after the last one's echo reached the screen.
    case measure(Int)

    init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: Keys.self)
        guard fields.allKeys.count == (fields.contains(.within) ? 2 : 1), let key = fields.allKeys.first(where: { $0 != .within }) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "a step is one of its keys"))
        }
        let within = try fields.decodeIfPresent(Int.self, forKey: .within) ?? 10
        switch key {
        case .type: self = .type(try fields.decode(String.self, forKey: key))
        case .wait: self = .wait(pattern: try fields.decode(String.self, forKey: key), within: within)
        case .resize:
            let size = try fields.decode([Int].self, forKey: key)
            guard size.count == 2 else { throw DecodingError.dataCorruptedError(forKey: key, in: fields, debugDescription: "resize is [columns, rows]") }
            self = .resize(columns: size[0], rows: size[1])
        case .snapshot: self = .snapshot(try fields.decode(String.self, forKey: key))
        case .same: self = .same(try fields.decode(String.self, forKey: key))
        case .drop: self = .drop
        case .detach: self = .detach
        case .attach: self = .attach
        case .killHost: self = .killHost
        case .phase: self = .phase(try fields.decode(String.self, forKey: key), within: within)
        case .measure: self = .measure(try fields.decode(Int.self, forKey: key))
        case .within: throw DecodingError.dataCorruptedError(forKey: key, in: fields, debugDescription: "within goes with a step")
        }
    }

    var description: String {
        switch self {
        case let .type(text): "type \(text.count) characters"
        case let .wait(pattern, within): "wait for /\(pattern)/ within \(within) s"
        case let .resize(columns, rows): "resize to \(columns)x\(rows)"
        case let .snapshot(name): "snapshot \(name)"
        case let .same(name): "same as \(name)"
        case .drop: "drop the connection"
        case .detach: "detach"
        case .attach: "attach"
        case .killHost: "kill the host as a crash would and start another"
        case let .phase(phase, within): "phase \(phase) within \(within) s"
        case let .measure(keys): "measure \(keys) echoed keys"
        }
    }

    private enum Keys: String, CodingKey {
        case type, wait, within, resize, snapshot, same, drop, detach, attach, killHost, phase, measure
    }
}
