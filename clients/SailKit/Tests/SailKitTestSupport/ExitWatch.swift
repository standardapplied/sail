import Foundation

/// Says who ends a test process early. A suite's process that exits while its `main` still runs
/// (a call of `exit`, AppKit's `terminate`, which exits) writes the stack that asked to standard
/// error, which the suite's log keeps; the runner's own end, after `main` returned, says nothing.
public enum ExitWatch {
    private static let installed: Bool = {
        atexit { ExitWatch.tell() }
        return true
    }()

    /// Watches this process's exit, once however often it is asked.
    public static func install() {
        _ = installed
    }

    private static func tell() {
        let stack = Thread.callStackSymbols
        guard stack.contains(where: { $0.contains(" main + ") }) else { return }
        let said = "exit-watch: the test process is ending while its main still runs, asked by:\n" + stack.joined(separator: "\n") + "\n"
        FileHandle.standardError.write(Data(said.utf8))
    }
}
