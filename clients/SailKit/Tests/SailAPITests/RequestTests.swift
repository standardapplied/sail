import Foundation
import SailAPI
import Testing

@Suite struct RequestTests {
    @Test func aNameIsOneSegmentWhateverItHolds() {
        #expect(Endpoint.room("a/b..c+d é?&=").request.path == "/v1/rooms/a%2Fb%2E%2Ec%2Bd%20%C3%A9%3F%26%3D")
        #expect(Endpoint.room("..").request.path == "/v1/rooms/%2E%2E")
    }

    @Test func aFilterSendsOnlyWhatItNamesInTheServersOrder() {
        #expect(Endpoint.specs().request.path == "/v1/specs")
        let filter = SpecFilter(project: "harbor", status: .inProgress, assignee: "me", repo: "harbor-api", text: "ferry+berth")
        #expect(Endpoint.specs(filter).request.path
            == "/v1/specs?project=harbor&status=in_progress&assignee=me&repo=harbor-api&q=ferry%2Bberth")
        #expect(Endpoint.rooms().request.path == "/v1/rooms")
        #expect(Endpoint.messages(room: "harbor-design", before: "m-9", after: "m-1", limit: 2).request.path
            == "/v1/rooms/harbor-design/messages?before=m-9&after=m-1&limit=2")
    }

    @Test func aReadSendsNoBodyAndAPostSaysItsBodyIsJSON() throws {
        #expect(Endpoint.whoami.request == HTTPRequest(method: .get, path: "/v1/whoami"))
        let post = try Endpoint.post(MessagePost(body: "Aye", replyTo: "m-1", asksQuestion: true), room: "harbor-design")
        #expect(post.request.method == .post)
        #expect(post.request.headers == ["Content-Type": "application/json"])
        #expect(post.request.body == Data(#"{"body":"Aye","question":true,"reply_to":"m-1"}"#.utf8))
        #expect(MessagePost(body: "Aye", asksQuestion: true).asksQuestion)
        #expect(!MessagePost(body: "Aye").asksQuestion)
    }

    @Test func aBodyThatCannotBeWrittenAsJSONIsUnsendableNotATrap() {
        do throws(SailError) {
            _ = try SailJSON.encode(Double.nan)
            Issue.record("a number JSON cannot hold was encoded")
        } catch {
            guard case let .unsendable(reason) = error else {
                Issue.record("\(error)")
                return
            }
            #expect(reason.hasPrefix("Double does not encode as JSON: "))
        }
    }

    @Test func theClientSendsItsTokenOnEveryRequest() {
        let client = SailClient(transport: RefusingTransport(), token: "tok-1")
        #expect(client.authorized(Endpoint.whoami.request).headers == ["Authorization": "Bearer tok-1"])
    }
}

struct RefusingTransport: Transport {
    func send(_ request: HTTPRequest) throws(SailError) -> HTTPResponse {
        throw .unreachable(request.route)
    }

    func open(_ request: HTTPRequest) throws(SailError) -> HTTPStream {
        throw .unreachable(request.route)
    }
}
