import Foundation

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

/// A new project's whole definition as `POST /v1/projects` takes it: its name, each repository with
/// the folder under `~/workspace/` it is cloned to, the agents its template installs, and each
/// workspace's size.
public struct ProjectDefinition: Codable, Equatable, Sendable {
    public struct Repo: Codable, Equatable, Sendable {
        public let url: String
        public let path: String

        public init(url: String, path: String) {
            self.url = url
            self.path = path
        }
    }

    public struct Agent: Codable, Equatable, Sendable {
        public let install: [String]

        public init(install: [String]) {
            self.install = install
        }
    }

    /// Cores, and memory and disk as the server spells a size ("8GB").
    public struct Resources: Codable, Equatable, Sendable {
        public let cpu: Int
        public let memory: String
        public let disk: String

        public init(cpu: Int, memory: String, disk: String) {
            self.cpu = cpu
            self.memory = memory
            self.disk = disk
        }
    }

    public let name: String
    public let repos: [Repo]
    public let agent: Agent
    public let resources: Resources

    public init(name: String, repos: [Repo], agent: Agent, resources: Resources) {
        self.name = name
        self.repos = repos
        self.agent = agent
        self.resources = resources
    }
}

/// A project's definition as the server keeps it, by the name it keeps it under, and who wrote it
/// last: what a create answers, and what reading the definition answers.
public struct DefinedProject: Codable, Equatable, Sendable {
    public let name: String
    public let definition: ProjectDefinition
    public let updatedBy: String

    private enum CodingKeys: String, CodingKey {
        case name, definition
        case updatedBy = "updated_by"
    }
}

/// A project's template as the server tells it: where it is, how many steps a build takes and each
/// by name, in the order the build takes them, and its build under way or last ended.
public struct TemplateStatus: Codable, Equatable, Sendable {
    /// A build: who started it and when, the step it is on (from 1; 0 before the first), and, once it
    /// ended, when, and why it failed with what to do.
    public struct Build: Codable, Equatable, Sendable {
        public let by: String
        public let startedAt: Date
        public let endedAt: Date?
        public let step: Int
        public let reason: String?
        public let remedy: [RemedyPart]?

        private enum CodingKeys: String, CodingKey {
            case by, step, reason, remedy
            case startedAt = "started_at"
            case endedAt = "ended_at"
        }
    }

    public let project: String
    public let state: Open<TemplateState>
    public let steps: Int
    public let stepNames: [String]
    public let build: Build?

    private enum CodingKeys: String, CodingKey {
        case project, state, steps, build
        case stepNames = "step_names"
    }
}

extension Endpoint where Response == DefinedProject {
    /// Creates the project `definition` names, whole, with no template yet.
    public static func createProject(_ definition: ProjectDefinition) throws(SailError) -> Self {
        Self(.post, Route.projects, body: try SailJSON.encode(definition))
    }

    /// Project `project`'s definition and who wrote it last.
    public static func projectDefinition(_ project: String) -> Self {
        Self(.get, Route.projects + [project, "definition"])
    }
}

extension SailClient {
    /// Creates `definition` for `handle`. A name taken by a project `handle` wrote last with this very
    /// definition is their own create, which landed while its answer was lost: it answers as that
    /// create did, so Create again after a time-out finds the project rather than a refusal.
    public func create(_ definition: ProjectDefinition, by handle: String) async -> Result<DefinedProject, SailError> {
        do throws(SailError) {
            return .success(try await send(.createProject(definition)))
        } catch {
            guard NewProject.nameRefusal(error) != nil, let held = try? await send(.projectDefinition(definition.name)),
                  held.updatedBy == handle, held.definition == definition else { return .failure(error) }
            return .success(held)
        }
    }
}

extension Endpoint where Response == TemplateStatus {
    /// Builds `project`'s template on the runner, or joins the build under way.
    public static func buildTemplate(project: String) -> Self {
        Self(.post, Route.projects + [project, "template:build"])
    }

    /// `project`'s template and its build.
    public static func template(project: String) -> Self {
        Self(.get, Route.projects + [project, "template"])
    }
}

extension SailClient {
    /// How long after an answer a template being built is read again.
    public static let templateReadsEvery = Duration.seconds(2)

    /// Reads `project`'s template and hands each answer to `answered`, then, while `answered` says
    /// so, reads it again ``templateReadsEvery`` after that answer, by `sleep`: one read at a time,
    /// never two at once; once the task is cancelled, nothing more is read or handed on.
    public func readTemplate(
        _ project: String, sleep: @Sendable (Duration) async throws -> Void, answered: @MainActor (Result<TemplateStatus, SailError>) -> Bool
    ) async {
        while !Task.isCancelled {
            let answer: Result<TemplateStatus, SailError>
            do throws(SailError) {
                answer = .success(try await send(.template(project: project)))
            } catch {
                answer = .failure(error)
            }
            guard !Task.isCancelled, await answered(answer) else { return }
            do {
                try await sleep(Self.templateReadsEvery)
            } catch {
                return
            }
        }
    }
}
