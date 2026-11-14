import SailSurface
import SailVT

/// JetBrains Mono's proportions (units per em: advance 600, ascent 1020, descent 300, underline
/// 50 thick 150 below, x-height 550) at `points` and `scale`.
func metrics(points: Double, scale: Double = 2) throws -> CellMetrics {
    let pixels = points * scale
    return try CellMetrics(
        font: FontMetrics(
            advance: 0.6 * pixels, ascent: 1.02 * pixels, descent: 0.3 * pixels, leading: 0, underlineOffset: 0.15 * pixels,
            underlineThickness: 0.05 * pixels, xHeight: 0.55 * pixels
        ),
        scale: scale
    )
}

/// A glyph source handing out slots in order and remembering what it was asked.
final class Glyphs: GlyphSource {
    private(set) var places: [AtlasItem: GlyphPlace] = [:]
    private(set) var asked: [AtlasItem] = []
    /// Whether a grapheme comes from the colour atlas: those starting in the emoji planes.
    let colourFromScalar: UInt32

    init(colourFromScalar: UInt32 = .max) {
        self.colourFromScalar = colourFromScalar
    }

    func place(_ item: AtlasItem) -> GlyphPlace? {
        asked.append(item)
        if let place = places[item] { return place }
        let isColour = if case let .glyph(text, _, _) = item { (text.unicodeScalars.first?.value ?? 0) >= colourFromScalar } else { false }
        let place = GlyphPlace(slot: AtlasSlot(x: places.count * 100, y: 0, width: item.wide ? 20 : 10, height: 20), colour: isColour)
        places[item] = place
        return place
    }

    func forget() {
        asked = []
    }
}

/// A glyph source over the real atlas bookkeeping, as the renderer's, remembering what each slot
/// holds; `room` new items a frame at most.
final class AtlasGlyphs: GlyphSource {
    var atlas: Atlas<AtlasItem>
    var frame: UInt64 = 1
    var completed: UInt64 = 0
    var room = Int.max
    private var placedThisFrame = 0
    private var placedFrame: UInt64 = 0
    private(set) var holder: [AtlasSlot: AtlasItem] = [:]

    init(side: Int, cell: PixelSize = PixelSize(width: 18, height: 40)) throws {
        atlas = try Atlas(cell: cell, initialSide: side, largestSide: side)
    }

    func place(_ item: AtlasItem) -> GlyphPlace? {
        if let slot = atlas.use(item, frame: frame) { return GlyphPlace(slot: slot, colour: false) }
        if placedFrame != frame {
            placedFrame = frame
            placedThisFrame = 0
        }
        guard placedThisFrame < room else { return nil }
        guard case let .placed(slot, _) = atlas.place(item, wide: item.wide, frame: frame, completed: completed) else { return nil }
        placedThisFrame += 1
        holder[slot] = item
        return GlyphPlace(slot: slot, colour: false)
    }
}

func screen(_ columns: Int, _ rows: Int, palette: Palette = .dark) throws -> Screen {
    try Screen(size: GridSize(columns: columns, rows: rows), scrollbackBytes: 1 << 20, palette: palette)
}

extension Screen {
    func fed(_ text: String) -> Screen {
        _ = feed(Array(text.utf8))
        return self
    }
}
