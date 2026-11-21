import Foundation
import SailTerminal
import SailTerminalWire

/// A save on the wire: its write, then its bytes in chunks of at most 64 KiB (the host's bound),
/// at most two of them awaiting the host's acknowledgement, then their end with the SHA-256 the
/// host checks before anything is renamed over the file.
public struct FileUpload: Sendable {
    public static let chunk = 64 * 1024
    /// How many chunks may await the host's acknowledgement at once.
    public static let window = 2

    public let id: Int32
    private let write: Frame
    private let bytes: [UInt8]
    private var sent = 0
    private var acknowledged = 0
    private var ended = false

    public init(id: Int32, path: FileName, base: String, seen: String, bytes: [UInt8]) {
        self.id = id
        self.bytes = bytes
        write = .filesWrite(id: id, path: path, base: base, seen: seen, size: Int64(bytes.count))
    }

    /// The write and its first chunks.
    public mutating func start() -> [Frame] {
        [write] + more()
    }

    /// The host holds the first `received` bytes: the next chunks, and the end once all are sent.
    public mutating func acked(_ received: Int64) -> [Frame] {
        acknowledged = max(acknowledged, Int(received))
        return more()
    }

    private mutating func more() -> [Frame] {
        var frames: [Frame] = []
        while sent < bytes.count, sent - acknowledged < Self.window * Self.chunk {
            let end = min(sent + Self.chunk, bytes.count)
            frames.append(.putChunk(id: id, offset: Int64(sent), bytes: Array(bytes[sent ..< end])))
            sent = end
        }
        if sent == bytes.count, !ended {
            ended = true
            var hash = SHA256()
            hash.update(bytes)
            frames.append(.putEnd(id: id, sha256: hash.finalize()))
        }
        return frames
    }
}
