import SailAPI

/// The `SAILPTY3` framing, byte for byte as the host's `PtyWire` writes it: a big-endian `Int32`
/// length, then the payload, a type byte and its fields. Integers are big-endian; strings and byte
/// blobs are an `Int32` length and the bytes; a list is an `Int32` count and its entries.
public enum FrameCodec {
    /// The largest payload either side accepts; anything larger is a protocol violation.
    public static let maxPayload = 1 << 20

    /// `frame` as it travels: its length, then its payload.
    public static func encode(_ frame: Frame) throws(TerminalWireError) -> [UInt8] {
        var payload = [frame.type]
        for field in frame.fields {
            field.append(to: &payload)
        }
        guard payload.count <= maxPayload else { throw .lengthOutOfRange(payload.count) }
        return bigEndian(Int32(payload.count)) + payload
    }

    /// The frame `payload` holds, the length that framed it already read. Bytes past the frame's
    /// last field are left unread, as the host leaves them, so a field a newer peer appends is not
    /// an error.
    public static func decode(payload: some Collection<UInt8>) throws(TerminalWireError) -> Frame {
        var cursor = Cursor(Array(payload))
        return try cursor.frame()
    }
}

/// `value` as it travels, most significant byte first.
func bigEndian(_ value: some FixedWidthInteger) -> [UInt8] {
    withUnsafeBytes(of: value.bigEndian, Array.init)
}

/// The integer `bytes` hold, most significant first.
func integer<Integer: FixedWidthInteger>(bigEndian bytes: some Collection<UInt8>) -> Integer {
    bytes.reduce(Integer.zero) { $0 << 8 | Integer($1) }
}

/// One field of a payload as the host's `PtyWire` writes it.
private enum Field {
    case bool(Bool)
    case int32(Int32)
    case int64(Int64)
    case blob([UInt8])
    case string(String)
    case strings([String])
    /// A length or count; one past what an `Int32` holds is written as its largest, and the frame
    /// that carries it is refused as too long rather than trapping here.
    case count(Int)

    func append(to payload: inout [UInt8]) {
        switch self {
        case let .bool(value): payload.append(value ? 1 : 0)
        case let .int32(value): payload += bigEndian(value)
        case let .int64(value): payload += bigEndian(value)
        case let .count(count): payload += bigEndian(Int32(clamping: count))
        case let .blob(blob):
            Field.count(blob.count).append(to: &payload)
            payload += blob
        case let .string(text): Field.blob(Array(text.utf8)).append(to: &payload)
        case let .strings(list):
            Field.count(list.count).append(to: &payload)
            for text in list {
                Field.string(text).append(to: &payload)
            }
        }
    }
}

private extension Frame {
    /// The byte that names this frame's type on the wire.
    var type: UInt8 {
        switch self {
        case .create: 1
        case .attach: 2
        case .input: 3
        case .resize: 4
        case .takeWrite: 5
        case .detach: 6
        case .listSessions: 7
        case .kill: 8
        case .hello: 9
        case .yield: 10
        case .output: 20
        case .replayBegin: 21
        case .replayEnd: 22
        case .writerChanged: 23
        case .resized: 24
        case .paused: 25
        case .continued: 26
        case .sessionEnded: 27
        case .sessionInfo: 28
        case .sessions: 29
        case .accepted: 30
        case .refused: 31
        case .welcome: 32
        }
    }

    /// This frame's fields in the order the host writes them.
    var fields: [Field] {
        switch self {
        case let .hello(token): [.string(token)]
        case let .welcome(hostBootID): [.string(hostBootID)]
        case let .create(create):
            [
                .string(create.session), .strings(create.command), .string(create.workingDirectory),
                .string(create.project), .string(create.room),
            ] + create.size.fields
        case let .attach(session, write): [.string(session), .bool(write)]
        case let .input(sequence, bytes): [.int64(sequence), .blob(bytes)]
        case let .resize(size), let .resized(size): size.fields
        case let .listSessions(after, limit): [.string(after), .int32(limit)]
        case let .kill(session): [.string(session)]
        case let .yield(session, reason): [.string(session), .string(reason)]
        case let .output(lastInputSequence, bytes): [.int64(lastInputSequence), .blob(bytes)]
        case let .replayBegin(safe): [.bool(safe)]
        case let .writerChanged(fde): [.string(fde)]
        case let .sessionEnded(reason): [.string(reason)]
        case let .sessionInfo(info): info.fields
        case let .sessions(page): [.count(page.sessions.count)] + page.sessions.flatMap(\.fields) + [.string(page.next)]
        case let .refused(refusal):
            [.string(refusal.message)] + (refusal.remedy.map { [.count($0.count)] + $0.flatMap(\.fields) } ?? [])
        case .takeWrite, .detach, .replayEnd, .paused, .continued, .accepted: []
        }
    }
}

private extension TerminalSize {
    var fields: [Field] {
        [.int32(columns), .int32(rows)]
    }
}

private extension SessionInfo {
    var fields: [Field] {
        [
            .string(name), .string(instanceID), .bool(live), .int32(attached), .string(writerFDE), .string(room),
            .strings(command),
        ]
    }
}

private extension RemedyPart {
    var fields: [Field] {
        switch self {
        case let .words(text): [.bool(false), .string(text)]
        case let .command(run, verb, args): [.bool(true), .string(run.hostName), .string(verb), .strings(args)]
        }
    }
}

private extension Open<Runner> {
    /// The runner the host's frames name `hostName`: a frame spells a runner as the host's enum
    /// does, in capitals, where the API's JSON spells it in lower case. A name this client does
    /// not know is kept as it came.
    init(hostName: String) {
        self = Runner.allCases.first { $0.rawValue.uppercased() == hostName }.map(Self.known) ?? .unknown(hostName)
    }

    var hostName: String {
        switch self {
        case let .known(runner): runner.rawValue.uppercased()
        case let .unknown(name): name
        }
    }
}

/// Reads one payload's fields in order, refusing any length or count before it sizes anything.
private struct Cursor {
    private let bytes: [UInt8]
    private var index = 0

    init(_ bytes: [UInt8]) {
        self.bytes = bytes
    }

    private var remaining: Int {
        bytes.count - index
    }

    mutating func frame() throws(TerminalWireError) -> Frame {
        switch try byte() {
        case 9: .hello(token: try string())
        case 32: .welcome(hostBootID: try string())
        case 1: .create(try create())
        case 2: .attach(session: try string(), write: try bool())
        case 3: .input(sequence: try int64(), bytes: try blob())
        case 4: .resize(try size())
        case 5: .takeWrite
        case 6: .detach
        case 7: .listSessions(after: try string(), limit: try int32())
        case 8: .kill(session: try string())
        case 10: .yield(session: try string(), reason: try string())
        case 20: .output(lastInputSequence: try int64(), bytes: try blob())
        case 21: .replayBegin(safe: try bool())
        case 22: .replayEnd
        case 23: .writerChanged(fde: try string())
        case 24: .resized(try size())
        case 25: .paused
        case 26: .continued
        case 27: .sessionEnded(reason: try string())
        case 28: .sessionInfo(try session())
        case 29: .sessions(SessionPage(sessions: try list { (cursor: inout Cursor) throws(TerminalWireError) in try cursor.session() }, next: try string()))
        case 30: .accepted
        case 31: .refused(try refusal())
        case let other: throw .unknownType(other)
        }
    }

    private mutating func create() throws(TerminalWireError) -> SessionCreate {
        SessionCreate(
            session: try string(), command: try strings(), workingDirectory: try string(), project: try string(),
            room: try string(), size: try size()
        )
    }

    private mutating func session() throws(TerminalWireError) -> SessionInfo {
        SessionInfo(
            name: try string(), instanceID: try string(), live: try bool(), attached: try int32(),
            writerFDE: try string(), room: try string(), command: try strings()
        )
    }

    private mutating func refusal() throws(TerminalWireError) -> HostRefusal {
        let message = try string()
        guard remaining > 0 else { return HostRefusal(message: message, remedy: nil) }
        return HostRefusal(message: message, remedy: try list { (cursor: inout Cursor) throws(TerminalWireError) in try cursor.part() })
    }

    private mutating func part() throws(TerminalWireError) -> RemedyPart {
        switch try byte() {
        case 0: .words(try string())
        case 1: .command(run: Open(hostName: try string()), verb: try string(), args: try strings())
        case let kind: throw .malformed("a remedy part of kind \(kind)")
        }
    }

    private mutating func size() throws(TerminalWireError) -> TerminalSize {
        TerminalSize(columns: try int32(), rows: try int32())
    }

    private mutating func strings() throws(TerminalWireError) -> [String] {
        try list { (cursor: inout Cursor) throws(TerminalWireError) in try cursor.string() }
    }

    /// A count, then that many entries; nothing is reserved for the count, so one larger than the
    /// frame holds fails at the first entry it cannot read.
    private mutating func list<Entry>(
        _ entry: (inout Cursor) throws(TerminalWireError) -> Entry
    ) throws(TerminalWireError) -> [Entry] {
        let count = try int32()
        guard count >= 0 else { throw .malformed("a list claims \(count) entries") }
        var entries: [Entry] = []
        for _ in 0 ..< count {
            entries.append(try entry(&self))
        }
        return entries
    }

    private mutating func string() throws(TerminalWireError) -> String {
        guard let text = String(validating: try blob(), as: UTF8.self) else {
            throw .malformed("a string is not UTF-8")
        }
        return text
    }

    private mutating func blob() throws(TerminalWireError) -> [UInt8] {
        let length = try int32()
        guard length >= 0 else { throw .malformed("a field claims \(length) bytes") }
        return Array(try take(Int(length)))
    }

    private mutating func bool() throws(TerminalWireError) -> Bool {
        try byte() == 1
    }

    private mutating func byte() throws(TerminalWireError) -> UInt8 {
        try take(1)[index - 1]
    }

    private mutating func int32() throws(TerminalWireError) -> Int32 {
        integer(bigEndian: try take(4))
    }

    private mutating func int64() throws(TerminalWireError) -> Int64 {
        integer(bigEndian: try take(8))
    }

    private mutating func take(_ count: Int) throws(TerminalWireError) -> ArraySlice<UInt8> {
        guard count <= remaining else { throw .truncated }
        defer { index += count }
        return bytes[index ..< index + count]
    }
}
