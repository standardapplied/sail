/// The projects the caller may see.
public struct ProjectList: Codable, Equatable, Sendable {
    public let projects: [ProjectSummary]
    public let total: Int

    public init(projects: [ProjectSummary], total: Int) {
        self.projects = projects
        self.total = total
    }
}

/// A project, and the state of its container on the box that answered.
public struct ProjectSummary: Codable, Equatable, Sendable, Identifiable {
    public let name: String
    public let containerStatus: Open<ContainerStatus>

    public init(name: String, containerStatus: Open<ContainerStatus>) {
        self.name = name
        self.containerStatus = containerStatus
    }

    /// A project is known by its name.
    public var id: String {
        name
    }

    private enum CodingKeys: String, CodingKey {
        case name
        case containerStatus = "container_status"
    }
}

/// What a project's container is doing: `error` when the box could not tell.
public enum ContainerStatus: String, CaseIterable, Sendable {
    case running, stopped
    case notCreated = "not_created"
    case error
}

extension Endpoint where Response == ProjectList {
    public static var projects: Self {
        Self(.get, Route.projects)
    }
}
