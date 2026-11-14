import SailTerminalWire
import Synchronization

/// One connection to a terminal host: connected, then admitted (the magic exchanged both ways, a
/// `Hello` sent with the connection's token, the host's `Welcome` read). One thread reads it; any
/// thread writes, a frame at a time, never interleaved; any thread may close it, which ends a
/// read or a handshake in progress.
final class Connection: Sendable {
    /// The most input one `Input` frame carries; longer input is split, in order.
    static let inputChunk = 64 * 1024

    private let socket: Socket
    private let writes = Mutex(())
    private let reading = Mutex(Reading())

    private init(socket: Socket) {
        self.socket = socket
    }

    /// A connection to `address`, made within `timeout` seconds; each later answer is waited for
    /// as long, until ``stream()``.
    static func connect(to address: HostAddress, timeout: Int) throws(TerminalError) -> Connection {
        let socket = try Socket.connect(to: address, timeout: timeout)
        socket.setReadTimeout(seconds: timeout)
        return Connection(socket: socket)
    }

    /// Is admitted with `token`: the boot id of the run of the host that welcomed it. Refused in
    /// the host's words, or naming which side is older when the magic differs.
    func admit(token: String) throws(TerminalError) -> String {
        try socket.send(Handshake.magic)
        let peer = try reading.withLock { (reading: inout Reading) throws(TerminalError) in try reading.magic(from: socket) }
        do throws(TerminalWireError) {
            try Handshake.check(peer: peer)
        } catch {
            throw Self.violation(error)
        }
        try send(.hello(token: token))
        switch try next() {
        case let .welcome(hostBootID): return hostBootID
        case let .refused(refusal): throw .refused(refusal)
        case nil: throw .closed
        case let other?: throw .protocolViolation("it answered a hello with \(other.name)")
        }
    }

    /// Sends `frame` and reads the host's answer to it: accepted, or refused in its words.
    func request(_ frame: Frame) throws(TerminalError) {
        try send(frame)
        switch try next() {
        case .accepted: return
        case let .refused(refusal): throw .refused(refusal)
        case nil: throw .closed
        case let other?: throw .protocolViolation("it answered \(frame.name) with \(other.name)")
        }
    }

    func send(_ frame: Frame) throws(TerminalError) {
        let bytes = try Self.encoded(frame)
        try writes.withLock { (_: inout ()) throws(TerminalError) in try socket.send(bytes) }
    }

    /// Sends `bytes` as `Input` frames of at most ``inputChunk`` bytes, each numbered by
    /// `sequence` as it is sent, so the numbers rise in the order the host reads them. The number
    /// of the last frame.
    func sendInput(_ bytes: [UInt8], sequence: () -> Int64) throws(TerminalError) -> Int64 {
        try writes.withLock { (_: inout ()) throws(TerminalError) -> Int64 in
            var last: Int64 = 0
            for start in stride(from: 0, to: bytes.count, by: Self.inputChunk) {
                last = sequence()
                let chunk = Array(bytes[start ..< min(bytes.count, start + Self.inputChunk)])
                try socket.send(try Self.encoded(.input(sequence: last, bytes: chunk)))
            }
            return last
        }
    }

    /// The next frame the host sent, nil at the end of the stream.
    func next() throws(TerminalError) -> Frame? {
        try reading.withLock { (reading: inout Reading) throws(TerminalError) in try reading.next(from: socket) }
    }

    /// From now on a read waits as long as the stream is quiet: keepalive finds a dead peer.
    func stream() {
        socket.setReadTimeout(seconds: nil)
    }

    /// Ends the connection; its reader sees the end of the stream.
    func close() {
        socket.shutdown()
    }

    private static func encoded(_ frame: Frame) throws(TerminalError) -> [UInt8] {
        do throws(TerminalWireError) {
            return try FrameCodec.encode(frame)
        } catch {
            throw .protocolViolation("\(frame.name) does not fit a frame: \(error)")
        }
    }

    fileprivate static func violation(_ error: TerminalWireError) -> TerminalError {
        if case let .skew(skew) = error { return .skew(skew) }
        return .protocolViolation("\(error)")
    }
}

/// The read side of a connection: bytes read but not yet framed, and frames framed but not yet
/// handed out.
private struct Reading: Sendable {
    private var frames = FrameReader()
    private var ready: [Frame] = []
    private var head = 0
    private var unread: [UInt8] = []
    private var buffer = [UInt8](repeating: 0, count: 64 * 1024)

    /// The peer's eight-byte magic; what follows it in the same read is kept for the frames.
    mutating func magic(from socket: Socket) throws(TerminalError) -> [UInt8] {
        while unread.count < Handshake.magic.count {
            let count = try socket.receive(into: &buffer)
            guard count > 0 else { throw .closed }
            unread += buffer[..<count]
        }
        defer { unread.removeFirst(Handshake.magic.count) }
        return Array(unread.prefix(Handshake.magic.count))
    }

    mutating func next(from socket: Socket) throws(TerminalError) -> Frame? {
        if !unread.isEmpty {
            try frame(unread)
            unread = []
        }
        while head == ready.count {
            ready.removeAll(keepingCapacity: true)
            head = 0
            let count = try socket.receive(into: &buffer)
            guard count > 0 else { return nil }
            try frame(buffer[..<count])
        }
        defer { head += 1 }
        return ready[head]
    }

    private mutating func frame(_ bytes: some Sequence<UInt8>) throws(TerminalError) {
        do throws(TerminalWireError) {
            ready += try frames.read(bytes)
        } catch {
            throw Connection.violation(error)
        }
    }
}

extension Frame {
    /// The frame's kind, as an error names it; never its contents.
    var name: String {
        switch self {
        case .hello: "Hello"
        case .welcome: "Welcome"
        case .create: "Create"
        case .attach: "Attach"
        case .input: "Input"
        case .resize: "Resize"
        case .takeWrite: "TakeWrite"
        case .detach: "Detach"
        case .listSessions: "ListSessions"
        case .kill: "Kill"
        case .yield: "Yield"
        case .issueTicket: "IssueTicket"
        case .ticket: "Ticket"
        case .ticketRefused: "TicketRefused"
        case .output: "Output"
        case .replayBegin: "ReplayBegin"
        case .replayEnd: "ReplayEnd"
        case .writerChanged: "WriterChanged"
        case .resized: "Resized"
        case .paused: "Paused"
        case .continued: "Continued"
        case .sessionEnded: "SessionEnded"
        case .sessionInfo: "SessionInfo"
        case .sessions: "Sessions"
        case .accepted: "Accepted"
        case .refused: "Refused"
        }
    }
}
