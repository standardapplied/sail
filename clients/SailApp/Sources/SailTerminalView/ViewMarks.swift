import Foundation
import os
import QuartzCore
import SailSurface
import Synchronization

/// The view's latency marks, taken from the main thread, the input queue and Metal's handlers, and
/// the same moments as signposts (subsystem `sh.standardapplied.sail`, category `terminal`), so
/// Instruments shows key → bytes written and frame committed → on glass on the owner's Mac.
/// Only times are kept, never what was typed or shown.
final class ViewMarks: Sendable {
    static let signposter = OSSignposter(subsystem: "sh.standardapplied.sail", category: "terminal")

    private let marks = Mutex(LatencyMarks())
    private let onGlass = Mutex<[UInt64: OSSignpostIntervalState]>([:])

    var figures: LatencyFigures {
        marks.withLock { $0.figures }
    }

    func reset() {
        marks.withLock { $0 = LatencyMarks() }
    }

    func keyBegan() -> OSSignpostIntervalState {
        Self.signposter.beginInterval("key to bytes written")
    }

    func keyWritten(event: Double, interval: OSSignpostIntervalState) {
        Self.signposter.endInterval("key to bytes written", interval)
        let written = CACurrentMediaTime()
        marks.withLock { $0.keyWritten(event: event, written: written) }
    }

    func output() {
        Self.signposter.emitEvent("screen updated")
        let now = CACurrentMediaTime()
        marks.withLock { $0.output(at: now) }
    }

    func committed(frame: UInt64, read: Double) {
        let now = CACurrentMediaTime()
        let interval = Self.signposter.beginInterval("frame committed to presented")
        onGlass.withLock { open in
            open[frame] = interval
            open = open.filter { $0.key + 64 > frame }
        }
        marks.withLock { $0.committed(frame: frame, at: now, read: read) }
    }

    func unchanged(read: Double) {
        marks.withLock { $0.unchanged(read: read) }
    }

    func skipped(read: Double) {
        marks.withLock { $0.skipped(read: read) }
    }

    func presented(frame: UInt64, at time: Double) {
        if let interval = onGlass.withLock({ $0.removeValue(forKey: frame) }) {
            Self.signposter.endInterval("frame committed to presented", interval)
        }
        marks.withLock { $0.presented(frame: frame, at: time) }
    }
}
