import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailWorkbench
import Testing

/// The Store a Key sheet's model and its request, read from the goldens the server recorded: the
/// kind is the first whose how names no command, the name is `default` unless the person has a login
/// of the harness, the store is one request holding the value in its body alone, each refusal is
/// the server's sentence under the key or, for a name, the name named again, and the clipboard is cleared only while it
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
        var sheet = try #require(StoreLogin(try offer().declaration, logins: try logins(golden), address: address))
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
    /// What the recorded store of a name stored again says once of the sessions already running.
    static let note = " An agent session already running keeps the key it started with until it ends; no workspace keeps any of it."

    typealias Clipboard = StoreLogin.Clipboard

    // MARK: The request

    @Test func theStoreIsOneRequestHoldingTheValueInItsBodyAlone() throws {
        var sheet = try Self.filled()
        let stored = sheet.store(Self.pasted, clipboard: Self.elsewhere)
        let endpoint = try #require(stored)
        let request = endpoint.request
        #expect(request.method == .put && request.path == "/v1/logins/claude-code/default")
        let body = try #require(request.body)
        let sent = try JSONSerialization.jsonObject(with: body) as? [String: String] == ["kind": "api-key", "value": Self.pasted]
        #expect(sent, "the body is not the kind and the value")
        let bytes = Data(Self.pasted.utf8)
        let described = [request.route, request.path, String(describing: request), String(reflecting: request), String(describing: endpoint), String(reflecting: endpoint), String(describing: sheet), String(reflecting: sheet)]
        let holding = described.filter { Data($0.utf8).range(of: bytes) != nil }.count
        #expect(holding == 0, "of the request's descriptions, these many hold the value")
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
        let holding = lines.filter { Data($0.utf8).range(of: bytes) != nil }.count
        #expect(holding == 0, "of the lines a failure writes, these many hold the value")
    }

    // MARK: The kind

    /// Claude Code offers its API key, which the sheet stores in the server's words. Codex CLI stores
    /// its API key. A harness whose every kind runs a command (here the recorded kind given a
    /// command as its how) offers no sheet.
    @Test func theKindIsTheFirstWhoseHowNamesNoCommand() throws {
        let sheet = try #require(StoreLogin(try Self.offer().declaration, logins: [], address: Self.address))
        #expect(sheet.kind.kind == "api-key" && sheet.label == "API key" && sheet.placeholder == "Paste your API key")
        #expect(sheet.help == "Create an API key in the Claude Console." && sheet.title == "Store a Claude Code key")
        #expect(sheet.fieldName == "Claude Code API key" && sheet.storeName == "Store Claude Code key")
        #expect(sheet.kept == "Kept on 127.0.0.1:7979 for your sessions only. Sail never shows it again.")
        let codex = try #require(StoreLogin(try Self.offer(at: 1).declaration, logins: [], address: "https://sail.example.com"))
        #expect(codex.kind.kind == "api-key" && codex.help == "Create an API key at platform.openai.com/api-keys." && codex.place == "sail.example.com")
        let command: [Any] = [["text": "Run "], ["run": "program", "verb": "make-key", "args": []]]
        let commandOnly = try Exchange("workspace-agents-dock-no-login")
            .altered(at: ["offered", 0, "kinds", 0, "how"], to: command)
        let agents = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: commandOnly.body))
        #expect(StoreLogin(agents.offered[0].declaration, logins: [], address: Self.address) == nil, "a kind made by running a command was offered")
    }

    /// The sheet is the harness's declaration, which the server's list of harnesses and a
    /// workspace's offer both carry: the Account view and the New Session panel open the same sheet.
    @Test func theAgentsRouteAndAWorkspacesOfferOpenTheSameSheet() throws {
        let known = try Endpoint.knownAgents.decode(HTTPResponse(status: 200, headers: [:], body: try Exchange("agents").body))
        let logins = try Self.logins("logins-no-default")
        for (at, agent) in known.agents.enumerated() {
            let offered = try Self.offer(at: at)
            #expect(agent.declaration == offered.declaration, "\(agent.name) is declared otherwise than dock offers it")
            #expect(StoreLogin(agent.declaration, logins: logins, address: Self.address) == StoreLogin(offered.declaration, logins: logins, address: Self.address))
        }
    }

    /// Replace names the key it replaces, whatever else the person has: the store is sent under it.
    @Test func replaceStoresUnderTheNameReplaced() throws {
        var sheet = try #require(StoreLogin(try Self.offer().declaration, logins: try Self.logins("logins-no-default"), address: Self.address, naming: "home"))
        #expect(sheet.name == "home" && sheet.asksName)
        sheet.typed(holdsSomething: true)
        let store = sheet.store(Self.pasted, clipboard: Self.elsewhere)
        #expect(store?.request.route == "PUT /v1/logins/claude-code/home")
    }

    /// A label is the kind's as a label starts, whatever case the server gives it.
    @Test func aLabelStartsUpperCase() throws {
        let lower = try Exchange("workspace-agents-dock-no-login").altered(at: ["offered", 0, "kinds", 0, "label"], to: "secret key")
        let agents = try Endpoint.agents(workspace: "dock", project: "harbor").decode(HTTPResponse(status: 200, headers: [:], body: lower.body))
        let sheet = try #require(StoreLogin(agents.offered[0].declaration, logins: [], address: Self.address))
        #expect(sheet.label == "Secret key" && sheet.placeholder == "Paste your secret key")
    }

    // MARK: The name

    /// With no Claude Code login (a Codex one only), the name is `default` and not asked, and Change
    /// does nothing; with one that is not the default, `work` is suggested, saying which they have,
    /// or the next free name when `work` is taken. Change names it with the key put away: Store off,
    /// Done off on an empty name; Done returns to an empty key, the name kept, and the store names it.
    @Test func theNameIsDefaultUntilThePersonHasALoginOfTheHarness() throws {
        var first = try Self.filled()
        #expect(!first.asksName && first.name == "default" && first.taken.isEmpty && first.storable)
        first.changeName()
        #expect(first.stage == .filled, "a name not asked was named")
        var second = try Self.filled(logins: "logins-no-default")
        #expect(second.asksName && second.taken == ["home"] && second.name == "work" && second.already == "you already have “home”")
        #expect(StoreLogin.free(among: ["work", "work-2", "home"]) == "work-3" && StoreLogin.free(among: ["home"]) == "work")
        second.named("ignored")
        #expect(second.name == "work", "a name was typed while the key was asked")
        second.changeName()
        #expect(second.stage == .naming(refused: nil) && !second.storable && second.nameGiven)
        second.typed(holdsSomething: true)
        #expect(second.stage == .naming(refused: nil), "the key was typed while the name was named")
        second.named("")
        second.doneNaming()
        #expect(!second.nameGiven && second.stage == .naming(refused: nil), "Done left with no name")
        let unnamed = second.store(Self.pasted, clipboard: Self.elsewhere)
        #expect(unnamed == nil, "a store went out while the name was named")
        second.named("personal")
        second.doneNaming()
        #expect(second.stage == .empty && second.name == "personal")
        #expect(second.keyDropped && second.help == "Paste the key again.", "the key Change put away is not asked again")
        second.changeName()
        second.changeName()
        #expect(second.stage == .naming(refused: nil))
        second.doneNaming()
        second.typed(holdsSomething: true)
        #expect(!second.keyDropped && second.help == "Create an API key in the Claude Console.", "the key pasted again still says to paste it")
        let named = second.store(Self.pasted, clipboard: Self.elsewhere)
        let endpoint = try #require(named)
        #expect(endpoint.request.path == "/v1/logins/claude-code/personal")
    }

    // MARK: The stages

    /// Empty: Store off and nothing sent. Filled: Store on. Storing: Store off, a second store and
    /// typing change nothing. Stored: the server's login.
    @Test func theSheetGoesFromEmptyToFilledToStoringToStored() throws {
        var sheet = try #require(StoreLogin(try Self.offer().declaration, logins: [], address: Self.address))
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
        var asking = try Self.filled(logins: "logins-no-default")
        _ = asking.store(Self.pasted, clipboard: Self.elsewhere)
        asking.changeName()
        #expect(asking.stage == .storing, "Change acted while the store went")
        sheet.answered(try Self.answer("login-stored"), clipboard: 7)
        guard case let .stored(answer, cleared) = sheet.stage else { Issue.record("not stored: \(sheet.stage)"); return }
        let login = answer.login
        #expect(login.name == "default" && login.kind == "api-key" && login.harness == "claude-code" && login.isDefault && !cleared)
        #expect(sheet.stored == "Stored Claude Code key “default”.")
        var replacing = try Self.filled()
        _ = replacing.store(Self.pasted, clipboard: Self.elsewhere)
        replacing.answered(try Self.answer("login-replaced"), clipboard: 7)
        #expect(replacing.stored == "Stored Claude Code key “default”." + Self.note, "the server's note on the login it replaced is not said")
        sheet.typed(holdsSomething: true)
        sheet.answered(try Self.answer("login-stored"), clipboard: 7)
        #expect(sheet.stage == .stored(answer, clearsClipboard: false), "a stored sheet changed")
        var idle = try Self.filled()
        idle.answered(try Self.answer("login-stored"), clipboard: 7)
        #expect(idle.stage == .filled && idle.stored == nil, "an answer to no store was taken")
    }

    static let refusals: [(golden: String, said: String, name: Bool)] = [
        ("refusal-invalid-request-login-empty", "A key is empty: paste the key.", false),
        ("refusal-invalid-request-login-lines", "A key is one line: paste only the key.", false),
        ("refusal-invalid-request-login-long", "A key is at most 16 KiB.", false),
        ("refusal-invalid-request-login-characters", "A key is printable ASCII with no space, quote, backslash, $ or backtick.", false),
        ("refusal-invalid-request-login-name", "A key's name is 1 to 32 lowercase letters, digits and hyphens, starting with a letter or digit, such as work or personal.", true),
    ]

    /// Each refusal the server gives is its sentence: the key's under the key, staying until the field
    /// holds something again; a name's names the name again, its sentence under it until the name
    /// changes, and Done returns to the key.
    @Test(arguments: refusals.indices)
    func aRefusalIsTheServersSentenceUnderItsField(_ index: Int) throws {
        let refusal = Self.refusals[index]
        var sheet = try Self.filled(logins: "logins-no-default")
        _ = sheet.store(Self.pasted, clipboard: Clipboard(count: 3, text: Self.pasted))
        sheet.answered(try Self.answer(refusal.golden), clipboard: 3)
        let wanted: StoreLogin.Stage = refusal.name ? .naming(refused: refusal.said) : .refused(refusal.said)
        #expect(sheet.stage == wanted && !sheet.storable && sheet.stored == nil)
        sheet.typed(holdsSomething: false)
        #expect(sheet.stage == wanted, "an empty field took the refusal away")
        if refusal.name {
            #expect(!sheet.nameGiven && sheet.keyDropped && sheet.help == StoreLogin.pasteAgain, "Done is on with the refused name unchanged, or the key sent is not asked again")
            sheet.doneNaming()
            #expect(sheet.stage == wanted, "Done left with the refused name unchanged")
        }
        sheet.named("work")
        if refusal.name {
            #expect(sheet.stage == .naming(refused: nil) && sheet.nameGiven, "a new name kept the name's refusal")
            sheet.doneNaming()
        } else {
            #expect(sheet.stage == wanted, "a name typed while the key was asked took the key's refusal away")
        }
        sheet.typed(holdsSomething: true)
        #expect(sheet.stage == .filled && sheet.storable)
    }

    /// No answer: nothing was stored, said with the control plane's name; any other failure in its
    /// own words. The person's logins not read say why no sheet opens, in the same way.
    @Test func noAnswerSaysNothingWasStored() throws {
        var sheet = try Self.filled()
        _ = sheet.store(Self.pasted, clipboard: Self.elsewhere)
        sheet.answered(.failure(.unreachable("Could not connect to the server.")), clipboard: 7)
        #expect(sheet.stage == .refused("Can't reach 127.0.0.1:7979. Nothing was stored."))
        var proxied = try Self.filled()
        _ = proxied.store(Self.pasted, clipboard: Self.elsewhere)
        let error = SailError.unexpected(route: "PUT /v1/logins/claude-code/default", status: 502)
        proxied.answered(.failure(error), clipboard: 7)
        #expect(proxied.stage == .refused(error.said(at: "127.0.0.1:7979")))
        let refused = Refusal(code: .known(.forbidden), message: "This credential acts for no person, so it keeps no logins.")
        #expect(StoreLogin.unread(.refused(refused), address: Self.address) == refused.message)
        #expect(StoreLogin.unread(.unreachable("Could not connect to the server."), address: Self.address) == "Can't reach 127.0.0.1:7979. Nothing can be stored now.")
        #expect(StoreLogin.unread(error, address: Self.address) == error.said(at: "127.0.0.1:7979"))
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
            #expect(sheet.stored == "Stored Claude Code key “default”." + (each.clears ? " Removed the key from the clipboard." : ""), "case \(index)")
        }
        var refused = try Self.filled()
        _ = refused.store(Self.pasted, clipboard: Clipboard(count: 4, text: Self.pasted))
        refused.answered(try Self.answer("refusal-invalid-request-login-lines"), clipboard: 4)
        var again = refused
        again.typed(holdsSomething: true)
        _ = again.store(Self.pasted, clipboard: Clipboard(count: 9, text: "other"))
        again.answered(try Self.answer("login-stored"), clipboard: 4)
        #expect(again.stage == .stored(try #require(try? Self.answer("login-stored").get()), clearsClipboard: false), "a refused store's clipboard was cleared by the next")
    }
}
