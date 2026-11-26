import Foundation

/// What this Mac remembers of a sign-in made at a control plane: who, by handle and name. Never a
/// token: the session is the vault's alone.
public struct RememberedSignIn: Codable, Equatable, Sendable {
    public let handle: String
    public let name: String?

    public init(handle: String, name: String?) {
        self.handle = handle
        self.name = name
    }

    /// The name's first word, as "Welcome back, Mara" greets; nil with no name.
    public var firstName: String? {
        name?.split(separator: " ").first.map(String.init)
    }
}

/// What this Mac keeps in its defaults for the welcome: the control plane it last reached, and for
/// each control plane the sign-in made there last. The address is kept only once it answered; a
/// sign-in is written by a sign-in and nothing else. Nothing here is a secret.
public struct SignInMemory {
    public static let addressKey = "controlPlane.address"

    private let defaults: UserDefaults

    public init(_ defaults: UserDefaults) {
        self.defaults = defaults
    }

    /// The control plane last reached, nil before any.
    public var plane: ControlPlane? {
        defaults.string(forKey: Self.addressKey).flatMap { try? ControlPlane(address: $0) }
    }

    /// `plane` answered: the next launch starts there.
    public func reached(_ plane: ControlPlane) {
        defaults.set(plane.description, forKey: Self.addressKey)
    }

    /// Someone signed in at `plane`: the next welcome there is theirs.
    public func signedIn(_ signIn: RememberedSignIn, at plane: ControlPlane) {
        reached(plane)
        guard let data = try? JSONEncoder().encode(signIn) else { return }
        defaults.set(data, forKey: Self.key(plane))
    }

    /// The sign-in made at `plane` last, nil when this Mac never signed in there.
    public func signIn(at plane: ControlPlane) -> RememberedSignIn? {
        defaults.data(forKey: Self.key(plane)).flatMap { try? JSONDecoder().decode(RememberedSignIn.self, from: $0) }
    }

    /// The screen this Mac opens on, from what it remembers alone.
    public var welcome: Welcome {
        Welcome(plane: plane, person: plane.flatMap(signIn(at:)))
    }

    private static func key(_ plane: ControlPlane) -> String {
        "signIn." + plane.description
    }
}

/// The signed-out screens, one question each, and every sentence on them (section 1 of the first
/// impressions page): Welcome asks for the control plane; Enroll, for a ticket where this Mac never
/// signed in; Welcome back offers the sign-in where it did. Each leads to the other, so a wrong
/// guess costs one click. A stage under way says its "-ing" word and, where a system sheet comes
/// up, why; a refusal stands under its screen's field (on Welcome back, under the primary).
public struct Welcome: Equatable, Sendable {
    public enum Screen: Equatable, Sendable {
        case address, ticket, back
    }

    public enum Stage: Equatable, Sendable {
        case connecting, creating, signingAfterEnrolling(handle: String), signingIn
    }

    public struct Field: Equatable, Sendable {
        public let label: String
        public let placeholder: String
        public let help: String
        public let secure: Bool

        package init(label: String, placeholder: String, help: String, secure: Bool) {
            self.label = label
            self.placeholder = placeholder
            self.help = help
            self.secure = secure
        }
    }

    /// The quiet line under the primary: words, a link, and words after it.
    public struct Other: Equatable, Sendable {
        public let before: String
        public let link: String
        public let after: String?

        package init(before: String, link: String, after: String?) {
            self.before = before
            self.link = link
            self.after = after
        }
    }

    /// The one-line note over the primary that says why a system sheet is up; `created` once the
    /// passkey is made.
    public struct Note: Equatable, Sendable {
        public let created: Bool
        public let words: String

        package init(created: Bool, words: String) {
            self.created = created
            self.words = words
        }
    }

    public private(set) var screen: Screen
    public private(set) var plane: ControlPlane?
    public private(set) var person: RememberedSignIn?
    public private(set) var stage: Stage?
    public private(set) var refusal: AccountRefusal?
    /// Whether the control plane answered: true once it did in this run, false when it was said
    /// unreachable, nil when not yet asked.
    public private(set) var answers: Bool?
    private var endedOn: Date?

    public init(plane: ControlPlane?, person: RememberedSignIn?) {
        self.plane = plane
        self.person = person
        screen = plane == nil ? .address : person == nil ? .ticket : .back
    }

    /// Change: the address again, the current one in the field.
    public mutating func change() {
        show(.address)
    }

    public mutating func enrollInstead() {
        show(.ticket)
    }

    public mutating func signInInstead() {
        show(.back)
    }

    public mutating func began(_ stage: Stage) {
        self.stage = stage
        refusal = nil
        endedOn = nil
    }

    /// `plane` answered: Enroll there, or Welcome back when `person` signed in there before.
    public mutating func reached(_ plane: ControlPlane, person: RememberedSignIn?) {
        self.plane = plane
        self.person = person
        answers = true
        show(person == nil ? .ticket : .back)
    }

    /// The stage under way ended in `refusal`. A sign-in after enrolling that did not finish leaves
    /// Welcome back: the passkey is made, and signing in is what is left.
    public mutating func refused(_ refusal: AccountRefusal) {
        if case .signingAfterEnrolling? = stage { screen = .back }
        if refusal.reason == .unreachable { answers = false }
        stage = nil
        self.refusal = refusal
    }

    /// The session ended on `day`: Welcome back says so over its primary.
    public mutating func ended(on day: Date) {
        show(.back)
        endedOn = day
    }

    private mutating func show(_ screen: Screen) {
        self.screen = screen
        stage = nil
        refusal = nil
        endedOn = nil
    }

    /// The control plane as its chip and sentences name it.
    public var host: String {
        plane?.named ?? ""
    }

    /// The control plane as Change puts it back in the field: as a person types it, the scheme
    /// left out where Sail adds it.
    public var typed: String {
        plane.map { $0.url.scheme == "https" ? $0.named : $0.description } ?? ""
    }

    public var showsMark: Bool {
        screen != .back
    }

    public var title: String {
        switch screen {
        case .address: "Welcome to Sail"
        case .ticket: "Enroll this Mac"
        case .back: person?.firstName.map { "Welcome back, \($0)" } ?? "Welcome back"
        }
    }

    public var lede: String {
        switch screen {
        case .address: "Run Claude Code, Codex and your shells in workspaces that keep going when you close the lid. Start with your team's control plane."
        case .ticket: "Your admin gives you a one-time ticket. Paste it here and Sail creates your passkey, kept in iCloud Keychain, then signs you in."
        case .back: "Sign in to \(host) with your passkey."
        }
    }

    /// Welcome's field and Enroll's.
    public static let addressField = Field(
        label: "Control plane", placeholder: "sail.example.com", help: "The address your admin gave you. Sail adds https:// when you leave it out.", secure: false
    )
    public static let ticketField = Field(
        label: "Enrollment ticket", placeholder: "Paste the ticket or its link", help: "A ticket lasts 15 minutes and enrolls one passkey.", secure: true
    )

    public var field: Field? {
        switch screen {
        case .address: Self.addressField
        case .ticket: Self.ticketField
        case .back: nil
        }
    }

    /// The primary's words on `screen`, as its button is made with them.
    public static func primary(_ screen: Screen) -> String {
        switch screen {
        case .address: "Continue"
        case .ticket: "Create Passkey"
        case .back: "Sign In with Passkey"
        }
    }

    public var primary: String {
        Self.primary(screen)
    }

    /// The chip's link back to Welcome.
    public static let changeLink = "Change"

    public var busyWord: String? {
        switch stage {
        case .connecting?: "Connecting"
        case .creating?: "Creating Passkey"
        case .signingAfterEnrolling?, .signingIn?: "Signing In"
        case nil: nil
        }
    }

    public var note: Note? {
        switch stage {
        case .creating?: Note(created: false, words: "Confirm with Touch ID to create your passkey.")
        case let .signingAfterEnrolling(handle)?: Note(created: true, words: "Passkey created for @\(handle). Confirm once more to sign in.")
        case .signingIn?: Note(created: false, words: "Confirm with Touch ID.")
        case .connecting?, nil: nil
        }
    }

    /// The quiet line on `screen`, as its link is made with its words.
    public static func other(_ screen: Screen) -> Other {
        switch screen {
        case .address: Other(before: "No control plane yet?", link: "Open Terminals", after: "for shells on this Mac.")
        case .ticket: Other(before: "Already enrolled?", link: primary(.back), after: nil)
        case .back: Other(before: "New on this Mac?", link: "Enroll with a Ticket", after: nil)
        }
    }

    public var other: Other {
        Self.other(screen)
    }

    /// The refusal's sentence and the line under it, its remedy where it has one: what stands under
    /// the field it is about.
    public var said: [String] {
        Array(refusal?.lines.prefix(2) ?? [])
    }

    public var banner: String? {
        endedOn.map { "Your sign-in ended on \($0.formatted(Self.day)). Your sessions on the runner kept running." }
    }

    /// The line at the card's foot: "Sail" and the app's own version.
    public static func version(_ version: String?) -> String {
        (["Sail"] + (version.map { [$0] } ?? [])).joined(separator: " ")
    }

    /// "25 November", in this Mac's time zone.
    private static let day = Date.FormatStyle(locale: Locale(identifier: "en_GB")).day().month(.wide)
}
