#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import SailKitTestSupport
@testable import SailTerminal
import SailTerminalWire
import Synchronization

/// A terminal host played by a test: it listens on a unix socket of its own, or on a loopback TCP
/// port, and each connection it accepts is a ``Peer`` the test reads and answers frame by frame.
/// A wait for a client is a wait for what the client does, ended only by the test's cancellation,
/// which its suite's time limit brings: a test that is not scheduled for a while, whatever holds
/// it, waits on, rather than failing on the clock.
final class FakeHost: Sendable {
    let address: HostAddress
    private let listener: Mutex<HeldListener>
    private let directory: String?

    /// A host on a unix socket in a new directory of its own.
    init() throws {
        let directory = try Self.temporaryDirectory()
        let path = directory + "/h.sock"
        self.directory = directory
        address = .unix(path: path)
        listener = Mutex(try HeldListener.unix(path))
    }

    /// A host on an ephemeral loopback TCP port.
    init(tcp: Void) throws {
        directory = nil
        let held = try HeldListener.loopback()
        address = .tcp(host: "127.0.0.1", port: held.port)
        listener = Mutex(held)
    }

    deinit {
        stop()
        if let directory { try? FileManager.default.removeItem(atPath: directory) }
    }

    /// The next connection a client makes.
    func accept() async throws -> Peer {
        try await untilCancelled("no client connected") { [self] in
            switch listener.withLock({ $0 }).next() {
            case let .connection(accepted): return Peer(socket: Socket(descriptor: accepted), descriptor: accepted)
            case .nothing: return nil
            case let .failed(code): throw HostError("accept failed: \(code)")
            case .closed: throw HostError("the host stopped listening")
            }
        }
    }

    /// Stops listening: a client's connect is refused from now on.
    func stop() {
        listener.withLock { $0 }.close()
        if case let .unix(path) = address { unlink(path) }
    }

    /// Listens again where it listened before.
    func restart() throws {
        guard case let .unix(path) = address else { throw HostError("only a unix host restarts") }
        let fresh = try HeldListener.unix(path)
        listener.withLock { $0 = fresh }
    }

    /// One accepted connection, which the test drives. What the client sends is read as it comes, on
    /// a thread of the peer's own, so a client's write never waits for the test to be scheduled.
    final class Peer: Sendable {
        /// The accepted descriptor's number, for a test of the close.
        let descriptor: Int32
        private let open: Mutex<Socket?>
        private let inbox = Inbox()

        init(socket: Socket, descriptor: Int32) {
            open = Mutex(socket)
            self.descriptor = descriptor
            Thread { [inbox] in inbox.drain(descriptor) }.start()
        }

        deinit {
            let socket = open.withLock { $0 }
            inbox.stop("the peer was dropped") { socket?.shutdown() }
        }

        private var socket: Socket {
            get throws {
                guard let socket = open.withLock({ $0 }) else { throw HostError("the peer was closed") }
                return socket
            }
        }

        /// Answers the client's magic with the host's and its `Hello` with a `Welcome` of `bootID`:
        /// the token the client presented.
        @discardableResult
        func welcome(bootID: String = "boot-1", magic: [UInt8] = Handshake.magic) async throws -> String {
            let token = try await hello(magic: magic)
            try send(.welcome(hostBootID: bootID))
            return token
        }

        /// Answers the magic and reads the client's `Hello`: its token.
        func hello(magic: [UInt8] = Handshake.magic) async throws -> String {
            try socket.send(magic)
            let theirs = try await inbox.magic()
            guard theirs == Handshake.magic else { throw HostError("the client sent \(theirs) for the magic") }
            guard case let .hello(token) = try await read() else { throw HostError("the client did not say hello") }
            return token
        }

        /// The next frame the client sent.
        func read() async throws -> Frame {
            try await inbox.frame()
        }

        /// Reads until the client closes the connection, the frames it sent meanwhile.
        func readToEnd() async throws -> [Frame] {
            var frames: [Frame] = []
            while true {
                do {
                    frames.append(try await read())
                } catch let error as HostError where error.reason == "the client closed the connection" {
                    return frames
                }
            }
        }

        /// The next frame the client sent that is not input: the input before it is dropped.
        func control() async throws -> Frame {
            while true {
                let frame = try await read()
                if case .input = frame { continue }
                return frame
            }
        }

        func send(_ frame: Frame) throws {
            try socket.send(try FrameCodec.encode(frame))
        }

        func send(_ frames: [Frame]) throws {
            try socket.send(try frames.flatMap { try FrameCodec.encode($0) })
        }

        func send(raw bytes: [UInt8]) throws {
            try socket.send(bytes)
        }

        /// Ends the connection as the host does: shut down, then closed with the last reference to
        /// its socket, which the peer's reader gives up before this returns. A shutdown alone would
        /// leave the socket open, and on macOS it does not always wake a client blocked reading a
        /// unix socket.
        func close() {
            let socket = open.withLock { open in
                defer { open = nil }
                return open
            }
            inbox.stop("the peer was closed") { socket?.shutdown() }
        }
    }

    /// What a client sent: its magic, then its frames, then how its connection ended. Read by one
    /// thread, which a glance at a time looks whether it is still wanted, straight from the
    /// descriptor, so the peer's socket, and with it the descriptor, is the peer's alone to close;
    /// waited on by the test.
    private final class Inbox: Sendable {
        private struct State {
            var magic: [UInt8] = []
            var frames: [Frame] = []
            var end: (any Error & Sendable)?
            var stopped = false
        }

        private let state = Mutex(State())
        private let arrived = Alarm { _ in }
        private let drained = DispatchSemaphore(value: 0)

        func drain(_ descriptor: Int32) {
            defer { drained.signal() }
            var reader = FrameReader()
            var buffer = [UInt8](repeating: 0, count: 64 * 1024)
            while !state.withLock({ $0.stopped }) {
                guard readable(descriptor) else { continue }
                let count = buffer.withUnsafeMutableBytes { recv(descriptor, $0.baseAddress, $0.count, 0) }
                if count < 0, errno == EINTR { continue }
                guard count >= 0 else { return end(HostError("a read failed: \(String(cString: strerror(errno)))")) }
                guard count > 0 else { return end(HostError("the client closed the connection")) }
                let bytes = buffer[..<count]
                let wanted = state.withLock { state in
                    let wanted = min(Handshake.magic.count - state.magic.count, bytes.count)
                    state.magic += bytes.prefix(wanted)
                    return wanted
                }
                let frames: [Frame]
                do throws(TerminalWireError) {
                    frames = try reader.read(bytes.dropFirst(wanted))
                } catch {
                    return end(error)
                }
                state.withLock { $0.frames += frames }
                arrived.ring()
            }
        }

        /// Stops the reader and returns once it has let go of the descriptor: `wake` (the socket's
        /// own shutdown) ends its wait at once, so the caller, often a task on the shared pool, is
        /// not held for a glance. The end is `reason` unless the connection had ended already.
        func stop(_ reason: String, wake: () -> Void) {
            let first = state.withLock { state in
                defer { state.stopped = true }
                if state.end == nil { state.end = HostError(reason) }
                return !state.stopped
            }
            arrived.ring()
            wake()
            if first { drained.wait() }
        }

        func magic() async throws -> [UInt8] {
            try await next("magic") { state in state.magic.count == Handshake.magic.count ? state.magic : nil }
        }

        func frame() async throws -> Frame {
            try await next("frame") { state in state.frames.isEmpty ? nil : state.frames.removeFirst() }
        }

        private func end(_ end: any Error & Sendable) {
            state.withLock { if $0.end == nil { $0.end = end } }
            arrived.ring()
        }

        private func next<Value: Sendable>(_ what: String, _ take: (inout State) -> Value?) async throws -> Value {
            while true {
                let (value, end) = state.withLock { state in (take(&state), state.end) }
                if let value { return value }
                if let end { throw end }
                guard !Task.isCancelled else { throw HostError("no \(what) came from the client before the test was cancelled") }
                await arrived.wait(nil)
            }
        }
    }

    private static func temporaryDirectory() throws -> String {
        var template = Array("/tmp/sailkit-XXXXXX".utf8CString)
        guard let made = mkdtemp(&template) else { throw HostError("no temporary directory: \(errno)") }
        return String(cString: made)
    }
}

struct HostError: Error, CustomStringConvertible {
    let reason: String

    init(_ reason: String) {
        self.reason = reason
    }

    var description: String {
        reason
    }
}

/// How long a reader of a fake looks at once before it looks whether it is still wanted, in
/// milliseconds: a cadence, never a limit on what it waits for.
let glance: Int32 = 100

/// Whether `descriptor` has something to read, or has ended, within a glance.
func readable(_ descriptor: Int32) -> Bool {
    var ready = pollfd(fd: descriptor, events: Int16(POLLIN), revents: 0)
    return poll(&ready, 1, glance) == 1
}

/// `work`, blocking, on a thread of its own.
func onThread<Value: Sendable>(_ work: @escaping @Sendable () throws -> Value) async throws -> Value {
    try await withCheckedThrowingContinuation { result in
        Thread { result.resume(with: Result { try work() }) }.start()
    }
}

/// `attempt`, on a thread of its own, again and again until it answers; each attempt waits at most a
/// glance and answers nil when what it waits for has not come. Only the calling task's cancellation,
/// which its suite's time limit brings, ends the wait unanswered, said as `unanswered`.
func untilCancelled<Value: Sendable>(_ unanswered: String, _ attempt: @escaping @Sendable () throws -> Value?) async throws -> Value {
    let cancelled = Cancelled()
    return try await withTaskCancellationHandler {
        try await onThread {
            while !cancelled.now {
                if let value = try attempt() { return value }
            }
            throw HostError("\(unanswered) before the test was cancelled")
        }
    } onCancel: {
        cancelled.set()
    }
}

/// Whether a task waiting on a thread was cancelled, read from that thread.
private final class Cancelled: Sendable {
    private let flag = Mutex(false)

    var now: Bool {
        flag.withLock { $0 }
    }

    func set() {
        flag.withLock { $0 = true }
    }
}
