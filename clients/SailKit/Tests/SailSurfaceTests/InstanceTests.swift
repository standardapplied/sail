import SailSurface
import SailVT
import Testing

@Suite struct InstanceTests {
    /// Scenario 4: after a frame where one row changed, only that row was laid out again (the
    /// cursor stays on it: libghostty-vt marks the row a cursor leaves changed too).
    @Test func aFrameWhereOneRowChangedLaysOutOnlyThatRow() throws {
        let screen = try screen(10, 4).fed("one\r\ntwo\r\nthree")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs()
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(builder.rebuilt == [0, 1, 2, 3])
        _ = builder.build(try screen.fed("\u{1b}[2;1H").frame(), overlay: Overlay(), glyphs: glyphs)
        _ = screen.fed("due")
        let again = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(builder.rebuilt == [1], "\(builder.rebuilt)")
        #expect(again.glyphs.count == 11)
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(builder.rebuilt.isEmpty)
    }

    /// A frame asks the glyph source once per distinct item, however many cells use it, and a frame
    /// that lays nothing out still asks for every item its cached rows show, so each stays marked
    /// used by the frames that draw it (V5).
    @Test func eachDistinctItemIsAskedOncePerFrameCachedRowsIncluded() throws {
        let screen = try screen(10, 2).fed("aaaaaaaaaa\u{1b}[4mbb\u{1b}[?25l")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs()
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        let first = glyphs.asked
        #expect(first.filter { $0 == .glyph("a", .regular, wide: false) }.count == 1)
        #expect(first.filter { $0 == .sprite(.underline(.single), wide: false) }.count == 1)
        glyphs.forget()
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(builder.rebuilt.isEmpty)
        #expect(Set(glyphs.asked) == Set(first), "the cached rows' items are asked again, once each")
        #expect(glyphs.asked.count == Set(first).count)
    }

    /// A cached row whose item an earlier row's new glyph took (the atlas full) is laid out again,
    /// so every cell samples the slot holding its own glyph. Four slots hold a, b, c and s; then row
    /// 0 gains X, which takes c's slot, and row 1's c, drawn again from its cache, finds s's.
    @Test func aCachedRowWhoseItemMovedIsLaidOutAgain() throws {
        let metrics = try metrics(points: 15)
        let glyphs = try AtlasGlyphs(side: 20, cell: PixelSize(width: 10, height: 10))
        var builder = InstanceBuilder(metrics: metrics)
        let screen = try screen(4, 3).fed("ab\r\nc\r\ns\u{1b}[?25l")
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        glyphs.completed = 1
        glyphs.frame = 2
        _ = screen.fed("\u{1b}[1;3HX\u{1b}[3;1H\u{1b}[K")
        let frame = try screen.frame()
        let drawn = builder.build(frame, overlay: Overlay(), glyphs: glyphs)
        #expect(drawn.glyphs.count == 4)
        for instance in drawn.glyphs {
            let column = (Int(instance.rect.x) - metrics.padding.width) / metrics.width
            let row = (Int(instance.rect.y) - metrics.padding.height) / metrics.height
            let slot = AtlasSlot(x: Int(instance.slot.x), y: Int(instance.slot.y), width: 10, height: 10)
            #expect(glyphs.holder[slot] == .glyph(frame.rows[row].cells[column].grapheme, .regular, wide: false), "cell \(column),\(row)")
        }
        #expect(builder.rebuilt.contains(1))
    }

    /// A glyph that found no slot (more new glyphs than the frame's share) is drawn on a later
    /// frame: the build says it was not complete, and the row is laid out again.
    @Test func aGlyphWithoutASlotIsDrawnOnALaterFrame() throws {
        let glyphs = try AtlasGlyphs(side: 1024)
        glyphs.room = 2
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let screen = try screen(10, 2).fed("abcd\u{1b}[?25l")
        let first = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(first.glyphs.count == 2)
        #expect(!builder.complete)
        glyphs.frame = 2
        let second = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(second.glyphs.count == 4)
        #expect(builder.complete)
    }

    @Test func invalidatingLaysOutEveryRowAgain() throws {
        let screen = try screen(10, 2).fed("ab")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: Glyphs())
        builder.invalidate()
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: Glyphs())
        #expect(builder.rebuilt == [0, 1])
    }

    @Test func cellsArePlacedOnTheGridAndWideOnesSpanTwo() throws {
        let cell = try metrics(points: 15)
        let screen = try screen(12, 2).fed("a漢b")
        var builder = InstanceBuilder(metrics: cell)
        let drawn = builder.build(try screen.frame(), overlay: Overlay(), glyphs: Glyphs())
        let rects = drawn.glyphs.map(\.rect)
        #expect(rects == [
            SIMD4(20, 16, 18, 40), SIMD4(38, 16, 36, 40), SIMD4(74, 16, 18, 40),
        ])
    }

    /// Scenario 3: an emoji ZWJ family, a flag and a skin-toned emoji are each one cluster across
    /// two cells, from the colour atlas.
    @Test(arguments: ["👨\u{200D}👩\u{200D}👧", "🇯🇵", "👍🏽"])
    func anEmojiSequenceIsOneClusterAcrossTwoCellsFromTheColourAtlas(_ sequence: String) throws {
        let screen = try screen(20, 2).fed(sequence + "\u{1b}[?25l")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs(colourFromScalar: 0x1F000)
        let drawn = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(drawn.glyphs.count == 1)
        #expect(drawn.glyphs.first?.colour == 1)
        #expect(drawn.glyphs.first?.rect.z == 36)
        #expect(glyphs.asked == [.glyph(sequence, .regular, wide: true)])
    }

    @Test func aSelectedCellIsDrawnInverted() throws {
        let screen = try screen(10, 2).fed("hello")
        #expect(screen.select(.range(from: CellPoint(x: 0, y: 0), to: CellPoint(x: 1, y: 0))))
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let drawn = builder.build(try screen.frame(), overlay: Overlay(), glyphs: Glyphs())
        let foreground = InstanceBuilder.color(Palette.dark.foreground)
        let background = InstanceBuilder.color(Palette.dark.background)
        #expect(drawn.backgrounds.count == 1, "two selected cells are one run")
        #expect(drawn.backgrounds.first?.color == foreground)
        #expect(drawn.backgrounds.first?.rect.z == 36)
        #expect(drawn.glyphs.prefix(2).allSatisfy { $0.color == background })
        #expect(drawn.glyphs.dropFirst(2).allSatisfy { $0.color == foreground })
    }

    /// Scenario 17's drawing: a view without focus draws the cursor hollow; with focus, a block
    /// cursor and the cell's glyph again in the cell's background over it.
    @Test func theCursorIsHollowWithoutFocusAndABlockOverItsGlyphWithIt() throws {
        let screen = try screen(10, 2).fed("ab\u{1b}[1;1H")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs()
        let focused = builder.build(try screen.frame(), overlay: Overlay(focused: true), glyphs: glyphs)
        let cursor = InstanceBuilder.color(Palette.dark.cursor)
        #expect(focused.decorations.count == 2)
        #expect(focused.decorations.first?.color == cursor)
        #expect(focused.decorations.last?.color == InstanceBuilder.color(Palette.dark.background))
        #expect(glyphs.asked.contains(.sprite(.cursor(.block), wide: false)))
        glyphs.forget()
        let unfocused = builder.build(try screen.frame(), overlay: Overlay(focused: false), glyphs: glyphs)
        #expect(unfocused.decorations.count == 1)
        #expect(glyphs.asked.contains(.sprite(.cursor(.hollow), wide: false)))
        _ = screen.fed("\u{1b}[?25l")
        #expect(builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs).decorations.isEmpty)
    }

    /// Scenario 8's drawing: composing text is drawn at the cursor, wide characters across two
    /// cells, and the cursor is not drawn meanwhile.
    @Test func composingTextIsDrawnAtTheCursor() throws {
        let screen = try screen(10, 2).fed("$ ")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs()
        let drawn = builder.build(try screen.frame(), overlay: Overlay(marked: "にほ"), glyphs: glyphs)
        #expect(drawn.decorations.count == 6)
        #expect(drawn.decorations[1].rect == SIMD4(56, 16, 36, 40))
        #expect(drawn.decorations[4].rect == SIMD4(92, 16, 36, 40))
        #expect(glyphs.asked.contains(.glyph("に", .regular, wide: true)))
        #expect(!glyphs.asked.contains(.sprite(.cursor(.block), wide: false)))
        let clipped = builder.build(try screen.frame(), overlay: Overlay(marked: "にほんごです"), glyphs: Glyphs())
        #expect(clipped.decorations.count == 4 * 3, "the row holds four of the six from column 2")
    }

    @Test func linesBoxesAndStylesBecomeTheirItems() throws {
        let screen = try screen(20, 2).fed("\u{1b}[1;3;4;9m x\u{1b}[0m─\u{1b}[8mh")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs()
        let drawn = builder.build(try screen.frame(), overlay: Overlay(focused: false), glyphs: glyphs)
        #expect(glyphs.asked.contains(.glyph("x", .boldItalic, wide: false)))
        #expect(glyphs.asked.contains(.sprite(.box(0x2500), wide: false)))
        #expect(glyphs.asked.contains(.sprite(.underline(.single), wide: false)))
        #expect(glyphs.asked.contains(.sprite(.strikethrough, wide: false)))
        #expect(!glyphs.asked.contains(.glyph("h", .regular, wide: false)), "invisible text draws no glyph")
        #expect(!glyphs.asked.contains(.glyph(" ", .boldItalic, wide: false)), "a blank draws no glyph")
        #expect(drawn.glyphs.count == 2)
    }

    @Test func theClearColourIsTheDefaultBackground() throws {
        let screen = try screen(4, 1, palette: .light)
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        #expect(builder.build(try screen.frame(), overlay: Overlay(), glyphs: Glyphs()).clear == InstanceBuilder.color(Palette.light.background))
    }

    @Test func theInstanceLayoutsAreTheShaders() {
        #expect(MemoryLayout<SolidInstance>.stride == 32)
        #expect(MemoryLayout<GlyphInstance>.stride == 48)
        #expect(MemoryLayout<GlyphInstance>.offset(of: \.slot) == 16)
        #expect(MemoryLayout<GlyphInstance>.offset(of: \.colour) == 24)
        #expect(MemoryLayout<GlyphInstance>.offset(of: \.color) == 32)
    }
}
