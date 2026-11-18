import AppKit
import SailLook

/// Whether the person asked for less motion (Accessibility ▸ Display ▸ Reduce motion), followed
/// live: spinners and pulses hold still and show their end state while it is on.
@MainActor
public final class Motion {
    public static let shared = Motion()
    /// Posted when `reduced` changes; an animated control starts or stops on it.
    public static let changed = Notification.Name("SailControls.Motion.changed")

    public var reduced: Bool {
        didSet {
            if reduced != oldValue { NotificationCenter.default.post(name: Self.changed, object: self) }
        }
    }

    private init() {
        reduced = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        _ = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification, object: nil, queue: .main
        ) { _ in
            MainActor.assumeIsolated { Motion.shared.reduced = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }
        }
    }

    /// `layer` pulsing between full and a quarter opacity every 1.2 s, or still at full while motion
    /// is reduced or `on` is false.
    static func pulse(_ layer: CALayer?, _ on: Bool, low: Double = 0.25) {
        guard on, !shared.reduced else {
            layer?.removeAnimation(forKey: pulseKey)
            return
        }
        guard layer?.animation(forKey: pulseKey) == nil else { return }
        let pulse = CABasicAnimation(keyPath: "opacity")
        pulse.fromValue = 1
        pulse.toValue = low
        pulse.duration = Look.Timing.pulse / 2
        pulse.autoreverses = true
        pulse.repeatCount = .infinity
        pulse.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        layer?.add(pulse, forKey: pulseKey)
    }

    static let pulseKey = "pulse"
}
