import SailAPI

/// One message of the `SAILPTY7` terminal protocol, in either direction: the host's `PtyMessage`.
/// The client sends the requests and receives the notifications; both are modelled so every frame
/// round-trips through ``FrameCodec``.
public enum Frame: Equatable, Sendable {
    case hello(token: String)
    /// The host's answer to an admitted hello: the boot id of this run of the host, which tells a
    /// session that died from one the host lost by restarting.
    case welcome(hostBootID: String)
    case create(SessionCreate)
    /// Attaches to `session`, asking for the keyboard when `write`. `client` names this client across
    /// its own reconnects; `takes` says the person asked now, which takes the keyboard from their
    /// own other client too. Without it a write request never takes it from another live client of
    /// theirs, and watches.
    case attach(session: String, write: Bool, client: String = "", takes: Bool = true)
    /// Input toward the pty; `sequence` is echoed back on output as the last input the host applied.
    case input(sequence: Int64, bytes: [UInt8])
    case resize(TerminalSize)
    case takeWrite
    case detach
    /// One page of the caller's sessions strictly after `after` (empty for the first page).
    case listSessions(after: String, limit: Int32)
    case kill(session: String)
    /// Ends a session for whatever displaced it; only the host's dispatch authority sends it.
    case yield(session: String, reason: String, record: String)
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
    /// Upload `id`, its end not yet sent, is abandoned: the host keeps nothing of it.
    case putCancel(id: Int32)
    /// The host holds the first `received` bytes of upload `id`: the client's window moves on.
    case putAck(id: Int32, received: Int64)
    /// Upload `id` is stored at `path`, in the session's own workspace.
    case putStored(id: Int32, path: String)
    /// Upload `id` refused: its kind and the bound it met, zero when none; the client words it.
    case putRefused(id: Int32, kind: Open<UploadRefusal.Kind>, limit: Int64)
    /// Ends every live session in workspace `workspace` of `project`, and every files connection to
    /// it; only the host's dispatch authority sends it.
    case yieldWorkspace(project: String, workspace: String, reason: String, record: String)
    /// Lists folder `path` of a files ticket's workspace: ``filesListing(_:)`` frames, or ``filesRefused(id:kind:message:)``.
    case filesList(id: Int32, path: FileName)
    /// What `path` is: ``filesEntry(id:entry:version:)``, or a refusal.
    case filesStat(id: Int32, path: FileName)
    /// At most `length` bytes of file `path` from `offset`: ``filesData(id:version:bytes:eof:whole:digest:)`` frames of at
    /// most 256 KiB, or a refusal.
    case filesRead(id: Int32, path: FileName, offset: Int64, length: Int64)
    /// Ends request `id`, under way or waiting; nothing more is answered for it.
    case filesCancel(id: Int32)
    /// Replaces the connection's whole watch set, each path from the version this client holds of it.
    case filesWatch([WatchedPath])
    case filesListing(FileListing)
    case filesEntry(id: Int32, entry: FileEntry, version: String)
    /// The next bytes request `id` reads, at the version the file had when the read began; `eof` on the
    /// last frame when it reached the file's end.
    /// The last frame alone carries `digest`, the SHA-256 in hex of every byte the read sent, which
    /// the workspace hashed again from the file after sending them and found the same: the read's
    /// content version, empty on every frame before the last. `whole`, on the last frame alone, says
    /// the read ran from 0 to the file's end: its digest is the whole file's, the one base a save takes.
    case filesData(id: Int32, version: String, bytes: [UInt8], eof: Bool, whole: Bool, digest: String)
    /// These watched paths changed: read them again.
    case filesChanged(paths: [FileName])
    /// The watch is live: said each second it runs.
    case filesTick
    /// Request `id` refused, in a sentence naming the file and the reason.
    case filesRefused(id: Int32, kind: Open<FileRefusal>, message: String)
    /// Saves `size` bytes as file `path`, sent after it as ``putChunk(id:offset:bytes:)`` frames and a
    /// ``putEnd(id:sha256:)``: written over the file only while its whole content is still `base`,
    /// the SHA-256 (lowercase hex) a whole read of it answered, or made anew only while nothing is
    /// named `path` when `base` is `"absent"`. `seen` is the version that read answered, blank with
    /// `"absent"`: it lets a save refuse early, never accept.
    case filesWrite(id: Int32, path: FileName, base: String, seen: String, size: Int64)
    /// Makes `path` anew, a folder when `folder`, else an empty file.
    case filesCreate(id: Int32, path: FileName, folder: Bool)
    /// Moves `from` to `to`, never over a name already there.
    case filesMove(id: Int32, from: FileName, to: FileName)
    /// Removes `path`, a folder with all it holds.
    case filesRemove(id: Int32, path: FileName)
    /// Write `id` is the file's whole content now: `digest` is its SHA-256, the next save's base,
    /// and `version` its version after the save.
    case filesWritten(id: Int32, digest: String, version: String)
    /// Write `id` wrote nothing: the file's content is `digest` now, `"absent"` when it is gone.
    case filesConflict(id: Int32, digest: String)
    /// Create, move or remove `id` is done.
    case filesDone(id: Int32)
    /// What differs from `HEAD` in each of the workspace's repositories, and what git ignores there:
    /// ``filesChangeList(_:)`` frames, or a refusal.
    case filesChanges(id: Int32)
    case filesChangeList(FileChangeList)
    /// `path`'s change against `HEAD` as a unified diff: ``filesDiffData(id:bytes:eof:)`` frames, or a
    /// refusal.
    case filesDiff(id: Int32, path: FileName)
    /// The next bytes of diff `id`, `eof` on its last frame: a diff, never a file's content, so it
    /// carries no version and no digest.
    case filesDiffData(id: Int32, bytes: [UInt8], eof: Bool)
    case output(lastInputSequence: Int64, bytes: [UInt8])
    /// What follows until ``replayEnd`` is the session's journal replayed; `safe` says whether it
    /// starts on a clean terminal boundary.
    case replayBegin(safe: Bool)
    case replayEnd
    /// `fde` holds the keyboard now; `yours` when it is this connection that holds it.
    case writerChanged(fde: String, yours: Bool)
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
    /// Read the files of a workspace.
    case files
}

/// What kind of refusal a ticket request met; the API answers each with its own status.
public enum TicketRefusal: String, CaseIterable, Sendable {
    case invalid
    case notFound = "not_found"
    case forbidden
    case limited
    case unavailable
    /// The workspace whose files were asked is stopped, and nothing starts it for them.
    case stopped
}

/// What an entry of a folder is, and what a link leads to; ``none`` is no link's target.
public enum FileKind: String, CaseIterable, Sendable {
    case none = ""
    case file
    case folder
    case link
    /// A pipe, a socket or a device.
    case other
    case missing
    case loop
}

/// Why a files request was refused.
public enum FileRefusal: String, CaseIterable, Sendable {
    case notFound = "not_found"
    case notPermitted = "not_permitted"
    case outsideHome = "outside_home"
    case isFolder = "is_folder"
    case notAFolder = "not_a_folder"
    case notAFile = "not_a_file"
    case loop
    /// The file changed while it was read: the client drops what it had of the read and reads again.
    case changed
    /// The name is taken: nothing was replaced.
    case exists
    /// The workspace's disk has no room; the sentence says how much is free.
    case noSpace = "no_space"
    /// Over the 20 MB a save may carry.
    case tooLarge = "too_large"
    /// Some of a folder could not be removed; the tree shows what is left.
    case partlyRemoved = "partly_removed"
    /// A change Sail does not make: a name that is not UTF-8 or holds a control character, or a
    /// folder moved into itself.
    case unsafe
    /// The workspace has no git, so nothing is said of its changes.
    case noGit = "no_git"
    /// No repository is where its changes, or a file's change, were asked.
    case noRepository = "no_repository"
    /// The change is binary, so it is not shown as text.
    case binary
    case failed
}

/// How a path differs from `HEAD`.
public enum ChangeState: String, CaseIterable, Sendable {
    case modified
    case added
    case deleted
    /// Moved from `from`: the app shows it added at its path and deleted at `from`.
    case renamed
    case untracked
    case conflicted
}

/// A path that differs from `HEAD`, how, and a rename's `from`, empty for any other.
public struct FileChange: Equatable, Sendable {
    public var path: FileName
    public var state: Open<ChangeState>
    public var from: FileName

    public init(path: FileName, state: Open<ChangeState>, from: FileName) {
        self.path = path
        self.state = state
        self.from = from
    }
}

/// Part of changes request `id`'s answer: repository `root` on `branch`, its `entries` and the paths
/// git `ignored` there, each path absolute and an ignored folder one path. One repository's frames
/// come together, every repository has one at least; `truncated` once its lists were cut at 5,000, or
/// at the answer's size; `more` while frames of the answer follow. `tookMillis`, on every frame, is
/// how long the answer took, which the client backs off by.
public struct FileChangeList: Equatable, Sendable {
    public var id: Int32
    public var tookMillis: Int64
    public var root: FileName
    public var branch: FileName
    public var entries: [FileChange]
    public var ignored: [FileName]
    public var truncated: Bool
    public var more: Bool

    public init(
        id: Int32, tookMillis: Int64, root: FileName, branch: FileName, entries: [FileChange], ignored: [FileName],
        truncated: Bool, more: Bool
    ) {
        self.id = id
        self.tookMillis = tookMillis
        self.root = root
        self.branch = branch
        self.entries = entries
        self.ignored = ignored
        self.truncated = truncated
        self.more = more
    }
}

/// A file's name or path as the workspace holds it: bytes, which need not be UTF-8. It addresses
/// that file exactly, and ``shown`` is what a person reads, each byte that is no UTF-8 as U+FFFD.
public struct FileName: Hashable, Sendable, ExpressibleByStringLiteral, CustomStringConvertible {
    public var bytes: [UInt8]

    public init(_ bytes: [UInt8]) {
        self.bytes = bytes
    }

    public init(_ text: String) {
        bytes = Array(text.utf8)
    }

    public init(stringLiteral text: String) {
        self.init(text)
    }

    /// The name as a person reads it.
    public var shown: String {
        String(decoding: bytes, as: UTF8.self)
    }

    public var description: String {
        shown
    }
}

/// One entry of a folder: its own size, its modification time in nanoseconds since the epoch, and its
/// permission bits; `target` is what a link leads to, ``FileKind/none`` for any other entry.
public struct FileEntry: Equatable, Sendable {
    public var name: FileName
    public var kind: Open<FileKind>
    public var target: Open<FileKind>
    public var size: Int64
    public var mtimeNanos: Int64
    public var mode: Int32

    public init(name: FileName, kind: Open<FileKind>, target: Open<FileKind>, size: Int64, mtimeNanos: Int64, mode: Int32) {
        self.name = name
        self.kind = kind
        self.target = target
        self.size = size
        self.mtimeNanos = mtimeNanos
        self.mode = mode
    }
}

/// A watched path and the opaque version this client holds of it, empty for one it holds as missing.
public struct WatchedPath: Equatable, Sendable {
    public var path: FileName
    public var version: String

    public init(path: FileName, version: String) {
        self.path = path
        self.version = version
    }
}

/// Entries of the folder request `id` lists, at `version`, in no order: `more` while frames of it follow,
/// and on its last frame `truncated` when the folder holds more than 50,000 entries.
public struct FileListing: Equatable, Sendable {
    public var id: Int32
    public var version: String
    public var entries: [FileEntry]
    public var more: Bool
    public var truncated: Bool

    public init(id: Int32, version: String, entries: [FileEntry], more: Bool, truncated: Bool) {
        self.id = id
        self.version = version
        self.entries = entries
        self.more = more
        self.truncated = truncated
    }
}

public struct TerminalSize: Equatable, Sendable {
    public var columns: Int32
    public var rows: Int32

    public init(columns: Int32, rows: Int32) {
        self.columns = columns
        self.rows = rows
    }
}

/// The harness a session runs, by the host's name for it (`claude-code`, `codex`), in one of the
/// harness's own modes, signed in with its person's stored login, or, `inside`, with none handed
/// over, so the person signs in with the harness's own login there. ``none`` for a command.
public struct SessionAgent: Equatable, Sendable {
    public var harness: String
    public var mode: String
    public var inside: Bool

    public init(harness: String, mode: String, inside: Bool = false) {
        self.harness = harness
        self.mode = mode
        self.inside = inside
    }

    public static let none = SessionAgent(harness: "", mode: "")

    /// Whether this names no harness: a session that runs a command.
    public var isNone: Bool { harness.isEmpty }
}

/// A session to start: `room` is the room it is pinned to, empty for none; `workspace` the project's
/// workspace it runs in, empty (or `main`) for the project's own container. `agent` names a harness
/// the host starts there instead of a command, the command then empty: the host composes it.
public struct SessionCreate: Equatable, Sendable {
    public var session: String
    public var command: [String]
    public var workingDirectory: String
    public var project: String
    public var room: String
    public var size: TerminalSize
    public var workspace: String
    public var agent: SessionAgent

    public init(
        session: String, command: [String], workingDirectory: String, project: String, room: String,
        size: TerminalSize, workspace: String = "", agent: SessionAgent = .none
    ) {
        self.session = session
        self.command = command
        self.workingDirectory = workingDirectory
        self.project = project
        self.room = room
        self.size = size
        self.workspace = workspace
        self.agent = agent
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
    /// When it was opened, in milliseconds since the epoch.
    public var createdAtMillis: Int64
    /// The host's words for why it ended, empty while it lives.
    public var endedReason: String
    /// The harness it runs, ``SessionAgent/none`` for a command; `command` is then the harness's own.
    public var agent: SessionAgent

    public init(
        name: String, instanceID: String, live: Bool, attached: Int32, writerFDE: String, room: String,
        command: [String], project: String = "", workspace: String = "", opener: String = "",
        createdAtMillis: Int64 = 0, endedReason: String = "", agent: SessionAgent = .none
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
        self.createdAtMillis = createdAtMillis
        self.endedReason = endedReason
        self.agent = agent
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
    /// Its kind as the API's error codes name it (`login_not_stored`), empty for none: what a client
    /// acts on, never the words.
    public var code: String

    public init(message: String, remedy: [RemedyPart], code: String = "") {
        self.message = message
        self.remedy = remedy
        self.code = code
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
    /// The peer's magic is not this protocol's: another version of Sail, or not Sail.
    case otherProtocol
}
