import Foundation
import SailAPI

/// The Store a Login sheet's model for one harness (`login-store-mockup.html`): the kind it stores,
/// the name a store takes, and where it stands. The kind is the first the server names that a
/// person makes without running anything (``SailAPI/AgentOffer/storable``). The name is `default`
/// and not asked while the person has no login of the harness; with one, it is asked, a free name
/// suggested. It never holds the value: ``store(_:clipboard:)`` reads it once into the request it
/// returns and keeps only whether the clipboard held exactly it then, by the clipboard's change
/// count, so a store that succeeds clears the clipboard only while it still holds that.
public struct StoreLogin: Equatable, Sendable {
    public enum Stage: Equatable, Sendable {
        case empty
        case filled
        case storing
        /// Refused, or not sent, in these words, said under `field`.
        case refused(String, under: Field)
        /// Stored as `login`; `clearsClipboard`, the clipboard still held the value and is cleared.
        case stored(StoredLogin, clearsClipboard: Bool)
    }

    public enum Field: Equatable, Sendable {
        case value, name
    }

    /// What the clipboard holds now: its change count and its text.
    public struct Clipboard: Equatable, Sendable {
        public let count: Int
        public let text: String?

        public init(count: Int, text: String?) {
            self.count = count
            self.text = text
        }
    }

    public let harness: String
    public let display: String
    public let kind: AgentLoginKind
    /// The names of the person's logins of this harness; none, and the name is not asked.
    public let taken: [String]
    /// The control plane as a person names it: its address without the scheme.
    public let place: String
    public private(set) var stage = Stage.empty
    public private(set) var name: String
    /// The clipboard's change count when it held exactly the value sent; nil when it did not.
    private var pastedAt: Int?

    /// The sheet for `offer` of a person whose logins are `logins`, on the control plane at
    /// `address`; nil when the harness offers no kind the app stores.
    public init?(_ offer: AgentOffer, logins: [StoredLogin], address: String) {
        guard let kind = offer.storable else { return nil }
        harness = offer.harness
        display = offer.display
        self.kind = kind
        taken = logins.filter { $0.harness == offer.harness }.map(\.name)
        place = address.range(of: "://").map { String(address[$0.upperBound...]) } ?? address
        name = taken.isEmpty ? Self.defaultName : Self.free(among: taken)
    }

    static let defaultName = "default"

    /// `work`, or the first of `work-2`, `work-3`… none of `taken` is.
    static func free(among taken: [String]) -> String {
        let named = { (number: Int) in number == 1 ? "work" : "work-\(number)" }
        var number = 1
        while taken.contains(named(number)) {
            number += 1
        }
        return named(number)
    }

    public var asksName: Bool {
        !taken.isEmpty
    }

    /// Store is on: the field holds something, a name is given, and nothing is under way.
    public var storable: Bool {
        stage == .filled && !name.isEmpty
    }

    /// The field now holds something, or nothing; a refusal stays until the field changes.
    public mutating func typed(holdsSomething: Bool) {
        switch stage {
        case .storing, .stored: return
        case .refused where !holdsSomething: return
        case .empty, .filled, .refused: stage = holdsSomething ? .filled : .empty
        }
    }

    /// The name typed; a refusal of the name goes with the change.
    public mutating func named(_ typed: String) {
        guard stage != .storing else { return }
        name = typed
        if case .refused(_, .name) = stage { stage = .empty }
    }

    /// The store of `value`, read from the field once, when Store is on, `clipboard` holding what
    /// the clipboard holds now; nil, and nothing changed, when it is off.
    public mutating func store(_ value: String, clipboard: Clipboard) -> Endpoint<LoginStored>? {
        guard storable, let endpoint = try? Endpoint.storeLogin(value, kind: kind.kind, harness: harness, name: name) else { return nil }
        pastedAt = clipboard.text == value ? clipboard.count : nil
        stage = .storing
        return endpoint
    }

    /// The store's answer, the clipboard's change count now: stored, the clipboard to be cleared
    /// only while it is unchanged since it held the value; or refused, in the server's words under
    /// the field they are about, the name's or the value's; or not sent, said so.
    public mutating func answered(_ answer: Result<LoginStored, SailError>, clipboard now: Int) {
        guard stage == .storing else { return }
        defer { pastedAt = nil }
        switch answer {
        case let .success(stored):
            stage = .stored(stored.login, clearsClipboard: pastedAt == now)
        case let .failure(.refused(refusal)):
            stage = .refused(refusal.message, under: refusal.fieldErrors.contains { $0.field == "name" } ? .name : .value)
        case .failure(.unreachable):
            stage = .refused("Can't reach \(place). Nothing was stored.", under: .value)
        case let .failure(error):
            stage = .refused(error.said(at: place), under: .value)
        }
    }

    // MARK: Words

    public var title: String {
        "Store a \(display) login"
    }

    /// The field's label: the kind's, as a label starts.
    public var label: String {
        kind.label.prefix(1).uppercased() + kind.label.dropFirst()
    }

    public var placeholder: String {
        "Paste your \(kind.label)"
    }

    /// How to make one, the one line under the field.
    public var help: String {
        kind.said ?? ""
    }

    /// The field as VoiceOver names it.
    public var fieldName: String {
        "\(display) \(kind.label)"
    }

    public static let nameLabel = "Name"

    /// Beside the name: the names the person has already.
    public var already: String {
        "you already have " + taken.map { "“\($0)”" }.joined(separator: ", ")
    }

    public var kept: String {
        "Kept on \(place) for your sessions only. Sail never shows it again."
    }

    public static let store = "Store"
    public static let storing = "Storing"

    /// Store as VoiceOver names it.
    public var storeName: String {
        "Store \(display) login"
    }

    /// The panel's one quiet line once stored, the clipboard's sentence when it was cleared.
    public var stored: String? {
        guard case let .stored(login, cleared) = stage else { return nil }
        return "Stored \(display) login “\(login.name)”." + (cleared ? " Removed the key from the clipboard." : "")
    }
}
