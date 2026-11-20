import Foundation
#if canImport(AppKit)
import AppKit
#endif

/// Says who ends a test process early. A suite's process that exits while its `main` still runs
/// (a call of `exit`, AppKit's `terminate`, which exits, or the main run loop stopped under Swift's
/// asynchronous `main`, which then exits) writes the stack that asked to standard error, which the
/// suite's log keeps; the runner's own end says nothing. On a Mac, who stops the app (`NSApplication`
/// `stop(_:)`, which stops the main run loop) is written too, with its stack.
public enum ExitWatch {
    private static let installed: Bool = {
        atexit { ExitWatch.tell() }
        #if canImport(AppKit)
        if let stop = class_getInstanceMethod(NSApplication.self, #selector(NSApplication.stop(_:))),
           let watched = class_getInstanceMethod(NSApplication.self, #selector(NSApplication.exitWatchStop(_:)))
        {
            method_exchangeImplementations(stop, watched)
        }
        #endif
        return true
    }()

    /// Watches this process's exit, once however often it is asked.
    public static func install() {
        _ = installed
    }

    static func say(_ what: String, _ stack: [String]) {
        FileHandle.standardError.write(Data(("exit-watch: \(what), asked by:\n" + stack.joined(separator: "\n") + "\n").utf8))
    }

    private static func tell() {
        let stack = Thread.callStackSymbols
        guard stack.contains(where: { $0.contains(" main + ") }) else { return }
        say("the test process is ending while its main still runs", stack)
    }
}

#if canImport(AppKit)
extension NSApplication {
    /// `stop(_:)` once the watch is installed: said with its stack, then done as AppKit does it.
    @objc func exitWatchStop(_ sender: Any?) {
        ExitWatch.say("NSApplication.stop(_:) stops the main run loop", Thread.callStackSymbols)
        exitWatchStop(sender)
    }
}
#endif
