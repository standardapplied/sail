import SailKitTestSupport
import SailTerminalWire
import Testing

@Suite struct FrameCodecTests {
    @Test func everyFrameTheHostWritesDecodesAsItsTypeAndEncodesToTheSameBytes() throws {
        let frames = try Goldens.frames()
        #expect(frames.count == 23)
        for (name, wire) in frames {
            let frame = try FrameCodec.decode(payload: wire.dropFirst(4))
            #expect(messageName(of: frame) == name)
            #expect(try FrameCodec.encode(frame) == wire, "\(name)")
        }
    }

    @Test func theGoldenFramesCarryTheirFieldsInOrder() throws {
        let frames = try Dictionary(uniqueKeysWithValues: Goldens.frames().map { ($0.name, try decoded($0.wire)) })
        #expect(frames["Create"] == .create(SessionCreate(
            session: "lounge", command: ["bash", "-l"], cwd: "/home/dev", project: "harbor",
            room: "harbor-design", size: TerminalSize(cols: 132, rows: 43)
        )))
        #expect(frames["Attach"] == .attach(session: "lounge", write: true))
        #expect(frames["Input"] == .input(seq: 42, bytes: Array("ls -la\r".utf8)))
        #expect(frames["Output"] == .output(lastInputSeq: 42, bytes: Array("héllo ✅\u{1b}[0m\r\n".utf8)))
        #expect(frames["Resize"] == .resize(TerminalSize(cols: 100, rows: 30)))
        #expect(frames["ReplayBegin"] == .replayBegin(safe: true))
        #expect(frames["ListSessions"] == .listSessions(after: "", limit: 16))
        #expect(frames["Sessions"] == .sessions(SessionPage(sessions: [lounge, shell], next: "shell")))
        #expect(frames["SessionInfo"] == .sessionInfo(lounge))
        #expect(frames["Err"] == .err(HostRefusal(
            message: "You do not hold the write token. Run sail session take lounge to take it.",
            remedy: [
                .words("You do not hold the write token. Run "),
                .command(runs: "SAIL", verb: "session", args: ["take", "lounge"]),
                .words(" to take it."),
            ]
        )))
    }

    @Test func aRefusalFromAHostThatSendsNoRemedyKeepsItsMessageAndEncodesAsItCame() throws {
        let err = try golden("Err")
        let message = 4 + Int(try decodedLength(err, at: 5))
        let bare = lengthPrefixed(Array(err[4 ..< 5 + message]))
        let frame = try decoded(bare)
        #expect(frame == .err(HostRefusal(
            message: "You do not hold the write token. Run sail session take lounge to take it.",
            remedy: nil
        )))
        #expect(try FrameCodec.encode(frame) == bare)
    }

    @Test func everyFrameCutShortIsTruncatedNeverAFrame() throws {
        for (name, wire) in try Goldens.frames() {
            let payload = Array(wire.dropFirst(4))
            let complete = name == "Err" ? try errWithoutRemedy(payload) : payload.count
            for end in 0 ..< payload.count where end != complete {
                #expect(throws: TerminalWireError.truncated, "\(name) cut at \(end)") {
                    try FrameCodec.decode(payload: payload[..<end])
                }
            }
        }
    }

    @Test func aFieldClaimingMoreBytesThanTheFrameHoldsIsTruncated() throws {
        var hello = try golden("Hello")
        hello.replaceSubrange(5 ..< 9, with: bigEndian(Int32.max))
        #expect(throws: TerminalWireError.truncated) { try FrameCodec.decode(payload: hello.dropFirst(4)) }
    }

    @Test func aNegativeFieldLengthOrCountIsMalformed() throws {
        var hello = try golden("Hello")
        hello.replaceSubrange(5 ..< 9, with: bigEndian(-1))
        #expect(throws: TerminalWireError.malformed("a field claims -1 bytes")) {
            try FrameCodec.decode(payload: hello.dropFirst(4))
        }
        var sessions = try golden("Sessions")
        sessions.replaceSubrange(5 ..< 9, with: bigEndian(-2))
        #expect(throws: TerminalWireError.malformed("a list claims -2 entries")) {
            try FrameCodec.decode(payload: sessions.dropFirst(4))
        }
    }

    @Test func aStringThatIsNotUTF8IsMalformed() throws {
        var hello = try golden("Hello")
        hello[9] = 0xFF
        #expect(throws: TerminalWireError.malformed("a string is not UTF-8")) {
            try FrameCodec.decode(payload: hello.dropFirst(4))
        }
    }

    @Test func aRemedyPartOfAnUnknownKindIsMalformed() throws {
        var err = try golden("Err")
        let message = 4 + Int(try decodedLength(err, at: 5))
        err[5 + message + 4] = 7
        #expect(throws: TerminalWireError.malformed("a remedy part of kind 7")) {
            try FrameCodec.decode(payload: err.dropFirst(4))
        }
    }

    @Test func aTypeTheHostNeverWritesIsUnknown() {
        #expect(throws: TerminalWireError.unknownType(99)) { try FrameCodec.decode(payload: [99]) }
        #expect(throws: TerminalWireError.truncated) { try FrameCodec.decode(payload: []) }
    }

    @Test func aFrameLargerThanTheHostAcceptsIsRefusedBeforeItIsSent() throws {
        let largest = FrameCodec.maxPayload - 13
        #expect(try FrameCodec.encode(.input(seq: 1, bytes: Array(repeating: 0, count: largest))).count
            == 4 + FrameCodec.maxPayload)
        #expect(throws: TerminalWireError.oversized(FrameCodec.maxPayload + 1)) {
            try FrameCodec.encode(.input(seq: 1, bytes: Array(repeating: 0, count: largest + 1)))
        }
    }

    @Test func theHandshakeIsTheHostsMagicAndNamesWhichSideIsBehind() throws {
        #expect(Handshake.magic == (try Goldens.magic()))
        try Handshake.check(peer: Goldens.magic())
        #expect(throws: TerminalWireError.skew(.hostOlder)) { try Handshake.check(peer: Array("SAILPTY2".utf8)) }
        #expect(throws: TerminalWireError.skew(.hostOlder)) { try Handshake.check(peer: Array("SAILPTY1".utf8)) }
        #expect(throws: TerminalWireError.skew(.clientOlder)) { try Handshake.check(peer: Array("SAILPTY4".utf8)) }
        #expect(throws: TerminalWireError.skew(.clientOlder)) { try Handshake.check(peer: Array("SAIL".utf8)) }
    }
}

let lounge = SessionInfo(
    name: "lounge", instanceID: "inst-1", live: true, attached: 2, writerFDE: "uday",
    room: "harbor-design", command: ["claude", "--resume"]
)
let shell = SessionInfo(
    name: "shell", instanceID: "inst-2", live: false, attached: 0, writerFDE: "", room: "", command: ["bash"]
)

func golden(_ name: String) throws -> [UInt8] {
    guard let wire = try Goldens.frames().first(where: { $0.name == name })?.wire else {
        throw GoldenError(reason: "no \(name) frame")
    }
    return wire
}

func decoded(_ wire: [UInt8]) throws -> Frame {
    try FrameCodec.decode(payload: wire.dropFirst(4))
}

func bigEndian(_ value: Int32) -> [UInt8] {
    withUnsafeBytes(of: value.bigEndian, Array.init)
}

func lengthPrefixed(_ payload: [UInt8]) -> [UInt8] {
    bigEndian(Int32(payload.count)) + payload
}

func decodedLength(_ wire: [UInt8], at offset: Int) throws -> Int32 {
    wire[offset ..< offset + 4].reduce(Int32(0)) { $0 << 8 | Int32($1) }
}

/// Where an `Err` payload ends when it carries its message alone, as an older host writes it.
func errWithoutRemedy(_ payload: [UInt8]) throws -> Int {
    1 + 4 + Int(try decodedLength(payload, at: 1))
}

/// The name of the host's `PtyMessage` record `frame` is.
func messageName(of frame: Frame) -> String {
    switch frame {
    case .hello: "Hello"
    case .welcome: "Welcome"
    case .create: "Create"
    case .attach: "Attach"
    case .input: "Input"
    case .resize: "Resize"
    case .takeWrite: "TakeWrite"
    case .detach: "Detach"
    case .listSessions: "ListSessions"
    case .kill: "Kill"
    case .yield: "Yield"
    case .output: "Output"
    case .replayBegin: "ReplayBegin"
    case .replayEnd: "ReplayEnd"
    case .writerChanged: "WriterChanged"
    case .resized: "Resized"
    case .paused: "Paused"
    case .continued: "Continued"
    case .sessionEnded: "SessionEnded"
    case .sessionInfo: "SessionInfo"
    case .sessions: "Sessions"
    case .ok: "Ok"
    case .err: "Err"
    }
}
