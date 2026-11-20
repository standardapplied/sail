import Foundation
#if canImport(AppKit)
import AppKit
#endif

/// Says who ends a test process early. A suite's process that exits while its `main` still runs
/// (a call of `exit`, AppKit's `terminate`, which exits, or the main run loop stopped under Swift's
/// asynchronous `main`, which then exits) writes the stack that asked to standard error, which the
/// suite's log keeps; the runner's own end says nothing. On a Mac, whatever stops or ends the app
/// (`NSApplication`'s `stop(_:)`, `terminate(_:)`, `abortModal()`, `stopModal()`,
/// `stopModal(withCode:)`) is written too, with its stack.
public enum ExitWatch {
    private static let installed: Bool = {
        atexit { ExitWatch.tell() }
        watchExceptions()
        #if canImport(AppKit)
        let watched: [(Selector, Selector)] = [
            (#selector(NSApplication.stop(_:)), #selector(NSApplication.exitWatchStop(_:))),
            (#selector(NSApplication.terminate(_:)), #selector(NSApplication.exitWatchTerminate(_:))),
            (#selector(NSApplication.abortModal), #selector(NSApplication.exitWatchAbortModal)),
            (#selector(NSApplication.stopModal as (NSApplication) -> () -> Void), #selector(NSApplication.exitWatchStopModalWithoutCode)),
            (#selector(NSApplication.stopModal(withCode:)), #selector(NSApplication.exitWatchStopModal(withCode:))),
        ]
        for (original, watch) in watched {
            if let one = class_getInstanceMethod(NSApplication.self, original), let other = class_getInstanceMethod(NSApplication.self, watch) {
                method_exchangeImplementations(one, other)
            }
        }
        #endif
        return true
    }()

    /// Watches this process's exit, once however often it is asked.
    public static func install() {
        _ = installed
    }

    private static let exceptions: Bool = {
        #if canImport(AppKit)
        let reported: [(Selector, Selector)] = [
            (#selector(NSApplication.reportException(_:)), #selector(NSApplication.exitWatchReport(_:))),
            (NSSelectorFromString("_crashOnException:"), #selector(NSApplication.exitWatchCrash(on:))),
        ]
        for (original, watch) in reported {
            if let one = class_getInstanceMethod(NSApplication.self, original), let other = class_getInstanceMethod(NSApplication.self, watch) {
                method_exchangeImplementations(one, other)
            }
        }
        #endif
        return true
    }()

    /// Writes every exception AppKit reports or ends the app on (a window asking for another layout
    /// pass from inside one, say) with its reason and its stack, which names the view that asked:
    /// the tests' and the drive's logs say it before the process goes.
    public static func watchExceptions() {
        _ = exceptions
    }

    public static func say(_ what: String, _ stack: [String] = []) {
        let asked = stack.isEmpty ? "" : ", asked by:\n" + stack.joined(separator: "\n")
        FileHandle.standardError.write(Data(("exit-watch: \(what)" + asked + "\n").utf8))
    }

    private static func tell() {
        let stack = Thread.callStackSymbols
        guard stack.contains(where: { $0.contains(" main + ") }) else { return }
        say("the test process is ending while its main still runs", stack)
    }
}

#if canImport(AppKit)
/// Once the watch is installed, each of these is said with its stack, then done as AppKit does it
/// (the exchanged implementations: calling the watch's own name calls AppKit's).
extension NSApplication {
    @objc func exitWatchStop(_ sender: Any?) {
        ExitWatch.say("NSApplication.stop(_:), which stops the main run loop", Thread.callStackSymbols)
        exitWatchStop(sender)
    }

    @objc func exitWatchTerminate(_ sender: Any?) {
        ExitWatch.say("NSApplication.terminate(_:)", Thread.callStackSymbols)
        exitWatchTerminate(sender)
    }

    @objc func exitWatchAbortModal() {
        ExitWatch.say("NSApplication.abortModal()", Thread.callStackSymbols)
        exitWatchAbortModal()
    }

    @objc func exitWatchStopModalWithoutCode() {
        ExitWatch.say("NSApplication.stopModal()", Thread.callStackSymbols)
        exitWatchStopModalWithoutCode()
    }

    @objc func exitWatchReport(_ exception: NSException) {
        ExitWatch.say("AppKit reports \(exception.name.rawValue): \(exception.reason ?? "")", exception.callStackSymbols)
        exitWatchReport(exception)
    }

    @objc func exitWatchCrash(on exception: NSException) {
        ExitWatch.say("AppKit ends the app on \(exception.name.rawValue): \(exception.reason ?? "")", exception.callStackSymbols)
        exitWatchCrash(on: exception)
    }

    @objc func exitWatchStopModal(withCode code: NSApplication.ModalResponse) {
        ExitWatch.say("NSApplication.stopModal(withCode:)", Thread.callStackSymbols)
        exitWatchStopModal(withCode: code)
    }
}
#endif
