/// The `SAILPTY3` framing, byte for byte as the host's `PtyWire` writes it: a big-endian `Int32`
/// length, then the payload, a type byte and its fields. Integers are big-endian; strings and byte
/// blobs are an `Int32` length and the bytes; a list is an `Int32` count and its entries.
public enum FrameCodec {
    /// The largest payload either side accepts; anything larger is a protocol violation.
    public static let maxPayload = 1 << 20

    /// `frame` as it travels: its length, then its payload.
    public static func encode(_ frame: Frame) throws(TerminalWireError) -> [UInt8] {
        let writer = Writer()
        writer.write(frame)
        return try writer.framed()
    }

    /// The frame `payload` holds, the length that framed it already read. Bytes past the frame's
    /// last field are left unread, as the host leaves them, so a field a newer peer appends is not
    /// an error.
    public static func decode(payload: some Collection<UInt8>) throws(TerminalWireError) -> Frame {
        var cursor = Cursor(Array(payload))
        return try cursor.frame()
    }
}

/// The integer `bytes` hold, most significant first.
func integer<Integer: FixedWidthInteger>(bigEndian bytes: some Collection<UInt8>) -> Integer {
    bytes.reduce(Integer.zero) { $0 << 8 | Integer($1) }
}

/// Writes one frame's payload behind room for its length, each field returning the writer for the next.
private final class Writer {
    private var bytes: [UInt8] = [0, 0, 0, 0]

    func framed() throws(TerminalWireError) -> [UInt8] {
        let length = bytes.count - 4
        guard length <= FrameCodec.maxPayload else { throw .oversized(length) }
        bytes.replaceSubrange(0 ..< 4, with: withUnsafeBytes(of: Int32(length).bigEndian, Array.init))
        return bytes
    }

    func write(_ frame: Frame) {
        switch frame {
        case let .hello(token): type(9).string(token)
        case let .welcome(hostBootID): type(32).string(hostBootID)
        case let .create(create):
            type(1).string(create.session).strings(create.command).string(create.cwd)
                .string(create.project).string(create.room).size(create.size)
        case let .attach(session, write): type(2).string(session).bool(write)
        case let .input(seq, bytes): type(3).int64(seq).blob(bytes)
        case let .resize(size): type(4).size(size)
        case .takeWrite: type(5)
        case .detach: type(6)
        case let .listSessions(after, limit): type(7).string(after).int32(limit)
        case let .kill(session): type(8).string(session)
        case let .yield(session, reason): type(10).string(session).string(reason)
        case let .output(lastInputSeq, bytes): type(20).int64(lastInputSeq).blob(bytes)
        case let .replayBegin(safe): type(21).bool(safe)
        case .replayEnd: type(22)
        case let .writerChanged(fde): type(23).string(fde)
        case let .resized(size): type(24).size(size)
        case .paused: type(25)
        case .continued: type(26)
        case let .sessionEnded(reason): type(27).string(reason)
        case let .sessionInfo(info): type(28).session(info)
        case let .sessions(page):
            type(29).count(page.sessions)
            for info in page.sessions {
                session(info)
            }
            string(page.next)
        case .ok: type(30)
        case let .err(refusal):
            type(31).string(refusal.message)
            if let remedy = refusal.remedy {
                count(remedy)
                for part in remedy {
                    self.part(part)
                }
            }
        }
    }

    @discardableResult
    private func type(_ type: UInt8) -> Writer {
        bytes.append(type)
        return self
    }

    private func part(_ part: HostRefusal.Part) {
        switch part {
        case let .words(text): bool(false).string(text)
        case let .command(runs, verb, args): bool(true).string(runs).string(verb).strings(args)
        }
    }

    @discardableResult
    private func size(_ size: TerminalSize) -> Writer {
        int32(size.cols).int32(size.rows)
    }

    @discardableResult
    private func session(_ info: SessionInfo) -> Writer {
        string(info.name).string(info.instanceID).bool(info.live).int32(info.attached)
            .string(info.writerFDE).string(info.room).strings(info.command)
    }

    @discardableResult
    private func string(_ text: String) -> Writer {
        blob(Array(text.utf8))
    }

    @discardableResult
    private func strings(_ list: [String]) -> Writer {
        count(list)
        for text in list {
            string(text)
        }
        return self
    }

    @discardableResult
    private func bool(_ value: Bool) -> Writer {
        bytes.append(value ? 1 : 0)
        return self
    }

    @discardableResult
    private func int32(_ value: Int32) -> Writer {
        bytes += withUnsafeBytes(of: value.bigEndian, Array.init)
        return self
    }

    @discardableResult
    private func int64(_ value: Int64) -> Writer {
        bytes += withUnsafeBytes(of: value.bigEndian, Array.init)
        return self
    }

    /// A length or count; one past what an `Int32` holds is written as its largest, and the frame
    /// that carries it is refused as oversized rather than trapping here.
    @discardableResult
    private func count(_ collection: some Collection) -> Writer {
        int32(Int32(clamping: collection.count))
    }

    @discardableResult
    private func blob(_ blob: [UInt8]) -> Writer {
        count(blob)
        bytes += blob
        return self
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
        case 3: .input(seq: try int64(), bytes: try blob())
        case 4: .resize(try size())
        case 5: .takeWrite
        case 6: .detach
        case 7: .listSessions(after: try string(), limit: try int32())
        case 8: .kill(session: try string())
        case 10: .yield(session: try string(), reason: try string())
        case 20: .output(lastInputSeq: try int64(), bytes: try blob())
        case 21: .replayBegin(safe: try bool())
        case 22: .replayEnd
        case 23: .writerChanged(fde: try string())
        case 24: .resized(try size())
        case 25: .paused
        case 26: .continued
        case 27: .sessionEnded(reason: try string())
        case 28: .sessionInfo(try session())
        case 29: .sessions(SessionPage(sessions: try list { (cursor: inout Cursor) throws(TerminalWireError) in try cursor.session() }, next: try string()))
        case 30: .ok
        case 31: .err(try refusal())
        case let other: throw .unknownType(other)
        }
    }

    private mutating func create() throws(TerminalWireError) -> SessionCreate {
        SessionCreate(
            session: try string(), command: try strings(), cwd: try string(), project: try string(),
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

    private mutating func part() throws(TerminalWireError) -> HostRefusal.Part {
        switch try byte() {
        case 0: .words(try string())
        case 1: .command(runs: try string(), verb: try string(), args: try strings())
        case let kind: throw .malformed("a remedy part of kind \(kind)")
        }
    }

    private mutating func size() throws(TerminalWireError) -> TerminalSize {
        TerminalSize(cols: try int32(), rows: try int32())
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
