import Foundation

/// What the user menu says and offers, from what the account knows: who is signed in (the name
/// the server gives, else the handle), `@handle · Role`, the control plane's address and whether it
/// answers, and what can be done there. Nothing a person reads names a command or a route.
public struct UserMenuContent: Equatable, Sendable {
    /// Whether the control plane answers: as it last answered, reconnecting while it is asked
    /// again after not answering.
    public enum Plane: Equatable, Sendable {
        case connected, reconnecting, unreachable

        /// As it last answered: out of reach, reconnecting while it is asked again; else connected.
        public init(unreachable: Bool, refreshing: Bool) {
            self = unreachable ? (refreshing ? .reconnecting : .unreachable) : .connected
        }

        public var said: String {
            switch self {
            case .connected: "Connected"
            case .reconnecting: "Reconnecting"
            case .unreachable: "Can’t reach"
            }
        }
    }

    /// What the menu offers, in its order; Appearance is offered whoever is signed in.
    public enum Item: String, Equatable, Sendable, CaseIterable {
        case account = "Account", signOut = "Sign Out", signIn = "Sign In"

        /// The key that chooses it with ⌘ from anywhere.
        public var key: String? {
            self == .account ? "," : nil
        }
    }

    /// The disc beside the name: the person's initial, or a person's outline when no one is signed in.
    public enum Disc: Equatable, Sendable {
        case initial(String), person
    }

    public let name: String
    public let disc: Disc?
    /// `@handle` and the role when someone is signed in, else the address or the last used.
    public let detail: String?
    public let address: String?
    public let plane: Plane?
    public let items: [Item]

    /// No one signed in: the control plane `lastUsed` here, or "no control plane yet".
    public static func signedOut(lastUsed: String?) -> Self {
        Self(name: "Not signed in", disc: .person, detail: lastUsed ?? "no control plane yet", address: nil, plane: nil, items: [.signIn])
    }

    /// Who is signed in at `address` not yet known: the app is asking.
    public static func checking(at address: String) -> Self {
        Self(name: "Checking", disc: nil, detail: address, address: nil, plane: nil, items: [.account])
    }

    /// Signed in as `handle` at `address`; `whoami` as it last answered, `unreachable` when the
    /// control plane last did not answer, `refreshing` while it is asked again.
    public static func signedIn(handle: String, address: String, whoami: Whoami?, unreachable: Bool, refreshing: Bool) -> Self {
        let name = [whoami?.displayName, whoami?.name].compactMap { $0 }.first { !$0.isEmpty } ?? handle
        let role = whoami.flatMap { whoami -> String? in
            guard case let .known(role) = whoami.role else { return nil }
            return role.rawValue.prefix(1).uppercased() + role.rawValue.dropFirst()
        }
        let plane = Plane(unreachable: unreachable, refreshing: refreshing)
        return Self(
            name: name, disc: name.first.map { .initial(String($0).uppercased()) },
            detail: "@\(handle)" + (role.map { " · \($0)" } ?? ""), address: address, plane: plane, items: [.account, .signOut]
        )
    }
}

/// How the app looks on this Mac: light, dark, or the Mac's own (Auto), kept in this Mac's
/// defaults and never sent anywhere.
public enum Appearance: String, CaseIterable, Sendable {
    case light, dark, auto

    public static let key = "appearance"

    public var said: String {
        rawValue.prefix(1).uppercased() + rawValue.dropFirst()
    }

    /// The choice kept in `defaults`; Auto until one is made, or for a value it does not know.
    public static func stored(in defaults: UserDefaults) -> Self {
        defaults.string(forKey: key).flatMap(Self.init(rawValue:)) ?? .auto
    }

    public func store(in defaults: UserDefaults) {
        defaults.set(rawValue, forKey: Self.key)
    }
}
