#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import SailTerminal
import Synchronization

/// A fake's listening socket, which the fake's own threads accept on and its owner closes. An
/// accept looks a glance at a time while it holds the descriptor, and the close takes the same
/// hold, so no accept ever runs on a number already closed and handed to another socket: a thread
/// blocked so would keep that other socket listening after its owner closed it, no process holding
/// it, and take the connection its test expected refused. A close is said before it waits for the
/// hold, so a thread that looks again and again stops looking rather than keep the close waiting.
public final class HeldListener: Sendable {
    /// What one look found.
    public enum Next: Sendable {
        case connection(Int32)
        case nothing
        case failed(Int32)
        case closed
    }

    /// How long one look waits, in milliseconds: a cadence, never a limit.
    private static let glance: Int32 = 100

    /// The loopback TCP port it listens on; 0 for a unix socket.
    public let port: UInt16
    private let held: Mutex<Int32>
    private let closing = Atomic(false)

    private init(_ descriptor: Int32, port: UInt16) {
        held = Mutex(descriptor)
        self.port = port
    }

    /// Listening on an ephemeral loopback TCP port.
    public static func loopback() throws -> HeldListener {
        let descriptor = Descriptors.socket(AF_INET)
        guard descriptor >= 0 else { throw ListenerError("no socket: \(errno)") }
        var address = sockaddr_in()
        #if canImport(Darwin)
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        #endif
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        var size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(descriptor, $0, size) == 0 && getsockname(descriptor, $0, &size) == 0 }
        }
        return try listening(descriptor, bound: bound, port: UInt16(bigEndian: address.sin_port), naming: "loopback")
    }

    /// Listening at the unix socket `path`.
    public static func unix(_ path: String) throws -> HeldListener {
        let descriptor = Descriptors.socket(AF_UNIX)
        guard descriptor >= 0 else { throw ListenerError("no socket: \(errno)") }
        var address = sockaddr_un()
        guard path.utf8.count < MemoryLayout.size(ofValue: address.sun_path) else {
            closeDescriptor(descriptor)
            throw ListenerError("\(path) is longer than a unix socket's path can be")
        }
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { raw in
            raw.copyBytes(from: path.utf8)
            raw[path.utf8.count] = 0
        }
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) == 0 }
        }
        return try listening(descriptor, bound: bound, port: 0, naming: path)
    }

    private static func listening(_ descriptor: Int32, bound: Bool, port: UInt16, naming name: String) throws -> HeldListener {
        guard bound, listen(descriptor, 16) == 0 else {
            let reason = errno
            closeDescriptor(descriptor)
            throw ListenerError("cannot listen at \(name): \(reason)")
        }
        return HeldListener(descriptor, port: port)
    }

    /// The next connection, looked for for at most a glance.
    public func next() -> Next {
        held.withLock { descriptor in
            guard descriptor >= 0, !closing.load(ordering: .acquiring) else { return .closed }
            var ready = pollfd(fd: descriptor, events: Int16(POLLIN), revents: 0)
            guard poll(&ready, 1, Self.glance) == 1 else { return .nothing }
            let accepted = Descriptors.accept(descriptor)
            return accepted >= 0 ? .connection(accepted) : .failed(errno)
        }
    }

    /// Stops listening, once any look in progress and any process a test is starting
    /// (``TestSpawn``) have ended: a connect is refused from now on.
    public func close() {
        closing.store(true, ordering: .releasing)
        held.withLock { descriptor in
            if descriptor >= 0 { TestSpawn.close(descriptor) }
            descriptor = -1
        }
    }

    deinit {
        close()
    }
}

/// How a test starts a process: under the one lock a ``HeldListener`` closes under. A process being
/// started holds a copy of every descriptor of this one, close-on-exec or not, until just before its
/// exec (glibc's child copies the table; xnu copies it inside `posix_spawn`), so a listener another
/// test closed meanwhile would go on listening and take the connection that test expects refused.
/// The product owns no listener and takes no such lock.
public enum TestSpawn {
    fileprivate static let lock = Mutex(())
    private static let waited = Atomic(0)

    /// How many closes have found a start in progress and waited for it: the test of that wait
    /// reads it to know its close is waiting.
    public static var waits: Int {
        waited.load(ordering: .acquiring)
    }

    /// ``Spawn/process(_:_:input:output:errors:newSession:)``, under the lock.
    public static func process(
        _ executable: String, _ arguments: [String], input: Spawn.Standard = .null, output: Spawn.Standard = .null, errors: Spawn.Standard = .null
    ) throws(SpawnRefused) -> pid_t {
        try lock.withLock { _ throws(SpawnRefused) in try Spawn.process(executable, arguments, input: input, output: output, errors: errors) }
    }

    fileprivate static func close(_ descriptor: Int32) {
        guard lock.withLockIfAvailable({ _ in closeDescriptor(descriptor) }) == nil else { return }
        waited.add(1, ordering: .releasing)
        lock.withLock { _ in closeDescriptor(descriptor) }
    }
}

private func closeDescriptor(_ descriptor: Int32) {
    #if canImport(Glibc)
    _ = Glibc.close(descriptor)
    #else
    _ = Darwin.close(descriptor)
    #endif
}

public struct ListenerError: Error, CustomStringConvertible {
    public let description: String

    init(_ description: String) {
        self.description = description
    }
}
