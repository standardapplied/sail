import AppKit
import SailLook

/// A bar standing in for text not yet listed: the hover fill in 3 pt corners, shown only after
/// 150 ms so a fast answer never flashes one, then pulsing every 1.2 s, held still while motion is
/// reduced.
@MainActor
public final class Skeleton: NSView {
    public var look: Look {
        didSet { needsDisplay = true }
    }

    private let size: NSSize

    public init(width: Double, height: Double = 10, look: Look) {
        size = NSSize(width: width, height: height)
        self.look = look
        super.init(frame: NSRect(origin: .zero, size: size))
        wantsLayer = true
        alphaValue = 0
        setAccessibilityElement(false)
        NotificationCenter.default.addObserver(self, selector: #selector(motionChanged), name: Motion.changed, object: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public var intrinsicContentSize: NSSize {
        size
    }

    override public func draw(_: NSRect) {
        NSColor(look.skeleton).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: 3, yRadius: 3).fill()
    }

    override public func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard window != nil else { return Motion.pulse(layer, false) }
        DispatchQueue.main.asyncAfter(deadline: .now() + Look.Timing.skeletonDelay) { [weak self] in
            MainActor.assumeIsolated { self?.show() }
        }
    }

    private func show() {
        guard window != nil else { return }
        alphaValue = 1
        Motion.pulse(layer, true, low: 0.45)
    }

    @objc private func motionChanged() {
        Motion.pulse(layer, window != nil && alphaValue == 1, low: 0.45)
    }
}
