/// One event of a Server-Sent Events stream, as the WHATWG standard dispatches it: its type
/// (`message` unless the stream named one), its data, and the last event id the stream had given
/// when it was dispatched.
public struct ServerSentEvent: Equatable, Sendable {
    public let type: String
    public let data: String
    public let lastEventID: String

    public init(type: String, data: String, lastEventID: String) {
        self.type = type
        self.data = data
        self.lastEventID = lastEventID
    }
}

/// Reads one connection's Server-Sent Events from its bytes, in whatever chunks they arrive, by the
/// WHATWG event-stream rules. Lines are split on bytes, so a chunk boundary inside a character or
/// between `\r` and `\n` changes nothing; an event is dispatched only at its blank line, so one
/// the connection ends inside is never seen. The id buffer starts at the id a previous connection
/// last dispatched, so an event a server sends without an id does not erase where to resume.
public struct EventStreamParser: Sendable {
    /// The id of the last event dispatched: what a reconnect sends as `Last-Event-ID`.
    public private(set) var lastEventID: String
    /// The reconnection time the stream last set with a `retry` field.
    public private(set) var retry: Duration?

    private var line: [UInt8] = []
    private var afterCarriageReturn = false
    private var atStart = true
    private var data = ""
    private var type = ""
    private var idBuffer: String

    public init(lastEventID: String = "") {
        self.lastEventID = lastEventID
        idBuffer = lastEventID
    }

    /// Takes `bytes` and answers every event they complete, in order.
    public mutating func feed(_ bytes: some Sequence<UInt8>) -> [ServerSentEvent] {
        var events: [ServerSentEvent] = []
        for byte in bytes {
            if afterCarriageReturn {
                afterCarriageReturn = false
                if byte == .lineFeed { continue }
            }
            switch byte {
            case .carriageReturn:
                afterCarriageReturn = true
                endLine(into: &events)
            case .lineFeed:
                endLine(into: &events)
            default:
                line.append(byte)
            }
        }
        return events
    }

    private mutating func endLine(into events: inout [ServerSentEvent]) {
        if atStart, line.starts(with: Self.byteOrderMark) {
            line.removeFirst(Self.byteOrderMark.count)
        }
        atStart = false
        defer { line.removeAll(keepingCapacity: true) }
        guard let colon = line.firstIndex(of: .colon) else {
            if line.isEmpty {
                dispatch(into: &events)
            } else {
                field(text(line[...]), value: "")
            }
            return
        }
        guard colon > line.startIndex else { return }
        var value = line[(colon + 1)...]
        if value.first == .space {
            value = value.dropFirst()
        }
        field(text(line[..<colon]), value: text(value))
    }

    private mutating func field(_ name: String, value: String) {
        switch name {
        case "event": type = value
        case "data": data += value + "\n"
        case "id" where !value.unicodeScalars.contains("\u{0}"): idBuffer = value
        case "retry" where !value.isEmpty && value.utf8.allSatisfy({ (UInt8(ascii: "0") ... UInt8(ascii: "9")).contains($0) }):
            if let milliseconds = Int(value) {
                retry = .milliseconds(milliseconds)
            }
        default: break
        }
    }

    private mutating func dispatch(into events: inout [ServerSentEvent]) {
        lastEventID = idBuffer
        defer {
            data = ""
            type = ""
        }
        guard !data.isEmpty else { return }
        events.append(ServerSentEvent(type: type.isEmpty ? "message" : type, data: String(data.dropLast()), lastEventID: lastEventID))
    }

    private func text(_ bytes: ArraySlice<UInt8>) -> String {
        String(decoding: bytes, as: UTF8.self)
    }

    private static let byteOrderMark: [UInt8] = [0xEF, 0xBB, 0xBF]
}

private extension UInt8 {
    static let lineFeed = UInt8(ascii: "\n")
    static let carriageReturn = UInt8(ascii: "\r")
    static let colon = UInt8(ascii: ":")
    static let space = UInt8(ascii: " ")
}
