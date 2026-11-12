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
        if var items = try? decoder.unkeyedContainer() {
            var array: [JSONValue] = []
            while !items.isAtEnd {
                array.append(try items.decode(JSONValue.self))
            }
            self = .array(array)
        } else if let fields = try? decoder.container(keyedBy: Key.self) {
            var object: [String: JSONValue] = [:]
            for key in fields.allKeys {
                object[key.stringValue] = try fields.decode(JSONValue.self, forKey: key)
            }
            self = .object(object)
        } else {
            self = try Self.scalar(decoder.singleValueContainer())
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

    /// Any key an object holds.
    private struct Key: CodingKey {
        let stringValue: String
        let intValue: Int? = nil

        init(stringValue: String) {
            self.stringValue = stringValue
        }

        init?(intValue: Int) {
            nil
        }
    }
}
