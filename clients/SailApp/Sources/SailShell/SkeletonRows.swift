import AppKit
import SailAPI
import SailControls
import SailLook

/// The list before it is listed, as the design's loading frame draws it: the head and three rows,
/// with skeleton bars where a row's name, instance, pill and owner will be, in the wide row's columns.
@MainActor
final class SkeletonRows: NSView {
    static let words: [(name: Double, place: Double)] = [(64, 104), (48, 88), (80, 120)]
    static let height = RowStyle.list.head + Double(words.count) * RowStyle.list.height

    var look: Look {
        didSet {
            for bar in bars.joined() {
                bar.look = look
            }
            needsDisplay = true
        }
    }

    private let bars: [[Skeleton]]

    init(look: Look) {
        self.look = look
        bars = [[Skeleton(width: 46, height: 8, look: look)]] + Self.words.map { words in
            [Skeleton(width: words.name, look: look), Skeleton(width: words.place, height: 8, look: look), Skeleton(width: 74, height: 18, look: look), Skeleton(width: 60, look: look)]
        }
        super.init(frame: .zero)
        for bar in bars.joined() {
            addSubview(bar)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    /// Each row's pill where the wide row's pill column starts, its owner after it, and the name's
    /// two lines at the row's leading inset.
    override func layout() {
        super.layout()
        let leading = RowStyle.list.leading, pill = max(leading + 130, Double(bounds.width) - RowStyle.list.trailing - ProjectView.actionsColumn - WorkspaceLine.ownerColumn - WorkspaceLine.pillColumn)
        bars[0][0].frame = NSRect(x: leading, y: (RowStyle.list.head - 8) / 2, width: 46, height: 8)
        for (index, row) in bars.dropFirst().enumerated() {
            let top = RowStyle.list.head + Double(index) * RowStyle.list.height
            let at = [(leading, top + 14), (leading, top + 30), (pill, top + 17), (pill + WorkspaceLine.pillColumn, top + 21)]
            for (bar, (x, y)) in zip(row, at) {
                bar.frame = NSRect(origin: NSPoint(x: x, y: y), size: bar.intrinsicContentSize)
            }
        }
    }

    override func draw(_: NSRect) {
        NSColor(look.gridLine).setFill()
        for index in Self.words.indices {
            NSRect(x: 0, y: RowStyle.list.head + Double(index) * RowStyle.list.height, width: Double(bounds.width), height: 1).fill()
        }
    }
}
