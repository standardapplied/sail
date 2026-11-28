import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// The fake chat server later slices test against answers as the server's goldens do: a read with
/// items after its seq at once, else held until one is appended (answered once) or its bound
/// passes; a message with its seq, queued while a turn is open; an end, a restart, an ended sign-in
/// and a chat gone, each to every read it holds.
@Suite(.timeLimit(.minutes(1))) struct ChatDeskTests {
    static func client(_ desk: ChatDesk, told: Told = Told()) -> SailClient {
        SailClient(transport: desk, token: "tok") { told.tell() }
    }

    @Test func aReadWithLaterItemsIsAnsweredAtOnceAsTheServerAnswers() async throws {
        let desk = try ChatDesk("chat-items")
        let page = try await Self.client(desk).send(.chat(fifthChat, after: 0))
        #expect(page == (try Endpoint<ChatPage>.chat(fifthChat, after: 0).decode(response(try Exchange("chat-items")))))
        #expect(try await Self.client(desk).send(.chat(fifthChat, after: 3)).items.map(\.seq) == [4, 5])
    }

    @Test func aHeldReadIsAnsweredWithTheItemAppendedOnce() async throws {
        let desk = try ChatDesk("chat-items")
        let client = Self.client(desk)
        let read = Task { try await client.send(.chat(fifthChat, after: 5)) }
        await desk.holding(1)
        let seq = try desk.append("text", to: fifthChat, ["text": "Reading it.", "new_paragraph": true, "clipped": false])
        let page = try await read.value
        #expect(seq == 6 && page.items.map(\.seq) == [6] && !page.ended)
        let said = try Self.text("Reading it.")
        #expect(page.items.first?.body == said.map(ChatItem.Body.text))
        try desk.append("text", to: fifthChat, ["text": " More.", "new_paragraph": false, "clipped": false])
        #expect(desk.held == 0)
        #expect(try await client.send(.chat(fifthChat, after: 6)).items.map(\.seq) == [7])
    }

    @Test func messagesAreTakenWithTheirSeqQueuedWhileATurnIsOpenAsTheGoldensSay() async throws {
        let desk = try ChatDesk("chat-items")
        let client = Self.client(desk)
        let again = try await client.send(.send("again", chat: fifthChat))
        let queued = try await client.send(.send("and this", chat: fifthChat))
        #expect(again == (try Endpoint<ChatSent>.send("again", chat: fifthChat).decode(response(try Exchange("chat-message-again")))))
        #expect(queued == (try Endpoint<ChatSent>.send("and this", chat: fifthChat).decode(response(try Exchange("chat-message-queued")))))
        let golden = try Endpoint<ChatPage>.chat(fifthChat, after: 5).decode(response(try Exchange("chat-items-after")))
        #expect(try await client.send(.chat(fifthChat, after: 5)).items.map(\.body) == Array(golden.items.prefix(2).map(\.body)))
    }

    @Test func aHeldReadIsAnsweredEmptyWhenItsBoundPasses() async throws {
        let desk = try ChatDesk("chat-items")
        let read = Task { [client = Self.client(desk)] in try await client.send(.chat(fifthChat, after: 5)) }
        await desk.holding(1)
        desk.boundPasses()
        let page = try await read.value
        #expect(page.items.isEmpty && !page.ended)
    }

    @Test func aRestartDropsEveryHeldReadAndKeepsTheChat() async throws {
        let desk = try ChatDesk("chat-items")
        let client = Self.client(desk)
        let reads = (0 ..< 2).map { _ in Task { try await client.send(.chat(fifthChat, after: 5)) } }
        await desk.holding(2)
        desk.restart()
        for read in reads {
            await #expect(throws: SailError.unreachable("the chat server restarted")) { try await read.value }
        }
        #expect(try await client.send(.chat(fifthChat, after: 0)).items.count == 5)
    }

    @Test func anEndAnswersTheHeldReadAsEndedAndTakesNoMessage() async throws {
        let desk = try ChatDesk("chat-items")
        let client = Self.client(desk)
        let read = Task { try await client.send(.chat(fifthChat, after: 5)) }
        await desk.holding(1)
        _ = try await client.send(.endChat(fifthChat))
        let page = try await read.value
        #expect(page.ended && page.items.isEmpty)
        let refused = Refusal(code: .known(.conflict), message: "This chat has ended, so it takes no message.", action: "Open a new chat in workspace berth.")
        await #expect(throws: SailError.refused(refused)) { try await client.send(.send("more", chat: fifthChat)) }
        #expect(try await client.send(.chat(fifthChat, after: 0)).ended)
        #expect(try await Self.client(try ChatDesk("chat-items-ended")).send(.chat(fifthChat, after: 0)).ended)
    }

    @Test func anEndedSignInAnswersTheHeldReadAndEveryCallAfter() async throws {
        let desk = try ChatDesk("chat-items")
        let told = Told()
        let client = Self.client(desk, told: told)
        let read = Task { try await client.send(.chat(fifthChat, after: 5)) }
        await desk.holding(1)
        desk.signOut()
        await #expect(throws: SailError.signIn(.ended)) { try await read.value }
        await #expect(throws: SailError.signIn(.ended)) { try await client.send(.send("hi", chat: fifthChat)) }
        #expect(told.count == 2)
    }

    @Test func aChatGoneIsNotFoundToTheHeldReadAndAfter() async throws {
        let desk = try ChatDesk("chat-items")
        let client = Self.client(desk)
        let read = Task { try await client.send(.chat(fifthChat, after: 5)) }
        await desk.holding(1)
        desk.erase(fifthChat)
        let gone = Refusal(code: .known(.notFound), message: "There is no chat \(fifthChat) of yours here.")
        await #expect(throws: SailError.refused(gone)) { try await read.value }
        await #expect(throws: SailError.refused(gone)) { try await client.send(.chat(fifthChat, after: 0)) }
    }

    @Test func aCancelledReadLetsGoAndARouteItDoesNotServeIsUnreachable() async throws {
        let desk = try ChatDesk("chat-items")
        let client = Self.client(desk)
        let read = Task { try await client.send(.chat(fifthChat, after: 5)) }
        await desk.holding(1)
        read.cancel()
        await #expect(throws: SailError.unreachable("the read was cancelled")) { try await read.value }
        #expect(desk.held == 0)
        await #expect(throws: SailError.self) { try await client.send(.interrupt(chat: fifthChat)) }
        #expect(desk.requests.map(\.route).last == "POST /v1/sessions/\(fifthChat)/chat:interrupt")
    }

    static func text(_ text: String) throws -> ChatItem.Text? {
        let json = #"{"seq":1,"at":"\#(ChatDesk.at)","type":"text","text":"\#(text)","new_paragraph":true,"clipped":false}"#
        guard case let .text(said) = try SailJSON.decode(ChatItem.self, from: Data(json.utf8), route: "text").body else { return nil }
        return said
    }
}
