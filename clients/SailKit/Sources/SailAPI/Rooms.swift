import Foundation

/// A room: a conversation in a project, its members, and the specs filed in it. `wake` is the mode
/// the room was set to, if any, and `effectiveWake` the one it runs under. `needsReply` says an
/// agent's question waits for an answer, `questionMessageID` which.
public struct Room: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let project: String
    public let title: String
    public let assignee: String?
    public let wake: Open<WakeMode>?
    public let effectiveWake: Open<WakeMode>
    public let members: [RoomMember]
    public let specIDs: [String]
    public let createdBy: String?
    public let createdAt: Date
    public let updatedAt: Date
    public let updatedBy: String?
    public let lastActivityAt: Date?
    public let needsReply: Bool
    public let questionMessageID: String?

    public init(
        id: String, project: String, title: String, assignee: String? = nil, wake: Open<WakeMode>? = nil,
        effectiveWake: Open<WakeMode>, members: [RoomMember] = [], specIDs: [String] = [], createdBy: String? = nil,
        createdAt: Date, updatedAt: Date, updatedBy: String? = nil, lastActivityAt: Date? = nil,
        needsReply: Bool = false, questionMessageID: String? = nil
    ) {
        self.id = id
        self.project = project
        self.title = title
        self.assignee = assignee
        self.wake = wake
        self.effectiveWake = effectiveWake
        self.members = members
        self.specIDs = specIDs
        self.createdBy = createdBy
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.updatedBy = updatedBy
        self.lastActivityAt = lastActivityAt
        self.needsReply = needsReply
        self.questionMessageID = questionMessageID
    }

    /// Reads a room as a list or the change feed shapes it. A fed room carries none of the fields
    /// the list derives from other rows (``HeldRows`` derives them): its specs, latest activity and
    /// open question read as none until derived.
    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        id = try fields.decode(String.self, forKey: .id)
        project = try fields.decode(String.self, forKey: .project)
        title = try fields.decode(String.self, forKey: .title)
        assignee = try fields.decodeIfPresent(String.self, forKey: .assignee)
        wake = try fields.decodeIfPresent(Open<WakeMode>.self, forKey: .wake)
        effectiveWake = try fields.decode(Open<WakeMode>.self, forKey: .effectiveWake)
        members = try fields.decodeIfPresent([RoomMember].self, forKey: .members) ?? []
        specIDs = try fields.decodeIfPresent([String].self, forKey: .specIDs) ?? []
        createdBy = try fields.decodeIfPresent(String.self, forKey: .createdBy)
        createdAt = try fields.decode(Date.self, forKey: .createdAt)
        updatedAt = try fields.decode(Date.self, forKey: .updatedAt)
        updatedBy = try fields.decodeIfPresent(String.self, forKey: .updatedBy)
        lastActivityAt = try fields.decodeIfPresent(Date.self, forKey: .lastActivityAt)
        needsReply = try fields.decodeIfPresent(Bool.self, forKey: .needsReply) ?? false
        questionMessageID = try fields.decodeIfPresent(String.self, forKey: .questionMessageID)
    }

    private enum CodingKeys: String, CodingKey {
        case id, project, title, assignee, wake, members
        case effectiveWake = "effective_wake"
        case specIDs = "spec_ids"
        case createdBy = "created_by"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case updatedBy = "updated_by"
        case lastActivityAt = "last_activity_at"
        case needsReply = "needs_reply"
        case questionMessageID = "question_message_id"
    }
}

/// When a message in a room wakes its agent: on every one, on one that mentions it, or never.
public enum WakeMode: String, CaseIterable, Sendable {
    case on, mention, off
}

/// An agent seated in a room: which agent, what its harness is called, and with what access.
public struct RoomMember: Codable, Equatable, Sendable {
    public let agent: String
    public let agentName: String?
    public let mode: Open<MemberMode>
    public let model: String?
    public let engagedAt: Date

    public init(agent: String, agentName: String? = nil, mode: Open<MemberMode>, model: String? = nil, engagedAt: Date) {
        self.agent = agent
        self.agentName = agentName
        self.mode = mode
        self.model = model
        self.engagedAt = engagedAt
    }

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

public struct RoomList: Codable, Equatable, Sendable {
    public let rooms: [Room]
    public let count: Int

    public init(rooms: [Room], count: Int) {
        self.rooms = rooms
        self.count = count
    }
}

extension Endpoint where Response == RoomList {
    /// The rooms of `project`, or of every project the caller sees.
    public static func rooms(project: String? = nil) -> Self {
        Self(.get, Route.rooms, query: [("project", project)])
    }
}

extension Endpoint where Response == Room {
    public static func room(_ id: String) -> Self {
        Self(.get, Route.room(id))
    }
}
