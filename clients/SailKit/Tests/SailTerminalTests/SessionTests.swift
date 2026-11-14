import Foundation
import SailAPI
@testable import SailTerminal
import SailTerminalWire
import SailVT
@testable import sail_term_probe
import Testing

@Suite(.timeLimit(.minutes(1))) struct SessionTests {
    let create = SessionCreate(
        session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 80, rows: 24)
    )

    @Test func aLocalSessionSaysHelloBlankCreatesThenAttachesForWriting() async throws {
        let host = try FakeHost()
        let session = TerminalSession(open: create, door: .local(host.address), screen: try screen())
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        #expect(try await peer.welcome() == "")
        #expect(try await peer.read() == .create(create))
        try peer.send(.accepted)
        #expect(try await peer.read() == .attach(session: "work", write: true))
        try peer.send(.accepted)
        try await watch.attached()
        #expect(session.state.hostBootID == "boot-1")
        session.close()
    }

    /// T1 and T4: a runner session differs only in where it connects and the token it presents, a
    /// ticket asked for each connection and presented once.
    @Test func aRunnerSessionPresentsAFreshTicketOnEveryConnection() async throws {
        let host = try FakeHost(tcp: ())
        let desk = TicketDesk(address: host.address)
        let naps = Naps()
        let session = TerminalSession(open: create, door: .runner(desk), screen: try screen(), timeout: 5, sleep: naps.sleep)
        let watch = Watch(session)
        session.start()
        let first = try await host.accept()
        #expect(try await first.welcome() == "ticket-1")
        _ = try await first.acceptAttach()
        try await watch.attached()
        first.close()
        let second = try await host.accept()
        #expect(try await second.welcome() == "ticket-2")
        #expect(try await second.read() == .attach(session: "work", write: true))
        #expect(desk.requests == [.open(project: ""), .attach(session: "work")])
        session.close()
    }

    /// T3: output reaches the screen in order, and a resize lands after the bytes before it.
    @Test func outputAndResizesReachTheScreenInTheOrderTheyCame() async throws {
        let (host, session, peer, watch) = try await attachedLocally(columns: 10, rows: 3)
        try peer.send([output("abcdefghij"), .resized(TerminalSize(columns: 5, rows: 3)), output("\r\nxyz")])
        try await watch.text("xyz")
        #expect(try session.screen.frame().size == GridSize(columns: 5, rows: 3))
        #expect(try session.screen.text().hasSuffix("xyz"))
        try await watch.until("told the resize") { watch.seen.contains(.resized(TerminalSize(columns: 5, rows: 3))) }
        session.close()
        _ = host
    }

    /// T5 and scenario 6: a replay starts from a clean screen and ends at the bottom; a view that is
    /// not focused owes a program that asked for focus reports a focus-lost.
    @Test func aReplayStartsCleanAndAnUnfocusedViewReportsFocusLost() async throws {
        let (host, session, peer, watch) = try await attachedLocally(columns: 20, rows: 3)
        try peer.send(output("before the drop"))
        try await watch.text("before the drop")
        session.setFocused(false)
        try peer.send([.replayBegin(safe: true), output("\u{1b}[?1004hafter"), .replayEnd])
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("\u{1b}[O".utf8)))
        #expect(try session.screen.text() == "after")
        session.close()
        _ = host
    }

    /// Scenario 12: the reply a stream's query provokes goes to the host as input.
    @Test func aDeviceStatusRequestIsAnsweredAsInput() async throws {
        let (host, session, peer, _) = try await attachedLocally()
        try peer.send(output("ab\u{1b}[6n"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("\u{1b}[1;3R".utf8)))
        session.close()
        _ = host
    }

    /// T6: a host that welcomes a reconnect with a new boot id lost every session it held.
    @Test func aWelcomeWithANewBootIDEndsTheSessionAsTheHostRestarted() async throws {
        let naps = Naps()
        let (host, _, peer, watch) = try await attachedLocally(sleep: naps.sleep)
        peer.close()
        let again = try await host.accept()
        try await again.welcome(bootID: "boot-2")
        try await watch.phase(.ended(.hostRestarted))
        #expect(SessionEnd.hostRestarted.description == "the host restarted, which ends every session it held")
    }

    @Test func aRefusedAttachEndsTheSessionWithTheHostsWords() async throws {
        let host = try FakeHost()
        let session = TerminalSession(attach: "theirs", write: true, door: .local(host.address), screen: try screen())
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        #expect(try await peer.read() == .attach(session: "theirs", write: true))
        let words = "There is no session 'theirs' on this box that you may attach."
        try peer.send(.refused(HostRefusal(message: words, remedy: nil)))
        try await watch.phase(.ended(.refused(words)))
    }

    /// Scenario 8, the client's half: the API's refusal of the attach ticket is the end, in its words.
    @Test func aRefusedTicketEndsTheSessionWithTheAPIsWords() async throws {
        let desk = TicketDesk(address: .tcp(host: "127.0.0.1", port: 9))
        let words = "There is no session 'theirs' on this box that you may attach."
        desk.refuse(Refusal(code: .known(.notFound), message: words))
        let session = TerminalSession(attach: "theirs", write: true, door: .runner(desk), screen: try screen())
        let watch = Watch(session)
        session.start()
        try await watch.phase(.ended(.refused(words)))
        #expect(desk.requests == [.attach(session: "theirs")])
    }

    /// Scenario 9: after 5 failures in a row, waiting 0.5, 1, 2, 4 and 8 s before them, the session
    /// parks; reconnectNow tries at once.
    @Test func aHostThatNeverAnswersParksTheSessionUntilReconnectNow() async throws {
        let naps = Naps()
        let (host, session, peer, watch) = try await attachedLocally(sleep: naps.sleep)
        host.stop()
        peer.close()
        try await watch.until("parked") {
            if case .parked = session.state.phase { return true }
            return false
        }
        #expect(naps.durations == TerminalSession.retryWaits)
        guard case let .parked(reason) = session.state.phase else { return }
        #expect(reason.contains("could not be reached"))
        try host.restart()
        session.reconnectNow()
        let again = try await host.accept()
        try await again.welcome()
        #expect(try await again.read() == .attach(session: "work", write: true))
        #expect(naps.durations == TerminalSession.retryWaits)
        session.close()
    }

    /// Scenario 10 and T7: keys typed while reconnecting are dropped and counted, never sent later.
    @Test func keysTypedWhileReconnectingAreDroppedAndCounted() async throws {
        let timeline = Timeline()
        let (host, session, peer, watch) = try await attachedLocally(sleep: timeline.sleep)
        peer.close()
        try await watch.phase(.reconnecting(attempt: 1))
        for letter in ["a", "b", "c"] {
            session.send(KeyInput(key: .a, text: letter))
        }
        #expect(session.state.droppedInputs == 3)
        try await timeline.until(held: [TerminalSession.retryWaits[0]])
        timeline.passAll()
        let again = try await host.accept()
        try await again.welcome()
        _ = try await again.acceptAttach()
        try await watch.attached()
        session.send(KeyInput(key: .z, text: "z"))
        #expect(try await again.read() == .input(sequence: 1, bytes: Array("z".utf8)))
        #expect(session.state.droppedInputs == 3)
        session.close()
    }

    /// Scenario 15: a bracketed paste larger than an input frame goes as frames of 64 KiB, numbered
    /// in order.
    @Test func aLargePasteIsSplitAtSixtyFourKiB() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        try peer.send(output("\u{1b}[?2004hready"))
        try await watch.text("ready")
        let text = String(repeating: "line of text\n", count: 12000)
        guard case .send = session.paste(text) else {
            Issue.record("a paste into a program that asked was not sent")
            return
        }
        var received: [UInt8] = []
        var sequences: [Int64] = []
        let expected = Array("\u{1b}[200~\(text)\u{1b}[201~".utf8)
        while received.count < expected.count {
            guard case let .input(sequence, bytes) = try await peer.read() else { continue }
            #expect(bytes.count <= 64 * 1024)
            sequences.append(sequence)
            received += bytes
        }
        #expect(received == expected)
        #expect(sequences == Array(1 ... Int64(sequences.count)))
        #expect(sequences.count == 3)
        session.close()
        _ = host
    }

    /// Scenario 14: a multi-line paste into a program that did not ask sends nothing until confirmed.
    @Test func aPasteThatNeedsConfirmationSendsNothing() async throws {
        let (host, session, peer, _) = try await attachedLocally()
        #expect(session.paste("one\ntwo\nthree") == .needsConfirmation)
        session.send(KeyInput(key: .x, text: "x"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("x".utf8)))
        session.close()
        _ = host
    }

    /// Scenario 16: the screen resizes at once; the host hears one size, once it held for 60 ms.
    @Test func aSizeIsSentOnceItHasHeldForSixtyMilliseconds() async throws {
        let timeline = Timeline()
        let (host, session, peer, watch) = try await attachedLocally(sleep: timeline.sleep)
        try session.resize(GridSize(columns: 132, rows: 40))
        #expect(try session.screen.frame().size == GridSize(columns: 132, rows: 40))
        try session.resize(GridSize(columns: 80, rows: 24))
        #expect(try session.screen.frame().size == GridSize(columns: 80, rows: 24))
        try await timeline.until(held: [TerminalSession.resizeHold])
        timeline.passAll()
        #expect(try await peer.read() == .resize(TerminalSize(columns: 80, rows: 24)))
        session.send(KeyInput(key: .q, text: "q"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("q".utf8)))
        session.close()
        _ = host
    }

    /// T8: paused and continued are told, and the client reads on through them.
    @Test func aPausedSessionIsToldAndReadOn() async throws {
        let (host, session, peer, watch) = try await attachedLocally(columns: 20, rows: 3)
        try peer.send([.paused, output("still read")])
        try await watch.text("still read")
        #expect(session.state.paused)
        try peer.send([.continued, .replayBegin(safe: true), output("caught up"), .replayEnd])
        try await watch.text("caught up")
        #expect(!session.state.paused)
        try await watch.until("told the pause and its end") { watch.seen.contains(.paused) && watch.seen.contains(.continued) }
        session.close()
        _ = host
    }

    /// Scenario 17: a 20 MiB burst reaches the screen whole, through a pause the host tells, with
    /// the client's memory bounded by its scrollback rather than by the burst.
    @Test func aTwentyMebibyteBurstReachesTheScreenThroughAPause() async throws {
        let (host, session, peer, watch) = try await attachedLocally(columns: 40, rows: 5)
        let counted = ByteCount()
        session.tapOutput { counted.add($0.count) }
        let line = Array("0123456789abcdefghijklmnopqrstuvwxyzABC\r\n".utf8)
        let frame = Array([[UInt8]](repeating: line, count: (256 * 1024) / line.count).joined())
        let before = residentBytes()
        for index in 0 ..< (20 << 20) / frame.count {
            if index == 40 { try peer.send(.paused) }
            if index == 41 { try peer.send(.continued) }
            try peer.send(.output(lastInputSequence: 0, bytes: frame))
        }
        try peer.send(output("end-of-burst"))
        try await watch.text("end-of-burst", within: 30)
        #expect(counted.total == (20 << 20) / frame.count * frame.count + "end-of-burst".utf8.count)
        try await watch.until("told the pause and its end") { watch.seen.contains(.paused) && watch.seen.contains(.continued) }
        #expect(!session.state.paused)
        if let before, let after = residentBytes() {
            #expect(after - before < 20 << 20, "grew \(after - before) bytes")
        }
        session.close()
        _ = host
    }

    @Test func theInputNumberAnEchoNamesMarksTheKeysLatency() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        session.send(KeyInput(key: .k, text: "k"))
        #expect(try await peer.read() == .input(sequence: 1, bytes: Array("k".utf8)))
        try peer.send(output("k", applied: 1))
        try await watch.until("measured a key") { session.latencyReport.screen.count == 1 }
        let report = session.latencyReport
        #expect(report.written.count == 1 && report.echoed.count == 1)
        #expect(report.written.max <= report.echoed.max && report.echoed.max <= report.screen.max)
        session.close()
        _ = host
    }

    @Test func takeWriteKillAndDetachAreTheirFrames() async throws {
        let (host, session, peer, watch) = try await attachedLocally()
        session.takeWrite()
        #expect(try await peer.read() == .takeWrite)
        session.kill()
        #expect(try await peer.read() == .kill(session: "work"))
        session.detach()
        #expect(try await peer.read() == .detach)
        try peer.send(.accepted)
        try await watch.phase(.detached)
        session.attach()
        let again = try await host.accept()
        try await again.welcome()
        #expect(try await again.read() == .attach(session: "work", write: true))
        session.close()
    }

    @Test func aDetachWhileReconnectingStopsTheRetriesAndAttachStartsAgain() async throws {
        let timeline = Timeline()
        let (host, session, peer, watch) = try await attachedLocally(sleep: timeline.sleep)
        peer.close()
        try await timeline.until(held: [TerminalSession.retryWaits[0]])
        session.detach()
        try await watch.phase(.detached)
        try await timeline.until(held: [])
        session.attach()
        let again = try await host.accept()
        try await again.welcome()
        _ = try await again.acceptAttach()
        try await watch.attached()
        #expect(timeline.held.isEmpty)
        session.close()
    }

    @Test func aSessionTheHostEndsIsOverForTheClient() async throws {
        let (host, _, peer, watch) = try await attachedLocally()
        try peer.send(.sessionEnded(reason: "exited 0"))
        try await watch.phase(.ended(.ended("exited 0")))
        _ = host
    }

    @Test func aHostOfAnotherProtocolEndsTheSessionNamingTheOlderSide() async throws {
        let host = try FakeHost()
        let session = TerminalSession(open: create, door: .local(host.address), screen: try screen())
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        try peer.send(raw: Array("SAILPTY2".utf8))
        try await watch.phase(.ended(.refused("the terminal host is older than this app: upgrade sail on it")))
    }

    @Test func aHostThatRefusesTheHelloEndsTheSessionInItsWords() async throws {
        let host = try FakeHost()
        let session = TerminalSession(open: create, door: .local(host.address), screen: try screen())
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        _ = try await peer.hello()
        try peer.send(.refused(HostRefusal(message: "That token is not this machine's person.", remedy: nil)))
        try await watch.phase(.ended(.refused("That token is not this machine's person.")))
    }

    private func attachedLocally(
        columns: Int = 80, rows: Int = 24, sleep: @escaping @Sendable (Duration) async throws -> Void = Naps().sleep
    ) async throws -> (FakeHost, TerminalSession, FakeHost.Peer, Watch) {
        let host = try FakeHost()
        let size = TerminalSize(columns: Int32(columns), rows: Int32(rows))
        var create = create
        create.size = size
        let session = TerminalSession(
            open: create, door: .local(host.address), screen: try screen(columns: columns, rows: rows), timeout: 5, sleep: sleep
        )
        let watch = Watch(session)
        session.start()
        let peer = try await host.accept()
        try await peer.welcome()
        _ = try await peer.acceptAttach(columns: size.columns, rows: size.rows)
        try await watch.until("took the attach's prologue") { watch.seen.contains(.writerChanged("ada")) }
        return (host, session, peer, watch)
    }
}
