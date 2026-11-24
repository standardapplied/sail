import AppKit
import SailSurface
import SailVT

/// A link in the terminal: a program's own (OSC 8), a web address in its text (``TerminalURL``),
/// each opened in the browser (``TerminalView/openURL``), or one the app finds
/// (``TerminalView/linkIn``, opened by ``TerminalView/onLink``). Underlined, with the pointing hand,
/// while ⌘ is held over it, across every row its line is soft-wrapped over, and opened whole by a
/// ⌘-click. A ⌘-click on a link goes to the link alone: neither the selection nor a program that
/// tracks the mouse gets it. Every other click, ⌘ held or not, is as before.
extension TerminalView {
    /// The text of the line holding row `y`, the rows it is soft-wrapped across joined into one,
    /// the cell each character starts at with its width, and whether the screen's edge cuts it:
    /// its start above the screen, its end below.
    func lineText(_ y: Int) -> (text: String, starts: [(cell: CellPoint, width: Int)], cut: (start: Bool, end: Bool))? {
        guard let rows = lastFrame?.rows, rows.indices.contains(y) else { return nil }
        var first = y, last = y
        while first > 0, rows[first - 1].wrapped {
            first -= 1
        }
        while last < rows.count - 1, rows[last].wrapped {
            last += 1
        }
        var text = "", starts: [(cell: CellPoint, width: Int)] = []
        for row in first ... last {
            for (x, cell) in rows[row].cells.enumerated() {
                switch cell.width {
                case .spacerTail, .spacerHead: continue
                case .narrow, .wide:
                    let grapheme = cell.grapheme.isEmpty ? " " : cell.grapheme
                    for character in grapheme {
                        text.append(character)
                        starts.append((CellPoint(x: x, y: row), cell.width == .wide ? 2 : 1))
                    }
                }
            }
        }
        return (text, starts, (first == 0 && rows[first].continues, last == rows.count - 1 && rows[last].wrapped))
    }

    /// The link under `cell`: its line's text, the 1-based character there, the cells it takes, a
    /// run in each row its line is soft-wrapped across, and the web address it opens, none for one
    /// of the app's own; none in a word the screen's edge cuts, as what is left of it is not it.
    func linkUnder(_ cell: CellPoint) -> (text: String, column: Int, link: OverlayLink, url: URL?)? {
        guard let (text, starts, cut) = lineText(cell.y),
              let index = starts.lastIndex(where: { $0.cell.y < cell.y || $0.cell.y == cell.y && $0.cell.x <= cell.x }),
              let (range, url) = target(in: text, at: index, of: starts.map(\.cell)),
              !range.isEmpty, range.lowerBound >= 0, range.upperBound <= starts.count else { return nil }
        let characters = Array(text)
        if cut.start, !characters[..<range.lowerBound].contains(where: \.isWhitespace) { return nil }
        if cut.end, !characters[range.upperBound...].contains(where: \.isWhitespace) { return nil }
        var spans: [OverlayLink.Span] = []
        for row in Set(starts[range].map(\.cell.y)).sorted() {
            let cells = starts[range].filter { $0.cell.y == row }
            guard let first = cells.map(\.cell.x).min(), let end = cells.map({ $0.cell.x + $0.width }).max() else { continue }
            spans.append(OverlayLink.Span(row: row, columns: first ..< end))
        }
        return (text, index + 1, OverlayLink(spans: spans), url)
    }

    /// What character `index` of a line's `text`, drawn in `cells`, is part of: a program's own
    /// link, the run of cells around it naming one address; a web address in the text; or a link
    /// of the app's own.
    private func target(in text: String, at index: Int, of cells: [CellPoint]) -> (Range<Int>, URL?)? {
        if let screen = link?.screen, case let address?? = screen.hyperlinks(at: [cells[index]]).first, let url = URL(string: address) {
            let addresses = screen.hyperlinks(at: cells)
            let start = addresses[..<index].lastIndex { $0 != address }.map { $0 + 1 } ?? 0
            let end = addresses[index...].firstIndex { $0 != address } ?? addresses.count
            return (start ..< end, url)
        }
        if let found = TerminalURL.find(in: text, at: index + 1) { return (found.range, found.url) }
        return linkIn(text, index + 1).map { ($0, nil) }
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
        guard let rows = lastFrame?.rows else { return nil }
        for y in rows.indices where y == 0 || !rows[y - 1].wrapped {
            guard let (line, starts, _) = lineText(y), let range = line.range(of: text) else { continue }
            return starts[line.distance(from: line.startIndex, to: range.lowerBound)].cell
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

    /// Where `span` of a link lies, in the view's points.
    func rect(of span: OverlayLink.Span) -> NSRect {
        let metrics = metrics, origin = metrics.origin(of: CellPoint(x: span.columns.lowerBound, y: span.row))
        return NSRect(
            x: Double(origin.x) / metrics.scale, y: Double(origin.y) / metrics.scale,
            width: Double(span.columns.count * metrics.width) / metrics.scale, height: Double(metrics.height) / metrics.scale
        )
    }

    override public func resetCursorRects() {
        shownLink?.spans.forEach { addCursorRect(rect(of: $0), cursor: .pointingHand) }
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
