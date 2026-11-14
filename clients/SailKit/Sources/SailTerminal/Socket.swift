#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

/// One connected stream socket, unix or TCP, through POSIX alone so the same code runs on every
/// platform. Reads block the thread that reads; writes block their caller for at most
/// ``sendTimeout``. Shutting it down wakes a blocked reader; the descriptor closes with the object.
final class Socket: Sendable {
    /// How long a write may wait for a peer that stopped reading before the connection is given up.
    static let sendTimeout = 10
    /// TCP keepalive: probes after 10 s of silence, 5 s apart, 3 unanswered and the peer is dead.
    static let keepalive = (idle: Int32(10), interval: Int32(5), count: Int32(3))

    private let descriptor: Int32

    init(descriptor: Int32) {
        self.descriptor = descriptor
    }

    deinit {
        close(descriptor)
    }

    /// A socket connected to `address` within `timeout` seconds.
    static func connect(to address: HostAddress, timeout: Int) throws(TerminalError) -> Socket {
        switch address {
        case let .unix(path): try unix(path, timeout: timeout)
        case let .tcp(host, port): try tcp(host, port: port, timeout: timeout)
        }
    }

    /// Writes every byte of `bytes`.
    func send(_ bytes: [UInt8]) throws(TerminalError) {
        var sent = 0
        while sent < bytes.count {
            let wrote = bytes.withUnsafeBytes { raw in
                raw.baseAddress.map { Self.write(descriptor, $0 + sent, raw.count - sent) } ?? 0
            }
            if wrote < 0, errno == EINTR { continue }
            guard wrote > 0 else {
                throw errno == EAGAIN || errno == EWOULDBLOCK ? .timedOut("a write") : .unreachable("a write failed: \(Self.reason(errno))")
            }
            sent += wrote
        }
    }

    /// Reads what has arrived into `buffer`, waiting for at least one byte: how many came, 0 at
    /// the end of the stream.
    func receive(into buffer: inout [UInt8]) throws(TerminalError) -> Int {
        while true {
            let read = buffer.withUnsafeMutableBytes { recv(descriptor, $0.baseAddress, $0.count, 0) }
            if read >= 0 { return read }
            switch errno {
            case EINTR: continue
            case EAGAIN, EWOULDBLOCK: throw .timedOut("a read")
            default: throw .unreachable("a read failed: \(Self.reason(errno))")
            }
        }
    }

    /// How long a read may wait, nil for as long as it takes.
    func setReadTimeout(seconds: Int?) {
        var limit = timeval(tv_sec: seconds ?? 0, tv_usec: 0)
        setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
    }

    /// Ends both directions: a blocked read returns, and the peer sees the end of the stream.
    func shutdown() {
        _ = Self.shutdownBoth(descriptor)
    }

    private static func unix(_ path: String, timeout: Int) throws(TerminalError) -> Socket {
        var address = sockaddr_un()
        let capacity = MemoryLayout.size(ofValue: address.sun_path)
        guard path.utf8.count < capacity else { throw .badAddress("\(path) is longer than a unix socket path may be") }
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { raw in
            raw.copyBytes(from: path.utf8)
            raw[path.utf8.count] = 0
        }
        let socket = try open(family: AF_UNIX)
        try withUnsafePointer(to: &address) { pointer throws(TerminalError) in
            try pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { (pointer: UnsafePointer<sockaddr>) throws(TerminalError) in
                try socket.connect(pointer, length: socklen_t(MemoryLayout<sockaddr_un>.size), timeout: timeout, naming: path)
            }
        }
        try socket.configure(tcp: false)
        return socket
    }

    private static func tcp(_ host: String, port: UInt16, timeout: Int) throws(TerminalError) -> Socket {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = streamType
        hints.ai_flags = AI_NUMERICSERV
        var found: UnsafeMutablePointer<addrinfo>?
        let looked = getaddrinfo(host, String(port), &hints, &found)
        guard looked == 0, let first = found else {
            throw .unreachable("\(host) does not resolve: \(String(cString: gai_strerror(looked)))")
        }
        defer { freeaddrinfo(first) }
        var failure = TerminalError.unreachable("\(host) resolves to no address")
        var next: UnsafeMutablePointer<addrinfo>? = first
        while let candidate = next {
            next = candidate.pointee.ai_next
            do {
                let socket = try open(family: candidate.pointee.ai_family)
                try socket.connect(candidate.pointee.ai_addr, length: candidate.pointee.ai_addrlen, timeout: timeout, naming: "\(host):\(port)")
                try socket.configure(tcp: true)
                return socket
            } catch {
                failure = error
            }
        }
        throw failure
    }

    private static func open(family: Int32) throws(TerminalError) -> Socket {
        let descriptor = socket(family, streamType, 0)
        guard descriptor >= 0 else { throw .unreachable("no socket: \(reason(errno))") }
        return Socket(descriptor: descriptor)
    }

    /// Connects without blocking past `timeout` seconds, then goes back to blocking.
    private func connect(_ address: UnsafePointer<sockaddr>, length: socklen_t, timeout: Int, naming name: String) throws(TerminalError) {
        let flags = fcntl(descriptor, F_GETFL)
        _ = fcntl(descriptor, F_SETFL, flags | O_NONBLOCK)
        defer { _ = fcntl(descriptor, F_SETFL, flags) }
        if Self.connectTo(descriptor, address, length) == 0 { return }
        guard errno == EINPROGRESS || errno == EINTR else { throw .unreachable("\(name): \(Self.reason(errno))") }
        var ready = pollfd(fd: descriptor, events: Int16(POLLOUT), revents: 0)
        let polled = poll(&ready, 1, Int32(timeout * 1000))
        guard polled > 0 else { throw polled == 0 ? .timedOut("a connection to \(name)") : .unreachable("\(name): \(Self.reason(errno))") }
        var problem: Int32 = 0
        var size = socklen_t(MemoryLayout<Int32>.size)
        getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &problem, &size)
        guard problem == 0 else { throw .unreachable("\(name): \(Self.reason(problem))") }
    }

    private func configure(tcp: Bool) throws(TerminalError) {
        var on: Int32 = 1
        var limit = timeval(tv_sec: Self.sendTimeout, tv_usec: 0)
        setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        #if canImport(Darwin)
        setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        #endif
        guard tcp else { return }
        let options: [(Int32, Int32, Int32)] = [
            (Int32(IPPROTO_TCP), TCP_NODELAY, 1),
            (SOL_SOCKET, SO_KEEPALIVE, 1),
            (Int32(IPPROTO_TCP), Self.keepaliveIdle, Self.keepalive.idle),
            (Int32(IPPROTO_TCP), TCP_KEEPINTVL, Self.keepalive.interval),
            (Int32(IPPROTO_TCP), TCP_KEEPCNT, Self.keepalive.count),
        ]
        for (level, option, value) in options {
            on = value
            guard setsockopt(descriptor, level, option, &on, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
                throw .unreachable("socket option \(option) refused: \(Self.reason(errno))")
            }
        }
    }

    private static func reason(_ code: Int32) -> String {
        String(cString: strerror(code))
    }

    #if canImport(Glibc)
    private static let streamType = Int32(SOCK_STREAM.rawValue)
    private static let keepaliveIdle = TCP_KEEPIDLE

    private static func write(_ descriptor: Int32, _ bytes: UnsafeRawPointer, _ count: Int) -> Int {
        Glibc.send(descriptor, bytes, count, Int32(MSG_NOSIGNAL))
    }

    private static func connectTo(_ descriptor: Int32, _ address: UnsafePointer<sockaddr>, _ length: socklen_t) -> Int32 {
        Glibc.connect(descriptor, address, length)
    }

    private static func shutdownBoth(_ descriptor: Int32) -> Int32 {
        Glibc.shutdown(descriptor, Int32(SHUT_RDWR))
    }
    #else
    private static let streamType = SOCK_STREAM
    private static let keepaliveIdle = TCP_KEEPALIVE

    private static func write(_ descriptor: Int32, _ bytes: UnsafeRawPointer, _ count: Int) -> Int {
        Darwin.send(descriptor, bytes, count, 0)
    }

    private static func connectTo(_ descriptor: Int32, _ address: UnsafePointer<sockaddr>, _ length: socklen_t) -> Int32 {
        Darwin.connect(descriptor, address, length)
    }

    private static func shutdownBoth(_ descriptor: Int32) -> Int32 {
        Darwin.shutdown(descriptor, SHUT_RDWR)
    }
    #endif
}
