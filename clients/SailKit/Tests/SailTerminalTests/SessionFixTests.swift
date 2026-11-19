import Foundation
import SailAPI
import SailKitTestSupport
@testable import SailTerminal
import SailTerminalWire
import SailVT
import Synchronization
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

    /// A runner's door goes on at the 8 s cap until a minute of waits is spent: the open, then 11
    /// reconnects.
    @Test func aRunnerThatAttachesAndClosesIsAskedTwelveTicketsThenParks() async throws {
        let host = try FakeHost(tcp: ())
        let desk = TicketDesk(address: host.address)
        let session = TerminalSession(open: create, door: .runner(desk), screen: try screen(), timeout: 5, sleep: Naps().sleep)
        let watch = Watch(session)
        session.start()
        for _ in 1 ... 12 {
            let peer = try await host.accept()
            try await peer.welcome()
            _ = try await peer.acceptAttach()
            peer.close()
        }
        try await watch.until("parked") {
            if case .parked = session.state.phase { return true }
            return false
        }
        #expect(desk.requests.count == 12)
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
        #expect(try await again.read().unnamed == .attach(session: "work", write: true, takes: false), "a reconnect never takes the keyboard from another live client")
        try again.send([.accepted, .replayBegin(safe: true), output("history", applied: applied), .replayEnd])
        try await watch.text("history")
        #expect(session.state.possiblyLostInputs == dropped)
        #expect(session.state.droppedInputs == 0)
        session.close()
    }

    /// The reader can take the host's word that an input was applied before the writer has noted
    /// the input sent: that input is confirmed, and a later replay with no output counts it kept.
    @Test func anInputNamedAppliedBeforeItIsNotedSentIsNeverCountedLost() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send([output("ready", applied: 0), output("ahead", applied: 1)])
        try await watch.text("ahead")
        session.send(KeyInput(key: .a, text: "a"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("a".utf8)))
        peer.close()
        let again = try await host.accept()
        try await again.welcome()
        #expect(try await again.read().unnamed == .attach(session: "work", write: true, takes: false), "a reconnect never takes the keyboard from another live client")
        let replays = session.replays
        try again.send([.accepted, .replayBegin(safe: true), .replayEnd])
        try await watch.until("finished the replay") { session.replays > replays }
        #expect(session.state.possiblyLostInputs == 0)
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
        #expect(try await live.read().unnamed == .attach(session: "work", write: true))
        try live.send([.accepted, .writerChanged(fde: "ada", yours: true)])
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
        try peer.send(.refused(HostRefusal(message: TerminalSession.notWriter, remedy: [.words(TerminalSession.notWriter)])))
        try await watch.until("told the refusal") { watch.seen.contains { if case .refused = $0 { true } else { false } } }
        try peer.send(output("\u{1b}[6nquiet"))
        try await watch.text("quiet")
        session.send(KeyInput(key: .k, text: "k"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("k".utf8)))
        try peer.send([.writerChanged(fde: "ada", yours: true), output("\u{1b}[6n")])
        guard case let .input(_, bytes) = try await peer.read() else { return }
        #expect(bytes.starts(with: Array("\u{1b}[".utf8)) && bytes.last == UInt8(ascii: "R"))
        session.close()
        _ = host
    }

    /// A program's clipboard write reaches the app only from a session this client writes to: a
    /// reader is watching someone else's program.
    @Test func aClipboardWriteIsToldOnlyWhileThisClientHoldsTheWriteToken() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(output("\u{1b}]52;c;aGVsbG8=\u{07}mine"))
        try await watch.until("told the write") { watch.seen.contains(.screen(.clipboardWrite("hello"))) }
        try peer.send(.refused(HostRefusal(message: TerminalSession.notWriter, remedy: [.words(TerminalSession.notWriter)])))
        try await watch.until("told the refusal") { watch.seen.contains { if case .refused = $0 { true } else { false } } }
        try peer.send(output("\u{1b}]52;c;dGhlaXJz\u{07}theirs\u{07}"))
        try await watch.until("told the bell after it") { watch.seen.contains(.screen(.bell)) }
        #expect(!watch.seen.contains(.screen(.clipboardWrite("theirs"))), "a reader's session told the clipboard write")
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
        #expect(try await peer.read().unnamed == .attach(session: "theirs", write: false))
        try peer.send([.accepted, .writerChanged(fde: "eng", yours: false), output("\u{1b}[6nwatching")])
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
        try peer.send([.accepted, .replayBegin(safe: true), output("before", applied: 100), .replayEnd, .writerChanged(fde: "ada", yours: true)])
        try await watch.text("before")
        #expect(session.latencyReport.echoed.count == 0)
        session.send(KeyInput(key: .x, text: "x"))
        #expect(try await peer.read() == .input(sequence: 101, bytes: Array("x".utf8)))
        session.close()
    }

    /// An attached session on this Mac is ended as any other: a Kill on a connection of its own,
    /// the host's answer awaited, never a Kill written to the attached connection and forgotten.
    @Test func anAttachedSessionsEndIsAKillWhoseAnswerIsAwaited() async throws {
        let (host, session, attached, _) = try await attachedLocally()
        let ended = Task { await session.end(within: .seconds(10)) }
        let killer = try await host.accept()
        try await killer.welcome()
        #expect(try await killer.read() == .kill(session: "work"))
        try killer.send(.accepted)
        #expect(await ended.value == .ended)
        #expect(try await attached.control() == .detach, "the attached connection only detaches")
    }

    /// A Kill that cannot reach the host is no end: unconfirmed, saying why.
    @Test func anAttachedSessionWhoseKillCannotBeSentIsNotEnded() async throws {
        let (host, session, attached, _) = try await attachedLocally()
        defer { withExtendedLifetime(attached) {} }
        host.stop()
        let outcome = await session.end(within: .seconds(5))
        guard case let .unconfirmed(why) = outcome, why.count == 1 else {
            Issue.record("the end was \(outcome)")
            return
        }
        #expect(why[0].hasPrefix("This Mac's terminal host could not be reached ("), "said as what is true: \(why[0])")
        #expect(why[0].hasSuffix("); if it has stopped, this terminal ended with it."))
    }

    /// A Kill the host refuses, the session still in its list, is no end: unconfirmed in the
    /// host's words.
    @Test func anAttachedSessionWhoseKillIsRefusedIsNotEnded() async throws {
        let (host, session, attached, _) = try await attachedLocally()
        defer { withExtendedLifetime(attached) {} }
        let ended = Task { await session.end(within: .seconds(10)) }
        let words = "Session 'work' is not yours."
        try await Self.refuseTheKill(at: host, saying: words, listing: [SessionListingTests.info("work")])
        #expect(await ended.value == .unconfirmed([words]))
    }

    /// A Kill refused because the host no longer holds the session is the session gone already, by
    /// the host's own list, not by its words.
    @Test func anAttachedSessionTheHostNoLongerListsIsGone() async throws {
        let (host, session, attached, _) = try await attachedLocally()
        defer { withExtendedLifetime(attached) {} }
        let ended = Task { await session.end(within: .seconds(10)) }
        try await Self.refuseTheKill(at: host, saying: "No session 'work'.", listing: [SessionListingTests.info("other")])
        #expect(await ended.value == .gone)
    }

    /// A Kill answered after the end's limit is no end: unconfirmed at the limit, in this Mac's
    /// words.
    @Test func anAttachedSessionWhoseKillIsAnsweredLateIsNotEnded() async throws {
        let (host, session, attached, _) = try await attachedLocally()
        defer { withExtendedLifetime(attached) {} }
        let began = ContinuousClock.now
        let ended = Task { await session.end(within: .seconds(1)) }
        let killer = try await host.accept()
        try await killer.welcome()
        #expect(try await killer.read() == .kill(session: "work"))
        #expect(await ended.value == .unconfirmed(["This Mac's terminal host did not answer within 1 s."]))
        #expect(ContinuousClock.now - began < .seconds(5), "within the end's one limit")
    }

    /// The end's own reads time out with the limit too, and may do so first, on a busy machine:
    /// the host not answering is said the same either way. A limit of 1.5 s reads for 1 s, so here
    /// the read times out first.
    @Test func aKillWhoseReadTimesOutIsSaidAsTheHostNotAnswering() async throws {
        let host = try FakeHost()
        let ended = Task { await Door.local(host.address).end("work", within: .milliseconds(1500)) }
        let killer = try await host.accept()
        try await killer.welcome()
        #expect(try await killer.read() == .kill(session: "work"))
        #expect(await ended.value == .unconfirmed(["This Mac's terminal host did not answer within 1 s."]))
    }

    static func refuseTheKill(at host: FakeHost, saying words: String, listing: [SessionInfo]) async throws {
        let killer = try await host.accept()
        try await killer.welcome()
        #expect(try await killer.read() == .kill(session: "work"))
        try killer.send(.refused(HostRefusal(message: words, remedy: [])))
        let lister = try await host.accept()
        try await lister.welcome()
        #expect(try await lister.read() == .listSessions(after: "", limit: SessionListing.pageSize))
        try lister.send(.sessions(SessionPage(sessions: listing, next: "")))
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
        try peer.send(.writerChanged(fde: "ada", yours: true))
        #expect(try await peer.read() == .resize(TerminalSize(columns: 100, rows: 30)))
        try peer.send(.writerChanged(fde: "eng", yours: false))
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
        #expect(try await peer.read().unnamed == .attach(session: "theirs", write: false))
        try peer.send([.accepted, .resized(TerminalSize(columns: 80, rows: 24)), .replayBegin(safe: true), .replayEnd, .writerChanged(fde: "eng", yours: false)])
        try await watch.until("took the prologue") { watch.seen.contains(.writerChanged("eng", yours: false)) }
        return (host, session, peer)
    }

    /// Fix 2: a render hold left open when the output stops ticks the screen at its limit, so a
    /// renderer that draws only on a tick shows what the program left.
    @Test func aHoldLeftOpenTicksTheScreenAtItsLimit() async throws {
        let hands = Hands()
        let timers = ManualTimers()
        let host = try FakeHost()
        let create = SessionCreate(session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 20, rows: 3))
        let screen = try Screen(size: GridSize(columns: 20, rows: 3), scrollbackBytes: 0, palette: .dark, now: { hands.now })
        let session = TerminalSession(
            open: create, door: .local(host.address), screen: screen, timeout: 5, stableAfter: .zero, sleep: Naps().sleep,
            after: timers.after
        )
        let ticks = Mutex(0)
        let (changes, sink) = AsyncStream.makeStream(of: ContinuousClock.Instant.self)
        session.onScreenChange { time in
            ticks.withLock { $0 += 1 }
            sink.yield(time)
        }
        var told = changes.makeAsyncIterator()
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.acceptAttach(columns: 20, rows: 3)
        try peer.send(output("old\u{1b}[?2026h\r\u{1b}[2Kstuck"))
        while try screen.text() != "old" {
            _ = await told.next()
        }
        try await timers.awaitSet()
        #expect(timers.live == [Screen.holdLimit])
        let before = ticks.withLock { $0 }
        hands.advance(Screen.holdLimit)
        timers.fireAll()
        #expect(ticks.withLock { $0 } == before + 1, "the hold's timer told no change")
        #expect(try Screen.text(of: [screen.frame().rows[0]]) == "stuck")
        session.close()
    }

    /// The thread that reads the host's frames and feeds the screen is named, so a sampler can find
    /// it, and the session says what quality of service it runs at. (Foundation on Linux answers a
    /// thread's name with the process's, so the name is checked where it is told: on macOS.)
    @Test func theReaderThreadIsNamedAndItsQualityKnown() async throws {
        let (_, session, peer, _) = try await attachedLocally()
        let names = Mutex<[String]>([])
        let (told, sink) = AsyncStream.makeStream(of: Void.self)
        session.onScreenChange { _ in
            names.withLock { $0.append(Thread.current.name ?? "") }
            sink.yield()
        }
        var tells = told.makeAsyncIterator()
        try peer.send(output("named"))
        _ = await tells.next()
        #if canImport(Darwin)
        #expect(names.withLock { $0 }.allSatisfy { $0 == TerminalSession.readerName }, "the change was told on \(names.withLock { $0 })")
        #endif
        #expect(!names.withLock { $0 }.isEmpty)
        #expect(session.readerQuality != nil, "the session does not know its reader's quality of service")
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

    /// Closing a pane that holds no connection ends its session at the host on one of its own.
    @Test func aSessionIsEndedWithoutAttaching() async throws {
        let host = try FakeHost()
        async let ended: Void = SessionListing.end("mac-a", at: host.address, timeout: 5)
        let peer = try await host.accept()
        #expect(try await peer.welcome() == "")
        #expect(try await peer.read() == .kill(session: "mac-a"))
        try peer.send(.accepted)
        try await ended
        let refused = Task { try await SessionListing.end("mac-b", at: host.address, timeout: 5) }
        let second = try await host.accept()
        try await second.welcome()
        _ = try await second.read()
        let refusal = HostRefusal(message: "No session mac-b.", remedy: [.words("No session mac-b.")])
        try second.send(.refused(refusal))
        await #expect(throws: TerminalError.refused(refusal)) { try await refused.value }
    }

    /// A runner's door admits Kill only from a connection that attached to the session and holds
    /// its write token, so a runner's session is ended through an attach ticket of its own,
    /// presented once: attached as the writer, past what the attachment streams, then Kill.
    @Test func aRunnersSessionIsEndedThroughItsDoorAsItsWriter() async throws {
        let host = try FakeHost(tcp: ())
        let desk = TicketDesk(address: host.address)
        async let ended = Door.runner(desk).end("app-a")
        let peer = try await host.accept()
        #expect(try await peer.welcome() == "ticket-1")
        #expect(try await peer.read().unnamed == .attach(session: "app-a", write: true))
        try peer.send([.accepted, .replayBegin(safe: true), output("$ "), .replayEnd, .writerChanged(fde: "uday", yours: true)])
        #expect(try await peer.read() == .kill(session: "app-a"))
        try peer.send([.sessionEnded(reason: "ended"), .accepted])
        #expect(await ended == .ended)
        #expect(desk.requests == [.attach(session: "app-a")])
    }

    /// When someone else holds the write token the attachment only observes, and the door refuses
    /// the Kill in its own words: the end is unconfirmed, saying so.
    @Test func aRunnersSessionAnotherHoldsIsNotEndedAndTheDoorsWordsAreSaid() async throws {
        let host = try FakeHost(tcp: ())
        let desk = TicketDesk(address: host.address)
        let ended = Task { await Door.runner(desk).end("app-a") }
        let peer = try await host.accept()
        try await peer.welcome()
        #expect(try await peer.read().unnamed == .attach(session: "app-a", write: true))
        try peer.send([.accepted, .writerChanged(fde: "ana", yours: false)])
        #expect(try await peer.read() == .kill(session: "app-a"))
        let words = FakeRunnerDoor.notTheWriter
        try peer.send(.refused(HostRefusal(message: words, remedy: [])))
        #expect(await ended.value == .unconfirmed([words]), "said in the door's words, decided by the door")
    }

    /// M5: a session the API has no ticket to attach to, refused as not found by its code, ended
    /// already: it is gone, never "could not be ended"; nothing is connected to.
    @Test func aRunnersSessionTheAPIFindsNoneOfIsGoneAlready() async throws {
        let desk = TicketDesk(address: .tcp(host: "127.0.0.1", port: 9))
        desk.refuse(Refusal(code: .known(.notFound), message: "There is no session 'app-a' on this box that you may attach."))
        #expect(await Door.runner(desk).end("app-a") == .gone)
        desk.refuse(Refusal(code: .known(.forbidden), message: "Session 'app-a' is not yours."))
        #expect(await Door.runner(desk).end("app-a") == .unconfirmed(["Session 'app-a' is not yours."]), "any other refusal is said")
    }

    /// M3: a tab closes only once its session ended, or had; an end not confirmed keeps the tab.
    @Test func aTabClosesOnlyOnceItsSessionEnded() {
        #expect(EndOutcome.ended.closesTab && EndOutcome.gone.closesTab)
        #expect(!EndOutcome.unconfirmed(["The runner did not answer within 10 s."]).closesTab)
    }

    /// A host that streams the session forever and never answers the Kill is given up on at the
    /// end's deadline, not read for ever.
    @Test(.timeLimit(.minutes(1))) func aRunnerThatNeverAnswersTheKillIsGivenUpOn() async throws {
        let host = try FakeHost(tcp: ())
        let desk = TicketDesk(address: host.address)
        let ended = Task { await Door.runner(desk).end("app-a", within: .seconds(1)) }
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.read()
        try peer.send(.accepted)
        _ = try await peer.read()
        let streaming = Task.detached {
            while !Task.isCancelled { try peer.send(output("x")) }
        }
        let outcome = await ended.value
        streaming.cancel()
        guard case .unconfirmed = outcome else {
            Issue.record("the end was \(outcome)")
            return
        }
    }

    /// M4: the ticket is inside the end's deadline: a control plane that never answers it leaves the
    /// end unconfirmed at the deadline, not at the transport's own 60 s.
    @Test(.timeLimit(.minutes(1))) func aTicketThatNeverComesIsInsideTheEndsDeadline() async {
        struct Silent: TicketSource {
            func ticket(for _: TicketRequest) async throws(SailError) -> TerminalTicket {
                try? await Task.sleep(for: .seconds(3600))
                throw .unreachable("no answer")
            }
        }
        let asked = ContinuousClock.now
        #expect(await Door.runner(Silent()).end("app-a", within: .seconds(1)) == .unconfirmed(["The runner did not answer within 1 s."]))
        #expect(ContinuousClock.now - asked < .seconds(5))
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
        let refusal = HostRefusal(message: "Listing is not allowed here.", remedy: [.words("Listing is not allowed here.")])
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
