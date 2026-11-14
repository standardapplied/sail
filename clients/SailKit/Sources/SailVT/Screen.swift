#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
import GhosttyVt
import Synchronization

/// The exact screen of one terminal session, kept by libghostty-vt: what a renderer draws, and the
/// bytes a key, a paste, a focus change or the mouse encode to. No escape sequence is parsed and
/// no input is encoded here; this is libghostty-vt's, driven in order.
///
/// Bytes are fed from one thread and frames read from another, under one lock for the terminal and
/// its render state. One lock is what a render hold needs: when a program starts synchronized
/// output (mode 2026), the render state is updated from inside the terminal's write, on the feeding
/// thread, so it keeps the last finished frame until the program ends the hold, or the hold has
/// lasted ``holdLimit``.
///
/// Bytes are fed in slices of ``feedSlice``, each under its own taking of the lock, and before each
/// slice the feeder lets a reader that is waiting go first: a `frame()` or `text()` announces
/// itself, and the feeder yields its thread until that reader holds the lock. So a frame waits for
/// at most the slice being fed when it asked, however many feeds follow back to back (`Mutex` alone
/// would let the feeder take the lock straight back).
public final class Screen: Sendable {
    /// The longest a program may hold the screen still; then the hold is ended and frames go on.
    public static let holdLimit = Duration.seconds(1)
    /// The most bytes fed under one taking of the lock.
    public static let feedSlice = 16 * 1024
    /// The largest pixel size of one cell this screen takes.
    public static let largestCellPixels = 4096

    private let parts: Mutex<Parts>
    /// Readers waiting for the lock, which the feeder lets in between slices.
    private let waitingReaders = Atomic<Int>(0)
    /// Slices fed so far: how a reader counts the slices it waited behind.
    private let slicesFed = Atomic<Int>(0)

    private struct Parts {
        let core: TerminalCore
        let cache: RenderCache
    }

    /// A screen of `size` keeping about `scrollbackBytes` of history, in `palette`'s colours.
    /// Refused when the loaded libghostty-vt is not 1.x from 1.0.1.
    public convenience init(size: GridSize, scrollbackBytes: Int, palette: Palette) throws(ScreenError) {
        try self.init(size: size, scrollbackBytes: scrollbackBytes, palette: palette, now: { ContinuousClock.now })
    }

    /// As the public one, its render holds timed on `now`.
    package init(
        size: GridSize, scrollbackBytes: Int, palette: Palette, now: @escaping @Sendable () -> ContinuousClock.Instant
    ) throws(ScreenError) {
        try LibraryVersion.check(LibraryVersion.loaded)
        guard scrollbackBytes >= 0 else { throw .invalid("a scrollback of \(scrollbackBytes) bytes is not one") }
        guard palette.ansi.count == 16 else { throw .invalid("a palette names 16 colours, not \(palette.ansi.count)") }
        let core = try TerminalCore(size: try Self.checked(size), scrollbackBytes: scrollbackBytes, palette: palette, now: now)
        let cache = try RenderCache(palette: palette)
        core.sink.renderState = cache.pointer
        parts = Mutex(Parts(core: core, cache: cache))
    }

    /// Takes the next bytes of the program's output, in order, and answers what they provoked.
    public func feed(_ bytes: [UInt8]) -> Effects {
        var effects = Effects()
        for start in stride(from: 0, to: bytes.count, by: Self.feedSlice) {
            while waitingReaders.load(ordering: .sequentiallyConsistent) > 0 {
                sched_yield()
            }
            let fed = parts.withLock { parts in
                bytes[start ..< min(bytes.count, start + Self.feedSlice)].withUnsafeBufferPointer {
                    ghostty_terminal_vt_write(parts.core.handle, $0.baseAddress, $0.count)
                }
                slicesFed.add(1, ordering: .sequentiallyConsistent)
                return parts.core.drain()
            }
            effects.reply += fed.reply
            effects.events += fed.events
        }
        return effects
    }

    /// Resizes the grid at once; text reflows as libghostty-vt reflows it. What the resize owes
    /// the program (an in-band size report, mode 2048) is answered.
    @discardableResult
    public func resize(_ size: GridSize) throws(ScreenError) -> Effects {
        let size = try Self.checked(size)
        return try parts.withLock { (parts: inout Parts) throws(ScreenError) -> Effects in
            try parts.core.resize(size)
            return parts.core.drain()
        }
    }

    /// The pixel size of one cell, which size reports and pixel mouse reports need; at most
    /// ``largestCellPixels`` a side.
    @discardableResult
    public func setCellPixels(width: Int, height: Int) throws(ScreenError) -> Effects {
        guard (0 ... Self.largestCellPixels).contains(width), (0 ... Self.largestCellPixels).contains(height) else {
            throw .invalid("a cell of \(width)x\(height) pixels is not one: each side is 0 through \(Self.largestCellPixels)")
        }
        return try parts.withLock { (parts: inout Parts) throws(ScreenError) -> Effects in
            parts.core.cellPixels = (UInt32(width), UInt32(height))
            try parts.core.resize(parts.core.size)
            return parts.core.drain()
        }
    }

    /// A replay of the session's history follows: the terminal starts again from nothing, the
    /// colours a program set included, and what the replay provokes is history, so nothing is
    /// answered, rung or written for it.
    public func beginReplay() {
        parts.withLock { parts in
            parts.core.sink.replaying = true
            parts.core.restart()
            _ = parts.core.drain()
        }
    }

    /// The replay is over: what follows is live, and the view returns to the bottom.
    public func endReplay() {
        parts.withLock { parts in
            parts.core.sink.replaying = false
            parts.core.scrollToBottom()
        }
    }

    /// Back to a blank terminal, every mode reset.
    public func reset() {
        parts.withLock { $0.core.restart() }
    }

    public func scrollToBottom() {
        parts.withLock { $0.core.scrollToBottom() }
    }

    /// Whether a program may write the clipboard; allowed until the app says otherwise.
    public func setClipboardWrites(_ policy: ClipboardWritePolicy) {
        parts.withLock { $0.core.sink.clipboardPolicy = policy }
    }

    /// Which option keys type as alt; libghostty-vt cannot know it from the terminal.
    public func setOptionAsAlt(_ optionAsAlt: OptionAsAlt) {
        parts.withLock { $0.core.optionAsAlt = optionAsAlt }
    }

    /// What a renderer draws now, and which rows changed since the last frame. Meant to be called
    /// once per display refresh (a display link): the feeder lets every waiting reader in before
    /// its next slice, so a caller that polls in a tight loop slows output (measured with 1 MiB
    /// feeds back to back: one such reader about 32 MiB/s, two about 0.33 MiB/s).
    public func frame() throws(ScreenError) -> ScreenFrame {
        try timedFrame().frame
    }

    /// A frame, and what it waited for the lock: how long, which also counts waiting for a CPU, and
    /// how many slices the feeder ran first, which counts only the feeder's fairness.
    package func timedFrame() throws(ScreenError) -> (frame: ScreenFrame, waited: Duration, slicesWaited: Int) {
        try read { (parts: Parts, wait: Wait) throws(ScreenError) in
            try Self.refresh(parts)
            return (parts.cache.frame(), wait.time, wait.slices)
        }
    }

    /// How long until the program's current render hold reaches ``holdLimit`` and frames go on,
    /// nil with no hold: a renderer that draws only on change draws again then.
    public var holdRemaining: Duration? {
        parts.withLock { parts in
            parts.core.sink.heldSince.map { max(.zero, $0 + Self.holdLimit - parts.core.sink.now()) }
        }
    }

    /// The viewport as plain text: each row's graphemes, trailing blanks dropped, then trailing
    /// blank rows. Reading it leaves the next frame's changes as they were.
    public func text() throws(ScreenError) -> String {
        try read { (parts: Parts, _: Wait) throws(ScreenError) in
            try Self.refresh(parts)
            return Self.text(of: parts.cache.rows)
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
    /// The encoders take the lock without the readers' hand-off: through a session, where reading
    /// the socket and decoding leave gaps between feeds, a key waits at most about 0.25 ms (p95,
    /// measured through floods of 4 KiB to 1 MiB outputs); with `feed()` called back to back and no
    /// gap at all, it can wait hundreds of milliseconds (measured 372 ms at p95).
    public func encode(_ key: KeyInput) -> [UInt8] {
        parts.withLock { $0.core.encode(key) }
    }

    /// The bytes pasting `text` sends: bracketed when the program asked (mode 2004). Text that
    /// could run commands in a program that did not ask is answered ``PasteOutcome/needsConfirmation``
    /// and sent only when `confirmed`.
    public func paste(_ text: String, confirmed: Bool = false) -> PasteOutcome {
        parts.withLock { $0.core.paste(text, confirmed: confirmed) }
    }

    /// The focus report for the view gaining or losing focus; empty unless the program asked for
    /// focus reports (mode 1004).
    public func focus(_ focused: Bool) -> [UInt8] {
        parts.withLock { $0.core.focus(focused) }
    }

    /// The bytes a pointer event sends; empty unless the program tracks the mouse.
    public func encode(_ mouse: MouseInput) -> [UInt8] {
        parts.withLock { $0.core.encode(mouse) }
    }

    /// Whether the program asked for mouse reports: a view gives it the pointer instead of
    /// selecting.
    public var tracksMouse: Bool {
        parts.withLock { $0.core.tracksMouse }
    }

    /// Scrolls `lines` (up is negative) at a pointer place: the wheel's report to a program that
    /// tracks the mouse, arrow keys to a full-screen program that asked for alternate scroll (mode
    /// 1007), or else the viewport through its history, which sends nothing.
    public func scroll(lines: Int, x: Double, y: Double) -> [UInt8] {
        parts.withLock { $0.core.scroll(lines: lines, x: x, y: y) }
    }

    /// Selects what `request` names; false when there is nothing there to select.
    @discardableResult
    public func select(_ request: SelectionRequest) -> Bool {
        parts.withLock { $0.core.select(request) }
    }

    public func clearSelection() {
        parts.withLock { _ = ghostty_terminal_set($0.core.handle, GHOSTTY_TERMINAL_OPT_SELECTION, nil) }
    }

    /// The selected text, wrapped lines joined; nil with no selection.
    public func selectedText() -> String? {
        parts.withLock { $0.core.selectedText() }
    }

    private struct Wait {
        let time: Duration
        let slices: Int
    }

    /// Runs `body` under the lock as a reader the feeder lets in before its next slice. Every access
    /// is sequentially consistent, so a feeder checking after the announcement sees it: the reader
    /// waits behind at most the one slice already past its check.
    private func read<Value: Sendable>(_ body: (Parts, Wait) throws(ScreenError) -> Value) throws(ScreenError) -> Value {
        let asked = ContinuousClock.now
        waitingReaders.add(1, ordering: .sequentiallyConsistent)
        let fedBefore = slicesFed.load(ordering: .sequentiallyConsistent)
        var announced = true
        defer { if announced { waitingReaders.subtract(1, ordering: .sequentiallyConsistent) } }
        return try parts.withLock { (parts: inout Parts) throws(ScreenError) -> Value in
            waitingReaders.subtract(1, ordering: .sequentiallyConsistent)
            announced = false
            let wait = Wait(time: ContinuousClock.now - asked, slices: slicesFed.load(ordering: .sequentiallyConsistent) - fedBefore)
            return try body(parts, wait)
        }
    }

    /// Brings the rows up to date, unless the program holds the screen still: then the rows keep
    /// the frame captured when the hold began, until it ends or outlasts ``holdLimit``.
    private static func refresh(_ parts: Parts) throws(ScreenError) {
        _ = parts.core.holdOutlasted(holdLimit)
        if parts.core.sink.heldSince == nil {
            try parts.cache.update(from: parts.core.handle)
        }
        try parts.cache.absorb()
    }

    private static func checked(_ size: GridSize) throws(ScreenError) -> GridSize {
        guard (1 ... Int(UInt16.max)).contains(size.columns), (1 ... Int(UInt16.max)).contains(size.rows) else {
            throw .invalid("a terminal of \(size) cells is not one: each side is 1 through 65535")
        }
        return size
    }
}
