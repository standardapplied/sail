import Foundation
import SailMeasures

/// Main's renderer against the branch's, on one runner (spec
/// `sail-app-renderer-bench-gates-on-the-machines-speed`). `serve --fonts DIR` draws a round of
/// redraws for each `round N` line it reads, a JSON line of their CPU times each, warm throughout,
/// and the CJK figures for `cjk`. `compare` starts main's build and the branch's in `serve`, asks
/// them in turn, main first, for ``pairs`` + 1 rounds each (the first a warm-up), judges them
/// (``SameRunner``) and exits 0 only when the step passes.
///
/// X, the most a branch may be slower than main (``noise``), is twice the largest drift main showed
/// against itself on CI: over ten runs, both sides the same commit, the median of each run's pairs
/// was 0.98, 1.01, 1.00, 1.04, 0.99, 1.00, 0.99, 1.01, 1.00 and 1.05, so 2 × 5 % = 10 %. A single
/// pair ran 0.54 to 2.00, which is why the median of the pairs is judged and never one pair.
enum RenderBench {
    static let pairs = 20
    static let round = 50
    /// X: the most a branch may be slower than main, a fraction.
    static let noise = 0.10

    static func main() throws -> Int32 {
        let arguments = Array(CommandLine.arguments.dropFirst())
        func value(_ name: String) -> String? {
            arguments.firstIndex(of: name).flatMap { arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil }
        }
        guard let mode = arguments.first, let fonts = value("--fonts") else {
            FileHandle.standardError.write(Data("usage: RenderBench serve --fonts DIR | compare --fonts DIR --main BIN --branch BIN --base SHA --body FILE --gating yes|no [--main-unbuilt WHY]\n".utf8))
            return 2
        }
        switch mode {
        case "serve":
            try serve(fonts: URL(filePath: fonts))
            return 0
        case "compare":
            signal(SIGPIPE, SIG_IGN)
            guard let branch = value("--branch"), let base = value("--base"), let body = value("--body"), let gating = value("--gating") else { return 2 }
            let allowance = SameRunner.allowance(in: (try? String(contentsOfFile: body, encoding: .utf8)) ?? "")
            let outcome: SameRunner.Outcome
            if let why = value("--main-unbuilt") {
                outcome = SameRunner.uncompared(why, allowance: allowance, gating: gating == "yes")
            } else if let main = value("--main") {
                outcome = try compare(main: main, branch: branch, fonts: fonts, base: base, allowance: allowance, gating: gating == "yes")
            } else { return 2 }
            outcome.lines.forEach { say("render-ab: " + $0) }
            outcome.warnings.forEach { say("::warning title=renderer bench::" + $0) }
            return outcome.met ? 0 : 1
        default:
            return 2
        }
    }

    private static func serve(fonts: URL) throws {
        let redraws = try Redraws(fonts: fonts)
        while let line = readLine() {
            if line.hasPrefix("round "), let count = Int(line.dropFirst(6)) {
                say(String(decoding: try JSONEncoder().encode(try redraws.round(count)), as: UTF8.self))
            } else if line == "cjk" {
                say(String(decoding: try JSONEncoder().encode(try redraws.cjk()), as: UTF8.self))
            } else {
                return
            }
        }
    }

    private static func compare(main: String, branch: String, fonts: String, base: String, allowance: SameRunner.Allowance?, gating: Bool) throws
        -> SameRunner.Outcome
    {
        let mainSide = try Served(main, fonts: fonts), branchSide = try Served(branch, fonts: fonts)
        var pairs: [SameRunner.Pair] = []
        for _ in 0 ... Self.pairs {
            guard let drawnByMain = mainSide.ask("round \(round)") else {
                return SameRunner.uncompared("main's renderer stopped answering", allowance: allowance, gating: gating)
            }
            guard let drawnByBranch = branchSide.ask("round \(round)") else {
                return SameRunner.Outcome(met: !gating, lines: ["missed: the branch's renderer stopped answering"], warnings: [])
            }
            pairs.append(SameRunner.Pair(main: drawnByMain, branch: drawnByBranch))
        }
        let cjk = branchSide.figures("cjk").map { $0.sorted { $0.key < $1.key }.map { "\($0.key) \(String(format: "%.2f", $0.value))" }.joined(separator: ", ") }
        let judged = SameRunner.judge(pairs, noise: noise, allowance: allowance, gating: gating, base: base)
        return SameRunner.Outcome(met: judged.met, lines: judged.lines + ["the branch's CJK screen (reported): " + (cjk ?? "not drawn")], warnings: judged.warnings)
    }

    /// `line` on standard output at once, unbuffered: a pipe's reader waits for it.
    static func say(_ line: String) {
        FileHandle.standardOutput.write(Data((line + "\n").utf8))
    }
}

/// A build of the bench running `serve`, asked a line at a time.
final class Served {
    private let process = Process()
    private let input = Pipe(), output = Pipe()
    private var buffer = Data()

    init(_ binary: String, fonts: String) throws {
        process.executableURL = URL(filePath: binary)
        process.arguments = ["serve", "--fonts", fonts]
        process.standardInput = input
        process.standardOutput = output
        try process.run()
    }

    deinit {
        try? input.fileHandleForWriting.close()
        process.waitUntilExit()
    }

    /// The CPU times of the round `line` asked for, nil when the build stopped answering.
    func ask(_ line: String) -> [Double]? {
        reply(to: line).flatMap { try? JSONDecoder().decode([Double].self, from: $0) }
    }

    func figures(_ line: String) -> [String: Double]? {
        reply(to: line).flatMap { try? JSONDecoder().decode([String: Double].self, from: $0) }
    }

    private func reply(to line: String) -> Data? {
        guard (try? input.fileHandleForWriting.write(contentsOf: Data((line + "\n").utf8))) != nil else { return nil }
        while true {
            if let end = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                defer { buffer.removeSubrange(...end) }
                return buffer[..<end]
            }
            let more = output.fileHandleForReading.availableData
            guard !more.isEmpty else { return nil }
            buffer.append(more)
        }
    }
}

exit(try RenderBench.main())
