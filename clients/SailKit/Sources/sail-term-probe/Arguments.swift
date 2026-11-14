import Foundation
import SailTerminal
import SailVT

/// What the probe was asked to do, read from its command line. A token is never on the command
/// line: `--token-env` names the environment variable that holds it.
enum Mode: Equatable {
    /// Write the golden of a recorded byte stream fed into a grid.
    case dump(file: String, size: GridSize)
    /// Measure the screen's own overhead.
    case bench(report: String?)
    /// Run a script against a host.
    case run(Run)
}

struct Run: Equatable {
    enum Host: Equatable {
        /// This machine's own host: its socket, the binary that serves it when the probe owns it,
        /// and the folder a session opens in.
        case local(socket: String?, binary: String?, folder: String)
        /// A runner, through tickets the API at `api` hands the token in `tokenVariable`.
        case runner(api: URL, tokenVariable: String, project: String)
    }

    var host: Host
    var script: String
    var report: String?
    var capture: String?
}

struct UsageError: Error, CustomStringConvertible {
    let description: String
}

enum Arguments {
    static let usage = """
    Usage:
      sail-term-probe --dump BYTES --size COLUMNSxROWS
      sail-term-probe --bench [--report FILE]
      sail-term-probe --local [--socket PATH] [--host-binary SAIL] [--folder PATH] --script FILE [--report FILE] [--capture FILE]
      sail-term-probe --api URL --token-env NAME --project PROJECT --script FILE [--report FILE]
    """

    static func parse(_ arguments: [String], currentDirectory: String) throws(UsageError) -> Mode {
        var options: [String: String] = [:]
        var flags: Set<String> = []
        var rest = arguments[...]
        while let name = rest.popFirst() {
            switch name {
            case "--local", "--bench":
                flags.insert(name)
            case "--dump", "--size", "--socket", "--host-binary", "--folder", "--script", "--report", "--capture", "--api",
                 "--token-env", "--project":
                guard let value = rest.popFirst(), !value.hasPrefix("--") else { throw UsageError(description: "\(name) needs a value") }
                guard options.updateValue(value, forKey: name) == nil else { throw UsageError(description: "\(name) is given twice") }
            default:
                throw UsageError(description: "'\(name)' is not an option of sail-term-probe")
            }
        }
        if let file = options["--dump"] {
            try only(options, flags, ["--dump", "--size"])
            return .dump(file: file, size: try size(options["--size"]))
        }
        if flags.contains("--bench") {
            try only(options, flags, ["--bench", "--report"])
            return .bench(report: options["--report"])
        }
        guard let script = options["--script"] else { throw UsageError(description: "--script is required") }
        if flags.contains("--local") {
            try only(options, flags, ["--local", "--socket", "--host-binary", "--folder", "--script", "--report", "--capture"])
            let folder = options["--folder"] ?? currentDirectory
            guard folder.hasPrefix("/") else { throw UsageError(description: "--folder must be an absolute path") }
            return .run(Run(
                host: .local(socket: options["--socket"], binary: options["--host-binary"], folder: folder), script: script,
                report: options["--report"], capture: options["--capture"]
            ))
        }
        try only(options, flags, ["--api", "--token-env", "--project", "--script", "--report"])
        guard let api = options["--api"], let url = URL(string: api), ["http", "https"].contains(url.scheme), url.host != nil else {
            throw UsageError(description: "--api must be an http or https URL")
        }
        guard let variable = options["--token-env"], !variable.isEmpty else { throw UsageError(description: "--token-env is required") }
        guard let project = options["--project"], !project.isEmpty else { throw UsageError(description: "--project is required") }
        return .run(Run(
            host: .runner(api: url, tokenVariable: variable, project: project), script: script, report: options["--report"],
            capture: nil
        ))
    }

    private static func only(_ options: [String: String], _ flags: Set<String>, _ allowed: Set<String>) throws(UsageError) {
        let given = Set(options.keys).union(flags)
        guard let stray = given.subtracting(allowed).sorted().first else { return }
        throw UsageError(description: "\(stray) does not go with \(allowed.sorted().first ?? "")")
    }

    private static func size(_ text: String?) throws(UsageError) -> GridSize {
        let parts = (text ?? "").split(separator: "x").compactMap { Int($0) }
        guard parts.count == 2, parts.allSatisfy({ (1 ... 65535).contains($0) }) else {
            throw UsageError(description: "--size must be COLUMNSxROWS")
        }
        return GridSize(columns: parts[0], rows: parts[1])
    }
}
