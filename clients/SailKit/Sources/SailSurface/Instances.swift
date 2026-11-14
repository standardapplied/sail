import SailVT

/// A filled rectangle: `rect` is x, y, width and height in pixels from the surface's top left;
/// `color` is red, green, blue and alpha, sRGB-encoded, 0 to 1. The layout is the shader's.
public struct SolidInstance: Equatable, Sendable {
    public var rect: SIMD4<Float>
    public var color: SIMD4<Float>

    public init(rect: SIMD4<Float>, color: SIMD4<Float>) {
        self.rect = rect
        self.color = color
    }
}

/// An atlas slot drawn at `rect`: from the alpha atlas tinted with `color`, or from the colour
/// atlas as it is (`colour` 1). The layout is the shader's.
public struct GlyphInstance: Equatable, Sendable {
    public var rect: SIMD4<Float>
    public var slot: SIMD2<Float>
    public var colour: Float
    public var unused: Float = 0
    public var color: SIMD4<Float>

    public init(rect: SIMD4<Float>, slot: SIMD2<Float>, colour: Bool, color: SIMD4<Float>) {
        self.rect = rect
        self.slot = slot
        self.colour = colour ? 1 : 0
        self.color = color
    }
}

public enum FontStyle: Int, Hashable, Sendable, CaseIterable {
    case regular, bold, italic, boldItalic

    init(_ attributes: CellAttributes) {
        switch (attributes.contains(.bold), attributes.contains(.italic)) {
        case (false, false): self = .regular
        case (true, false): self = .bold
        case (false, true): self = .italic
        case (true, true): self = .boldItalic
        }
    }
}

/// What an atlas slot holds: a grapheme cluster in a face, or a sprite; one cell wide or two.
public enum AtlasItem: Hashable, Sendable {
    case glyph(String, FontStyle, wide: Bool)
    case sprite(Sprite, wide: Bool)

    public var wide: Bool {
        switch self {
        case let .glyph(_, _, wide), let .sprite(_, wide): wide
        }
    }

    static let denseCount = 1400

    /// A small number standing for the item when it is one of the common ones (a one-byte grapheme,
    /// a box-drawing character, a line or a cursor), so a frame can remember its place in an array.
    var denseIndex: Int? {
        switch self {
        case let .glyph(text, style, wide):
            guard text.utf8.count == 1, let byte = text.utf8.first, byte < 0x80 else { return nil }
            return (Int(byte) << 3) | (style.rawValue << 1) | (wide ? 1 : 0)
        case let .sprite(sprite, wide):
            return Self.denseIndex(of: sprite).map { 1024 + ($0 << 1) | (wide ? 1 : 0) }
        }
    }

    private static func denseIndex(of sprite: Sprite) -> Int? {
        switch sprite {
        case let .box(scalar): (0x2500 ... 0x259F).contains(scalar) ? Int(scalar - 0x2500) : nil
        case .underline(.none): 160
        case .underline(.single): 161
        case .underline(.double): 162
        case .underline(.curly): 163
        case .underline(.dotted): 164
        case .underline(.dashed): 165
        case .strikethrough: 166
        case .overline: 167
        case .cursor(.block): 168
        case .cursor(.hollow): 169
        case .cursor(.bar): 170
        case .cursor(.underline): 171
        }
    }
}

/// The places one build has asked for. The first ask of an item in a frame marks it used by the
/// frame, so every later cell with the same item takes the answer without a lookup: a full screen
/// asks once per distinct item, not once per cell.
struct PlaceMemo {
    private var asked = [Bool](repeating: false, count: AtlasItem.denseCount)
    private var dense = [GlyphPlace?](repeating: nil, count: AtlasItem.denseCount)
    private var other: [AtlasItem: GlyphPlace?] = [:]

    mutating func place(_ item: AtlasItem, glyphs: some GlyphSource) -> GlyphPlace? {
        place(item, index: item.denseIndex, glyphs: glyphs)
    }

    mutating func place(_ item: AtlasItem, index: Int?, glyphs: some GlyphSource) -> GlyphPlace? {
        if let index {
            if !asked[index] {
                asked[index] = true
                dense[index] = glyphs.place(item)
            }
            return dense[index]
        }
        if let known = other[item] { return known }
        let place = glyphs.place(item)
        other[item] = place
        return place
    }
}

/// The distinct items one row uses and where each was drawn, kept so a row drawn again from its
/// cache marks them used, and is laid out again when any of them moved.
struct DistinctItems: Sendable {
    private(set) var entries: [(item: AtlasItem, place: GlyphPlace)] = []
    private var dense = [Bool](repeating: false, count: AtlasItem.denseCount)
    private var other: Set<AtlasItem> = []

    mutating func add(_ item: AtlasItem, index: Int?, place: GlyphPlace) {
        if let index {
            guard !dense[index] else { return }
            dense[index] = true
        } else {
            guard other.insert(item).inserted else { return }
        }
        entries.append((item, place))
    }
}

/// Where a glyph source drew an item, and in which atlas.
public struct GlyphPlace: Equatable, Sendable {
    public var slot: AtlasSlot
    public var colour: Bool

    public init(slot: AtlasSlot, colour: Bool) {
        self.slot = slot
        self.colour = colour
    }
}

/// Draws items into the atlases on demand: the renderer, with Core Text and the GPU textures.
public protocol GlyphSource: AnyObject {
    /// The item's place, drawn first when it is new, and marked used by the frame being built;
    /// nil when it has no slot this frame: no room, or new items past this frame's share.
    func place(_ item: AtlasItem) -> GlyphPlace?
}

/// What a view adds to the screen's frame: whether it has focus (the cursor is hollow without
/// it) and the text an input method is composing, drawn at the cursor.
public struct Overlay: Equatable, Sendable {
    public var focused: Bool
    public var marked: String

    public init(focused: Bool = true, marked: String = "") {
        self.focused = focused
        self.marked = marked
    }
}

/// One frame's instances, in drawing order: backgrounds, then glyphs, then decorations (lines,
/// the cursor, composing text).
public struct FrameInstances: Equatable, Sendable {
    public var clear: SIMD4<Float>
    public var backgrounds: [SolidInstance]
    public var glyphs: [GlyphInstance]
    public var decorations: [GlyphInstance]
}

/// Lays a ``ScreenFrame`` out as instances, keeping each row's and rebuilding only the rows the
/// frame says changed. The cursor and the composing text are laid out fresh every frame, and
/// placed before the rows, so a frame's share of new glyphs never leaves them out.
public struct InstanceBuilder: Sendable {
    public let metrics: CellMetrics
    /// The rows the last ``build(_:overlay:glyphs:)`` laid out again.
    public private(set) var rebuilt: [Int] = []

    /// Whether the last build drew every item, the cursor and the composing text included: false
    /// when one found no atlas slot this frame (the atlas full of frames in flight, or new glyphs
    /// past the frame's share), so the caller draws again at the next refresh.
    public var complete: Bool {
        overlayComplete && !rows.contains { $0.incomplete }
    }

    private var overlayComplete = true
    private var rows: [RowInstances] = []
    private var size: GridSize?

    private struct RowInstances: Sendable {
        var backgrounds: [SolidInstance] = []
        var glyphs: [GlyphInstance] = []
        var decorations: [GlyphInstance] = []
        var items = DistinctItems()
        /// An item found no atlas slot: the row is laid out again at the next frame.
        var incomplete = false
    }

    public init(metrics: CellMetrics) {
        self.metrics = metrics
    }

    /// Forgets every row: the atlas was reset or the colours of everything changed.
    public mutating func invalidate() {
        rows = []
        size = nil
    }

    public mutating func build(_ frame: ScreenFrame, overlay: Overlay, glyphs: some GlyphSource) -> FrameInstances {
        let dirty: Set<Int>
        if size != frame.size || rows.count != frame.rows.count {
            rows = Array(repeating: RowInstances(), count: frame.rows.count)
            dirty = Set(frame.rows.indices)
        } else {
            switch frame.changed {
            case .all: dirty = Set(frame.rows.indices)
            case let .rows(changed): dirty = changed.filter { frame.rows.indices.contains($0) }
            case .nothing: dirty = []
            }
        }
        size = frame.size
        rebuilt = []
        let overlaid = overlay.marked.isEmpty ? cursor(frame, focused: overlay.focused, glyphs: glyphs) : composing(overlay.marked, frame, glyphs: glyphs)
        overlayComplete = overlaid.complete
        var memo = PlaceMemo()
        for y in frame.rows.indices {
            if dirty.contains(y) || rows[y].incomplete || !rows[y].items.entries.allSatisfy({ memo.place($0.item, glyphs: glyphs) == $0.place }) {
                rows[y] = layout(row: frame.rows[y], at: y, defaults: frame.colors, memo: &memo, glyphs: glyphs)
                rebuilt.append(y)
            }
        }
        var instances = FrameInstances(
            clear: Self.color(frame.colors.background),
            backgrounds: Self.joined(rows.map(\.backgrounds)),
            glyphs: Self.joined(rows.map(\.glyphs)),
            decorations: Self.joined(rows.map(\.decorations))
        )
        instances.decorations += overlaid.drawn
        return instances
    }

    private func layout(
        row: ScreenFrame.Row, at y: Int, defaults: DefaultColors, memo: inout PlaceMemo, glyphs: some GlyphSource
    ) -> RowInstances {
        var laid = RowInstances()
        for (x, cell) in row.cells.enumerated() {
            let (foreground, background) = cell.selected ? (cell.background, cell.foreground) : (cell.foreground, cell.background)
            let origin = metrics.origin(of: CellPoint(x: x, y: y))
            if background != defaults.background {
                let color = Self.color(background)
                if let last = laid.backgrounds.last, last.color == color, Int(last.rect.x + last.rect.z) == origin.x,
                   Int(last.rect.y) == origin.y
                {
                    laid.backgrounds[laid.backgrounds.count - 1].rect.z += Float(metrics.width)
                } else {
                    laid.backgrounds.append(SolidInstance(rect: rect(origin, cells: 1), color: color))
                }
            }
            guard cell.width == .narrow || cell.width == .wide else { continue }
            let wide = cell.width == .wide
            let ink = Self.color(foreground)
            let area = rect(origin, cells: wide ? 2 : 1)
            if !cell.attributes.contains(.invisible), let item = Self.item(for: cell.grapheme, style: FontStyle(cell.attributes), wide: wide) {
                let index = item.denseIndex
                if let place = memo.place(item, index: index, glyphs: glyphs) {
                    laid.items.add(item, index: index, place: place)
                    laid.glyphs.append(GlyphInstance(rect: area, slot: Self.origin(place.slot), colour: place.colour, color: ink))
                } else {
                    laid.incomplete = true
                }
            }
            func line(_ sprite: Sprite, _ color: SIMD4<Float>) {
                let item = AtlasItem.sprite(sprite, wide: wide)
                let index = item.denseIndex
                guard let place = memo.place(item, index: index, glyphs: glyphs) else {
                    laid.incomplete = true
                    return
                }
                laid.items.add(item, index: index, place: place)
                laid.decorations.append(GlyphInstance(rect: area, slot: Self.origin(place.slot), colour: false, color: color))
            }
            if cell.underline != .none { line(.underline(cell.underline), cell.underlineColor.map(Self.color) ?? ink) }
            if cell.attributes.contains(.strikethrough) { line(.strikethrough, ink) }
            if cell.attributes.contains(.overline) { line(.overline, ink) }
        }
        return laid
    }

    /// What the cursor or the composing text draws, and whether every item of it found a slot.
    private typealias Overlaid = (drawn: [GlyphInstance], complete: Bool)

    private func cursor(_ frame: ScreenFrame, focused: Bool, glyphs: some GlyphSource) -> Overlaid {
        guard frame.cursor.visible, let position = frame.cursor.position, frame.rows.indices.contains(position.y),
              frame.rows[position.y].cells.indices.contains(position.x)
        else { return ([], true) }
        let cell = frame.rows[position.y].cells[position.x]
        let wide = cell.width == .wide
        let shape: CursorShape = switch (focused, frame.cursor.style) {
        case (false, _), (true, .blockHollow): .hollow
        case (true, .block): .block
        case (true, .bar): .bar
        case (true, .underline): .underline
        }
        let origin = metrics.origin(of: position)
        let area = rect(origin, cells: wide ? 2 : 1)
        guard let block = glyphs.place(.sprite(.cursor(shape), wide: wide)) else { return ([], false) }
        let drawn = [GlyphInstance(rect: area, slot: Self.origin(block.slot), colour: false, color: Self.color(frame.colors.cursor))]
        guard shape == .block, !cell.attributes.contains(.invisible),
              let item = Self.item(for: cell.grapheme, style: FontStyle(cell.attributes), wide: wide)
        else { return (drawn, true) }
        guard let place = glyphs.place(item) else { return (drawn, false) }
        let under = cell.selected ? cell.foreground : cell.background
        return (drawn + [GlyphInstance(rect: area, slot: Self.origin(place.slot), colour: place.colour, color: Self.color(under))], true)
    }

    /// The composing text, from the cursor to the row's end at most: the default background, the
    /// text in the default foreground, underlined.
    private func composing(_ marked: String, _ frame: ScreenFrame, glyphs: some GlyphSource) -> Overlaid {
        let start = frame.cursor.position ?? CellPoint(x: 0, y: 0)
        var x = start.x
        var drawn: [GlyphInstance] = []
        var complete = true
        let ink = Self.color(frame.colors.foreground)
        for grapheme in marked {
            let text = String(grapheme)
            let wide = Graphemes.cells(text) == 2
            guard x + (wide ? 2 : 1) <= frame.size.columns else { break }
            let area = rect(metrics.origin(of: CellPoint(x: x, y: start.y)), cells: wide ? 2 : 1)
            let layers: [(AtlasItem?, SIMD4<Float>)] = [
                (.sprite(.cursor(.block), wide: wide), Self.color(frame.colors.background)),
                (Self.item(for: text, style: .regular, wide: wide), ink),
                (.sprite(.underline(.single), wide: wide), ink),
            ]
            for case let (item?, color) in layers {
                guard let place = glyphs.place(item) else {
                    complete = false
                    continue
                }
                drawn.append(GlyphInstance(rect: area, slot: Self.origin(place.slot), colour: place.colour, color: color))
            }
            x += wide ? 2 : 1
        }
        return (drawn, complete)
    }

    private func rect(_ origin: (x: Int, y: Int), cells: Int) -> SIMD4<Float> {
        SIMD4(Float(origin.x), Float(origin.y), Float(metrics.width * cells), Float(metrics.height))
    }

    static func item(for grapheme: String, style: FontStyle, wide: Bool) -> AtlasItem? {
        guard !grapheme.isEmpty, grapheme != " " else { return nil }
        return Sprite.drawing(grapheme).map { .sprite($0, wide: wide) } ?? .glyph(grapheme, style, wide: wide)
    }

    /// The arrays one after another, in one allocation.
    static func joined<Element>(_ parts: [[Element]]) -> [Element] {
        var all: [Element] = []
        all.reserveCapacity(parts.reduce(0) { $0 + $1.count })
        for part in parts {
            all.append(contentsOf: part)
        }
        return all
    }

    static func origin(_ slot: AtlasSlot) -> SIMD2<Float> {
        SIMD2(Float(slot.x), Float(slot.y))
    }

    public static func color(_ rgb: RGB) -> SIMD4<Float> {
        SIMD4(Float(rgb.red) / 255, Float(rgb.green) / 255, Float(rgb.blue) / 255, 1)
    }
}
