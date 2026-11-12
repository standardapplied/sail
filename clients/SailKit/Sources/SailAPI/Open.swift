/// A word from a vocabulary the server may grow: one this client knows, or the raw word a newer
/// server sent, kept rather than refused or dropped.
public enum Open<Known>: RawRepresentable, Hashable, Sendable, Codable
where Known: RawRepresentable & Hashable & Sendable, Known.RawValue == String {
    case known(Known)
    case unknown(String)

    public init(rawValue: String) {
        self = Known(rawValue: rawValue).map(Self.known) ?? .unknown(rawValue)
    }

    public init(_ known: Known) {
        self = .known(known)
    }

    public var rawValue: String {
        switch self {
        case let .known(known): known.rawValue
        case let .unknown(raw): raw
        }
    }
}
