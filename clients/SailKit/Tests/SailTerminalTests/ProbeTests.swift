import Foundation
import SailAPI
import SailTerminal
import SailTerminalWire
@testable import sail_term_probe
import Testing

@Suite struct ProbeTests {
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

    /// MINOR 11: a report names what a step did, never what it typed.
    @Test func aReportNamesAStepsKindNotWhatItTyped() {
        #expect(Step.type("secret words\r").description == "type 13 characters")
    }

    /// A `same` after a reconnect is proved by the replay, not by the screen before the replay
    /// arrives: the e2e lane once passed a drop's check on the old screen while the replay that
    /// followed changed it.
    @Test func aSameAfterAReconnectWaitsForTheReplay() async throws {
        let steps = try await reconnected { peer in try peer.send(.accepted) }
        #expect(steps.map(\.ok) == [true, true, true, true, true, false])
    }

    @Test func aSameAfterAReconnectPassesOnAReplayOfTheKeptScreen() async throws {
        let steps = try await reconnected { peer in
            try peer.send([
                .accepted, .resized(TerminalSize(columns: 20, rows: 3)), .replayBegin(safe: true), output("kept"), .replayEnd,
                .writerChanged(fde: "ada"),
            ])
        }
        #expect(steps.map(\.ok) == [true, true, true, true, true, true])
    }

    /// Runs snapshot, drop, attached, same against a fake host whose reattach `answer` gives.
    private func reconnected(answer: (FakeHost.Peer) async throws -> Void) async throws -> [StepResult] {
        let host = try FakeHost()
        let create = SessionCreate(
            session: "work", command: [], workingDirectory: "/tmp", project: "", room: "", size: TerminalSize(columns: 20, rows: 3)
        )
        let session = TerminalSession(
            open: create, door: .local(host.address), screen: try screen(columns: 20, rows: 3), timeout: 5, stableAfter: .zero,
            sleep: Naps().sleep
        )
        let runner = Runner(session: session, host: nil, echoBudget: 10) { _ in }
        let script = try JSONDecoder().decode(Script.self, from: Data("""
            {"size": [20, 3], "steps": [{"wait": "kept", "within": 5}, {"snapshot": "before"}, {"drop": true},
              {"phase": "attached", "within": 5}, {"same": "before"}]}
            """.utf8))
        session.start()
        let first = try await host.accept()
        try await first.welcome()
        _ = try await first.acceptAttach(columns: 20, rows: 3)
        try first.send(output("kept"))
        let report = Task { await runner.run(script) }
        let again = try await host.accept()
        try await again.welcome()
        guard case .attach = try await again.read() else { throw HostError("the reconnect did not attach") }
        try await answer(again)
        let steps = await report.value.steps
        session.close()
        _ = first
        return steps
    }
}
