import CoreGraphics
import Foundation
import ImageIO

/// A frame read back from the GPU: BGRA, eight bits a channel, sRGB, rows from the top.
public struct RenderedImage: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public let bgra: [UInt8]

    public init(width: Int, height: Int, bgra: [UInt8]) {
        self.width = width
        self.height = height
        self.bgra = bgra
    }

    /// The image as a PNG tagged sRGB.
    public func png() -> Data? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB), let provider = CGDataProvider(data: Data(bgra) as CFData),
              let image = CGImage(
                  width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: space,
                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
              )
        else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, "public.png" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    /// The image a PNG holds, drawn into sRGB BGRA.
    public init?(png: Data) {
        guard let source = CGImageSourceCreateWithData(png as CFData, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
              let space = CGColorSpace(name: CGColorSpace.sRGB)
        else { return nil }
        var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let drawn = bytes.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(
                data: raw.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: image.width * 4,
                space: space, bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            return true
        }
        guard drawn else { return nil }
        self.init(width: image.width, height: image.height, bgra: bytes)
    }

    /// How this image differs from `expected`: the pixels any of whose channels differ by more
    /// than `tolerance`, and an image of them (red where they differ, a faint copy of the expected
    /// elsewhere). Images of different sizes differ everywhere.
    public func difference(from expected: RenderedImage, tolerance: UInt8) -> (pixels: Int, of: Int, image: RenderedImage) {
        guard width == expected.width, height == expected.height else {
            return (max(width * height, expected.width * expected.height), max(width * height, 1), self)
        }
        var differing = 0
        var picture = [UInt8](repeating: 0, count: bgra.count)
        for pixel in 0 ..< width * height {
            let base = pixel * 4
            var worst: UInt8 = 0
            for channel in 0 ..< 4 {
                let a = bgra[base + channel]
                let b = expected.bgra[base + channel]
                worst = max(worst, a > b ? a - b : b - a)
            }
            if worst > tolerance {
                differing += 1
                picture[base ..< base + 4] = [0, 0, 255, 255]
            } else {
                let faint = UInt8(expected.bgra[base ..< base + 3].map(Int.init).reduce(0, +) / 12)
                picture[base ..< base + 4] = [faint, faint, faint, 255]
            }
        }
        return (differing, width * height, RenderedImage(width: width, height: height, bgra: picture))
    }
}
