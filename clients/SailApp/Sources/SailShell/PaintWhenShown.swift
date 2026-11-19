/// A pane's paint for a change of look, put off while the pane is hidden: no one sees it, and
/// painting it says its lines again and lays it out, at the switch, for nothing. A shown pane is
/// painted at once; a hidden one once, when it is next shown.
@MainActor
final class PaintWhenShown {
    private var owed = false
    private let paint: @MainActor () -> Void

    init(_ paint: @escaping @MainActor () -> Void) {
        self.paint = paint
    }

    /// The look changed, the pane `hidden` or not.
    func lookChanged(hidden: Bool) {
        if hidden {
            owed = true
        } else {
            done()
        }
    }

    /// The pane is shown again: what was put off is painted, once.
    func shown() {
        guard owed else { return }
        owed = false
        done()
    }

    private func done() {
        paint()
    }
}
