import Foundation
import SailAPI

/// The Store a Key sheet's model for one harness (`login-store-mockup.html`): the kind it stores,
/// the name a store takes, and where it stands. The kind is the first the server names that a
/// person makes without running anything (``SailAPI/AgentOffer/storable``). The name is `default`
/// and not asked while the person has no login of the harness; with one, a free name is suggested
/// and said as words beside Change, which names it with the key's field put away: a key and an
/// editable name are never asked together, which AppKit would take for a login form and offer
/// Passwords' AutoFill over. A name the server refuses is named again, its sentence under it. It
/// never holds the value: ``store(_:clipboard:)`` reads it once into the request it
/// returns and keeps only whether the clipboard held exactly it then, by the clipboard's change
/// count, so a store that succeeds clears the clipboard only while it still holds that.
public struct StoreLogin: Equatable, Sendable {
    public enum Stage: Equatable, Sendable {
        case empty
        case filled
        /// The name is being named, the key's field put away; `refused`, the server's sentence on
        /// the name sent.
        case naming(refused: String?)
        case storing
        /// The key refused, or not sent, in these words, said under its field.
        case refused(String)
        /// Stored, as the server answered; `clearsClipboard`, the clipboard still held the value and is
        /// cleared where the panel says so.
        case stored(LoginStored, clearsClipboard: Bool)
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
    /// The key was put away by Change, or sent with a name the server refused: it is pasted again.
    public private(set) var keyDropped = false

    /// The sheet for `offer` of a person whose logins are `logins`, on the control plane at
    /// `address`; nil when the harness offers no kind the app stores.
    public init?(_ offer: AgentOffer, logins: [StoredLogin], address: String) {
        guard let kind = offer.storable else { return nil }
        harness = offer.harness
        display = offer.display
        self.kind = kind
        taken = logins.filter { $0.harness == offer.harness }.map(\.name)
        place = Self.place(address)
        name = taken.isEmpty ? Self.defaultName : Self.free(among: taken)
    }

    static let defaultName = "default"

    /// The control plane at `address` as a person names it: its address without the scheme.
    public static func place(_ address: String) -> String {
        address.range(of: "://").map { String(address[$0.upperBound...]) } ?? address
    }

    /// Why no sheet opens when the person's logins could not be read, at the control plane at
    /// `address`: the server's sentence, or that it did not answer.
    public static func unread(_ error: SailError, address: String) -> String {
        switch error {
        case let .refused(refusal): refusal.message
        case .unreachable: "Can't reach \(place(address)). Nothing can be stored now."
        default: error.said(at: place(address))
        }
    }

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

    /// Store is on: the field holds something and nothing is under way.
    public var storable: Bool {
        stage == .filled
    }

    /// The field now holds something, or nothing; a refusal stays until the field changes.
    public mutating func typed(holdsSomething: Bool) {
        switch stage {
        case .storing, .stored, .naming: return
        case .refused where !holdsSomething: return
        case .empty, .filled, .refused:
            stage = holdsSomething ? .filled : .empty
            if holdsSomething { keyDropped = false }
        }
    }

    /// Change: the name named, the key's field put away and its value gone; only where it is asked.
    public mutating func changeName() {
        switch stage {
        case .empty, .filled, .refused:
            guard asksName else { return }
            if stage == .filled { keyDropped = true }
            stage = .naming(refused: nil)
        case .naming, .storing, .stored: return
        }
    }

    /// The name typed while it is named; a refusal of it goes with the change.
    public mutating func named(_ typed: String) {
        guard case .naming = stage else { return }
        name = typed
        stage = .naming(refused: nil)
    }

    /// Done is on: a name is given, and not one the server refused unchanged.
    public var nameGiven: Bool {
        stage == .naming(refused: nil) && !name.isEmpty
    }

    /// Done: back to the key's field, empty, the name kept.
    public mutating func doneNaming() {
        guard nameGiven else { return }
        stage = .empty
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
    /// only while it is unchanged since it held the value; or refused, in the server's words, a
    /// name's refusal naming the name again; or not sent, said so.
    public mutating func answered(_ answer: Result<LoginStored, SailError>, clipboard now: Int) {
        guard stage == .storing else { return }
        defer { pastedAt = nil }
        switch answer {
        case let .success(stored):
            stage = .stored(stored, clearsClipboard: pastedAt == now)
        case let .failure(.refused(refusal)) where refusal.fieldErrors.contains(where: { $0.field == "name" }):
            stage = .naming(refused: refusal.message)
            keyDropped = true
        case let .failure(.refused(refusal)):
            stage = .refused(refusal.message)
        case .failure(.unreachable):
            stage = .refused("Can't reach \(place). Nothing was stored.")
        case let .failure(error):
            stage = .refused(error.said(at: place))
        }
    }

    /// What makes the key just stored the one Start takes, where the server did not make it the
    /// default itself (the person had one already): nil before it is stored, and for the default.
    public var use: Endpoint<LoginStored>? {
        guard case let .stored(answer, _) = stage, !answer.login.isDefault else { return nil }
        return .useLogin(harness: harness, name: answer.login.name)
    }

    /// Why the key just stored is not the one Start takes: the server's sentence, or that it did not
    /// answer.
    public func notUsed(_ error: SailError) -> String {
        guard case let .stored(answer, _) = stage else { return "" }
        return switch error {
        case let .refused(refusal): refusal.message
        case .unreachable: "Can't reach \(place), so “\(answer.login.name)” is not the key Start takes."
        default: error.said(at: place)
        }
    }

    // MARK: Words

    public var title: String {
        "Store a \(display) key"
    }

    /// The field's label: the kind's, as a label starts.
    public var label: String {
        kind.label.prefix(1).uppercased() + kind.label.dropFirst()
    }

    public var placeholder: String {
        "Paste your \(kind.label)"
    }

    /// The one line under the field: how to make one, or that the key put away is pasted again.
    public var help: String {
        keyDropped ? Self.pasteAgain : kind.said ?? ""
    }

    public static let pasteAgain = "Paste the key again."

    /// The field as VoiceOver names it.
    public var fieldName: String {
        "\(display) \(kind.label)"
    }

    public static let nameLabel = "Name"
    public static let change = "Change"
    public static let done = "Done"

    /// Change as VoiceOver names it.
    public static let changeName = "Change name"

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
        "Store \(display) key"
    }

    /// The panel's one quiet line once stored: the clipboard's sentence when it was cleared, and
    /// what the server says once of runs on a login it replaced.
    public var stored: String? {
        guard case let .stored(answer, cleared) = stage else { return nil }
        let clipboard = cleared ? " Removed the key from the clipboard." : ""
        return "Stored \(display) key “\(answer.login.name)”." + clipboard + (answer.note.map { " " + $0 } ?? "")
    }
}
