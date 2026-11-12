import Foundation
import SailAPI

/// One event the server published: what happened (`type`) and when (`timestamp`), in which project
/// and, when it concerns one, which spec or room (`spec`). `version` is the event format's. `id` is
/// the stream's sequence; an event the stream tells a reader first and never stores, a room's turn
/// state, has none, which is why an event is not `Identifiable`. `agent` is a label its sender
/// chose; `publisher` is who published it through a door, absent for the server's own events.
public struct Event: Codable, Equatable, Sendable {
    public let version: Int
    public let id: Int64?
    public let timestamp: Date
    public let project: String
    public let spec: String?
    public let type: Open<EventType>
    public let agent: String
    public let host: String
    public let data: [String: JSONValue]
    public let publisher: Publisher?

    public init(
        version: Int = 1, id: Int64? = nil, timestamp: Date, project: String, spec: String? = nil,
        type: Open<EventType>, agent: String, host: String, data: [String: JSONValue] = [:],
        publisher: Publisher? = nil
    ) {
        self.version = version
        self.id = id
        self.timestamp = timestamp
        self.project = project
        self.spec = spec
        self.type = type
        self.agent = agent
        self.host = host
        self.data = data
        self.publisher = publisher
    }

    public init(from decoder: any Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        version = try fields.decode(Int.self, forKey: .version)
        id = try fields.decodeIfPresent(Int64.self, forKey: .id)
        timestamp = try fields.decode(Date.self, forKey: .timestamp)
        project = try fields.decode(String.self, forKey: .project)
        spec = try fields.decodeIfPresent(String.self, forKey: .spec)
        type = try fields.decode(Open<EventType>.self, forKey: .type)
        agent = try fields.decode(String.self, forKey: .agent)
        host = try fields.decode(String.self, forKey: .host)
        data = try fields.decodeIfPresent([String: JSONValue].self, forKey: .data) ?? [:]
        publisher = try fields.decodeIfPresent(Publisher.self, forKey: .publisher)
    }

    private enum CodingKeys: String, CodingKey {
        case id, project, spec, type, agent, host, data, publisher
        case version = "v"
        case timestamp = "ts"
    }
}

/// Who published an event through a door, as the server authenticated them: `handle` is absent for
/// a machine credential that names no FDE, and `lane` is the door they came through.
public struct Publisher: Codable, Equatable, Sendable {
    public let handle: String?
    public let role: Open<Role>
    public let lane: Open<Lane>

    public init(handle: String? = nil, role: Open<Role>, lane: Open<Lane>) {
        self.handle = handle
        self.role = role
        self.lane = lane
    }
}

/// The ways a write reaches the server: the box's own command line, an HTTP token, a run's
/// principal (`agent`) or a read-and-converse run's (`room`) over the container's socket, the
/// box's credential used inside a project container (`container`), a node's sync, main's revisions
/// adopted by a node, and the box's own machinery.
public enum Lane: String, CaseIterable, Sendable {
    case cli, api, agent, room, container, sync, main, system
}

/// The newest events the server holds that the caller may read, at most `limit` of them: what a
/// reader asks for after a stream connects, since a stream carries only what is published while it
/// is open.
public struct RecentEvents: Codable, Equatable, Sendable {
    public let limit: Int
    public let returned: Int
    public let events: [Event]

    public init(limit: Int, returned: Int, events: [Event]) {
        self.limit = limit
        self.returned = returned
        self.events = events
    }
}

extension Endpoint where Response == RecentEvents {
    /// The newest events, `limit` of them at most (the server allows 1 through 5000).
    public static func recentEvents(limit: Int? = nil) -> Self {
        Self(.get, Route.recentEvents, query: [("limit", limit.map(String.init))])
    }
}
