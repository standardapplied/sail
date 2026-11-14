import GhosttyVt
import Synchronization

/// The exact screen of one terminal session, kept by libghostty-vt: what a renderer draws, and the
/// bytes a key, a paste, a focus change or the mouse encode to. No escape sequence is parsed and
/// no input is encoded here; this is libghostty-vt's, driven in order.
///
/// Bytes are fed from one thread and frames read from another: the terminal has its lock, and a
/// frame holds it only while the render state copies what it needs (`begin_update`); reading the
/// rows out happens under the render state's own lock, while the terminal goes on.
public final class Screen: Sendable {
    private let terminal: Mutex<TerminalCore>
    private let render: Mutex<RenderCache>

    /// A screen of `size` keeping about `scrollbackBytes` of history, in `palette`'s colours.
    /// Refused when the loaded libghostty-vt is not 1.x from 1.0.1.
    public init(size: GridSize, scrollbackBytes: Int, palette: Palette) throws(ScreenError) {
        try LibraryVersion.check(LibraryVersion.loaded)
        guard scrollbackBytes >= 0 else { throw .invalid("a scrollback of \(scrollbackBytes) bytes is not one") }
        guard palette.ansi.count == 16 else { throw .invalid("a palette names 16 colours, not \(palette.ansi.count)") }
        terminal = Mutex(try TerminalCore(size: try Self.checked(size), scrollbackBytes: scrollbackBytes, palette: palette))
        render = Mutex(try RenderCache(palette: palette))
    }

    /// Takes the next bytes of the program's output, in order, and answers what they provoked.
    public func feed(_ bytes: [UInt8]) -> Effects {
        terminal.withLock { core in
            bytes.withUnsafeBufferPointer { ghostty_terminal_vt_write(core.handle, $0.baseAddress, $0.count) }
            return core.drain()
        }
    }

    /// Resizes the grid at once; text reflows as libghostty-vt reflows it.
    public func resize(_ size: GridSize) throws(ScreenError) {
        let size = try Self.checked(size)
        try terminal.withLock { (core: inout TerminalCore) throws(ScreenError) in try core.resize(size) }
    }

    /// The pixel size of one cell, which size reports and pixel mouse reports need.
    public func setCellPixels(width: Int, height: Int) throws(ScreenError) {
        guard width >= 0, height >= 0 else { throw .invalid("a cell of \(width)x\(height) pixels is not one") }
        try terminal.withLock { (core: inout TerminalCore) throws(ScreenError) in
            core.cellPixels = (UInt32(width), UInt32(height))
            try core.resize(core.size)
        }
    }

    /// A replay of the session's history follows: the terminal starts again from nothing, and what
    /// the replay provokes is history, so nothing is answered, rung or written for it.
    public func beginReplay() {
        terminal.withLock { core in
            ghostty_terminal_reset(core.handle)
            core.sink.replaying = true
        }
    }

    /// The replay is over: what follows is live, and the view returns to the bottom.
    public func endReplay() {
        terminal.withLock { core in
            core.sink.replaying = false
            core.scrollToBottom()
        }
    }

    /// Back to a blank terminal, every mode reset.
    public func reset() {
        terminal.withLock { ghostty_terminal_reset($0.handle) }
    }

    public func scrollToBottom() {
        terminal.withLock { $0.scrollToBottom() }
    }

    /// Whether a program may write the clipboard; allowed until the app says otherwise.
    public func setClipboardWrites(_ policy: ClipboardWritePolicy) {
        terminal.withLock { $0.sink.clipboardPolicy = policy }
    }

    /// Which option keys type as alt; libghostty-vt cannot know it from the terminal.
    public func setOptionAsAlt(_ optionAsAlt: OptionAsAlt) {
        terminal.withLock { $0.optionAsAlt = optionAsAlt }
    }

    /// What a renderer draws now, and which rows changed since the last frame.
    public func frame() throws(ScreenError) -> ScreenFrame {
        try render.withLock { (cache: inout RenderCache) throws(ScreenError) in
            try refresh(cache)
            return cache.frame()
        }
    }

    /// The viewport as plain text: each row's graphemes, trailing blanks dropped, then trailing
    /// blank rows. Reading it leaves the next frame's changes as they were.
    public func text() throws(ScreenError) -> String {
        try render.withLock { (cache: inout RenderCache) throws(ScreenError) in
            try refresh(cache)
            return Self.text(of: cache.rows)
        }
    }

    /// `rows` as plain text.
    public static func text(of rows: [ScreenFrame.Row]) -> String {
        let lines = rows.map { row in
            String(row.cells.compactMap { cell -> String? in
                switch cell.width {
                case .spacerTail, .spacerHead: nil
                case .narrow, .wide: cell.grapheme.isEmpty ? " " : cell.grapheme
                }
            }.joined().reversed().drop { $0 == " " }.reversed())
        }
        return lines.reversed().drop(while: \.isEmpty).reversed().joined(separator: "\n")
    }

    /// The bytes `key` sends, encoded by libghostty-vt from the terminal's modes as they are now
    /// (cursor keys, keypad, the Kitty flags a program pushed); empty for a key that sends none.
    public func encode(_ key: KeyInput) -> [UInt8] {
        terminal.withLock { $0.encode(key) }
    }

    /// The bytes pasting `text` sends: bracketed when the program asked (mode 2004). Text that
    /// could run commands in a program that did not ask is answered ``PasteOutcome/needsConfirmation``
    /// and sent only when `confirmed`.
    public func paste(_ text: String, confirmed: Bool = false) -> PasteOutcome {
        terminal.withLock { $0.paste(text, confirmed: confirmed) }
    }

    /// The focus report for the view gaining or losing focus; empty unless the program asked for
    /// focus reports (mode 1004).
    public func focus(_ focused: Bool) -> [UInt8] {
        terminal.withLock { $0.focus(focused) }
    }

    /// The bytes a pointer event sends; empty unless the program tracks the mouse.
    public func encode(_ mouse: MouseInput) -> [UInt8] {
        terminal.withLock { $0.encode(mouse) }
    }

    /// Scrolls `lines` (up is negative) at a pointer place: the wheel's report to a program that
    /// tracks the mouse, arrow keys to a full-screen program that asked for alternate scroll (mode
    /// 1007), or else the viewport through its history, which sends nothing.
    public func scroll(lines: Int, x: Double, y: Double) -> [UInt8] {
        terminal.withLock { $0.scroll(lines: lines, x: x, y: y) }
    }

    /// Selects what `request` names; false when there is nothing there to select.
    @discardableResult
    public func select(_ request: SelectionRequest) -> Bool {
        terminal.withLock { $0.select(request) }
    }

    public func clearSelection() {
        terminal.withLock { _ = ghostty_terminal_set($0.handle, GHOSTTY_TERMINAL_OPT_SELECTION, nil) }
    }

    /// The selected text, wrapped lines joined; nil with no selection.
    public func selectedText() -> String? {
        terminal.withLock { $0.selectedText() }
    }

    private func refresh(_ cache: RenderCache) throws(ScreenError) {
        let state = cache.handle
        try cache.finishUpdate(begun: terminal.withLock { ghostty_render_state_begin_update(state.pointer, $0.handle) })
    }

    private static func checked(_ size: GridSize) throws(ScreenError) -> GridSize {
        guard (1 ... Int(UInt16.max)).contains(size.columns), (1 ... Int(UInt16.max)).contains(size.rows) else {
            throw .invalid("a terminal of \(size) cells is not one: each side is 1 through 65535")
        }
        return size
    }
}
