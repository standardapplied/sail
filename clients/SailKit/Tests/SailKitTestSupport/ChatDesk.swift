import Foundation
import SailAPI
import Synchronization

/// A chat server for tests, as a ``Transport``. It holds each chat's items and reads them as the
/// server does: at once when it holds items after the seq asked from or the chat has ended, else
/// held until an item is appended or the hold's bound passes (``boundPasses()``, the test's clock,
/// never the wall's). It takes a message with its seq, queued while a turn is open, and ends a
/// chat (`POST /v1/sessions/{id}:end` or ``end(_:)``). It drops every held read as a restart does,
/// answers every call as an ended sign-in (401), and answers a chat gone as not found (404).
/// Seeded from `chat-items*.json` goldens. Any other route is unreachable through it.
public final class ChatDesk: Transport, Sendable {
    /// When every item it logs was logged: the instant the server's goldens pin.
    public static let at = "2026-11-11T09:00:00.656Z"

    private struct Chat {
        var items: [(seq: Int64, json: Data)] = []
        var ended = false
        var turnOpen = false
    }

    private struct Hold {
        let session: String
        let after: Int64
        let answer: CheckedContinuation<Result<HTTPResponse, SailError>, Never>
    }

    private struct State {
        var chats: [String: Chat] = [:]
        var holds: [Int: Hold] = [:]
        var nextHold = 0
        var signedOut = false
        var requests: [HTTPRequest] = []
        var watchers: [(count: Int, waiter: CheckedContinuation<Void, Never>)] = []
    }

    private let state = Mutex(State())

    /// Holding the chats `goldens` read, each by the session its path names, in order.
    public init(_ goldens: String...) throws {
        for name in goldens {
            let exchange = try Exchange(name)
            guard let session = exchange.path.split(separator: "/").dropFirst(2).first.map(String.init),
                  let page = try JSONSerialization.jsonObject(with: exchange.body) as? [String: Any],
                  let items = page["items"] as? [[String: Any]]
            else { throw GoldenError(reason: "\(name) is no page of a chat") }
            state.withLock { state in
                if state.chats[session] == nil { state.chats[session] = Chat() }
            }
            for item in items {
                try log(in: session) { _, _ in item }
            }
            if page["ended"] as? Bool == true { end(session) }
        }
    }

    /// Every request it was sent, in order.
    public var requests: [HTTPRequest] {
        state.withLock { $0.requests }
    }

    /// How many reads it holds now.
    public var held: Int {
        state.withLock { $0.holds.count }
    }

    /// Logs an item of `type` with `fields` in chat `session`, a new one where it holds none, and
    /// answers the reads it holds there; the item's seq.
    @discardableResult
    public func append(_ type: String, to session: String, _ fields: [String: any Sendable] = [:]) throws -> Int64 {
        try log(in: session) { _, seq in Self.item(type, seq: seq, fields) }.seq
    }

    private static func item(_ type: String, seq: Int64, _ fields: [String: Any]) -> [String: Any] {
        fields.merging(["seq": seq, "at": at, "type": type]) { _, given in given }
    }

    /// Answers every read it holds with no items, as the server does when its hold's bound passes.
    public func boundPasses() {
        answer { _ in true }
    }

    /// Drops every read it holds, as a server that restarts does; the chats stay.
    public func restart() {
        let dropped = state.withLock { state in
            defer { state.holds = [:] }
            return state.holds.values.map(\.answer)
        }
        dropped.forEach { $0.resume(returning: .failure(.unreachable("the chat server restarted"))) }
    }

    /// Ends chat `session`: the reads it holds there are answered as ended, and it takes no message.
    public func end(_ session: String) {
        state.withLock { $0.chats[session]?.ended = true }
        answer { $0.session == session }
    }

    /// Erases chat `session`: every call about it is not found, the reads it holds included.
    public func erase(_ session: String) {
        state.withLock { _ = $0.chats.removeValue(forKey: session) }
        answer { $0.session == session }
    }

    /// Ends the caller's sign-in: every call is answered as one with no session, the held included.
    public func signOut() {
        state.withLock { $0.signedOut = true }
        answer { _ in true }
    }

    /// Returns once it holds `count` reads, or the waiting test is cancelled.
    public func holding(_ count: Int) async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { waiter in
                let reached = state.withLock { state in
                    let reached = state.holds.count >= count || Task.isCancelled
                    if !reached { state.watchers.append((count, waiter)) }
                    return reached
                }
                if reached { waiter.resume() }
            }
        } onCancel: {
            let watchers = state.withLock { state in
                defer { state.watchers = [] }
                return state.watchers.map(\.waiter)
            }
            watchers.forEach { $0.resume() }
        }
    }

    public func send(_ request: HTTPRequest) async throws(SailError) -> HTTPResponse {
        state.withLock { $0.requests.append(request) }
        let route = try Route(request)
        if state.withLock({ $0.signedOut }) { return Self.signedOut }
        guard state.withLock({ $0.chats[route.session] != nil }) else { return Self.notFound(route.session) }
        switch (request.method, route.what) {
        case let (.get, .read(after)): return try await read(route.session, after: after).get()
        case (.post, .send): return try send(request, in: route.session)
        case (.post, .end):
            end(route.session)
            return HTTPResponse(status: 204, body: Data())
        default: throw .unreachable("ChatDesk answers no \(request.route)")
        }
    }

    public func open(_ request: HTTPRequest) async throws(SailError) -> HTTPStream {
        state.withLock { $0.requests.append(request) }
        throw .unreachable("ChatDesk streams nothing: \(request.route)")
    }

    private func read(_ session: String, after: Int64) async -> Result<HTTPResponse, SailError> {
        let id = state.withLock { state in
            defer { state.nextHold += 1 }
            return state.nextHold
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { answer in
                let (page, reached) = state.withLock { state -> (Result<HTTPResponse, SailError>?, [CheckedContinuation<Void, Never>]) in
                    if Task.isCancelled { return (.failure(.unreachable("the read was cancelled")), []) }
                    if let page = Self.page(state, session, after: after) { return (.success(page), []) }
                    state.holds[id] = Hold(session: session, after: after, answer: answer)
                    let reached = state.watchers.filter { $0.count <= state.holds.count }.map(\.waiter)
                    state.watchers.removeAll { $0.count <= state.holds.count }
                    return (nil, reached)
                }
                reached.forEach { $0.resume() }
                if let page { answer.resume(returning: page) }
            }
        } onCancel: {
            state.withLock { $0.holds.removeValue(forKey: id) }?.answer.resume(returning: .failure(.unreachable("the read was cancelled")))
        }
    }

    private func send(_ request: HTTPRequest, in session: String) throws(SailError) -> HTTPResponse {
        guard let body = request.body, let text = (try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["text"] as? String else {
            throw .unreachable("ChatDesk takes a message as {\"text\"}")
        }
        if state.withLock({ $0.chats[session]?.ended == true }) { return Self.recorded("refusal-chat-ended") }
        do {
            let message = try log(in: session) { chat, seq in Self.item("message", seq: seq, ["text": text, "queued": chat.turnOpen]) }
            return Self.ok(["queued": message.queued ?? false, "schema_version": 1, "seq": message.seq])
        } catch {
            throw .unsendable("\(error)")
        }
    }

    /// What the desk reads of an item it logs.
    private struct Head: Decodable {
        let seq: Int64
        let type: String
        let queued: Bool?
    }

    /// Logs the item `make` makes of chat `session` as it stands and the seq next in it, a new chat
    /// where it holds none, and answers the reads it holds there.
    @discardableResult
    private func log(in session: String, _ make: (Chat, Int64) -> [String: Any]) throws -> Head {
        let head = try state.withLock { state throws -> Head in
            var chat = state.chats[session] ?? Chat()
            let json = try JSONSerialization.data(withJSONObject: make(chat, (chat.items.last?.seq ?? 0) + 1), options: .sortedKeys)
            let head = try JSONDecoder().decode(Head.self, from: json)
            chat.items.append((head.seq, json))
            chat.turnOpen = Self.opens(head.type, queued: head.queued == true) ?? chat.turnOpen
            state.chats[session] = chat
            return head
        }
        answer { $0.session == session && $0.after < head.seq }
        return head
    }

    /// Whether an item of `type` opens a turn (true) or closes one (false), as the server's rule
    /// has it; nil for one that does neither.
    private static func opens(_ type: String, queued: Bool) -> Bool? {
        switch type {
        case "message": queued ? nil : true
        case "delivered", "self_started": true
        case "turn_ended", "interrupted": false
        default: nil
        }
    }

    /// Answers each held read `picked` picks with what it is owed now: the items after its seq, the
    /// chat's end, its absence or the sign-in's, or, the bound passed, none.
    private func answer(_ picked: (Hold) -> Bool) {
        let answered = state.withLock { state -> [(CheckedContinuation<Result<HTTPResponse, SailError>, Never>, HTTPResponse)] in
            let ids = state.holds.filter { picked($0.value) }.keys
            return ids.compactMap { id in
                state.holds.removeValue(forKey: id).map { hold in
                    (hold.answer, Self.owed(state, hold) ?? Self.empty)
                }
            }
        }
        answered.forEach { $0.0.resume(returning: .success($0.1)) }
    }

    private static func owed(_ state: State, _ hold: Hold) -> HTTPResponse? {
        if state.signedOut { return signedOut }
        guard state.chats[hold.session] != nil else { return notFound(hold.session) }
        return page(state, hold.session, after: hold.after)
    }

    /// The page a read after `after` is answered with now, nil while it would be held.
    private static func page(_ state: State, _ session: String, after: Int64) -> HTTPResponse? {
        guard let chat = state.chats[session] else { return nil }
        let items = chat.items.filter { $0.seq > after }.map(\.json)
        guard !items.isEmpty || chat.ended else { return nil }
        return page(items, ended: chat.ended)
    }

    private static func page(_ items: [Data], ended: Bool) -> HTTPResponse {
        let listed = items.map { String(decoding: $0, as: UTF8.self) }.joined(separator: ",")
        return HTTPResponse(status: 200, body: Data(#"{"ended":\#(ended),"items":[\#(listed)],"schema_version":1}"#.utf8))
    }

    private static let empty = page([], ended: false)

    private static var signedOut: HTTPResponse {
        recorded("refusal-missing-bearer-token")
    }

    private static func notFound(_ session: String) -> HTTPResponse {
        refusal(404, code: "not_found", message: "There is no chat \(session) of yours here.")
    }

    private static func recorded(_ name: String) -> HTTPResponse {
        guard let exchange = try? Exchange(name) else { return refusal(500, code: "internal", message: "\(name) is no golden") }
        return HTTPResponse(status: exchange.status, body: exchange.body)
    }

    private static func refusal(_ status: Int, code: String, message: String) -> HTTPResponse {
        ok(["error": ["code": code, "message": message, "field_errors": [Any]()], "schema_version": 1], status: status)
    }

    private static func ok(_ body: [String: Any], status: Int = 200) -> HTTPResponse {
        HTTPResponse(status: status, body: (try? JSONSerialization.data(withJSONObject: body, options: .sortedKeys)) ?? Data())
    }

    /// A request's route as the desk reads it: the chat it names and what it asks of it.
    private struct Route {
        enum What {
            case read(after: Int64)
            case send
            case end
        }

        let session: String
        let what: What

        init(_ request: HTTPRequest) throws(SailError) {
            let parts = request.path.split(separator: "?", maxSplits: 1)
            let segments = (parts.first ?? "").split(separator: "/").map(String.init)
            let query = parts.dropFirst().first.map(String.init) ?? ""
            guard segments.count >= 3, segments[0] == "v1", segments[1] == "sessions" else {
                throw .unreachable("ChatDesk answers no \(request.route)")
            }
            switch Array(segments.dropFirst(3)) {
            case ["chat"]:
                let after = query.hasPrefix("after=") ? Int64(query.dropFirst("after=".count)) : nil
                guard let after else { throw .unreachable("ChatDesk reads a chat after a seq: \(request.route)") }
                (session, what) = (segments[2], .read(after: after))
            case ["chat", "messages"]: (session, what) = (segments[2], .send)
            case [] where segments[2].hasSuffix(":end"): (session, what) = (String(segments[2].dropLast(":end".count)), .end)
            default: throw .unreachable("ChatDesk answers no \(request.route)")
            }
        }
    }
}
