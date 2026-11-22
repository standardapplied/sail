import AppKit
import SailSurface
import SailVT

/// A link in the terminal's text, as the app finds one (``TerminalView/linkIn``): underlined, with
/// the pointing hand, while ⌘ is held over it, and opened by a ⌘-click (``TerminalView/onLink``).
/// A ⌘-click on a link goes to the link alone: neither the selection nor a program that tracks the
/// mouse gets it. Every other click, ⌘ held or not, is as before.
extension TerminalView {
    /// The text of row `y` in characters, and the cell each character starts at.
    func rowText(_ y: Int) -> (text: String, starts: [Int])? {
        guard let rows = lastFrame?.rows, rows.indices.contains(y) else { return nil }
        var text = "", starts: [Int] = []
        for (x, cell) in rows[y].cells.enumerated() {
            switch cell.width {
            case .spacerTail, .spacerHead: continue
            case .narrow, .wide:
                let grapheme = cell.grapheme.isEmpty ? " " : cell.grapheme
                for character in grapheme {
                    text.append(character)
                    starts.append(x)
                }
            }
        }
        return (text, starts)
    }

    /// The link under `cell`: its row's text, the 1-based character there, and the cells it takes.
    func linkUnder(_ cell: CellPoint) -> (text: String, column: Int, link: OverlayLink)? {
        guard let (text, starts) = rowText(cell.y), let index = starts.lastIndex(where: { $0 <= cell.x }),
              let range = linkIn(text, index + 1), !range.isEmpty, range.lowerBound >= 0, range.upperBound <= starts.count else { return nil }
        let end = range.upperBound < starts.count ? starts[range.upperBound] : starts[range.upperBound - 1] + 1
        return (text, index + 1, OverlayLink(row: cell.y, columns: starts[range.lowerBound] ..< end))
    }

    /// ⌘ held over `cell`, or not: the link there underlined and the pointing hand over it, or
    /// none.
    func showLink(at cell: CellPoint?, command: Bool) {
        linkPointer = cell
        let found = command ? cell.flatMap { linkUnder($0)?.link } : nil
        guard found != shownLink else { return }
        shownLink = found
        window?.invalidateCursorRects(for: self)
        (found == nil ? NSCursor.arrow : NSCursor.pointingHand).set()
        needFrame()
    }

    /// The text under the link changed: it is looked for again where the pointer last was.
    func lookForLinkAgain() {
        showLink(at: linkPointer, command: NSEvent.modifierFlags.contains(.command))
    }

    /// The cell where `text` first starts in the text drawn, nil when it is not there: where the
    /// drive clicks.
    package func cell(of text: String) -> CellPoint? {
        for y in lastFrame?.rows.indices ?? 0 ..< 0 {
            guard let (row, starts) = rowText(y), let range = row.range(of: text) else { continue }
            return CellPoint(x: starts[row.distance(from: row.startIndex, to: range.lowerBound)], y: y)
        }
        return nil
    }

    /// The middle of `cell`, in the view's points.
    package func point(of cell: CellPoint) -> NSPoint {
        let metrics = metrics, origin = metrics.origin(of: cell)
        return NSPoint(x: (Double(origin.x) + Double(metrics.width) / 2) / metrics.scale, y: (Double(origin.y) + Double(metrics.height) / 2) / metrics.scale)
    }

    /// The cell the pointer is over now, nil when it is outside the view.
    var pointerCell: CellPoint? {
        guard let window, let grid = lastFrame?.size ?? grid else { return nil }
        let point = convert(window.mouseLocationOutsideOfEventStream, from: nil)
        guard bounds.contains(point) else { return nil }
        let scale = renderer.faces.scale
        return metrics.cell(atX: Double(point.x) * scale, y: Double(point.y) * scale, in: grid)
    }

    /// Where `link` lies, in the view's points.
    func rect(of link: OverlayLink) -> NSRect {
        let metrics = metrics, origin = metrics.origin(of: CellPoint(x: link.columns.lowerBound, y: link.row))
        return NSRect(
            x: Double(origin.x) / metrics.scale, y: Double(origin.y) / metrics.scale,
            width: Double(link.columns.count * metrics.width) / metrics.scale, height: Double(metrics.height) / metrics.scale
        )
    }

    override public func resetCursorRects() {
        if let shownLink { addCursorRect(rect(of: shownLink), cursor: .pointingHand) }
    }
}

extension ScreenFrame.Change {
    /// Whether row `y`'s cells changed.
    func touches(_ y: Int) -> Bool {
        switch self {
        case .nothing: false
        case .all: true
        case let .rows(rows): rows.contains(y)
        }
    }
}
