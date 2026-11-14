import AppKit
import QuartzCore

/// A clock that calls the view once per display refresh while not paused, with the time that
/// refresh's frame is meant to reach the display.
@MainActor
public protocol FrameClock: AnyObject {
    var isPaused: Bool { get set }
    func invalidate()
}

/// The view's display link (`NSView.displayLink(target:selector:)`), which follows the display the
/// view is on and calls nothing while the view is hidden. It is made, paused, resumed and ended
/// on the main thread only: Ghostty's app froze when a display link was changed from another
/// thread during display sleep and hot-plug.
@MainActor
final class DisplayLinkClock: NSObject, FrameClock {
    private var link: CADisplayLink?
    private let tick: @MainActor (Double) -> Void

    init(view: NSView, tick: @escaping @MainActor (Double) -> Void) {
        self.tick = tick
        super.init()
        let link = view.displayLink(target: self, selector: #selector(fire(_:)))
        link.isPaused = true
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func fire(_ link: CADisplayLink) {
        tick(link.targetTimestamp)
    }

    var isPaused: Bool {
        get { link?.isPaused ?? true }
        set { link?.isPaused = newValue }
    }

    func invalidate() {
        link?.invalidate()
        link = nil
    }
}

/// Starts, pauses, resumes and stops the view's clock: paused when a refresh finds nothing to
/// draw, resumed by output, input or any change of the view. Each change is recorded with whether
/// it ran on the main thread, so a test proves that every one did (V3).
@MainActor
final class FramePacer {
    enum Change: Equatable, Sendable {
        case start, pause, resume, stop
    }

    struct Record: Equatable, Sendable {
        let change: Change
        let onMainThread: Bool
    }

    private(set) var records: [Record] = []
    private var clock: (any FrameClock)?

    /// The view has a clock and it is paused: a refresh found nothing to draw, or no drawable.
    var isIdle: Bool {
        clock?.isPaused == true
    }

    func start(_ clock: any FrameClock) {
        stop()
        record(.start)
        self.clock = clock
        clock.isPaused = false
    }

    func resume() {
        guard let clock, clock.isPaused else { return }
        record(.resume)
        clock.isPaused = false
    }

    func pause() {
        guard let clock, !clock.isPaused else { return }
        record(.pause)
        clock.isPaused = true
    }

    func stop() {
        guard let clock else { return }
        record(.stop)
        clock.invalidate()
        self.clock = nil
    }

    private func record(_ change: Change) {
        records.append(Record(change: change, onMainThread: Thread.isMainThread))
        if records.count > 1024 { records.removeFirst(records.count - 1024) }
    }
}
