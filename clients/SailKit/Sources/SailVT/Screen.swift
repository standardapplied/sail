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
/// Bytes are fed in slices of ``feedSlice``, each under its own taking of the lock, and callers on
/// any other thread take turns with the feeder. A caller (a frame, a key's encoding, a paste)
/// takes a ticket. A turn falls due once ``handOffEvery`` slices were fed since the last, or
/// ``turnEvery`` passed: then the feeder, before its next slice, lets in every ticket taken so far,
/// and a ticket taken meanwhile waits for the next turn; with no feed running, a caller takes a
/// due turn itself. So a caller waits behind at most ``handOffEvery`` slices or ``turnEvery``,
/// however many feeds follow back to back (`Mutex` alone would let the feeder take the lock
/// straight back), and callers that come back as fast as they can still leave output the lock for
/// that long between their turns. The feeding thread's own calls (a replay's start, the hold's
/// remaining time) wait for no turn, and a `frame()` or `text()` with no output fed since the last
/// one takes no ticket while the lock is free and nobody waits.
public final class Screen: Sendable {
    /// The longest a program may hold the screen still; then the hold is ended and frames go on.
    public static let holdLimit = Duration.seconds(1)
    /// The most bytes fed under one taking of the lock.
    public static let feedSlice = 16 * 1024
    /// The fewest slices fed between two turns: 512 KiB, about 1.5 ms of feeding on Linux and 4 ms
    /// on a macOS runner, against a frame's 0.6 and 1.4 ms.
    public static let handOffEvery = 32
    /// The longest between two turns, so a caller is let in when output slows or stops.
    public static let turnEvery = Duration.milliseconds(5)
    /// The largest pixel size of one cell this screen takes.
    public static let largestCellPixels = 4096

    private let parts: Mutex<Parts>
    /// Tickets taken, numbered from 1.
    private let tickets = Atomic<Int>(0)
    /// Tickets whose caller has taken the lock.
    private let served = Atomic<Int>(0)
    /// The last ticket the open hand-off lets in, ``closed`` with none open, ``opening`` while it
    /// counts.
    private let admitting = Atomic<Int>(closed)
    private static let closed = Int.min
    private static let opening = Int.max
    /// Slices fed so far, and at the last turn.
    private let slicesFed = Atomic<Int>(0)
    private let turnAt = Atomic<Int>(-handOffEvery)
    /// Nanoseconds from ``origin`` to the last turn.
    private let turnTime = Atomic<Int>(Int.min / 2)
    private let origin = ContinuousClock.now
    /// Feeds running, and the thread that fed last.
    private let feeders = Atomic<Int>(0)
    private let feederThread = Atomic<UInt>(0)
    /// Slices fed when the last frame or text was read.
    private let readAt = Atomic<Int>(-1)
    /// Turns so far, which the tests bound.
    package let turns = Atomic<Int>(0)

    /// Slices fed so far, and tickets served, which the tests count.
    package var slices: Int {
        slicesFed.load(ordering: .sequentiallyConsistent)
    }

    package var ticketsServed: Int {
        served.load(ordering: .sequentiallyConsistent)
    }

    private let turnGap: Int

    private struct Parts {
        let core: TerminalCore
        let cache: RenderCache
    }

    /// A screen of `size` keeping about `scrollbackBytes` of history, in `palette`'s colours.
    /// Refused when the loaded libghostty-vt is not 1.x from 1.0.1.
    public convenience init(size: GridSize, scrollbackBytes: Int, palette: Palette) throws(ScreenError) {
        try self.init(size: size, scrollbackBytes: scrollbackBytes, palette: palette, now: { ContinuousClock.now })
    }

    /// As the public one, its render holds timed on `now`, and a turn due at least every
    /// `turnEvery`.
    package init(
        size: GridSize, scrollbackBytes: Int, palette: Palette, now: @escaping @Sendable () -> ContinuousClock.Instant,
        turnEvery: Duration = Screen.turnEvery
    ) throws(ScreenError) {
        turnGap = Self.nanoseconds(turnEvery)
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
        feederThread.store(Self.thread(), ordering: .sequentiallyConsistent)
        feeders.add(1, ordering: .sequentiallyConsistent)
        defer { feeders.subtract(1, ordering: .sequentiallyConsistent) }
        var effects = Effects()
        for start in stride(from: 0, to: bytes.count, by: Self.feedSlice) {
            handOff()
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
        return try ahead { (parts: Parts, _: Wait) throws(ScreenError) -> Effects in
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
        return try ahead { (parts: Parts, _: Wait) throws(ScreenError) -> Effects in
            parts.core.cellPixels = (UInt32(width), UInt32(height))
            try parts.core.resize(parts.core.size)
            return parts.core.drain()
        }
    }

    /// A replay of the session's history follows: the terminal starts again from nothing, the
    /// colours a program set included, and what the replay provokes is history, so nothing is
    /// answered, rung or written for it.
    public func beginReplay() {
        ahead { (parts: Parts, _: Wait) in
            parts.core.sink.replaying = true
            parts.core.restart()
            _ = parts.core.drain()
        }
    }

    /// The replay is over: what follows is live, and the view returns to the bottom.
    public func endReplay() {
        ahead { (parts: Parts, _: Wait) in
            parts.core.sink.replaying = false
            parts.core.scrollToBottom()
        }
    }

    /// Back to a blank terminal, every mode reset.
    public func reset() {
        ahead { parts, _ in parts.core.restart() }
    }

    public func scrollToBottom() {
        ahead { parts, _ in parts.core.scrollToBottom() }
    }

    /// Whether a program may write the clipboard; allowed until the app says otherwise.
    public func setClipboardWrites(_ policy: ClipboardWritePolicy) {
        ahead { parts, _ in parts.core.sink.clipboardPolicy = policy }
    }

    /// Which option keys type as alt; libghostty-vt cannot know it from the terminal.
    public func setOptionAsAlt(_ optionAsAlt: OptionAsAlt) {
        ahead { parts, _ in parts.core.optionAsAlt = optionAsAlt }
    }

    /// What a renderer draws now, and which rows changed since the last frame.
    public func frame() throws(ScreenError) -> ScreenFrame {
        try timedFrame().frame
    }

    /// A frame, and what it waited for the lock: how long, which also counts waiting for a CPU, how
    /// many slices the feeder ran first, which counts only the feeder's fairness, and whether it
    /// took a ticket.
    package func timedFrame() throws(ScreenError) -> (frame: ScreenFrame, wait: Wait) {
        try read { (parts: Parts, wait: Wait) throws(ScreenError) in
            try Self.refresh(parts)
            return (parts.cache.frame(), wait)
        }
    }

    /// How long until the program's current render hold reaches ``holdLimit`` and frames go on,
    /// nil with no hold: a renderer that draws only on change draws again then.
    public var holdRemaining: Duration? {
        ahead { (parts: Parts, _: Wait) in
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
    public func encode(_ key: KeyInput) -> [UInt8] {
        ahead { parts, _ in parts.core.encode(key) }
    }

    /// The bytes pasting `text` sends: bracketed when the program asked (mode 2004). Text that
    /// could run commands in a program that did not ask is answered ``PasteOutcome/needsConfirmation``
    /// and sent only when `confirmed`.
    public func paste(_ text: String, confirmed: Bool = false) -> PasteOutcome {
        ahead { parts, _ in parts.core.paste(text, confirmed: confirmed) }
    }

    /// The focus report for the view gaining or losing focus; empty unless the program asked for
    /// focus reports (mode 1004).
    public func focus(_ focused: Bool) -> [UInt8] {
        ahead { parts, _ in parts.core.focus(focused) }
    }

    /// The bytes a pointer event sends; empty unless the program tracks the mouse.
    public func encode(_ mouse: MouseInput) -> [UInt8] {
        ahead { parts, _ in parts.core.encode(mouse) }
    }

    /// Scrolls `lines` (up is negative) at a pointer place: the wheel's report to a program that
    /// tracks the mouse, arrow keys to a full-screen program that asked for alternate scroll (mode
    /// 1007), or else the viewport through its history, which sends nothing.
    public func scroll(lines: Int, x: Double, y: Double) -> [UInt8] {
        ahead { parts, _ in parts.core.scroll(lines: lines, x: x, y: y) }
    }

    /// Selects what `request` names; false when there is nothing there to select.
    @discardableResult
    public func select(_ request: SelectionRequest) -> Bool {
        ahead { parts, _ in parts.core.select(request) }
    }

    public func clearSelection() {
        ahead { parts, _ in _ = ghostty_terminal_set(parts.core.handle, GHOSTTY_TERMINAL_OPT_SELECTION, nil) }
    }

    /// The selected text, wrapped lines joined; nil with no selection.
    public func selectedText() -> String? {
        ahead { parts, _ in parts.core.selectedText() }
    }

    /// What a caller waited for the lock.
    package struct Wait: Sendable {
        package let time: Duration
        package let slices: Int
        package let ticketed: Bool
    }

    /// When a turn is due, lets in every ticket taken so far. Every access is sequentially
    /// consistent: a ticket taken after the count sees the hand-off open and waits for the next
    /// turn, and with a feed running no caller takes the lock but through a hand-off, so the
    /// feeder waits only for the callers it counted, each on its way to the lock.
    private func handOff() {
        guard turnDue(), tickets.load(ordering: .sequentiallyConsistent) > served.load(ordering: .sequentiallyConsistent) else { return }
        admitting.store(Self.opening, ordering: .sequentiallyConsistent)
        let last = tickets.load(ordering: .sequentiallyConsistent)
        admitting.store(last, ordering: .sequentiallyConsistent)
        while served.load(ordering: .sequentiallyConsistent) < last {
            sched_yield()
        }
        takeTurn()
        admitting.store(Self.closed, ordering: .sequentiallyConsistent)
    }

    private func turnDue() -> Bool {
        slicesFed.load(ordering: .sequentiallyConsistent) - turnAt.load(ordering: .sequentiallyConsistent) >= Self.handOffEvery
            || elapsed() - turnTime.load(ordering: .sequentiallyConsistent) >= turnGap
    }

    private func takeTurn() {
        turnAt.store(slicesFed.load(ordering: .sequentiallyConsistent), ordering: .sequentiallyConsistent)
        turnTime.store(elapsed(), ordering: .sequentiallyConsistent)
        turns.add(1, ordering: .sequentiallyConsistent)
    }

    private func elapsed() -> Int {
        Self.nanoseconds(ContinuousClock.now - origin)
    }

    private static func nanoseconds(_ duration: Duration) -> Int {
        let (seconds, attoseconds) = duration.components
        return Int(seconds) &* 1_000_000_000 &+ Int(attoseconds / 1_000_000_000)
    }

    private static func thread() -> UInt {
        #if canImport(Darwin)
        UInt(bitPattern: pthread_self())
        #else
        UInt(pthread_self())
        #endif
    }

    /// Runs `body` under the lock in this caller's turn: at the feeder's next hand-off, or, with
    /// no feed running, once a turn is due; at once on the feeding thread. Nothing between taking
    /// the ticket and serving it can fail, so every ticket is served.
    private func ahead<Value: Sendable, Failure: Error>(_ body: (Parts, Wait) throws(Failure) -> Value) throws(Failure) -> Value {
        let asked = ContinuousClock.now
        let ticket = tickets.wrappingAdd(1, ordering: .sequentiallyConsistent).newValue
        let fedBefore = slicesFed.load(ordering: .sequentiallyConsistent)
        if feederThread.load(ordering: .sequentiallyConsistent) != Self.thread() {
            awaitTurn(ticket)
        }
        return try parts.withLock { (parts: inout Parts) throws(Failure) -> Value in
            served.add(1, ordering: .sequentiallyConsistent)
            let wait = Wait(time: ContinuousClock.now - asked, slices: slicesFed.load(ordering: .sequentiallyConsistent) - fedBefore, ticketed: true)
            return try body(parts, wait)
        }
    }

    private func awaitTurn(_ ticket: Int) {
        while true {
            let admitted = admitting.load(ordering: .sequentiallyConsistent)
            if admitted == Self.closed {
                if feeders.load(ordering: .sequentiallyConsistent) == 0, turnDue() {
                    takeTurn()
                    return
                }
            } else if admitted != Self.opening, ticket <= admitted {
                return
            }
            sched_yield()
        }
    }

    /// A frame's or text's read: with no output fed since the last read, no ticket waiting and the
    /// lock free, taken at once without a ticket; else through ``ahead(_:)``. A read without a
    /// ticket while one waits could take the lock again and again ahead of it, and so hold up the
    /// hand-off waiting for that ticket.
    private func read<Value: Sendable>(_ body: (Parts, Wait) throws(ScreenError) -> Value) throws(ScreenError) -> Value {
        if slicesFed.load(ordering: .sequentiallyConsistent) == readAt.load(ordering: .sequentiallyConsistent),
           tickets.load(ordering: .sequentiallyConsistent) == served.load(ordering: .sequentiallyConsistent)
        {
            let quiet = try parts.withLockIfAvailable { (parts: inout Parts) throws(ScreenError) -> Value in
                try body(parts, Wait(time: .zero, slices: 0, ticketed: false))
            }
            if let quiet { return quiet }
        }
        return try ahead { (parts: Parts, wait: Wait) throws(ScreenError) -> Value in
            readAt.store(slicesFed.load(ordering: .sequentiallyConsistent), ordering: .sequentiallyConsistent)
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
