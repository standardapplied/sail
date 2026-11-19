import SailAPI
import SailTerminalWire

/// Why a connection to a terminal host did not get, or stay, attached.
public enum TerminalError: Error, Equatable, Sendable, CustomStringConvertible {
    /// An address that names nothing this client can connect to.
    case badAddress(String)
    /// The host could not be reached, or the connection to it broke: why.
    case unreachable(String)
    /// The host's magic is not this protocol's: another version of Sail's.
    case otherProtocol
    /// The host refused, in its own words.
    case refused(HostRefusal)
    /// The API refused the ticket the connection needed.
    case ticketRefused(Refusal)
    /// The control plane could not answer for the ticket the connection needed: unreachable, or
    /// its proxy answering in its place.
    case controlPlane(SailError)
    /// The connection needed a ticket asked as a person this client is not signed in as now.
    case signIn(SignIn)
    /// The host sent what the protocol does not allow here.
    case protocolViolation(String)
    /// The host did not answer in time.
    case timedOut(String)
    /// The host closed the connection.
    case closed
    /// What was asked is not one this session's door admits.
    case unsupported(String)

    public var description: String {
        switch self {
        case let .badAddress(reason): reason
        case let .unreachable(reason): "the terminal host could not be reached: \(reason)"
        case .otherProtocol: "this app and the terminal host are different versions of Sail, so they cannot talk"
        case let .refused(refusal): refusal.message
        case let .ticketRefused(refusal): refusal.message
        case let .controlPlane(error): error.description
        case let .signIn(needed): needed.lines[0]
        case let .protocolViolation(what): "the terminal host broke the protocol: \(what)"
        case let .timedOut(what): "the terminal host did not answer \(what) in time"
        case .closed: "the terminal host closed the connection"
        case let .unsupported(what): what
        }
    }
}
