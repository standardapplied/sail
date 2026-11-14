/// The frames handed to the GPU and the instance buffers they read. Frame `n` writes buffer
/// `n % buffers`, so a frame may begin only when the frame that last wrote its buffer has
/// completed: the CPU never writes memory a submitted command buffer still reads. Completions
/// may be reported in any order; ``completed`` is the newest frame with every frame before it
/// done.
public struct FrameLedger: Sendable {
    public let buffers: Int
    /// The newest frame begun, zero before the first.
    public private(set) var begun: UInt64 = 0
    /// The newest frame whose GPU work is done, every earlier one done too.
    public private(set) var completed: UInt64 = 0
    private var finishedAhead: Set<UInt64> = []

    public init(buffers: Int = 3) throws(SurfaceError) {
        guard buffers >= 1 else { throw .invalid("frames need a buffer at least, not \(buffers)") }
        self.buffers = buffers
    }

    /// The next frame and the buffer it writes, nil while that buffer is still read by a frame in
    /// flight: the caller draws on a later display refresh instead of waiting.
    public mutating func begin() -> (frame: UInt64, buffer: Int)? {
        let next = begun + 1
        guard next <= completed + UInt64(buffers) else { return nil }
        begun = next
        return (next, Int(next % UInt64(buffers)))
    }

    /// The GPU finished `frame`.
    public mutating func complete(_ frame: UInt64) {
        guard frame > completed, frame <= begun else { return }
        finishedAhead.insert(frame)
        while finishedAhead.remove(completed + 1) != nil {
            completed += 1
        }
    }

    /// Frames begun and not completed.
    public var inFlight: Int {
        Int(begun - completed)
    }
}
