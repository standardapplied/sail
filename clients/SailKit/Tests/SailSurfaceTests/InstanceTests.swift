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

    /// P2: a blink's frame lays out no row; it differs from the frame before only in the cursor.
    @Test func aBlinkLaysOutNoRowAndChangesOnlyTheCursor() throws {
        let screen = try screen(10, 3).fed("one\r\ntwo")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs()
        let shown = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        let hidden = builder.build(try screen.frame(), overlay: Overlay(cursorOn: false), glyphs: glyphs)
        #expect(builder.rebuilt.isEmpty)
        #expect(hidden.backgrounds == shown.backgrounds)
        #expect(hidden.glyphs == shown.glyphs)
        #expect(hidden.decorations.count == shown.decorations.count - 1, "the cursor's one instance, and only it, is gone")
        #expect(shown.decorations.starts(with: hidden.decorations))
        let back = builder.build(try screen.frame(), overlay: Overlay(cursorOn: true), glyphs: glyphs)
        #expect(builder.rebuilt.isEmpty)
        #expect(back == shown)
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

    /// The cursor is placed before the rows spend the frame's share of new glyphs, so it is drawn
    /// in the first frame.
    @Test func theCursorIsPlacedBeforeTheRowsSpendTheShare() throws {
        let glyphs = try AtlasGlyphs(side: 1024)
        glyphs.room = 2
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let drawn = builder.build(try screen(10, 2).fed("ab").frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(drawn.decorations.count == 1, "the cursor")
        #expect(!builder.complete, "one of the two letters waits for the next frame")
    }

    /// A frame whose cursor or composing text found no slot is not complete, so the caller draws
    /// again at the next refresh.
    @Test(arguments: ["", "にほ"]) func anOverlayWithoutASlotLeavesTheFrameIncomplete(marked: String) throws {
        let glyphs = try AtlasGlyphs(side: 1024)
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let screen = try screen(10, 2).fed("ab\u{1b}[?25l")
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: glyphs)
        #expect(builder.complete)
        glyphs.frame = 2
        glyphs.room = 0
        _ = screen.feed(Array("\u{1b}[?25h".utf8))
        let drawn = builder.build(try screen.frame(), overlay: Overlay(marked: marked), glyphs: glyphs)
        #expect(drawn.decorations.isEmpty)
        #expect(!builder.complete, "the \(marked.isEmpty ? "cursor" : "composing text") found no slot")
        glyphs.frame = 3
        glyphs.room = .max
        let later = builder.build(try screen.frame(), overlay: Overlay(marked: marked), glyphs: glyphs)
        #expect(!later.decorations.isEmpty)
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

    /// A selection with fixed colours, as a theme's in Ghostty, draws every selected cell in them
    /// whatever a program set: over light text on a dark cell and dark text on a light one alike,
    /// so selected text stays readable; the unselected cells keep their own colours.
    @Test(arguments: [("light on dark", "\u{1b}[38;2;250;250;250;48;2;20;20;20m"), ("dark on light", "\u{1b}[38;2;20;20;20;48;2;250;250;250m")])
    func aSelectionDrawsInItsOwnColoursOverAnyProgramsColours(_ name: String, _ colours: String) throws {
        let screen = try screen(10, 2).fed(colours + "hello")
        #expect(screen.select(.range(from: CellPoint(x: 0, y: 0), to: CellPoint(x: 1, y: 0))))
        let ink = SelectionInk(foreground: RGB(0x0E1217), background: RGB(0xF9D7D1))
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let drawn = builder.build(try screen.frame(), overlay: Overlay(selection: ink), glyphs: Glyphs())
        let selected = InstanceBuilder.color(ink.background)
        #expect(drawn.backgrounds.first?.color == selected && drawn.backgrounds.first?.rect.z == 36, "\(name): the selection's background")
        #expect(drawn.backgrounds.dropFirst().allSatisfy { $0.color != selected }, "\(name): an unselected cell took the selection")
        #expect(drawn.glyphs.prefix(2).allSatisfy { $0.color == InstanceBuilder.color(ink.foreground) }, "\(name): the selected glyphs")
        #expect(drawn.glyphs.dropFirst(2).allSatisfy { $0.color != InstanceBuilder.color(ink.foreground) }, "\(name): an unselected glyph took the selection's ink")
    }

    /// A new selection ink (a look switched) lays every row out again: the rows drawn in the old
    /// one are not kept.
    @Test func aNewSelectionInkLaysEveryRowOutAgain() throws {
        let screen = try screen(10, 3).fed("one\r\ntwo")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        _ = builder.build(try screen.frame(), overlay: Overlay(), glyphs: Glyphs())
        _ = builder.build(try screen.frame(), overlay: Overlay(selection: SelectionInk(foreground: RGB(0xFFFFFF), background: RGB(0x532017))), glyphs: Glyphs())
        #expect(builder.rebuilt == [0, 1, 2])
    }

    /// Calm: a cursor marking no keys (the view without focus, or a terminal that ended) is hollow
    /// in the idle ink; only the one with the keys is in the cursor's colour.
    @Test func aCursorWithoutTheKeysIsHollowInTheIdleInk() throws {
        let screen = try screen(10, 2).fed("ab\u{1b}[1;1H")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let idle = RGB(0x8C8B88)
        let away = builder.build(try screen.frame(), overlay: Overlay(focused: false, idleCursor: idle), glyphs: Glyphs())
        #expect(away.decorations.count == 1 && away.decorations.first?.color == InstanceBuilder.color(idle))
        let typing = builder.build(try screen.frame(), overlay: Overlay(focused: true, idleCursor: idle), glyphs: Glyphs())
        #expect(typing.decorations.first?.color == InstanceBuilder.color(Palette.dark.cursor))
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
        try #require(drawn.decorations.count == 6)
        #expect(drawn.decorations[1].rect == SIMD4(56, 16, 36, 40))
        #expect(drawn.decorations[4].rect == SIMD4(92, 16, 36, 40))
        #expect(glyphs.asked.contains(.glyph("に", .regular, wide: true)))
        #expect(!glyphs.asked.contains(.sprite(.cursor(.block), wide: false)))
        let clipped = builder.build(try screen.frame(), overlay: Overlay(marked: "にほんごです"), glyphs: Glyphs())
        #expect(clipped.decorations.count == 4 * 3, "the row holds four of the six from column 2")
    }

    /// A link is underlined, single, in the default foreground, across exactly its cells and in
    /// its row, after the cursor; it lays no row out again, and one off the grid draws nothing.
    @Test func aLinkIsUnderlinedAcrossItsCells() throws {
        let screen = try screen(20, 2).fed("see src/a.ts:4 here")
        var builder = try InstanceBuilder(metrics: metrics(points: 15))
        let glyphs = Glyphs()
        let plain = builder.build(try screen.frame(), overlay: Overlay(cursorOn: false), glyphs: glyphs)
        let linked = builder.build(try screen.frame(), overlay: Overlay(cursorOn: false, link: OverlayLink(row: 0, columns: 4 ..< 14)), glyphs: glyphs)
        #expect(builder.rebuilt.isEmpty, "the link laid rows out again: \(builder.rebuilt)")
        let underline = linked.decorations.dropFirst(plain.decorations.count)
        #expect(underline.count == 10)
        #expect(underline.map(\.rect.x) == (4 ..< 14).map { Float(20 + $0 * 18) }, "\(underline.map(\.rect))")
        #expect(underline.allSatisfy { $0.rect.y == 16 && $0.color == InstanceBuilder.color(try! screen.frame().colors.foreground) })
        #expect(glyphs.asked.contains(.sprite(.underline(.single), wide: false)))
        let off = builder.build(try screen.frame(), overlay: Overlay(cursorOn: false, link: OverlayLink(row: 5, columns: 0 ..< 3)), glyphs: glyphs)
        #expect(off.decorations.count == plain.decorations.count && builder.complete)
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
