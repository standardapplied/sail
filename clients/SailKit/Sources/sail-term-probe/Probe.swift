import Foundation
import SailAPI
import SailTerminal
import SailTerminalWire
import SailVT

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
            let report = Report(steps: [], latency: nil, droppedInputs: nil, budgets: try Bench.run())
            report.budgets.forEach { print("\($0.name): \(String(format: "%.3f", $0.milliseconds)) (limit \($0.limit)) \($0.met ? "met" : "MISSED")") }
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
        let create = SessionCreate(
            session: "probe-" + UUID().uuidString.prefix(8).lowercased(), command: script.command, workingDirectory: "",
            project: "", room: "", size: TerminalSize(columns: Int32(size.columns), rows: Int32(size.rows))
        )
        var host: LocalHostProcess?
        defer { host?.stop() }
        let session: TerminalSession
        let echoBudget: Double
        switch run.host {
        case let .local(socket, binary, folder):
            if let binary {
                host = LocalHostProcess(binary: binary)
                try await host?.start()
            }
            var local = create
            local.workingDirectory = folder
            let address = try socket.map { HostAddress.unix(path: $0) } ?? HostAddress.local()
            session = TerminalSession(open: local, door: .local(address), screen: screen)
            echoBudget = echoBudgets.local
        case let .runner(api, variable, project):
            guard let token = ProcessInfo.processInfo.environment[variable], !token.isEmpty else {
                throw ProbeError("the environment variable \(variable) holds no token")
            }
            var remote = create
            remote.project = project
            remote.workingDirectory = "/tmp"
            let client = SailClient(transport: URLSessionTransport(base: api), token: token)
            session = TerminalSession(open: remote, door: .runner(client), screen: screen)
            echoBudget = echoBudgets.runner
        }
        let capture = try run.capture.map(Capture.init(path:))
        if let capture {
            session.tapOutput { capture.append($0) }
        }
        session.start()
        let report = await Runner(session: session, host: host, echoBudget: echoBudget) { result in
            say("\(result.ok ? "ok" : "FAILED") \(result.step) (\(String(format: "%.1f", result.milliseconds)) ms)\(result.detail.map { ": \($0)" } ?? "")")
        }.run(script)
        session.close()
        try capture?.close()
        print(try screen.text())
        report.budgets.forEach { say("\($0.name): \(String(format: "%.3f", $0.milliseconds)) ms (limit \($0.limit)) \($0.met ? "met" : "MISSED")") }
        try report.write(to: run.report)
        return report.passed
    }

    private static func say(_ line: String) {
        FileHandle.standardError.write(Data((line + "\n").utf8))
    }
}

/// The raw output of a session, kept in a file the person named: how the vim and less fixtures are
/// recorded. Nothing else the probe does writes a terminal byte anywhere.
private final class Capture: Sendable {
    private let file: FileHandle

    init(path: String) throws {
        guard FileManager.default.createFile(atPath: path, contents: nil) else { throw ProbeError("cannot write \(path)") }
        file = try FileHandle(forWritingTo: URL(filePath: path))
    }

    func append(_ bytes: [UInt8]) {
        file.write(Data(bytes))
    }

    func close() throws {
        try file.close()
    }
}
