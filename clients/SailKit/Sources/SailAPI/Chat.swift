import Foundation

/// The agent a chat opens with: its harness, the chat mode it starts in, and whether it signs in
/// inside instead of with the person's stored key.
public struct ChatAgent: Encodable, Equatable, Sendable {
    public let harness: String
    public let mode: String
    public let inside: Bool

    public init(harness: String, mode: String, inside: Bool) {
        self.harness = harness
        self.mode = mode
        self.inside = inside
    }
}

/// The chat a workspace opened: the session it is.
public struct ChatOpened: Decodable, Equatable, Sendable {
    public let session: String
}

/// A message the chat took: the seq it was logged at, and whether it waits for the open turn's end.
public struct ChatSent: Decodable, Equatable, Sendable {
    public let seq: Int64
    public let queued: Bool
}

/// What a chat logged for an answer or a change of mode: its seq.
public struct ChatLogged: Decodable, Equatable, Sendable {
    public let seq: Int64
}

/// Whether the chat's open turn is stopping.
public struct ChatStopping: Decodable, Equatable, Sendable {
    public let stopping: Bool
}

/// What a person answers a request of their agent with: allow it, allow it always, deny it with
/// their words or none, or answer a question, each of its questions by its text.
public enum ChatAnswer: Encodable, Equatable, Sendable {
    case allow
    case allowAlways
    case deny(String?)
    case answer([String: Given])

    /// One question's answer: labels of its own choices, or the person's own words.
    public struct Given: Encodable, Equatable, Sendable {
        public let chosen: [String]
        public let own: String?

        public init(chosen: [String] = [], own: String? = nil) {
            self.chosen = chosen
            self.own = own
        }
    }

    public var decision: ChatDecision {
        switch self {
        case .allow: .allow
        case .allowAlways: .allowAlways
        case .deny: .deny
        case .answer: .answer
        }
    }

    private enum CodingKeys: String, CodingKey {
        case decision, message, answers
    }

    public func encode(to encoder: any Encoder) throws {
        var answer = encoder.container(keyedBy: CodingKeys.self)
        try answer.encode(decision, forKey: .decision)
        switch self {
        case let .deny(words): try answer.encodeIfPresent(words, forKey: .message)
        case let .answer(answers): try answer.encode(answers, forKey: .answers)
        case .allow, .allowAlways: break
        }
    }
}

extension Route {
    package static func session(_ id: String) -> [String] {
        ["v1", "sessions", id]
    }

    package static func chat(_ session: String) -> [String] {
        Self.session(session) + ["chat"]
    }
}

extension Endpoint where Response == ChatOpened {
    /// Opens a chat with `agent` in workspace `workspace` of `project`, the caller's own.
    public static func openChat(_ agent: ChatAgent, workspace: String, project: String) throws(SailError) -> Self {
        Self(.post, Route.workspace(workspace, project: project) + ["chats"], body: try SailJSON.encode(agent))
    }
}

extension Endpoint where Response == ChatSent {
    /// Says `text` in the caller's chat `session`.
    public static func send(_ text: String, chat session: String) throws(SailError) -> Self {
        Self(.post, Route.chat(session) + ["messages"], body: try SailJSON.encode(["text": text]))
    }
}

extension Endpoint where Response == ChatPage {
    /// The items of the caller's chat `session` after `seq`, held by the server until there is one.
    public static func chat(_ session: String, after seq: Int64) -> Self {
        Self(.get, Route.chat(session), query: [("after", String(seq))])
    }
}

extension Endpoint where Response == ChatLogged {
    /// Answers the request `request` of the caller's chat `session` with `answer`.
    public static func answer(_ request: String, chat session: String, with answer: ChatAnswer) throws(SailError) -> Self {
        Self(.post, Route.chat(session) + ["requests", request], body: try SailJSON.encode(answer))
    }

    /// Puts the caller's chat `session` in `mode`, one of its harness's chat modes, between turns.
    public static func setMode(_ mode: String, chat session: String) throws(SailError) -> Self {
        Self(.put, Route.chat(session) + ["mode"], body: try SailJSON.encode(["mode": mode]))
    }
}

extension Endpoint where Response == ChatStopping {
    /// Stops the open turn of the caller's chat `session`.
    public static func interrupt(chat session: String) -> Self {
        Self(.post, Route.chat(session), action: "interrupt")
    }
}

extension Endpoint where Response == NoContent {
    /// Ends the caller's chat `session`: its process and the chat, for good.
    public static func endChat(_ session: String) -> Self {
        Self(.post, Route.session(session), action: "end")
    }
}
