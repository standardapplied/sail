import SailAPI
import SailKitTestSupport
import SailTerminalWire
import Testing

@Suite struct FrameCodecTests {
    @Test func everyFrameTheHostWritesDecodesAsItsTypeAndEncodesToTheSameBytes() throws {
        let frames = try Goldens.frames()
        #expect(frames.count == 26)
        for (name, wire) in frames {
            let frame = try FrameCodec.decode(payload: wire.dropFirst(4))
            #expect(messageName(of: frame) == name)
            #expect(try FrameCodec.encode(frame) == wire, "\(name)")
        }
    }

    @Test func theGoldenFramesCarryTheirFieldsInOrder() throws {
        let frames = try Dictionary(uniqueKeysWithValues: Goldens.frames().map { ($0.name, try decoded($0.wire)) })
        #expect(frames["Create"] == .create(SessionCreate(
            session: "lounge", command: ["bash", "-l"], workingDirectory: "/home/dev", project: "harbor",
            room: "harbor-design", size: TerminalSize(columns: 132, rows: 43), workspace: "dock"
        )))
        #expect(frames["Attach"] == .attach(session: "lounge", write: true))
        #expect(frames["Input"] == .input(sequence: 42, bytes: Array("ls -la\r".utf8)))
        #expect(frames["Output"] == .output(lastInputSequence: 42, bytes: Array("héllo ✅\u{1b}[0m\r\n".utf8)))
        #expect(frames["Resize"] == .resize(TerminalSize(columns: 100, rows: 30)))
        #expect(frames["ReplayBegin"] == .replayBegin(safe: true))
        #expect(frames["ListSessions"] == .listSessions(after: "", limit: 16))
        #expect(frames["Sessions"] == .sessions(SessionPage(sessions: [lounge, shell], next: "shell")))
        #expect(frames["SessionInfo"] == .sessionInfo(lounge))
        #expect(frames["IssueTicket"] == .issueTicket(
            action: .known(.open), target: "harbor", credential: "sail_uday", workspace: "dock"
        ))
        #expect(frames["Ticket"] == .ticket("tkt_harbor"))
        #expect(frames["TicketRefused"] == .ticketRefused(kind: .known(.notFound), remedy: [
            .words("Project 'harbor' is not in the catalog. Run '"),
            .command(run: .known(.sail), verb: "sync", args: []),
            .words("'."),
        ]))
        #expect(frames["Err"] == .refused(HostRefusal(
            message: "You do not hold the write token. Run sail session take lounge to take it.",
            remedy: [
                .words("You do not hold the write token. Run "),
                .command(run: .known(.sail), verb: "session", args: ["take", "lounge"]),
                .words(" to take it."),
            ]
        )))
    }

    @Test func aRefusalFromAHostThatSendsNoRemedyKeepsItsMessageAndEncodesAsItCame() throws {
        let err = try golden("Err")
        let message = 4 + Int(try decodedLength(err, at: 5))
        let bare = lengthPrefixed(Array(err[4 ..< 5 + message]))
        let frame = try decoded(bare)
        #expect(frame == .refused(HostRefusal(
            message: "You do not hold the write token. Run sail session take lounge to take it.",
            remedy: nil
        )))
        #expect(try FrameCodec.encode(frame) == bare)
    }

    @Test func aRunnerTheClientDoesNotKnowIsKeptAsTheHostSpelledItAndEncodesAsItCame() throws {
        let err = try golden("Err")
        let runner = try #require(range(of: "SAIL", in: err))
        var grown = err
        grown.replaceSubrange(runner, with: Array("TUGS".utf8))
        let frame = try decoded(grown)
        #expect(frame == .refused(HostRefusal(
            message: "You do not hold the write token. Run sail session take lounge to take it.",
            remedy: [
                .words("You do not hold the write token. Run "),
                .command(run: .unknown("TUGS"), verb: "session", args: ["take", "lounge"]),
                .words(" to take it."),
            ]
        )))
        #expect(try FrameCodec.encode(frame) == grown)
    }

    @Test func onlyTheByteOneReadsAsTrueAsTheHostReadsIt() throws {
        var replay = try golden("ReplayBegin")
        #expect(try decoded(replay) == .replayBegin(safe: true))
        replay[5] = 2
        #expect(try decoded(replay) == .replayBegin(safe: false))
        replay[5] = 0
        #expect(try decoded(replay) == .replayBegin(safe: false))
    }

    @Test func everyFrameCutShortIsTruncatedNeverAFrame() throws {
        for (name, wire) in try Goldens.frames() {
            let payload = Array(wire.dropFirst(4))
            let complete = name == "Err" ? try errWithoutRemedy(payload) : withoutWorkspace(name, payload)
            for end in 0 ..< payload.count where end != complete {
                #expect(throws: TerminalWireError.truncated, "\(name) cut at \(end)") {
                    try FrameCodec.decode(payload: payload[..<end])
                }
            }
        }
    }

    @Test func aCreateOrATicketFromAHostBeforeWorkspacesIsForTheProjectsContainer() throws {
        let create = Array(try golden("Create").dropFirst(4))
        let ticket = Array(try golden("IssueTicket").dropFirst(4))

        guard case let .create(bare) = try FrameCodec.decode(payload: create[..<withoutWorkspace("Create", create)]),
              case let .issueTicket(_, _, _, workspace) = try FrameCodec.decode(
                  payload: ticket[..<withoutWorkspace("IssueTicket", ticket)]
              )
        else {
            Issue.record("a frame without its workspace decodes as another frame")
            return
        }
        #expect(bare.workspace == "")
        #expect(workspace == "")
        let info = Array(try golden("SessionInfo").dropFirst(4))
        let page = Array(try golden("Sessions").dropFirst(4))
        #expect(try FrameCodec.decode(payload: info[..<withoutWorkspace("SessionInfo", info)])
            == .sessionInfo(SessionInfo(
                name: "lounge", instanceID: "inst-1", live: true, attached: 2, writerFDE: "uday",
                room: "harbor-design", command: ["claude", "--resume"]
            )))
        guard case let .sessions(older) = try FrameCodec.decode(payload: page[..<withoutWorkspace("Sessions", page)])
        else {
            Issue.record("a page without its places decodes as another frame")
            return
        }
        #expect(older.sessions.map(\.workspace) == ["", ""])
        #expect(older.next == "shell")
    }

    /// The wire rule: a newer peer appends after every field an older one writes, never inside a
    /// repeated element. So each frame as 0.69.0 wrote it (its own recorded golden) is the head of
    /// the frame as this release writes it, byte for byte, and 0.69.0's reader, which reads only
    /// that head, reads this release's frame as its own; and this release reads 0.69.0's frame as
    /// the same frame with the new fields empty.
    @Test(arguments: ["Create", "IssueTicket", "SessionInfo", "Sessions"])
    func aFrameAsTheLastReleaseWroteItIsTheHeadOfThisOnesAndReadsWithItsNewFieldsEmpty(name: String) throws {
        let released = try #require(try Goldens.frames("released/frames-0.69.0.json").first { $0.name == name })
        let current = try #require(try Goldens.frames().first { $0.name == name })
        let older = Array(released.wire.dropFirst(4))
        let newer = Array(current.wire.dropFirst(4))

        #expect(Array(newer.prefix(older.count)) == older, "a field of \(name) was written inside 0.69.0's")
        #expect(try FrameCodec.decode(payload: older) == withoutNewFields(try FrameCodec.decode(payload: newer)))
    }

    @Test func aPagePlacingOtherThanItsSessionsIsMalformed() throws {
        var page = Array(try golden("Sessions").dropFirst(4))
        let placed = withoutWorkspace("Sessions", page)
        page.replaceSubrange(placed ..< placed + 4, with: [0, 0, 0, 3])
        #expect(throws: TerminalWireError.malformed("a page places 3 sessions but holds 2")) {
            try FrameCodec.decode(payload: page)
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

    @Test func theLargestPayloadIsTheLargestTheHostSends() throws {
        #expect(FrameCodec.maxPayload == (try Goldens.maxPayload()))
    }

    @Test func aFrameLargerThanTheHostAcceptsIsRefusedBeforeItIsSent() throws {
        let largest = FrameCodec.maxPayload - 13
        #expect(try FrameCodec.encode(.input(sequence: 1, bytes: Array(repeating: 0, count: largest))).count
            == 4 + FrameCodec.maxPayload)
        #expect(throws: TerminalWireError.lengthOutOfRange(FrameCodec.maxPayload + 1)) {
            try FrameCodec.encode(.input(sequence: 1, bytes: Array(repeating: 0, count: largest + 1)))
        }
    }

    @Test func theHandshakeIsTheHostsMagicAndNamesWhichSideIsBehind() throws {
        #expect(Handshake.magic == (try Goldens.magic()))
        try Handshake.check(peer: Goldens.magic())
        #expect(throws: TerminalWireError.skew(.hostOlder)) { try Handshake.check(peer: Array("SAILPTY2".utf8)) }
        #expect(throws: TerminalWireError.skew(.hostOlder)) { try Handshake.check(peer: Array("SAILPTY1".utf8)) }
        #expect(throws: TerminalWireError.skew(.clientOlder)) { try Handshake.check(peer: Array("SAILPTY4".utf8)) }
        #expect(throws: TerminalWireError.skew(.clientOlder)) { try Handshake.check(peer: Array("SAIL".utf8)) }
        #expect(throws: TerminalWireError.skew(.clientOlder)) { try Handshake.check(peer: Goldens.magic() + [0x33]) }
    }
}

let lounge = SessionInfo(
    name: "lounge", instanceID: "inst-1", live: true, attached: 2, writerFDE: "uday",
    room: "harbor-design", command: ["claude", "--resume"], workspace: "dock", opener: "uday"
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

/// Where `name`'s golden frame ends without what a host before workspaces never sent: the
/// workspace `dock` of a create or ticket, `lounge`'s place in `dock` opened by `uday`, and a
/// page's places of `lounge` and of `shell`, which is in none.
func withoutWorkspace(_ name: String, _ payload: [UInt8]) -> Int {
    let lounge = field("dock") + field("uday")
    return switch name {
    case "Create", "IssueTicket": payload.count - field("dock")
    case "SessionInfo": payload.count - lounge
    case "Sessions": payload.count - 4 - lounge - field("") - field("")
    default: payload.count
    }
}

/// `frame` with every field 0.69.0 never wrote left empty.
func withoutNewFields(_ frame: Frame) -> Frame {
    func unplaced(_ info: SessionInfo) -> SessionInfo {
        var bare = info
        bare.workspace = ""
        bare.opener = ""
        return bare
    }
    switch frame {
    case let .create(create):
        var bare = create
        bare.workspace = ""
        return .create(bare)
    case let .issueTicket(action, target, credential, _):
        return .issueTicket(action: action, target: target, credential: credential, workspace: "")
    case let .sessionInfo(info):
        return .sessionInfo(unplaced(info))
    case let .sessions(page):
        return .sessions(SessionPage(sessions: page.sessions.map(unplaced), next: page.next))
    default:
        return frame
    }
}

/// The bytes a string field takes: its length and its UTF-8.
func field(_ text: String) -> Int {
    4 + text.utf8.count
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
    case .issueTicket: "IssueTicket"
    case .ticket: "Ticket"
    case .ticketRefused: "TicketRefused"
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
    case .accepted: "Ok"
    case .refused: "Err"
    }
}
