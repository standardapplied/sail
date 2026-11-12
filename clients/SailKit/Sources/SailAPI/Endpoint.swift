import Foundation

/// One route of the API and the answer it gives: what to send, and how to read what comes back.
public struct Endpoint<Response: Decodable & Sendable>: Sendable {
    public let request: HTTPRequest

    package init(_ method: HTTPMethod, _ segments: [String], query: [(String, String?)] = [], body: Data? = nil) {
        request = HTTPRequest(method, segments, query: query, body: body)
    }

    /// The answer `response` gives: its body on success, the server's refusal otherwise.
    public func decode(_ response: HTTPResponse) throws(SailError) -> Response {
        try SailJSON.decode(response, route: request.route)
    }
}

/// The API's paths, each spelled once, as the segments a request escapes.
package enum Route {
    package static let whoami = ["v1", "whoami"]
    package static let projects = ["v1", "projects"]
    package static let rooms = ["v1", "rooms"]
    package static let specs = ["v1", "specs"]
    package static let eventStream = ["v1", "events", "stream"]
    package static let recentEvents = ["v1", "events", "recent"]
    package static let terminalTickets = ["v1", "terminal", "tickets"]

    package static func room(_ id: String) -> [String] {
        rooms + [id]
    }

    package static func messages(room id: String) -> [String] {
        room(id) + ["messages"]
    }

    package static func spec(_ id: String) -> [String] {
        specs + [id]
    }
}

/// The server's JSON, read and written one way: each key as its model spells it, and an instant as
/// ISO-8601 with milliseconds, so a model encoded here decodes here to itself.
package enum SailJSON {
    package static func decode<Response: Decodable>(_ response: HTTPResponse, route: String) throws(SailError) -> Response {
        guard (200 ..< 300).contains(response.status) else {
            throw refusal(response, route: route)
        }
        return try decode(Response.self, from: response.body, route: route)
    }

    package static func decode<Value: Decodable>(_ type: Value.Type, from data: Data, route: String) throws(SailError) -> Value {
        do {
            return try decoder().decode(type, from: data)
        } catch {
            throw .undecodable(undecodable(error, route: route))
        }
    }

    package static func encode(_ value: some Encodable) throws(SailError) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var text = encoder.singleValueContainer()
            try text.encode(instant(date))
        }
        do {
            return try encoder.encode(value)
        } catch {
            throw .unsendable("\(Swift.type(of: value)) does not encode as JSON: \(error)")
        }
    }

    /// The refusal a failed answer carries, or the bare status when it carries none.
    package static func refusal(_ response: HTTPResponse, route: String) -> SailError {
        guard let envelope = try? decoder().decode(Envelope.self, from: response.body) else {
            return .unexpected(route: route, status: response.status)
        }
        return .refused(envelope.error)
    }

    private struct Envelope: Decodable {
        let error: Refusal
    }

    /// `date` as the server writes an instant: ISO-8601 in UTC to the nearest millisecond. The
    /// fraction is written here, from the rounded instant, since a formatter that cuts a fraction
    /// short would write the millisecond before one a `Double` holds a hair under.
    private static func instant(_ date: Date) -> String {
        let milliseconds = Int64((date.timeIntervalSince1970 * 1000).rounded())
        let fraction = (milliseconds % 1000 + 1000) % 1000
        let second = Date(timeIntervalSince1970: TimeInterval(milliseconds - fraction) / 1000)
        return Date.ISO8601FormatStyle().format(second).dropLast() + "." + String(fraction + 1000).dropFirst() + "Z"
    }

    private static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            do {
                return try Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(text)
            } catch {
                throw DecodingError.dataCorrupted(
                    .init(codingPath: decoder.codingPath, debugDescription: "'\(text)' is not an ISO-8601 instant")
                )
            }
        }
        return decoder
    }

    private static func undecodable(_ error: any Error, route: String) -> Undecodable {
        switch error {
        case let DecodingError.keyNotFound(key, context):
            Undecodable(route: route, field: field(context.codingPath + [key]), reason: "it is missing")
        case let DecodingError.valueNotFound(_, context):
            Undecodable(route: route, field: field(context.codingPath), reason: "it is null")
        case let DecodingError.typeMismatch(type, context):
            Undecodable(route: route, field: field(context.codingPath), reason: "it is not \(type)")
        case let DecodingError.dataCorrupted(context):
            Undecodable(route: route, field: field(context.codingPath), reason: context.debugDescription)
        default:
            Undecodable(route: route, field: "(body)", reason: "\(error)")
        }
    }

    /// `path` as the server spells it: `rooms[0].created_at`.
    private static func field(_ path: [any CodingKey]) -> String {
        guard !path.isEmpty else { return "(body)" }
        return path.reduce(into: "") { field, key in
            if let index = key.intValue {
                field += "[\(index)]"
            } else {
                field += (field.isEmpty ? "" : ".") + key.stringValue
            }
        }
    }
}
