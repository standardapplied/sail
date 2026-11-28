#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import SailAPI
import SailKitTestSupport
import Synchronization
import Testing

/// Chats read on the long-poll transport never take a connection another call waits for: eight
/// reads held open by a real loopback server, which counts the connections it holds, and the
/// project listing on the app's other transport still answers while all eight hold.
@Suite(.timeLimit(.minutes(1))) struct LongPollTests {
    @Test func eightHeldReadsNeverDelayAProjectListing() async throws {
        let server = try HoldingServer()
        let base = try #require(URL(string: "http://127.0.0.1:\(server.port)"))
        let polls = SailClient(transport: URLSessionTransport.longPoll(base: base), token: "tok")
        let calls = SailClient(transport: URLSessionTransport(base: base), token: "tok")
        let reads = (1 ... 8).map { chat in Task { try await polls.send(.chat("chat-\(chat)", after: 0)) } }
        defer { reads.forEach { $0.cancel() } }
        await server.holding(8)
        let listed = try await calls.send(.projects)
        #expect(listed.projects.isEmpty && server.held == 8)
        server.release()
        for read in reads {
            #expect(try await read.value.items.isEmpty)
        }
    }

    @Test func aLongPollWaitsPastTheServersHold() {
        #expect(URLSessionTransport.longPolls.timeoutIntervalForRequest > 25)
    }
}

/// A loopback HTTP server that holds every chat read until ``release()``, then answers each with no
/// items, and answers any other request at once with no projects; each on a thread of its own.
final class HoldingServer: Sendable {
    let port: UInt16
    private let listener: HeldListener
    private let holds = Holds()

    private final class Holds: Sendable {
        struct State {
            var clients: [Int32] = []
            var watchers: [(count: Int, waiter: CheckedContinuation<Void, Never>)] = []
        }

        let state = Mutex(State())

        func hold(_ client: Int32) {
            let reached = state.withLock { state in
                state.clients.append(client)
                let count = state.clients.count
                defer { state.watchers.removeAll { $0.count <= count } }
                return state.watchers.filter { $0.count <= count }.map(\.waiter)
            }
            reached.forEach { $0.resume() }
        }

        func take() -> [Int32] {
            state.withLock { state in
                defer { state.clients = [] }
                return state.clients
            }
        }
    }

    init() throws {
        let listener = try HeldListener.loopback()
        port = listener.port
        self.listener = listener
        Thread { [holds] in
            while true {
                switch listener.next() {
                case let .connection(client): Thread { Self.serve(client, holds) }.start()
                case .nothing: continue
                case .failed, .closed: return
                }
            }
        }.start()
    }

    deinit {
        listener.close()
        holds.take().forEach { _ = close($0) }
    }

    var held: Int {
        holds.state.withLock { $0.clients.count }
    }

    /// Returns once it holds `count` reads, or the waiting test is cancelled.
    func holding(_ count: Int) async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { waiter in
                let reached = holds.state.withLock { state in
                    let reached = state.clients.count >= count || Task.isCancelled
                    if !reached { state.watchers.append((count, waiter)) }
                    return reached
                }
                if reached { waiter.resume() }
            }
        } onCancel: {
            holds.state.withLock { state in
                defer { state.watchers = [] }
                return state.watchers.map(\.waiter)
            }.forEach { $0.resume() }
        }
    }

    func release() {
        for client in holds.take() {
            Self.answer(client, #"{"ended":false,"items":[],"schema_version":1}"#)
        }
    }

    private static func serve(_ client: Int32, _ holds: Holds) {
        var received: [UInt8] = []
        var buffer = [UInt8](repeating: 0, count: 4096)
        while !String(decoding: received, as: UTF8.self).contains("\r\n\r\n") {
            let count = read(client, &buffer, buffer.count)
            guard count > 0 else { break }
            received += buffer.prefix(count)
        }
        let line = String(decoding: received, as: UTF8.self).split(separator: "\r\n").first ?? ""
        if line.contains("/chat?") {
            holds.hold(client)
        } else {
            answer(client, #"{"projects":[],"schema_version":1,"total":0}"#)
        }
    }

    private static func answer(_ client: Int32, _ body: String) {
        let reply = Array(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)".utf8
        )
        _ = reply.withUnsafeBytes { write(client, $0.baseAddress, $0.count) }
        close(client)
    }
}
