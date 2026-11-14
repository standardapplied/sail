import Foundation
import SailAPI
@testable import SailTerminal
import SailTerminalWire
import SailVT
@testable import sail_term_probe
import Testing

/// The real `sail` terminal host of this machine: the scenarios run against `sail _pty-host
/// --local` started from `SAIL_BINARY`, in this account's home. `SAIL_LOCAL_SOCKET` and
/// `SAIL_LOCAL_FOLDER` name the socket and the folder when the host runs where the account's own
/// home and folders are not (a container, on a box whose home the host refuses). Skipped without
/// `SAIL_BINARY`; CI sets it.
@Suite(.serialized, .timeLimit(.minutes(2)), .enabled(if: RealLocalHost.binary != nil))
struct RealLocalHostTests {
    /// Scenario 1.
    @Test func aSessionOpensInItsFolderWithTheLoginShell() async throws {
        try await RealLocalHost.with { host in
            let (session, watch) = try host.open()
            try await watch.attached(within: 30)
            "pwd\r".forEach { session.send(.typing($0)) }
            try await watch.until(within: 20, "showed its folder") { try session.screen.text().contains("\n\(host.folder)") }
            session.close()
        }
    }

    /// Scenario 7.
    @Test func aHostThatCrashedAndStartedAgainEndsTheSessionAsTheHostRestarted() async throws {
        try await RealLocalHost.with { host in
            let (session, watch) = try host.open()
            try await watch.attached(within: 30)
            host.process.crash()
            try await host.process.start()
            try await watch.phase(.ended(.hostRestarted), within: 30)
        }
    }

    /// Scenario 9: a host that is not there fails 5 tries; the parked session tries at once on
    /// reconnectNow, and finds the host that replaced it.
    @Test func aHostThatIsGoneParksTheSessionAndReconnectNowTriesAtOnce() async throws {
        try await RealLocalHost.with { host in
            let naps = Naps()
            let (session, watch) = try host.open(sleep: naps.sleep)
            try await watch.attached(within: 30)
            host.process.crash()
            try await watch.until(within: 30, "parked") {
                if case .parked = session.state.phase { return true }
                return false
            }
            #expect(naps.durations == TerminalSession.retryWaits)
            try await host.process.start()
            session.reconnectNow()
            try await watch.phase(.ended(.hostRestarted), within: 30)
            #expect(naps.durations == TerminalSession.retryWaits)
        }
    }

    /// Scenario 10.
    @Test func keysTypedWhileReconnectingAreDroppedAndCounted() async throws {
        try await RealLocalHost.with { host in
            let timeline = Timeline()
            let (session, watch) = try host.open(command: ["cat"], sleep: timeline.sleep)
            try await watch.attached(within: 30)
            session.dropConnection()
            try await watch.phase(.reconnecting(attempt: 1))
            "abc".forEach { session.send(.typing($0)) }
            #expect(session.state.droppedInputs == 3)
            try await timeline.until(held: [TerminalSession.retryWaits[0]])
            timeline.passAll()
            try await watch.attached(within: 30)
            "z\r".forEach { session.send(.typing($0)) }
            try await watch.until(within: 20, "echoed z") { try session.screen.text().contains("z") }
            #expect(try !session.screen.text().contains("abc"))
            #expect(session.state.droppedInputs == 3)
            session.close()
        }
    }

    /// Scenarios 5 and 6 on this machine's host: a drop reattaches and replays the same screen, and
    /// an unfocused view tells a program that asked for focus reports that it lost focus.
    @Test func aDropReattachesToTheSameScreenAndReportsFocusLost() async throws {
        try await RealLocalHost.with { host in
            let (session, watch) = try host.open(command: ["sh", "-c", "printf 'focus\\033[?1004h\\n'; exec cat -v"])
            try await watch.attached(within: 30)
            try await watch.until(within: 20, "started cat") { try session.screen.text().contains("focus") }
            let before = try session.screen.text()
            session.setFocused(false)
            session.dropConnection()
            try await watch.phase(.reconnecting(attempt: 1))
            try await watch.attached(within: 30)
            try await watch.until(within: 20, "showed the focus-lost report") { try session.screen.text().contains("^[[O") }
            #expect(try session.screen.text().hasPrefix(before))
            session.close()
        }
    }
}

/// A real local host for one test, stopped when the test ends.
struct RealLocalHost {
    static let binary = ProcessInfo.processInfo.environment["SAIL_BINARY"]

    let process: LocalHostProcess
    let address: HostAddress
    let folder: String

    static func with(_ body: (RealLocalHost) async throws -> Void) async throws {
        guard let binary else { throw HostError("SAIL_BINARY names no sail binary") }
        let environment = ProcessInfo.processInfo.environment
        let process = LocalHostProcess(binary: binary)
        try await process.start()
        defer { process.stop() }
        let folder = environment["SAIL_LOCAL_FOLDER"] ?? FileManager.default.temporaryDirectory.resolvingSymlinksInPath().path
        let address = try environment["SAIL_LOCAL_SOCKET"].map { HostAddress.unix(path: $0) } ?? HostAddress.local()
        try await body(RealLocalHost(process: process, address: address, folder: folder))
    }

    func open(
        command: [String] = [], sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) throws -> (TerminalSession, Watch) {
        let create = SessionCreate(
            session: "test-" + UUID().uuidString.prefix(8).lowercased(), command: command, workingDirectory: folder, project: "",
            room: "", size: TerminalSize(columns: 100, rows: 30)
        )
        let session = TerminalSession(
            open: create, door: .local(address), screen: try screen(columns: 100, rows: 30), timeout: 10, sleep: sleep
        )
        let watch = Watch(session)
        session.start()
        return (session, watch)
    }
}
