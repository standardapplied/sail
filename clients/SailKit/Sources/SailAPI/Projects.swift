/// The projects the caller may see.
public struct ProjectList: Codable, Equatable, Sendable {
    public let projects: [ProjectSummary]
    public let total: Int

    public init(projects: [ProjectSummary], total: Int) {
        self.projects = projects
        self.total = total
    }
}

/// A project, the state of its container on the box that answered, and its template's.
public struct ProjectSummary: Codable, Equatable, Sendable, Identifiable {
    public let name: String
    public let containerStatus: Open<ContainerStatus>
    /// Where its template is; nil from a server that does not say.
    public let template: Open<TemplateState>?

    public init(name: String, containerStatus: Open<ContainerStatus>, template: Open<TemplateState>? = nil) {
        self.name = name
        self.containerStatus = containerStatus
        self.template = template
    }

    /// A project is known by its name.
    public var id: String {
        name
    }

    private enum CodingKeys: String, CodingKey {
        case name
        case containerStatus = "container_status"
        case template
    }
}

/// Where a project's template is: a version is current; one is being built; none is current and
/// the last build failed or was cut short; or there has never been one.
public enum TemplateState: String, CaseIterable, Sendable {
    case ready, building, failed, missing
}

/// What a project's container is doing: `error` when the box could not tell.
public enum ContainerStatus: String, CaseIterable, Sendable {
    case running, stopped, frozen
    case notCreated = "not_created"
    case error
}

extension Endpoint where Response == ProjectList {
    public static var projects: Self {
        Self(.get, Route.projects)
    }
}
