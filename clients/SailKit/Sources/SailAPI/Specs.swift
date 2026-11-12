import Foundation

/// A spec as the server shows it. `engagement` is the agent standing in its room, and `wake` the
/// room's wake mode. `lastActivityAt` comes with a listing; `needsReply` and `questionMessageID`
/// say an agent's question in its room waits for an answer.
public struct Spec: Codable, Equatable, Sendable, Identifiable {
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
    public let wake: Open<WakeMode>?
    public let engagement: RoomMember?
    public let createdBy: String?
    public let createdAt: Date
    public let updatedAt: Date
    public let updatedBy: String?
    public let roomID: String?
    public let lastActivityAt: Date?
    public let needsReply: Bool
    public let questionMessageID: String?

    public init(
        id: String, project: String, title: String, status: Open<SpecStatus>, assignee: String? = nil,
        agent: String? = nil, model: String? = nil, reasoningEffort: String? = nil, branch: String? = nil,
        priority: Int = 0, dependsOn: [String] = [], repos: [String] = [], wake: Open<WakeMode>? = nil,
        engagement: RoomMember? = nil, createdBy: String? = nil, createdAt: Date, updatedAt: Date,
        updatedBy: String? = nil, roomID: String? = nil, lastActivityAt: Date? = nil, needsReply: Bool = false,
        questionMessageID: String? = nil
    ) {
        self.id = id
        self.project = project
        self.title = title
        self.status = status
        self.assignee = assignee
        self.agent = agent
        self.model = model
        self.reasoningEffort = reasoningEffort
        self.branch = branch
        self.priority = priority
        self.dependsOn = dependsOn
        self.repos = repos
        self.wake = wake
        self.engagement = engagement
        self.createdBy = createdBy
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.updatedBy = updatedBy
        self.roomID = roomID
        self.lastActivityAt = lastActivityAt
        self.needsReply = needsReply
        self.questionMessageID = questionMessageID
    }

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
        wake = try fields.decodeIfPresent(Open<WakeMode>.self, forKey: .wake)
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

public struct SpecList: Codable, Equatable, Sendable {
    public let specs: [Spec]
    public let total: Int

    public init(specs: [Spec], total: Int) {
        self.specs = specs
        self.total = total
    }
}

/// One spec with what it says, its body and its plan, and where its work stands: `openFindings`
/// counts the findings its last passed review left open, and `latestRun` is its newest run.
public struct SpecDetail: Codable, Equatable, Sendable {
    public let spec: Spec
    public let body: String?
    public let plan: String?
    public let openFindings: Int
    public let latestRun: RunSummary?

    public init(spec: Spec, body: String? = nil, plan: String? = nil, openFindings: Int = 0, latestRun: RunSummary? = nil) {
        self.spec = spec
        self.body = body
        self.plan = plan
        self.openFindings = openFindings
        self.latestRun = latestRun
    }

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        spec = try fields.decode(Spec.self, forKey: .spec)
        body = try fields.decodeIfPresent(String.self, forKey: .body)
        plan = try fields.decodeIfPresent(String.self, forKey: .plan)
        openFindings = try fields.decodeIfPresent(Int.self, forKey: .openFindings) ?? 0
        latestRun = try fields.decodeIfPresent(RunSummary.self, forKey: .latestRun)
    }

    private enum CodingKeys: String, CodingKey {
        case spec, body, plan
        case openFindings = "open_findings"
        case latestRun = "latest_run"
    }
}

/// A run of an agent on a spec: the box that executed it (`node`), the principal it acts as and
/// the FDE who owns it, how it ended, and, while it runs, whether it shows progress (`presence`).
public struct RunSummary: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let node: String
    public let status: Open<RunStatus>
    public let exitCode: Int?
    public let principal: String?
    public let owner: String?
    public let sessionID: String?
    public let sessionSource: String?
    public let lastActivityAt: Date?
    public let presence: Open<RunPresence>?

    public init(
        id: String, node: String, status: Open<RunStatus>, exitCode: Int? = nil, principal: String? = nil,
        owner: String? = nil, sessionID: String? = nil, sessionSource: String? = nil, lastActivityAt: Date? = nil,
        presence: Open<RunPresence>? = nil
    ) {
        self.id = id
        self.node = node
        self.status = status
        self.exitCode = exitCode
        self.principal = principal
        self.owner = owner
        self.sessionID = sessionID
        self.sessionSource = sessionSource
        self.lastActivityAt = lastActivityAt
        self.presence = presence
    }

    private enum CodingKeys: String, CodingKey {
        case id, node, status, principal, owner, presence
        case exitCode = "exit_code"
        case sessionID = "session_id"
        case sessionSource = "session_source"
        case lastActivityAt = "last_activity_at"
    }
}

public enum RunStatus: String, CaseIterable, Sendable {
    case running, stopping, stopped, completed, failed
}

/// Whether a running run has shown progress lately.
public enum RunPresence: String, CaseIterable, Sendable {
    case working, quiet
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
            .get, Route.specs,
            query: [
                ("project", filter.project), ("status", filter.status?.rawValue), ("assignee", filter.assignee),
                ("repo", filter.repo), ("q", filter.text),
            ]
        )
    }
}

extension Endpoint where Response == SpecDetail {
    public static func spec(_ id: String) -> Self {
        Self(.get, Route.spec(id))
    }
}
