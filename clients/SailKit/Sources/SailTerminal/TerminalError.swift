import SailAPI
import SailTerminalWire

/// Why a connection to a terminal host did not get, or stay, attached.
public enum TerminalError: Error, Equatable, Sendable, CustomStringConvertible {
    /// An address that names nothing this client can connect to.
    case badAddress(String)
    /// The host could not be reached, or the connection to it broke: why.
    case unreachable(String)
    /// The host speaks another version of the terminal protocol; which side is older.
    case skew(Skew)
    /// The host refused, in its own words.
    case refused(HostRefusal)
    /// The API refused the ticket the connection needed.
    case ticketRefused(Refusal)
    /// The host sent what the protocol does not allow here.
    case protocolViolation(String)
    /// The host did not answer in time.
    case timedOut(String)
    /// The host closed the connection.
    case closed

    public var description: String {
        switch self {
        case let .badAddress(reason): reason
        case let .unreachable(reason): "the terminal host could not be reached: \(reason)"
        case .skew(.hostOlder): "the terminal host is older than this app: upgrade sail on it"
        case .skew(.clientOlder): "this app is older than the terminal host: update the app"
        case let .refused(refusal): refusal.message
        case let .ticketRefused(refusal): refusal.message
        case let .protocolViolation(what): "the terminal host broke the protocol: \(what)"
        case let .timedOut(what): "the terminal host did not answer \(what) in time"
        case .closed: "the terminal host closed the connection"
        }
    }
}
