import AppKit
import Darwin
import Foundation
import SailAPI
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailTerminal
import SailTerminalWire
import SailTestPasskey
import SailWorkbench
import Synchronization
import Testing

/// Tickets as a control plane that refuses or cannot be reached hands them out: every request kept.
final class RecordingTickets: TicketSource {
    private let asked = Mutex<[TicketRequest]>([])
    private let answer: SailError

    init(answering answer: SailError) {
        self.answer = answer
    }

    var requests: [TicketRequest] {
        asked.withLock { $0 }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        asked.withLock { $0.append(request) }
        throw answer
    }
}

/// A runner's terminal door played by a test, on a loopback port: it welcomes any ticket, opens and
/// attaches what it is asked to, and ends a session when asked, unless told to refuse it, which it
/// does in the real door's words and hangs up, as the real door does for a connection that does not
/// hold the write token. Every Kill it was sent is kept.
final class FakeRunnerDoor: Sendable {
    let port: UInt16
    private let listener: Int32
    private let state = Mutex<(kills: [String], refusing: Set<String>)>(([], []))

    init() throws {
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw DoorError("no socket for the door") }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socket, $0, size) } }
        guard bound == 0, listen(socket, 16) == 0 else {
            close(socket)
            throw DoorError("the door could not listen")
        }
        var named = size
        _ = withUnsafeMutablePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socket, $0, &named) } }
        port = UInt16(bigEndian: address.sin_port)
        listener = socket
        Thread { [self] in serve() }.start()
    }

    /// The sessions it was asked to end, in order.
    var kills: [String] {
        state.withLock { $0.kills }
    }

    /// Refuses to end `session` from now on.
    func refuse(_ session: String) {
        state.withLock { _ = $0.refusing.insert(session) }
    }

    func stop() {
        shutdown(listener, Int32(SHUT_RDWR))
        close(listener)
    }

    /// The real door's words for a Kill outside what a connection's ticket admits.
    static func refusal(of session: String) -> String {
        "This connection's ticket is to attach to session '\(session)', and it admits nothing else; ask the API for a ticket for that."
    }

    private func serve() {
        while true {
            let connection = accept(listener, nil, nil)
            guard connection >= 0 else { return }
            var one: Int32 = 1
            setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            Thread { [self] in converse(connection) }.start()
        }
    }

    private func converse(_ connection: Int32) {
        defer { close(connection) }
        guard receive(8, on: connection) == Handshake.magic else { return }
        transmit(Handshake.magic, on: connection)
        while let frame = frame(on: connection) {
            switch frame {
            case .hello: answer(.welcome(hostBootID: "door-1"), on: connection)
            case .create, .attach: answer(.accepted, on: connection)
            case let .kill(session):
                let refused = state.withLock { state in
                    state.kills.append(session)
                    return state.refusing.contains(session)
                }
                guard refused else {
                    answer(.accepted, on: connection)
                    continue
                }
                return answer(.refused(HostRefusal(message: Self.refusal(of: session), remedy: nil)), on: connection)
            default: continue
            }
        }
    }

    private func frame(on connection: Int32) -> Frame? {
        guard let header = receive(4, on: connection) else { return nil }
        let length = header.reduce(0) { $0 << 8 | Int($1) }
        guard length > 0, length <= FrameCodec.maxPayload, let payload = receive(length, on: connection) else { return nil }
        return try? FrameCodec.decode(payload: payload)
    }

    private func receive(_ count: Int, on connection: Int32) -> [UInt8]? {
        var bytes = [UInt8](repeating: 0, count: count)
        var got = 0
        while got < count {
            let read = bytes.withUnsafeMutableBytes { recv(connection, $0.baseAddress! + got, count - got, 0) }
            guard read > 0 else { return nil }
            got += read
        }
        return bytes
    }

    private func answer(_ frame: Frame, on connection: Int32) {
        guard let bytes = try? FrameCodec.encode(frame) else { return }
        transmit(bytes, on: connection)
    }

    private func transmit(_ bytes: [UInt8], on connection: Int32) {
        _ = bytes.withUnsafeBytes { send(connection, $0.baseAddress, $0.count, 0) }
    }
}

struct DoorError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}

/// Tickets to a ``FakeRunnerDoor``, every request kept.
final class DoorTickets: TicketSource {
    private let address: String
    private let asked = Mutex<[TicketRequest]>([])

    init(_ door: FakeRunnerDoor) {
        address = "127.0.0.1:\(door.port)"
    }

    var requests: [TicketRequest] {
        asked.withLock { $0 }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        asked.withLock { $0.append(request) }
        return TerminalTicket(ticket: "ticket", address: address, expiresAt: Date().addingTimeInterval(30))
    }
}

/// What a quit was told to do, in order.
@MainActor final class Replies {
    var given: [Bool] = []
}

/// A terminal in a workspace: a tab like any other whose session's ticket names the tab's
/// workspace, at the first open and at Open Again; refused, it says the server's words and what
/// the person can do; closed or quit, its session is ended at the runner, and what could not be
/// ended is said.
@MainActor @Suite struct RunnerTabTests {
    static let notYours = Refusal(
        code: .known(.forbiddenNotAssignee), message: "Workspace 'dock' belongs to 'uday', not you.",
        action: "Ask uday to enter it, or have an admin do it."
    )

    static func until(_ seconds: Double = 5, _ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else { return false }
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in DispatchQueue.main.async { done.resume() } }
        }
        return true
    }

    static func pane(_ place: Place, runner: (any TicketSource)?) throws -> TerminalPane {
        let shell = try WorkbenchLookTests.shell(.light)
        var bench = Workbench()
        let id = bench.newTab(session: place.newSessionName, place: place)
        return try TerminalPane(
            try #require(bench.panes[id]), start: .open(folder: place.folder), host: shell.thisMac.host, runner: runner,
            fonts: TerminalFonts(directory: LookTests.fonts), points: 15, look: .light, palette: Look.light.palette
        )
    }

    /// A3, A5, A13: the session asks a ticket naming the tab's project and workspace; refused, the
    /// banner says the server's sentence and the fix in words; Open Again asks a new ticket for
    /// the same workspace.
    @Test func aRunnerTabsTicketNamesItsWorkspaceAtEveryOpen() async throws {
        let tickets = RecordingTickets(answering: .refused(Self.notYours))
        let pane = try Self.pane(.workspace(project: "acme", name: "try"), runner: tickets)
        #expect(pane.status == nil)
        pane.begin()
        #expect(await Self.until { pane.status == "Workspace 'dock' belongs to 'uday', not you. Ask uday to enter it, or have an admin do it." },
                "the banner says \(pane.status ?? "nothing")")
        pane.openAgain()
        #expect(await Self.until { tickets.requests.count == 2 })
        #expect(tickets.requests == [.open(project: "acme", workspace: "try"), .open(project: "acme", workspace: "try")])
    }

    /// Q2: while a runner tab's session keeps trying, the banner names where it is going back to and
    /// still offers Reconnect Now, which asks a ticket at once.
    @Test func aRunnerTabSaysWhereItIsReconnectingAndOffersReconnectNow() async throws {
        let tickets = RecordingTickets(answering: .unreachable("the runner does not answer"))
        let pane = try Self.pane(.workspace(project: "harbor", name: "dock"), runner: tickets)
        pane.begin()
        #expect(await Self.until { pane.status == "Reconnecting to harbor · dock…" }, "the banner says \(pane.status ?? "nothing")")
        #expect(pane.statusButton == "Reconnect Now")
        pane.detach()
    }

    /// Every word a sheet shows: its texts and its buttons' titles.
    static func words(in view: NSView?) -> [String] {
        guard let view else { return [] }
        let own: [String] = switch view {
        case let button as NSButton: [button.title]
        case let field as NSTextField: [field.stringValue]
        default: []
        }
        return own + view.subviews.flatMap { words(in: $0) }
    }

    /// Two runner tabs, harbor · dock and harbor · try, attached through the fake door.
    static func attachedRunnerTabs(_ tickets: DoorTickets) async throws -> [TerminalPane] {
        let panes = try ["dock", "try"].map { try Self.pane(.workspace(project: "harbor", name: $0), runner: tickets) }
        panes.forEach { $0.begin() }
        #expect(await Self.until(10) { panes.allSatisfy { $0.status == nil } }, "attached: \(panes.map { $0.status ?? "nothing said" })")
        return panes
    }

    /// Ruling 6, a person's quit with runner tabs open: asked once, "Quit and End 2 Runner
    /// Terminals" ends each at the runner through its door (an attach ticket for it, then Kill), and
    /// only then does the app quit.
    @Test func quittingAsksOnceThenEndsEachRunnerTerminalThroughItsDoorAndQuits() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let tickets = DoorTickets(door)
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attachedRunnerTabs(tickets)
        let replies = Replies()
        let answered = RunnerQuit.ask(panes, on: shell.window) { replies.given.append($0) }
        #expect(answered == .terminateLater, "AppKit is told to wait for the answer")
        let sheet = try #require(shell.window.attachedSheet, "the quit asks")
        let said = Self.words(in: sheet.contentView)
        #expect(said.contains("Quit and End 2 Runner Terminals") && said.contains("Cancel"), "the sheet says \(said)")
        shell.window.endSheet(sheet, returnCode: .alertFirstButtonReturn)
        #expect(await Self.until(10) { replies.given == [true] }, "the app was told \(replies.given)")
        #expect(door.kills.sorted() == panes.map(\.name).sorted(), "the door was asked to end \(door.kills)")
        let attached = tickets.requests.compactMap { request -> String? in if case let .attach(session) = request { session } else { nil } }
        #expect(attached.sorted() == panes.map(\.name).sorted(), "an attach ticket for each, then its Kill: \(tickets.requests)")
        #expect(shell.window.attachedSheet == nil, "nothing more is asked")
    }

    /// Cancel leaves every runner terminal as it was and the app running.
    @Test func cancellingTheQuitEndsNothing() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let tickets = DoorTickets(door)
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attachedRunnerTabs(tickets)
        let asked = tickets.requests
        let replies = Replies()
        _ = RunnerQuit.ask(panes, on: shell.window) { replies.given.append($0) }
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertSecondButtonReturn)
        #expect(await Self.until(10) { replies.given == [false] }, "the app was told \(replies.given)")
        #expect(door.kills.isEmpty && tickets.requests == asked, "kills \(door.kills), tickets \(tickets.requests)")
        #expect(panes.allSatisfy { $0.status == nil }, "every tab is still attached")
    }

    /// A runner terminal the door will not end is named, with the door's words, before the quit;
    /// the other is ended.
    @Test func aRunnerTerminalThatCannotBeEndedIsNamedBeforeTheQuit() async throws {
        let door = try FakeRunnerDoor()
        defer { door.stop() }
        let tickets = DoorTickets(door)
        let shell = try WorkbenchLookTests.shell(.light)
        let panes = try await Self.attachedRunnerTabs(tickets)
        door.refuse(panes[1].name)
        let replies = Replies()
        _ = RunnerQuit.ask(panes, on: shell.window) { replies.given.append($0) }
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await Self.until(10) { Self.words(in: shell.window.attachedSheet?.contentView).contains("Some terminals on the runner could not be ended") })
        let said = Self.words(in: shell.window.attachedSheet?.contentView).joined(separator: "\n")
        #expect(said.contains("harbor · try: \(FakeRunnerDoor.refusal(of: panes[1].name))") && !said.contains("harbor · dock:"), "the sheet says \(said)")
        #expect(replies.given.isEmpty, "the app waits for the person to read it")
        #expect(door.kills == panes.map(\.name), "both were asked")
        shell.window.endSheet(try #require(shell.window.attachedSheet), returnCode: .alertFirstButtonReturn)
        #expect(await Self.until(10) { replies.given == [true] }, "the app was told \(replies.given)")
    }

    /// A tab in a workspace is made only for a person signed in: its tickets are theirs.
    @Test func aWorkspaceTabNeedsSomeoneSignedIn() throws {
        #expect(throws: ShellError.host("Sign in to open a terminal in a workspace.")) {
            try Self.pane(.workspace(project: "acme", name: "try"), runner: nil)
        }
    }

    /// Ruling 6: a runner tab's session does not outlive the app. Ended at the runner on its own
    /// connection, through an attach ticket for it; a runner that cannot be reached is said, the tab
    /// named, so the quit can say which it could not end.
    @Test func runnerTerminalsAreEndedAtTheRunnerAndThoseThatCannotBeAreSaid() async throws {
        let tickets = RecordingTickets(answering: .unreachable("the runner does not answer"))
        let runner = try Self.pane(.workspace(project: "acme", name: "try"), runner: tickets)
        runner.begin()
        #expect(await Self.until { !tickets.requests.isEmpty })
        let ending = try #require(runner.end(), "a session not attached is ended on a connection of its own")
        let stopped = await ending.value
        #expect(stopped != nil, "a runner that cannot be reached is said")
        #expect(tickets.requests.contains(.attach(session: runner.name)), "\(tickets.requests)")
    }

    /// The Workspaces item is on the rail only while someone is signed in.
    @Test func theWorkspacesItemIsOfferedOnlySignedIn() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        #expect(shell.rail.button(.workspaces)?.isHidden == true)
        shell.rail.offer(.workspaces, true)
        #expect(shell.rail.button(.workspaces)?.isHidden == false)
    }

    /// A1, A2, A7: the pane lists the person's workspaces from the server's own answers, theirs
    /// first; Make says it is under way; a refusal is the server's sentence and the fix in words.
    @Test func thePaneListsMakesAndRefusesInTheServersWords() async throws {
        let pane = WorkspacesPane(look: .light, title: try TitleFont(directory: LookTests.fonts))
        pane.person = ("uday", true)
        pane.client = SailClient(transport: RecordedServer([try Exchange("projects"), try Exchange("workspaces"), try Exchange("workspace-create")]), token: "tok")
        await pane.refresh()
        #expect(pane.rows.map(\.name) == ["dock"], "the project's own container is not shown")
        #expect(pane.list.selectedRow == 0 && pane.open.isEnabled, "the first row is chosen, so Return or Open opens it")
        #expect(pane.text.contains("dock · running"), "\(pane.text)")
        pane.name.stringValue = "new"
        let before = pane.ended
        pane.makeWorkspace(nil)
        #expect(pane.text.contains("Making new (about 3 s)"))
        #expect(await Self.until { pane.ended > before })
        #expect(!pane.text.contains("Making"), "\(pane.text)")
        let viewer = WorkspacesPane(look: .light, title: try TitleFont(directory: LookTests.fonts))
        viewer.client = SailClient(
            transport: RecordedServer([try Exchange("projects"), try Exchange("workspaces"), try Exchange("refusal-read-only-credential-workspace")]),
            token: "tok"
        )
        await viewer.refresh()
        viewer.name.stringValue = "new"
        let asked = viewer.ended
        viewer.makeWorkspace(nil)
        #expect(await Self.until { viewer.ended > asked })
        #expect(viewer.text.hasSuffix("Your credential is read-only and cannot enter workspaces.\nAsk an admin for a member or admin credential."), "\(viewer.text)")
    }
}
