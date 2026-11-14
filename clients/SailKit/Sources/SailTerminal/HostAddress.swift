#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

/// Where a terminal host listens: the unix socket of this machine's own host, or a runner's TCP
/// door as a ticket names it.
public enum HostAddress: Hashable, Sendable, CustomStringConvertible {
    case unix(path: String)
    case tcp(host: String, port: UInt16)

    /// The longest socket path the local host binds (macOS's `sun_path` less what the JDK keeps).
    public static let longestSocketPath = 102

    /// This account's own host: `~/.sail/local/pty.sock` in the home the account database names,
    /// where `sail _pty-host --local` serves, never `$HOME`.
    public static func local() throws(TerminalError) -> HostAddress {
        try local(home: try accountHome())
    }

    /// The local host's socket under `home`.
    public static func local(home: String) throws(TerminalError) -> HostAddress {
        let path = (home.hasSuffix("/") ? String(home.dropLast()) : home) + "/.sail/local/pty.sock"
        guard path.utf8.count <= longestSocketPath else {
            throw .badAddress("this machine's terminal host would listen at \(path), \(path.utf8.count) bytes, over the \(longestSocketPath) a socket path may take")
        }
        return .unix(path: path)
    }

    /// The door a ticket's `address` names: `host:port`, an IPv6 host in brackets.
    public init(ticketAddress address: String) throws(TerminalError) {
        let host: Substring
        let port: Substring
        if address.hasPrefix("[") {
            guard let close = address.firstIndex(of: "]"), address[address.index(after: close)...].hasPrefix(":") else {
                throw .badAddress("'\(address)' is not [ipv6]:port")
            }
            host = address[address.index(after: address.startIndex) ..< close]
            port = address[address.index(close, offsetBy: 2)...]
            guard host.contains(":"), host.allSatisfy(\.isHexDigitOrSeparator) else {
                throw .badAddress("'\(address)' does not hold an IPv6 address in its brackets")
            }
        } else {
            let parts = address.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 2 else { throw .badAddress("'\(address)' is not host:port") }
            host = parts[0]
            port = parts[1]
            guard !host.isEmpty, host.allSatisfy(\.isHostNameCharacter) else {
                throw .badAddress("'\(address)' names no host this client can reach")
            }
        }
        guard let number = UInt16(port), number > 0, String(number) == String(port) else {
            throw .badAddress("'\(address)' names no port from 1 to 65535")
        }
        self = .tcp(host: String(host), port: number)
    }

    public var description: String {
        switch self {
        case let .unix(path): path
        case let .tcp(host, port): host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)"
        }
    }

    private static func accountHome() throws(TerminalError) -> String {
        var entry = passwd()
        var found: UnsafeMutablePointer<passwd>?
        var buffer = [CChar](repeating: 0, count: 16 * 1024)
        let result = getpwuid_r(getuid(), &entry, &buffer, buffer.count, &found)
        guard result == 0, found != nil, let home = entry.pw_dir else {
            throw .badAddress("the account database names no home for user \(getuid())")
        }
        return String(cString: home)
    }
}

private extension Character {
    var isHexDigitOrSeparator: Bool {
        isHexDigit || self == ":" || self == "."
    }

    var isHostNameCharacter: Bool {
        isASCII && (isLetter || isNumber || self == "." || self == "-")
    }
}
