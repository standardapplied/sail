import Foundation

/// A message in a room. `specID` names the room it was posted in: the server still calls a room a
/// spec there.
public struct Message: Decodable, Equatable, Sendable {
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

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        id = try fields.decode(String.self, forKey: .id)
        specID = try fields.decode(String.self, forKey: .specID)
        author = try fields.decode(String.self, forKey: .author)
        body = try fields.decode(String.self, forKey: .body)
        replyTo = try fields.decodeIfPresent(String.self, forKey: .replyTo)
        createdAt = try fields.decode(Date.self, forKey: .createdAt)
        isQuestion = try fields.decodeIfPresent(Bool.self, forKey: .question) ?? false
        isError = try fields.decodeIfPresent(Bool.self, forKey: .error) ?? false
    }

    private enum CodingKeys: String, CodingKey {
        case id, author, body, question, error
        case specID = "spec_id"
        case replyTo = "reply_to"
        case createdAt = "created_at"
    }
}

/// One page of a room's messages, oldest first.
public struct MessageList: Decodable, Equatable, Sendable {
    public let specID: String
    public let messages: [Message]
    public let total: Int

    private enum CodingKeys: String, CodingKey {
        case messages, total
        case specID = "spec_id"
    }
}

public struct MessagePosted: Decodable, Equatable, Sendable {
    public let message: Message
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
            "GET", ["v1", "rooms", room, "messages"],
            query: [("before", before), ("after", after), ("limit", limit.map(String.init))]
        )
    }
}

extension Endpoint where Response == MessagePosted {
    public static func post(_ message: MessagePost, room: String) -> Self {
        Self("POST", ["v1", "rooms", room, "messages"], body: SailJSON.encode(message))
    }
}
