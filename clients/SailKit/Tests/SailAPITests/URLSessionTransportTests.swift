import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import SailAPI
import Testing

@Suite struct URLSessionTransportTests {
    let transport: URLSessionTransport = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [EchoProtocol.self]
        return URLSessionTransport(base: URL(string: "https://box.example.com")!, configuration: configuration)
    }()

    @Test func aRequestReachesTheServerWithItsMethodPathHeadersAndBody() async throws {
        let request = HTTPRequest(
            method: .post, path: "/v1/rooms/harbor-design/messages?limit=2",
            headers: ["Authorization": "Bearer tok"], body: Data(#"{"body":"Aye"}"#.utf8)
        )
        let response = try await transport.send(request)
        #expect(response.status == 201)
        #expect(response.headers["x-echo"] == "yes")
        let echoed = try JSONDecoder().decode([String: String].self, from: response.body)
        #expect(echoed == [
            "method": "POST",
            "url": "https://box.example.com/v1/rooms/harbor-design/messages?limit=2",
            "authorization": "Bearer tok",
            "body": #"{"body":"Aye"}"#,
        ])
    }

    @Test func aStreamYieldsItsBodyAsItArrives() async throws {
        let stream = try await transport.open(HTTPRequest(method: .get, path: "/v1/events/stream"))
        #expect(stream.status == 201)
        var chunks: [[UInt8]] = []
        for try await chunk in stream.body {
            chunks.append(chunk)
        }
        #expect(chunks.flatMap(\.self) == Array(EchoProtocol.streamed.utf8))
    }

    @Test func aServerThatCannotBeReachedIsUnreachable() async {
        let request = HTTPRequest(method: .get, path: "/unreachable")
        await #expect(throws: SailError.self) { try await transport.send(request) }
        await #expect(throws: SailError.self) { try await transport.open(request) }
        do {
            _ = try await transport.send(request)
        } catch {
            guard case let .unreachable(reason) = error else {
                Issue.record("\(error)")
                return
            }
            #expect(reason.hasPrefix("https://box.example.com: "))
        }
    }
}

/// Answers each request in the process: a stream path with its chunks, an unreachable path with a
/// connection error, and anything else with what it was sent.
final class EchoProtocol: URLProtocol {
    static let streamed = ": subscribed\n\nid: 1\ndata: {}\n\n"

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 201, httpVersion: "HTTP/1.1", headerFields: ["X-Echo": "yes"])
        else { return }
        if url.path == "/unreachable" {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        for chunk in url.path.hasSuffix("/stream") ? chunks() : [echo()] {
            client?.urlProtocol(self, didLoad: chunk)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private func chunks() -> [Data] {
        let bytes = Array(Self.streamed.utf8)
        return stride(from: 0, to: bytes.count, by: 5).map { Data(bytes[$0 ..< min($0 + 5, bytes.count)]) }
    }

    private func echo() -> Data {
        let echoed = [
            "method": request.httpMethod ?? "",
            "url": request.url?.absoluteString ?? "",
            "authorization": request.value(forHTTPHeaderField: "Authorization") ?? "",
            "body": String(decoding: body(), as: UTF8.self),
        ]
        return (try? JSONEncoder().encode(echoed)) ?? Data()
    }

    private func body() -> Data {
        if let body = request.httpBody {
            return body
        }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }
        return data
    }
}
