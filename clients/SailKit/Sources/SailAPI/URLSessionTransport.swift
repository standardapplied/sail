import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Synchronization

/// A ``Transport`` over one `URLSession`, which lives as long as a copy of the transport or a
/// request it sent does. A stream is read through the session's data delegate rather than
/// `bytes(for:)`, which Linux's `URLSession` does not have, so one adapter serves every platform.
public struct URLSessionTransport: Transport {
    /// How many chunks of a stream wait for a reader that has fallen behind. One more and the
    /// stream fails, since dropping a chunk would corrupt everything after it.
    package static let streamBacklog = 256

    private let root: String
    private let connection: Connection

    /// Requests reach the server at `base`, `https://box.example.com` say; a path in `base` is
    /// kept in front of every request's.
    public init(base: URL, configuration: URLSessionConfiguration = .default) {
        let base = base.absoluteString
        root = base.hasSuffix("/") ? String(base.dropLast()) : base
        connection = Connection(configuration: configuration)
    }

    public func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        let request = try urlRequest(request)
        let (body, response) = try await reaching { try await connection.session.data(for: request) }
        return HTTPResponse(status: response.status, headers: response.headers, body: body)
    }

    public func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        let task = connection.session.dataTask(with: try urlRequest(request))
        let (chunks, body) = ChunkBacklog.make(limit: Self.streamBacklog) { task.cancel() }
        let reader = StreamReader(chunks: chunks)
        connection.streams.read(task, with: reader)
        task.resume()
        let head = try await reaching {
            try await withTaskCancellationHandler { try await reader.head.value() } onCancel: { task.cancel() }
        }
        return HTTPStream(status: head.status, headers: head.headers, body: body)
    }

    /// `request` at this transport's server. Its path is appended to the base, never resolved
    /// against it, so no path leaves the server or the base's own path.
    func urlRequest(_ request: HTTPRequest) throws(SailError) -> URLRequest {
        guard request.path.hasPrefix("/"), let target = URL(string: root + request.path) else {
            throw .unsendable("'\(request.path)' is not a path under \(root)")
        }
        var url = URLRequest(url: target)
        url.httpMethod = request.method.rawValue
        url.httpBody = request.body
        for (name, value) in request.headers {
            url.setValue(value, forHTTPHeaderField: name)
        }
        return url
    }

    private func reaching<Answer>(_ call: () async throws -> Answer) async throws(SailError) -> Answer {
        do {
            return try await call()
        } catch {
            throw .unreachable("\(root): \(error.localizedDescription)")
        }
    }
}

/// The session a transport's copies share, invalidated when the last of them goes; a stream still
/// being read then runs to its end.
private final class Connection: Sendable {
    let session: URLSession
    let streams = Streams()

    init(configuration: URLSessionConfiguration) {
        session = URLSession(configuration: configuration, delegate: streams, delegateQueue: nil)
    }

    deinit {
        session.finishTasksAndInvalidate()
    }
}

/// A response's status and headers, names lowercased.
private struct Head: Sendable {
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

/// The session's delegate: hands what arrives for a streamed task to that task's reader.
private final class Streams: NSObject, URLSessionDataDelegate, Sendable {
    private let readers = Mutex<[Int: StreamReader]>([:])

    func read(_ task: URLSessionTask, with reader: StreamReader) {
        readers.withLock { $0[task.taskIdentifier] = reader }
    }

    private func reader(of task: URLSessionTask) -> StreamReader? {
        readers.withLock { $0[task.taskIdentifier] }
    }

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void
    ) {
        reader(of: dataTask)?.head.answer(.success(Head(status: response.status, headers: response.headers)))
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        reader(of: dataTask)?.chunks.receive([UInt8](data))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        readers.withLock { $0.removeValue(forKey: task.taskIdentifier) }?.finish(error)
    }
}

/// One streamed request as its caller reads it: its head, handed over once, and the chunks of its
/// body, queued as they arrive.
private struct StreamReader: Sendable {
    let head = Handoff<Head>()
    let chunks: ChunkBacklog

    func finish(_ error: (any Error)?) {
        head.answer(.failure(error ?? URLError(.badServerResponse)))
        chunks.finish(error)
    }
}

/// A value one side hands the other exactly once, whichever arrives first: the answer, or the
/// caller waiting for it. An answer after the first is ignored.
package final class Handoff<Value: Sendable>: Sendable {
    private enum State {
        case nobody
        case caller(CheckedContinuation<Value, any Error>)
        case answered(Result<Value, any Error>)
    }

    private let state = Mutex(State.nobody)

    package init() {}

    package func value() async throws -> Value {
        try await withCheckedThrowingContinuation { caller in
            let answered: Result<Value, any Error>? = state.withLock { state in
                guard case let .answered(result) = state else {
                    state = .caller(caller)
                    return nil
                }
                return result
            }
            if let answered {
                caller.resume(with: answered)
            }
        }
    }

    package func answer(_ result: Result<Value, any Error>) {
        let caller: CheckedContinuation<Value, any Error>? = state.withLock { state in
            switch state {
            case .nobody:
                state = .answered(result)
                return nil
            case let .caller(caller):
                state = .answered(result)
                return caller
            case .answered:
                return nil
            }
        }
        caller?.resume(with: result)
    }
}

/// The writing end of a stream's body: its chunks are held for the reader as they arrive, `limit`
/// of them at most. A reader that falls further behind loses the stream: it reads the chunks held
/// and then the failure, since a chunk dropped from the middle would corrupt everything after it.
package struct ChunkBacklog: Sendable {
    private let chunks: AsyncThrowingStream<[UInt8], any Error>.Continuation
    private let limit: Int

    /// A backlog and the body its reader reads. `onEnd` runs once, when the body fails, finishes,
    /// or loses its reader, which is why the backlog does not hold the body itself.
    package static func make(
        limit: Int, onEnd: @escaping @Sendable () -> Void
    ) -> (backlog: ChunkBacklog, body: AsyncThrowingStream<[UInt8], any Error>) {
        let (body, chunks) = AsyncThrowingStream.makeStream(of: [UInt8].self, bufferingPolicy: .bufferingOldest(limit))
        chunks.onTermination = { _ in onEnd() }
        return (ChunkBacklog(chunks: chunks, limit: limit), body)
    }

    package func receive(_ chunk: [UInt8]) {
        if case .dropped = chunks.yield(chunk) {
            chunks.finish(throwing: SailError.unreachable("the stream's reader fell \(limit) chunks behind"))
        }
    }

    package func finish(_ error: (any Error)?) {
        chunks.finish(throwing: error)
    }
}
