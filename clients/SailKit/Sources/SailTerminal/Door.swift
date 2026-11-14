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
                throw .unreachable("the ticket could not be asked: \(error)")
            }
            return (try HostAddress(ticketAddress: ticket.address), ticket.ticket)
        }
    }
}

/// What a ticket is asked for: to open a session in a project, or to attach to one.
public enum TicketRequest: Equatable, Sendable {
    case open(project: String)
    case attach(session: String)
}

/// Whoever hands out terminal tickets: the control plane, for its caller.
public protocol TicketSource: Sendable {
    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket
}

extension SailClient: TicketSource {
    public func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        switch request {
        case let .open(project): try await send(.terminalTicket(open: project))
        case let .attach(session): try await send(.terminalTicket(attach: session))
        }
    }
}
