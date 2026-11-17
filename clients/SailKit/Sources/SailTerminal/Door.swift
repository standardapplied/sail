import SailAPI

/// How a session reaches its host. The only difference between hosts: where each connection goes,
/// and what its `Hello` carries.
public enum Door: Sendable {
    /// This machine's own host: its socket, and a blank token, which is the account's person.
    case local(HostAddress)
    /// A runner's TCP door, through a fresh ticket for every connection.
    case runner(any TicketSource)

    /// Where the next connection for `request` goes and the token it presents. A ticket is asked
    /// here, every time, and presented once.
    func entry(for request: TicketRequest) async throws(TerminalError) -> (address: HostAddress, token: String) {
        switch self {
        case let .local(address):
            return (address, "")
        case let .runner(source):
            let ticket: TerminalTicket
            do throws(SailError) {
                ticket = try await source.ticket(for: request)
            } catch let .refused(refusal) {
                throw .ticketRefused(refusal)
            } catch {
                throw .controlPlane(error)
            }
            return (try HostAddress(ticketAddress: ticket.address), ticket.ticket)
        }
    }
}

/// What a ticket is asked for: to open a session in a project's workspace (blank for the project's
/// own container), or to attach to one.
public enum TicketRequest: Equatable, Sendable {
    case open(project: String, workspace: String)
    case attach(session: String)
}

/// Whoever hands out terminal tickets: the control plane, for its caller.
public protocol TicketSource: Sendable {
    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket
}

extension SailClient: TicketSource {
    public func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        switch request {
        case let .open(project, workspace): try await send(.terminalTicket(open: project, workspace: workspace))
        case let .attach(session): try await send(.terminalTicket(attach: session))
        }
    }
}

/// The tickets of whoever is signed in now, asked of at every ticket, for a terminal opened by
/// `handle`: so a reconnect, Open Again or an end use the sign-in of the moment, never one the
/// person signed out of, and a terminal opened by one person never asks as another.
public final class SignedInTickets: TicketSource {
    public let handle: String
    private let current: @MainActor @Sendable () -> (handle: String, tickets: any TicketSource)?

    public init(for handle: String, current: @escaping @MainActor @Sendable () -> (handle: String, tickets: any TicketSource)?) {
        self.handle = handle
        self.current = current
    }

    public func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        guard let signedIn = await current() else {
            throw .refused(Refusal(code: .known(.missingBearerToken), message: "You are signed out of the control plane.", action: "Sign in to reach the runner."))
        }
        guard signedIn.handle == handle else {
            throw .refused(Refusal(
                code: .known(.invalidBearerToken), message: "This terminal is \(handle)'s, and \(signedIn.handle) is signed in.",
                action: "Sign in as \(handle) to reach it."
            ))
        }
        return try await signedIn.tickets.ticket(for: request)
    }
}
