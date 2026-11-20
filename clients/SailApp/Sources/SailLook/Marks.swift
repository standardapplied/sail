import AppKit
import SailVT

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
    /// A small mark turned over to ink (the user menu's highlight, its avatar, the chosen
    /// Appearance): not grey, and at most one in any group.
    case inverseFill
    /// A neutral fill and its area in points: grey on paper, which paper and ink draws nowhere at rest.
    case neutralFill(Double)
}

/// A view that draws marks of its own.
@MainActor package protocol Marked {
    var drawnMarks: [Mark] { get }
}

package extension Look {
    /// The accent's tokens: whichever a control draws in, it is the accent.
    var accents: [RGB] {
        [accent, accentHover, accentPressed]
    }

    /// The error's tokens.
    var errors: [RGB] {
        [error, errorHover, errorPressed]
    }

    /// What a fill of `area` points inside the card draws: the accent, the error, or a neutral grey
    /// (ink at any strength, or an opaque tone that is neither the card's ground, a banner's tone nor
    /// a colour). The chrome's own tones are grey here: they belong to the chrome alone.
    func marks(fill: Tint?, area: Double) -> [Mark] {
        guard let fill, fill.alpha > 0 else { return [] }
        if accents.contains(fill.rgb) { return [fill.alpha >= 1 ? .accentFill : .accentTint] }
        if errors.contains(fill.rgb), fill.alpha >= 1 { return [.errorFill] }
        if fill.rgb == inverse, fill.alpha >= 1 { return [.inverseFill] }
        guard area > 0 else { return [] }
        let grounds = [canvas, surface, surfacePop, palette.background] + BannerTone.allCases.map { BannerPaint($0, look: self).fill }
        let colours = [warning, success, info, onAccent, onError, onInk]
        return grounds.contains(fill.rgb) || colours.contains(fill.rgb) ? [] : [.neutralFill(area)]
    }

    func marks(line: Tint?) -> [Mark] {
        guard let line, line.alpha > 0 else { return [] }
        return accents.contains(line.rgb) ? [.accentLine] : errors.contains(line.rgb) ? [.errorInk] : []
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
