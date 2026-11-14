import GhosttyVt

/// The render state of one screen and the rows read out of it, kept between frames so an update
/// reads again only the rows libghostty-vt marked dirty, or every row when the colours changed. It
/// lives inside the screen's lock, with the terminal.
final class RenderCache {
    private let state: Owned
    private let rowIterator: Owned
    private let rowCells: Owned
    private(set) var rows: [ScreenFrame.Row] = []
    private(set) var cursor = Cursor(position: nil, style: .block, visible: true, blinking: false)
    private(set) var colors: DefaultColors
    private var columns = 0
    /// What changed since the last frame was handed out, across every update in between.
    private var unseen = ScreenFrame.Change.all
    private var grapheme = [UInt8](repeating: 0, count: 64)
    private var palette: [RGB] = []
    /// The colours of the last update, byte for byte, palette included.
    private var colorPrint: [UInt8] = []
    private let paletteCursor: RGB

    init(palette: Palette) throws(ScreenError) {
        state = try Owned.make("ghostty_render_state_new", { ghostty_render_state_new(nil, $0) }) { ghostty_render_state_free($0) }
        rowIterator = try Owned.make("ghostty_render_state_row_iterator_new", { ghostty_render_state_row_iterator_new(nil, $0) }) {
            ghostty_render_state_row_iterator_free($0)
        }
        rowCells = try Owned.make("ghostty_render_state_row_cells_new", { ghostty_render_state_row_cells_new(nil, $0) }) {
            ghostty_render_state_row_cells_free($0)
        }
        colors = DefaultColors(foreground: palette.foreground, background: palette.background, cursor: palette.cursor)
        paletteCursor = palette.cursor
    }

    /// The render state, which a render hold's start updates from inside the terminal's write.
    var pointer: GhosttyRenderState {
        state.pointer
    }

    /// Copies what the terminal has now into the render state.
    func update(from terminal: GhosttyTerminal) throws(ScreenError) {
        try require("ghostty_render_state_update", ghostty_render_state_update(state.pointer, terminal))
    }

    /// The frame as it stands, with everything that changed since the frame before.
    func frame() -> ScreenFrame {
        defer { unseen = .nothing }
        return ScreenFrame(
            size: GridSize(columns: columns, rows: rows.count), rows: rows, cursor: cursor, colors: colors,
            changed: unseen
        )
    }

    /// Reads into the rows what the render state holds that the rows do not.
    func absorb() throws(ScreenError) {
        var dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE
        try get(GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty)
        var width: UInt16 = 0
        var height: UInt16 = 0
        try get(GHOSTTY_RENDER_STATE_DATA_COLS, &width)
        try get(GHOSTTY_RENDER_STATE_DATA_ROWS, &height)
        let recoloured = try readColors()
        try readCursor()
        let resized = Int(width) != columns || Int(height) != rows.count
        guard dirty != GHOSTTY_RENDER_STATE_DIRTY_FALSE || resized || recoloured else { return }
        let full = dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL || resized || recoloured
        columns = Int(width)
        if resized {
            rows = Array(repeating: ScreenFrame.Row(cells: []), count: Int(height))
        }
        var iterator: OpaquePointer? = rowIterator.pointer
        try get(GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &iterator)
        var changed = Set<Int>()
        var y = 0
        while y < rows.count, ghostty_render_state_row_iterator_next(rowIterator.pointer) {
            var rowDirty = false
            try require("ghostty_render_state_row_get", ghostty_render_state_row_get(rowIterator.pointer, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &rowDirty))
            if full || rowDirty {
                rows[y] = try readRow()
                changed.insert(y)
            }
            y += 1
        }
        try require("ghostty_render_state_clean", ghostty_render_state_clean(state.pointer))
        unseen = unseen.merged(with: full ? .all : changed.isEmpty ? .nothing : .rows(changed))
    }

    private func readRow() throws(ScreenError) -> ScreenFrame.Row {
        var cells: OpaquePointer? = rowCells.pointer
        try require("ghostty_render_state_row_get", ghostty_render_state_row_get(rowIterator.pointer, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &cells))
        var row: [Cell] = []
        row.reserveCapacity(columns)
        while row.count < columns, ghostty_render_state_row_cells_next(rowCells.pointer) {
            row.append(try readCell())
        }
        return ScreenFrame.Row(cells: row)
    }

    private func readCell() throws(ScreenError) -> Cell {
        let cells = rowCells.pointer
        var raw = GhosttyCell()
        try cellGet(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw)
        var selected = false
        try cellGet(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected)
        if Self.isBlank(raw) {
            return Cell(
                grapheme: "", width: .narrow, foreground: colors.foreground, background: colors.background, attributes: [],
                underline: .none, underlineColor: nil, selected: selected
            )
        }
        var wide = GHOSTTY_CELL_WIDE_NARROW
        try require("ghostty_cell_get", ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide))
        var style = GhosttyStyle()
        try cellGet(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style)
        var foreground = colors.foreground
        var background = colors.background
        try optionalColor(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, into: &foreground)
        try optionalColor(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, into: &background)
        let attributes = CellAttributes(style.flags)
        if attributes.contains(.inverse) {
            swap(&foreground, &background)
        }
        if attributes.contains(.dim) {
            foreground = foreground.blended(toward: background, by: Palette.dimming)
        }
        return Cell(
            grapheme: try readGrapheme(), width: CellWidth(wide), foreground: foreground, background: background,
            attributes: attributes, underline: Underline(style.flags), underlineColor: color(style.underline_color),
            selected: selected
        )
    }

    /// Whether `raw` is a narrow cell with no text, no style and no colour of its own: a default
    /// cell, read no further.
    private static func isBlank(_ raw: GhosttyCell) -> Bool {
        var text = true
        var styled = true
        var tag = GHOSTTY_CELL_CONTENT_CODEPOINT
        var wide = GHOSTTY_CELL_WIDE_WIDE
        return ghostty_cell_get(raw, GHOSTTY_CELL_DATA_HAS_TEXT, &text) == GHOSTTY_SUCCESS && !text
            && ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide) == GHOSTTY_SUCCESS && wide == GHOSTTY_CELL_WIDE_NARROW
            && ghostty_cell_get(raw, GHOSTTY_CELL_DATA_HAS_STYLING, &styled) == GHOSTTY_SUCCESS && !styled
            && ghostty_cell_get(raw, GHOSTTY_CELL_DATA_CONTENT_TAG, &tag) == GHOSTTY_SUCCESS && tag == GHOSTTY_CELL_CONTENT_CODEPOINT
    }

    private func readGrapheme() throws(ScreenError) -> String {
        var buffer = GhosttyBuffer()
        var result = grapheme.withUnsafeMutableBufferPointer { bytes in
            buffer = GhosttyBuffer(ptr: bytes.baseAddress, cap: bytes.count, len: 0)
            return ghostty_render_state_row_cells_get(rowCells.pointer, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &buffer)
        }
        if result == GHOSTTY_OUT_OF_SPACE {
            grapheme = [UInt8](repeating: 0, count: buffer.len)
            result = grapheme.withUnsafeMutableBufferPointer { bytes in
                buffer = GhosttyBuffer(ptr: bytes.baseAddress, cap: bytes.count, len: 0)
                return ghostty_render_state_row_cells_get(rowCells.pointer, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &buffer)
            }
        }
        try require("ghostty_render_state_row_cells_get(graphemes)", result)
        return String(decoding: grapheme[..<buffer.len], as: UTF8.self)
    }

    /// Reads the default colours and the palette: whether any differ from the last update's (an
    /// OSC 10, 11 or 4, or their resets), which every cell drawn in a default colour depends on.
    private func readColors() throws(ScreenError) -> Bool {
        var read = GhosttyRenderStateColors()
        read.size = MemoryLayout<GhosttyRenderStateColors>.size
        try get(GHOSTTY_RENDER_STATE_DATA_COLORS, &read)
        let print = withUnsafeBytes(of: &read) { Array($0) }
        guard print != colorPrint else { return false }
        colorPrint = print
        colors = DefaultColors(
            foreground: RGB(read.foreground), background: RGB(read.background),
            cursor: read.cursor_has_value ? RGB(read.cursor) : paletteCursor
        )
        palette = withUnsafeBytes(of: &read.palette) { Array($0.bindMemory(to: GhosttyColorRgb.self)).map(RGB.init) }
        return true
    }

    /// The colour a style's colour names, nil when it names none.
    private func color(_ color: GhosttyStyleColor) -> RGB? {
        switch Int(color.tag) {
        case Int(GHOSTTY_STYLE_COLOR_RGB.rawValue): RGB(color.value.rgb)
        case Int(GHOSTTY_STYLE_COLOR_PALETTE.rawValue): palette.indices.contains(Int(color.value.palette)) ? palette[Int(color.value.palette)] : nil
        default: nil
        }
    }

    private func readCursor() throws(ScreenError) {
        var read = GhosttyRenderStateCursor()
        read.size = MemoryLayout<GhosttyRenderStateCursor>.size
        try get(GHOSTTY_RENDER_STATE_DATA_CURSOR, &read)
        cursor = Cursor(
            position: read.viewport_has_value ? CellPoint(x: Int(read.viewport_x), y: Int(read.viewport_y)) : nil,
            style: CursorStyle(read.visual_style), visible: read.visible, blinking: read.blinking
        )
    }

    private func get<Value>(_ data: GhosttyRenderStateData, _ value: inout Value) throws(ScreenError) {
        let result = withUnsafeMutablePointer(to: &value) { ghostty_render_state_get(state.pointer, data, UnsafeMutableRawPointer($0)) }
        try require("ghostty_render_state_get(\(data.rawValue))", result)
    }

    private func cellGet<Value>(_ cells: OpaquePointer, _ data: GhosttyRenderStateRowCellsData, _ value: inout Value) throws(ScreenError) {
        let result = withUnsafeMutablePointer(to: &value) { ghostty_render_state_row_cells_get(cells, data, UnsafeMutableRawPointer($0)) }
        try require("ghostty_render_state_row_cells_get(\(data.rawValue))", result)
    }

    /// The cell's own colour into `color`, left as the default when the cell has none.
    private func optionalColor(_ cells: OpaquePointer, _ data: GhosttyRenderStateRowCellsData, into color: inout RGB) throws(ScreenError) {
        var read = GhosttyColorRgb()
        let result = ghostty_render_state_row_cells_get(cells, data, &read)
        guard result != GHOSTTY_NO_VALUE else { return }
        try require("ghostty_render_state_row_cells_get(\(data.rawValue))", result)
        color = RGB(read)
    }
}

extension RGB {
    init(_ color: GhosttyColorRgb) {
        self.init(red: color.r, green: color.g, blue: color.b)
    }

    var ghostty: GhosttyColorRgb {
        GhosttyColorRgb(r: red, g: green, b: blue)
    }
}

private extension CellAttributes {
    /// The attributes a style's flags hold (`style.h`: bit 0 bold, 1 italic, 2 faint, 3 blink,
    /// 4 inverse, 5 invisible, 6 strikethrough, 7 overline), packed in the order of ``CellAttributes``.
    init(_ flags: GhosttyStyleFlags) {
        let bits = UInt8(truncatingIfNeeded: flags)
        self.init(rawValue: (bits & 0b111) | (bits >> 1 & 0b111000) | (bits << 3 & 0b0100_0000) | (bits & 0b1000_0000))
    }
}

private extension Underline {
    /// The underline style in bits 8 to 10 of a style's flags, a `GhosttySgrUnderline`.
    init(_ flags: GhosttyStyleFlags) {
        switch GhosttySgrUnderline(rawValue: .init((flags >> 8) & 0x7)) {
        case GHOSTTY_SGR_UNDERLINE_SINGLE: self = .single
        case GHOSTTY_SGR_UNDERLINE_DOUBLE: self = .double
        case GHOSTTY_SGR_UNDERLINE_CURLY: self = .curly
        case GHOSTTY_SGR_UNDERLINE_DOTTED: self = .dotted
        case GHOSTTY_SGR_UNDERLINE_DASHED: self = .dashed
        default: self = .none
        }
    }
}

private extension CellWidth {
    init(_ wide: GhosttyCellWide) {
        switch wide {
        case GHOSTTY_CELL_WIDE_WIDE: self = .wide
        case GHOSTTY_CELL_WIDE_SPACER_TAIL: self = .spacerTail
        case GHOSTTY_CELL_WIDE_SPACER_HEAD: self = .spacerHead
        default: self = .narrow
        }
    }
}

private extension CursorStyle {
    init(_ style: GhosttyCursorStyle) {
        switch style {
        case GHOSTTY_CURSOR_STYLE_BAR: self = .bar
        case GHOSTTY_CURSOR_STYLE_UNDERLINE: self = .underline
        case GHOSTTY_CURSOR_STYLE_BLOCK_HOLLOW: self = .blockHollow
        default: self = .block
        }
    }
}
