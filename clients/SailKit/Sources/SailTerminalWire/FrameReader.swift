/// Reads frames out of a byte stream however it is chunked: a length is checked the moment its
/// four bytes arrive, so a length outside 1 through ``FrameCodec/maxPayload`` is refused before
/// anything waits for, or allocates, the bytes it claims. After an error the stream's framing is
/// lost and its connection should be closed.
public struct FrameReader: Sendable {
    private var buffer: [UInt8] = []

    public init() {}

    /// The bytes held of a frame not yet complete.
    public var buffered: Int {
        buffer.count
    }

    /// Takes `bytes` and answers every frame they complete, in order.
    public mutating func read(_ bytes: some Sequence<UInt8>) throws(TerminalWireError) -> [Frame] {
        buffer.append(contentsOf: bytes)
        var frames: [Frame] = []
        var start = 0
        while buffer.count - start >= 4 {
            let length = Int(integer(bigEndian: buffer[start ..< start + 4]) as Int32)
            guard (1 ... FrameCodec.maxPayload).contains(length) else { throw .lengthOutOfRange(length) }
            guard buffer.count - start - 4 >= length else { break }
            frames.append(try FrameCodec.decode(payload: buffer[start + 4 ..< start + 4 + length]))
            start += 4 + length
        }
        buffer.removeFirst(start)
        return frames
    }
}
