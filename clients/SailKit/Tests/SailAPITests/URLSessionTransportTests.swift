import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import SailAPI
import Synchronization
import Testing

@Suite(.timeLimit(.minutes(1))) struct URLSessionTransportTests {
    let transport = scriptedTransport(base: "https://box.example.com")

    @Test func aRequestReachesTheServerWithItsMethodPathHeadersAndBody() async throws {
        let request = HTTPRequest(
            method: .post, path: "/v1/rooms/harbor-design/messages?limit=2",
            headers: ["Authorization": "Bearer tok"], body: Data(#"{"body":"Aye"}"#.utf8)
        )
        let response = try await transport.send(request)
        #expect(response.status == 201)
        #expect(response.headers["x-echo"] == "yes")
        let echoed = try JSONDecoder().decode([String: String].self, from: response.body)
        #expect(echoed == [
            "method": "POST",
            "url": "https://box.example.com/v1/rooms/harbor-design/messages?limit=2",
            "authorization": "Bearer tok",
            "body": #"{"body":"Aye"}"#,
        ])
    }

    @Test(arguments: ["https://box.example.com/sail", "https://box.example.com/sail/"])
    func aBaseWithAPathKeepsItInFrontOfEveryRequest(base: String) async throws {
        let response = try await scriptedTransport(base: base).send(HTTPRequest(method: .get, path: "/v1/whoami"))
        let echoed = try JSONDecoder().decode([String: String].self, from: response.body)
        #expect(echoed["url"] == "https://box.example.com/sail/v1/whoami")
    }

    @Test func aPathThatLooksLikeAnotherServerStaysOnThisOne() async throws {
        let response = try await transport.send(HTTPRequest(method: .get, path: "//evil.example.com/x"))
        let echoed = try JSONDecoder().decode([String: String].self, from: response.body)
        #expect(echoed["url"] == "https://box.example.com//evil.example.com/x")
    }

    @Test(arguments: ["", "v1/whoami", "a:b:c", "https://evil.example.com/x"])
    func aPathThatDoesNotStartAtTheRootIsUnsendableNotATrap(path: String) async {
        let request = HTTPRequest(method: .get, path: path)
        let unsendable = SailError.unsendable("'\(path)' is not a path under https://box.example.com")
        await #expect(throws: unsendable) { try await transport.send(request) }
        await #expect(throws: unsendable) { try await transport.open(request) }
    }

    @Test func aStreamYieldsItsHeadAndItsBodyAsItArrives() async throws {
        let stream = try await transport.open(HTTPRequest(method: .get, path: "/stream/whole"))
        #expect(stream.status == 201)
        #expect(stream.headers["x-echo"] == "yes")
        var chunks: [[UInt8]] = []
        for try await chunk in stream.body {
            chunks.append(chunk)
        }
        #expect(chunks.flatMap(\.self) == Array(ScriptedProtocol.streamed.utf8))
    }

    @Test func aServerThatCannotBeReachedIsUnreachable() async {
        let request = HTTPRequest(method: .get, path: "/unreachable")
        await #expect(throws: SailError.self) { try await transport.open(request) }
        do throws(SailError) {
            _ = try await transport.send(request)
            Issue.record("the request was answered")
        } catch {
            guard case let .unreachable(reason) = error else {
                Issue.record("\(error)")
                return
            }
            #expect(reason.hasPrefix("https://box.example.com: "))
        }
    }

    @Test func aStreamThatBreaksAfterItsHeadYieldsWhatArrivedAndThenFails() async throws {
        let stream = try await transport.open(HTTPRequest(method: .get, path: "/break"))
        var chunks: [[UInt8]] = []
        await #expect(throws: (any Error).self) {
            for try await chunk in stream.body {
                chunks.append(chunk)
            }
        }
        #expect(chunks.flatMap(\.self) == Array(ScriptedProtocol.streamed.utf8))
    }

    @Test func aReaderCancelledWhileItWaitsForTheHeadReleasesTheConnection() async {
        let request = HTTPRequest(method: .get, path: "/silent")
        let opening = Task { [transport] in try await transport.open(request) }
        await Signals.shared.wait(for: "start /silent")
        opening.cancel()
        await Signals.shared.wait(for: "stop /silent")
        await #expect(throws: SailError.self) { try await opening.value }
        #expect(Signals.shared.count(of: "stop /silent") == 1)
    }

    @Test func aStreamNobodyReadsReleasesItsConnectionWhenItIsDropped() async throws {
        _ = try await transport.open(HTTPRequest(method: .get, path: "/hang/unread"))
        await Signals.shared.wait(for: "stop /hang/unread")
        #expect(Signals.shared.count(of: "stop /hang/unread") == 1)
    }

    @Test func aReaderThatStopsPartWayReleasesTheConnection() async throws {
        do {
            let stream = try await transport.open(HTTPRequest(method: .get, path: "/hang/part-read"))
            var chunks = stream.body.makeAsyncIterator()
            #expect(try await chunks.next() == Array(ScriptedProtocol.streamed.utf8))
        }
        await Signals.shared.wait(for: "stop /hang/part-read")
        #expect(Signals.shared.count(of: "stop /hang/part-read") == 1)
    }

    @Test func aHeadThatArrivesBeforeItsCallerWaitsIsHandedOverAndALaterOneIgnored() async throws {
        let head = Handoff<Int>()
        head.answer(.success(200))
        head.answer(.success(500))
        head.answer(.failure(URLError(.cancelled)))
        #expect(try await head.value() == 200)
        #expect(try await head.value() == 200)
        let failed = Handoff<Int>()
        failed.answer(.failure(URLError(.cancelled)))
        await #expect(throws: URLError.self) { try await failed.value() }
    }

    @Test func aReaderThatFallsBehindReadsWhatWasHeldAndThenLosesTheStream() async throws {
        let ended = Mutex(0)
        let (backlog, body) = ChunkBacklog.make(limit: 3) { ended.withLock { $0 += 1 } }
        for chunk in UInt8(1) ... 5 {
            backlog.receive([chunk])
        }
        #expect(ended.withLock { $0 } == 1)
        var held: [[UInt8]] = []
        await #expect(throws: SailError.unreachable("the stream's reader fell 3 chunks behind")) {
            for try await chunk in body {
                held.append(chunk)
            }
        }
        #expect(held == [[1], [2], [3]])
    }

    @Test func aReaderThatKeepsUpReadsEveryChunkToTheEnd() async throws {
        let (backlog, body) = ChunkBacklog.make(limit: 3) {}
        var chunks = body.makeAsyncIterator()
        var read: [[UInt8]] = []
        for round in UInt8(0) ..< 3 {
            for chunk in UInt8(1) ... 3 {
                backlog.receive([round, chunk])
            }
            for _ in 1 ... 3 {
                read.append(try #require(try await chunks.next()))
            }
        }
        backlog.finish(nil)
        #expect(try await chunks.next() == nil)
        #expect(read.count == 9)
        #expect(URLSessionTransport.streamBacklog == 256)
    }
}

func scriptedTransport(base: String) -> URLSessionTransport {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedProtocol.self]
    return URLSessionTransport(base: URL(string: base) ?? URL(filePath: "/"), configuration: configuration)
}

/// What a scripted connection did, by name, for a test to wait on without watching the clock.
final class Signals: Sendable {
    static let shared = Signals()

    private struct State {
        var counts: [String: Int] = [:]
        var waiting: [String: CheckedContinuation<Void, Never>] = [:]
    }

    private let state = Mutex(State())

    func raise(_ name: String) {
        let waiter = state.withLock { state in
            state.counts[name, default: 0] += 1
            return state.waiting.removeValue(forKey: name)
        }
        waiter?.resume()
    }

    func wait(for name: String) async {
        await withCheckedContinuation { waiter in
            let raised = state.withLock { state in
                if state.counts[name] == nil {
                    state.waiting[name] = waiter
                }
                return state.counts[name] != nil
            }
            if raised {
                waiter.resume()
            }
        }
    }

    func count(of name: String) -> Int {
        state.withLock { $0.counts[name, default: 0] }
    }
}

/// Answers each request in the process, by its path: `/unreachable` fails to connect, `/silent`
/// never answers, `/hang/…` sends a head and a chunk and never ends, `/break` sends them and then
/// fails, `/stream/…` sends them in pieces and ends, and anything else echoes what it was sent.
/// Each start and stop is raised as a signal named for the path.
final class ScriptedProtocol: URLProtocol {
    static let streamed = ": subscribed\n\nid: 1\ndata: {}\n\n"

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 201, httpVersion: "HTTP/1.1", headerFields: ["X-Echo": "yes"])
        else { return }
        defer { Signals.shared.raise("start \(url.path)") }
        let whole = Data(Self.streamed.utf8)
        switch url.path.split(separator: "/").first {
        case "unreachable":
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
        case "silent":
            break
        case "hang":
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: whole)
        case "break":
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: whole)
            client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
        case "stream":
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            for start in stride(from: 0, to: whole.count, by: 5) {
                client?.urlProtocol(self, didLoad: whole[start ..< min(start + 5, whole.count)])
            }
            client?.urlProtocolDidFinishLoading(self)
        default:
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: echo())
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {
        Signals.shared.raise("stop \(request.url?.path ?? "")")
    }

    private func echo() -> Data {
        let echoed = [
            "method": request.httpMethod ?? "",
            "url": request.url?.absoluteString ?? "",
            "authorization": request.value(forHTTPHeaderField: "Authorization") ?? "",
            "body": String(decoding: body(), as: UTF8.self),
        ]
        return (try? JSONEncoder().encode(echoed)) ?? Data()
    }

    private func body() -> Data {
        if let body = request.httpBody {
            return body
        }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }
        return data
    }
}
