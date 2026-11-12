/// Who the server takes the caller to be. `fde` is the caller's handle when its credential acts as
/// an FDE; `name` is the credential's name; `displayName` and `email` come with a passkey session.
/// A client gates what it offers on `capabilities`, never on a role it maps to powers itself.
public struct Whoami: Codable, Equatable, Sendable {
    public let fde: String?
    public let name: String?
    public let displayName: String?
    public let email: String?
    public let role: Open<Role>
    public let capabilities: [Open<Capability>]

    public init(
        fde: String? = nil, name: String? = nil, displayName: String? = nil, email: String? = nil,
        role: Open<Role>, capabilities: [Open<Capability>]
    ) {
        self.fde = fde
        self.name = name
        self.displayName = displayName
        self.email = email
        self.role = role
        self.capabilities = capabilities
    }

    private enum CodingKeys: String, CodingKey {
        case fde, name, role, capabilities, email
        case displayName = "display_name"
    }
}

public enum Role: String, CaseIterable, Sendable {
    case admin, member, viewer
}

public enum Capability: String, CaseIterable, Sendable {
    case read, write, admin
}

extension Endpoint where Response == Whoami {
    public static var whoami: Self {
        Self(.get, Route.whoami)
    }
}
