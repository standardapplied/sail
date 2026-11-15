#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import Foundation
import SailAPI
import SailKitTestSupport
import Synchronization
import Testing

/// A loopback HTTP server that answers every request with `answer` and keeps the head of each
/// request it was sent: its request line and headers, lowercased.
final class HTTPListener: Sendable {
    let port: UInt16
    private let descriptor: Int32
    private let heads = Heads()

    private final class Heads: Sendable {
        let list = Mutex<[String]>([])
    }

    init(answer: @escaping @Sendable (_ requestLine: String) -> String) throws {
        let descriptor = socket(AF_INET, Self.streamType, 0)
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        var size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(descriptor, $0, size) == 0 && getsockname(descriptor, $0, &size) == 0 }
        }
        guard descriptor >= 0, bound, Self.listening(descriptor) else { throw GoldenError(reason: "no loopback listener: \(errno)") }
        port = UInt16(bigEndian: address.sin_port)
        self.descriptor = descriptor
        Thread { [heads] in
            while true {
                let client = accept(descriptor, nil, nil)
                guard client >= 0 else { return }
                var received: [UInt8] = []
                var buffer = [UInt8](repeating: 0, count: 4096)
                while !received.suffix(4).elementsEqual("\r\n\r\n".utf8) {
                    let count = read(client, &buffer, buffer.count)
                    guard count > 0 else { break }
                    received += buffer.prefix(count)
                }
                let head = String(decoding: received, as: UTF8.self).lowercased()
                heads.list.withLock { $0.append(head) }
                let reply = Array(answer(head.split(separator: "\r\n").first.map(String.init) ?? "").utf8)
                _ = reply.withUnsafeBytes { write(client, $0.baseAddress, $0.count) }
                close(client)
            }
        }.start()
    }

    deinit {
        shutdown(descriptor, Int32(SHUT_RDWR))
        close(descriptor)
    }

    /// The heads of the requests it was sent.
    var requests: [String] {
        heads.list.withLock { $0 }
    }

    private static func listening(_ descriptor: Int32) -> Bool {
        listen(descriptor, 8) == 0
    }

    #if canImport(Glibc)
    private static let streamType = Int32(SOCK_STREAM.rawValue)
    #else
    private static let streamType = SOCK_STREAM
    #endif
}

/// The bodies a redirect is tried with. Linux's Foundation, refused a redirect whose body is empty,
/// waits out the request's timeout before it answers (no request is sent on either way), so the
/// empty body is tried where the app runs, on Apple's.
#if canImport(Darwin)
let redirectBodies = ["", "{}"]
#else
let redirectBodies = ["{}"]
#endif

/// S1 and S4 past the first hop: a control plane answering with a redirect to a name the address
/// rule refuses reaches nothing there. No request goes to the second server, so neither the ticket
/// nor the token does, and the redirect is said.
@Suite(.timeLimit(.minutes(1))) struct RedirectTests {
    @Test(arguments: redirectBodies)
    func aRedirectIsNeverFollowedAndIsSaid(_ body: String) async throws {
        let elsewhere = try HTTPListener { _ in "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}" }
        let port = elsewhere.port
        let redirecting = try HTTPListener { line in
            let path = line.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
            return "HTTP/1.1 307 Temporary Redirect\r\nLocation: http://localhost:\(port)\(path)\r\nContent-Length: \(body.utf8.count)\r\n"
                + "Connection: close\r\n\r\n\(body)"
        }
        let address = try ControlPlane(address: "http://127.0.0.1:\(redirecting.port)")
        let vault = MemoryVault().holding(PasskeySession(sessionToken: "sess_redirect_probe", fde: "uday", expiresAt: .distantFuture), at: address)
        let account = Account(authenticator: ToldPasskey(), vault: vault)
        let enrolling = await refusal { () async throws(AccountRefusal) in try await account.enroll(ticket: aTicket, at: address) }
        let signedIn = try await account.current(at: address)
        #expect(enrolling?.reason == .redirected)
        #expect(enrolling?.sentence == "http://127.0.0.1:\(redirecting.port) answered with a redirect; Sail does not follow one.")
        guard case let .failure(refused) = signedIn?.whoami else {
            Issue.record("whoami answered across the redirect")
            return
        }
        #expect(refused.reason == .redirected)
        #expect(redirecting.requests.count == 2)
        #expect(redirecting.requests.contains { $0.contains("x-enrollment-ticket: \(aTicket.lowercased())") })
        #expect(elsewhere.requests.isEmpty, "the second server was sent: \(elsewhere.requests)")
    }
}
