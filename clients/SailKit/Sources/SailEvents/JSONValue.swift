/// A JSON value whose shape the server leaves open: an event's `data`, which each type fills its own
/// way. A number written without a fraction or an exponent that an `Int64` holds is an `integer`,
/// exactly; any other is a `number`.
public enum JSONValue: Hashable, Sendable, Codable {
    case null
    case bool(Bool)
    case integer(Int64)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: any Decoder) throws {
        let value = try decoder.singleValueContainer()
        if (try? decoder.unkeyedContainer()) != nil {
            self = .array(try value.decode([JSONValue].self))
        } else if (try? decoder.container(keyedBy: NoKey.self)) != nil {
            self = .object(try value.decode([String: JSONValue].self))
        } else {
            self = try Self.scalar(value)
        }
    }

    private static func scalar(_ value: any SingleValueDecodingContainer) throws -> JSONValue {
        if value.decodeNil() {
            .null
        } else if let bool = try? value.decode(Bool.self) {
            .bool(bool)
        } else if let integer = try? value.decode(Int64.self) {
            .integer(integer)
        } else if let number = try? value.decode(Double.self) {
            .number(number)
        } else if let string = try? value.decode(String.self) {
            .string(string)
        } else {
            throw DecodingError.dataCorruptedError(in: value, debugDescription: "it is a number no Int64 or Double holds")
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .null: try value.encodeNil()
        case let .bool(bool): try value.encode(bool)
        case let .integer(integer): try value.encode(integer)
        case let .number(number): try value.encode(number)
        case let .string(string): try value.encode(string)
        case let .array(array): try value.encode(array)
        case let .object(object): try value.encode(object)
        }
    }

    /// Asks whether a value is an object without naming any of its keys.
    private enum NoKey: CodingKey {}
}
