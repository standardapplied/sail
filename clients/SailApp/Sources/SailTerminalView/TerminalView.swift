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
    var makeClock: @MainActor (TerminalView) -> any FrameClock = { view in DisplayLinkClock(view: view) { [weak view] in view?.refresh() } }
    var composition = Composition()
    var committedDuringKey: [String]?
    var pointer = Pointer()
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

    /// Starts the latency figures from nothing.
    public func resetLatency() {
        marks.reset()
    }

    public var metrics: CellMetrics {
        renderer.metrics
    }

    /// Draws `link`'s screen and types into it, in place of any link before. Programs may not write
    /// the clipboard (it changes only on the person's action) and Option types characters.
    public func attach(_ link: any TerminalLink) {
        detach()
        self.link = link
        link.screen.setClipboardWrites(.deny)
        link.screen.setOptionAsAlt(.none)
        _ = try? link.screen.setCellPixels(width: metrics.width, height: metrics.height)
        grid = nil
        layoutGrid()
        let changes = link.screenChanges
        let marks = marks
        tasks.append(Task.detached { [weak self] in
            for await _ in changes {
                marks.output()
                await self?.outputArrived()
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

    /// Stops drawing the session and typing into it; the session itself is the caller's.
    public func detach() {
        tasks.forEach { $0.cancel() }
        tasks = []
        link = nil
        composition.unmark()
        lastFrame = nil
    }

    /// The last frame drawn, offscreen at the view's pixel size: what the person saw.
    public func snapshot() throws(RenderError) -> RenderedImage? {
        guard let lastFrame else { return nil }
        return try renderer.image(of: lastFrame, overlay: overlay, size: pixelSize)
    }

    /// The visible text of the last frame drawn.
    public var visibleText: String {
        lastFrame.map { Screen.text(of: $0.rows) } ?? ""
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

    override public func setFrameSize(_ size: NSSize) {
        super.setFrameSize(size)
        configureLayer(scale: renderer.faces.scale)
        layoutGrid()
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
            _ = try? link.screen.setCellPixels(width: metrics.width, height: metrics.height)
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
    /// reflows now, and the session tells the host once the size has held.
    func layoutGrid() {
        guard let link else { return }
        let fitted = metrics.grid(fitting: pixelSize)
        let size = GridSize(
            columns: min(fitted.columns, TerminalSession.largestGrid.columns), rows: min(fitted.rows, TerminalSession.largestGrid.rows)
        )
        guard size != grid else { return }
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

    private func outputArrived() {
        pacer.resume()
    }

    /// One display refresh: read the screen; draw when it or the view changed, else pause until
    /// something does.
    func refresh() {
        guard let link else {
            pacer.pause()
            return
        }
        let read = CACurrentMediaTime()
        guard let frame = try? link.screen.frame() else {
            pacer.pause()
            return
        }
        let changed = needsDraw || frame.changed != .nothing || frame.cursor != lastFrame?.cursor || frame.colors != lastFrame?.colors
            || frame.size != lastFrame?.size
        guard changed, let metalLayer else {
            marks.unchanged(read: read)
            pacer.pause()
            return
        }
        let marks = marks
        switch renderer.draw(frame, overlay: overlay, in: metalLayer, presented: { marks.presented(frame: $0, at: $1) }) {
        case let .drawn(number):
            marks.committed(frame: number, read: read)
            lastFrame = frame
            needsDraw = false
        case .busy, .unavailable:
            marks.skipped(read: read)
            renderer.invalidate()
            needsDraw = true
        }
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
        updateFocus(responder: true)
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

    /// Sends what `key` encodes to, on the input queue; a key typed while scrolled back returns the
    /// view to the bottom first. `event` is the key's time on the host clock.
    func send(_ key: KeyInput, at event: Double) {
        guard let link else { return }
        link.screen.scrollToBottom()
        let marks = marks
        let measured = key.action != .release
        let interval = marks.keyBegan()
        input.async {
            link.send(key)
            if measured { marks.keyWritten(event: event, interval: interval) }
        }
        needFrame()
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
