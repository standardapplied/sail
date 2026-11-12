import Foundation

/// The goldens the server's `ClientGoldensTest` writes, read where it writes them, so every shape
/// a test decodes is one the server produced.
public enum Goldens {
    static let root = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "Goldens")

    /// The instant the server's golden writer pins its clock to, in milliseconds since 1970: one
    /// with a fraction, so an instant read to the second is not the instant written.
    public static let pinnedMilliseconds: Int64 = 1_794_387_600_656

    public static func bytes(_ path: String) throws -> [UInt8] {
        [UInt8](try Data(contentsOf: root.appending(path: path)))
    }

    /// The names of the files in `directory`, without their extension, in name order.
    public static func names(in directory: String) throws -> [String] {
        try FileManager.default
            .contentsOfDirectory(atPath: root.appending(path: directory).path)
            .map { ($0 as NSString).deletingPathExtension }
            .sorted()
    }

    /// The object `path` holds.
    public static func object(_ path: String) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: Data(bytes(path))) as? [String: Any] else {
            throw GoldenError(reason: "\(path) does not hold an object")
        }
        return object
    }

    /// Every pty frame the host's `PtyWire` wrote, by its message name, as its full wire bytes.
    public static func frames() throws -> [(name: String, wire: [UInt8])] {
        let frames = try object("frames/frames.json")["frames"] as? [[String: String]] ?? []
        return try frames.map { frame in
            guard let name = frame["name"], let hex = frame["hex"] else {
                throw GoldenError(reason: "a frame without a name or hex")
            }
            return (name, try bytes(hex: hex))
        }
    }

    /// The handshake magic the host's `PtyWire` sends.
    public static func magic() throws -> [UInt8] {
        try bytes(hex: object("frames/frames.json")["magic"] as? String ?? "")
    }

    /// The largest payload the host's `PtyWire` sends.
    public static func maxPayload() throws -> Int {
        guard let largest = try object("frames/frames.json")["max_payload"] as? Int else {
            throw GoldenError(reason: "frames.json names no max_payload")
        }
        return largest
    }

    /// How the server answers a request for its event stream: `open` when it streams, `full` when
    /// every stream it allows is taken. The status, and the headers a reader acts on.
    public static func streamAnswer(_ name: String) throws -> (status: Int, headers: [String: String]) {
        guard var answer = try object("events/answers.json")[name] as? [String: Any],
              let status = answer.removeValue(forKey: "status") as? Int
        else {
            throw GoldenError(reason: "answers.json holds no \(name) answer")
        }
        return (status, answer.compactMapValues { $0 as? String })
    }

    /// The words of `vocabulary` the server's own enum holds.
    public static func vocabulary(_ vocabulary: String) throws -> [String] {
        let words = try object("vocabulary/vocabulary.json")[vocabulary] as? [Any] ?? []
        return words.map { word in
            (word as? [String: Any]).flatMap { $0["code"] as? String } ?? String(describing: word)
        }
    }

    static func bytes(hex: String) throws -> [UInt8] {
        let digits = Array(hex.utf8)
        guard digits.count.isMultiple(of: 2) else { throw GoldenError(reason: "odd hex: \(hex)") }
        return try stride(from: 0, to: digits.count, by: 2).map { index in
            guard let byte = UInt8(String(decoding: digits[index ..< index + 2], as: UTF8.self), radix: 16) else {
                throw GoldenError(reason: "not hex: \(hex)")
            }
            return byte
        }
    }
}

/// One request the server's test sent and the answer its router gave, as `http/<name>.json` holds.
public struct Exchange: Sendable {
    public let method: String
    public let path: String
    public let requestBody: Data?
    public let status: Int
    public let body: Data

    public init(_ name: String) throws {
        let golden = try Goldens.object("http/\(name).json")
        guard let request = golden["request"] as? [String: Any],
              let response = golden["response"] as? [String: Any],
              let method = request["method"] as? String,
              let path = request["path"] as? String,
              let status = response["status"] as? Int,
              let body = response["body"]
        else {
            throw GoldenError(reason: "http/\(name).json is not a request and its response")
        }
        self.method = method
        self.path = path
        self.status = status
        self.requestBody = try request["body"].map { try JSONSerialization.data(withJSONObject: $0) }
        self.body = try JSONSerialization.data(withJSONObject: body)
    }

    private init(_ exchange: Exchange, body: Data) {
        method = exchange.method
        path = exchange.path
        requestBody = exchange.requestBody
        status = exchange.status
        self.body = body
    }

    /// This exchange with the response body's value at `path` replaced by `value`, or removed when
    /// `value` is nil: a server's answer altered as a test needs, never a shape typed by hand.
    public func altered(at path: [any JSONPathStep], to value: Any?) throws -> Exchange {
        let object = try JSONSerialization.jsonObject(with: body)
        return Exchange(self, body: try JSONSerialization.data(withJSONObject: replacing(object, at: path[...], with: value)))
    }

    private func replacing(_ node: Any, at path: ArraySlice<any JSONPathStep>, with value: Any?) throws -> Any {
        guard let step = path.first else { return value as Any }
        switch (step, node) {
        case let (key as String, object as [String: Any]):
            var object = object
            let rest = path.dropFirst()
            if rest.isEmpty, value == nil {
                object[key] = nil
            } else {
                object[key] = try replacing(object[key] as Any, at: rest, with: value)
            }
            return object
        case let (index as Int, array as [Any]) where array.indices.contains(index):
            var array = array
            array[index] = try replacing(array[index], at: path.dropFirst(), with: value)
            return array
        default:
            throw GoldenError(reason: "no \(step) in \(node)")
        }
    }
}

extension Date {
    /// This instant to the millisecond, as the server writes one.
    public var milliseconds: Int64 {
        Int64((timeIntervalSince1970 * 1000).rounded())
    }
}

/// A key or an index into a golden's JSON.
public protocol JSONPathStep: Sendable {}
extension String: JSONPathStep {}
extension Int: JSONPathStep {}

public struct GoldenError: Error, CustomStringConvertible {
    public let reason: String

    public init(reason: String) {
        self.reason = reason
    }

    public var description: String {
        reason
    }
}
