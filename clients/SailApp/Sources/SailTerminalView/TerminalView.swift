import AppKit
import QuartzCore
import SailLook
import SailRenderer
import SailSurface
import SailTerminal
import SailVT

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
    var makeClock: @MainActor (TerminalView) -> any FrameClock = { view in
        DisplayLinkClock(view: view) { [weak view] target in _ = view?.refresh(target: target) }
    }
    var composition = Composition()
    var committedDuringKey: [String]?
    var pointer = Pointer()
    /// The layer's next drawable, asked on the main thread or, while one is awaited, off it; a
    /// test hands out none. Sendable, so a closure made on the main actor is not bound to it.
    var nextDrawable: @Sendable (CAMetalLayer) -> (any CAMetalDrawable)? = { $0.nextDrawable() }
    /// Whether a window is on screen; a test, whose windows the window server never shows, says.
    var isOnScreen: (NSWindow) -> Bool = { $0.occlusionState.contains(.visible) }
    /// The last refresh found no drawable.
    private var awaitingDrawable = false
    private var committedFrames = 0
    /// An ask for a drawable is under way off the main thread.
    private var askingForDrawable = false
    /// Whether the window was on screen when last looked at while a drawable was awaited.
    private var wasOnScreen = false
    /// A drawable that ask brought, for the next frame.
    private var broughtDrawable: (any CAMetalDrawable)?
    /// The cell the pointer last moved into with no button held.
    var hovered: CellPoint?
    /// The button went down on a view that was not focused: that click only focuses it.
    var focusingClick = false
    /// When the view last became its window's first responder, on the events' clock.
    var focusedAt = 0.0
    var gatherer = ScrollGatherer()
    private(set) var focused = false
    private(set) var lastFrame: ScreenFrame?
    private var needsDraw = true
    private var tasks: [Task<Void, Never>] = []

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

    public var atlasBytes: Int {
        renderer.atlasUploadBytes
    }

    public var framesCommitted: Int {
        committedFrames
    }

    /// Where drawing stands, in words: frames presented and committed, the display link, its
    /// refreshes, and whether a drawable is awaited. For a step whose frames did not come.
    public var drawingState: String {
        let figures = latency
        return "frames presented \(figures.reportedPresents + figures.unreportedPresents), committed \(committedFrames); the display link "
            + "\(pacer.isIdle ? "paused" : "running") after \(figures.refresh.count) refreshes; \(awaitingDrawable ? "" : "not ")awaiting a drawable"
    }

    /// Starts the latency figures from nothing.
    public func resetLatency() {
        marks.reset()
    }

    public var metrics: CellMetrics {
        renderer.metrics
    }

    /// Draws `link`'s screen and types into it, in place of any link before. A link is drawn by one
    /// view for its life, since its streams have one reader: move the view, not the session.
    /// Programs may not write the clipboard (it changes only on the person's action) and Option
    /// types characters.
    public func attach(_ link: any TerminalLink) {
        precondition(
            Self.canAttach(link),
            "this terminal session was already drawn by a view, and its screen and event streams end with that view: open or attach a new session, or move the view"
        )
        Self.attached.add(link)
        detach()
        self.link = link
        link.screen.setClipboardWrites(.deny)
        link.screen.setOptionAsAlt(.none)
        let cell = (width: metrics.width, height: metrics.height)
        input.async { try? link.setCellPixels(width: cell.width, height: cell.height) }
        grid = nil
        layoutGrid()
        let changes = link.screenChanges
        let marks = marks
        tasks.append(Task.detached { [weak self] in
            for await updated in changes {
                marks.output(at: CACurrentMediaTime() - (ContinuousClock.now - updated) / .seconds(1))
                await self?.drawNowIfIdle()
            }
        })
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

    /// Whether `link` may be attached: no view has drawn it before.
    public static func canAttach(_ link: any TerminalLink) -> Bool {
        !attached.contains(link)
    }

    /// Every link a view has attached, held weakly: its streams are spent.
    private static let attached = NSHashTable<AnyObject>.weakObjects()

    /// Stops drawing the session and typing into it, for good: the session itself is the caller's,
    /// and no view can attach it again.
    public func detach() {
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

    /// A hidden view draws nothing (its display link calls nothing, and output does not draw it);
    /// shown again, it draws its screen as it is now at the next refresh.
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

    private func configureLayer(scale: Double) {
        guard let metalLayer else { return }
        metalLayer.device = renderer.device
        metalLayer.pixelFormat = .bgra8Unorm
        metalLayer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
        metalLayer.framebufferOnly = true
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = CGSize(width: pixelSize.width, height: pixelSize.height)
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
        Overlay(focused: focused, marked: composition.marked)
    }

    /// Something the view shows changed: draw at the next refresh.
    func needFrame() {
        needsDraw = true
        pacer.resume()
    }

    /// Output reached the screen, or a key was written (which returns a scrolled-back view to the
    /// bottom). A paused link means the view is idle, the typing-echo case: the screen is read now
    /// and drawn if it changed, and only a frame drawn resumes the link, so a key that changed
    /// nothing leaves the view idle and its echo is drawn the moment it arrives, meeting the next
    /// display refresh rather than the one after. A view paused for want of a drawable is not
    /// idle: output does not ask for one again (an occluded window's ask can block for a second).
    func drawNowIfIdle() {
        guard pacer.isIdle, !awaitingDrawable, !isHiddenOrHasHiddenAncestor, refresh(target: CACurrentMediaTime() + refreshInterval) else { return }
        pacer.resume()
    }

    /// No drawable came: the link pauses, and nothing on the main thread asks for one again (an
    /// ask can wait a second) until the window is seen again. While the window is on screen it is
    /// asked for off the main thread, ``drawableRetry`` after each ask that brought none.
    private func drawableUnavailable() {
        awaitingDrawable = true
        pacer.pause()
        askForDrawable()
    }

    private func askForDrawable() {
        guard awaitingDrawable, !askingForDrawable, let metalLayer else { return }
        wasOnScreen = window.map(isOnScreen) ?? false
        guard wasOnScreen else { return }
        askingForDrawable = true
        let ask = DrawableAsk(view: self, layer: metalLayer, next: nextDrawable)
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + Self.drawableRetry) {
            ask.drawable = ask.next(ask.layer)
            DispatchQueue.main.async {
                MainActor.assumeIsolated { ask.view?.drawableAsked(ask.drawable) }
            }
        }
    }

    private func drawableAsked(_ drawable: (any CAMetalDrawable)?) {
        askingForDrawable = false
        guard awaitingDrawable else { return }
        guard let drawable else {
            askForDrawable()
            return
        }
        broughtDrawable = drawable
        awaitingDrawable = false
        needFrame()
    }

    nonisolated static let drawableRetry = 0.25

    private var refreshInterval: Double {
        1 / Double(max(1, window?.screen?.maximumFramesPerSecond ?? 60))
    }

    /// The window was seen again, having been off screen: a view that paused for want of a
    /// drawable asks for one again. A notification that changes nothing the view saw does nothing.
    @objc private func visibilityChanged() {
        let onScreen = window.map(isOnScreen) ?? false
        defer { wasOnScreen = onScreen }
        guard awaitingDrawable, onScreen, !wasOnScreen else { return }
        awaitingDrawable = false
        needFrame()
    }

    /// One display refresh, for the frame meant to reach the display at `target`: read the screen;
    /// draw when it or the view changed, else pause until something does. True when the link is
    /// wanted for the next refresh: a frame was drawn, or every buffer was still in flight.
    @discardableResult
    func refresh(target: Double) -> Bool {
        guard let link else {
            pacer.pause()
            return false
        }
        let read = CACurrentMediaTime()
        defer { marks.refreshed(since: read) }
        guard let frame = try? link.screen.frame() else {
            pacer.pause()
            return false
        }
        let changed = needsDraw || frame.changed != .nothing || frame.cursor != lastFrame?.cursor || frame.colors != lastFrame?.colors
            || frame.size != lastFrame?.size
        guard changed, let metalLayer else {
            marks.unchanged(read: read)
            pacer.pause()
            return false
        }
        guard !awaitingDrawable else {
            needsDraw = true
            pacer.pause()
            return false
        }
        switch renderer.draw(frame, overlay: overlay, from: { takeDrawable(metalLayer) }, presented: { [marks] in marks.presented(frame: $0, at: $1) }) {
        case let .drawn(number):
            committedFrames += 1
            awaitingDrawable = false
            marks.committed(frame: number, read: read, target: target)
            lastFrame = frame
            needsDraw = renderer.lastFrameWantsAnother
            return true
        case .busy:
            marks.skipped(read: read)
            renderer.invalidate()
            needsDraw = true
            return true
        case .unavailable:
            marks.skipped(read: read)
            renderer.invalidate()
            needsDraw = true
            drawableUnavailable()
            return false
        }
    }

    private func takeDrawable(_ layer: CAMetalLayer) -> (any CAMetalDrawable)? {
        guard let brought = broughtDrawable else { return nextDrawable(layer) }
        broughtDrawable = nil
        return brought
    }

    private func handle(_ event: SessionEvent) {
        if case .screen(.bell) = event, !NSApp.isActive {
            NSApp.requestUserAttention(.informationalRequest)
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
        let marks = marks
        let interval = marks.keyBegan()
        input.async { [weak self] in
            link.screen.scrollToBottom()
            link.send(key)
            marks.keyWritten(event: event, interval: interval)
            Task { @MainActor in self?.drawNowIfIdle() }
        }
    }

    isolated deinit {
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

/// One ask for a drawable, carried to a background queue and its answer back to the main thread.
/// Unchecked: Core Animation's `nextDrawable` may be called from any thread (render threads call
/// it), and the ask is touched by one thread at a time, the main thread, then the queue, then the
/// main thread again.
private final class DrawableAsk: @unchecked Sendable {
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
