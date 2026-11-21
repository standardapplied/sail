import AppKit
import QuartzCore
import SailControls
import SailWorkbench
import Synchronization

/// What lies over a terminal in its window, and the rule that keeps its frames cheap: a layer over
/// a terminal is composited with every frame the terminal presents, and one that paints is painted
/// again at every change of look.
///
/// The rule: over a terminal, a view or a bare layer may cover more than a sliver or a corner
/// (``small(_:)``) only when it is known, the pane's own dimming scrim or a divider between panes
/// no thicker than its hit area, and nothing known that covers more paints a bitmap or a shape. It
/// holds for panes attached, with no card up and no panel floating over them. Two named exceptions
/// may cover more and hold a bitmap, but must not be given a new one during a change of look
/// (``PaintWatch``): the pane's own card, and the files panel where it floats over the terminals at
/// the least window.
///
/// What the walk sees: the views shown in the terminal's window later in its order, with their
/// layers, and the bare layers (a layer with no view of its own) on those views' layers or on the
/// terminal's ancestors' layers above its own, below the window's frame view. What it does not
/// see: other windows (sheets, the user menu's panel, tooltips, popovers), which the window server
/// composites, not the app's commit; and a layer that comes and goes between two looks.
@MainActor package enum OverTerminals {
    /// A view or a bare layer shown over a terminal, and the part of the terminal it covers, in
    /// window points.
    package struct Cover {
        package let view: NSView?
        package let layer: CALayer?
        package let covers: NSRect

        @MainActor package var said: String {
            let size = "\(Int(covers.width))x\(Int(covers.height))"
            guard let view else { return "a bare \(layer.map { String(describing: type(of: $0)) } ?? "layer") \(size)" }
            return "\(type(of: view)) \(size)\(view.layer == nil ? "" : ", a layer")"
        }
    }

    /// Everything shown under `root` that lies over part of `terminal`, later in the window's order,
    /// other than the terminal's own.
    package static func covers(of terminal: NSView, in root: NSView) -> [Cover] {
        let place = path(of: terminal), rect = terminal.convert(terminal.bounds, to: nil)
        let views = shown(under: root).filter { view in
            view !== terminal && !view.isDescendant(of: terminal) && !terminal.isDescendant(of: view) && later(path(of: view), than: place)
        }
        let viewCovers = views.compactMap { cover(view: $0, layer: nil, at: $0.convert($0.bounds, to: nil), over: rect) }
        let bareCovers = (views.flatMap { host in bare(on: host).map { (host, $0) } } + bareAbove(terminal, in: root)).compactMap { host, layer in
            host.layer.flatMap { cover(view: nil, layer: layer, at: host.convert($0.convert(layer.bounds, from: layer), to: nil), over: rect) }
        }
        return viewCovers + bareCovers
    }

    /// What breaks the rule over `pane`'s `terminal`, in words, each cover named with what it covers.
    package static func misses(over terminal: NSView, of pane: NSView, in root: NSView) -> [String] {
        covers(of: terminal, in: root).filter { !small($0.covers) }.compactMap { cover in
            guard let view = cover.view else { return "\(cover.said) covers more than 1 pt or 8 × 8 pt of it" }
            if excepted(view, over: pane) { return nil }
            if !known(view, over: pane) { return "\(cover.said) covers more than 1 pt or 8 × 8 pt of it" }
            return paints(view) ? "\(cover.said) paints a bitmap or a shape" : nil
        }
    }

    /// What the rule expects to find over `pane`'s `terminal` and did not: its scrim when it is
    /// `dimmed`, a divider, and a part of the card's edge when the look has one (`edged`). A walk
    /// that finds nothing cannot pass for one that found nothing wrong.
    package static func unfound(over terminal: NSView, of pane: NSView, in root: NSView, dimmed: Bool, edged: Bool) -> [String] {
        let found = covers(of: terminal, in: root).compactMap(\.view)
        let rect = terminal.convert(terminal.bounds, to: nil)
        return [
            dimmed && !found.contains { $0 is Scrim && $0.isDescendant(of: pane) && $0.convert($0.bounds, to: nil).contains(rect) } ? "its scrim" : nil,
            found.contains { $0 is DividerView } ? nil : "a divider",
            edged && !found.contains { $0 is CardEdge.Line || $0 is CardEdge.Arc } ? "a part of the card's edge" : nil,
        ].compactMap(\.self)
    }

    /// The layers over `pane`'s `terminal` that cover more than a sliver or a corner, each with its
    /// sublayers: those no change of look may give a new bitmap.
    package static func largeLayers(over terminal: NSView, of pane: NSView, in root: NSView) -> [CALayer] {
        covers(of: terminal, in: root).filter { !small($0.covers) }.compactMap { $0.layer ?? $0.view?.layer }.flatMap(tree)
    }

    /// Whether `rect` is no more than a sliver (1 pt in one dimension) or a corner (8 × 8 pt). What a
    /// layer over a terminal costs each frame is the pixels it covers: a 1 pt line the card's length
    /// covers about a thousand points, the card's own edge; at 2 pt it would cover twice that.
    package static func small(_ rect: NSRect) -> Bool {
        min(rect.width, rect.height) <= 1 || (rect.width <= 8 && rect.height <= 8)
    }

    /// Whether `view` is known over `pane`: the pane's own dimming scrim, or a divider no thicker
    /// than the pointer's hit area across it.
    static func known(_ view: NSView, over pane: NSView) -> Bool {
        (view is Scrim && view.isDescendant(of: pane)) || (view is DividerView && min(view.frame.width, view.frame.height) <= Layout.dividerHitWidth)
    }

    /// Whether `view` is, or is inside, a named exception over `pane`: the pane's own card, or the
    /// files panel.
    static func excepted(_ view: NSView, over pane: NSView) -> Bool {
        var at: NSView? = view
        while let current = at {
            if (current is Card && current.isDescendant(of: pane)) || current is FilesPanel { return true }
            at = current.superview
        }
        return false
    }

    /// Whether `view` paints: it draws itself, or a layer of it holds a bitmap or is a shape.
    static func paints(_ view: NSView) -> Bool {
        let draw = #selector(NSView.draw(_:))
        let drawsItself = class_getMethodImplementation(type(of: view), draw) != class_getMethodImplementation(NSView.self, draw)
        return drawsItself || (view.layer.map(tree) ?? []).contains { $0.contents != nil || $0 is CAShapeLayer }
    }

    private static func cover(view: NSView?, layer: CALayer?, at frame: NSRect, over rect: NSRect) -> Cover? {
        let covers = frame.intersection(rect)
        return covers.isEmpty ? nil : Cover(view: view, layer: layer, covers: covers)
    }

    /// The bare layers on `host`'s layer: its sublayers that back no view.
    private static func bare(on host: NSView) -> [CALayer] {
        (host.layer?.sublayers ?? []).filter { !($0.delegate is NSView) }
    }

    /// The bare layers on each of `terminal`'s ancestors below `root`, ordered above the layer of
    /// the ancestor's child that leads to the terminal, with the ancestor that holds them.
    private static func bareAbove(_ terminal: NSView, in root: NSView) -> [(NSView, CALayer)] {
        var pairs: [(NSView, CALayer)] = [], child = terminal
        while let parent = child.superview, parent !== root {
            let sublayers = parent.layer?.sublayers ?? []
            let floor = child.layer.flatMap { sublayers.firstIndex(of: $0) } ?? -1
            pairs += sublayers.enumerated().filter { $0.offset > floor && !($0.element.delegate is NSView) }.map { (parent, $0.element) }
            child = parent
        }
        return pairs
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

/// Counts the bitmaps given to the layers it watches: each time one's `contents` is set to
/// something, as a view that draws itself is given its bitmap. A layer is watched from when it is
/// handed in, so one that comes and goes between two hand-ins is never counted.
@MainActor package final class PaintWatch {
    private final class Count: Sendable {
        let value = Mutex(0)
    }

    private let count = Count()
    private var watched: [ObjectIdentifier: NSKeyValueObservation] = [:]

    package init() {}

    package var paints: Int {
        count.value.withLock { $0 }
    }

    package func watch(_ layers: [CALayer]) {
        for layer in layers where watched[ObjectIdentifier(layer)] == nil {
            watched[ObjectIdentifier(layer)] = layer.observe(\.contents) { [count] layer, _ in
                if layer.contents != nil { count.value.withLock { $0 += 1 } }
            }
        }
    }

    package func stop() {
        watched.values.forEach { $0.invalidate() }
        watched = [:]
    }
}
