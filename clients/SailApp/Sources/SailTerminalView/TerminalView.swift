import AppKit
import os
import QuartzCore
import SailLook
import SailRenderer
import SailSurface
import SailTerminal
import SailVT
import Synchronization

private let terminalLog = Logger(subsystem: "sh.standardapplied.sail", category: "terminal")

/// A terminal session in an AppKit view. It draws only what the screen's frame says and sends only
/// what the screen's encoders make (V2). Output is drawn on the first display refresh after it
/// reached the screen: the display link is resumed by output and paused when a refresh finds
/// nothing to draw (V1). Input goes to the session on a queue of its own, in order, never on the
/// main thread, since a send may wait for a slow host.
@MainActor
public final class TerminalView: NSView {
    /// What the session tells, as it tells it: phases, the title, the bell, the working directory.
    public var onEvent: @MainActor (SessionEvent) -> Void = { _ in }
    public var onFocus: @MainActor () -> Void = {}
    /// The pasteboard ⌘C writes and ⌘V reads: the general one, unless a test gives another.
    public var pasteboard = NSPasteboard.general
    /// Asks the person whether to paste text of `lines` lines into a program that did not ask for
    /// pastes; the answer is given once. A sheet on the window unless replaced.
    public var confirmPaste: @MainActor (_ lines: Int, _ answer: @escaping @MainActor (Bool) -> Void) -> Void = { _, answer in answer(false) }
    public private(set) var link: (any TerminalLink)?
    public private(set) var fontPoints = FontLadder.standard
    /// The grid the view last gave the session.
    public private(set) var grid: GridSize?

    let renderer: TerminalRenderer
    let pacer = FramePacer()
    let marks = ViewMarks()
    let input = DispatchQueue(label: "sh.standardapplied.sail.terminal.input", qos: .userInteractive)
    /// Where the drawable is asked for: a thread of the view's own, so handing it the ask is a
    /// semaphore signal, with no request for a worker thread made from the main thread (``WorkThread``).
    private let fetcher = WorkThread(name: "sh.standardapplied.sail.terminal.drawable")
    var makeClock: @MainActor (TerminalView) -> any FrameClock = { view in
        DisplayLinkClock(view: view) { [weak view] target in
            view?.linkFires += 1
            _ = view?.refresh(target: target)
        }
    }
    var composition = Composition()
    var committedDuringKey: [String]?
    var pointer = Pointer()
    /// The layer's next drawable, always asked off the main thread, where its wait of up to a
    /// second blocks nothing (D1); a test withholds. Sendable, so it is not bound to the main actor.
    var nextDrawable: @Sendable (CAMetalLayer) -> (any CAMetalDrawable)? = { $0.nextDrawable() }
    /// Whether a window is on screen; a test, whose windows the window server never shows, says.
    var isOnScreen: (NSWindow) -> Bool = { $0.occlusionState.contains(.visible) }
    private var committedFrames = 0
    /// A drawable is being asked for off the main thread.
    private var fetching = false
    /// The drawable just brought, for the frame drawn on its arrival only.
    private var fetched: (any CAMetalDrawable)?
    /// A refresh wanted a frame and had no drawable: only then is one asked for.
    private var owed = false
    /// The cell the pointer last moved into with no button held.
    var hovered: CellPoint?
    /// The button went down on a view that was not focused: that click only focuses it.
    var focusingClick = false
    /// The right button's press went to the program, so its release does too.
    var rightReported = false
    /// The menu a right-click or a control-click offers; none unless the app gives one.
    public var contextMenu: @MainActor () -> NSMenu? = { nil }
    /// When the view last became its window's first responder, on the events' clock.
    var focusedAt = 0.0
    var gatherer = ScrollGatherer()
    private(set) var focused = false
    public private(set) var lastFrame: ScreenFrame?
    private var needsDraw = true
    private var tasks: [Task<Void, Never>] = []
    /// A blinking cursor is in its shown half.
    public private(set) var cursorOn = true
    /// Whether the cursor may blink at all; a test whose frames are counted turns it off.
    var cursorBlinks = true
    private var blink: Timer?
    /// How many times the blink's timer woke the main thread: what the drive counts.
    public private(set) var blinkWakeups = 0
    /// How many display refreshes reached the view, and how each ended: what an idle view's
    /// wakeups are made of, for the drive to report.
    public private(set) var refreshes = 0
    /// How many refreshes ended each way, indexed by ``RefreshOutcome``'s raw value: plain integers.
    public private(set) var refreshOutcomes = [Int](repeating: 0, count: RefreshOutcome.allCases.count)
    /// How many of those the display link asked for; the rest came from a drawable's arrival or
    /// from output or a key drawn at once.
    public internal(set) var linkFires = 0

    /// Whether a blink is due: the only thing that would wake this view while nothing changes.
    public var isBlinking: Bool {
        blink != nil
    }

    public init(fonts: TerminalFonts, points: Double = FontLadder.standard) throws(RenderError) {
        let scale = NSScreen.main.map { Double($0.backingScaleFactor) } ?? 2
        renderer = try TerminalRenderer(fonts: fonts, points: points, scale: scale)
        fontPoints = points
        super.init(frame: NSRect(x: 0, y: 0, width: 640, height: 400))
        wantsLayer = true
        focusRingType = .none
        configureLayer(scale: scale)
        confirmPaste = { [weak self] lines, answer in
            guard let self else { return answer(false) }
            presentPasteSheet(lines: lines, answer: answer)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var isFlipped: Bool {
        true
    }

    override public var acceptsFirstResponder: Bool {
        true
    }

    override public func makeBackingLayer() -> CALayer {
        CAMetalLayer()
    }

    var metalLayer: CAMetalLayer? {
        layer as? CAMetalLayer
    }

    /// How many drawables Core Animation keeps, 2 or 3.
    public var drawableCount: Int {
        get { metalLayer?.maximumDrawableCount ?? 3 }
        set { metalLayer?.maximumDrawableCount = newValue == 2 ? 2 : 3 }
    }

    public var latency: LatencyFigures {
        marks.figures
    }

    /// The drawable asks that overlap `span`, one still out included: what a late first frame is
    /// read against.
    public func drawableAsks(in span: Span) -> [Span] {
        marks.asks(in: span)
    }

    /// How many drawables came back at a size the layer no longer had, and were asked for again.
    public private(set) var drawablesThrownForSize = 0

    /// Where drawing stands, in words: frames presented and committed, the display link, its
    /// refreshes, and whether a drawable is awaited. For a step whose frames did not come.
    public var drawingState: String {
        let figures = latency
        return "frames presented \(figures.reportedPresents + figures.unreportedPresents), committed \(committedFrames); the display link "
            + "\(pacer.isIdle ? "paused" : "running") after \(figures.refresh.count) refreshes; \(fetching ? "" : "not ")awaiting a drawable; "
            + UnpresentedFrame.said(figures.unpresented, reported: marks.presentsToldWithATime, unreported: figures.unreportedPresents) + "; "
            + String(format: "commit to completed p50 %.1f max %.1f ms, to told presented p50 %.1f max %.1f ms", figures.commitToCompleted.p50,
                     figures.commitToCompleted.max, figures.commitToPresentTold.p50, figures.commitToPresentTold.max)
    }

    public func resetLatency() {
        marks.reset()
    }

    public var metrics: CellMetrics {
        renderer.metrics
    }

    /// Draws `link`'s screen and types into it, in place of any link before. A link is drawn by one
    /// view for its life, since its streams have one reader: move the view, not the session. A
    /// program's clipboard write (OSC 52) reaches the pasteboard: the session tells it only while
    /// this client holds the write token, and the screen refuses one over 1 MiB; reads stay refused.
    /// Option types characters.
    public func attach(_ link: any TerminalLink) {
        precondition(
            Self.canAttach(link),
            "this terminal session was already drawn by a view, and its screen and event streams end with that view: open or attach a new session, or move the view"
        )
        Self.attached.add(link)
        detach()
        self.link = link
        link.screen.setOptionAsAlt(.none)
        let cell = (width: metrics.width, height: metrics.height)
        input.async { try? link.setCellPixels(width: cell.width, height: cell.height) }
        grid = nil
        layoutGrid()
        let wake = ScreenWake(marks: marks) { [weak self] in
            MainActor.assumeIsolated { self?.drawNowIfIdle() }
        }
        link.onScreenChange { updated in wake.changed(at: CACurrentMediaTime() - (ContinuousClock.now - updated) / .seconds(1)) }
        let events = link.events
        tasks.append(Task { [weak self] in
            for await event in events {
                self?.handle(event)
            }
        })
        let focused = focused
        input.async { link.setFocused(focused) }
        needFrame()
    }

    /// Draws the screen in `palette` from the next frame on, its content, scrollback, selection and
    /// cursor kept; the link tells a program that subscribed of a changed scheme. A palette the
    /// screen refuses leaves the old colours, said in the system log.
    public func recolour(_ palette: Palette) {
        ink = (palette.selection, palette.idleCursor)
        guard let link else { return }
        input.async {
            do {
                try link.setPalette(palette)
            } catch {
                terminalLog.fault("a terminal kept its colours: the screen refused the new palette: \(String(describing: error), privacy: .public)")
            }
        }
    }

    /// Whether `link` may be attached: no view has drawn it before.
    public static func canAttach(_ link: any TerminalLink) -> Bool {
        !attached.contains(link)
    }

    /// Every link a view has attached, held weakly: its streams are spent.
    private static let attached = NSHashTable<AnyObject>.weakObjects()

    /// Stops drawing the session and typing into it, for good: the session itself is the caller's,
    /// and no view can attach it again.
    public func detach() {
        blink?.invalidate()
        blink = nil
        link?.onScreenChange(nil)
        tasks.forEach { $0.cancel() }
        tasks = []
        link = nil
        composition.unmark()
        lastFrame = nil
    }

    /// The last frame drawn, offscreen at the view's pixel size, every glyph it holds drawn: what
    /// the person saw once ``isDrawnInFull``.
    public func snapshot() throws(RenderError) -> RenderedImage? {
        guard let lastFrame else { return nil }
        return try renderer.image(of: lastFrame, overlay: overlay, size: pixelSize)
    }

    /// The visible text of the last frame drawn.
    public var visibleText: String {
        lastFrame.map { Screen.text(of: $0.rows) } ?? ""
    }

    /// Whether the last frame drawn showed every glyph, the cursor and composing text it holds:
    /// false while new glyphs wait for later refreshes. A ``snapshot()`` draws offscreen with no
    /// share, so until the next frame on the display this describes the snapshot.
    public var isDrawnInFull: Bool {
        renderer.lastFrameComplete
    }

    override public func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        NotificationCenter.default.removeObserver(self)
        guard let window else {
            pacer.stop()
            return
        }
        for name in [NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification] {
            NotificationCenter.default.addObserver(self, selector: #selector(keyWindowChanged), name: name, object: window)
        }
        NotificationCenter.default.addObserver(
            self, selector: #selector(visibilityChanged), name: NSWindow.didChangeOcclusionStateNotification, object: window
        )
        pacer.start(makeClock(self))
        viewDidChangeBackingProperties()
    }

    override public func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        let scale = window.map { Double($0.backingScaleFactor) } ?? renderer.faces.scale
        if scale != renderer.faces.scale {
            setFont(points: fontPoints, scale: scale)
        }
        configureLayer(scale: scale)
        layoutGrid()
    }

    /// A hidden view draws nothing and pauses its display link; shown, it draws its screen as it is.
    override public func viewDidHide() {
        super.viewDidHide()
        pacer.pause()
        updateBlink()
    }

    override public func viewDidUnhide() {
        super.viewDidUnhide()
        needFrame()
    }

    override public func setFrameSize(_ size: NSSize) {
        super.setFrameSize(size)
        configureLayer(scale: renderer.faces.scale)
        layoutGrid(sizeChanging: true)
    }

    /// The font at `points` from the ladder's range: the cell, the grid and every glyph again.
    public func setFontSize(_ points: Double) {
        let size = min(FontLadder.sizes.last ?? points, max(FontLadder.sizes.first ?? points, points))
        guard size != fontPoints else { return }
        setFont(points: size, scale: renderer.faces.scale)
    }

    private func setFont(points: Double, scale: Double) {
        do {
            try renderer.setFont(points: points, scale: scale)
        } catch {
            return
        }
        fontPoints = points
        if let link {
            let cell = (width: metrics.width, height: metrics.height)
            input.async { try? link.setCellPixels(width: cell.width, height: cell.height) }
        }
        layoutGrid()
        needFrame()
    }

    private var pixelSize: PixelSize {
        let scale = renderer.faces.scale
        return PixelSize(
            width: max(1, Int((Double(bounds.width) * scale).rounded())), height: max(1, Int((Double(bounds.height) * scale).rounded()))
        )
    }

    /// The layer at the view's pixel size and `scale`, changed only where it differs: a drawable
    /// size set again, even to the same value, costs the layer its drawables.
    private func configureLayer(scale: Double) {
        guard let metalLayer else { return }
        if metalLayer.device == nil {
            metalLayer.device = renderer.device
            metalLayer.pixelFormat = .bgra8Unorm
            metalLayer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
            metalLayer.framebufferOnly = true
        }
        let size = CGSize(width: pixelSize.width, height: pixelSize.height)
        guard metalLayer.contentsScale != CGFloat(scale) || metalLayer.drawableSize != size else { return }
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = size
        needFrame()
    }

    /// The grid that fits the view, given to the session at once when it changed: the screen
    /// reflows now, and the session tells the host once the size has held. While the view's size is
    /// changing (`sizeChanging`, a drag or a window resize) the session is told even when the grid
    /// is not, so its hold counts from the last change of the view, not of the grid (W5): a drag
    /// that crosses a column only now and then still gives the program one size, at its end.
    func layoutGrid(sizeChanging: Bool = false) {
        guard let link else { return }
        let fitted = metrics.grid(fitting: pixelSize)
        let size = GridSize(
            columns: min(fitted.columns, TerminalSession.largestGrid.columns), rows: min(fitted.rows, TerminalSession.largestGrid.rows)
        )
        guard size != grid else {
            if sizeChanging { input.async { try? link.resize(size) } }
            return
        }
        grid = size
        input.async { [weak self] in
            try? link.resize(size)
            Task { @MainActor in self?.needFrame() }
        }
    }

    var overlay: Overlay {
        Overlay(focused: holdsTheKeys, cursorOn: cursorOn, marked: composition.marked, selection: ink.selection, idleCursor: ink.idleCursor)
    }

    /// The palette's selection and idle cursor, the look's fixed colours.
    private var ink: (selection: SelectionInk?, idleCursor: RGB?) = (nil, nil)

    /// Whether its cursor marks where the keys go: focused, and its session not ended.
    public var holdsTheKeys: Bool {
        focused && !inert
    }

    /// Takes no keys any more (its session ended): the cursor is drawn as without focus.
    public var inert = false {
        didSet { if inert != oldValue { needFrame() } }
    }

    /// Something the view shows changed: draw at the next refresh.
    func needFrame() {
        needsDraw = true
        guard !isHiddenOrHasHiddenAncestor else { return }
        pacer.resume()
    }

    /// Output reached the screen, or a key was written (which returns a scrolled-back view to the
    /// bottom). A paused link means the view is idle, the typing-echo case: the screen is read now
    /// and drawn if it changed, and only a frame drawn resumes the link, so a key that changed
    /// nothing leaves the view idle and its echo is drawn the moment it arrives, meeting the next
    /// display refresh rather than the one after.
    func drawNowIfIdle() {
        guard pacer.isIdle, !isHiddenOrHasHiddenAncestor, refresh(target: CACurrentMediaTime() + refreshInterval) else { return }
        pacer.resume()
    }

    /// Asks for the layer's next drawable on the view's own thread, at the moment a frame needs one
    /// (Apple: acquire it as late as possible), unless an ask is under way or the window is off
    /// screen, where the occlusion notification asks again.
    private func fetchDrawable() {
        guard !fetching, let metalLayer, window.map(isOnScreen) ?? false else { return }
        fetching = true
        marks.fetchStarted()
        let fetch = DrawableFetch(view: self, layer: metalLayer, next: nextDrawable)
        fetcher.run {
            fetch.drawable = fetch.next(fetch.layer)
            DispatchQueue.main.async {
                MainActor.assumeIsolated { fetch.view?.arrived(fetch.drawable) }
            }
        }
    }

    /// A drawable of the layer's size draws the owed frame now, reading the screen as it is (D2),
    /// and is let go if that frame does not use it; none, the one-second wait over, asks again
    /// while the frame is still owed and the view shown.
    private func arrived(_ drawable: (any CAMetalDrawable)?) {
        fetching = false
        marks.fetchEnded()
        guard let drawable, let metalLayer,
              drawable.texture.width == Int(metalLayer.drawableSize.width), drawable.texture.height == Int(metalLayer.drawableSize.height)
        else {
            if drawable != nil { drawablesThrownForSize += 1 }
            if owed, !isHiddenOrHasHiddenAncestor { fetchDrawable() }
            return
        }
        owed = false
        fetched = drawable
        defer { fetched = nil }
        if !isHiddenOrHasHiddenAncestor, refresh(target: CACurrentMediaTime() + refreshInterval) { pacer.resume() }
    }

    private var refreshInterval: Double {
        1 / Double(max(1, window?.screen?.maximumFramesPerSecond ?? 60))
    }

    /// The window was seen again: a shown view that owes a frame asks for its drawable.
    @objc private func visibilityChanged() {
        if owed, !isHiddenOrHasHiddenAncestor { fetchDrawable() }
        updateBlink()
    }

    /// One display refresh, for the frame meant to reach the display at `target`: with no drawable
    /// just brought, ask for one and draw on its arrival; else read the screen and draw when it or
    /// the view changed, or pause until something does. True when the link is wanted for the next
    /// refresh: a frame was drawn, or every buffer was still in flight, or the frame could not be
    /// encoded (tried again then). A frame drawn only for the view (a blink, focus, a selection)
    /// leaves the link paused: the screen did not change, so no output is under way.
    @discardableResult
    func refresh(target: Double) -> Bool {
        refreshes += 1
        guard let link else {
            pacer.pause()
            return refreshed(.noSession, false)
        }
        guard !isHiddenOrHasHiddenAncestor else {
            needsDraw = true
            pacer.pause()
            return refreshed(.hidden, false)
        }
        guard fetched != nil else {
            owed = true
            pacer.pause()
            fetchDrawable()
            return refreshed(.askedForDrawable, false)
        }
        let read = CACurrentMediaTime()
        let work = Duration.threadCPU
        defer { marks.refreshed(since: read, workSince: work) }
        guard let frame = try? link.screen.frame() else {
            pacer.pause()
            return refreshed(.noFrame, false)
        }
        let screenChanged = frame.changed != .nothing || frame.cursor != lastFrame?.cursor || frame.colors != lastFrame?.colors
            || frame.size != lastFrame?.size
        guard needsDraw || screenChanged, let metalLayer else {
            marks.unchanged(read: read)
            pacer.pause()
            return refreshed(.nothingChanged, false)
        }
        switch renderer.draw(frame, overlay: overlay, from: { defer { self.fetched = nil }; return self.fetched }, presented: { [marks] in marks.presented(frame: $0, at: $1) }, completed: { [marks] in marks.completed(frame: $0, ok: $1) }) {
        case let .drawn(number):
            committedFrames += 1
            marks.committed(frame: number, read: read, target: target)
            lastFrame = frame
            needsDraw = renderer.lastFrameWantsAnother
            updateBlink()
            return refreshed(.drew, screenChanged || needsDraw)
        case .busy:
            marks.skipped(read: read)
            renderer.invalidate()
            needsDraw = true
            return refreshed(.buffersInFlight, true)
        case .unavailable:
            marks.skipped(read: read)
            renderer.invalidate()
            needsDraw = true
            return refreshed(.couldNotEncode, true)
        }
    }

    private func handle(_ event: SessionEvent) {
        if case .screen(.bell) = event, !NSApp.isActive {
            NSApp.requestUserAttention(.informationalRequest)
        }
        if case let .screen(.clipboardWrite(text)) = event {
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
        }
        onEvent(event)
    }

    @objc private func keyWindowChanged() {
        updateFocus()
    }

    override public func becomeFirstResponder() -> Bool {
        focusedAt = ProcessInfo.processInfo.systemUptime
        updateFocus(responder: true)
        onFocus()
        return true
    }

    override public func resignFirstResponder() -> Bool {
        updateFocus(responder: false)
        return true
    }

    /// Focused while the window is key and the view its first responder; `responder` says which
    /// the view is becoming, while AppKit has not recorded it yet.
    private func updateFocus(responder: Bool? = nil) {
        let now = window?.isKeyWindow == true && (responder ?? (window?.firstResponder === self))
        guard now != focused else { return }
        focused = now
        if let link {
            input.async { link.setFocused(now) }
        }
        needFrame()
        updateBlink()
    }

    private func refreshed(_ outcome: RefreshOutcome, _ wanted: Bool) -> Bool {
        refreshOutcomes[outcome.rawValue] += 1
        return wanted
    }

    /// The system's insertion-point blink: `NSTextInsertionPointBlinkPeriodOn` and `…Off`, in ms,
    /// the keys AppKit's text view reads (Chromium's `ui/base/cocoa/defaults_utils.mm` reads them
    /// for the same reason); 500 ms each when unset.
    public static var blinkPeriods: (on: Double, off: Double) {
        func period(_ key: String) -> Double {
            let milliseconds = UserDefaults.standard.integer(forKey: key)
            return milliseconds > 0 ? Double(milliseconds) / 1000 : 0.5
        }
        return (period("NSTextInsertionPointBlinkPeriodOn"), period("NSTextInsertionPointBlinkPeriodOff"))
    }

    /// The cursor blinks while the screen says it does (DECSCUSR, mode 12) and the view shows it
    /// focused: never in a hidden, unfocused or unseen view (P2), nor while text is composed.
    private var shouldBlink: Bool {
        guard cursorBlinks, focused, !isHiddenOrHasHiddenAncestor, let window, isOnScreen(window), composition.marked.isEmpty,
              let cursor = lastFrame?.cursor, cursor.blinking, cursor.visible, cursor.position != nil
        else { return false }
        return true
    }

    /// Starts or stops the blink as ``shouldBlink`` says; stopped, the cursor shows. `restart`
    /// shows it for a whole period, as a key typed does, so it never blinks while keys come.
    func updateBlink(restart: Bool = false) {
        guard shouldBlink else {
            blink?.invalidate()
            blink = nil
            showCursor()
            return
        }
        guard restart || blink == nil else { return }
        showCursor()
        scheduleBlink(after: Self.blinkPeriods.on)
    }

    private func showCursor() {
        guard !cursorOn else { return }
        cursorOn = true
        needFrame()
    }

    private func scheduleBlink(after seconds: Double) {
        blink?.invalidate()
        let timer = Timer(timeInterval: seconds, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.blinked() }
        }
        timer.tolerance = seconds / 10
        RunLoop.main.add(timer, forMode: .common)
        blink = timer
    }

    /// Half a blink: the cursor shown or hidden, one frame drawn (P2: no row laid out again). An
    /// idle view asks for its drawable at once, as typing's echo does, so the display link is not
    /// woken for it.
    private func blinked() {
        blinkWakeups += 1
        blink = nil
        guard shouldBlink else { return updateBlink() }
        cursorOn.toggle()
        needsDraw = true
        drawNowIfIdle()
        let periods = Self.blinkPeriods
        scheduleBlink(after: cursorOn ? periods.on : periods.off)
    }

    /// Sends what `key` encodes to, on the input queue, so the main thread never takes the
    /// screen's lock for a key; a press typed while scrolled back returns the view to the bottom
    /// first. A release or a modifier alone (`typed` false) only goes to the encoder, which sends
    /// it to a program that asked for such reports. `event` is the key's time on the host clock.
    func send(_ key: KeyInput, at event: Double, typed: Bool = true) {
        guard let link else { return }
        guard typed, key.action != .release else {
            input.async { link.send(key) }
            return
        }
        updateBlink(restart: true)
        let marks = marks
        let interval = marks.keyBegan()
        input.async { [weak self] in
            link.screen.scrollToBottom()
            let sent = CACurrentMediaTime()
            link.send(key)
            marks.keyWritten(event: event, sent: sent, interval: interval)
            Task { @MainActor in self?.drawNowIfIdle() }
        }
    }

    isolated deinit {
        blink?.invalidate()
        link?.onScreenChange(nil)
        tasks.forEach { $0.cancel() }
    }

    override public func isAccessibilityElement() -> Bool {
        true
    }

    override public func accessibilityRole() -> NSAccessibility.Role? {
        .textArea
    }

    override public func accessibilityValue() -> Any? {
        visibleText
    }
}

/// A view's wake for its screen's changes, told on the session's own thread: a change that finds no
/// wake outstanding posts one block to the main queue, which takes the wake, marks the oldest change
/// it carries (so output to shown is the longest any of them waited) and, when it carries more than
/// one, the newest (so a key whose echo came in a later change is put to it), and draws the screen as
/// it is then. The session's thread only takes a lock and, at most once per wake, posts; nothing between a
/// change and the main queue runs on a shared pool, and however fast a session's screen changes, one
/// wake per view waits on the main queue at a time.
final class ScreenWake: Sendable {
    private struct Outstanding {
        var oldest: Double
        var newest: Double
        var changes: Int
        /// Which wake this is, so its hand-off's stamps are written into it and no later one.
        var wake: UInt64
        /// When the session's thread began to hand the wake to the queue.
        var handing: Double
        /// When that hand-off had returned; nil when the queue took the wake first. The two
        /// bracket the hand-off, so a thread kept from the CPU around it is never read as either
        /// side's time alone.
        var handed: Double?
    }

    private struct State {
        var outstanding: Outstanding?
        var wakes: UInt64 = 0
    }

    private let handOver: @Sendable (@escaping @Sendable () -> Void) -> Void
    private let marks: ViewMarks
    private let draw: @Sendable () -> Void
    /// The changes since the queue last took the wake (nil while none is outstanding), and how
    /// many wakes there have been.
    private let state = Mutex(State())

    /// A wake that `queue`, the main queue unless a test gives another, takes to call `draw`.
    convenience init(on queue: DispatchQueue = .main, marks: ViewMarks, draw: @escaping @Sendable () -> Void) {
        self.init(handingOver: { queue.async(execute: $0) }, marks: marks, draw: draw)
    }

    /// A wake that `handOver` gives the queue: a test hands it over its own way.
    init(handingOver handOver: @escaping @Sendable (@escaping @Sendable () -> Void) -> Void, marks: ViewMarks, draw: @escaping @Sendable () -> Void) {
        self.handOver = handOver
        self.marks = marks
        self.draw = draw
    }

    func changed(at time: Double) {
        let wake = state.withLock { state -> UInt64? in
            guard state.outstanding == nil else {
                state.outstanding?.changes += 1
                state.outstanding?.newest = time
                return nil
            }
            state.wakes &+= 1
            state.outstanding = Outstanding(oldest: time, newest: time, changes: 1, wake: state.wakes, handing: CACurrentMediaTime(), handed: nil)
            return state.wakes
        }
        guard let wake else { return }
        handOver { self.taken() }
        let handed = CACurrentMediaTime()
        state.withLock { state in
            if state.outstanding?.wake == wake { state.outstanding?.handed = handed }
        }
    }

    /// The wake is cleared before the screen is read, so a change after the read posts another.
    private func taken() {
        guard let taken = state.withLock({ state in
            defer { state.outstanding = nil }
            return state.outstanding
        }) else { return }
        marks.output(at: taken.oldest)
        if taken.changes > 1 { marks.output(at: taken.newest) }
        marks.told(since: taken.oldest, handing: taken.handing, handed: taken.handed, changes: taken.changes)
        draw()
    }
}

/// One ask for a drawable, carried to the view's own thread and its answer back to the main thread.
/// Apple's pages say `nextDrawable()` may block until a drawable is free, and that with
/// `allowsNextDrawableTimeout` (on by default) it returns nil after one second; so it is not
/// called on the main thread. Unchecked: the ask is touched by one thread at a time, the main
/// thread, that thread, then the main thread.
private final class DrawableFetch: @unchecked Sendable {
    weak var view: TerminalView?
    let layer: CAMetalLayer
    let next: @Sendable (CAMetalLayer) -> (any CAMetalDrawable)?
    var drawable: (any CAMetalDrawable)?

    init(view: TerminalView, layer: CAMetalLayer, next: @escaping @Sendable (CAMetalLayer) -> (any CAMetalDrawable)?) {
        self.view = view
        self.layer = layer
        self.next = next
    }
}

/// How a display refresh ended: what an idle view's wakeups are made of.
public enum RefreshOutcome: Int, CaseIterable, Sendable {
    case noSession, hidden, askedForDrawable, noFrame, nothingChanged, drew, buffersInFlight, couldNotEncode
}

extension TerminalView: Marked {
    /// The cursor, in the accent only where the keys go; elsewhere it is hollow ink.
    package var drawnMarks: [Mark] {
        holdsTheKeys ? [.accentLine] : []
    }
}
