/// Reads frames out of a byte stream however it is chunked: a length is checked the moment its
/// four bytes arrive, so a length outside 1 through ``FrameCodec/maxPayload`` is refused before
/// anything waits for, or allocates, the bytes it claims. What the stream's reader is told never
/// depends on the chunking: the frames before a bad one are answered first, and the error is
/// raised by the read after them and by every read from then on. After an error the stream's
/// framing is lost and its connection should be closed.
public struct FrameReader: Sendable {
    private var buffer: [UInt8] = []
    private var failure: TerminalWireError?

    public init() {}

    /// The bytes held of a frame not yet complete.
    public var buffered: Int {
        buffer.count
    }

    /// Takes `bytes` and answers every frame they complete, in order.
    public mutating func read(_ bytes: some Sequence<UInt8>) throws(TerminalWireError) -> [Frame] {
        if let failure { throw failure }
        buffer.append(contentsOf: bytes)
        var frames: [Frame] = []
        var start = 0
        do throws(TerminalWireError) {
            while let payload = try payload(at: start) {
                frames.append(try FrameCodec.decode(payload: payload))
                start = payload.endIndex
            }
            buffer.removeFirst(start)
        } catch {
            buffer.removeAll()
            failure = error
            if frames.isEmpty { throw error }
        }
        return frames
    }

    /// The payload of the frame that starts at `start`, nil until all of it has arrived.
    private func payload(at start: Int) throws(TerminalWireError) -> ArraySlice<UInt8>? {
        guard buffer.count - start >= 4 else { return nil }
        let length = Int(integer(bigEndian: buffer[start ..< start + 4]) as Int32)
        guard (1 ... FrameCodec.maxPayload).contains(length) else { throw .lengthOutOfRange(length) }
        let end = start + 4 + length
        return end <= buffer.count ? buffer[start + 4 ..< end] : nil
    }
}
