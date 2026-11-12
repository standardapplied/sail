import Foundation

/// A terminal ticket the terminal host issued for the caller: presented within its life as the
/// `hello` token of one TCP connection to the host at `address`, it admits that connection once, for
/// what it was asked for and nothing else.
public struct TerminalTicket: Codable, Equatable, Sendable {
    public let ticket: String
    /// Where to present it: `host:port`, an IPv6 host in brackets.
    public let address: String
    public let expiresAt: Date

    public init(ticket: String, address: String, expiresAt: Date) {
        self.ticket = ticket
        self.address = address
        self.expiresAt = expiresAt
    }

    private enum CodingKeys: String, CodingKey {
        case ticket, address
        case expiresAt = "expires_at"
    }
}

extension Endpoint where Response == TerminalTicket {
    /// A ticket to open a session in `project`, and attach to it.
    public static func terminalTicket(open project: String) throws(SailError) -> Self {
        Self(.post, Route.terminalTickets, body: try SailJSON.encode(["action": "open", "project": project]))
    }

    /// A ticket to attach to `session`.
    public static func terminalTicket(attach session: String) throws(SailError) -> Self {
        Self(.post, Route.terminalTickets, body: try SailJSON.encode(["action": "attach", "session": session]))
    }
}
