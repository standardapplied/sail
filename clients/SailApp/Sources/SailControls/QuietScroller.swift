import AppKit
import SailLook

/// The design system's scroller (`ScrollerStyle`), after Mast's terminal scrollbar: a 6 pt thumb in
/// 3 pt corners, 3 pt from the edge, in the look's text at 35 %, 55 % hovered or dragged, and no
/// track or arrows. As an overlay scroller AppKit fades it once scrolling settles and brings it back
/// over its track; with Always show scroll bars set, it stays.
@MainActor
public final class QuietScroller: NSScroller {
    public var look = Look.light {
        didSet { needsDisplay = true }
    }

    package var hovered = false {
        didSet { if hovered != oldValue { needsDisplay = true } }
    }

    package var dragging = false {
        didSet { if dragging != oldValue { needsDisplay = true } }
    }

    private var hoverArea: NSTrackingArea?

    override public class var isCompatibleWithOverlayScrollers: Bool {
        true
    }

    override public class func scrollerWidth(for _: NSControl.ControlSize, scrollerStyle _: NSScroller.Style) -> CGFloat {
        ScrollerStyle.track
    }

    /// The thumb as drawn: its 6 pt at the track's far edge, along the knob AppKit places.
    package var thumb: NSRect {
        let knob = rect(for: .knob)
        return NSRect(x: Double(bounds.maxX) - ScrollerStyle.edge - ScrollerStyle.thumb, y: Double(knob.minY), width: ScrollerStyle.thumb, height: Double(knob.height))
    }

    override public func draw(_: NSRect) {
        drawKnob()
    }

    override public func drawKnobSlot(in _: NSRect, highlight _: Bool) {}

    override public func drawKnob() {
        let thumb = self.thumb
        guard thumb.height > 0 else { return }
        NSColor(ScrollerStyle.ink(look, active: hovered || dragging)).setFill()
        NSBezierPath(roundedRect: thumb, xRadius: ScrollerStyle.radius, yRadius: ScrollerStyle.radius).fill()
    }

    /// Drags begun on the thumb, for the tests: AppKit's overlay scrollers track a drag in
    /// `mouseDown`, not always through `trackKnob`.
    package private(set) var drags = 0

    override public func mouseDown(with event: NSEvent) {
        guard thumb.insetBy(dx: -(ScrollerStyle.track - ScrollerStyle.thumb) / 2, dy: 0).contains(convert(event.locationInWindow, from: nil)) else {
            return super.mouseDown(with: event)
        }
        drags += 1
        dragging = true
        super.mouseDown(with: event)
        dragging = false
    }

    override public func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let hoverArea { removeTrackingArea(hoverArea) }
        let area = NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self)
        addTrackingArea(area)
        hoverArea = area
    }

    override public func mouseEntered(with event: NSEvent) {
        super.mouseEntered(with: event)
        hovered = true
    }

    override public func mouseExited(with event: NSEvent) {
        super.mouseExited(with: event)
        hovered = false
    }
}

/// A scroll view for the app's lists: vertical only, the quiet scroller, no background of its own,
/// the scroller's track inset 4 pt top and bottom. Always the overlay style, whatever "Show scroll
/// bars" says (AppKit sets `scrollerStyle` again from the system's preference, and this refuses
/// any other): its rows never give the track 12 pt, so bands and fills run under it and nothing
/// shifts when a list becomes scrollable. A short list does not bounce.
@MainActor
public final class QuietScrollView: NSScrollView {
    public let scroller = QuietScroller()

    public var look: Look {
        get { scroller.look }
        set { scroller.look = newValue }
    }

    public init(look: Look) {
        super.init(frame: .zero)
        scroller.look = look
        verticalScroller = scroller
        hasVerticalScroller = true
        hasHorizontalScroller = false
        autohidesScrollers = true
        drawsBackground = false
        horizontalScrollElasticity = .none
        scrollerInsets = NSEdgeInsets(top: ScrollerStyle.inset, left: 0, bottom: ScrollerStyle.inset, right: 0)
        scrollerStyle = .overlay
    }

    override public var scrollerStyle: NSScroller.Style {
        get { super.scrollerStyle }
        set { super.scrollerStyle = .overlay }
    }

    /// Bounces only while its rows run past it: a list that fits never shows bare surface.
    override public func tile() {
        super.tile()
        let scrollable = Double(documentView?.frame.height ?? 0) > Double(contentSize.height) + 0.5
        verticalScrollElasticity = scrollable ? .automatic : .none
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    /// Its rows shown from their top.
    public func scrollToTop() {
        contentView.scroll(to: .zero)
        reflectScrolledClipView(contentView)
    }

    /// Redraws what it scrolls (a change of look, new rows of the same list) where the person left
    /// it: the scroll before `redraw`, restored after, as far as the new rows still reach.
    public func keepingScroll(_ redraw: () -> Void) {
        let kept = contentView.bounds.origin.y
        redraw()
        superview?.layoutSubtreeIfNeeded()
        let room = max(Double(documentView?.frame.height ?? 0) - Double(contentSize.height), 0)
        contentView.scroll(to: NSPoint(x: 0, y: min(Double(kept), room)))
        reflectScrolledClipView(contentView)
    }
}
