import AppKit
import QuartzCore

/// What lies over a terminal in its window, and the rule that keeps its frames cheap: a layer over
/// a terminal is composited with every frame the terminal presents, and one that paints is painted
/// again at every change of look. Over a terminal, a view may cover more than a sliver (1 pt in one
/// dimension) or a corner (8 × 8 pt) only when it is known, the pane's own dimming scrim or a
/// divider between panes, and nothing covering more paints a bitmap or a shape.
@MainActor package enum OverTerminals {
    /// A view shown over a terminal, and the part of the terminal it covers, in window points.
    package struct Cover {
        package let view: NSView
        package let covers: NSRect

        @MainActor package var said: String {
            "\(type(of: view)) \(Int(covers.width))x\(Int(covers.height))\(view.layer == nil ? "" : ", a layer")"
        }
    }

    /// Every view shown under `root`, other than `terminal`'s own, that lies over part of it later in
    /// the window's order.
    package static func covers(of terminal: NSView, in root: NSView) -> [Cover] {
        let place = path(of: terminal), rect = terminal.convert(terminal.bounds, to: nil)
        return shown(under: root).compactMap { view in
            guard view !== terminal, !view.isDescendant(of: terminal), !terminal.isDescendant(of: view), later(path(of: view), than: place) else { return nil }
            let covers = view.convert(view.bounds, to: nil).intersection(rect)
            return covers.isEmpty ? nil : Cover(view: view, covers: covers)
        }
    }

    /// What breaks the rule over `pane`'s `terminal`, in words, each cover named with what it covers.
    package static func misses(over terminal: NSView, of pane: NSView, in root: NSView) -> [String] {
        covers(of: terminal, in: root).filter { !small($0.covers) }.compactMap { cover in
            if !known(cover.view, over: pane) { return "\(cover.said) covers more than 1 pt or 8 × 8 pt of it" }
            return paints(cover.view) ? "\(cover.said) paints a bitmap or a shape" : nil
        }
    }

    /// The layers of the views over `pane`'s `terminal` that cover more than a sliver or a corner,
    /// each with its sublayers: those whose bitmap a change of look must not paint again.
    package static func largeLayers(over terminal: NSView, of pane: NSView, in root: NSView) -> [CALayer] {
        covers(of: terminal, in: root).filter { !small($0.covers) }.compactMap { $0.view.layer }.flatMap(tree)
    }

    /// Whether `rect` is no more than a sliver (1 pt in one dimension) or a corner (8 × 8 pt).
    package static func small(_ rect: NSRect) -> Bool {
        min(rect.width, rect.height) <= 1 || (rect.width <= 8 && rect.height <= 8)
    }

    /// Whether `view` is known over `pane`: the pane's own dimming scrim, or a divider between panes.
    static func known(_ view: NSView, over pane: NSView) -> Bool {
        (view is Scrim && view.isDescendant(of: pane)) || view is DividerView
    }

    /// Whether `view` paints: it draws itself, or a layer of it holds a bitmap or is a shape.
    static func paints(_ view: NSView) -> Bool {
        let draw = #selector(NSView.draw(_:))
        let drawsItself = class_getMethodImplementation(type(of: view), draw) != class_getMethodImplementation(NSView.self, draw)
        return drawsItself || (view.layer.map(tree) ?? []).contains { $0.contents != nil || $0 is CAShapeLayer }
    }

    private static func tree(_ layer: CALayer) -> [CALayer] {
        [layer] + (layer.sublayers ?? []).flatMap(tree)
    }

    private static func shown(under root: NSView) -> [NSView] {
        root.subviews.filter { !$0.isHidden && $0.alphaValue > 0 }.flatMap { [$0] + shown(under: $0) }
    }

    private static func path(of view: NSView) -> [Int] {
        var indices: [Int] = [], view = view
        while let parent = view.superview {
            indices.insert(parent.subviews.firstIndex(of: view) ?? 0, at: 0)
            view = parent
        }
        return indices
    }

    private static func later(_ one: [Int], than other: [Int]) -> Bool {
        for (a, b) in zip(one, other) where a != b {
            return a > b
        }
        return false
    }
}
