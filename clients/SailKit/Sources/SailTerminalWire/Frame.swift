import SailAPI

/// One message of the `SAILPTY4` terminal protocol, in either direction: the host's `PtyMessage`.
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
    /// Asks for a ticket for the holder of `credential` to take `action` on `target`, a project or a
    /// session, once over the host's TCP listener; the API asks it for a client, which is answered the
    /// ticket over HTTP.
    case issueTicket(action: Open<TicketAction>, target: String, credential: String, workspace: String = "")
    /// A ticket, presented as the `hello` token of one TCP connection to the host.
    case ticket(String)
    /// A ticket request refused: what kind of refusal, what happened, and its remedy as words and
    /// commands, kept apart.
    case ticketRefused(kind: Open<TicketRefusal>, reason: String, remedy: [RemedyPart])
    /// Begins upload `id` of a file of `size` bytes named `name` into the attached session; the
    /// host chooses where it is stored, from the name reduced to characters a shell takes as they
    /// are.
    case putBegin(id: Int32, name: String, size: Int64)
    /// The bytes of upload `id` from `offset`, at most 64 KiB of them: the host's bound.
    case putChunk(id: Int32, offset: Int64, bytes: [UInt8])
    /// Upload `id` is all sent; `sha256` is the digest of its bytes, which the host checks.
    case putEnd(id: Int32, sha256: [UInt8])
    /// Upload `id` is abandoned: the host keeps nothing of it, unless, its end sent, the host has
    /// already answered that it is stored.
    case putCancel(id: Int32)
    /// What became of upload `id` of the attached session, asked on a new connection when the one
    /// that sent its end dropped: answered as the upload was, or `not_known`.
    case putQuery(id: Int32)
    /// The host holds the first `received` bytes of upload `id`: the client's window moves on.
    case putAck(id: Int32, received: Int64)
    /// Upload `id` is stored at `path`, in the session's own workspace.
    case putStored(id: Int32, path: String)
    /// Upload `id` refused: its kind and the bound it met, zero when none; the client words it.
    case putRefused(id: Int32, kind: Open<UploadRefusal.Kind>, limit: Int64)
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

/// What a ticket lets its holder do over the host's TCP listener.
public enum TicketAction: String, CaseIterable, Sendable {
    /// Open a session in a project, and attach to it.
    case open
    /// Attach to a session.
    case attach
}

/// What kind of refusal a ticket request met; the API answers each with its own status.
public enum TicketRefusal: String, CaseIterable, Sendable {
    case invalid
    case notFound = "not_found"
    case forbidden
    case limited
    case unavailable
}

public struct TerminalSize: Equatable, Sendable {
    public var columns: Int32
    public var rows: Int32

    public init(columns: Int32, rows: Int32) {
        self.columns = columns
        self.rows = rows
    }
}

/// A session to start: `room` is the room it is pinned to, empty for none; `workspace` the project's
/// workspace it runs in, empty (or `main`) for the project's own container.
public struct SessionCreate: Equatable, Sendable {
    public var session: String
    public var command: [String]
    public var workingDirectory: String
    public var project: String
    public var room: String
    public var size: TerminalSize
    public var workspace: String

    public init(
        session: String, command: [String], workingDirectory: String, project: String, room: String,
        size: TerminalSize, workspace: String = ""
    ) {
        self.session = session
        self.command = command
        self.workingDirectory = workingDirectory
        self.project = project
        self.room = room
        self.size = size
        self.workspace = workspace
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
    /// The project the session runs in, empty for none.
    public var project: String
    /// The workspace of that project the session runs in, empty for the project's own container.
    public var workspace: String
    /// The person who opened the session.
    public var opener: String

    public init(
        name: String, instanceID: String, live: Bool, attached: Int32, writerFDE: String, room: String,
        command: [String], project: String = "", workspace: String = "", opener: String = ""
    ) {
        self.name = name
        self.instanceID = instanceID
        self.live = live
        self.attached = attached
        self.writerFDE = writerFDE
        self.room = room
        self.command = command
        self.project = project
        self.workspace = workspace
        self.opener = opener
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

/// The host's refusal: what happened, naming no command, and the remedy behind it as words and
/// commands, the same parts an API refusal carries, kept apart; the remedy is empty when there is
/// none.
public struct HostRefusal: Equatable, Sendable {
    public var message: String
    public var remedy: [RemedyPart]

    public init(message: String, remedy: [RemedyPart]) {
        self.message = message
        self.remedy = remedy
    }

    /// What a person reads of it, as every refusal is said (``Said/lines(_:remedy:otherwise:)``).
    public var lines: [String] {
        let words = remedy.compactMap { part -> String? in if case let .words(text) = part { text } else { nil } }.joined()
        return Said.lines(message, remedy: remedy, otherwise: words.isEmpty ? nil : words)
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
