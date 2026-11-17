import Foundation
import SailAPI
import SailTerminal
import SailTerminalWire
import SailVT
import Synchronization

/// A terminal client with no window: it opens a session on this machine's host or on a runner,
/// runs a script of steps against it, prints the screen, and leaves a JSON report.
@main
struct Probe {
    /// T10's budget for an echo over loopback through each kind of host.
    static let echoBudgets = (local: 10.0, runner: 15.0)

    static func main() async {
        do {
            let mode = try Arguments.parse(Array(CommandLine.arguments.dropFirst()), currentDirectory: FileManager.default.currentDirectoryPath)
            exit(try await run(mode) ? 0 : 1)
        } catch let error as UsageError {
            say("sail-term-probe: \(error)\n\(Arguments.usage)")
            exit(2)
        } catch {
            say("sail-term-probe: \(error)")
            exit(1)
        }
    }

    static func run(_ mode: Mode) async throws -> Bool {
        switch mode {
        case let .dump(file, size):
            let screen = try Screen(size: size, scrollbackBytes: 1 << 20, palette: .dark)
            print(try screen.dump(after: screen.feed([UInt8](try Data(contentsOf: URL(filePath: file))))), terminator: "")
            return true
        case let .bench(path):
            let bench = try Bench.run()
            let report = Report(steps: [], latency: nil, droppedInputs: nil, budgets: bench.budgets, samples: bench.samples)
            report.budgets.forEach { budget in
                let verdict = budget.reported ? "" : " (limit \(budget.limit)) \(budget.met ? "met" : "MISSED")"
                print("\(budget.name): \(String(format: "%.3f", budget.milliseconds))\(verdict)")
            }
            try report.write(to: path)
            return report.passed
        case let .run(run):
            return try await script(run)
        }
    }

    private static func script(_ run: Run) async throws -> Bool {
        let script = try Script.read(run.script)
        let size = GridSize(columns: script.size[0], rows: script.size[1])
        let screen = try Screen(size: size, scrollbackBytes: 10 << 20, palette: .dark)
        var create = SessionCreate(
            session: "probe-" + UUID().uuidString.prefix(8).lowercased(), command: script.command, workingDirectory: "",
            project: "", room: "", size: TerminalSize(columns: Int32(size.columns), rows: Int32(size.rows))
        )
        var host: LocalHostProcess?
        defer { host?.stop() }
        var tickets: RecordingTickets?
        let door: Door
        let echoBudget: Double
        switch run.host {
        case let .local(socket, binary, folder):
            if let binary {
                host = LocalHostProcess(binary: binary)
                try await host?.start()
            }
            create.workingDirectory = folder
            door = .local(try socket.map { HostAddress.unix(path: $0) } ?? HostAddress.local())
            echoBudget = echoBudgets.local
        case let .runner(api, variable, project, workspace):
            guard let token = ProcessInfo.processInfo.environment[variable], !token.isEmpty else {
                throw ProbeError("the environment variable \(variable) holds no token")
            }
            create.project = project
            create.workspace = workspace
            create.workingDirectory = "/tmp"
            let recorded = RecordingTickets(SailClient(transport: URLSessionTransport(base: api), token: token))
            tickets = recorded
            door = .runner(recorded)
            echoBudget = echoBudgets.runner
        }
        let session = TerminalSession(open: create, door: door, screen: screen)
        let capture = try run.capture.map(Capture.init(path:))
        if let capture {
            session.tapOutput { capture.append($0) }
        }
        session.start()
        say("session \(create.session)")
        if script.endWhileOpening { return await endWhileOpening(session) }
        var report = await Runner(session: session, host: host, echoBudget: echoBudget) { result in
            say("\(result.ok ? "ok" : "FAILED") \(result.step) (\(String(format: "%.1f", result.milliseconds)) ms)\(result.detail.map { ": \($0)" } ?? "")")
        }.run(script)
        if case let .ended(end) = session.state.phase {
            say("a person reads: " + end.said.joined(separator: " | "))
        }
        session.close()
        report.connections = tickets?.asked ?? []
        try capture?.close()
        print(try screen.text())
        report.budgets.forEach { say("\($0.name): \(String(format: "%.3f", $0.milliseconds)) ms (limit \($0.limit)) \($0.met ? "met" : "MISSED")") }
        try report.write(to: run.report)
        return report.passed
    }

    /// Ends `session` while its `Create` is out and unanswered, as closing a tab on a workspace
    /// that is starting does: true when the end began with the `Create` still unanswered and the
    /// runner ended what it made. The `Create` sends no event, so this looks every 10 ms.
    private static func endWhileOpening(_ session: TerminalSession) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(60)
        while session.atHost != .coming {
            guard ContinuousClock.now < deadline, session.atHost == .none else {
                say("FAILED the Create was never seen unanswered: \(session.state.phase)")
                return false
            }
            try? await Task.sleep(for: .milliseconds(10))
        }
        let began = ContinuousClock.now
        let outcome = await session.end()
        say("ended while opening: the Create unanswered when the end began; \(outcome) in \(began.duration(to: .now))")
        session.close()
        return outcome.closesTab
    }

    private static func say(_ line: String) {
        FileHandle.standardError.write(Data((line + "\n").utf8))
    }
}

/// Tickets from the API, each request kept: one per connection the session makes to its host, so
/// the report shows every connection the run opened.
final class RecordingTickets: TicketSource {
    private let source: any TicketSource
    private let requests = Mutex<[String]>([])

    init(_ source: any TicketSource) {
        self.source = source
    }

    var asked: [String] {
        requests.withLock { $0 }
    }

    func ticket(for request: TicketRequest) async throws(SailError) -> TerminalTicket {
        let named =
            switch request {
            case let .open(project, workspace):
                workspace.isEmpty ? "a ticket to open a session in \(project)" : "a ticket to open a session in workspace \(workspace) of \(project)"
            case let .attach(session): "a ticket to attach to \(session)"
            }
        requests.withLock { $0.append(named) }
        return try await source.ticket(for: request)
    }
}

/// The raw output of a session, kept in a new file the person named, readable by them alone: how
/// the vim and less fixtures are recorded. Nothing else the probe does writes a terminal byte
/// anywhere.
final class Capture: Sendable {
    private let file: FileHandle

    init(path: String) throws {
        let descriptor = open(path, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard descriptor >= 0 else { throw ProbeError("cannot make \(path), which must not exist yet: \(String(cString: strerror(errno)))") }
        file = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
    }

    func append(_ bytes: [UInt8]) {
        file.write(Data(bytes))
    }

    func close() throws {
        try file.close()
    }
}
