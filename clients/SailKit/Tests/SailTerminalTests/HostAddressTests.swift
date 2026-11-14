@testable import SailTerminal
import Testing

@Suite struct HostAddressTests {
    @Test(arguments: [
        ("127.0.0.1:7072", HostAddress.tcp(host: "127.0.0.1", port: 7072)),
        ("box.tailnet.ts.net:7072", .tcp(host: "box.tailnet.ts.net", port: 7072)),
        ("[fd7a:115c::1]:7072", .tcp(host: "fd7a:115c::1", port: 7072)),
        ("[::1]:1", .tcp(host: "::1", port: 1)),
    ])
    func aTicketsAddressIsItsHostAndPort(_ address: String, _ expected: HostAddress) throws {
        #expect(try HostAddress(ticketAddress: address) == expected)
        #expect(expected.description == address)
    }

    @Test(arguments: [
        "", "box", "box:", ":7072", "box:0", "box:65536", "box:07072", "box:72x", "fd7a::1:7072", "[fd7a::1]7072",
        "[box]:7072", "box name:7072", "box/path:7072", "[::1]:", "b\u{f6}x:7072",
    ])
    func anAddressThatNamesNoHostAndPortIsRefused(_ address: String) {
        #expect(throws: TerminalError.self) { try HostAddress(ticketAddress: address) }
    }

    @Test func theLocalHostListensUnderTheAccountsHome() throws {
        #expect(try HostAddress.local(home: "/Users/ada") == .unix(path: "/Users/ada/.sail/local/pty.sock"))
        #expect(try HostAddress.local(home: "/home/ada/") == .unix(path: "/home/ada/.sail/local/pty.sock"))
        guard case let .unix(path) = try HostAddress.local() else {
            Issue.record("the local host is not a unix socket")
            return
        }
        #expect(path.hasSuffix("/.sail/local/pty.sock"))
    }

    @Test func aHomeTooLongForASocketPathIsRefusedNamingTheLimit() {
        let home = "/" + String(repeating: "h", count: 90)
        #expect(throws: TerminalError.badAddress(
            "this machine's terminal host would listen at \(home)/.sail/local/pty.sock, 112 bytes, over the 102 a socket path may take"
        )) { try HostAddress.local(home: home) }
    }
}
