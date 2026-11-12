import Foundation

/// The methods this client sends.
public enum HTTPMethod: String, Sendable {
    case get = "GET"
    case post = "POST"
}

/// A request to the server, relative to wherever a ``Transport`` reaches it.
public struct HTTPRequest: Equatable, Sendable {
    public var method: HTTPMethod
    /// The path and query, percent-encoded and starting at the root: `/v1/rooms?project=harbor`.
    public var path: String
    public var headers: [String: String]
    public var body: Data?

    public init(method: HTTPMethod, path: String, headers: [String: String] = [:], body: Data? = nil) {
        self.method = method
        self.path = path
        self.headers = headers
        self.body = body
    }

    /// A request for `segments` and `query`, every character of each but ASCII letters, digits,
    /// `-`, `_` and `~` percent-encoded: a segment never reads as another segment, `.` or `..`, and
    /// a `+` never reaches the server's form decoding as a space. A nil query value is left out.
    package init(_ method: HTTPMethod, _ segments: [String], query: [(String, String?)] = [], body: Data? = nil) {
        let path = segments.map { "/" + Self.escaped($0) }.joined()
        let parameters = query.compactMap { name, value in value.map { "\(Self.escaped(name))=\(Self.escaped($0))" } }
        self.init(
            method: method,
            path: parameters.isEmpty ? path : path + "?" + parameters.joined(separator: "&"),
            headers: body == nil ? [:] : ["Content-Type": "application/json"],
            body: body
        )
    }

    private static func escaped(_ text: String) -> String {
        text.utf8.map { byte in
            unescaped.contains(byte)
                ? String(UnicodeScalar(byte))
                : "%" + String(byte >> 4, radix: 16, uppercase: true) + String(byte & 0xF, radix: 16, uppercase: true)
        }.joined()
    }

    private static let unescaped = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_~".utf8)

    /// `method path`, as an error names the route that gave it.
    public var route: String {
        "\(method.rawValue) \(path)"
    }
}

/// A whole answer. Header names are lowercased.
public struct HTTPResponse: Equatable, Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data

    public init(status: Int, headers: [String: String] = [:], body: Data) {
        self.status = status
        self.headers = headers
        self.body = body
    }
}

/// An answer whose body arrives as it is written, in whatever chunks the connection delivers.
/// Header names are lowercased.
public struct HTTPStream: Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: AsyncThrowingStream<[UInt8], any Error>

    public init(status: Int, headers: [String: String] = [:], body: AsyncThrowingStream<[UInt8], any Error>) {
        self.status = status
        self.headers = headers
        self.body = body
    }
}

/// How requests reach a server: ``URLSessionTransport`` on a device, a recorded server in tests.
/// A failure to reach the server is ``SailError/unreachable(_:)``; any answer, a refusal
/// included, is returned for the caller to read.
public protocol Transport: Sendable {
    func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse
    func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream
}
