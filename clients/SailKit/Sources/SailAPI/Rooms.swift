import Foundation

/// A room: a conversation in a project, its members, and the specs filed in it. `wake` is the mode
/// the room was set to, if any, and `effectiveWake` the one it runs under. `needsReply` says an
/// agent's question waits for an answer, `questionMessageId` which.
public struct Room: Decodable, Equatable, Sendable {
    public let id: String
    public let project: String
    public let title: String
    public let assignee: String?
    public let wake: String?
    public let effectiveWake: String
    public let members: [RoomMember]
    public let specIds: [String]
    public let createdBy: String?
    public let createdAt: Date
    public let updatedAt: Date
    public let updatedBy: String?
    public let lastActivityAt: Date?
    public let needsReply: Bool
    public let questionMessageId: String?

    private enum CodingKeys: String, CodingKey {
        case id, project, title, assignee, wake, members
        case effectiveWake = "effective_wake"
        case specIds = "spec_ids"
        case createdBy = "created_by"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case updatedBy = "updated_by"
        case lastActivityAt = "last_activity_at"
        case needsReply = "needs_reply"
        case questionMessageId = "question_message_id"
    }
}

/// An agent seated in a room: which agent, what its harness is called, and with what access.
public struct RoomMember: Decodable, Equatable, Sendable {
    public let agent: String
    public let agentName: String?
    public let mode: Open<MemberMode>
    public let model: String?
    public let engagedAt: Date

    private enum CodingKeys: String, CodingKey {
        case agent, mode, model
        case agentName = "agent_name"
        case engagedAt = "engaged_at"
    }
}

public enum MemberMode: String, CaseIterable, Sendable {
    case full
    case readOnly = "read_only"
}

public struct RoomList: Decodable, Equatable, Sendable {
    public let rooms: [Room]
    public let count: Int
}

extension Endpoint where Response == RoomList {
    /// The rooms of `project`, or of every project the caller sees.
    public static func rooms(project: String? = nil) -> Self {
        Self("GET", ["v1", "rooms"], query: [("project", project)])
    }
}

extension Endpoint where Response == Room {
    public static func room(_ id: String) -> Self {
        Self("GET", ["v1", "rooms", id])
    }
}
