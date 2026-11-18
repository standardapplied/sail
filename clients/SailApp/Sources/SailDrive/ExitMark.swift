import Darwin
import Foundation

/// The file a clean exit leaves, as a C string the runtime's exit handler can open.
private nonisolated(unsafe) var exitMark: UnsafeMutablePointer<CChar>?

/// A file the C runtime writes as the process exits cleanly (`exit`, which AppKit calls once it
/// has terminated), and that a crash or a hang never writes. `ci/drive` reads its absence as a
/// crash or a hang at quit, which a report written before quitting cannot show.
enum ExitMark {
    /// Has a clean exit leave `file`, removing one an earlier run left.
    static func arm(at file: URL) {
        unlink(file.path)
        exitMark = strdup(file.path)
        atexit {
            guard let path = exitMark else { return }
            _ = close(open(path, O_CREAT | O_WRONLY | O_TRUNC, 0o644))
        }
    }
}
