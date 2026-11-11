/// The projects the caller may see. `containerStatus` is the project's container on this box, as
/// the server words it (`running`, `stopped`, `not_created`).
public struct ProjectList: Decodable, Equatable, Sendable {
    public let projects: [ProjectSummary]
    public let total: Int
}

public struct ProjectSummary: Decodable, Equatable, Sendable {
    public let name: String
    public let containerStatus: String

    private enum CodingKeys: String, CodingKey {
        case name
        case containerStatus = "container_status"
    }
}

extension Endpoint where Response == ProjectList {
    public static var projects: Self {
        Self("GET", ["v1", "projects"])
    }
}
