import Foundation
import SailAPI
import SailKitTestSupport
import Testing

/// What the Account view says, read from the goldens the server recorded: the session's end and
/// whether it ends soon, a row per passkey of the caller's own, a row per harness the server lists
/// with its key or how it signs in without one, what each removal asks, and what a group shows
/// while its first answer is awaited or its last call failed.
@Suite(.timeLimit(.minutes(1))) struct AccountPageTests {
    static let clock = AccountPage.Clock(calendar: {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        return calendar
    }(), locale: Locale(identifier: "en_GB"))

    static func instant(_ text: String) throws -> Date {
        try Date.ISO8601FormatStyle().parse(text)
    }

    static func decoded<Response>(_ endpoint: Endpoint<Response>, _ golden: String) throws -> Response {
        try endpoint.decode(response(try Exchange(golden)))
    }

    static func agents(_ golden: String = "agents") throws -> [KnownAgent] {
        try decoded(.knownAgents, golden).agents
    }

    static func logins(_ golden: String) throws -> [StoredLogin] {
        try decoded(.logins, golden).logins
    }

    /// "Signed in until 25 December 2026, 16:47 · 29 days left"; under three days left the line
    /// says when it ends and is drawn as a warning.
    @Test func theSessionsEndAndWhetherItEndsSoon() throws {
        let now = try Self.instant("2026-11-26T16:47:00Z")
        let later = AccountPage.ending(try Self.instant("2026-12-25T16:47:00Z"), now: now, clock: Self.clock)
        #expect(later == AccountPage.Ending(said: "Signed in until 25 December 2026, 16:47 · 29 days left", soon: false))
        let soon = AccountPage.ending(try Self.instant("2026-11-28T16:47:00Z"), now: now, clock: Self.clock)
        #expect(soon == AccountPage.Ending(said: "Signed in until 28 November 2026, 16:47 · ends in 2 days", soon: true))
        let edge = AccountPage.ending(now.addingTimeInterval(3 * 86_400), now: now, clock: Self.clock)
        #expect(!edge.soon && edge.said.hasSuffix(" · 3 days left"), "three days left is not soon: \(edge.said)")
        #expect(AccountPage.ending(now.addingTimeInterval(86_400), now: now, clock: Self.clock).said.hasSuffix(" · ends in 1 day"))
        #expect(AccountPage.ending(now.addingTimeInterval(3600), now: now, clock: Self.clock).said.hasSuffix(" · ends within a day"))
        let almostThree = AccountPage.ending(now.addingTimeInterval(2 * 86_400 + 23 * 3600), now: now, clock: Self.clock)
        #expect(almostThree.soon && almostThree.said.hasSuffix(" · ends in 2 days"), "2 days 23 hours left reads \(almostThree.said)")
    }

    /// A session whose end has passed says it ended, as the welcome says it (the day and the month),
    /// and offers Sign In Again.
    @Test func aSessionPastItsEndSaysItEnded() throws {
        let now = try Self.instant("2026-11-26T16:47:00Z")
        let ended = AccountPage.ending(now.addingTimeInterval(-600), now: now, clock: Self.clock)
        #expect(ended == AccountPage.Ending(said: "Your sign-in ended on 26 November.", soon: true))
        #expect(AccountPage.ending(now, now: now, clock: Self.clock).said == "Your sign-in ended on 26 November.")
    }

    /// Dates are said in the clock's own time zone, not the machine's: the end in Auckland falls on
    /// the next day there, and a passkey used on a UTC day that is already over in Auckland was not
    /// used today there.
    @Test func datesAreSaidInTheClocksTimeZone() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "Pacific/Auckland"))
        let auckland = AccountPage.Clock(calendar: calendar, locale: Locale(identifier: "en_GB"))
        let now = try Self.instant("2026-11-26T16:47:00Z")
        let later = AccountPage.ending(try Self.instant("2026-12-25T16:47:00Z"), now: now, clock: auckland)
        #expect(later.said == "Signed in until 26 December 2026, 05:47 · 29 days left", "\(later.said)")
        let passkeys = try Self.decoded(.passkeys, "passkeys").passkeys
        let noonInUTC = try Self.instant("2026-11-11T12:00:00Z")
        #expect(AccountPage.passkeyRows(passkeys, now: noonInUTC, clock: Self.clock).first?.detail == "Added 11 November 2026 · last used today")
        let rows = AccountPage.passkeyRows(passkeys, now: noonInUTC, clock: auckland)
        #expect(rows.first?.detail == "Added 11 November 2026 · last used 11 November", "\(rows.map(\.detail))")
    }

    /// A row per passkey of the caller's, in the server's order: its label, "This Mac" on the one
    /// this session signed in with, when it was added and last used ("today", a day of this year, or
    /// none before its first use).
    @Test func aRowPerPasskeyWithThisMacsTagged() throws {
        let passkeys = try Self.decoded(.passkeys, "passkeys").passkeys
        let sameDay = AccountPage.passkeyRows(passkeys, now: try Self.instant("2026-11-11T18:00:00Z"), clock: Self.clock)
        #expect(sameDay.map(\.label) == ["Sail for Mac", "Sail for iPhone"] && sameDay.map(\.thisMac) == [true, false])
        #expect(sameDay.map(\.detail) == ["Added 11 November 2026 · last used today", "Added 11 November 2026"])
        #expect(sameDay.map(\.id) == ["Z29sZGVuIGNyZWRlbnRpYWxfaWQgNg", "Z29sZGVuIGNyZWRlbnRpYWxfaWQgMjQ"])
        let later = AccountPage.passkeyRows(passkeys, now: try Self.instant("2026-11-26T09:00:00Z"), clock: Self.clock)
        try #require(later.count == 2)
        #expect(later[0].detail == "Added 11 November 2026 · last used 11 November")
        let nextYear = AccountPage.passkeyRows(passkeys, now: try Self.instant("2027-01-02T09:00:00Z"), clock: Self.clock)
        #expect(nextYear.first?.detail == "Added 11 November 2026 · last used 11 November 2026")
        #expect(AccountPage.thisMac == "This Mac")
    }

    /// Removing a passkey asks first, naming it: Remove is the server's to refuse.
    @Test func removingAPasskeyAsksNamingIt() throws {
        let row = try #require(AccountPage.passkeyRows(try Self.decoded(.passkeys, "passkeys").passkeys, now: .now, clock: Self.clock).last)
        #expect(row.removing == AccountPage.Question(
            title: "Remove the passkey “Sail for iPhone”?",
            body: "It stops signing you in, and wherever it is signed in now signs out. Your other passkeys keep working."
        ))
    }

    /// A row per harness the server lists, in its order, with its default key in the kind's label
    /// and the key's name; the harness's further names are rows of their own.
    @Test func aRowPerListedHarnessWithItsDefaultKey() throws {
        let rows = AccountPage.keyRows(try Self.agents(), logins: try Self.logins("logins-with-a-default"))
        #expect(rows.map(\.display) == ["Claude Code", "Codex CLI"] && rows.map(\.monogram) == ["Cl", "Co"])
        #expect(rows.map(\.detail) == ["API key “default” · new sessions and spec runs use it", "API key “work” · new sessions and spec runs use it"])
        #expect(rows.map(\.login) == ["default", "work"] && rows.map(\.agent.harness) == ["claude-code", "codex"])
        let none = AccountPage.keyRows(try Self.agents(), logins: try Self.logins("logins-no-default"))
        #expect(none.map(\.display) == ["Claude Code", "Claude Code", "Codex CLI"])
        #expect(none.map(\.detail) == ["No key in use. Sessions sign in with your Claude account.", "API key “home” · not in use", "API key “work” · new sessions and spec runs use it"])
        #expect(none.map(\.login) == [nil, "home", "work"] && Set(none.map(\.id)).count == 3)
    }

    /// Add Key on a harness with no key in use and Replace on its default; a key not in use offers
    /// Remove alone, so replacing never changes which key new sessions take.
    @Test func onlyTheKeyInUseIsReplacedAndAKeyNotInUseIsOnlyRemoved() throws {
        let rows = AccountPage.keyRows(try Self.agents(), logins: try Self.logins("logins-no-default"))
        #expect(rows.map(\.login) == [nil, "home", "work"] && rows.map(\.stores) == [true, false, true], "\(rows.map(\.detail))")
        #expect(AccountPage.keyRows(try Self.agents(), logins: []).allSatisfy(\.stores))
    }

    /// The rows are the server's list, never one written in the app: a harness it stops listing has
    /// no row, one it adds has one, and a key of a harness it does not list names none.
    @Test func theRowsAreTheServersHarnessesAlone() throws {
        let codexOnly = try Exchange("agents").altered(at: ["agents"], to: [try Self.firstAgentJSON(at: 1)])
        let codex = try Endpoint.knownAgents.decode(response(codexOnly)).agents
        let rows = AccountPage.keyRows(codex, logins: try Self.logins("logins-with-a-default"))
        #expect(rows.map(\.agent.harness) == ["codex"], "a key row names a harness the server did not list: \(rows.map(\.display))")
        #expect(rows.map(\.detail) == ["API key “work” · new sessions and spec runs use it"], "a key of a harness not listed is said: \(rows.map(\.detail))")
        let renamed = try Exchange("agents").altered(at: ["agents", 1, "display_name"], to: "Tide")
        #expect(AccountPage.keyRows(try Endpoint.knownAgents.decode(response(renamed)).agents, logins: []).map(\.display) == ["Claude Code", "Tide"])
        let noSignIn = try Exchange("agents").altered(at: ["agents", 1, "sign_in"], to: nil)
        #expect(AccountPage.keyRows(try Endpoint.knownAgents.decode(response(noSignIn)).agents, logins: []).last?.detail == "No key.")
    }

    static func firstAgentJSON(at index: Int) throws -> Any {
        let body = try #require(try JSONSerialization.jsonObject(with: Exchange("agents").body) as? [String: Any])
        let agents = try #require(body["agents"] as? [Any])
        return agents[index]
    }

    /// Removing a key asks first, saying what is left without it in the route's words; a further
    /// name asks for itself. No key's value or fingerprint is ever said.
    @Test func removingAKeyAsksSayingWhatIsLeft() throws {
        let rows = AccountPage.keyRows(try Self.agents(), logins: try Self.logins("logins-no-default"))
        try #require(rows.count == 3, "rows: \(rows.map(\.detail))")
        #expect(rows[0].removing == nil, "a row with no key offers a removal")
        #expect(rows[1].removing == AccountPage.Question(title: "Remove the Claude Code key “home”?", body: "Sessions already running keep it until they end."))
        #expect(rows[2].removing == AccountPage.Question(
            title: "Remove the Codex CLI key?",
            body: "Sessions already running keep it until they end. New Codex CLI sessions sign in with your ChatGPT account, and spec runs need a key again."
        ))
        let said = rows.flatMap { [$0.detail, $0.removing?.title ?? "", $0.removing?.body ?? ""] }
        #expect(said.allSatisfy { !$0.contains("584e1bc1") && !$0.contains("191b148d") && !$0.contains("67283359") })
    }

    /// A group shows the drawing mark until its first answer; a failed call is said in place, the
    /// last answer kept and dimmed; an answer after it is shown whole again.
    @Test func aGroupKeepsItsLastAnswerAndDimsOnlyWhenItsCallFailed() throws {
        var group = AccountPage.Answered<[String]>()
        #expect(group.waiting && !group.dimmed && group.value == nil)
        group.take(.failure(.unreachable("lost")), at: "sail.example.com")
        #expect(!group.waiting && !group.dimmed && group.failure == ["Sail cannot reach the control plane at sail.example.com."])
        group.take(.success(["a"]), at: "sail.example.com")
        #expect(group.value == ["a"] && group.failure == nil && !group.dimmed)
        let refused = try Exchange("refusal-conflict-passkey-last")
        guard case let .refused(refusal) = SailJSON.refusal(response(refused), route: "") else {
            Issue.record("no refusal")
            return
        }
        group.take(.failure(.refused(refusal)), at: "sail.example.com")
        #expect(group.value == ["a"] && group.dimmed)
        #expect(group.failure == ["This is your only passkey, so removing it would leave you no way to sign in.", "Enroll another passkey with a ticket from your admin, then remove this one."])
    }

    /// The words the view says beside its groups.
    @Test func theViewsOwnWords() {
        #expect(AccountPage.unreachable(at: "sail.example.com") == ["Sail cannot reach sail.example.com.", "Check that this Mac is on its network."])
        #expect(AccountPage.passkeysNote == "A new passkey needs a ticket from your admin.")
        #expect(AccountPage.keysNote == "Agents sign in with their own account or a key stored here.")
    }

    /// A removal that answers 204 with no bytes is a removal: nothing is read from it.
    @Test func aRemovalThatSendsNothingBackIsDone() throws {
        let removed = try Exchange("passkey-removed")
        #expect(removed.status == 204 && removed.body.isEmpty)
        #expect(try Endpoint.removePasskey("Z29sZGVuIGNyZWRlbnRpYWxfaWQgMjQ").decode(response(removed)) == NoContent())
        #expect(throws: SailError.self) { try Endpoint.removePasskey("x").decode(HTTPResponse(status: 409, headers: [:], body: Data())) }
        let login = try Self.decoded(.removeLogin(harness: "claude-code", name: "default"), "login-removed")
        #expect(login.login?.name == "default" && login.note != nil)
    }
}
