/// One message of the `SAILPTY3` terminal protocol, in either direction: the host's `PtyMessage`.
/// The client sends the requests and receives the notifications; both are modelled so every frame
/// round-trips through ``FrameCodec``.
public enum Frame: Equatable, Sendable {
    case hello(token: String)
    /// The host's answer to an admitted hello: the boot id of this run of the host, which tells a
    /// session that died from one the host lost by restarting.
    case welcome(hostBootID: String)
    case create(SessionCreate)
    case attach(session: String, write: Bool)
    /// Input toward the pty; `sequence` is echoed back on output as the last input the host applied.
    case input(sequence: Int64, bytes: [UInt8])
    case resize(TerminalSize)
    case takeWrite
    case detach
    /// One page of the caller's sessions strictly after `after` (empty for the first page).
    case listSessions(after: String, limit: Int32)
    case kill(session: String)
    /// Ends a session for whatever displaced it; only the host's dispatch authority sends it.
    case yield(session: String, reason: String)
    case output(lastInputSequence: Int64, bytes: [UInt8])
    /// What follows until ``replayEnd`` is the session's journal replayed; `safe` says whether it
    /// starts on a clean terminal boundary.
    case replayBegin(safe: Bool)
    case replayEnd
    case writerChanged(fde: String)
    case resized(TerminalSize)
    case paused
    case continued
    case sessionEnded(reason: String)
    case sessionInfo(SessionInfo)
    case sessions(SessionPage)
    case accepted
    case refused(HostRefusal)
}

public struct TerminalSize: Equatable, Sendable {
    public var columns: Int32
    public var rows: Int32

    public init(columns: Int32, rows: Int32) {
        self.columns = columns
        self.rows = rows
    }
}

/// A session to start: `room` is the room it is pinned to, empty for none.
public struct SessionCreate: Equatable, Sendable {
    public var session: String
    public var command: [String]
    public var workingDirectory: String
    public var project: String
    public var room: String
    public var size: TerminalSize

    public init(session: String, command: [String], workingDirectory: String, project: String, room: String, size: TerminalSize) {
        self.session = session
        self.command = command
        self.workingDirectory = workingDirectory
        self.project = project
        self.room = room
        self.size = size
    }
}

/// One session as the host lists it. `name` is reused across lives of a session; `instanceID`
/// names this life, so two ended sessions of one name are told apart. `writerFDE` and `room` are
/// empty when nobody writes and the session is bound to no room.
public struct SessionInfo: Equatable, Sendable {
    public var name: String
    public var instanceID: String
    public var live: Bool
    public var attached: Int32
    public var writerFDE: String
    public var room: String
    public var command: [String]

    public init(
        name: String, instanceID: String, live: Bool, attached: Int32, writerFDE: String, room: String,
        command: [String]
    ) {
        self.name = name
        self.instanceID = instanceID
        self.live = live
        self.attached = attached
        self.writerFDE = writerFDE
        self.room = room
        self.command = command
    }
}

/// One page of a session listing; `next` is empty on the last page, else the cursor to continue.
public struct SessionPage: Equatable, Sendable {
    public var sessions: [SessionInfo]
    public var next: String

    public init(sessions: [SessionInfo], next: String) {
        self.sessions = sessions
        self.next = next
    }
}

/// The host's refusal: its message, and the remedy behind it as words and commands. `remedy` is
/// nil when the host sent the message alone, as a host from before remedies does.
public struct HostRefusal: Equatable, Sendable {
    public var message: String
    public var remedy: [Part]?

    public init(message: String, remedy: [Part]?) {
        self.message = message
        self.remedy = remedy
    }

    /// Words, or a command `runs` names the runner of (`SAIL`, `CLIENT`, `PROGRAM` as the host
    /// writes it), kept verbatim so a client renders it for its own reader.
    public enum Part: Equatable, Sendable {
        case words(String)
        case command(runs: String, verb: String, args: [String])
    }
}

public enum TerminalWireError: Error, Equatable, Sendable {
    /// A frame's length, the one given, is outside 1 through ``FrameCodec/maxPayload``: refused,
    /// never allocated.
    case lengthOutOfRange(Int)
    /// A frame ends before the fields it claims.
    case truncated
    case unknownType(UInt8)
    case malformed(String)
    /// The peer speaks another version of the protocol.
    case skew(Skew)
}

/// Which side of a handshake is behind.
public enum Skew: Equatable, Sendable {
    /// The host speaks an older protocol than this client.
    case hostOlder
    /// The host no longer speaks this client's protocol.
    case clientOlder
}
