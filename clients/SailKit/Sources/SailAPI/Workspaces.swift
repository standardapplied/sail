/// A project's workspaces on the box that answered: its own container as `main`, and every
/// workspace of it, other people's included.
public struct WorkspaceList: Codable, Equatable, Sendable {
    public let project: String
    public let workspaces: [WorkspaceRow]

    public init(project: String, workspaces: [WorkspaceRow]) {
        self.project = project
        self.workspaces = workspaces
    }
}

/// One workspace: its name, its instance, its state, and its owner while that person is active.
public struct WorkspaceRow: Codable, Equatable, Sendable {
    public let name: String
    public let instance: String
    public let state: Open<WorkspaceState>
    public let owner: String?

    public init(name: String, instance: String, state: Open<WorkspaceState>, owner: String?) {
        self.name = name
        self.instance = instance
        self.state = state
        self.owner = owner
    }
}

/// Where a workspace's instance is.
public enum WorkspaceState: String, CaseIterable, Sendable {
    case running, stopped, frozen
    case notCreated = "not_created"
    case incomplete
}

extension Endpoint where Response == WorkspaceList {
    /// The workspaces of `project`.
    public static func workspaces(project: String) -> Self {
        Self(.get, Route.workspaces(project: project))
    }
}

/// A workspace the caller created: theirs, a clone of the project's current template.
public struct WorkspaceCreated: Codable, Equatable, Sendable {
    public let project: String
    public let workspace: String
    public let instance: String
    /// The person it belongs to: the caller.
    public let owner: String
    /// The template version it was cloned from.
    public let template: String
    /// Whether that template was older than the project's definition: it was cloned as it was.
    public let stale: Bool
    public let timings: WorkspaceTimings

    public init(
        project: String, workspace: String, instance: String, owner: String, template: String, stale: Bool,
        timings: WorkspaceTimings
    ) {
        self.project = project
        self.workspace = workspace
        self.instance = instance
        self.owner = owner
        self.template = template
        self.stale = stale
        self.timings = timings
    }
}

/// What a start, stop or delete of a workspace did; `timings` only for a start that started it.
public struct WorkspaceChanged: Codable, Equatable, Sendable {
    public let project: String
    public let workspace: String
    public let instance: String
    public let change: Open<WorkspaceChange>
    public let timings: WorkspaceTimings?

    public init(
        project: String, workspace: String, instance: String, change: Open<WorkspaceChange>,
        timings: WorkspaceTimings?
    ) {
        self.project = project
        self.workspace = workspace
        self.instance = instance
        self.change = change
        self.timings = timings
    }
}

/// What a workspace door did.
public enum WorkspaceChange: String, CaseIterable, Sendable {
    case started
    case alreadyRunning = "already_running"
    case stopped
    case alreadyStopped = "already_stopped"
    case deleted
}

/// How long a creation or a start took, in milliseconds; `cloneMs` only for a creation.
public struct WorkspaceTimings: Codable, Equatable, Sendable {
    public let cloneMs: Int64?
    public let startMs: Int64
    public let readyMs: Int64
    public let totalMs: Int64
    /// Each step of getting ready, by name.
    public let readySteps: [String: Int64]

    public init(cloneMs: Int64?, startMs: Int64, readyMs: Int64, totalMs: Int64, readySteps: [String: Int64]) {
        self.cloneMs = cloneMs
        self.startMs = startMs
        self.readyMs = readyMs
        self.totalMs = totalMs
        self.readySteps = readySteps
    }

    private enum CodingKeys: String, CodingKey {
        case cloneMs = "clone_ms"
        case startMs = "start_ms"
        case readyMs = "ready_ms"
        case totalMs = "total_ms"
        case readySteps = "ready_steps"
    }
}

extension Endpoint where Response == WorkspaceCreated {
    /// Creates workspace `name` of `project`, the caller's own.
    public static func createWorkspace(_ name: String, project: String) throws(SailError) -> Self {
        Self(.post, Route.workspaces(project: project), body: try SailJSON.encode(["name": name]))
    }
}

extension Endpoint where Response == WorkspaceChanged {
    /// Starts workspace `name` of `project`, giving its owner their credentials again.
    public static func startWorkspace(_ name: String, project: String) -> Self {
        Self(.post, Route.workspace(name, project: project) + ["start"])
    }

    /// Stops workspace `name` of `project`.
    public static func stopWorkspace(_ name: String, project: String) -> Self {
        Self(.post, Route.workspace(name, project: project) + ["stop"])
    }

    /// Deletes workspace `name` of `project`: refused with `workspace_holds_work` when it holds work
    /// or that cannot be told, unless `force`.
    public static func deleteWorkspace(_ name: String, project: String, force: Bool = false) -> Self {
        Self(.delete, Route.workspace(name, project: project), query: [("force", force ? "true" : nil)])
    }
}
