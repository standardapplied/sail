import Foundation
@testable import SailFiles
import SailTerminal
import SailTerminalWire
import Testing

/// A save on the wire: its write, then its bytes in chunks of at most 64 KiB with at most two
/// awaiting the host's acknowledgement, then their end with the SHA-256 the host checks.
@Suite struct FileUploadTests {
    static func sha(_ bytes: [UInt8]) -> [UInt8] {
        var hash = SHA256()
        hash.update(bytes)
        return hash.finalize()
    }

    @Test func twoChunksAwaitTheHostAndTheEndCarriesTheDigest() throws {
        let bytes = (0 ..< 200_000).map { UInt8($0 % 251) }
        var upload = FileUpload(id: 7, path: "/home/dev/workspace/tide.ts", base: "b", seen: "v1", bytes: bytes)
        let start = upload.start()
        #expect(start.first == .filesWrite(id: 7, path: "/home/dev/workspace/tide.ts", base: "b", seen: "v1", size: 200_000))
        #expect(start.dropFirst().map { if case let .putChunk(_, offset, chunk) = $0 { "\(offset)+\(chunk.count)" } else { "" } } == ["0+65536", "65536+65536"])
        #expect(upload.acked(65_536).map { if case let .putChunk(_, offset, _) = $0 { offset } else { Int64(-1) } } == [Int64(131_072)])
        let rest = upload.acked(131_072)
        guard case let .putChunk(_, offset, last)? = rest.first, case let .putEnd(id, digest)? = rest.last else {
            Issue.record("\(rest)")
            return
        }
        #expect(offset == 196_608 && last.count == 3392 && id == 7 && digest == Self.sha(bytes))
        #expect(upload.acked(200_000).isEmpty, "the end was sent twice")
    }

    @Test func anEmptyFileIsItsWriteAndItsEnd() {
        var upload = FileUpload(id: 1, path: "/home/dev/workspace/empty", base: OpenFile.absent, seen: "", bytes: [])
        #expect(upload.start() == [.filesWrite(id: 1, path: "/home/dev/workspace/empty", base: OpenFile.absent, seen: "", size: 0), .putEnd(id: 1, sha256: Self.sha([]))])
    }
}
