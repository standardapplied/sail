import Foundation

/// A message in a room. `specID` names the room it was posted in: the server still calls a room a
/// spec there.
public struct Message: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let specID: String
    public let author: String
    public let body: String
    public let replyTo: String?
    public let createdAt: Date
    /// Whether it asks for a reply.
    public let isQuestion: Bool
    /// Whether it is what a live member's failed turn left, in its own words.
    public let isError: Bool

    public init(
        id: String, specID: String, author: String, body: String, replyTo: String? = nil, createdAt: Date,
        isQuestion: Bool = false, isError: Bool = false
    ) {
        self.id = id
        self.specID = specID
        self.author = author
        self.body = body
        self.replyTo = replyTo
        self.createdAt = createdAt
        self.isQuestion = isQuestion
        self.isError = isError
    }

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        id = try fields.decode(String.self, forKey: .id)
        specID = try fields.decode(String.self, forKey: .specID)
        author = try fields.decode(String.self, forKey: .author)
        body = try fields.decode(String.self, forKey: .body)
        replyTo = try fields.decodeIfPresent(String.self, forKey: .replyTo)
        createdAt = try fields.decode(Date.self, forKey: .createdAt)
        isQuestion = try fields.decodeIfPresent(Bool.self, forKey: .isQuestion) ?? false
        isError = try fields.decodeIfPresent(Bool.self, forKey: .isError) ?? false
    }

    private enum CodingKeys: String, CodingKey {
        case id, author, body
        case specID = "spec_id"
        case replyTo = "reply_to"
        case createdAt = "created_at"
        case isQuestion = "question"
        case isError = "error"
    }
}

/// One page of a room's messages, oldest first.
public struct MessageList: Codable, Equatable, Sendable {
    public let specID: String
    public let messages: [Message]
    public let total: Int

    public init(specID: String, messages: [Message], total: Int) {
        self.specID = specID
        self.messages = messages
        self.total = total
    }

    private enum CodingKeys: String, CodingKey {
        case messages, total
        case specID = "spec_id"
    }
}

public struct MessagePosted: Codable, Equatable, Sendable {
    public let message: Message

    public init(message: Message) {
        self.message = message
    }
}

/// A message to post: its body, the message it answers, and whether it asks a question.
public struct MessagePost: Encodable, Equatable, Sendable {
    public let body: String
    public let replyTo: String?
    private let question: Bool?

    public init(body: String, replyTo: String? = nil, asksQuestion: Bool = false) {
        self.body = body
        self.replyTo = replyTo
        question = asksQuestion ? true : nil
    }

    public var asksQuestion: Bool {
        question == true
    }

    private enum CodingKeys: String, CodingKey {
        case body, question
        case replyTo = "reply_to"
    }
}

extension Endpoint where Response == MessageList {
    /// A page of `room`'s messages: the newest, or those before or after a message id, at most
    /// `limit` of them (the server's default is 50, its most 100).
    public static func messages(room: String, before: String? = nil, after: String? = nil, limit: Int? = nil) -> Self {
        Self(
            .get, Route.messages(room: room),
            query: [("before", before), ("after", after), ("limit", limit.map(String.init))]
        )
    }
}

extension Endpoint where Response == MessagePosted {
    public static func post(_ message: MessagePost, room: String) throws(SailError) -> Self {
        Self(.post, Route.messages(room: room), body: try SailJSON.encode(message))
    }
}
