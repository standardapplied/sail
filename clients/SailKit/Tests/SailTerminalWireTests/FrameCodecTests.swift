import SailAPI
import SailKitTestSupport
import SailTerminalWire
import Testing

@Suite struct FrameCodecTests {
    @Test func everyFrameTheHostWritesDecodesAsItsTypeAndEncodesToTheSameBytes() throws {
        let frames = try Goldens.frames()
        #expect(frames.count == 52)
        for (name, wire) in frames {
            let frame = try FrameCodec.decode(payload: wire.dropFirst(4))
            #expect(messageName(of: frame) == name)
            #expect(try FrameCodec.encode(frame) == wire, "\(name)")
        }
    }

    @Test func theGoldenFramesCarryTheirFieldsInOrder() throws {
        let frames = try Dictionary(uniqueKeysWithValues: Goldens.frames().map { ($0.name, try decoded($0.wire)) })
        #expect(frames["Create"] == .create(SessionCreate(
            session: "lounge", command: [], workingDirectory: "/home/dev", project: "harbor",
            room: "harbor-design", size: TerminalSize(columns: 132, rows: 43), workspace: "dock",
            agent: SessionAgent(harness: "codex", mode: "full-access", inside: true)
        )))
        #expect(frames["Attach"] == .attach(session: "lounge", write: true, client: "pane-7f", takes: false))
        #expect(frames["WriterChanged"] == .writerChanged(fde: "uday", yours: true))
        #expect(frames["Yield"] == .yield(session: "lounge", reason: "a dispatch took the repos", record: "A dispatch took the repos."))
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
        let chunk = (0 ..< 64 * 1024).map { UInt8(truncatingIfNeeded: $0) }
        #expect(frames["PutBegin"] == .putBegin(id: 7, name: "", size: 64 << 20))
        #expect(frames["PutChunk"] == .putChunk(id: 7, offset: .max, bytes: chunk))
        if case let .putEnd(id, sha256) = frames["PutEnd"] {
            #expect(id == 7 && sha256.count == 32)
        } else {
            Issue.record("PutEnd is \(String(describing: frames["PutEnd"]))")
        }
        #expect(frames["PutCancel"] == .putCancel(id: .max))
        #expect(frames["PutAck"] == .putAck(id: 7, received: 65536))
        #expect(frames["PutStored"] == .putStored(id: 7, path: "/var/lib/sail-paste/inst-1/1-shot.png"))
        #expect(frames["PutRefused"] == .putRefused(id: -1, kind: .known(.tooLarge), limit: 64 << 20))
        #expect(frames["TicketRefused"] == .ticketRefused(kind: .known(.notFound), reason: "Project 'harbor' is not in the catalog.", remedy: [
            .words("Run '"),
            .command(run: .known(.sail), verb: "sync", args: []),
            .words("'."),
        ]))
        #expect(frames["YieldWorkspace"] == .yieldWorkspace(
            project: "harbor", workspace: "dock",
            reason: "Workspace dock of harbor is being stopped by ana, which ends this terminal.",
            record: "Workspace dock of harbor was stopped by ana."
        ))
        #expect(frames["FilesList"] == .filesList(id: 1, path: "/home/dev/workspace"))
        #expect(frames["FilesStat"] == .filesStat(id: 2, path: "/home/dev/workspace/App.java"))
        #expect(frames["FilesRead"] == .filesRead(id: 3, path: "/home/dev/workspace/App.java", offset: 65536, length: 262_144))
        #expect(frames["FilesCancel"] == .filesCancel(id: 3))
        #expect(frames["FilesWatch"] == .filesWatch([
            WatchedPath(path: "/home/dev/workspace", version: "51:9:4096:1.5:1.5"),
            WatchedPath(path: "/home/dev/workspace/gone.md", version: ""),
        ]))
        #expect(frames["FilesListing"] == .filesListing(FileListing(
            id: 1, version: "51:9:4096:1795210162.152082747:1795210162.152082747",
            entries: [
                FileEntry(name: "App.java", kind: .known(.file), target: .known(.none), size: 12, mtimeNanos: 1_795_210_162_152_082_747, mode: 0o644),
                FileEntry(name: "src", kind: .known(.link), target: .known(.folder), size: 3, mtimeNanos: -1, mode: 0o777),
                FileEntry(name: FileName([0x62, 0x61, 0x64, 0xFF, 0x6E]), kind: .known(.file), target: .known(.none), size: 4, mtimeNanos: 7, mode: 0o600),
            ],
            more: false, truncated: true
        )))
        #expect(frames["FilesEntry"] == .filesEntry(
            id: 2,
            entry: FileEntry(name: "App.java", kind: .known(.file), target: .known(.none), size: 12, mtimeNanos: 7, mode: 0o600),
            version: "51:10:12:7.0:7.0"
        ))
        #expect(frames["FilesData"] == .filesData(
            id: 3, version: "51:10:12:7.0:7.0", bytes: Array("class App {}".utf8), eof: true, whole: true,
            digest: "42f26956ba1a845d48ac0db6270beddc5d864212ef56efe8a7964871297b9da3"
        ))
        #expect(frames["FilesChanged"] == .filesChanged(paths: ["/home/dev/workspace", "/home/dev/workspace/App.java"]))
        #expect(frames["FilesTick"] == .filesTick)
        #expect(frames["FilesRefused"] == .filesRefused(id: 4, kind: .known(.loop), message: "/home/dev/workspace/self leads back to itself."))
        #expect(frames["FilesWrite"] == .filesWrite(id: 5, path: "/home/dev/workspace/App.java", base: "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", seen: "51:10:12:7.0:7.0", size: 13))
        #expect(frames["FilesCreate"] == .filesCreate(id: 6, path: "/home/dev/workspace/notes", folder: true))
        #expect(frames["FilesMove"] == .filesMove(id: 7, from: "/home/dev/workspace/a.md", to: "/home/dev/workspace/b.md"))
        #expect(frames["FilesRemove"] == .filesRemove(id: 8, path: "/home/dev/workspace/old"))
        #expect(frames["FilesWritten"] == .filesWritten(id: 5, digest: "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447", version: "51:11:13:8.0:8.0"))
        #expect(frames["FilesConflict"] == .filesConflict(id: 5, digest: "absent"))
        #expect(frames["FilesDone"] == .filesDone(id: 6))
        #expect(frames["Err"] == .refused(HostRefusal(
            message: "You do not hold the write token.",
            remedy: [
                .words("Run "),
                .command(run: .known(.sail), verb: "session", args: ["take", "lounge"]),
                .words(" to take it."),
            ],
            code: "login_not_stored"
        )))
    }

    /// A name that is not UTF-8 is kept as its bytes, so it addresses its own file, and is shown with
    /// the replacement character; a request for it sends those bytes back.
    @Test func aNameThatIsNotUTF8IsItsBytesAndShownReplaced() throws {
        guard case let .filesListing(listing) = try decoded(try golden("FilesListing")) else {
            Issue.record("no listing")
            return
        }
        let bad = try #require(listing.entries.last?.name)
        #expect(bad.bytes == [0x62, 0x61, 0x64, 0xFF, 0x6E])
        #expect(bad.shown == "bad\u{FFFD}n")
        let asked = try FrameCodec.encode(.filesStat(id: 9, path: bad))
        #expect(Array(asked.suffix(5)) == [0x62, 0x61, 0x64, 0xFF, 0x6E], "the bytes, not the replacement")
        #expect(try decoded(asked) == .filesStat(id: 9, path: bad))
        #expect(FileName([0x62, 0xFF]) != FileName([0x62, 0xFE]), "two bad names stay two")
    }

    @Test func theFilesWordsAndTheTicketWordsAreTheHostsInItsOrder() throws {
        #expect(FileKind.allCases.map(\.rawValue) == (try Goldens.vocabulary("file_kinds")))
        #expect(FileRefusal.allCases.map(\.rawValue) == (try Goldens.vocabulary("file_refusals")))
        #expect(TicketAction.allCases.map(\.rawValue) == (try Goldens.vocabulary("ticket_actions")))
        #expect(TicketRefusal.allCases.map(\.rawValue) == (try Goldens.vocabulary("ticket_refusals")))
    }

    @Test func aRefusalWithoutItsRemedyIsTruncatedNotReadAsWords() throws {
        let err = try golden("Err")
        let message = 4 + Int(try decodedLength(err, at: 5))
        let bare = lengthPrefixed(Array(err[4 ..< 5 + message]))
        #expect(throws: TerminalWireError.truncated) { try decoded(bare) }
    }

    @Test func aRunnerTheClientDoesNotKnowIsKeptAsTheHostSpelledItAndEncodesAsItCame() throws {
        let err = try golden("Err")
        let runner = try #require(range(of: "SAIL", in: err))
        var grown = err
        grown.replaceSubrange(runner, with: Array("TUGS".utf8))
        let frame = try decoded(grown)
        #expect(frame == .refused(HostRefusal(
            message: "You do not hold the write token.",
            remedy: [
                .words("Run "),
                .command(run: .unknown("TUGS"), verb: "session", args: ["take", "lounge"]),
                .words(" to take it."),
            ],
            code: "login_not_stored"
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
            let ends = payload.count <= 1024
                ? Array(0 ..< payload.count)
                : Array(0 ..< 512) + Array(stride(from: 512, to: payload.count - 512, by: 4093)) + Array(payload.count - 512 ..< payload.count)
            for end in ends {
                #expect(throws: TerminalWireError.truncated, "\(name) cut at \(end)") {
                    try FrameCodec.decode(payload: payload[..<end])
                }
            }
        }
    }

    @Test func aPagePlacingOtherThanItsSessionsIsMalformed() throws {
        var page = Array(try golden("Sessions").dropFirst(4))
        let loungePlace = field("harbor") + field("dock") + field("uday") + 8 + field("")
            + field("claude-code") + field("bypassPermissions") + 1
        let shellPlace = 3 * field("") + 8 + field("") + 2 * field("") + 1
        let places = page.count - 4 - loungePlace - shellPlace
        page.replaceSubrange(places ..< places + 4, with: [0, 0, 0, 3])
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

    @Test func theHandshakeIsTheHostsMagicAndAnyOtherIsAnotherProtocol() throws {
        #expect(Handshake.magic == (try Goldens.magic()))
        try Handshake.check(peer: Goldens.magic())
        for other in ["SAILPTY6", "SAILPTY8", "SAIL"] {
            #expect(throws: TerminalWireError.otherProtocol) { try Handshake.check(peer: Array(other.utf8)) }
        }
        #expect(throws: TerminalWireError.otherProtocol) { try Handshake.check(peer: Goldens.magic() + [0x33]) }
    }
}

let lounge = SessionInfo(
    name: "lounge", instanceID: "inst-1", live: true, attached: 2, writerFDE: "uday",
    room: "harbor-design", command: ["claude", "--permission-mode", "bypassPermissions"], project: "harbor",
    workspace: "dock", opener: "uday", createdAtMillis: 1_763_460_000_000,
    agent: SessionAgent(harness: "claude-code", mode: "bypassPermissions")
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
    case .putBegin: "PutBegin"
    case .putChunk: "PutChunk"
    case .putEnd: "PutEnd"
    case .putCancel: "PutCancel"
    case .putAck: "PutAck"
    case .putStored: "PutStored"
    case .putRefused: "PutRefused"
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
    case .yieldWorkspace: "YieldWorkspace"
    case .filesList: "FilesList"
    case .filesStat: "FilesStat"
    case .filesRead: "FilesRead"
    case .filesCancel: "FilesCancel"
    case .filesWatch: "FilesWatch"
    case .filesListing: "FilesListing"
    case .filesEntry: "FilesEntry"
    case .filesData: "FilesData"
    case .filesChanged: "FilesChanged"
    case .filesTick: "FilesTick"
    case .filesRefused: "FilesRefused"
    case .filesWrite: "FilesWrite"
    case .filesCreate: "FilesCreate"
    case .filesMove: "FilesMove"
    case .filesRemove: "FilesRemove"
    case .filesWritten: "FilesWritten"
    case .filesConflict: "FilesConflict"
    case .filesDone: "FilesDone"
    }
}

/// The bytes a string field takes: its length and its UTF-8.
func field(_ text: String) -> Int {
    4 + text.utf8.count
}
