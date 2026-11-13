/// The API as one caller: every request it sends carries the caller's bearer token.
public struct SailClient: Sendable {
    package let transport: any Transport
    private let token: String

    public init(transport: any Transport, token: String) {
        self.transport = transport
        self.token = token
    }

    /// The answer `endpoint` gives this caller.
    public func send<Response>(_ endpoint: Endpoint<Response>) async throws(SailError) -> Response {
        try await positioned(endpoint).response
    }

    /// The answer `endpoint` gives this caller, with the position it was read at when the route
    /// names one: a list or a detail of a row the change feed serves, to follow the feed from.
    public func positioned<Response>(
        _ endpoint: Endpoint<Response>
    ) async throws(SailError) -> (response: Response, position: String?) {
        let answer = try await transport.send(authorized(endpoint.request))
        return (try endpoint.decode(answer), answer.position)
    }

    /// `request` as this caller sends it.
    package func authorized(_ request: HTTPRequest) -> HTTPRequest {
        var request = request
        request.headers["Authorization"] = "Bearer \(token)"
        return request
    }
}
