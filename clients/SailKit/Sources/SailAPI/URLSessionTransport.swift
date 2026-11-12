import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Synchronization

/// A ``Transport`` over `URLSession`. A stream is read through a data delegate rather than
/// `bytes(for:)`, which Linux's `URLSession` does not have, so one adapter serves every platform.
public struct URLSessionTransport: Transport {
    private let base: URL
    private let configuration: URLSessionConfiguration
    private let session: URLSession

    /// Requests reach the server at `base`, `https://box.example.com` say.
    public init(base: URL, configuration: URLSessionConfiguration = .default) {
        self.base = base
        self.configuration = configuration
        session = URLSession(configuration: configuration)
    }

    public func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        let (body, response) = try await Self.reaching(base) { try await session.data(for: urlRequest(request)) }
        return HTTPResponse(status: response.status, headers: response.headers, body: body)
    }

    public func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        let (body, chunks) = AsyncThrowingStream.makeStream(of: [UInt8].self)
        let reader = StreamReader(chunks: chunks)
        let session = URLSession(configuration: configuration, delegate: reader, delegateQueue: nil)
        let task = session.dataTask(with: urlRequest(request))
        chunks.onTermination = { _ in
            task.cancel()
            session.finishTasksAndInvalidate()
        }
        task.resume()
        let head = try await Self.reaching(base) {
            try await withTaskCancellationHandler { try await reader.head() } onCancel: { task.cancel() }
        }
        return HTTPStream(status: head.status, headers: head.headers, body: body)
    }

    func urlRequest(_ request: HTTPRequest) -> URLRequest {
        guard let target = URL(string: request.path, relativeTo: base) else {
            preconditionFailure("'\(request.path)' is not a percent-encoded path; build requests with Endpoint")
        }
        var url = URLRequest(url: target.absoluteURL)
        url.httpMethod = request.method.rawValue
        url.httpBody = request.body
        for (name, value) in request.headers {
            url.setValue(value, forHTTPHeaderField: name)
        }
        return url
    }

    private static func reaching<Answer>(
        _ base: URL, _ call: () async throws -> Answer
    ) async throws(SailError) -> Answer {
        do {
            return try await call()
        } catch {
            throw .unreachable("\(base.absoluteString): \(error.localizedDescription)")
        }
    }
}

/// A response's status and headers, names lowercased.
struct Head: Sendable {
    let status: Int
    let headers: [String: String]
}

private extension URLResponse {
    var status: Int {
        (self as? HTTPURLResponse)?.statusCode ?? 0
    }

    var headers: [String: String] {
        let fields = (self as? HTTPURLResponse)?.allHeaderFields ?? [:]
        return Dictionary(
            fields.map { ("\($0.key)".lowercased(), "\($0.value)") }, uniquingKeysWith: { first, _ in first }
        )
    }
}

/// The delegate of one streamed request: its head resumes whoever waits for it once, and every
/// chunk of its body is yielded as it arrives.
private final class StreamReader: NSObject, URLSessionDataDelegate, Sendable {
    private enum Waiting {
        case nobody
        case caller(CheckedContinuation<Head, any Error>)
        case answered(Result<Head, any Error>)
    }

    private let chunks: AsyncThrowingStream<[UInt8], any Error>.Continuation
    private let waiting = Mutex(Waiting.nobody)

    init(chunks: AsyncThrowingStream<[UInt8], any Error>.Continuation) {
        self.chunks = chunks
    }

    func head() async throws -> Head {
        try await withCheckedThrowingContinuation { caller in
            let answered: Result<Head, any Error>? = waiting.withLock { waiting in
                guard case let .answered(result) = waiting else {
                    waiting = .caller(caller)
                    return nil
                }
                return result
            }
            if let answered {
                caller.resume(with: answered)
            }
        }
    }

    private func answer(_ result: Result<Head, any Error>) {
        let caller: CheckedContinuation<Head, any Error>? = waiting.withLock { waiting in
            switch waiting {
            case .nobody:
                waiting = .answered(result)
                return nil
            case let .caller(caller):
                waiting = .answered(result)
                return caller
            case .answered:
                return nil
            }
        }
        caller?.resume(with: result)
    }

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void
    ) {
        answer(.success(Head(status: response.status, headers: response.headers)))
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        chunks.yield([UInt8](data))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        answer(.failure(error ?? URLError(.badServerResponse)))
        if let error {
            chunks.finish(throwing: error)
        } else {
            chunks.finish()
        }
        session.finishTasksAndInvalidate()
    }
}
