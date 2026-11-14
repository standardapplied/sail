import Metal
import SailLook
import SailSurface

/// How large an atlas starts and how far it grows, both in pixels a side.
struct AtlasSides: Sendable {
    var initial = 1024
    var largest = Atlas<AtlasItem>.standardLargestSide
}

/// One atlas texture and its bookkeeping. A slot's pixels are written once, when it is placed;
/// growing replaces the texture with a new one twice the side holding the old pixels, so a frame
/// in flight keeps reading the texture it was encoded with.
final class AtlasTexture {
    let format: MTLPixelFormat
    let bytesPerPixel: Int
    private let device: any MTLDevice
    private(set) var atlas: Atlas<AtlasItem>
    private(set) var texture: any MTLTexture
    /// Bumped whenever slots placed before stop being valid: the cached rows must be laid out again.
    private(set) var generation = 0
    private(set) var uploaded = 0

    init(device: any MTLDevice, cell: PixelSize, format: MTLPixelFormat, bytesPerPixel: Int, sides: AtlasSides) throws(RenderError) {
        self.device = device
        self.format = format
        self.bytesPerPixel = bytesPerPixel
        do {
            atlas = try Atlas(cell: cell, initialSide: sides.initial, largestSide: sides.largest)
        } catch {
            throw .unavailable("no atlas holds cells of \(cell.width)x\(cell.height): \(error)")
        }
        texture = try Self.texture(device, format, side: sides.initial)
    }

    /// The slot of `item` when it is in this atlas, marked used by `frame`.
    func use(_ item: AtlasItem, frame: UInt64) -> AtlasSlot? {
        atlas.use(item, frame: frame)
    }

    /// The slot of `item`, its pixels drawn by `draw` when it is new; nil when the atlas has no
    /// room that no frame in flight uses.
    func place(_ item: AtlasItem, frame: UInt64, completed: UInt64, draw: () -> [UInt8]) -> AtlasSlot? {
        switch atlas.place(item, wide: item.wide, frame: frame, completed: completed) {
        case let .found(slot):
            return slot
        case let .placed(slot, grewTo):
            if let side = grewTo, !grow(to: side) { return nil }
            let pixels = draw()
            guard pixels.count == slot.width * slot.height * bytesPerPixel else { return nil }
            pixels.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else { return }
                texture.replace(
                    region: MTLRegionMake2D(slot.x, slot.y, slot.width, slot.height), mipmapLevel: 0, withBytes: base,
                    bytesPerRow: slot.width * bytesPerPixel
                )
            }
            uploaded += pixels.count
            return slot
        case .full:
            return nil
        }
    }

    private func grow(to side: Int) -> Bool {
        let old = texture
        guard let grown = try? Self.texture(device, format, side: side) else {
            atlas = (try? Atlas(cell: atlas.cell, initialSide: old.width, largestSide: old.width)) ?? atlas
            texture = (try? Self.texture(device, format, side: old.width)) ?? old
            generation += 1
            return false
        }
        var pixels = [UInt8](repeating: 0, count: old.width * old.height * bytesPerPixel)
        let region = MTLRegionMake2D(0, 0, old.width, old.height)
        pixels.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            old.getBytes(base, bytesPerRow: old.width * bytesPerPixel, from: region, mipmapLevel: 0)
        }
        pixels.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            grown.replace(region: region, mipmapLevel: 0, withBytes: base, bytesPerRow: old.width * bytesPerPixel)
        }
        texture = grown
        uploaded += pixels.count
        return true
    }

    private static func texture(_ device: any MTLDevice, _ format: MTLPixelFormat, side: Int) throws(RenderError) -> any MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: side, height: side, mipmapped: false)
        descriptor.usage = [.shaderRead]
        guard let texture = device.makeTexture(descriptor: descriptor) else { throw .unavailable("no atlas texture of side \(side) could be made") }
        return texture
    }
}

/// The renderer's glyph source: sprites and text into the alpha atlas, emoji into the colour one.
final class GlyphStore: GlyphSource {
    let rasterizer: GlyphRasterizer
    let alpha: AtlasTexture
    let colour: AtlasTexture
    /// The frame being built, which marks every slot it uses.
    var frame: UInt64 = 0
    /// The newest frame the GPU finished, every earlier one finished too.
    var completed: @Sendable () -> UInt64 = { 0 }
    private(set) var unplaced = 0

    init(device: any MTLDevice, faces: TerminalFaces, sides: AtlasSides) throws(RenderError) {
        rasterizer = GlyphRasterizer(faces: faces)
        let cell = PixelSize(width: faces.metrics.width, height: faces.metrics.height)
        alpha = try AtlasTexture(device: device, cell: cell, format: .r8Unorm, bytesPerPixel: 1, sides: sides)
        colour = try AtlasTexture(device: device, cell: cell, format: .bgra8Unorm, bytesPerPixel: 4, sides: sides)
    }

    var generation: Int {
        alpha.generation + colour.generation
    }

    var uploaded: Int {
        alpha.uploaded + colour.uploaded
    }

    /// An item already drawn costs one lookup (text and sprites are in the alpha atlas); only a new
    /// one asks Core Text which atlas it belongs in.
    func place(_ item: AtlasItem) -> GlyphPlace? {
        if let slot = alpha.use(item, frame: frame) {
            return GlyphPlace(slot: slot, colour: false)
        }
        if case .glyph = item, let slot = colour.use(item, frame: frame) {
            return GlyphPlace(slot: slot, colour: true)
        }
        let cells = item.wide ? 2 : 1
        let done = completed()
        let slot: AtlasSlot?
        let isColour: Bool
        switch item {
        case let .sprite(sprite, _):
            isColour = false
            slot = alpha.place(item, frame: frame, completed: done) { sprite.render(rasterizer.metrics, cells: cells).pixels }
        case let .glyph(grapheme, style, _):
            isColour = rasterizer.isColour(grapheme, style)
            let atlas = isColour ? colour : alpha
            slot = atlas.place(item, frame: frame, completed: done) {
                isColour ? rasterizer.colour(grapheme, style, cells: cells) : rasterizer.coverage(grapheme, style, cells: cells)
            }
        }
        guard let slot else {
            unplaced += 1
            return nil
        }
        return GlyphPlace(slot: slot, colour: isColour)
    }
}
