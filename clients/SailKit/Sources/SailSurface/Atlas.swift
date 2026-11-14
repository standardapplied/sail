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
/// `largestSide` (a power of two times it), holding slots one cell or two cells wide in shelves one
/// cell high. Each slot remembers the last frame that used it, and the slots of each width are kept
/// in the order they were last used, least recent first. When the atlas is full and at its largest,
/// the least recently used slot is given to the new item, but never one a frame still in flight on
/// the GPU uses, nor one the frame being built uses: a slot is taken only once every frame that used
/// it has completed. Taking a slot and marking one used are each constant time.
public struct Atlas<Item: Hashable & Sendable>: Sendable {
    public static var standardLargestSide: Int { 8192 }

    public let cell: PixelSize
    public let largestSide: Int
    public private(set) var side: Int

    private var index: [Item: Int] = [:]
    private var nodes: [Node] = []
    private var lists = [List(), List()]
    private var shelves: [Int] = []
    /// The newest frame told so far: frames only move forward, so a frame number below it is taken
    /// as it, which keeps each list in the order of last use and only ever delays a reuse.
    private var newestFrame: UInt64 = 0

    private struct Node: Sendable {
        var item: Item
        let slot: AtlasSlot
        var lastUsed: UInt64
        var previous: Int?
        var next: Int?
    }

    /// Slots of one width, least recently used at the head.
    private struct List: Sendable {
        var head: Int?
        var tail: Int?
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
        index.count
    }

    /// The slot of `item`, one cell wide or two (`wide`), marked used by `frame`; `completed` is
    /// the newest frame whose GPU work is done, all before it done too.
    public mutating func place(_ item: Item, wide: Bool, frame: UInt64, completed: UInt64) -> Placement {
        if let slot = use(item, frame: frame) {
            return .found(slot)
        }
        let frame = moved(to: frame)
        let width = wide ? 2 * cell.width : cell.width
        if let slot = newSlot(width: width) {
            keep(item, in: slot, frame: frame)
            return .placed(slot, grewTo: nil)
        }
        if side < largestSide {
            side *= 2
            if let slot = newSlot(width: width) {
                keep(item, in: slot, frame: frame)
                return .placed(slot, grewTo: side)
            }
        }
        let list = wide ? 1 : 0
        guard let head = lists[list].head, nodes[head].lastUsed <= completed, nodes[head].lastUsed < frame else { return .full }
        index[nodes[head].item] = nil
        nodes[head].item = item
        index[item] = head
        markUsed(head, frame: frame)
        return .placed(nodes[head].slot, grewTo: nil)
    }

    /// The slot of `item`, marked used by `frame` (a row drawn again from its cache uses it); nil
    /// when the item is not in the atlas. One lookup: a frame asks this of every distinct item.
    public mutating func use(_ item: Item, frame: UInt64) -> AtlasSlot? {
        guard let node = index[item] else { return nil }
        markUsed(node, frame: moved(to: frame))
        return nodes[node].slot
    }

    public func slot(of item: Item) -> AtlasSlot? {
        index[item].map { nodes[$0].slot }
    }

    private mutating func moved(to frame: UInt64) -> UInt64 {
        newestFrame = max(newestFrame, frame)
        return newestFrame
    }

    private mutating func keep(_ item: Item, in slot: AtlasSlot, frame: UInt64) {
        nodes.append(Node(item: item, slot: slot, lastUsed: frame))
        index[item] = nodes.count - 1
        append(nodes.count - 1)
    }

    /// Records the use and moves the slot to its list's tail, the most recently used end.
    private mutating func markUsed(_ node: Int, frame: UInt64) {
        guard nodes[node].lastUsed != frame else { return }
        nodes[node].lastUsed = frame
        unlink(node)
        append(node)
    }

    private func list(of node: Int) -> Int {
        nodes[node].slot.width == cell.width ? 0 : 1
    }

    private mutating func unlink(_ node: Int) {
        let list = list(of: node)
        let (previous, next) = (nodes[node].previous, nodes[node].next)
        if let previous { nodes[previous].next = next } else { lists[list].head = next }
        if let next { nodes[next].previous = previous } else { lists[list].tail = previous }
        nodes[node].previous = nil
        nodes[node].next = nil
    }

    private mutating func append(_ node: Int) {
        let list = list(of: node)
        nodes[node].previous = lists[list].tail
        nodes[node].next = nil
        if let tail = lists[list].tail { nodes[tail].next = node } else { lists[list].head = node }
        lists[list].tail = node
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
}
