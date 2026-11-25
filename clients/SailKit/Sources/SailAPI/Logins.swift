import Foundation

/// A person's stored logins, never a value.
public struct LoginList: Codable, Equatable, Sendable {
    public let fde: String
    public let logins: [StoredLogin]
}

/// One stored login as anyone is shown it: whose, its harness, its name and kind, and whether it
/// is the one its harness starts with. Never its value.
public struct StoredLogin: Codable, Equatable, Sendable {
    public let fde: String
    public let harness: String
    public let name: String
    public let kind: String
    public let isDefault: Bool

    private enum CodingKeys: String, CodingKey {
        case fde, harness, name, kind
        case isDefault = "default"
    }
}

/// What a store answered: the login stored, and what is said once of runs that started with one
/// it replaced.
public struct LoginStored: Codable, Equatable, Sendable {
    public let login: StoredLogin
    public let note: String?
}

extension Endpoint where Response == LoginList {
    /// The caller's own logins.
    public static var logins: Self {
        Self(.get, Route.logins)
    }
}

extension Endpoint where Response == LoginStored {
    /// Stores `value` as the caller's login `name` of `harness`, of `kind`, replacing one of that
    /// name. The value is in the body alone: never in the path, the route or a description, so no
    /// error or log line that names a request names it.
    public static func storeLogin(_ value: String, kind: String, harness: String, name: String) throws(SailError) -> Self {
        Self(.put, Route.logins + [harness, name], body: try SailJSON.encode(["kind": kind, "value": value]))
    }

    /// Makes the caller's login `name` of `harness` the default, the one its starts take.
    public static func useLogin(harness: String, name: String) -> Self {
        Self(.post, Route.logins + [harness, name + ":use"])
    }
}
