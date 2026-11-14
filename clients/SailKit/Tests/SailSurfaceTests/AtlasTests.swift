import SailSurface
import Testing

@Suite struct AtlasTests {
    private let cell = PixelSize(width: 10, height: 10)

    @Test func anItemPlacedOnceIsFoundAfter() throws {
        var atlas = try Atlas<String>(cell: cell, initialSide: 40, largestSide: 40)
        guard case let .placed(slot, nil) = atlas.place("a", wide: false, frame: 1, completed: 0) else {
            Issue.record("the first placement made no slot")
            return
        }
        let again = atlas.place("a", wide: false, frame: 2, completed: 1)
        #expect(again == .found(slot))
        #expect(slot == AtlasSlot(x: 0, y: 0, width: 10, height: 10))
        guard case let .placed(wide, nil) = atlas.place("b", wide: true, frame: 2, completed: 1) else {
            Issue.record("a wide item made no slot")
            return
        }
        #expect(wide == AtlasSlot(x: 10, y: 0, width: 20, height: 10))
    }

    @Test func aFullAtlasDoublesItsSideUpToTheLargest() throws {
        var atlas = try Atlas<Int>(cell: cell, initialSide: 20, largestSide: 40)
        for item in 0 ..< 4 {
            let placement = atlas.place(item, wide: false, frame: 1, completed: 0)
            #expect(placement != .full)
        }
        #expect(atlas.side == 20)
        guard case let .placed(slot, grewTo) = atlas.place(4, wide: false, frame: 1, completed: 0) else {
            Issue.record("the fifth item was not placed")
            return
        }
        #expect(grewTo == 40)
        #expect(atlas.side == 40)
        #expect(slot.x + slot.width <= 40 && slot.y + slot.height <= 40)
        for item in 0 ..< 4 {
            #expect(atlas.slot(of: item) != nil, "growing kept item \(item)")
        }
    }

    /// Scenario 5: full and at its largest, a new item takes the least recently used slot whose
    /// frames all completed; a slot a frame in flight or the frame being built uses is never taken.
    @Test func aFullAtlasNeverTakesASlotAFrameInFlightUses() throws {
        var atlas = try Atlas<String>(cell: cell, initialSide: 20, largestSide: 20)
        _ = atlas.place("older", wide: false, frame: 1, completed: 0)
        _ = atlas.place("old", wide: false, frame: 2, completed: 1)
        _ = atlas.place("in flight", wide: false, frame: 4, completed: 3)
        _ = atlas.place("this frame", wide: false, frame: 5, completed: 3)
        let older = atlas.slot(of: "older")
        guard case let .placed(slot, nil) = atlas.place("new", wide: false, frame: 5, completed: 3) else {
            Issue.record("no slot was taken")
            return
        }
        #expect(slot == older)
        #expect(atlas.slot(of: "older") == nil)
        guard case let .placed(next, nil) = atlas.place("newer", wide: false, frame: 5, completed: 3) else {
            Issue.record("the second least recent slot was not taken")
            return
        }
        #expect(next == AtlasSlot(x: 10, y: 0, width: 10, height: 10))
        let newest = atlas.place("newest", wide: false, frame: 5, completed: 3)
        #expect(newest == .full)
        #expect(atlas.slot(of: "in flight") != nil && atlas.slot(of: "this frame") != nil)
    }

    @Test func anItemUsedAgainIsUsedByThatFrame() throws {
        var atlas = try Atlas<String>(cell: cell, initialSide: 20, largestSide: 20)
        for item in ["a", "b", "c", "d"] {
            _ = atlas.place(item, wide: false, frame: 1, completed: 0)
        }
        let used = atlas.use("a", frame: 3)
        let missing = atlas.use("z", frame: 3)
        #expect(used == AtlasSlot(x: 0, y: 0, width: 10, height: 10) && missing == nil)
        _ = atlas.place("e", wide: false, frame: 3, completed: 2)
        #expect(atlas.slot(of: "a") != nil)
    }

    /// The slot given up is the least recently used, wherever it sits, and finding it costs the
    /// same however full the atlas: 2,000 evicting placements in a full 8192 atlas of 18x40 cells
    /// (46,308 wide slots) took 14.6 s when each scanned the atlas; they must take well under 1 s.
    @Test func aFullAtlasGivesUpItsLeastRecentlyUsedSlotAtOnce() throws {
        var atlas = try Atlas<Int>(cell: PixelSize(width: 18, height: 40), initialSide: 8192, largestSide: 8192)
        var filled = 0
        while case .placed = atlas.place(filled, wide: true, frame: 1, completed: 0) {
            filled += 1
        }
        #expect(filled == 46308)
        for item in stride(from: 0, to: filled, by: 2) {
            _ = atlas.use(item, frame: 2)
        }
        let started = ContinuousClock.now
        var taken: [AtlasSlot] = []
        for new in 0 ..< 2000 {
            guard case let .placed(slot, nil) = atlas.place(filled + new, wide: true, frame: 4, completed: 3) else {
                Issue.record("placement \(new) found no slot")
                return
            }
            taken.append(slot)
        }
        #expect(ContinuousClock.now - started < .seconds(1))
        #expect(atlas.slot(of: 1) == nil && atlas.slot(of: 0) != nil, "the slots used only in frame 1 went first")
        #expect(Set(taken).count == 2000)
    }

    @Test func aWideItemTakesOnlyAWideSlot() throws {
        var atlas = try Atlas<String>(cell: cell, initialSide: 20, largestSide: 20)
        for item in ["a", "b", "c", "d"] {
            _ = atlas.place(item, wide: false, frame: 1, completed: 0)
        }
        let wide = atlas.place("wide", wide: true, frame: 3, completed: 2)
        #expect(wide == .full)
    }

    @Test func anAtlasThatCannotHoldItsCellsIsRefused() {
        #expect(throws: SurfaceError.self) { try Atlas<Int>(cell: PixelSize(width: 30, height: 10), initialSide: 40) }
        #expect(throws: SurfaceError.self) { try Atlas<Int>(cell: PixelSize(width: 10, height: 10), initialSide: 40, largestSide: 120) }
        #expect(throws: SurfaceError.self) { try Atlas<Int>(cell: PixelSize(width: 0, height: 10)) }
    }
}

@Suite struct FrameLedgerTests {
    /// V5: frame n writes buffer n % 3; a fourth frame waits until the first completed.
    @Test func aBufferIsWrittenOnlyOnceTheFrameReadingItCompleted() throws {
        var ledger = try FrameLedger()
        let first = ledger.begin()
        let second = ledger.begin()
        let third = ledger.begin()
        #expect([first?.buffer, second?.buffer, third?.buffer] == [1, 2, 0])
        #expect(ledger.begin() == nil)
        ledger.complete(2)
        #expect(ledger.completed == 0)
        #expect(ledger.begin() == nil, "frame 4 writes frame 1's buffer, still in flight")
        ledger.complete(1)
        #expect(ledger.completed == 2)
        let fourth = ledger.begin()
        #expect(fourth?.frame == 4 && fourth?.buffer == 1)
        #expect(ledger.inFlight == 2)
    }

    @Test func completionsOfFramesNeverBegunAreIgnored() throws {
        var ledger = try FrameLedger(buffers: 2)
        ledger.complete(1)
        #expect(ledger.completed == 0)
        _ = ledger.begin()
        ledger.complete(1)
        ledger.complete(1)
        #expect(ledger.completed == 1)
        #expect(throws: SurfaceError.self) { try FrameLedger(buffers: 0) }
    }
}
