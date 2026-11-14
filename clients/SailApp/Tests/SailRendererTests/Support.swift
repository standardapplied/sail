import Foundation
import Metal
@testable import SailRenderer
import SailLook
import SailSurface
import SailVT

/// Where the package's fonts and goldens are, and where a run leaves its images: `SAIL_FRAMES_OUT`
/// when set (CI uploads it), else `.build/frames`.
enum Paths {
    static let package = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    static let fonts = package.appending(path: "Fonts")
    static let goldens = package.appending(path: "Tests/Goldens/frames")

    static var out: URL {
        let directory = ProcessInfo.processInfo.environment["SAIL_FRAMES_OUT"].map { URL(filePath: $0) }
            ?? package.appending(path: ".build/frames")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// Goldens are recorded, never compared, when a run is asked to (`workflow_dispatch` only).
    static var recording: Bool {
        ProcessInfo.processInfo.environment["SAIL_RECORD_GOLDENS"] == "1"
    }
}

struct TestError: Error, CustomStringConvertible {
    let description: String
}

func device() throws -> any MTLDevice {
    guard let device = MTLCreateSystemDefaultDevice() else { throw TestError(description: "this Mac has no Metal device") }
    return device
}

func makeRenderer(points: Double = 15, scale: Double = 2, sides: AtlasSides = AtlasSides()) throws -> TerminalRenderer {
    try TerminalRenderer(device: device(), fonts: TerminalFonts(directory: Paths.fonts), points: points, scale: scale, sides: sides)
}

func makeScreen(_ columns: Int, _ rows: Int, palette: Palette = .dark, _ text: String = "") throws -> Screen {
    let screen = try Screen(size: GridSize(columns: columns, rows: rows), scrollbackBytes: 1 << 20, palette: palette)
    _ = screen.feed(Array(text.utf8))
    return screen
}

extension TerminalRenderer {
    /// The pixels a grid of `size` fills, padding included.
    func pixels(of size: GridSize) -> PixelSize {
        PixelSize(
            width: 2 * metrics.padding.width + size.columns * metrics.width, height: 2 * metrics.padding.height + size.rows * metrics.height
        )
    }

    func image(of screen: Screen, overlay: Overlay = Overlay()) throws -> RenderedImage {
        let frame = try screen.frame()
        return try image(of: frame, overlay: overlay, size: pixels(of: frame.size))
    }
}

extension RenderedImage {
    /// The pixel at `x`, `y` as red, green, blue.
    func rgb(_ x: Int, _ y: Int) -> (Int, Int, Int) {
        let base = (y * width + x) * 4
        return (Int(bgra[base + 2]), Int(bgra[base + 1]), Int(bgra[base]))
    }

    func write(_ name: String) {
        if let png = png() { try? png.write(to: Paths.out.appending(path: name + ".png")) }
    }
}
