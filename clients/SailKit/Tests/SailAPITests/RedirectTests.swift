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
/// request it was sent: its request line and headers, lowercased. It reads a request's body before
/// it answers, so closing never resets a connection with bytes unread.
final class HTTPListener: Sendable {
    let port: UInt16
    private let listener: HeldListener
    private let heads = Heads()

    private final class Heads: Sendable {
        let list = Mutex<[String]>([])
    }

    init(answer: @escaping @Sendable (_ requestLine: String) -> String) throws {
        let listener = try HeldListener.loopback()
        port = listener.port
        self.listener = listener
        Thread { [heads] in
            while true {
                let client: Int32
                switch listener.next() {
                case let .connection(accepted): client = accepted
                case .nothing: continue
                case .failed, .closed: return
                }
                var received: [UInt8] = []
                var buffer = [UInt8](repeating: 0, count: 4096)
                func headEnd() -> Int? {
                    received.indices.dropLast(3).first { received[$0 ..< $0 + 4].elementsEqual("\r\n\r\n".utf8) }.map { $0 + 4 }
                }
                while headEnd() == nil {
                    let count = read(client, &buffer, buffer.count)
                    guard count > 0 else { break }
                    received += buffer.prefix(count)
                }
                let end = headEnd() ?? received.count
                let head = String(decoding: received.prefix(end), as: UTF8.self).lowercased()
                let length = head.split(separator: "\r\n").first { $0.hasPrefix("content-length:") }
                    .flatMap { Int($0.dropFirst("content-length:".count).trimmingCharacters(in: .whitespaces)) } ?? 0
                var body = received.count - end
                while body < length {
                    let count = read(client, &buffer, min(buffer.count, length - body))
                    guard count > 0 else { break }
                    body += count
                }
                heads.list.withLock { $0.append(head) }
                let reply = Array(answer(head.split(separator: "\r\n").first.map(String.init) ?? "").utf8)
                _ = reply.withUnsafeBytes { write(client, $0.baseAddress, $0.count) }
                close(client)
            }
        }.start()
    }

    deinit {
        listener.close()
    }

    /// The heads of the requests it was sent.
    var requests: [String] {
        heads.list.withLock { $0 }
    }

}

/// S1 and S4 past the first hop: a control plane answering with a redirect to a name the address
/// rule refuses reaches nothing there. No request goes to the second server, so neither the ticket
/// nor the token does, and the redirect is said.
@Suite(.timeLimit(.minutes(1))) struct RedirectTests {
    @Test(arguments: ["", "{}"])
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
