/// Everything a call to the server can end in other than its answer.
public enum SailError: Error, Equatable, Sendable {
    /// The server said no, in its own words.
    case refused(Refusal)
    /// The server answered with a shape this client cannot read.
    case undecodable(Undecodable)
    /// The server answered with a status and no refusal to explain it.
    case unexpected(route: String, status: Int)
    /// The server could not be reached, or the connection to it broke.
    case unreachable(String)
}

/// A refusal as the server words it, kept verbatim: what it is, what happened, and what to do.
/// `action` is the fix as a person reads it; `remedy` is the same fix as words and commands, sent
/// when it names a command, so a client can render each command for its own reader.
public struct Refusal: Equatable, Sendable, Decodable {
    public let code: Open<ErrorCode>
    public let message: String
    public let action: String?
    public let remedy: [RemedyPart]

    public init(code: Open<ErrorCode>, message: String, action: String?, remedy: [RemedyPart]) {
        self.code = code
        self.message = message
        self.action = action
        self.remedy = remedy
    }

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        code = try fields.decode(Open<ErrorCode>.self, forKey: .code)
        message = try fields.decode(String.self, forKey: .message)
        action = try fields.decodeIfPresent(String.self, forKey: .action)
        remedy = try fields.decodeIfPresent([RemedyPart].self, forKey: .remedy) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case code, message, action, remedy
    }
}

/// One part of a remedy: words, or a command to run.
public enum RemedyPart: Equatable, Sendable, Decodable {
    case words(String)
    case command(run: Open<Runner>, verb: String, args: [String])

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        if let text = try fields.decodeIfPresent(String.self, forKey: .text) {
            self = .words(text)
        } else {
            self = .command(
                run: try fields.decode(Open<Runner>.self, forKey: .run),
                verb: try fields.decode(String.self, forKey: .verb),
                args: try fields.decode([String].self, forKey: .args)
            )
        }
    }

    private enum CodingKeys: String, CodingKey {
        case text, run, verb, args
    }
}

/// Who runs a remedy's command: `sail` on the box that answered, `sail` on a thin client, or
/// another program.
public enum Runner: String, CaseIterable, Sendable {
    case sail, client, program
}

/// An answer this client could not read: the route that gave it, the field as the server spells
/// it (`rooms[0].created_at`), and why.
public struct Undecodable: Equatable, Sendable, CustomStringConvertible {
    public let route: String
    public let field: String
    public let reason: String

    public init(route: String, field: String, reason: String) {
        self.route = route
        self.field = field
        self.reason = reason
    }

    public var description: String {
        "\(route) answered \(field) that this client cannot read: \(reason)"
    }
}
