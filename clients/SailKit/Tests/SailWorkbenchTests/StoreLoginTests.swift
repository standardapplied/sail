import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailWorkbench
import Testing

/// The Store a Login sheet's model and its request, read from the goldens the server recorded: the
/// kind is the first whose how names no command, the name is `default` unless the person has a login
/// of the harness, the store is one request holding the value in its body alone, each refusal is
/// the server's sentence under the field it is about, and the clipboard is cleared only while it
/// still holds the value sent. The value is an obvious fake, never kept by the model.
@Suite struct StoreLoginTests {
    static let pasted = "sk-ant-api03-store-login-tests-fake"
    static let address = "http://127.0.0.1:7979"

    static func offer(_ golden: String = "workspace-agents-dock-no-login", at index: Int = 0) throws -> AgentOffer {
        let agents = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: try Exchange(golden).body))
        return try #require(agents.offered.indices.contains(index) ? agents.offered[index] : nil, "\(golden) offers no harness \(index)")
    }

    static func logins(_ golden: String) throws -> [StoredLogin] {
        try Endpoint.logins.decode(HTTPResponse(status: 200, headers: [:], body: try Exchange(golden).body)).logins
    }

    /// The sheet for Claude Code in dock with no Claude Code login, the key pasted.
    static func filled(logins golden: String = "logins") throws -> StoreLogin {
        var sheet = try #require(StoreLogin(try offer(), logins: try logins(golden), address: address))
        sheet.typed(holdsSomething: true)
        return sheet
    }

    /// What the golden `name` answered, as the client reads it.
    static func answer(_ name: String) throws -> Result<LoginStored, SailError> {
        let exchange = try Exchange(name)
        do throws(SailError) {
            return .success(try Endpoint.storeLogin(pasted, kind: "api-key", harness: "claude-code", name: "default").decode(HTTPResponse(status: exchange.status, headers: [:], body: exchange.body)))
        } catch {
            return .failure(error)
        }
    }

    static let elsewhere = Clipboard(count: 7, text: "something else")

    typealias Clipboard = StoreLogin.Clipboard

    // MARK: The request

    @Test func theStoreIsOneRequestHoldingTheValueInItsBodyAlone() throws {
        var sheet = try Self.filled()
        let endpoint = try #require(sheet.store(Self.pasted, clipboard: Self.elsewhere))
        let request = endpoint.request
        #expect(request.method == .put && request.path == "/v1/logins/claude-code/default")
        let body = try #require(request.body)
        #expect(try JSONSerialization.jsonObject(with: body) as? [String: String] == ["kind": "api-key", "value": Self.pasted])
        let bytes = Data(Self.pasted.utf8)
        let described = [request.route, request.path, String(describing: request), String(reflecting: request), String(describing: endpoint), String(reflecting: endpoint), String(describing: sheet), String(reflecting: sheet)]
        let holding = described.filter { Data($0.utf8).range(of: bytes) != nil }
        #expect(holding.isEmpty, "\(holding.count) of the request's descriptions hold the value")
        #expect(request.headers["Content-Type"] == "application/json")
    }

    /// Every line a failed store could write, its error's description, its sentence and its route,
    /// for each refusal the server gives and for no answer at all: none holds the value's bytes.
    @Test func noLineAFailedStoreWritesHoldsTheValue() throws {
        let bytes = Data(Self.pasted.utf8)
        let failures = try Self.refusals.map { try Self.answer($0.golden) } + [.failure(.unreachable("Could not connect to the server.")), .failure(.unexpected(route: "PUT /v1/logins/claude-code/default", status: 502))]
        var lines: [String] = []
        for case let .failure(error) in failures {
            lines += [error.description, error.said(at: Self.address), error.route ?? "", String(reflecting: error)]
            var sheet = try Self.filled()
            _ = sheet.store(Self.pasted, clipboard: Clipboard(count: 1, text: Self.pasted))
            sheet.answered(.failure(error), clipboard: 1)
            lines.append(String(reflecting: sheet))
        }
        #expect(lines.count == 4 * (Self.refusals.count + 2) + Self.refusals.count + 2)
        let holding = lines.filter { Data($0.utf8).range(of: bytes) != nil }
        #expect(holding.isEmpty, "\(holding.count) lines a failure writes hold the value")
    }

    // MARK: The kind

    /// Claude Code offers an API key and a long-lived token; the token's how runs a command, so the
    /// sheet stores the API key, in the server's words. Codex CLI stores its API key. A harness whose
    /// every kind runs a command offers no sheet.
    @Test func theKindIsTheFirstWhoseHowNamesNoCommand() throws {
        let sheet = try #require(StoreLogin(try Self.offer(), logins: [], address: Self.address))
        #expect(sheet.kind.kind == "api-key" && sheet.label == "API key" && sheet.placeholder == "Paste your API key")
        #expect(sheet.help == "Create an API key in the Claude Console." && sheet.title == "Store a Claude Code login")
        #expect(sheet.fieldName == "Claude Code API key" && sheet.storeName == "Store Claude Code login")
        #expect(sheet.kept == "Kept on 127.0.0.1:7979 for your sessions only. Sail never shows it again.")
        let codex = try #require(StoreLogin(try Self.offer(at: 1), logins: [], address: "https://sail.example.com"))
        #expect(codex.kind.kind == "api-key" && codex.help == "Create an API key at platform.openai.com/api-keys." && codex.place == "sail.example.com")
        let tokenOnly = try Exchange("workspace-agents-dock-no-login").altered(at: ["offered", 0, "kinds"], to: [try Self.kinds()[1]])
        let agents = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: tokenOnly.body))
        #expect(StoreLogin(agents.offered[0], logins: [], address: Self.address) == nil, "a kind made by running a command was offered")
    }

    /// The recorded kinds of Claude Code, as the server wrote them.
    static func kinds() throws -> [Any] {
        let golden = try Goldens.object("http/workspace-agents-dock-no-login.json")
        let body = (golden["response"] as? [String: Any])?["body"] as? [String: Any]
        let offered = try #require((body?["offered"] as? [[String: Any]])?.first)
        return try #require(offered["kinds"] as? [Any])
    }

    /// A label is the kind's as a label starts, whatever case the server gives it.
    @Test func aLabelStartsUpperCase() throws {
        let lower = try Exchange("workspace-agents-dock-no-login").altered(at: ["offered", 0, "kinds", 0, "label"], to: "secret key")
        let agents = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: lower.body))
        let sheet = try #require(StoreLogin(agents.offered[0], logins: [], address: Self.address))
        #expect(sheet.label == "Secret key" && sheet.placeholder == "Paste your secret key")
    }

    // MARK: The name

    /// With no Claude Code login (a Codex one only), the name is `default` and not asked; with one
    /// that is not the default, it is asked, `work` suggested, saying which they have; the next free
    /// name when `work` is taken; and Store is off on an empty name.
    @Test func theNameIsDefaultUntilThePersonHasALoginOfTheHarness() throws {
        let first = try Self.filled()
        #expect(!first.asksName && first.name == "default" && first.taken.isEmpty && first.storable)
        var second = try Self.filled(logins: "logins-no-default")
        #expect(second.asksName && second.taken == ["home"] && second.name == "work" && second.already == "you already have “home”")
        #expect(StoreLogin.free(among: ["work", "work-2", "home"]) == "work-3" && StoreLogin.free(among: ["home"]) == "work")
        second.named("")
        #expect(!second.storable, "Store is on with no name")
        let unnamed = second.store(Self.pasted, clipboard: Self.elsewhere)
        #expect(unnamed == nil && second.stage == .filled)
        second.named("personal")
        let endpoint = try #require(second.store(Self.pasted, clipboard: Self.elsewhere))
        #expect(endpoint.request.path == "/v1/logins/claude-code/personal")
    }

    // MARK: The stages

    /// Empty: Store off and nothing sent. Filled: Store on. Storing: Store off, a second store and
    /// typing change nothing. Stored: the server's login.
    @Test func theSheetGoesFromEmptyToFilledToStoringToStored() throws {
        var sheet = try #require(StoreLogin(try Self.offer(), logins: [], address: Self.address))
        #expect(sheet.stage == .empty && !sheet.storable)
        let empty = sheet.store(Self.pasted, clipboard: Self.elsewhere)
        #expect(empty == nil && sheet.stage == .empty, "an empty field was stored")
        sheet.typed(holdsSomething: true)
        #expect(sheet.stage == .filled && sheet.storable)
        sheet.typed(holdsSomething: false)
        #expect(sheet.stage == .empty)
        sheet.typed(holdsSomething: true)
        let first = sheet.store(Self.pasted, clipboard: Self.elsewhere)
        #expect(first != nil && sheet.stage == .storing && !sheet.storable)
        let second = sheet.store(Self.pasted, clipboard: Self.elsewhere)
        #expect(second == nil, "a second store went out while the first was under way")
        sheet.typed(holdsSomething: false)
        sheet.named("other")
        #expect(sheet.stage == .storing && sheet.name == "default")
        sheet.answered(try Self.answer("login-stored"), clipboard: 7)
        guard case let .stored(login, cleared) = sheet.stage else { Issue.record("not stored: \(sheet.stage)"); return }
        #expect(login.name == "default" && login.kind == "api-key" && login.harness == "claude-code" && login.isDefault && !cleared)
        #expect(sheet.stored == "Stored Claude Code login “default”.")
        sheet.typed(holdsSomething: true)
        sheet.answered(try Self.answer("login-stored"), clipboard: 7)
        #expect(sheet.stage == .stored(login, clearsClipboard: false), "a stored sheet changed")
        var idle = try Self.filled()
        idle.answered(try Self.answer("login-stored"), clipboard: 7)
        #expect(idle.stage == .filled && idle.stored == nil, "an answer to no store was taken")
    }

    static let refusals: [(golden: String, said: String, under: StoreLogin.Field)] = [
        ("refusal-invalid-request-login-empty", "A login's value is empty: paste the key or token.", .value),
        ("refusal-invalid-request-login-lines", "A login's value is one line: paste only the key or token.", .value),
        ("refusal-invalid-request-login-long", "A login's value is at most 16 KiB.", .value),
        ("refusal-invalid-request-login-characters", "A login's value is printable ASCII with no space, quote, backslash, $ or backtick.", .value),
        ("refusal-invalid-request-login-name", "A login's name is 1 to 32 lowercase letters, digits and hyphens, starting with a letter or digit, such as work or personal.", .name),
    ]

    /// Each refusal the server gives is its sentence, under the value or, naming the name, under the
    /// name; it stays until the field holds something again, or the name changes.
    @Test(arguments: refusals.indices)
    func aRefusalIsTheServersSentenceUnderItsField(_ index: Int) throws {
        let refusal = Self.refusals[index]
        var sheet = try Self.filled(logins: "logins-no-default")
        _ = sheet.store(Self.pasted, clipboard: Clipboard(count: 3, text: Self.pasted))
        sheet.answered(try Self.answer(refusal.golden), clipboard: 3)
        #expect(sheet.stage == .refused(refusal.said, under: refusal.under) && !sheet.storable && sheet.stored == nil)
        sheet.typed(holdsSomething: false)
        #expect(sheet.stage == .refused(refusal.said, under: refusal.under), "an empty field took the refusal away")
        if refusal.under == .name {
            sheet.named("work")
            #expect(sheet.stage == .empty, "a new name kept the name's refusal")
        } else {
            sheet.named("work")
            #expect(sheet.stage == .refused(refusal.said, under: .value), "a new name took the value's refusal away")
        }
        sheet.typed(holdsSomething: true)
        #expect(sheet.stage == .filled && sheet.storable)
    }

    /// No answer: nothing was stored, said with the control plane's name; any other failure in its
    /// own words.
    @Test func noAnswerSaysNothingWasStored() throws {
        var sheet = try Self.filled()
        _ = sheet.store(Self.pasted, clipboard: Self.elsewhere)
        sheet.answered(.failure(.unreachable("Could not connect to the server.")), clipboard: 7)
        #expect(sheet.stage == .refused("Can't reach 127.0.0.1:7979. Nothing was stored.", under: .value))
        var proxied = try Self.filled()
        _ = proxied.store(Self.pasted, clipboard: Self.elsewhere)
        let error = SailError.unexpected(route: "PUT /v1/logins/claude-code/default", status: 502)
        proxied.answered(.failure(error), clipboard: 7)
        #expect(proxied.stage == .refused(error.said(at: "127.0.0.1:7979"), under: .value))
    }

    // MARK: The clipboard

    /// Stored with the clipboard still holding exactly the value, unchanged since: cleared, and said.
    /// Changed since, or holding something else when the store went out, or refused: left alone.
    @Test func theClipboardIsClearedOnlyWhileItStillHoldsTheValue() throws {
        let cases: [(then: Clipboard, now: Int, answer: String, clears: Bool)] = [
            (Clipboard(count: 4, text: Self.pasted), 4, "login-stored", true),
            (Clipboard(count: 4, text: Self.pasted), 5, "login-stored", false),
            (Clipboard(count: 4, text: Self.pasted + " "), 4, "login-stored", false),
            (Clipboard(count: 4, text: nil), 4, "login-stored", false),
        ]
        for (index, each) in cases.enumerated() {
            var sheet = try Self.filled()
            _ = sheet.store(Self.pasted, clipboard: each.then)
            sheet.answered(try Self.answer(each.answer), clipboard: each.now)
            guard case let .stored(_, cleared) = sheet.stage else { Issue.record("case \(index) not stored"); continue }
            #expect(cleared == each.clears, "case \(index): cleared \(cleared)")
            #expect(sheet.stored == "Stored Claude Code login “default”." + (each.clears ? " Removed the key from the clipboard." : ""), "case \(index)")
        }
        var refused = try Self.filled()
        _ = refused.store(Self.pasted, clipboard: Clipboard(count: 4, text: Self.pasted))
        refused.answered(try Self.answer("refusal-invalid-request-login-lines"), clipboard: 4)
        var again = refused
        again.typed(holdsSomething: true)
        _ = again.store(Self.pasted, clipboard: Clipboard(count: 9, text: "other"))
        again.answered(try Self.answer("login-stored"), clipboard: 4)
        #expect(again.stage == .stored(try #require(try? Self.answer("login-stored").get()).login, clearsClipboard: false), "a refused store's clipboard was cleared by the next")
    }
}
