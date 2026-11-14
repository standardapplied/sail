import SailTerminal

/// The view's latency, on the host clock in seconds (`CACurrentMediaTime`, which key event and
/// drawable timestamps share): key event to bytes written; output to the frame that draws it,
/// committed and then on glass; key event to the frame that shows its echo. A key's echo is the
/// first output after its bytes were written. Output is drawn by the frame whose read of the
/// screen came after the output's tick; output read by a frame that drew before its tick was seen
/// is put to that frame, which did show it.
public struct LatencyMarks: Sendable {
    public static let capacity = 4096

    private struct Output: Sendable {
        let time: Double
        var keys: [Double]
    }

    private var awaitingEcho: [(event: Double, written: Double)] = []
    private var pending: [Output] = []
    private var committed: [UInt64: [Output]] = [:]
    private var presentedAt: [UInt64: Double] = [:]
    private var lastFrame: UInt64?
    private var lastCallback = -Double.infinity
    private var lastOutputPresented: Double?

    private var keyToWritten: [Double] = []
    private var outputToCommitted: [Double] = []
    private var outputToPresented: [Double] = []
    private var keyToEcho: [Double] = []
    private var presentGaps: [Double] = []
    private var late = 0
    private var dropped = 0

    public init() {}

    /// A key that happened at `event` had its bytes written at `written`.
    public mutating func keyWritten(event: Double, written: Double) {
        Self.keep(written - event, in: &keyToWritten)
        awaitingEcho.append((event, written))
        if awaitingEcho.count > Self.capacity { awaitingEcho.removeFirst() }
    }

    /// The session's screen took output, seen at `time`.
    public mutating func output(at time: Double) {
        let echoed = awaitingEcho.filter { $0.written <= time }.map(\.event)
        awaitingEcho.removeAll { $0.written <= time }
        pending.append(Output(time: time, keys: echoed))
        if pending.count > Self.capacity { pending.removeFirst() }
    }

    /// A display callback that read the screen at `read` drew `frame`, committed at `time`.
    public mutating func committed(frame: UInt64, at time: Double, read: Double) {
        let drawn = take(through: read)
        for output in drawn {
            Self.keep(time - output.time, in: &outputToCommitted)
        }
        committed[frame, default: []] += drawn
        lastFrame = frame
        if let presented = presentedAt[frame] { settle(frame, presented: presented) }
    }

    /// A display callback that read the screen at `read` found nothing to draw: output seen
    /// before then was in the last frame drawn.
    public mutating func unchanged(read: Double) {
        let drawn = take(through: read)
        guard let lastFrame, !drawn.isEmpty else { return }
        committed[lastFrame, default: []] += drawn
        if let presented = presentedAt[lastFrame] { settle(lastFrame, presented: presented) }
    }

    /// A display callback at `read` could not draw (every buffer in flight): output seen before it
    /// is late when a later callback draws it.
    public mutating func skipped(read: Double) {
        lastCallback = read
    }

    /// `frame` reached the display at `time`; zero when it was dropped.
    public mutating func presented(frame: UInt64, at time: Double) {
        guard time > 0 else {
            dropped += 1
            committed[frame] = nil
            return
        }
        presentedAt[frame] = time
        presentedAt = presentedAt.filter { $0.key + 8 > frame }
        settle(frame, presented: time)
    }

    public var figures: LatencyFigures {
        LatencyFigures(
            keyToWritten: Self.percentiles(keyToWritten), outputToCommitted: Self.percentiles(outputToCommitted),
            outputToPresented: Self.percentiles(outputToPresented), keyToEcho: Self.percentiles(keyToEcho),
            largestPresentGap: (presentGaps.max() ?? 0) * 1000, lateOutputs: late, droppedFrames: dropped
        )
    }

    private mutating func take(through read: Double) -> [Output] {
        let drawn = pending.filter { $0.time <= read }
        pending.removeAll { $0.time <= read }
        late += drawn.filter { $0.time < lastCallback }.count
        lastCallback = read
        return drawn
    }

    private mutating func settle(_ frame: UInt64, presented: Double) {
        guard let outputs = committed.removeValue(forKey: frame), let earliest = outputs.map(\.time).min() else { return }
        for output in outputs {
            Self.keep(presented - output.time, in: &outputToPresented)
            for key in output.keys {
                Self.keep(presented - key, in: &keyToEcho)
            }
        }
        if let previous = lastOutputPresented, earliest < previous {
            Self.keep(presented - previous, in: &presentGaps)
        }
        lastOutputPresented = max(lastOutputPresented ?? presented, presented)
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

/// The view's latency figures, in milliseconds.
public struct LatencyFigures: Equatable, Sendable, Codable {
    public var keyToWritten: Percentiles
    public var outputToCommitted: Percentiles
    public var outputToPresented: Percentiles
    public var keyToEcho: Percentiles
    /// The longest time between two frames that drew output while more output was waiting.
    public var largestPresentGap: Double
    /// Output a display callback saw and could not draw, drawn by a later one.
    public var lateOutputs: Int
    public var droppedFrames: Int
}
