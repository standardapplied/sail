import SailTerminal

/// The view's latency, on the host clock in seconds (`CACurrentMediaTime`, which key event and
/// drawable timestamps share): key event to bytes written; output (the moment the session's screen
/// took it) to the frame that draws it, committed and then on the display; key event to the frame
/// that shows its echo, a key's echo being the first output after its bytes were written. Output is
/// drawn by the frame whose read of the screen came after it; output read by a frame that drew just
/// before it was told is put to that frame, which did show it.
///
/// A frame the display reports a presentation time for is measured on the display. A frame it
/// reports none for (a dropped frame, or a virtual display) is not: its time on the display is only
/// estimated, as the refresh it was drawn for, and kept apart from the measured figures.
public struct LatencyMarks: Sendable {
    public static let capacity = 4096

    private struct Output: Sendable {
        let time: Double
        var keys: [Double]
    }

    private var awaitingEcho: [(event: Double, written: Double)] = []
    private var pending: [Output] = []
    private var committed: [UInt64: (time: Double, outputs: [Output])] = [:]
    private var targets: [UInt64: Double] = [:]
    private var presentedAt: [UInt64: (time: Double, reported: Bool)] = [:]
    private var lastFrame: (number: UInt64, committed: Double)?
    private var lastCallback = -Double.infinity

    private var keyToWritten: [Double] = []
    private var outputToCommitted: [Double] = []
    private var outputToPresented: [Double] = []
    private var outputToPresentedEstimated: [Double] = []
    private var outputToShown: [Double] = []
    private var keyToEcho: [Double] = []
    private var keyToEchoEstimated: [Double] = []
    private var refreshes: [Double] = []
    private var late = 0
    private var outputs = 0
    private var reported = 0
    private var unreported = 0

    public init() {}

    /// A key that happened at `event` had its bytes written at `written`.
    public mutating func keyWritten(event: Double, written: Double) {
        Self.keep(written - event, in: &keyToWritten)
        awaitingEcho.append((event, written))
        if awaitingEcho.count > Self.capacity { awaitingEcho.removeFirst() }
    }

    /// The session's screen took output at `time`.
    public mutating func output(at time: Double) {
        outputs += 1
        let echoed = awaitingEcho.filter { $0.written <= time }.map(\.event)
        awaitingEcho.removeAll { $0.written <= time }
        pending.append(Output(time: time, keys: echoed))
        if pending.count > Self.capacity { pending.removeFirst() }
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
        lastFrame = (frame, time)
        if let presented = presentedAt[frame] { settle(frame, presented) }
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

    /// `frame` reached the display at `time`; zero when the display reported no time.
    public mutating func presented(frame: UInt64, at time: Double) {
        let presented: (time: Double, reported: Bool)
        if time > 0 {
            reported += 1
            presented = (time, true)
        } else {
            unreported += 1
            presented = (targets[frame] ?? 0, false)
        }
        presentedAt[frame] = presented
        presentedAt = presentedAt.filter { $0.key + 8 > frame }
        settle(frame, presented)
    }

    /// How long one refresh took, reading the screen to committing the frame or pausing.
    public mutating func refreshed(taking seconds: Double) {
        Self.keep(seconds, in: &refreshes)
    }

    public var figures: LatencyFigures {
        LatencyFigures(
            keyToWritten: Self.percentiles(keyToWritten), outputToCommitted: Self.percentiles(outputToCommitted),
            outputToPresented: Self.percentiles(outputToPresented), outputToPresentedEstimated: Self.percentiles(outputToPresentedEstimated),
            outputToShown: Self.percentiles(outputToShown), keyToEcho: Self.percentiles(keyToEcho),
            keyToEchoEstimated: Self.percentiles(keyToEchoEstimated), refresh: Self.percentiles(refreshes), outputs: outputs,
            lateOutputs: late, reportedPresents: reported, unreportedPresents: unreported
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
        for output in drawn.outputs {
            Self.keep((presented.reported ? presented.time : drawn.time) - output.time, in: &outputToShown)
            guard presented.time > 0 else { continue }
            let echoes = output.keys.map { presented.time - $0 }
            if presented.reported {
                Self.keep(presented.time - output.time, in: &outputToPresented)
                echoes.forEach { Self.keep($0, in: &keyToEcho) }
            } else {
                Self.keep(presented.time - output.time, in: &outputToPresentedEstimated)
                echoes.forEach { Self.keep($0, in: &keyToEchoEstimated) }
            }
        }
    }

    private static func keep(_ seconds: Double, in samples: inout [Double]) {
        guard seconds.isFinite, seconds >= 0 else { return }
        samples.append(seconds)
        if samples.count > capacity { samples.removeFirst() }
    }

    private static func percentiles(_ seconds: [Double]) -> Percentiles {
        Percentiles(seconds.map { Duration.seconds($0) })
    }
}

/// The view's latency figures, in milliseconds. `outputToPresented` and `keyToEcho` count only
/// frames the display reported a presentation time for; the `…Estimated` figures are the others,
/// at the refresh they were drawn for.
public struct LatencyFigures: Equatable, Sendable, Codable {
    public var keyToWritten: Percentiles
    public var outputToCommitted: Percentiles
    public var outputToPresented: Percentiles
    public var outputToPresentedEstimated: Percentiles
    /// Each output to the frame that showed it: on the display when it reported the time, else
    /// committed. Its maximum is the longest any output waited to be shown.
    public var outputToShown: Percentiles
    public var keyToEcho: Percentiles
    public var keyToEchoEstimated: Percentiles
    /// One refresh's time on the main thread.
    public var refresh: Percentiles
    /// Outputs told so far.
    public var outputs: Int
    /// Output a refresh saw and could not draw, drawn by a later one.
    public var lateOutputs: Int
    public var reportedPresents: Int
    public var unreportedPresents: Int
}
