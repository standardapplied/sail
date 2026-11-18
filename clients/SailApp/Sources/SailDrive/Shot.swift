import Foundation

/// A screenshot by `/usr/sbin/screencapture -x`, begun and then awaited. Exec'd from a shell, the
/// drive runs it itself. Opened by LaunchServices, the app is its own responsible process for the
/// privacy grants and holds no Screen Recording, so it asks the shell that launched it, which
/// does: one line on the FIFO `SAIL_DRIVE_SHOTS` names (screencapture's arguments, tab-separated,
/// the file last), answered by `<file>.done` once taken, beside `<file>.said`, what screencapture
/// said.
@MainActor
struct Shot {
    static let longest = Duration.seconds(20)

    let file: URL
    private let process: Process?
    private let errors: Pipe?

    /// Begins a shot with `arguments` into `file`; nil, said, when it could not be begun.
    static func begin(_ arguments: [String], to file: URL) -> Shot? {
        let words = ["-x"] + arguments + [file.path]
        if let fifo = ProcessInfo.processInfo.environment["SAIL_DRIVE_SHOTS"] {
            guard let asked = FileHandle(forWritingAtPath: fifo) else {
                print("screencapture: the launching shell's FIFO \(fifo) could not be opened")
                return nil
            }
            asked.write(Data((words.joined(separator: "\t") + "\n").utf8))
            try? asked.close()
            return Shot(file: file, process: nil, errors: nil)
        }
        let process = Process()
        let errors = Pipe()
        process.executableURL = URL(filePath: "/usr/sbin/screencapture")
        process.arguments = words
        process.standardError = errors
        guard (try? process.run()) != nil else {
            print("screencapture did not run")
            return nil
        }
        return Shot(file: file, process: process, errors: errors)
    }

    /// Returns once the shot is taken, at most ``longest`` later, having printed what
    /// screencapture said, if anything.
    func taken() async {
        let done = URL(filePath: file.path + ".done")
        let said = URL(filePath: file.path + ".said")
        let deadline = ContinuousClock.now + Self.longest
        while ContinuousClock.now < deadline, process?.isRunning ?? !FileManager.default.fileExists(atPath: done.path) {
            try? await Task.sleep(for: .milliseconds(10))
        }
        var text: String
        if let process, let errors {
            if process.isRunning { process.terminate() }
            text = String(decoding: errors.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        } else {
            let answered = FileManager.default.fileExists(atPath: done.path)
            text = answered ? ((try? String(contentsOf: said, encoding: .utf8)) ?? "") : "the launching shell took no shot within 20 s"
            try? FileManager.default.removeItem(at: done)
            try? FileManager.default.removeItem(at: said)
        }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty { print("screencapture: \(text)") }
    }
}
