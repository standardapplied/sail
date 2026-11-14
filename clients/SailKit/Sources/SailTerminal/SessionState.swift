import SailTerminalWire
import SailVT

/// Where a session stands, as the app reads it at any time.
public struct SessionState: Equatable, Sendable {
    public var phase: SessionPhase
    /// The session's name on its host: the one it was opened or attached as.
    public var session: String
    /// Input handed in that never reached the program: handed in while not attached (dropped, never
    /// queued), or written into a connection that died before the host applied it, as the replay
    /// of the next attach shows.
    public var droppedInputs: Int
    /// The boot id of the run of the host the session lives on, once a host welcomed it.
    public var hostBootID: String?
    /// The FDE holding the write token, empty for nobody, as the host last said.
    public var writer: String?
    /// The host stopped sending live output to this client, which fell behind; it catches up with
    /// a replay.
    public var paused: Bool

    public init(
        phase: SessionPhase, session: String, droppedInputs: Int = 0, hostBootID: String? = nil, writer: String? = nil,
        paused: Bool = false
    ) {
        self.phase = phase
        self.session = session
        self.droppedInputs = droppedInputs
        self.hostBootID = hostBootID
        self.writer = writer
        self.paused = paused
    }
}

public enum SessionPhase: Equatable, Sendable {
    /// Not started, or connecting for the first time.
    case connecting
    case attached
    /// The connection was lost; connecting again, this the attempt in a row about to be made.
    case reconnecting(attempt: Int)
    /// So many attempts in a row failed that the session waits for ``TerminalSession/reconnectNow()``:
    /// the last failure, in words.
    case parked(reason: String)
    /// Detached on purpose; the session lives on at its host.
    case detached
    case ended(SessionEnd)
}

/// Why a session is over for this client.
public enum SessionEnd: Equatable, Sendable, CustomStringConvertible {
    /// The host's run that held the session ended: a new boot id welcomed the reconnect.
    case hostRestarted
    /// The host, or the API for it, refused the session, in these words.
    case refused(String)
    /// The host ended the session, for this reason.
    case ended(String)
    /// The host sent what the protocol does not allow, which asking again would not mend.
    case protocolViolation(String)
    /// The app closed it.
    case closed

    public var description: String {
        switch self {
        case .hostRestarted: "the host restarted, which ends every session it held"
        case let .refused(words): words
        case let .ended(reason): "the session ended: \(reason)"
        case let .protocolViolation(what): "the terminal host broke the protocol: \(what)"
        case .closed: "closed"
        }
    }
}

/// What a session tells its app, besides the screen it keeps.
public enum SessionEvent: Equatable, Sendable {
    case phase(SessionPhase)
    /// The host's geometry for the session changed; the screen took it in order.
    case resized(TerminalSize)
    case writerChanged(String)
    case paused
    case continued
    /// A replay of the session's history began; `safe` is false when it starts inside an escape
    /// sequence, so the screen may be wrong until the program next redraws.
    case replay(safe: Bool)
    /// The host refused a frame of this client's while attached (input without the write token, say).
    case refused(HostRefusal)
    case screen(ScreenEvent)
}

/// What a session asks its host for when it connects.
enum Intent: Equatable, Sendable {
    case open(SessionCreate)
    case attach(session: String, write: Bool)

    var ticket: TicketRequest {
        switch self {
        case let .open(create): .open(project: create.project)
        case let .attach(session, _): .attach(session: session)
        }
    }

    var session: String {
        switch self {
        case let .open(create): create.session
        case let .attach(session, _): session
        }
    }
}
