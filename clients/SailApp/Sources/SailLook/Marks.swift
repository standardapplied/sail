import AppKit

/// What a control draws in terracotta or in the error ink, by token: what the calm look counts, so
/// it cannot erode unseen.
package enum Mark: Equatable, Sendable {
    /// A solid accent fill: the one primary action.
    case accentFill
    /// A translucent accent fill.
    case accentTint
    /// A line in the accent: a bar, a ring, an underline, the caret, the cursor with the keys.
    case accentLine
    case errorFill
    case errorInk
}

/// A view that draws marks of its own.
@MainActor package protocol Marked {
    var drawnMarks: [Mark] { get }
}

package extension Look {
    func marks(fill: Tint?) -> [Mark] {
        guard let fill, fill.alpha > 0 else { return [] }
        if fill.rgb == accent { return [fill.alpha >= 1 ? .accentFill : .accentTint] }
        return fill.rgb == error && fill.alpha >= 1 ? [.errorFill] : []
    }

    func marks(line: Tint?) -> [Mark] {
        guard let line, line.alpha > 0 else { return [] }
        return line.rgb == accent ? [.accentLine] : line.rgb == error ? [.errorInk] : []
    }
}

/// Every mark drawn in `view` and under it; a hidden view draws none.
@MainActor package func marks(in view: NSView) -> [Mark] {
    guard !view.isHidden else { return [] }
    return ((view as? Marked)?.drawnMarks ?? []) + view.subviews.flatMap { marks(in: $0) }
}

/// Every mark drawn in `window`: its content, its attached sheet and its child windows.
@MainActor package func marks(in window: NSWindow) -> [Mark] {
    let content = window.contentView.map { marks(in: $0) } ?? []
    let sheet = window.attachedSheet.map { marks(in: $0) } ?? []
    let children = (window.childWindows ?? []).filter(\.isVisible).flatMap { marks(in: $0) }
    return content + sheet + children
}
