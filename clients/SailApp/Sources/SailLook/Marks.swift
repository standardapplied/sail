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
    /// A neutral fill and its area in points: grey on paper, which paper and ink draws nowhere at rest.
    case neutralFill(Double)
}

/// A view that draws marks of its own.
@MainActor package protocol Marked {
    var drawnMarks: [Mark] { get }
}

package extension Look {
    /// What a fill of `area` points draws: the accent, the error, or a neutral grey (ink at any
    /// strength short of turned over, or an opaque tone that is neither a ground, a banner's tone nor
    /// a colour).
    func marks(fill: Tint?, area: Double) -> [Mark] {
        guard let fill, fill.alpha > 0 else { return [] }
        if fill.rgb == accent { return [fill.alpha >= 1 ? .accentFill : .accentTint] }
        if fill.rgb == error, fill.alpha >= 1 { return [.errorFill] }
        guard area > 0 else { return [] }
        if fill.rgb == error { return [.neutralFill(area)] }
        if fill.rgb == ink || fill.rgb == text.rgb { return fill.alpha < 0.95 ? [.neutralFill(area)] : [] }
        let grounds = [canvas, surface, surfacePop, chrome, chromeWash, palette.background] + BannerTone.allCases.map { BannerPaint($0, look: self).fill }
        let colours = [accentHover, accentPressed, errorHover, errorPressed, warning, success, info, onAccent, onError, onInk]
        return grounds.contains(fill.rgb) || colours.contains(fill.rgb) ? [] : [.neutralFill(area)]
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
