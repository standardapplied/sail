import Foundation

/// What the Account view says (`first-impressions-mockup.html`, section 2): when the session ends
/// and whether soon, a row per passkey of the caller's own, a row per harness the server lists with
/// its key or how it signs in without one, what each removal asks, and what a group shows while its
/// first answer is awaited or its last call failed. Every name of a harness and of its sign-in is
/// the server's; no key's value or fingerprint is ever said.
public enum AccountPage {
    /// The calendar and the words dates are said in: the Mac's own, or fixed ones in a test.
    public struct Clock: Sendable {
        public let calendar: Calendar
        public let locale: Locale

        public init(calendar: Calendar = .current, locale: Locale = .current) {
            self.calendar = calendar
            self.locale = locale
        }

        func day(_ date: Date, year: Bool = true) -> String {
            let style = Date.FormatStyle(locale: locale, calendar: calendar, timeZone: calendar.timeZone).day().month(.wide)
            return date.formatted(year ? style.year() : style)
        }

        func time(_ date: Date) -> String {
            date.formatted(Date.FormatStyle(locale: locale, calendar: calendar, timeZone: calendar.timeZone).hour().minute())
        }
    }

    public static let thisMac = "This Mac"
    /// A row's detail while its removal is under way.
    public static let removing = "Removing"
    public static let passkeysNote =
        "Passkeys sign you in. They live in your iCloud Keychain or on a security key; Sail holds none of them. A new one needs a ticket from your admin."
    public static let keysNote =
        "Each agent signs in with its own account inside the session, or with one API key you store here. A stored key also runs specs. Sail never shows a key again once stored."
    public static let signOutNote = "Signing out ends nothing on the runner: your sessions keep running for your next sign-in."

    public static func signedInHere(_ handle: String) -> String {
        "Signed in on this Mac as @\(handle)."
    }

    /// The control plane not answering, said in its group: what is so, then what to do.
    public static func unreachable(at host: String) -> [String] {
        ["Sail cannot reach \(host).", "Check that this Mac is on its network."]
    }

    /// When the session ends: its day and time, and how long is left; under three days left it is
    /// `soon`, said as when it ends; past its end, said as ended (the day, as the welcome says it),
    /// and `soon`, so Sign In Again shows.
    public struct Ending: Equatable, Sendable {
        public let said: String
        public let soon: Bool

        public init(said: String, soon: Bool) {
            self.said = said
            self.soon = soon
        }
    }

    public static func ending(_ until: Date, now: Date, clock: Clock = Clock()) -> Ending {
        guard until > now else { return Ending(said: "Your sign-in ended on \(clock.day(until, year: false)).", soon: true) }
        let days = Int((until.timeIntervalSince(now) / 86_400).rounded(.down))
        let left = switch days {
        case 3...: "\(days) days left"
        case 2: "ends in 2 days"
        case 1: "ends in 1 day"
        default: "ends within a day"
        }
        return Ending(said: "Signed in until \(clock.day(until)), \(clock.time(until)) · \(left)", soon: days < 3)
    }

    /// What a removal asks before it is sent.
    public struct Question: Equatable, Sendable {
        public let title: String
        public let body: String

        public init(title: String, body: String) {
            self.title = title
            self.body = body
        }
    }

    /// A passkey of the caller's: its label, "This Mac" on the one this session signed in with, and
    /// when it was added and last used.
    public struct PasskeyRow: Equatable, Sendable {
        public let id: String
        public let label: String
        public let thisMac: Bool
        public let detail: String

        public var removing: Question {
            Question(
                title: "Remove the passkey “\(label)”?",
                body: "It stops signing you in, and wherever it is signed in now signs out. Your other passkeys keep working."
            )
        }
    }

    /// A row per passkey, in the server's order: "Added 12 November 2026 · last used today", the
    /// day alone within this year, nothing of its use before its first.
    public static func passkeyRows(_ passkeys: [OwnPasskey], now: Date, clock: Clock = Clock()) -> [PasskeyRow] {
        let calendar = clock.calendar
        return passkeys.map { passkey in
            let used = passkey.lastUsedAt.map { last in
                calendar.isDate(last, inSameDayAs: now) ? "today"
                    : clock.day(last, year: calendar.component(.year, from: last) != calendar.component(.year, from: now))
            }
            let detail = "Added \(clock.day(passkey.createdAt))" + (used.map { " · last used \($0)" } ?? "")
            return PasskeyRow(id: passkey.id, label: passkey.label, thisMac: passkey.current, detail: detail)
        }
    }

    /// A harness's key, or its want of one: the harness's monogram and name, what the key is or how
    /// its sessions sign in without one, and the key's name (none for a row with no key).
    public struct KeyRow: Equatable, Sendable {
        public let agent: AgentDeclaration
        public let signIn: String?
        public let login: String?
        public let isDefault: Bool
        public let detail: String

        public var id: String {
            agent.harness + "\u{1F}" + (login ?? "")
        }

        public var display: String {
            agent.display
        }

        /// The first two letters of its name.
        public var monogram: String {
            String(agent.display.prefix(2))
        }

        /// It offers Add Key (no key in use) or Replace (the key in use); a key not in use offers
        /// Remove alone, so replacing a key never changes which one new sessions take.
        public var stores: Bool {
            login == nil || isDefault
        }

        /// What removing its key asks; nil for a row with no key.
        public var removing: Question? {
            guard let login else { return nil }
            guard isDefault else { return Question(title: "Remove the \(display) key “\(login)”?", body: Self.keepIt) }
            let after = signIn.map { "New \(display) sessions sign in with \(AccountPage.lowered($0)), and spec runs need a key again." }
                ?? "New \(display) sessions and spec runs need a key again."
            return Question(title: "Remove the \(display) key?", body: Self.keepIt + " " + after)
        }

        static let keepIt = "Sessions already running keep it until they end."
    }

    /// A row per harness the server lists, in its order: its default key ("API key “default” · new
    /// sessions and spec runs use it"), or "No key." ("No key in use." when it has keys) and how its
    /// sessions sign in without one; then a row for each further key of it, not in use. A key of a
    /// harness the server does not list has no row.
    public static func keyRows(_ agents: [KnownAgent], logins: [StoredLogin]) -> [KeyRow] {
        agents.flatMap { agent -> [KeyRow] in
            let declared = agent.declaration, signIn = agent.signIn?.name
            let kind = { (login: StoredLogin) in agent.kinds.first { $0.kind == login.kind }?.label ?? login.kind }
            let own = logins.filter { $0.harness == agent.name }
            let first = own.first(where: \.isDefault).map { login in
                KeyRow(agent: declared, signIn: signIn, login: login.name, isDefault: true, detail: "\(kind(login)) “\(login.name)” · new sessions and spec runs use it")
            } ?? KeyRow(agent: declared, signIn: signIn, login: nil, isDefault: false, detail: (own.isEmpty ? "No key." : "No key in use.") + (signIn.map { " Sessions sign in with \(lowered($0))." } ?? ""))
            return [first] + own.filter { !$0.isDefault }.map { login in
                KeyRow(agent: declared, signIn: signIn, login: login.name, isDefault: false, detail: "\(kind(login)) “\(login.name)” · not in use")
            }
        }
    }

    /// `words` as they read inside a sentence: "Your Claude account" as "your Claude account".
    static func lowered(_ words: String) -> String {
        words.prefix(1).lowercased() + words.dropFirst()
    }

    /// What a group drew from its call: nothing yet (its first answer awaited, the drawing mark in
    /// its place), its last answer, and why its last call failed, said in place: the last answer is
    /// kept, dimmed, until a call answers again.
    public struct Answered<Value: Equatable & Sendable>: Equatable, Sendable {
        public private(set) var value: Value?
        public private(set) var failure: [String]?

        public init() {}

        public var waiting: Bool {
            value == nil && failure == nil
        }

        public var dimmed: Bool {
            value != nil && failure != nil
        }

        /// The call's answer, a failure said in the server's words or as the control plane at
        /// `place` failing.
        public mutating func take(_ answer: Result<Value, SailError>, at place: String) {
            switch answer {
            case let .success(value):
                self.value = value
                failure = nil
            case let .failure(.refused(refusal)):
                failure = refusal.lines
            case let .failure(error):
                failure = [error.said(at: place)]
            }
        }
    }
}
