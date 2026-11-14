import Foundation
import SailAPI
@testable import SailTerminal
import SailTerminalWire
import SailVT
@testable import sail_term_probe
import Testing

/// The session's answers to a host that misbehaves, a connection that dies quietly and a person
/// who detaches mid-connect.
@Suite(.timeLimit(.minutes(1))) struct SessionFixTests {
    let create = SessionCreate(
        session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 80, rows: 24)
    )

    /// Ruling 1: a host that attaches and at once drops is a failed attempt: after the first drop,
    /// five reconnects that each attach and drop, waiting 0.5, 1, 2, 4 and 8 s before them, and the
    /// session parks, where it used to retry every 0.5 s forever.
    @Test func aHostThatAttachesAndDropsParksTheSessionAfterFiveTries() async throws {
        let naps = Naps()
        let (host, session, peer, watch) = try await attachedLocally(stableAfter: TerminalSession.stableAttachment, sleep: naps.sleep)
        peer.close()
        try await watch.until("lost the connection") { session.state.phase != .attached }
        var attaches = 1
        for _ in 2 ... 12 {
            guard case .reconnecting = session.state.phase else { break }
            guard let again = try? await host.accept() else { break }
            try await again.welcome()
            _ = try await again.acceptAttach()
            try await watch.attached()
            attaches += 1
            again.close()
            try await watch.until("lost the connection again") { session.state.phase != .attached }
        }
        try await watch.until("parked") {
            if case .parked = session.state.phase { return true }
            return false
        }
        #expect(attaches == 6)
        #expect(naps.durations == TerminalSession.retryWaits)
        session.close()
    }

    @Test func aRunnerThatAttachesAndClosesIsAskedSixTicketsThenParks() async throws {
        let host = try FakeHost(tcp: ())
        let desk = TicketDesk(address: host.address)
        let session = TerminalSession(open: create, door: .runner(desk), screen: try screen(), timeout: 5, sleep: Naps().sleep)
        let watch = Watch(session)
        session.start()
        for _ in 1 ... 6 {
            let peer = try await host.accept()
            try await peer.welcome()
            _ = try await peer.acceptAttach()
            peer.close()
        }
        try await watch.until("parked") {
            if case .parked = session.state.phase { return true }
            return false
        }
        #expect(desk.requests.count == 6)
        session.close()
    }

    @Test func aConnectionThatStayedAttachedLongEnoughStartsTheCountAgain() async throws {
        let naps = Naps()
        let (host, session, peer, watch) = try await attachedLocally(stableAfter: .zero, sleep: naps.sleep)
        peer.close()
        let again = try await host.accept()
        try await again.welcome()
        _ = try await again.acceptAttach()
        try await watch.attached()
        #expect(naps.durations == [TerminalSession.retryWaits[0]])
        session.close()
    }

    /// Ruling 1: a protocol violation is not a drop; the session ends naming it.
    @Test(arguments: [
        (Frame.sessions(SessionPage(sessions: [], next: "")), "it sent Sessions to an attached client"),
        (Frame.resized(TerminalSize(columns: 5000, rows: 24)), "it resized the session to 5000x24, beyond 1000x500"),
    ])
    func aFrameAnAttachedClientCannotTakeEndsTheSessionNamingIt(_ frame: Frame, _ reason: String) async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(frame)
        try await watch.phase(.ended(.protocolViolation(reason)))
        #expect(SessionEnd.protocolViolation(reason).description == "the terminal host broke the protocol: \(reason)")
        _ = (host, session)
    }

    @Test func aLengthBeyondTheFrameLimitEndsTheSession() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(raw: [0x7F, 0xFF, 0xFF, 0xFF])
        try await watch.until("ended") {
            if case .ended(.protocolViolation) = session.state.phase { return true }
            return false
        }
        _ = host
    }

    /// Ruling 3: on wake the attached connection is made to prove itself with the size it has.
    @Test func reconnectNowWhileAttachedSendsTheCurrentSizeAsAProbe() async throws {
        let (host, session, peer, _) = try await attachedLocally(columns: 90, rows: 20)
        session.reconnectNow()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 90, rows: 20)))
        session.close()
        _ = host
    }

    @Test func aTCPConnectionIsGivenUpWhenItsDataStaysUnacknowledged() async throws {
        let host = try FakeHost(tcp: ())
        let socket = try Socket.connect(to: host.address, timeout: 5)
        #if os(Linux)
        #expect(socket.retransmitDrop == Int32(Socket.deadAfter * 1000))
        #else
        #expect(socket.retransmitDrop == Int32(Socket.deadAfter))
        #endif
        #expect(Socket.quietDeadAfter == 45)
    }

    /// Ruling 3: keys written into a connection that died before the host applied them are counted,
    /// as an estimate apart from the exact dropped count, by the next attach's replay; keys it
    /// applied are not.
    @Test(arguments: [(Int64(0), 2), (Int64(1), 1), (Int64(2), 0)])
    func keysTheHostNeverAppliedAreCountedDroppedAtTheNextAttach(_ applied: Int64, _ dropped: Int) async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        session.send(KeyInput(key: .a, text: "a"))
        session.send(KeyInput(key: .b, text: "b"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("a".utf8)))
        #expect(try await peer.read() == .input(sequence: 2, bytes: Array("b".utf8)))
        peer.close()
        let again = try await host.accept()
        try await again.welcome()
        #expect(try await again.read() == .attach(session: "work", write: true))
        try again.send([.accepted, .replayBegin(safe: true), output("history", applied: applied), .replayEnd])
        try await watch.text("history")
        #expect(session.state.possiblyLostInputs == dropped)
        #expect(session.state.droppedInputs == 0)
        session.close()
    }

    /// Ruling 5 (the reviewer's s10): an attempt still being admitted when the person detaches is
    /// shut, and never sends an attach after the next one has.
    @Test func anAttemptOvertakenByADetachAndAnAttachSendsNothing() async throws {
        let host = try FakeHost()
        let session = TerminalSession(open: create, door: .local(host.address), screen: try screen(), timeout: 5, sleep: Naps().sleep)
        let watch = Watch(session)
        session.start()
        let stale = try await host.accept()
        _ = try await stale.hello()
        session.detach()
        try await watch.phase(.detached)
        session.attach()
        let live = try await host.accept()
        try await live.welcome()
        #expect(try await live.read() == .create(create))
        try live.send(.accepted)
        #expect(try await live.read() == .attach(session: "work", write: true))
        try live.send([.accepted, .writerChanged(fde: "ada")])
        try await watch.attached()
        try? stale.send(.welcome(hostBootID: "boot-1"))
        await #expect(throws: (any Error).self) { try await stale.read() }
        #expect(session.state.phase == .attached)
        session.close()
    }

    /// MINOR 3: a close during the handshake (here, waiting for the host's welcome) shuts the
    /// connection at once, not after the handshake's timeout.
    @Test func aCloseDuringTheHandshakeShutsTheConnectionAtOnce() async throws {
        let host = try FakeHost()
        let session = TerminalSession(open: create, door: .local(host.address), screen: try screen(), timeout: 30, sleep: Naps().sleep)
        session.start()
        let peer = try await host.accept()
        _ = try await peer.hello()
        session.close()
        let started = ContinuousClock.now
        await #expect(throws: (any Error).self) { try await peer.read() }
        #expect(ContinuousClock.now - started < .seconds(FakeHost.deadline - 1))
    }

    /// MINOR 4: a detach whose connection dies before the host's answer is still a detach.
    @Test func aDetachWhoseConnectionDiesBeforeTheAnswerIsStillADetach() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        session.detach()
        #expect(try await peer.read() == .detach)
        peer.close()
        try await watch.phase(.detached)
        #expect(!watch.seen.contains { if case .phase(.reconnecting) = $0 { true } else { false } })
        _ = host
    }

    /// MINOR 6: a read-only attach answers no query, and a writer refused the token stops
    /// answering until the host names a writer again.
    @Test func aConnectionWithoutTheWriteTokenAnswersNoQuery() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(.refused(HostRefusal(message: TerminalSession.notWriter, remedy: nil)))
        try await watch.until("told the refusal") { watch.seen.contains { if case .refused = $0 { true } else { false } } }
        try peer.send(output("\u{1b}[6nquiet"))
        try await watch.text("quiet")
        session.send(KeyInput(key: .k, text: "k"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("k".utf8)))
        try peer.send([.writerChanged(fde: "ada"), output("\u{1b}[6n")])
        guard case let .input(_, bytes) = try await peer.read() else { return }
        #expect(bytes.starts(with: Array("\u{1b}[".utf8)) && bytes.last == UInt8(ascii: "R"))
        session.close()
        _ = host
    }

    @Test func aReadOnlyAttachAnswersNoQuery() async throws {
        let host = try FakeHost()
        let session = TerminalSession(attach: "theirs", write: false, door: .local(host.address), screen: try screen(), timeout: 5, sleep: Naps().sleep)
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        #expect(try await peer.read() == .attach(session: "theirs", write: false))
        try peer.send([.accepted, .writerChanged(fde: "eng"), output("\u{1b}[6nwatching")])
        try await watch.text("watching")
        session.send(KeyInput(key: .q, text: "q"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("q".utf8)))
        session.close()
    }

    /// MINOR 7: a client's input numbers start above the session's, so its marks are its own.
    @Test func inputIsNumberedAboveWhatTheSessionHadApplied() async throws {
        let host = try FakeHost()
        let session = TerminalSession(attach: "old", write: true, door: .local(host.address), screen: try screen(), timeout: 5, sleep: Naps().sleep)
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.read()
        try peer.send([.accepted, .replayBegin(safe: true), output("before", applied: 100), .replayEnd, .writerChanged(fde: "ada")])
        try await watch.text("before")
        #expect(session.latencyReport.echoed.count == 0)
        session.send(KeyInput(key: .x, text: "x"))
        #expect(try await peer.read() == .input(sequence: 101, bytes: Array("x".utf8)))
        session.close()
    }

    /// MINOR 1: a runner's door cannot kill; nothing is sent.
    @Test func aKillThroughARunnersDoorIsRefusedBeforeAnythingIsSent() throws {
        let session = TerminalSession(attach: "s", write: true, door: .runner(TicketDesk(address: .tcp(host: "127.0.0.1", port: 9))), screen: try screen())
        #expect(throws: TerminalError.self) { try session.kill() }
    }

    /// Fix 2: a kill with no connection attached says so rather than doing nothing.
    @Test func aKillWhileNotAttachedIsRefused() throws {
        let host = try FakeHost()
        let session = TerminalSession(attach: "s", write: true, door: .local(host.address), screen: try screen())
        #expect(throws: TerminalError.unsupported("the session is not attached, so there is no connection to ask the host to end it on")) {
            try session.kill()
        }
    }

    /// Fix 2 BLOCKER (the reviewer's n1): an applied number no client could have sent ends the
    /// session; the next key no longer overflows.
    @Test(arguments: [Int64.max, TerminalSession.largestApplied + 1, -2, Int64.min])
    func anAppliedNumberNoClientCouldSendEndsTheSession(_ applied: Int64) async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(output("x", applied: applied))
        try await watch.until("ended") {
            if case .ended(.protocolViolation) = session.state.phase { return true }
            return false
        }
        session.send(KeyInput(key: .a, text: "a"))
        #expect(session.state.droppedInputs == 1)
        _ = host
    }

    @Test func theLargestAppliedNumberStillLeavesRoomToNumberInput() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(output("edge", applied: TerminalSession.largestApplied))
        try await watch.text("edge")
        session.send(KeyInput(key: .a, text: "a"))
        #expect(try await peer.read() == .input(sequence: TerminalSession.largestApplied + 1, bytes: Array("a".utf8)))
        session.close()
        _ = host
    }

    /// Fix 2 MAJOR (the reviewer's n5): after this client resized the pty, the wake probe sends that
    /// size, never the size the session attached at.
    @Test func theWakeProbeSendsThePtySizeThisClientLastSent() async throws {
        let timeline = Timeline()
        let (host, session, peer, watch) = try await attachedLocally(columns: 80, rows: 24, sleep: timeline.sleep)
        try session.resize(GridSize(columns: 100, rows: 30))
        try await timeline.until(held: [TerminalSession.resizeHold])
        timeline.passAll()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 100, rows: 30)))
        session.reconnectNow()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 100, rows: 30)))
        try peer.send(.resized(TerminalSize(columns: 120, rows: 40)))
        try await watch.until("took the host's size") { try session.screen.frame().size == GridSize(columns: 120, rows: 40) }
        session.reconnectNow()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 120, rows: 40)))
        session.close()
        _ = host
    }

    /// Fix 3 (the reviewer's n6): a reader's resize is the view's alone, since the host ignores it,
    /// so the wake probe sends the size the pty has and resizes nothing.
    @Test func aReadersWakeProbeSendsThePtysSizeNotItsView() async throws {
        let timeline = Timeline()
        let (host, session, peer) = try await attachedToRead(sleep: timeline.sleep)
        try session.resize(GridSize(columns: 100, rows: 30))
        try await timeline.until(held: [TerminalSession.resizeHold])
        timeline.passAll()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 100, rows: 30)))
        session.reconnectNow()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 80, rows: 24)))
        session.close()
        _ = host
    }

    /// Fix 3: once the host grants this client's `TakeWrite`, the view's size goes to it once, so the
    /// new writer's pty takes its size, and the wake probe then sends that size.
    @Test func takingTheWriteTokenSendsTheViewsSizeOnce() async throws {
        let timeline = Timeline()
        let (host, session, peer) = try await attachedToRead(sleep: timeline.sleep)
        try session.resize(GridSize(columns: 100, rows: 30))
        try await timeline.until(held: [TerminalSession.resizeHold])
        timeline.passAll()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 100, rows: 30)))
        session.takeWrite()
        #expect(try await peer.read() == .takeWrite)
        try peer.send(.writerChanged(fde: "ada"))
        #expect(try await peer.read() == .resize(TerminalSize(columns: 100, rows: 30)))
        try peer.send(.writerChanged(fde: "eng"))
        session.send(KeyInput(key: .q, text: "q"))
        guard case .input = try await peer.read() else { throw HostError("a second size went to the host") }
        session.reconnectNow()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 100, rows: 30)))
        session.close()
        _ = host
    }

    /// A session attached read-only at 80x24 to a session another FDE writes.
    private func attachedToRead(
        sleep: @escaping @Sendable (Duration) async throws -> Void
    ) async throws -> (FakeHost, TerminalSession, FakeHost.Peer) {
        let host = try FakeHost()
        let session = TerminalSession(
            attach: "theirs", write: false, door: .local(host.address), screen: try screen(columns: 80, rows: 24), timeout: 5,
            stableAfter: .zero, sleep: sleep
        )
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        #expect(try await peer.read() == .attach(session: "theirs", write: false))
        try peer.send([.accepted, .resized(TerminalSize(columns: 80, rows: 24)), .replayBegin(safe: true), .replayEnd, .writerChanged(fde: "eng")])
        try await watch.until("took the prologue") { watch.seen.contains(.writerChanged("eng")) }
        return (host, session, peer)
    }

    /// Fix 2: a render hold left open when the output stops ticks the screen at its limit, so a
    /// renderer that draws only on a tick shows what the program left.
    @Test func aHoldLeftOpenTicksTheScreenAtItsLimit() async throws {
        let timeline = Timeline()
        let hands = Hands()
        let host = try FakeHost()
        let create = SessionCreate(session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 20, rows: 3))
        let screen = try Screen(size: GridSize(columns: 20, rows: 3), scrollbackBytes: 0, palette: .dark, now: { hands.now })
        let session = TerminalSession(open: create, door: .local(host.address), screen: screen, timeout: 5, stableAfter: .zero, sleep: timeline.sleep)
        var ticks = session.screenChanges.makeAsyncIterator()
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.acceptAttach(columns: 20, rows: 3)
        try peer.send(output("old\u{1b}[?2026h\r\u{1b}[2Kstuck"))
        while try screen.text() != "old" {
            _ = await ticks.next()
        }
        try await timeline.until(held: [Screen.holdLimit])
        hands.advance(Screen.holdLimit)
        timeline.passAll()
        _ = await ticks.next()
        #expect(try Screen.text(of: [screen.frame().rows[0]]) == "stuck")
        session.close()
    }

    /// MINOR 2: a resize's in-band size report goes out at once.
    @Test func aResizeAnswersAnInBandSizeReportAtOnce() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(output("\u{1b}[?2048hready"))
        try await watch.text("ready")
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("\u{1b}[48;24;80;0;0t".utf8)))
        try session.resize(GridSize(columns: 100, rows: 30))
        #expect(try await peer.read() == .input(sequence: 2, bytes: Array("\u{1b}[48;30;100;0;0t".utf8)))
        #expect(throws: ScreenError.self) { try session.resize(GridSize(columns: 1001, rows: 30)) }
        session.close()
        _ = host
    }

    /// A new cell size (the font changed) answers a program's in-band size report at once.
    @Test func aCellSizeChangeAnswersAnInBandSizeReportAtOnce() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(output("\u{1b}[?2048hready"))
        try await watch.text("ready")
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("\u{1b}[48;24;80;0;0t".utf8)))
        try session.setCellPixels(width: 18, height: 40)
        #expect(try await peer.read() == .input(sequence: 2, bytes: Array("\u{1b}[48;24;80;960;1440t".utf8)))
        session.close()
        _ = host
    }

    /// MINOR 9: whether a replay starts on a clean boundary is told.
    @Test func aReplaysSafetyIsTold() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send([.replayBegin(safe: false), output("x"), .replayEnd])
        try await watch.until("told the replay") { watch.seen.contains(.replay(safe: false)) }
        session.close()
        _ = host
    }
}

/// The local host's listing, page by page.
@Suite(.timeLimit(.minutes(1))) struct SessionListingTests {
    static func info(_ name: String) -> SessionInfo {
        SessionInfo(name: name, instanceID: "i-\(name)", live: true, attached: 0, writerFDE: "", room: "", command: [])
    }

    @Test func everyPageIsReadInOrder() async throws {
        let host = try FakeHost()
        async let listed = SessionListing.sessions(at: host.address, timeout: 5)
        let peer = try await host.accept()
        #expect(try await peer.welcome() == "")
        #expect(try await peer.read() == .listSessions(after: "", limit: SessionListing.pageSize))
        try peer.send(.sessions(SessionPage(sessions: [Self.info("a"), Self.info("b")], next: "b")))
        #expect(try await peer.read() == .listSessions(after: "b", limit: SessionListing.pageSize))
        try peer.send(.sessions(SessionPage(sessions: [Self.info("c")], next: "")))
        #expect(try await listed.map(\.name) == ["a", "b", "c"])
    }

    @Test func aCursorThatDoesNotMoveForwardIsAProtocolViolation() async throws {
        let host = try FakeHost()
        let listed = Task { try await SessionListing.sessions(at: host.address, timeout: 5) }
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.read()
        try peer.send(.sessions(SessionPage(sessions: [Self.info("a")], next: "a")))
        _ = try await peer.read()
        try peer.send(.sessions(SessionPage(sessions: [Self.info("a")], next: "a")))
        await #expect(throws: TerminalError.protocolViolation("its listing went back to 'a' after 'a'")) { try await listed.value }
    }

    @Test func aTimeoutOfLessThanOneSecondIsRefusedBeforeConnecting() async throws {
        let host = try FakeHost()
        for timeout in [0, -1, Int.min] {
            await #expect(throws: TerminalError.unsupported("a listing waits 1 s or more for each answer, not \(timeout) s")) {
                try await SessionListing.sessions(at: host.address, timeout: timeout)
            }
        }
    }

    @Test func aRefusalIsThrownInTheHostsWords() async throws {
        let host = try FakeHost()
        let listed = Task { try await SessionListing.sessions(at: host.address, timeout: 5) }
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.read()
        let refusal = HostRefusal(message: "Listing is not allowed here.", remedy: nil)
        try peer.send(.refused(refusal))
        await #expect(throws: TerminalError.refused(refusal)) { try await listed.value }
    }

    @Test func anAnswerThatIsNotAPageIsAProtocolViolation() async throws {
        let host = try FakeHost()
        let listed = Task { try await SessionListing.sessions(at: host.address, timeout: 5) }
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.read()
        try peer.send(.accepted)
        await #expect(throws: TerminalError.protocolViolation("it answered ListSessions with Accepted")) { try await listed.value }
    }

    @Test func aListingPastTheLargestIsAProtocolViolation() async throws {
        let host = try FakeHost()
        let listed = Task { try await SessionListing.sessions(at: host.address, timeout: 5) }
        let peer = try await host.accept()
        try await peer.welcome()
        let pages = SessionListing.largest / Int(SessionListing.pageSize) + 1
        for page in 0 ..< pages {
            _ = try await peer.read()
            let names = (0 ..< Int(SessionListing.pageSize)).map { String(format: "s%03d-%03d", page, $0) }
            try peer.send(.sessions(SessionPage(sessions: names.map(Self.info), next: names.last ?? "")))
        }
        await #expect(throws: TerminalError.protocolViolation("it listed more than \(SessionListing.largest) sessions")) { try await listed.value }
    }
}
