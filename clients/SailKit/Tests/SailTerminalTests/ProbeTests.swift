import Foundation
import SailAPI
import SailKitTestSupport
import SailTerminal
import SailTerminalWire
@testable import sail_term_probe
import Synchronization
import Testing

@Suite(.timeLimit(.minutes(1))) struct ProbeTests {
    /// `--workspace` names the workspace a runner session opens in; without it, the project's own
    /// container, as before.
    @Test func aRunnerRunNamesItsWorkspace() throws {
        let given = ["--api", "http://10.0.0.2:7443", "--token-env", "T", "--project", "acme", "--script", "s.json"]
        let inTry = try Arguments.parse(given + ["--workspace", "try"], currentDirectory: "/")
        let bare = try Arguments.parse(given, currentDirectory: "/")
        guard case let .run(run) = inTry, case let .run(plain) = bare else { Issue.record("not runs"); return }
        #expect(run.host == .runner(api: URL(string: "http://10.0.0.2:7443")!, tokenVariable: "T", project: "acme", workspace: "try"))
        #expect(plain.host == .runner(api: URL(string: "http://10.0.0.2:7443")!, tokenVariable: "T", project: "acme", workspace: ""))
        #expect(throws: UsageError.self) { try Arguments.parse(["--local", "--workspace", "try", "--script", "s.json"], currentDirectory: "/") }
    }

    /// MINOR 11: a capture is a new file only its owner reads; one that exists is not overwritten.
    @Test func aCaptureIsANewFileOnlyItsOwnerReads() throws {
        let path = FileManager.default.temporaryDirectory.appending(path: "capture-\(UUID().uuidString)").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let capture = try Capture(path: path)
        capture.append(Array("bytes".utf8))
        try capture.close()
        let attributes = try FileManager.default.attributesOfItem(atPath: path)
        #expect((attributes[.posixPermissions] as? NSNumber)?.intValue == 0o600)
        #expect(throws: ProbeError.self) { try Capture(path: path) }
        #expect(try String(contentsOfFile: path, encoding: .utf8) == "bytes")
    }

    /// A host that ends before it says it serves is reported as it ends, never at the start's
    /// deadline, which is held and never let pass; it is reaped before the start throws.
    @Test func aHostThatEndsBeforeItServesIsReportedAsItEnds() async throws {
        let deadline = Timeline()
        let started = Mutex<pid_t?>(nil)
        let host = LocalHostProcess(binary: "/usr/bin/true", sleep: deadline.sleep) { executable, arguments, errors throws(SpawnRefused) in
            let pid = try RealLocalHost.spawn(executable, arguments, errors)
            started.withLock { $0 = pid }
            return pid
        }
        await #expect { try await host.start() } throws: { error in
            "\(error)" == "/usr/bin/true _pty-host --local ended before it said it serves"
        }
        let pid = try #require(started.withLock { $0 })
        #expect(HostTakeoverTests.gone(pid), "the host was left behind")
        withExtendedLifetime(host) {}
    }

    /// A start cancelled before its host says it serves is said to be cancelled, never to have
    /// run out of its deadline, which is held and never let pass. The host takes no arguments and
    /// lives until it is killed.
    @Test func aCancelledStartIsSaidToBeCancelled() async throws {
        let deadline = Timeline()
        let binary = FileManager.default.temporaryDirectory.appending(path: "silent-host-\(UUID().uuidString)").path
        defer { try? FileManager.default.removeItem(atPath: binary) }
        try #require(FileManager.default.createFile(atPath: binary, contents: Data("#!/bin/sh\nexec tail -f /dev/null\n".utf8), attributes: [.posixPermissions: 0o700]))
        let host = LocalHostProcess(binary: binary, sleep: deadline.sleep, spawn: RealLocalHost.spawn)
        let start = Task { try await host.start() }
        start.cancel()
        await #expect(throws: CancellationError.self) { try await start.value }
    }

    /// MINOR 11: a report names what a step did, never what it typed.
    @Test func aReportNamesAStepsKindNotWhatItTyped() {
        #expect(Step.type("secret words\r").description == "type 13 characters")
    }

    /// A `same` after a reconnect is proved by the replay, not by the screen before the replay
    /// arrives: the e2e lane once passed a drop's check on the old screen while the replay that
    /// followed changed it. No replay comes, and the `same` fails once its limit is let pass.
    @Test func aSameAfterAReconnectWaitsForTheReplay() async throws {
        let steps = try await reconnected(sameRunsOut: true) { peer in try peer.send(.accepted) }
        #expect(steps.map(\.ok) == [true, true, true, true, true, false])
    }

    /// Fix 3: a replay is compared once it has ended, never partway through, where a screen can
    /// match the kept one for a moment.
    @Test func aSameWaitsForTheReplayToEnd() async throws {
        let steps = try await reconnected(sameRunsOut: true) { peer in
            try peer.send([.accepted, .resized(TerminalSize(columns: 20, rows: 3)), .replayBegin(safe: true), output("kept")])
        }
        #expect(steps.map(\.ok) == [true, true, true, true, true, false])
    }

    @Test func aSameAfterAReconnectPassesOnAReplayOfTheKeptScreen() async throws {
        let steps = try await reconnected(sameRunsOut: false) { peer in
            try peer.send([
                .accepted, .resized(TerminalSize(columns: 20, rows: 3)), .replayBegin(safe: true), output("kept"), .replayEnd,
                .writerChanged(fde: "ada", yours: true),
            ])
        }
        #expect(steps.map(\.ok) == [true, true, true, true, true, true])
    }

    /// The GCP proof's close and Reconnect Now: close lets the client's part go, then ends the
    /// session through its door as its writer, as a runner tab's close does.
    @Test func closeEndsTheSessionThroughItsDoorAndReconnectNowTriesAtOnce() async throws {
        let held = try JSONDecoder().decode(Script.self, from: Data(#"{"size": [20, 3], "steps": [{"hold": 12}]}"#.utf8))
        #expect(held.steps == [.hold(12)] && held.steps.map(\.description) == ["hold 12 s"])
        let script = try JSONDecoder().decode(Script.self, from: Data(#"{"size": [20, 3], "steps": [{"reconnectNow": true}, {"close": true}]}"#.utf8))
        #expect(script.steps == [.reconnectNow, .close])
        #expect(script.steps.map(\.description) == ["reconnect now", "close, ending the session at its host"])
        let host = try FakeHost(tcp: ())
        let desk = TicketDesk(address: host.address)
        let create = SessionCreate(
            session: "work", command: [], workingDirectory: "/tmp", project: "acme", room: "", size: TerminalSize(columns: 20, rows: 3),
            workspace: "try"
        )
        let session = TerminalSession(
            open: create, door: .runner(desk), screen: try screen(columns: 20, rows: 3), timeout: Patience.answer, sleep: Naps().sleep
        )
        let runner = Runner(session: session, host: nil, echoBudget: 10, patience: nil, ending: .seconds(Patience.answer)) { _ in }
        session.start()
        let first = try await host.accept()
        try await first.welcome()
        _ = try await first.acceptAttach(columns: 20, rows: 3)
        async let report = runner.run(script)
        #expect(try await first.read() == .resize(TerminalSize(columns: 20, rows: 3)), "reconnect now")
        let ending = try await host.accept()
        #expect(try await ending.welcome() == "ticket-2")
        #expect(try await ending.read().unnamed == .attach(session: "work", write: true))
        try ending.send(.accepted)
        #expect(try await ending.read() == .kill(session: "work"))
        try ending.send(.accepted)
        let steps = await report.steps
        #expect(steps.map(\.ok) == [true, true, true])
        #expect(desk.requests == [.open(project: "acme", workspace: "try"), .attach(session: "work")])
        _ = first
    }

    /// A limited wait whose task is cancelled, as a test's time limit cancels it, says what it waited
    /// for, never only that it was cancelled.
    @Test func aCancelledWaitSaysWhatItWaitedFor() async throws {
        let create = SessionCreate(
            session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 20, rows: 3)
        )
        let session = TerminalSession(
            open: create, door: .local(.unix(path: "/nonexistent/h.sock")), screen: try screen(columns: 20, rows: 3), timeout: Patience.answer,
            sleep: Naps().sleep
        )
        defer { session.close() }
        let limits = Timeline()
        let follower = Follower(session, patience: Self.probesOwn, sleep: limits.sleep)
        let waiting = Task { try await follower.until("held what it never holds") { false } }
        try await limits.until(held: [.seconds(Self.probesOwn)])
        waiting.cancel()
        do {
            try await waiting.value
            Issue.record("a wait for what never holds returned")
        } catch let error as ProbeError {
            #expect(error.description.hasPrefix("the session never held what it never holds"), "\(error)")
        }
    }

    /// The probe's own patience: the limit a `same` whose replay never comes, or never ends, fails
    /// at, and the one limit these tests let pass.
    static let probesOwn = 5

    /// Runs snapshot, drop, attached, same against a fake host whose reattach `answer` gives. Every
    /// limit of the run's is held until the test lets it pass: the steps that name one wait
    /// `Patience.answer`, the others the probe's own 5 s. Once the drop is seen (the attached step
    /// holds the only limit) the host answers, and when `sameRunsOut` the `same`'s limit, then the
    /// only one held, passes; no limit runs out by the runner's speed.
    private func reconnected(sameRunsOut: Bool, answer: (FakeHost.Peer) async throws -> Void) async throws -> [StepResult] {
        let host = try FakeHost()
        let create = SessionCreate(
            session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 20, rows: 3)
        )
        let session = TerminalSession(
            open: create, door: .local(host.address), screen: try screen(columns: 20, rows: 3), timeout: Patience.answer, stableAfter: .zero,
            sleep: Naps().sleep
        )
        let limits = Timeline()
        let runner = Runner(session: session, host: nil, echoBudget: 10, patience: Self.probesOwn, sleep: limits.sleep) { _ in }
        let script = try JSONDecoder().decode(Script.self, from: Data("""
            {"size": [20, 3], "steps": [{"wait": "kept", "within": \(Patience.answer)}, {"snapshot": "before"}, {"drop": true},
              {"phase": "attached", "within": \(Patience.answer)}, {"same": "before"}]}
            """.utf8))
        session.start()
        let first = try await host.accept()
        try await first.welcome()
        _ = try await first.acceptAttach(columns: 20, rows: 3)
        try first.send(output("kept"))
        async let report = runner.run(script)
        let again = try await host.accept()
        try await again.welcome()
        guard case .attach = try await again.read() else { throw HostError("the reconnect did not attach") }
        try await limits.until(held: [.seconds(Patience.answer)])
        try await answer(again)
        if sameRunsOut {
            try await limits.until(held: [.seconds(Self.probesOwn)])
            limits.passAll()
        }
        let steps = await report.steps
        session.close()
        _ = first
        return steps
    }
}
