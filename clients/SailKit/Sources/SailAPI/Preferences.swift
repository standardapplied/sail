import Foundation

/// The form a session takes: a terminal, or a chat.
public enum SessionForm: String, CaseIterable, Codable, Sendable {
    case terminal, chat
}

/// The forms a person starts sessions in: chats only, terminals only, or either, chosen each time.
public enum FormPreference: String, CaseIterable, Codable, Sendable {
    case chat, terminal, both
}

/// What a person chose of how they work, kept by the server for them.
public struct Preferences: Codable, Equatable, Sendable {
    public let sessionForm: Open<FormPreference>

    public init(sessionForm: Open<FormPreference>) {
        self.sessionForm = sessionForm
    }

    private enum CodingKeys: String, CodingKey {
        case sessionForm = "session_form"
    }
}

extension Route {
    package static let preferences = ["v1", "me", "preferences"]
}

extension Endpoint where Response == Preferences {
    public static var preferences: Self {
        Self(.get, Route.preferences)
    }

    /// Keeps `preferences` for the caller, answering them as kept.
    public static func setPreferences(_ preferences: Preferences) throws(SailError) -> Self {
        Self(.put, Route.preferences, body: try SailJSON.encode(preferences))
    }
}
