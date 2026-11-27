/// The API as one caller: every request it sends carries the caller's bearer token.
public struct SailClient: Sendable {
    package let transport: any Transport
    private let token: String
    private let ended: @Sendable () -> Void

    /// `ended` is told each time the server says the session `token` names has ended.
    public init(transport: any Transport, token: String, ended: @escaping @Sendable () -> Void = {}) {
        self.transport = transport
        self.token = token
        self.ended = ended
    }

    /// The answer `endpoint` gives this caller.
    public func send<Response>(_ endpoint: Endpoint<Response>) async throws(SailError) -> Response {
        try await positioned(endpoint).response
    }

    /// The answer `endpoint` gives this caller, with the position it was read at when the route
    /// names one: a list or a detail of a row the change feed serves, to follow the feed from. A
    /// refusal saying the session has ended is the one place this is decided for every call: it is
    /// ``SignIn/ended``, in Sail's words, and `ended` is told.
    public func positioned<Response>(
        _ endpoint: Endpoint<Response>
    ) async throws(SailError) -> (response: Response, position: String?) {
        let answer = try await transport.send(authorized(endpoint.request))
        do throws(SailError) {
            return (try endpoint.decode(answer), answer.position)
        } catch let .refused(refusal) where Self.endsTheSession.contains(refusal.code) {
            ended()
            throw .signIn(.ended)
        }
    }

    /// The codes with which the server says the session a call was asked with has ended.
    private static let endsTheSession: Set<Open<ErrorCode>> = [.known(.invalidBearerToken), .known(.missingBearerToken)]

    /// `request` as this caller sends it.
    package func authorized(_ request: HTTPRequest) -> HTTPRequest {
        var request = request
        request.headers["Authorization"] = "Bearer \(token)"
        return request
    }
}
