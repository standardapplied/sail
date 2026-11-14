extension Screen {
    /// The screen as a fixture's golden holds it: the frame (``ScreenFrame/dump``), what feeding it
    /// produced, and what three fixed inputs encode to in the modes the stream left (Ctrl-C, a
    /// two-line paste, a focus loss), so a golden also pins the modes a stream sets.
    public func dump(after effects: Effects) throws(ScreenError) -> String {
        var lines = [try frame().dump]
        if !effects.reply.isEmpty {
            lines.append("reply \(hex(effects.reply))")
        }
        lines += effects.events.map { "event \($0)" }
        let paste = switch paste("x\ny") {
        case let .send(bytes): hex(bytes)
        case .needsConfirmation: "needs confirmation"
        case .nothingToPaste: "nothing"
        }
        lines.append("input ctrl-c \(hex(encode(KeyInput(key: .c, modifiers: .control, text: "c", unshifted: "c"))))")
        lines.append("input paste \(paste)")
        lines.append("input focus-lost \(hex(focus(false)))")
        return lines.joined(separator: "\n") + "\n"
    }
}

extension ScreenFrame {
    /// The frame as text: its size, cursor and colours, then each row that holds anything, as its
    /// text between bars and the runs of cells whose look differs from the defaults.
    public var dump: String {
        var lines = [
            "size \(size)",
            "cursor \(cursor.dump)",
            "colors fg \(colors.foreground) bg \(colors.background)" + (colors.cursor.map { " cursor \($0)" } ?? ""),
        ]
        for (y, row) in rows.enumerated() {
            let text = Screen.text(of: [row])
            let runs = runs(of: row)
            guard !text.isEmpty || !runs.isEmpty else { continue }
            lines.append("row \(y) |\(text)|")
            lines += runs.map { "  \($0)" }
        }
        return lines.joined(separator: "\n")
    }

    /// Each maximal run of cells that look alike and unlike a default cell, as `first-last look`.
    private func runs(of row: Row) -> [String] {
        var runs: [(range: ClosedRange<Int>, look: String)] = []
        for (x, cell) in row.cells.enumerated() {
            let look = look(of: cell)
            if let last = runs.last, last.look == look, last.range.upperBound == x - 1 {
                runs[runs.count - 1].range = last.range.lowerBound ... x
            } else {
                runs.append((x ... x, look))
            }
        }
        return runs.filter { !$0.look.isEmpty }.map { run in
            let span = run.range.count == 1 ? "\(run.range.lowerBound)" : "\(run.range.lowerBound)-\(run.range.upperBound)"
            return "\(span) \(run.look)"
        }
    }

    private func look(of cell: Cell) -> String {
        var parts: [String] = []
        if cell.foreground != colors.foreground { parts.append("fg \(cell.foreground)") }
        if cell.background != colors.background { parts.append("bg \(cell.background)") }
        let names: [(CellAttributes, String)] = [
            (.bold, "bold"), (.italic, "italic"), (.dim, "dim"), (.inverse, "inverse"), (.invisible, "invisible"),
            (.strikethrough, "strikethrough"),
        ]
        parts += names.filter { cell.attributes.contains($0.0) }.map(\.1)
        if cell.underline != .none { parts.append("underline \(cell.underline)") }
        switch cell.width {
        case .narrow: break
        case .wide: parts.append("wide")
        case .spacerTail: parts.append("spacer-tail")
        case .spacerHead: parts.append("spacer-head")
        }
        if cell.selected { parts.append("selected") }
        return parts.joined(separator: " ")
    }
}

private extension Cursor {
    var dump: String {
        let place = position.map { "\($0.x),\($0.y)" } ?? "off-screen"
        return "\(place) \(style) \(visible ? "visible" : "hidden") \(blinking ? "blinking" : "steady")"
    }
}

func hex(_ bytes: [UInt8]) -> String {
    bytes.isEmpty ? "-" : bytes.map { ($0 < 16 ? "0" : "") + String($0, radix: 16) }.joined()
}
