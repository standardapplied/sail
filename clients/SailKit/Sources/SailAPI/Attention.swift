import Foundation

/// What a focus heartbeat recorded: the conversation, the minute the server stamped, and whether
/// that minute of the caller's was new. The server names the person and the minute; the client
/// names only the conversation.
public struct AttentionMinute: Codable, Equatable, Sendable {
    public let conversation: String
    public let minute: Date
    public let recorded: Bool

    public init(conversation: String, minute: Date, recorded: Bool) {
        self.conversation = conversation
        self.minute = minute
        self.recorded = recorded
    }
}

extension Endpoint where Response == AttentionMinute {
    /// The heartbeat of a view of `conversation`, a room or a spec, that is focused with the
    /// person's input in the last minute.
    public static func attention(conversation: String) throws(SailError) -> Self {
        Self(.post, Route.attention, body: try SailJSON.encode(["conversation": conversation]))
    }
}

/// When a view sends its focus heartbeat: at most once a minute, and only while it is focused and
/// the person gave it input in the last minute. Every instant is the caller's clock, passed in.
public struct FocusHeartbeat: Equatable, Sendable {
    /// The least time between two heartbeats, and how recent input must be.
    public static let interval: TimeInterval = 60

    private var lastInput: Date?
    private var lastSent: Date?

    public init() {}

    /// The person gave the view input at `instant`.
    public mutating func input(at instant: Date) {
        lastInput = instant
    }

    /// Whether a heartbeat is due at `now` for a view that is `focused`: focused, with input in the
    /// last minute, and none sent in the last minute. A heartbeat due is taken as sent.
    public mutating func due(at now: Date, focused: Bool) -> Bool {
        guard focused, let input = lastInput, now.timeIntervalSince(input) < Self.interval else {
            return false
        }
        if let sent = lastSent, now.timeIntervalSince(sent) < Self.interval {
            return false
        }
        lastSent = now
        return true
    }
}
