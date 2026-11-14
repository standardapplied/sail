/// Where an item sits in an atlas texture, in pixels from its top left.
public struct AtlasSlot: Hashable, Sendable {
    public let x: Int
    public let y: Int
    public let width: Int
    public let height: Int

    public init(x: Int, y: Int, width: Int, height: Int) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

/// The bookkeeping of one glyph atlas: square, its side doubling from `initialSide` up to
/// `largestSide` (a power of two times it), holding slots one cell or two cells wide in shelves one cell high. Each slot
/// remembers the last frame that used it; when the atlas is full and at its largest, the slot
/// least recently used is given to the new item, but never one a frame still in flight on the GPU
/// uses, nor one the frame being built uses: a slot is taken only once every frame that used it
/// has completed.
public struct Atlas<Item: Hashable & Sendable>: Sendable {
    public static var standardLargestSide: Int { 8192 }

    public let cell: PixelSize
    public let largestSide: Int
    public private(set) var side: Int

    private var slots: [Item: Entry] = [:]
    private var shelves: [Int] = []
    private var lastAdded = 0

    private struct Entry: Sendable {
        let slot: AtlasSlot
        var lastUsed: UInt64
    }

    /// Where `place` put an item.
    public enum Placement: Equatable, Sendable {
        /// Already there; nothing to draw.
        case found(AtlasSlot)
        /// A new slot: the item is drawn into it. When the atlas grew first, `grewTo` is its new
        /// side, and the texture is replaced by one that large holding the old one's pixels.
        case placed(AtlasSlot, grewTo: Int?)
        /// No slot is free and none can be taken from frames that completed.
        case full
    }

    public init(cell: PixelSize, initialSide: Int = 1024, largestSide: Int = Atlas.standardLargestSide) throws(SurfaceError) {
        guard cell.width >= 1, cell.height >= 1, initialSide >= 2 * cell.width, initialSide >= cell.height,
              largestSide >= initialSide, largestSide % initialSide == 0, (largestSide / initialSide).nonzeroBitCount == 1
        else {
            throw .invalid(
                "an atlas growing by doubling from \(initialSide) to \(largestSide) cannot hold cells of \(cell.width)x\(cell.height)"
            )
        }
        self.cell = cell
        self.largestSide = largestSide
        side = initialSide
    }

    public var count: Int {
        slots.count
    }

    /// The slot of `item`, one cell wide or two (`wide`), marked used by `frame`; `completed` is
    /// the newest frame whose GPU work is done, all before it done too.
    public mutating func place(_ item: Item, wide: Bool, frame: UInt64, completed: UInt64) -> Placement {
        if let slot = use(item, frame: frame) {
            return .found(slot)
        }
        let width = wide ? 2 * cell.width : cell.width
        if let slot = newSlot(width: width) {
            return keep(item, slot, frame: frame, grewTo: nil)
        }
        if side < largestSide {
            side *= 2
            if let slot = newSlot(width: width) {
                return keep(item, slot, frame: frame, grewTo: side)
            }
        }
        if let (victim, entry) = leastRecentlyUsed(width: width, completed: completed, before: frame) {
            slots[victim] = nil
            return keep(item, entry.slot, frame: frame, grewTo: nil)
        }
        return .full
    }

    /// The slot of `item`, marked used by `frame` (a row drawn again from its cache uses it); nil
    /// when the item is not in the atlas. One lookup: a frame asks this of every cell.
    public mutating func use(_ item: Item, frame: UInt64) -> AtlasSlot? {
        guard let index = slots.index(forKey: item) else { return nil }
        slots.values[index].lastUsed = frame
        return slots.values[index].slot
    }

    public func slot(of item: Item) -> AtlasSlot? {
        slots[item]?.slot
    }

    private mutating func keep(_ item: Item, _ slot: AtlasSlot, frame: UInt64, grewTo: Int?) -> Placement {
        slots[item] = Entry(slot: slot, lastUsed: frame)
        return .placed(slot, grewTo: grewTo)
    }

    /// Room never used yet: on a shelf with space left, or a new shelf below the last.
    private mutating func newSlot(width: Int) -> AtlasSlot? {
        if let shelf = shelves.indices.first(where: { shelves[$0] + width <= side }) {
            defer { shelves[shelf] += width }
            return AtlasSlot(x: shelves[shelf], y: shelf * cell.height, width: width, height: cell.height)
        }
        guard (shelves.count + 1) * cell.height <= side, width <= side else { return nil }
        shelves.append(width)
        return AtlasSlot(x: 0, y: (shelves.count - 1) * cell.height, width: width, height: cell.height)
    }

    private func leastRecentlyUsed(width: Int, completed: UInt64, before frame: UInt64) -> (Item, Entry)? {
        slots
            .filter { $0.value.slot.width == width && $0.value.lastUsed <= completed && $0.value.lastUsed < frame }
            .min { $0.value.lastUsed < $1.value.lastUsed }
            .map { ($0.key, $0.value) }
    }
}
