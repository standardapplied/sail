#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
@testable import SailTerminal
import SailTerminalWire
import Synchronization

/// A terminal host played by a test: it listens on a unix socket of its own, or on a loopback TCP
/// port, and each connection it accepts is a ``Peer`` the test reads and answers frame by frame.
/// Every wait is bounded by ``deadline`` seconds.
final class FakeHost: Sendable {
    static let deadline = 5

    let address: HostAddress
    private let listener: Mutex<Int32>
    private let directory: String?

    /// A host on a unix socket in a new directory of its own.
    init() throws {
        let directory = try Self.temporaryDirectory()
        let path = directory + "/h.sock"
        self.directory = directory
        address = .unix(path: path)
        listener = Mutex(try Self.listen(unix: path))
    }

    /// A host on an ephemeral loopback TCP port.
    init(tcp: Void) throws {
        directory = nil
        let (descriptor, port) = try Self.listenTCP()
        address = .tcp(host: "127.0.0.1", port: port)
        listener = Mutex(descriptor)
    }

    deinit {
        stop()
        if let directory { try? FileManager.default.removeItem(atPath: directory) }
    }

    /// The next connection a client makes.
    func accept() async throws -> Peer {
        let descriptor = listener.withLock { $0 }
        return try await onThread {
            var ready = pollfd(fd: descriptor, events: Int16(POLLIN), revents: 0)
            guard poll(&ready, 1, Int32(Self.deadline * 1000)) == 1 else { throw HostError("no client connected within \(Self.deadline) s") }
            let accepted = acceptClient(descriptor)
            guard accepted >= 0 else { throw HostError("accept failed: \(errno)") }
            let socket = Socket(descriptor: accepted)
            socket.setReadTimeout(seconds: Self.deadline)
            return Peer(socket: socket)
        }
    }

    /// Stops listening: a client's connect is refused from now on.
    func stop() {
        listener.withLock { descriptor in
            if descriptor >= 0 { close(descriptor) }
            descriptor = -1
        }
        if case let .unix(path) = address { unlink(path) }
    }

    /// Listens again where it listened before.
    func restart() throws {
        guard case let .unix(path) = address else { throw HostError("only a unix host restarts") }
        let descriptor = try Self.listen(unix: path)
        listener.withLock { $0 = descriptor }
    }

    /// One accepted connection, which the test drives.
    final class Peer: Sendable {
        private let socket: Socket
        private let frames = Mutex(FrameReader())
        private let unread = Mutex<[Frame]>([])

        init(socket: Socket) {
            self.socket = socket
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
            let theirs = try await onThread { [socket] in
                var buffer = [UInt8](repeating: 0, count: 8)
                var got: [UInt8] = []
                while got.count < 8 {
                    let count = try socket.receive(into: &buffer)
                    guard count > 0 else { throw HostError("the client closed during the magic") }
                    got += buffer[..<count]
                }
                return got
            }
            guard theirs.prefix(8) == ArraySlice(Handshake.magic) else { throw HostError("the client sent \(theirs) for the magic") }
            guard case let .hello(token) = try await read() else { throw HostError("the client did not say hello") }
            return token
        }

        /// The next frame the client sent.
        func read() async throws -> Frame {
            if let frame = unread.withLock({ $0.isEmpty ? nil : $0.removeFirst() }) { return frame }
            return try await onThread { [self] in
                var buffer = [UInt8](repeating: 0, count: 64 * 1024)
                while true {
                    if let frame = self.unread.withLock({ $0.isEmpty ? nil : $0.removeFirst() }) { return frame }
                    let count = try self.socket.receive(into: &buffer)
                    guard count > 0 else { throw HostError("the client closed the connection") }
                    let read = try self.frames.withLock { try $0.read(buffer[..<count]) }
                    self.unread.withLock { $0 += read }
                }
            }
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

        func close() {
            socket.shutdown()
        }
    }

    private static func temporaryDirectory() throws -> String {
        var template = Array("/tmp/sailkit-XXXXXX".utf8CString)
        guard let made = mkdtemp(&template) else { throw HostError("no temporary directory: \(errno)") }
        return String(cString: made)
    }

    private static func listen(unix path: String) throws -> Int32 {
        let descriptor = socket(AF_UNIX, streamType, 0)
        guard descriptor >= 0 else { throw HostError("no socket: \(errno)") }
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { raw in
            raw.copyBytes(from: path.utf8)
            raw[path.utf8.count] = 0
        }
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        guard bound == 0, startListening(descriptor) == 0 else { throw HostError("cannot listen at \(path): \(errno)") }
        return descriptor
    }

    private static func listenTCP() throws -> (Int32, UInt16) {
        let descriptor = socket(AF_INET, streamType, 0)
        guard descriptor >= 0 else { throw HostError("no socket: \(errno)") }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = 0
        var size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { pointer in
                bind(descriptor, pointer, size) == 0 && getsockname(descriptor, pointer, &size) == 0
            }
        }
        guard bound, startListening(descriptor) == 0 else { throw HostError("cannot listen on loopback: \(errno)") }
        return (descriptor, UInt16(bigEndian: address.sin_port))
    }

    #if canImport(Glibc)
    private static let streamType = Int32(SOCK_STREAM.rawValue)
    #else
    private static let streamType = SOCK_STREAM
    #endif
}

private func acceptClient(_ descriptor: Int32) -> Int32 {
    accept(descriptor, nil, nil)
}

private func startListening(_ descriptor: Int32) -> Int32 {
    listen(descriptor, 16)
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

/// `work`, blocking, on a thread of its own.
func onThread<Value: Sendable>(_ work: @escaping @Sendable () throws -> Value) async throws -> Value {
    try await withCheckedThrowingContinuation { result in
        Thread { result.resume(with: Result { try work() }) }.start()
    }
}
