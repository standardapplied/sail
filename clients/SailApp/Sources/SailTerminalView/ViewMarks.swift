import Foundation
import os
import QuartzCore
import SailRenderer
import SailSurface
import Synchronization

/// The view's latency marks, taken from the main thread, the input queue and Metal's handlers, and
/// two of the spec's three signpost intervals (subsystem `sh.standardapplied.sail`, category
/// `terminal`): key to bytes written, and screen updated to presented, which begins as the view is
/// told of the output; the session signs the third, output read to screen updated. Every interval
/// that begins ends. Only times are kept, never what was typed or shown.
final class ViewMarks: Sendable {
    static let signposter = OSSignposter(subsystem: "sh.standardapplied.sail", category: "terminal")

    private let marks = Mutex(LatencyMarks())
    private let shown = Mutex(Showing())

    /// Screen-updated intervals waiting for their output to reach the display.
    private struct Showing {
        var pending: [(time: Double, interval: OSSignpostIntervalState)] = []
        var byFrame: [UInt64: [OSSignpostIntervalState]] = [:]
        var presented: Set<UInt64> = []
        var lastFrame: UInt64?
    }

    var figures: LatencyFigures {
        let now = CACurrentMediaTime()
        return marks.withLock { (marks: inout LatencyMarks) -> LatencyFigures in
            var figures = marks.figures
            figures.unpresented = marks.unpresented(at: now)
            return figures
        }
    }

    func fetchStarted() {
        let now = CACurrentMediaTime()
        marks.withLock { $0.fetchStarted(at: now) }
    }

    func fetchEnded() {
        let now = CACurrentMediaTime()
        marks.withLock { $0.fetchEnded(at: now) }
    }

    func completed(frame: UInt64, ok: Bool) {
        let now = CACurrentMediaTime()
        marks.withLock { $0.completed(frame: frame, at: now, ok: ok) }
    }

    func reset() {
        marks.withLock { $0 = LatencyMarks() }
    }

    func keyBegan() -> OSSignpostIntervalState {
        Self.signposter.beginInterval("key to bytes written")
    }

    func keyWritten(event: Double, sent: Double, interval: OSSignpostIntervalState) {
        Self.signposter.endInterval("key to bytes written", interval)
        let written = CACurrentMediaTime()
        marks.withLock { $0.keyWritten(event: event, sent: sent, written: written) }
    }

    /// The session's screen took output at `time` on the host clock.
    func output(at time: Double) {
        let interval = Self.signposter.beginInterval("screen updated to presented")
        shown.withLock { showing in
            showing.pending.append((time, interval))
            if showing.pending.count > 256 { Self.end(showing.pending.removeFirst().interval) }
        }
        marks.withLock { $0.output(at: time) }
    }

    /// The main thread took the view's wake for `changes` changes, the oldest at `since`, now; the
    /// session's thread began to hand it over at `handing` and had handed it at `handed`, nil when
    /// the queue took it first.
    func told(since: Double, handing: Double, handed: Double?, changes: Int) {
        let now = CACurrentMediaTime()
        marks.withLock { $0.told(at: now, since: since, handing: handing, handed: handed, changes: changes) }
    }

    func committed(frame: UInt64, read: Double, target: Double) {
        let now = CACurrentMediaTime()
        shown.withLock { showing in
            showing.byFrame[frame, default: []] += Self.take(&showing.pending, through: read)
            showing.lastFrame = frame
            if showing.presented.contains(frame) { Self.endAll(frame, &showing) }
        }
        marks.withLock { $0.committed(frame: frame, at: now, read: read, target: target) }
    }

    func refreshed(since read: Double, workSince cpu: Duration) {
        let now = CACurrentMediaTime()
        let work = (Duration.threadCPU - cpu) / .seconds(1)
        marks.withLock { $0.refreshed(taking: now - read, working: work) }
    }

    func unchanged(read: Double) {
        shown.withLock { showing in
            let drawn = Self.take(&showing.pending, through: read)
            guard let frame = showing.lastFrame, !showing.presented.contains(frame) else {
                drawn.forEach(Self.end)
                return
            }
            showing.byFrame[frame, default: []] += drawn
        }
        marks.withLock { $0.unchanged(read: read) }
    }

    func skipped(read: Double) {
        marks.withLock { $0.skipped(read: read) }
    }

    func presented(frame: UInt64, at time: Double) {
        shown.withLock { showing in
            showing.presented.insert(frame)
            showing.presented = showing.presented.filter { $0 + 64 > frame }
            Self.endAll(frame, &showing)
        }
        let told = CACurrentMediaTime()
        marks.withLock { $0.presented(frame: frame, at: time, told: told) }
    }

    private static func take(
        _ pending: inout [(time: Double, interval: OSSignpostIntervalState)], through read: Double
    ) -> [OSSignpostIntervalState] {
        let drawn = pending.filter { $0.time <= read }.map(\.interval)
        pending.removeAll { $0.time <= read }
        return drawn
    }

    private static func endAll(_ frame: UInt64, _ showing: inout Showing) {
        showing.byFrame.removeValue(forKey: frame)?.forEach(end)
        for stale in showing.byFrame.keys where stale + 64 <= frame {
            showing.byFrame.removeValue(forKey: stale)?.forEach(end)
        }
    }

    private static func end(_ interval: OSSignpostIntervalState) {
        signposter.endInterval("screen updated to presented", interval)
    }
}
