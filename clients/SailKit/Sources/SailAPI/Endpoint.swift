import Foundation

/// One route of the API and the answer it gives: what to send, and how to read what comes back.
public struct Endpoint<Response: Decodable & Sendable>: Sendable {
    public let request: HTTPRequest

    package init(_ method: String, _ segments: [String], query: [(String, String?)] = [], body: Data? = nil) {
        request = HTTPRequest(method, segments, query: query, body: body)
    }

    /// The answer `response` gives: its body on success, the server's refusal otherwise.
    public func decode(_ response: HTTPResponse) throws(SailError) -> Response {
        try SailJSON.decode(response, route: request.route)
    }
}

/// The server's JSON: each key as its model spells it, and ISO-8601 instants with milliseconds.
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
        } catch let error as DecodingError {
            throw .undecodable(undecodable(error, route: route))
        } catch {
            throw .undecodable(Undecodable(route: route, field: "(body)", reason: "\(error)"))
        }
    }

    package static func encode(_ value: some Encodable) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        do {
            return try encoder.encode(value)
        } catch {
            preconditionFailure("a request body always encodes: \(error)")
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

    private static func undecodable(_ error: DecodingError, route: String) -> Undecodable {
        switch error {
        case let .keyNotFound(key, context):
            Undecodable(route: route, field: field(context.codingPath + [key]), reason: "it is missing")
        case let .valueNotFound(_, context):
            Undecodable(route: route, field: field(context.codingPath), reason: "it is null")
        case let .typeMismatch(type, context):
            Undecodable(route: route, field: field(context.codingPath), reason: "it is not \(type)")
        case let .dataCorrupted(context):
            Undecodable(route: route, field: field(context.codingPath), reason: context.debugDescription)
        @unknown default:
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
