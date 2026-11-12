import Foundation

/// A spec as the server shows it. `engagement` is the agent standing in its room, and `wake` the
/// room's wake mode. `lastActivityAt` comes with a listing; `needsReply` and `questionMessageID`
/// say an agent's question in its room waits for an answer.
public struct Spec: Decodable, Equatable, Sendable {
    public let id: String
    public let project: String
    public let title: String
    public let status: Open<SpecStatus>
    public let assignee: String?
    public let agent: String?
    public let model: String?
    public let reasoningEffort: String?
    public let branch: String?
    public let priority: Int
    public let dependsOn: [String]
    public let repos: [String]
    public let wake: String?
    public let engagement: RoomMember?
    public let createdBy: String?
    public let createdAt: Date
    public let updatedAt: Date
    public let updatedBy: String?
    public let roomID: String?
    public let lastActivityAt: Date?
    public let needsReply: Bool
    public let questionMessageID: String?

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        id = try fields.decode(String.self, forKey: .id)
        project = try fields.decode(String.self, forKey: .project)
        title = try fields.decode(String.self, forKey: .title)
        status = try fields.decode(Open<SpecStatus>.self, forKey: .status)
        assignee = try fields.decodeIfPresent(String.self, forKey: .assignee)
        agent = try fields.decodeIfPresent(String.self, forKey: .agent)
        model = try fields.decodeIfPresent(String.self, forKey: .model)
        reasoningEffort = try fields.decodeIfPresent(String.self, forKey: .reasoningEffort)
        branch = try fields.decodeIfPresent(String.self, forKey: .branch)
        priority = try fields.decode(Int.self, forKey: .priority)
        dependsOn = try fields.decodeIfPresent([String].self, forKey: .dependsOn) ?? []
        repos = try fields.decodeIfPresent([String].self, forKey: .repos) ?? []
        wake = try fields.decodeIfPresent(String.self, forKey: .wake)
        engagement = try fields.decodeIfPresent(RoomMember.self, forKey: .engagement)
        createdBy = try fields.decodeIfPresent(String.self, forKey: .createdBy)
        createdAt = try fields.decode(Date.self, forKey: .createdAt)
        updatedAt = try fields.decode(Date.self, forKey: .updatedAt)
        updatedBy = try fields.decodeIfPresent(String.self, forKey: .updatedBy)
        roomID = try fields.decodeIfPresent(String.self, forKey: .roomID)
        lastActivityAt = try fields.decodeIfPresent(Date.self, forKey: .lastActivityAt)
        needsReply = try fields.decodeIfPresent(Bool.self, forKey: .needsReply) ?? false
        questionMessageID = try fields.decodeIfPresent(String.self, forKey: .questionMessageID)
    }

    private enum CodingKeys: String, CodingKey {
        case id, project, title, status, assignee, agent, model, branch, priority, repos, wake, engagement
        case reasoningEffort = "reasoning_effort"
        case dependsOn = "depends_on"
        case createdBy = "created_by"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case updatedBy = "updated_by"
        case roomID = "room_id"
        case lastActivityAt = "last_activity_at"
        case needsReply = "needs_reply"
        case questionMessageID = "question_message_id"
    }
}

public enum SpecStatus: String, CaseIterable, Sendable {
    case draft, pending
    case inProgress = "in_progress"
    case review
    case awaitingMerge = "awaiting_merge"
    case done, cancelled, archived
}

public struct SpecList: Decodable, Equatable, Sendable {
    public let specs: [Spec]
    public let total: Int
}

/// One spec with what it says: its body and its plan.
public struct SpecDetail: Decodable, Equatable, Sendable {
    public let spec: Spec
    public let body: String?
    public let plan: String?
}

/// Which specs a listing holds; a nil field filters nothing. `assignee` `me` is the caller.
public struct SpecFilter: Equatable, Sendable {
    public var project: String?
    public var status: SpecStatus?
    public var assignee: String?
    public var repo: String?
    public var text: String?

    public init(
        project: String? = nil, status: SpecStatus? = nil, assignee: String? = nil, repo: String? = nil,
        text: String? = nil
    ) {
        self.project = project
        self.status = status
        self.assignee = assignee
        self.repo = repo
        self.text = text
    }
}

extension Endpoint where Response == SpecList {
    public static func specs(_ filter: SpecFilter = SpecFilter()) -> Self {
        Self(
            "GET", ["v1", "specs"],
            query: [
                ("project", filter.project), ("status", filter.status?.rawValue), ("assignee", filter.assignee),
                ("repo", filter.repo), ("q", filter.text),
            ]
        )
    }
}

extension Endpoint where Response == SpecDetail {
    public static func spec(_ id: String) -> Self {
        Self("GET", ["v1", "specs", id])
    }
}
