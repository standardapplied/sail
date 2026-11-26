import Foundation

/// A control plane's address as Sail may use it: `https://` to any name, or plain `http://` only to
/// an IP literal in a private range (loopback, RFC 1918, 100.64.0.0/10, `::1`, `fc00::/7`), which is
/// for an encrypting overlay. Nothing but a scheme, a host and a port from 1 to 65535: no user,
/// path, query or fragment. A text with no scheme is a bare host, or a host and a port, and is
/// taken as `https://`, so nothing is ever sent as plain HTTP that was not typed so. The host is an
/// IP literal or a name of letters, digits and inner dashes, so nothing else typed there (a pasted
/// ticket) is ever looked up as a name. Decided from the text alone, before any byte is sent and
/// with no name looked up.
public struct ControlPlane: Hashable, Sendable, CustomStringConvertible {
    /// `scheme://host[:port]`, the host lowercased and an IPv6 host in brackets.
    public let url: URL

    public init(address text: String) throws(AccountRefusal) {
        let typed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmed = typed.contains("://") || typed.isEmpty ? typed : "https://" + typed
        guard let parts = URLComponents(string: trimmed), let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let raw = parts.host, !raw.isEmpty, parts.user == nil, parts.password == nil, parts.query == nil,
              parts.fragment == nil, ["", "/"].contains(parts.path),
              parts.port.map({ (1 ... 65535).contains($0) }) ?? true
        else {
            throw AccountRefusal(.notAnAddress)
        }
        let host = raw.trimmingCharacters(in: CharacterSet(charactersIn: "[]")).lowercased()
        guard Self.isName(host) || Self.ipv4(host) != nil || Self.ipv6(host) != nil else { throw AccountRefusal(.notAnAddress) }
        if scheme == "http", !Self.isPrivateLiteral(host) {
            throw AccountRefusal(.addressNotAllowed(host))
        }
        guard let url = URL(string: "\(scheme)://\(Self.bracketed(host))\(parts.port.map { ":\($0)" } ?? "")") else {
            throw AccountRefusal(.notAnAddress)
        }
        self.url = url
    }

    public var description: String {
        url.absoluteString
    }

    /// The control plane as a person reads it in a sentence or a line, and types it: its host, an
    /// IPv6 literal in brackets, and its port when it has one.
    public var named: String {
        guard let host = url.host()?.trimmingCharacters(in: CharacterSet(charactersIn: "[]")) else { return url.absoluteString }
        return url.port.map { "\(Self.bracketed(host)):\($0)" } ?? Self.bracketed(host)
    }

    /// An IPv6 literal in brackets, as an address spells it, so its colons are never read as a port's.
    private static func bracketed(_ host: String) -> String {
        host.contains(":") ? "[\(host)]" : host
    }

    /// Dot-separated labels of letters, digits and dashes, none empty, none starting or ending with
    /// a dash, the last not all digits, as a host name is spelled.
    private static func isName(_ host: String) -> Bool {
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        return host.count <= 253 && labels.last?.allSatisfy(\.isNumber) == false && labels.allSatisfy { label in
            (1 ... 63).contains(label.count) && label.first != "-" && label.last != "-"
                && label.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
        }
    }

    static func isPrivateLiteral(_ host: String) -> Bool {
        if let octets = ipv4(host) {
            switch (octets[0], octets[1]) {
            case (127, _), (10, _), (172, 16 ... 31), (192, 168), (100, 64 ... 127): return true
            default: return false
            }
        }
        guard let bytes = ipv6(host) else { return false }
        return bytes == [UInt8](repeating: 0, count: 15) + [1] || bytes[0] & 0xFE == 0xFC
    }

    /// Four decimal octets, no leading zeros: `inet_aton`'s shorthands (`10.1`, `0x0a.0.0.1`) are not
    /// addresses here.
    private static func ipv4(_ host: String) -> [Int]? {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        let octets = parts.compactMap { part -> Int? in
            guard (1 ... 3).contains(part.count), part.allSatisfy(\.isASCII), part.allSatisfy(\.isNumber),
                  part == "0" || !part.hasPrefix("0"), let value = Int(part), value <= 255
            else { return nil }
            return value
        }
        return parts.count == 4 && octets.count == 4 ? octets : nil
    }

    private static func ipv6(_ host: String) -> [UInt8]? {
        guard host.contains(":") else { return nil }
        var address = in6_addr()
        guard inet_pton(AF_INET6, host, &address) == 1 else { return nil }
        return withUnsafeBytes(of: &address) { Array($0) }
    }
}

/// The enrollment ticket in what was pasted: the ticket itself, or the `ticket` of a link
/// `sail fde enroll` printed.
public enum EnrollmentTicket {
    public static func parse(_ text: String) throws(AccountRefusal) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let ticket = trimmed.contains("://")
            ? URLComponents(string: trimmed)?.queryItems?.first { $0.name == "ticket" }?.value ?? ""
            : trimmed
        guard (8 ... 256).contains(ticket.count), ticket.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") })
        else { throw AccountRefusal(.notATicket) }
        return ticket
    }
}
