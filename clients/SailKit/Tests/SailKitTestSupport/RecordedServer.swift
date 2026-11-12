import Foundation
import SailAPI

/// A server that answers each request with the golden recorded for its route, and keeps every
/// request it was sent.
public actor RecordedServer: Transport {
    private let answers: [String: HTTPResponse]
    public private(set) var requests: [HTTPRequest] = []

    public init(_ exchanges: [Exchange]) {
        answers = Dictionary(
            exchanges.map { ("\($0.method) \($0.path)", HTTPResponse(status: $0.status, body: $0.body)) },
            uniquingKeysWith: { first, _ in first }
        )
    }

    public func send(_ request: HTTPRequest) throws(SailError) -> HTTPResponse {
        requests.append(request)
        guard let answer = answers[request.route] else { throw .unreachable("no golden answers \(request.route)") }
        return answer
    }

    public func open(_ request: HTTPRequest) throws(SailError) -> HTTPStream {
        requests.append(request)
        throw .unreachable("no golden streams \(request.route)")
    }
}
