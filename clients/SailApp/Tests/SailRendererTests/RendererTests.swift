import CryptoKit
import Foundation
import Metal
@testable import SailRenderer
import SailLook
import SailSurface
import SailVT
import Testing

@Suite struct RendererTests {
    /// Scenario 2 as drawn: at 10, 15 and 32 pt a box's top edge is one unbroken line of the
    /// foreground across all three cells, from the corner's arm to the other corner's.
    @Test(arguments: [10.0, 15, 32])
    func boxLinesMeetAcrossCells(points: Double) throws {
        let renderer = try makeRenderer(points: points)
        let screen = try makeScreen(3, 3, "┌─┐\r\n│ │\r\n└─┘\u{1b}[?25l")
        let image = try renderer.image(of: screen)
        let cell = renderer.metrics
        let row = cell.padding.height + (cell.height - cell.stroke) / 2
        let first = cell.padding.width + (cell.width - cell.stroke) / 2
        let last = cell.padding.width + 2 * cell.width + (cell.width + cell.stroke) / 2 - 1
        let ink = Palette.dark.foreground
        for x in first ... last {
            let (r, g, b) = image.rgb(x, row)
            #expect((r, g, b) == (Int(ink.red), Int(ink.green), Int(ink.blue)), "a gap at x \(x) of \(first)...\(last) at \(points) pt")
        }
        image.write("box-edges-\(Int(points))pt")
    }

    /// Scenario 3: a wide CJK character covers both its cells; an emoji comes from the colour
    /// atlas and spans two cells in colour.
    @Test func wideCharactersSpanTwoCellsAndEmojiAreInColour() throws {
        let renderer = try makeRenderer()
        let screen = try makeScreen(6, 1, "漢😀\u{1b}[?25l")
        let image = try renderer.image(of: screen)
        image.write("wide-and-emoji")
        let cell = renderer.metrics
        let background = Palette.dark.background
        func inked(columns: ClosedRange<Int>) -> Bool {
            let xs = (cell.padding.width + columns.lowerBound * cell.width) ..< (cell.padding.width + (columns.upperBound + 1) * cell.width)
            return xs.contains { x in
                (cell.padding.height ..< cell.padding.height + cell.height).contains { y in
                    let (r, g, b) = image.rgb(x, y)
                    return (r, g, b) != (Int(background.red), Int(background.green), Int(background.blue))
                }
            }
        }
        #expect(inked(columns: 0 ... 0) && inked(columns: 1 ... 1), "漢 inks both its cells")
        #expect(inked(columns: 2 ... 2) && inked(columns: 3 ... 3), "😀 inks both its cells")
        let emojiPixels = (cell.padding.width + 2 * cell.width ..< cell.padding.width + 4 * cell.width).flatMap { x in
            (cell.padding.height ..< cell.padding.height + cell.height).map { image.rgb(x, $0) }
        }
        #expect(emojiPixels.contains { abs($0.0 - $0.2) > 60 || abs($0.1 - $0.2) > 60 }, "the emoji is drawn in colour")
        #expect(renderer.atlasUploadBytes > 0)
    }

    /// Scenario 5 and V5: with the atlas full of a frame still in flight (held by an event the
    /// GPU waits on), the next frame's new glyphs take only slots that frame does not use, and the
    /// frame in flight is drawn exactly as it would be alone.
    @Test(.timeLimit(.minutes(1))) func aFrameInFlightKeepsItsGlyphsWhenTheAtlasIsFull() async throws {
        let sides = AtlasSides(initial: 128, largest: 128)
        let renderer = try makeRenderer(sides: sides)
        let cell = renderer.metrics
        let slots = (128 / cell.height) * (128 / cell.width)
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        try #require(slots + 8 <= alphabet.count)
        let frameA = try makeScreen(40, 2, String(alphabet[0 ..< slots - 4]) + "\u{1b}[?25l").frame()
        let frameB = try makeScreen(40, 2, String(alphabet[slots ..< slots + 8]) + "\u{1b}[?25l").frame()
        let size = renderer.pixels(of: frameA.size)
        let gate = try #require(renderer.device.makeSharedEvent())
        let (doneA, readbackA) = try held(renderer, frameA, size, gate: gate)
        let (doneB, _) = try held(renderer, frameB, size, gate: nil)
        #expect(renderer.unplacedGlyphs == 4, "the atlas had four free slots for the second frame's eight new glyphs")
        gate.signaledValue = 1
        await doneA.value
        await doneB.value
        let drawnInFlight = RenderedImage(
            width: size.width, height: size.height,
            bgra: [UInt8](UnsafeRawBufferPointer(start: readbackA.contents(), count: size.width * size.height * 4))
        )
        let alone = try makeRenderer(sides: sides).image(of: frameA, overlay: Overlay(), size: size)
        drawnInFlight.write("in-flight")
        alone.write("in-flight-alone")
        #expect(drawnInFlight.difference(from: alone, tolerance: 0).pixels == 0, "the frame in flight changed")
    }

    /// V5 for instance memory: with three frames in flight no fourth frame is written, and one
    /// is once they completed.
    @Test(.timeLimit(.minutes(1))) func aFourthFrameWaitsForABuffer() async throws {
        let renderer = try makeRenderer()
        let frame = try makeScreen(10, 2, "abc").frame()
        let size = renderer.pixels(of: frame.size)
        let gate = try #require(renderer.device.makeSharedEvent())
        var done: [Task<Void, Never>] = []
        for _ in 0 ..< 3 {
            done.append(try held(renderer, frame, size, gate: gate).done)
        }
        #expect(throws: RenderError.self) { try renderer.submitOffscreen(frame, overlay: Overlay(), size: size) }
        gate.signaledValue = 1
        for task in done { await task.value }
        #expect(throws: Never.self) { _ = try renderer.image(of: frame, overlay: Overlay(), size: size) }
    }

    /// Submits `frame`, held until the test signals `gate` from the CPU when given; the task ends
    /// once its buffer is free again.
    private func held(
        _ renderer: TerminalRenderer, _ frame: ScreenFrame, _ size: PixelSize, gate: (any MTLEvent)?
    ) throws -> (done: Task<Void, Never>, readback: any MTLBuffer) {
        let (stream, finished) = AsyncStream.makeStream(of: Void.self)
        let (_, readback) = try renderer.submitOffscreen(frame, overlay: Overlay(), size: size, wait: gate.map { ($0, 1) }) {
            finished.finish()
        }
        return (Task { for await _ in stream {} }, readback)
    }

    @Test func theShippedFontsAreThePinnedOnes() throws {
        let pins = [
            "JetBrainsMono-Regular.ttf": "a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f",
            "JetBrainsMono-Bold.ttf": "5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb",
            "JetBrainsMono-Italic.ttf": "9d0a1f7a708e6af183f1193b7e81d40da294f5c67682c085d8401c60aac8ded4",
            "JetBrainsMono-BoldItalic.ttf": "4039d5ce0ed225bf9c8b2c8c6436290ae2f356b7e90d70fa666227238324aa3b",
            "OFL.txt": "30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad",
        ]
        for (file, pin) in pins {
            let digest = SHA256.hash(data: try Data(contentsOf: Paths.fonts.appending(path: file)))
            #expect(digest.map { String(format: "%02x", $0) }.joined() == pin, "\(file) is not the pinned JetBrains Mono 2.304 file")
        }
        let faces = try TerminalFonts(directory: Paths.fonts).faces(points: 15, scale: 2)
        print("JetBrains Mono 15 pt at scale 2: cell \(faces.metrics.width)x\(faces.metrics.height), baseline \(faces.metrics.baseline)")
        #expect(faces.metrics.width > 0 && faces.metrics.height > faces.metrics.width)
    }
}
