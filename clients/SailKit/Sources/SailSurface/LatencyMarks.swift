import Foundation
import SailTerminal

/// The view's latency, on the host clock in seconds (`CACurrentMediaTime`, which key event and
/// drawable timestamps share): key event to bytes written; output (the moment the session's screen
/// took it) to the frame that draws it, committed and then on the display; key event to the frame
/// that shows its echo, a key's echo being the first output after its bytes began to go. Output is
/// drawn by the frame whose read of the screen came after it; output read by a frame that drew just
/// before it was told is put to that frame, which did show it.
///
/// A frame the display reports a presentation time for is measured on the display. Where the
/// display has reported no time yet (a virtual display), a frame counts as shown when its command
/// buffer completed, the earliest it can be presented (Apple: a drawable is presented only after
/// its command buffer completes); a frame dropped on a display that reports times counts the same
/// way. Those are kept apart from the measured figures.
public struct LatencyMarks: Sendable {
    public static let capacity = 4096

    private struct Output: Sendable {
        let time: Double
        var keys: [Double]
    }

    private var awaitingEcho: [(event: Double, sent: Double)] = []
    private var pending: [Output] = []
    private var committed: [UInt64: (time: Double, outputs: [Output])] = [:]
    private var targets: [UInt64: Double] = [:]
    private var presentedAt: [UInt64: (time: Double, reported: Bool)] = [:]
    private var lastFrame: (number: UInt64, committed: Double)?
    private var lastCallback = -Double.infinity

    private var keyWrites: [Span] = []
    private var outputToCommitted: [Double] = []
    private var outputToPresented: [Double] = []
    private var outputToPresentedEstimated: [Double] = []
    private var outputToShown: [Double] = []
    private var keyToEcho: [Double] = []
    private var keyToEchoEstimated: [Double] = []
    private var refreshes: [Double] = []
    private var refreshWork: [Double] = []
    private var late = 0
    private var screenUpdates = 0
    private var reported = 0
    private var unreported = 0
    private var presentedBeforeCommit = 0
    private var longestToShown: Span?
    private var longestToTold: Span?
    private var longestToToldHanding: Double?
    private var longestToToldHanded: Double?
    private var wakes = 0
    private var changes = 0
    private var longestBetweenCommits: Span?
    private var longestGapOutputs = 0
    private var fetches: [Span] = []
    private var fetchStarted: Double?
    private var inFlight: [UInt64: Flight] = [:]
    private var commitToCompleted: [Double] = []
    private var commitToPresentTold: [Double] = []

    /// A frame not yet told presented: when it was committed, and when its commands completed; or
    /// one told presented before its commit was marked.
    private struct Flight: Sendable {
        var committed: Double?
        var completed: (time: Double, ok: Bool)?
        var told = false
    }

    public init() {}

    /// A key that happened at `event` had its bytes begin to go at `sent` and written at `written`.
    /// Its echo may already have been told, even drawn, before the input queue got here: the key
    /// is then put to the first output since `sent` that no frame has yet shown.
    public mutating func keyWritten(event: Double, sent: Double, written: Double) {
        if (written - event).isFinite, written >= event {
            keyWrites.append(Span(event, written))
            if keyWrites.count > Self.capacity { keyWrites.removeFirst() }
        }
        let drawn = committed.keys.sorted().first { committed[$0]?.outputs.contains { $0.time >= sent } == true }
        if let drawn, let index = committed[drawn]?.outputs.firstIndex(where: { $0.time >= sent }) {
            committed[drawn]?.outputs[index].keys.append(event)
        } else if let index = pending.firstIndex(where: { $0.time >= sent }) {
            pending[index].keys.append(event)
        } else {
            awaitingEcho.append((event, sent))
            if awaitingEcho.count > Self.capacity { awaitingEcho.removeFirst() }
        }
    }

    /// The session's screen took output at `time`: a wake of the view marks the oldest change it
    /// carried, so a frame that shows it is measured from the change that waited longest, and the
    /// newest too when it carried more than one, so a key whose echo came in a later change of the
    /// same wake is put to it, not held for an unrelated later output.
    public mutating func output(at time: Double) {
        screenUpdates += 1
        let echoed = awaitingEcho.filter { $0.sent <= time }.map(\.event)
        awaitingEcho.removeAll { $0.sent <= time }
        pending.append(Output(time: time, keys: echoed))
        if pending.count > Self.capacity { pending.removeFirst() }
    }

    /// The main thread took the view's wake at `time`, for `changes` changes of the screen, the
    /// oldest at `since`, the session's thread having begun to hand it over at `handing` and handed
    /// it at `handed` (nil when the queue took it first): the hop a frame request waits on, from the
    /// session's thread to the main one.
    public mutating func told(at time: Double, since: Double, handing: Double, handed: Double?, changes: Int) {
        wakes += 1
        self.changes += changes
        let hop = Span(since, max(since, time))
        guard hop.length > longestToTold?.length ?? -1 else { return }
        longestToTold = hop
        longestToToldHanding = handing
        longestToToldHanded = handed
    }

    /// A refresh that read the screen at `read` drew `frame`, committed at `time`, for the display
    /// refresh at `target`.
    public mutating func committed(frame: UInt64, at time: Double, read: Double, target: Double) {
        targets[frame] = target
        targets = targets.filter { $0.key + 64 > frame }
        let drawn = take(through: read)
        for output in drawn {
            Self.keep(time - output.time, in: &outputToCommitted)
        }
        committed[frame] = (time, (committed[frame]?.outputs ?? []) + drawn)
        if let lastFrame, time - lastFrame.committed > longestBetweenCommits?.length ?? -1 {
            longestBetweenCommits = Span(lastFrame.committed, time)
            longestGapOutputs = drawn.filter { $0.time > lastFrame.committed }.count
        }
        lastFrame = (frame, time)
        if inFlight[frame]?.told == true {
            inFlight[frame] = nil
        } else {
            inFlight[frame, default: Flight()].committed = time
            inFlight = inFlight.filter { $0.key + 64 > frame }
        }
        if let done = inFlight[frame]?.completed { Self.keep(done.time - time, in: &commitToCompleted) }
        if let presented = presentedAt[frame] {
            presentedBeforeCommit += 1
            settle(frame, presented)
        }
    }

    /// A refresh that read the screen at `read` found nothing to draw: output told before then was
    /// in the last frame drawn.
    public mutating func unchanged(read: Double) {
        let drawn = take(through: read)
        guard let lastFrame, !drawn.isEmpty else { return }
        committed[lastFrame.number] = (lastFrame.committed, (committed[lastFrame.number]?.outputs ?? []) + drawn)
        if let presented = presentedAt[lastFrame.number] { settle(lastFrame.number, presented) }
    }

    /// A refresh at `read` could not draw: output told before it is late when a later one draws it.
    public mutating func skipped(read: Double) {
        lastCallback = read
    }

    /// `frame`'s commands completed on the GPU at `time`, `ok` unless they failed: shown then where
    /// the display has reported no presentation time. Either can be told before the commit is
    /// marked, and the frame is then settled when it is.
    public mutating func completed(frame: UInt64, at time: Double, ok: Bool) {
        if let committed = inFlight[frame]?.committed { Self.keep(time - committed, in: &commitToCompleted) }
        if inFlight[frame]?.told != true { inFlight[frame, default: Flight()].completed = (time, ok) }
        if ok, reported == 0 { show(frame, (time, false)) }
    }

    /// `frame` reached the display at `time`, Metal telling it at `told`; zero when the display
    /// reported no time, and the frame then counts as shown at its completion, if it completed.
    public mutating func presented(frame: UInt64, at time: Double, told: Double) {
        let flight = inFlight.removeValue(forKey: frame)
        if let committed = flight?.committed { Self.keep(told - committed, in: &commitToPresentTold) } else { inFlight[frame] = Flight(told: true) }
        guard time <= 0 else {
            reported += 1
            return show(frame, (time, true))
        }
        unreported += 1
        if flight?.completed?.ok != false { show(frame, (flight?.completed?.time ?? targets[frame] ?? told, false)) }
    }

    /// The frames committed and not yet told presented, oldest first, as of `now`.
    public func unpresented(at now: Double) -> [UnpresentedFrame] {
        inFlight.compactMap { frame, flight in
            flight.committed.map { committed in
                UnpresentedFrame(
                    frame: frame, ageMs: (now - committed) * 1000,
                    completedAfterMs: flight.completed.map { ($0.time - committed) * 1000 }, completedOK: flight.completed?.ok
                )
            }
        }.sorted { $0.frame < $1.frame }
    }

    private mutating func show(_ frame: UInt64, _ shown: (time: Double, reported: Bool)) {
        presentedAt[frame] = shown
        presentedAt = presentedAt.filter { $0.key + 8 > frame }
        settle(frame, shown)
    }

    /// A drawable was asked for at `time`, off the main thread.
    public mutating func fetchStarted(at time: Double) {
        fetchStarted = time
    }

    /// The drawable asked for came, or the ask gave up, at `time`.
    /// The drawable asks that overlap `span`, an ask still out included up to `now`.
    public func asks(in span: Span, now: Double) -> [Span] {
        let open = fetchStarted.map { [Span($0, now)] } ?? []
        return (fetches + open).filter { $0.overlap(span) > 0 }
    }

    public mutating func fetchEnded(at time: Double) {
        guard let started = fetchStarted else { return }
        fetchStarted = nil
        fetches.append(Span(started, time))
        if fetches.count > Self.capacity { fetches.removeFirst() }
    }

    /// How long one refresh took, reading the screen to committing the frame or pausing: on the
    /// wall clock, and the work it did on its thread's CPU clock (what a stall of the machine does
    /// not add to).
    public mutating func refreshed(taking seconds: Double, working work: Double) {
        Self.keep(seconds, in: &refreshes)
        Self.keep(work, in: &refreshWork)
    }

    public var figures: LatencyFigures {
        LatencyFigures(
            keyToWritten: Self.percentiles(keyWrites.map(\.length)), keyWrites: keyWrites, outputToCommitted: Self.percentiles(outputToCommitted),
            outputToPresented: Self.percentiles(outputToPresented), outputToPresentedEstimated: Self.percentiles(outputToPresentedEstimated),
            outputToShown: Self.percentiles(outputToShown), keyToEcho: Self.percentiles(keyToEcho),
            keyToEchoEstimated: Self.percentiles(keyToEchoEstimated), refresh: Self.percentiles(refreshes),
            refreshWork: Self.percentiles(refreshWork), screenUpdates: screenUpdates,
            lateOutputs: late, reportedPresents: reported, unreportedPresents: unreported,
            presentedBeforeCommit: presentedBeforeCommit, longestToShown: longestToShown, longestToTold: longestToTold, longestToToldHanding: longestToToldHanding,
            longestToToldHanded: longestToToldHanded, wakes: wakes, changes: changes,
            longestBetweenCommits: longestBetweenCommits, longestGapOutputs: longestGapOutputs,
            longestGapFetching: longestBetweenCommits.map { gap in fetches.reduce(0) { $0 + $1.overlap(gap) } * 1000 } ?? 0,
            commitToCompleted: Self.percentiles(commitToCompleted),
            commitToPresentTold: Self.percentiles(commitToPresentTold), unpresented: []
        )
    }

    private mutating func take(through read: Double) -> [Output] {
        let drawn = pending.filter { $0.time <= read }
        pending.removeAll { $0.time <= read }
        late += drawn.filter { $0.time < lastCallback }.count
        lastCallback = read
        return drawn
    }

    private mutating func settle(_ frame: UInt64, _ presented: (time: Double, reported: Bool)) {
        guard let drawn = committed.removeValue(forKey: frame) else { return }
        let shown = presented.time
        for output in drawn.outputs {
            let toShown = Span(output.time, shown)
            Self.keep(toShown.end - toShown.start, in: &outputToShown)
            Self.keepLongest(toShown, in: &longestToShown)
            let echoes = output.keys.map { shown - $0 }
            if presented.reported {
                Self.keep(shown - output.time, in: &outputToPresented)
                echoes.forEach { Self.keep($0, in: &keyToEcho) }
            } else {
                Self.keep(shown - output.time, in: &outputToPresentedEstimated)
                echoes.forEach { Self.keep($0, in: &keyToEchoEstimated) }
            }
        }
    }

    private static func keep(_ seconds: Double, in samples: inout [Double]) {
        guard seconds.isFinite, seconds >= 0 else { return }
        samples.append(seconds)
        if samples.count > capacity { samples.removeFirst() }
    }

    private static func keepLongest(_ span: Span, in longest: inout Span?) {
        if span.length > longest?.length ?? -1 { longest = span }
    }

    private static func percentiles(_ seconds: [Double]) -> Percentiles {
        Percentiles(seconds.map { Duration.seconds($0) })
    }
}

/// The view's latency figures, in milliseconds. `outputToPresented` and `keyToEcho` count only
/// frames the display reported a presentation time for; the `…Estimated` figures are the others,
/// at their command buffer's completion.
public struct LatencyFigures: Equatable, Sendable, Codable {
    public var keyToWritten: Percentiles
    /// Each key's time to its bytes written, in seconds on the host clock: what a tail budget
    /// judges net of the measuring process's own stalls.
    public var keyWrites: [Span]
    public var outputToCommitted: Percentiles
    public var outputToPresented: Percentiles
    public var outputToPresentedEstimated: Percentiles
    /// Each output to the frame that showed it: on the display when it reported the time, else at
    /// its completion. Its maximum is the longest any output waited to be shown.
    public var outputToShown: Percentiles
    public var keyToEcho: Percentiles
    public var keyToEchoEstimated: Percentiles
    /// One refresh's time on the main thread.
    public var refresh: Percentiles
    /// One refresh's work, on the main thread's CPU clock.
    public var refreshWork: Percentiles
    /// Screen-update ticks told so far; one tick may hold several reads of output.
    public var screenUpdates: Int
    /// Output a refresh saw and could not draw, drawn by a later one.
    public var lateOutputs: Int
    public var reportedPresents: Int
    public var unreportedPresents: Int
    /// Frames told presented or completed before their commit was marked.
    public var presentedBeforeCommit: Int
    /// When the output that waited longest to be shown was told and shown, in seconds.
    public var longestToShown: Span?
    /// When the output that took longest to reach the main thread was taken by the screen and when
    /// the main thread took the view's wake for it, in seconds: a frame is asked for no sooner.
    public var longestToTold: Span?
    /// When the session's thread began to hand the wake of ``longestToTold`` to the main queue, and
    /// when that hand-off had returned, in seconds; the second nil when the main thread took the
    /// wake first. Together they bracket the hand-off: what came before it was the reader's side,
    /// what came after it the main queue's.
    public var longestToToldHanding: Double?
    public var longestToToldHanded: Double?
    /// How many wakes the main thread took for screen changes: one at most is outstanding per view,
    /// however many changes come meanwhile.
    public var wakes: Int
    /// How many changes of the screen those wakes carried.
    public var changes: Int
    /// The longest time between two committed frames, in seconds.
    public var longestBetweenCommits: Span?
    /// How many outputs reached the screen inside that gap, and for how many ms a drawable was being
    /// asked for in it: a gap with no output was silence, not a stall.
    public var longestGapOutputs: Int
    public var longestGapFetching: Double
    /// A frame's commit to its commands completing on the GPU, and to Metal telling it presented.
    public var commitToCompleted: Percentiles
    public var commitToPresentTold: Percentiles
    /// The frames committed and not yet told presented when the figures were taken.
    public var unpresented: [UnpresentedFrame]
}

/// A committed frame Metal has not told presented: how long ago it was committed, and how long
/// after that its commands completed (nil while they have not), and whether they completed
/// without error.
public struct UnpresentedFrame: Equatable, Sendable, Codable, CustomStringConvertible {
    public var frame: UInt64
    public var ageMs: Double
    public var completedAfterMs: Double?
    public var completedOK: Bool?

    public var description: String {
        let commands = completedAfterMs.map { String(format: "its commands %@ %.1f ms after", completedOK == true ? "completed" : "failed", $0) }
            ?? "its commands not completed"
        return String(format: "frame %llu committed %.0f ms ago, ", frame, ageMs) + commands
    }

    /// The frames committed and not yet told presented, in words: listed where the display tells
    /// presentation times; where it tells none (`reported` 0, as on a CI runner's display), a frame
    /// not told presented says nothing of the display, so they are counted and that is said.
    public static func said(_ frames: [UnpresentedFrame], reported: Int, unreported: Int) -> String {
        guard reported > 0 else {
            return "the display tells no presentation time (\(unreported) presents told without one), so the \(frames.count) "
                + "frames not told presented say nothing of it"
        }
        return "not yet told presented: " + (frames.isEmpty ? "none" : frames.map(\.description).joined(separator: ", "))
    }
}

/// A stretch of time on the host clock, in seconds.
public struct Span: Equatable, Sendable, Codable {
    public var start: Double
    public var end: Double

    public init(_ start: Double, _ end: Double) {
        self.start = start
        self.end = end
    }

    public var length: Double {
        max(0, end - start)
    }

    public func overlap(_ other: Span) -> Double {
        Span(max(start, other.start), min(end, other.end)).length
    }
}
