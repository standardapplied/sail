import Darwin
import Foundation

/// A screenshot by `/usr/sbin/screencapture`, begun and then awaited. Exec'd from a shell, the drive
/// runs it itself. Opened by LaunchServices, the app is its own responsible process for the privacy
/// grants and holds no Screen Recording, so it asks the shell that launched it, which does: one
/// line on the FIFO `SAIL_DRIVE_SHOTS` names, `screen FILE`, `window ID FILE` or `menu FILE`,
/// tab-separated; the shell builds screencapture's arguments itself and answers with `FILE.done`
/// once taken, beside `FILE.said`, what screencapture said.
@MainActor
struct Shot {
    /// The whole screen; one window, by its number, without its shadow; or the whole screen a
    /// second later, while a menu is up.
    enum Kind {
        case screen
        case window(Int)
        case menu
    }

    static let longest = Duration.seconds(20)

    let file: URL
    private let process: Process?
    private let errors: Pipe?

    /// Begins a shot of `kind` into `file`; nil, said, when it could not be begun.
    static func begin(_ kind: Kind, to file: URL) -> Shot? {
        try? FileManager.default.removeItem(at: Self.done(file))
        try? FileManager.default.removeItem(at: Self.said(file))
        if let fifo = ProcessInfo.processInfo.environment["SAIL_DRIVE_SHOTS"] {
            return ask(fifo, request(kind, file)) ? Shot(file: file, process: nil, errors: nil) : nil
        }
        let process = Process()
        let errors = Pipe()
        process.executableURL = URL(filePath: "/usr/sbin/screencapture")
        process.arguments = arguments(kind) + [file.path]
        process.standardError = errors
        guard (try? process.run()) != nil else {
            print("screencapture did not run")
            return nil
        }
        return Shot(file: file, process: process, errors: errors)
    }

    /// screencapture's arguments for `kind`, as the launching shell builds them too.
    static func arguments(_ kind: Kind) -> [String] {
        switch kind {
        case .screen: ["-x"]
        case let .window(number): ["-x", "-o", "-l", "\(number)"]
        case .menu: ["-x", "-T", "1"]
        }
    }

    static func request(_ kind: Kind, _ file: URL) -> String {
        switch kind {
        case .screen: "screen\t\(file.path)\n"
        case let .window(number): "window\t\(number)\t\(file.path)\n"
        case .menu: "menu\t\(file.path)\n"
        }
    }

    /// Writes `line` to the FIFO without waiting for a reader: a shell that is not reading is said,
    /// not waited on for ever.
    private static func ask(_ fifo: String, _ line: String) -> Bool {
        let descriptor = open(fifo, O_WRONLY | O_NONBLOCK)
        guard descriptor >= 0 else {
            print("screencapture: the launching shell is not reading \(fifo) (errno \(errno))")
            return false
        }
        defer { close(descriptor) }
        let bytes = Array(line.utf8)
        return bytes.withUnsafeBytes { write(descriptor, $0.baseAddress, $0.count) } == bytes.count
    }

    private static func done(_ file: URL) -> URL {
        URL(filePath: file.path + ".done")
    }

    private static func said(_ file: URL) -> URL {
        URL(filePath: file.path + ".said")
    }

    /// Returns once the shot is taken, at most ``longest`` later, having printed what
    /// screencapture said, if anything.
    func taken() async {
        let done = Self.done(file)
        let said = Self.said(file)
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
