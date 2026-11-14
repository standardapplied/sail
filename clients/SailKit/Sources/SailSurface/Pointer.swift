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

    private var anchor: CellPoint?
    private var reporting = false

    public init() {}

    public mutating func down(at cell: CellPoint, clicks: Int, shift: Bool, programTracksMouse: Bool) -> Action {
        if programTracksMouse, !shift {
            reporting = true
            return .report
        }
        reporting = false
        switch clicks {
        case 2:
            anchor = nil
            return .select(.word(at: cell))
        case 3...:
            anchor = nil
            return .select(.line(at: cell))
        default:
            if shift, let anchor {
                return .select(.range(from: anchor, to: cell))
            }
            anchor = cell
            return .clearSelection
        }
    }

    public mutating func dragged(to cell: CellPoint) -> Action {
        if reporting { return .report }
        guard let anchor else { return .nothing }
        return .select(.range(from: anchor, to: cell))
    }

    public mutating func up() -> Action {
        defer { reporting = false }
        return reporting ? .report : .nothing
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
