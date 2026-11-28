import Foundation
@testable import SailAPI
import SailKitTestSupport
import Synchronization
import Testing

/// A chat as the server writes it: every item of each golden log decoded whole, each type and each
/// field a case of this app's, a type it does not know kept by its name, a known one missing a field
/// refused naming its seq; an ended sign-in and a refusal said through the one check; the agents
/// route's forms and chat; and the person's preference.
@Suite(.timeLimit(.minutes(1))) struct ChatTests {
    static func page(_ exchange: Exchange) throws -> ChatPage {
        try Endpoint<ChatPage>.chat(fifthChat, after: 0).decode(response(exchange))
    }

    static func every() throws -> ChatPage {
        try SailJSON.decode(ChatPage.self, from: Data(Goldens.bytes("chat/items.json")), route: "chat/items.json")
    }

    @Test func aLogIsReadItemByItemWithEveryField() throws {
        let page = try Self.page(try Exchange("chat-items"))
        let golden = try #require(try Goldens.object("http/chat-items.json")["response"] as? [String: Any])
        let session = try #require((golden["body"] as? [String: Any])?["items"] as? [[String: Any]]).dropFirst().first
        #expect(!page.ended)
        #expect(page.items.map(\.seq) == [1, 2, 3, 4, 5] && page.items.allSatisfy { $0.at.milliseconds == Goldens.pinnedMilliseconds })
        #expect(page.items.map(\.body) == [
            .message(.init(text: "hi", queued: false)),
            .session(.init(
                model: "claude-haiku-5-5", mode: "default", commands: try #require(session?["commands"] as? [String]),
                skills: try #require(session?["skills"] as? [String]), clipped: false
            )),
            .tool(.init(
                under: nil, id: "toolu_011VvCwXcvkemp321CMEyur6", kind: .known(.command), words: "Print probe-ok",
                detail: .command(.init(line: "echo probe-ok", background: false, clipped: false)), clipped: false
            )),
            .toolDone(.init(under: nil, id: "toolu_011VvCwXcvkemp321CMEyur6", output: "probe-ok", failed: false, clipped: false)),
            .turnEnded(.init(costUsd: 3.01485E-4, tokensIn: 23708, tokensOut: 86, durationMs: 940)),
        ])
    }

    @Test func whatAPersonAnsweredIsReadAsAnswered() throws {
        let items = try Endpoint<ChatPage>.chat(sixthChat, after: 0).decode(response(try Exchange("chat-items-answered"))).items
        func body(_ seq: Int64) -> ChatItem.Body? {
            items.first { $0.seq == seq }?.body
        }
        #expect(body(1) == .modeSet(.init(mode: "acceptEdits", request: nil, by: "dana")))
        guard case let .permission(always) = body(6) else { Issue.record("seq 6 is \(String(describing: body(6)))"); return }
        #expect(always.always.map(\.words.count) == [3] && always.always[0].words[0].hasPrefix("Allow this exact command: `touch always.txt`"))
        #expect(always.kind == .known(.command) && always.agent == nil && always.reason == nil && always.call == "toolu_01VH3wM1frxF9fXQX779TBXL")
        #expect(body(5) == .answered(.init(
            request: chatRequest(7), decision: .known(.deny), message: "use the existing spinner instead", answers: nil, by: "dana"
        )))
        #expect(body(7) == .answered(.init(request: chatRequest(9), decision: .known(.allowAlways), message: nil, answers: nil, by: "dana")))
        guard case let .question(question) = body(12) else { Issue.record("seq 12 is \(String(describing: body(12)))"); return }
        #expect(question.questions.map(\.multiSelect) == [true, false] && question.questions.map(\.header) == ["Colors", "Size"])
        #expect(question.questions[0].choices == ["Red", "Blue", "Green"].map { (label: String) in ChatItem.Choice(label: label, about: "Select \(label)", clipped: false) })
        #expect(body(13) == .answered(.init(
            request: chatRequest(12), decision: .known(.answer), message: nil,
            answers: [
                .init(question: "Which colors?", chosen: ["Red", "Blue"], own: nil),
                .init(question: "Which size?", chosen: [], own: "teal, but only on weekends"),
            ],
            by: "dana"
        )))
        #expect(body(14) == .plan(.init(
            id: chatRequest(13), call: "toolu_013gcyynq43U4qWvaJZkUQ9Q", plan: "Create /home/dev/workspace/hello.txt containing \"bye\".\n", clipped: false
        )))
        #expect(body(17) == .withdrawn(.init(id: chatRequest(14))) && body(18) == .interrupted)
        #expect(body(20) == .modeSet(.init(mode: "plan", request: chatRequest(15), by: "dana")))
    }

    @Test func everyGoldenLogDecodes() throws {
        let after = try Endpoint<ChatPage>.chat(fifthChat, after: 5).decode(response(try Exchange("chat-items-after")))
        #expect(after.items.map(\.body) == [
            .message(.init(text: "again", queued: false)), .message(.init(text: "and this", queued: true)),
            .turnEnded(.init(costUsd: 3.01485E-4, tokensIn: 23708, tokensOut: 86, durationMs: 940)), .delivered(.init(message: 7)),
        ])
        let ended = try Endpoint<ChatPage>.chat(fifthChat, after: 10).decode(response(try Exchange("chat-items-ended")))
        #expect(ended.ended && ended.items.isEmpty)
    }

    /// Every type the server logs is in the server's own golden of them, and reads as its case.
    @Test func everyTypeTheServerLogsIsACaseWithEveryField() throws {
        let items = try Self.every().items
        let types = try #require((try Goldens.object("chat/items.json")["items"] as? [[String: Any]])?.compactMap { $0["type"] as? String })
        #expect(Set(types) == Set(ChatItemType.allCases.map(\.rawValue)))
        #expect(!items.contains { if case .unknown = $0.body { true } else { false } })
        let bodies = items.map(\.body)
        #expect(bodies[2] == .told(.init(words: "Sail restarted, so the turn stopped.")))
        #expect(bodies[3] == .answered(.init(request: "req-1", decision: .known(.allowAlways), message: nil, answers: nil, by: "dana")))
        #expect(bodies[5] == .session(.init(model: nil, mode: "default", commands: ["compact"], skills: ["review"], clipped: false)))
        #expect(bodies[6] == .modeChanged(.init(mode: "acceptEdits", clipped: false)))
        #expect(bodies[7] == .text(.init(under: nil, text: "Reading the file.", newParagraph: true, clipped: false)))
        #expect(bodies[8] == .text(.init(under: "toolu_sub", text: " and the next.", newParagraph: false, clipped: false)))
        #expect(bodies[15] == .toolDone(.init(under: nil, id: "toolu_cmd", output: "BUILD SUCCESSFUL", failed: false, clipped: false)))
        #expect(bodies[16] == .permission(.init(
            id: "req-2", call: "toolu_cmd", agent: "agent-1", kind: .known(.command), words: "Run the tests", reason: "It runs a command.",
            detail: .command(.init(line: "./gradlew test", background: true, clipped: false)),
            always: [.init(words: ["Allow ./gradlew test from now on."])], clipped: false
        )))
        #expect(bodies[17] == .question(.init(id: "req-3", call: "toolu_ask", questions: [.init(
            question: "Which size?", header: nil,
            choices: [.init(label: "Small", about: "The small one", clipped: false), .init(label: "Large", about: nil, clipped: false)],
            multiSelect: false, clipped: false
        )])))
        #expect(bodies[20] == .retrying(.init(attempt: 1, attempts: 10, delayMs: 500, error: "Overloaded", clipped: false)))
        #expect(bodies[21] == .compacted(.init(trigger: "auto", tokensBefore: 120_000, tokensAfter: 30_000, clipped: false)))
        #expect(bodies[22] == .interrupted && bodies[25] == .selfStarted)
        #expect(bodies[23] == .refused(.init(id: nil, words: "That mode is not one this chat has.", clipped: false)))
        #expect(bodies[24] == .unreadable(.init(tooLarge: true, stopped: false)))
        let failures = bodies.compactMap { body -> ChatItem.Failed? in if case let .failed(failed) = body { failed } else { nil } }
        #expect(failures.map(\.login) == [.refused, .limited(resets: "15:00 UTC"), .undelivered, nil])
        #expect(bodies[30] == .turnEnded(.init(costUsd: nil, tokensIn: 100, tokensOut: 20, durationMs: 900)))
    }

    @Test func everyDetailIsReadByItsOwnType() throws {
        let details = try Self.every().items.compactMap { item -> ChatDetail? in if case let .tool(tool) = item.body { tool.detail } else { nil } }
        #expect(details == [
            .command(.init(line: "./gradlew test", background: true, clipped: false)),
            .edit([
                .init(kind: .known(.change), path: "a.txt", from: nil, before: "old", after: "new", everywhere: true, clipped: false),
                .init(kind: .known(.add), path: "b.txt", from: nil, before: nil, after: "made", everywhere: false, clipped: false),
                .init(kind: .known(.delete), path: "c.txt", from: nil, before: "gone", after: nil, everywhere: false, clipped: false),
                .init(kind: .known(.move), path: "e.txt", from: "d.txt", before: "x", after: "y", everywhere: false, clipped: false),
            ]),
            .read(.init(path: "README.md", clipped: false)),
            .search(.init(what: "TODO", where: "src", clipped: false)),
            .fetch(.init(address: "https://example.com/docs", clipped: false)),
            .other(.init(name: "tracker_lookup", input: #"{"id":7}"#, clipped: false)),
        ])
    }

    @Test func aTypeThisAppDoesNotKnowIsKeptWithItsSeqAndThePageReads() throws {
        let page = try Self.page(try Exchange("chat-items").altered(at: ["items", 2, "type"], to: "drydock"))
        #expect(page.items.count == 5 && page.items[2].seq == 3 && page.items[2].at.milliseconds == Goldens.pinnedMilliseconds)
        #expect(page.items[2].body == .unknown("drydock"))
        let detail = try Self.page(try Exchange("chat-items").altered(at: ["items", 2, "detail", "type"], to: "telescope"))
        guard case let .tool(tool) = detail.items[2].body else { Issue.record("not a tool"); return }
        #expect(tool.detail == .unknown("telescope"))
        let kind = try Self.page(try Exchange("chat-items").altered(at: ["items", 2, "kind"], to: "lighthouse"))
        guard case let .tool(lit) = kind.items[2].body else { Issue.record("not a tool"); return }
        #expect(lit.kind == .unknown("lighthouse"))
    }

    @Test func aKnownTypeMissingAFieldFailsThePageNamingTheSeq() throws {
        let route = "GET /v1/sessions/\(fifthChat)/chat?after=0"
        #expect(throws: SailError.undecodable(Undecodable(route: route, field: "items[2].id", reason: "it is missing, in the item at seq 3"))) {
            try Self.page(try Exchange("chat-items").altered(at: ["items", 2, "id"], to: nil))
        }
        #expect(throws: SailError.undecodable(Undecodable(route: route, field: "items[2].detail.background", reason: "it is missing, in the item at seq 3"))) {
            try Self.page(try Exchange("chat-items").altered(at: ["items", 2, "detail", "background"], to: nil))
        }
        #expect(throws: SailError.undecodable(Undecodable(route: route, field: "items[4].tokens_in", reason: "it is null, in the item at seq 5"))) {
            try Self.page(try Exchange("chat-items").altered(at: ["items", 4, "tokens_in"], to: NSNull()))
        }
        #expect(throws: SailError.undecodable(Undecodable(route: route, field: "items[0].seq", reason: "it is missing"))) {
            try Self.page(try Exchange("chat-items").altered(at: ["items", 0, "seq"], to: nil))
        }
    }

    @Test func anEndedSignInMidChatIsTheEndedSignInAndToldOnce() async throws {
        let told = Told()
        let unsigned = Exchange(method: "GET", path: "/v1/sessions/\(fifthChat)/chat?after=0", status: 401, body: try Exchange("refusal-missing-bearer-token").body)
        let client = SailClient(transport: RecordedServer([unsigned]), token: "tok") { told.tell() }
        await #expect(throws: SailError.signIn(.ended)) { try await client.send(.chat(fifthChat, after: 0)) }
        #expect(told.count == 1)
    }

    @Test func aWithdrawnRequestIsRefusedInTheServersWords() async throws {
        let (result, _) = try await answer("refusal-chat-request-withdrawn") { client in
            try await client.send(.answer(chatRequest(14), chat: sixthChat, with: .allow))
        }
        #expect(throws: SailError.refused(Refusal(
            code: .known(.requestWithdrawn), message: "Claude Code no longer waits for this answer, so it was not sent.",
            action: "Answer what it asks next, or send a message."
        ))) { try result.get() }
    }

    @Test func eachRouteReadsItsAnswerAsTheServerWroteIt() throws {
        func read<Response>(_ endpoint: Endpoint<Response>, _ name: String) throws -> Response {
            try endpoint.decode(response(try Exchange(name)))
        }
        #expect(try read(.openChat(claudeChat, workspace: "berth", project: "harbor"), "chat-opened") == ChatOpened(session: fifthChat))
        #expect(try read(.send("hi", chat: fifthChat), "chat-message-sent") == ChatSent(seq: 1, queued: false))
        #expect(try read(.send("and this", chat: fifthChat), "chat-message-queued") == ChatSent(seq: 7, queued: true))
        #expect(try read(.answer(chatRequest(9), chat: sixthChat, with: .allowAlways), "chat-request-allowed-always") == ChatLogged(seq: 7))
        #expect(try read(.setMode("plan", chat: sixthChat), "chat-mode-set") == ChatLogged(seq: 20))
        #expect(try read(.interrupt(chat: sixthChat), "chat-stopping") == ChatStopping(stopping: true))
        #expect(try read(.interrupt(chat: sixthChat), "chat-not-stopping") == ChatStopping(stopping: false))
        #expect(try read(.endChat(fifthChat), "chat-ended") == NoContent())
    }

    @Test func eachDecisionIsSentByItsOwnName() throws {
        #expect(ChatAnswer.allow.decision.rawValue == "allow" && ChatAnswer.allowAlways.decision.rawValue == "allow_always")
        #expect(ChatAnswer.deny(nil).decision == .deny && ChatAnswer.answer([:]).decision == .answer)
        let denied = try #require(try Endpoint<ChatLogged>.answer(chatRequest(7), chat: sixthChat, with: .deny(nil)).request.body)
        #expect(String(decoding: denied, as: UTF8.self) == #"{"decision":"deny"}"#)
    }

    /// The agents route names the forms each harness opens in and how a chat of it starts.
    @Test func everyOfferSaysItsFormsAndHowItChats() throws {
        for name in try Goldens.names(in: "http").filter({ $0.hasPrefix("workspace-agents") }) {
            let agents = try Endpoint.agents(workspace: "any", project: "harbor").decode(response(try Exchange(name)))
            for offer in agents.offered {
                #expect(!offer.forms.isEmpty, "\(name): \(offer.harness) opens in no form")
                #expect(offer.forms.contains(.known(.chat)) == (offer.chat != nil), "\(name): \(offer.harness)")
            }
        }
        let agents = try Endpoint.agents(workspace: "berth", project: "harbor").decode(response(try Exchange("workspace-agents")))
        #expect(agents.offered.map(\.forms) == [[.known(.terminal), .known(.chat)], [.known(.terminal)]])
        let chat = try #require(agents.offered.first?.chat)
        #expect(chat.modes.map(\.label) == ["Asks first", "Edits freely", "Plans first"] && chat.modeDefault == "default" && chat.inside)
    }

    @Test func thePreferenceIsReadAndKeptByItsWord() throws {
        #expect(try Endpoint.preferences.decode(response(try Exchange("preferences"))) == Preferences(sessionForm: .known(.chat)))
        let kept = try Endpoint.setPreferences(Preferences(sessionForm: .known(.both)))
        #expect(try kept.decode(response(try Exchange("preferences-set"))) == Preferences(sessionForm: .known(.both)))
        let later = try Exchange("preferences").altered(at: ["session_form"], to: "pair")
        #expect(try Endpoint.preferences.decode(response(later)).sessionForm == .unknown("pair"))
    }
}

/// How many times a client was told its sign-in ended.
final class Told: Sendable {
    private let told = Mutex(0)

    func tell() {
        told.withLock { $0 += 1 }
    }

    var count: Int {
        told.withLock { $0 }
    }
}
