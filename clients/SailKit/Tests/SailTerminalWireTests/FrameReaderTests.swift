import SailKitTestSupport
import SailTerminalWire
import Testing

@Suite struct FrameReaderTests {
    @Test func fiveHundredSeededChunkingsOfTheHostsFramesReadAsTheSameFramesInOrder() throws {
        let (stream, frames) = try hostStream()
        for seed in UInt64(1) ... 500 {
            #expect(try read(chunking(of: stream, seed: seed)) == frames, "seed \(seed)")
        }
    }

    @Test func theHostsFramesCutAtEveryOffsetReadAsTheSameFrames() throws {
        let (stream, frames) = try hostStream()
        for offset in 0 ... stream.count {
            #expect(try read(cut(stream, at: offset)) == frames, "cut at \(offset)")
        }
    }

    @Test func aPartFrameIsHeldUntilTheRestArrives() throws {
        let output = try golden("Output")
        var reader = FrameReader()
        #expect(try reader.read(output.dropLast()).isEmpty)
        #expect(reader.buffered == output.count - 1)
        #expect(try reader.read(output.suffix(1)) == [try decoded(output)])
        #expect(reader.buffered == 0)
    }

    @Test(arguments: [Int32(FrameCodec.maxPayload + 1), Int32.max, 0, -1, Int32.min])
    func aLengthOutsideOneToTheLimitIsRefusedOnceItsFourBytesArrive(length: Int32) throws {
        var reader = FrameReader()
        #expect(try reader.read(bigEndian(length).prefix(3)).isEmpty)
        #expect(throws: TerminalWireError.lengthOutOfRange(Int(length))) {
            try reader.read(bigEndian(length).suffix(1))
        }
    }

    @Test func framesReadBeforeABadOneAreAnsweredAndTheErrorComesOnTheNextRead() throws {
        let ended = try golden("SessionEnded")
        let broken = ended + bigEndian(Int32.max)
        let refusal = TerminalWireError.lengthOutOfRange(Int(Int32.max))
        for offset in 0 ... broken.count {
            var reader = FrameReader()
            var frames: [Frame] = []
            var failures: [TerminalWireError] = []
            for chunk in cut(broken, at: offset) + [[]] {
                do {
                    frames += try reader.read(chunk)
                } catch {
                    failures.append(error)
                }
            }
            #expect(frames == [try decoded(ended)], "cut at \(offset)")
            #expect(failures.first == refusal, "cut at \(offset)")
            #expect(throws: refusal) { try reader.read(ended) }
            #expect(reader.buffered == 0)
        }
    }

    @Test func aFrameThatDoesNotDecodeAfterGoodOnesIsRaisedOnTheNextReadToo() throws {
        let ended = try golden("SessionEnded")
        var reader = FrameReader()
        #expect(try reader.read(ended + lengthPrefixed([99])) == [try decoded(ended)])
        #expect(throws: TerminalWireError.unknownType(99)) { try reader.read([]) }
    }

    @Test func aFrameAtTheLimitIsRead() throws {
        let largest = Frame.input(sequence: 1, bytes: Array(repeating: 7, count: FrameCodec.maxPayload - 13))
        var reader = FrameReader()
        #expect(try reader.read(FrameCodec.encode(largest)) == [largest])
    }

    @Test func aFrameWhoseFieldsDisagreeWithItsLengthIsAnError() throws {
        var reader = FrameReader()
        #expect(throws: TerminalWireError.truncated) { try reader.read(lengthPrefixed([9, 0, 0, 0, 5, 65])) }
    }
}

/// Every frame the host wrote, one after another, and what each decodes to.
func hostStream() throws -> (stream: [UInt8], frames: [Frame]) {
    let frames = try Goldens.frames()
    return (frames.flatMap(\.wire), try frames.map { try decoded($0.wire) })
}

func read(_ chunks: [[UInt8]]) throws -> [Frame] {
    var reader = FrameReader()
    let frames = try chunks.flatMap { try reader.read($0) }
    #expect(reader.buffered == 0)
    return frames
}
