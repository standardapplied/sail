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
    /// What answered a request for an event stream is not one: the content type it named, if any.
    /// Something between this client and the server answered in its place.
    case notAnEventStream(route: String, contentType: String?)
    /// The request cannot be sent as it was written: why.
    case unsendable(String)
}

/// A refusal as the server words it, kept verbatim: what it is, what happened, and what to do.
/// `action` is the fix as a person reads it; `remedy` is the same fix as words and commands, sent
/// when it names a command, so a client can render each command for its own reader. `fieldErrors`
/// name what the refusal is about, field by field, when the server says.
public struct Refusal: Codable, Equatable, Sendable {
    public let code: Open<ErrorCode>
    public let message: String
    public let action: String?
    public let remedy: [RemedyPart]
    public let fieldErrors: [FieldError]

    public init(
        code: Open<ErrorCode>, message: String, action: String? = nil, remedy: [RemedyPart] = [],
        fieldErrors: [FieldError] = []
    ) {
        self.code = code
        self.message = message
        self.action = action
        self.remedy = remedy
        self.fieldErrors = fieldErrors
    }

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        code = try fields.decode(Open<ErrorCode>.self, forKey: .code)
        message = try fields.decode(String.self, forKey: .message)
        action = try fields.decodeIfPresent(String.self, forKey: .action)
        remedy = try fields.decodeIfPresent([RemedyPart].self, forKey: .remedy) ?? []
        fieldErrors = try fields.decodeIfPresent([FieldError].self, forKey: .fieldErrors) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case code, message, action, remedy
        case fieldErrors = "field_errors"
    }
}

/// One thing a refusal is about: a field of the request, or a fact the server names (the node a
/// run executed on, say), and what the server says of it.
public struct FieldError: Codable, Equatable, Sendable {
    public let field: String
    public let message: String

    public init(field: String, message: String) {
        self.field = field
        self.message = message
    }
}

/// One part of a remedy: words, or a command to run.
public enum RemedyPart: Codable, Equatable, Sendable {
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

    public func encode(to encoder: any Encoder) throws {
        var fields = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case let .words(text):
            try fields.encode(text, forKey: .text)
        case let .command(run, verb, args):
            try fields.encode(run, forKey: .run)
            try fields.encode(verb, forKey: .verb)
            try fields.encode(args, forKey: .args)
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
