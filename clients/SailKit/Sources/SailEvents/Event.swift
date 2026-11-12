import Foundation
import SailAPI

/// One event the server published: what happened (`type`) and when (`timestamp`), in which project
/// and, when it concerns one, which spec or room (`spec`). `version` is the event format's. `id` is the stream's sequence; an event the stream tells a
/// reader first and never stores, a room's turn state, has none. `agent` is a label its sender
/// chose; `publisher` is who published it through a door, absent for the server's own events.
public struct Event: Decodable, Equatable, Sendable {
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
/// a machine credential that names no FDE.
public struct Publisher: Decodable, Equatable, Sendable {
    public let handle: String?
    public let role: String
    public let lane: String

    public init(handle: String?, role: String, lane: String) {
        self.handle = handle
        self.role = role
        self.lane = lane
    }
}
