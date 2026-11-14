import SailVT

/// The person's pointer over the terminal, as Ghostty behaves: a drag selects, a double click a
/// word, a triple click a line, Shift extends the selection. A program that tracks the mouse gets
/// the pointer instead, unless Shift is held.
public struct Pointer: Equatable, Sendable {
    /// What a pointer event does.
    public enum Action: Equatable, Sendable {
        /// The program tracks the mouse: report the event to it.
        case report
        case select(SelectionRequest)
        case clearSelection
        case nothing
    }

    /// Where the last selection started, and whether by cells, words or lines: a drag or a
    /// Shift-click extends from it by the same unit.
    private var start: CellPoint?
    private var unit = Unit.cell
    /// A button is down over the text: a drag extends the selection.
    private var selecting = false
    private var reporting = false

    private enum Unit: Equatable, Sendable {
        case cell, word, line
    }

    public init() {}

    public mutating func down(at cell: CellPoint, clicks: Int, shift: Bool, programTracksMouse: Bool) -> Action {
        if programTracksMouse, !shift {
            reporting = true
            return .report
        }
        reporting = false
        selecting = true
        switch clicks {
        case 2:
            start = cell
            unit = .word
            return .select(.word(at: cell))
        case 3...:
            start = cell
            unit = .line
            return .select(.line(at: cell))
        default:
            if shift, let start {
                return .select(extending(from: start, to: cell))
            }
            start = cell
            unit = .cell
            return .clearSelection
        }
    }

    public mutating func dragged(to cell: CellPoint) -> Action {
        if reporting { return .report }
        guard selecting, let start else { return .nothing }
        return .select(extending(from: start, to: cell))
    }

    public mutating func up() -> Action {
        defer {
            reporting = false
            selecting = false
        }
        return reporting ? .report : .nothing
    }

    private func extending(from start: CellPoint, to cell: CellPoint) -> SelectionRequest {
        switch unit {
        case .cell: .range(from: start, to: cell)
        case .word: .words(from: start, to: cell)
        case .line: .lines(from: start, to: cell)
        }
    }
}

/// Wheel and trackpad deltas, gathered into whole lines: a trackpad's precise deltas are points,
/// a cell's height to the line; a wheel's are lines already, often fractions of one.
public struct ScrollGatherer: Equatable, Sendable {
    /// The most lines one event scrolls.
    static let mostLines = 10_000.0
    private var gathered = 0.0

    public init() {}

    /// The whole lines to scroll for a delta, up negative; `deltaY` is AppKit's, positive to see
    /// what is above.
    public mutating func lines(deltaY: Double, precise: Bool, lineHeight: Double) -> Int {
        guard deltaY.isFinite, lineHeight.isFinite, lineHeight > 0 else { return 0 }
        gathered -= precise ? deltaY / lineHeight : deltaY
        let whole = min(Self.mostLines, max(-Self.mostLines, gathered.rounded(.towardZero)))
        gathered -= whole
        gathered = min(1, max(-1, gathered))
        return Int(whole)
    }

    public mutating func reset() {
        gathered = 0
    }
}
