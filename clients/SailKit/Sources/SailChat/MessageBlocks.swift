/// An agent's message read as Markdown's blocks: the first of CommonMark's two phases, the one that
/// decides a message's layout. Each block keeps its inline Markdown as written, for the view to
/// style; a code block's text and a table cell's are what the view copies.
///
/// Read: ATX headings, fenced code, bullet and ordered lists nested by indent, block quotes,
/// thematic breaks and GFM pipe tables. Read as paragraph text in this first build: indented code,
/// setext headings (a `---` under text is a rule), HTML, link reference definitions, a lazy line
/// (one that goes on with a list item or a quote without its indent or its `>`), and a table that
/// does not start its block.
///
/// The text is the agent's, so untrusted: HTML stays text and nothing is fetched; lists and quotes
/// nest at most ``depth`` deep, where deeper markers are text; a table has at most ``columns``
/// columns; and the work is linear in the text.
///
/// As text streams in, ``settled`` only grows: a block settles once a finished line after it starts
/// another, since until its line ends, a line that starts a block (`#`) can still turn into text of
/// the block before it (`#x`). The rest is ``open``: the last block, and the one before it while the
/// last starts on the unfinished line.
public struct MessageBlocks: Sendable {
    public enum Block: Equatable, Sendable {
        case paragraph(String)
        case heading(level: Int, text: String)
        /// `language` is the first word of the fence's info string, or empty; `text` is its lines
        /// as written, without the fences, to the end of the message while no fence closes it.
        case code(language: String, text: String)
        /// `start` is an ordered list's first number, nil for bullets; each item is its blocks.
        case list(start: Int?, items: [[Block]])
        case quote([Block])
        case rule
        case table(Table)
    }

    /// Every row has as many cells as the header.
    public struct Table: Equatable, Sendable {
        public enum Alignment: Equatable, Sendable { case none, left, center, right }

        public let alignments: [Alignment]
        public let header: [String]
        public let rows: [[String]]
    }

    public static let depth = 8
    public static let columns = 32

    public private(set) var settled: [Block] = []
    public private(set) var open: [Block] = []
    private var tail = ""

    public init(_ text: String = "") {
        append(text)
    }

    public var blocks: [Block] { settled + open }

    public static func split(_ text: String) -> [Block] {
        Reader.blocks(Reader.lines(text), depth: 0)
    }

    /// Reads only from the first open block's line on: what settled is never read again.
    public mutating func append(_ text: String) {
        tail += text
        let lines = Reader.lines(tail)
        let read = Reader(lines: lines, depth: 0).all()
        let unfinished = tail.utf8.last.map { $0 == UInt8(ascii: "\n") ? lines.count : lines.count - 1 } ?? 0
        let kept = read.count > 1 && read[read.count - 1].line == unfinished ? 2 : min(read.count, 1)
        settled += read.dropLast(kept).map(\.block)
        open = read.suffix(kept).map(\.block)
        let from = kept > 0 ? read[read.count - kept].line : unfinished
        tail = from < lines.count ? String(Substring(tail.unicodeScalars[lines[from].startIndex...])) : ""
    }
}

private typealias Block = MessageBlocks.Block

private struct Reader {
    let lines: [Substring]
    let depth: Int
    var at = 0

    static func lines(_ text: String) -> [Substring] {
        let bytes = text.utf8
        var lines: [Substring] = [], start = bytes.startIndex
        for at in bytes.indices where bytes[at] == UInt8(ascii: "\n") {
            lines.append(line(text, start ..< at))
            start = bytes.index(after: at)
        }
        if start < bytes.endIndex { lines.append(line(text, start ..< bytes.endIndex)) }
        return lines
    }

    static func line(_ text: String, _ range: Range<String.Index>) -> Substring {
        let line = Substring(text.unicodeScalars[range])
        return line.utf8.last == UInt8(ascii: "\r") ? Substring(line.unicodeScalars.dropLast()) : line
    }

    static func blocks(_ lines: [Substring], depth: Int) -> [Block] {
        Reader(lines: lines, depth: depth).all().map(\.block)
    }

    consuming func all() -> [(line: Int, block: Block)] {
        var read: [(line: Int, block: Block)] = []
        while at < lines.count {
            if lines[at].isBlank {
                at += 1
            } else {
                let line = at
                read.append((line, next()))
            }
        }
        return read
    }

    mutating func next() -> Block {
        let line = lines[at]
        if let fence = Fence(line) { return code(fence) }
        if let heading = line.heading {
            at += 1
            return heading
        }
        if line.isRule {
            at += 1
            return .rule
        }
        if depth < MessageBlocks.depth, line.quoted != nil { return quote() }
        if depth < MessageBlocks.depth, let marker = Marker(line) { return list(marker) }
        return table() ?? paragraph()
    }

    func interrupts(_ line: Substring) -> Bool {
        if Fence(line) != nil || line.heading != nil || line.isRule { return true }
        guard depth < MessageBlocks.depth else { return false }
        if line.quoted != nil { return true }
        guard let marker = Marker(line), !marker.first.isBlank else { return false }
        return marker.start == nil || marker.start == 1
    }

    mutating func paragraph() -> Block {
        var text = [lines[at].trimmed]
        at += 1
        while at < lines.count, !lines[at].isBlank, !interrupts(lines[at]) {
            text.append(lines[at].trimmed)
            at += 1
        }
        return .paragraph(text.joined(separator: "\n"))
    }

    mutating func code(_ fence: Fence) -> Block {
        var text: [Substring] = []
        at += 1
        while at < lines.count, !fence.closes(lines[at]) {
            text.append(lines[at].dropping(columns: fence.indent))
            at += 1
        }
        at = min(at + 1, lines.count)
        return .code(language: fence.language, text: text.joined(separator: "\n"))
    }

    mutating func quote() -> Block {
        var inner: [Substring] = []
        while at < lines.count, let content = lines[at].quoted {
            inner.append(content)
            at += 1
        }
        return .quote(Self.blocks(inner, depth: depth + 1))
    }

    mutating func list(_ first: Marker) -> Block {
        var items: [[Block]] = [], marker = first
        while true {
            items.append(Self.blocks(item(marker), depth: depth + 1))
            guard let sibling = sibling(of: marker) else { break }
            marker = sibling
        }
        return .list(start: first.start, items: items)
    }

    /// The lines of the item `marker` starts, to their content's column: blank lines belong to it
    /// only when a line indented to it follows them.
    mutating func item(_ marker: Marker) -> [Substring] {
        var body = [marker.first]
        at += 1
        while at < lines.count {
            var after = at
            while after < lines.count, lines[after].isBlank { after += 1 }
            guard after < lines.count, lines[after].indent.columns >= marker.content else { break }
            body += Array(repeating: "", count: after - at)
            body.append(lines[after].dropping(columns: marker.content))
            at = after + 1
        }
        return body
    }

    mutating func sibling(of marker: Marker) -> Marker? {
        var next = at
        while next < lines.count, lines[next].isBlank { next += 1 }
        guard next < lines.count, !lines[next].isRule, let sibling = Marker(lines[next]),
              (sibling.start == nil) == (marker.start == nil), sibling.delimiter == marker.delimiter
        else { return nil }
        at = next
        return sibling
    }

    mutating func table() -> Block? {
        guard at + 1 < lines.count, lines[at].utf8.contains(UInt8(ascii: "|")), lines[at + 1].utf8.contains(UInt8(ascii: "|")) else { return nil }
        let header = lines[at].cells(upTo: MessageBlocks.columns + 1)
        guard header.count <= MessageBlocks.columns, let alignments = lines[at + 1].alignments(header.count) else { return nil }
        at += 2
        var rows: [[String]] = []
        while at < lines.count, !lines[at].isBlank, !interrupts(lines[at]) {
            let cells = lines[at].cells(upTo: header.count)
            rows.append(cells + Array(repeating: "", count: header.count - cells.count))
            at += 1
        }
        return .table(MessageBlocks.Table(alignments: alignments, header: header, rows: rows))
    }
}

private struct Fence {
    let indent: Int
    let mark: UInt8
    let length: Int
    let language: String

    init?(_ line: Substring) {
        let (indent, text) = line.indent
        guard indent < 4, text < line.utf8.endIndex, line.utf8[text] == UInt8(ascii: "`") || line.utf8[text] == UInt8(ascii: "~") else { return nil }
        let (mark, after) = (line.utf8[text], line.run(from: text))
        let info = Substring(line.unicodeScalars[after...]).trimmed
        guard line.utf8.distance(from: text, to: after) >= 3, mark == UInt8(ascii: "~") || !info.utf8.contains(UInt8(ascii: "`")) else { return nil }
        self.indent = indent
        self.mark = mark
        length = line.utf8.distance(from: text, to: after)
        language = String(info.unicodeScalars.prefix { $0 != " " && $0 != "\t" })
    }

    func closes(_ line: Substring) -> Bool {
        let (indent, text) = line.indent
        guard indent < 4, text < line.utf8.endIndex, line.utf8[text] == mark else { return false }
        let after = line.run(from: text)
        return line.utf8.distance(from: text, to: after) >= length && Substring(line.unicodeScalars[after...]).isBlank
    }
}

private struct Marker {
    let start: Int?
    let delimiter: UInt8
    let content: Int
    let first: Substring

    init?(_ line: Substring) {
        let bytes = line.utf8
        let (indent, text) = line.indent
        guard indent < 4, text < bytes.endIndex else { return nil }
        var at = text, digits = 0, value = 0
        while at < bytes.endIndex, digits < 10, let digit = bytes[at].digit {
            (value, digits, at) = (value * 10 + digit, digits + 1, bytes.index(after: at))
        }
        guard at < bytes.endIndex, digits <= 9 else { return nil }
        let bullet = digits == 0 && [UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "+")].contains(bytes[at])
        guard bullet || digits > 0 && (bytes[at] == UInt8(ascii: ".") || bytes[at] == UInt8(ascii: ")")) else { return nil }
        let rest = Substring(line.unicodeScalars[bytes.index(after: at)...])
        guard rest.isBlank || rest.utf8.first == UInt8(ascii: " ") || rest.utf8.first == UInt8(ascii: "\t") else { return nil }
        let width = indent + digits + 1, (spaces, content) = rest.indent
        start = bullet ? nil : value
        delimiter = bytes[at]
        self.content = rest.isBlank || spaces > 4 ? width + 1 : width + spaces
        first = rest.isBlank ? "" : spaces > 4 ? Substring(rest.unicodeScalars.dropFirst()) : Substring(rest.unicodeScalars[content...])
    }
}

private extension UInt8 {
    var digit: Int? { (UInt8(ascii: "0") ... UInt8(ascii: "9")).contains(self) ? Int(self - UInt8(ascii: "0")) : nil }
    var isSpace: Bool { self == UInt8(ascii: " ") || self == UInt8(ascii: "\t") }
}

private extension Substring {
    /// The columns of the line's leading spaces and tabs, tabs to the next multiple of 4, and
    /// where its text starts.
    var indent: (columns: Int, text: Index) {
        var columns = 0, at = utf8.startIndex
        while at < utf8.endIndex, utf8[at].isSpace {
            columns += utf8[at] == UInt8(ascii: " ") ? 1 : 4 - columns % 4
            at = utf8.index(after: at)
        }
        return (columns, at)
    }

    var isBlank: Bool { indent.text == utf8.endIndex }

    var trimmed: Substring {
        let start = indent.text
        var end = utf8.endIndex
        while end > start, utf8[utf8.index(before: end)].isSpace { end = utf8.index(before: end) }
        return Substring(unicodeScalars[start ..< end])
    }

    func dropping(columns: Int) -> Substring {
        var dropped = 0, at = utf8.startIndex
        while at < utf8.endIndex, dropped < columns, utf8[at].isSpace {
            dropped += utf8[at] == UInt8(ascii: " ") ? 1 : 4 - dropped % 4
            at = utf8.index(after: at)
        }
        return Substring(unicodeScalars[at...])
    }

    /// Where the run of the byte at `start` ends.
    func run(from start: Index) -> Index {
        var at = start
        while at < utf8.endIndex, utf8[at] == utf8[start] { at = utf8.index(after: at) }
        return at
    }

    var heading: Block? {
        let (indent, text) = self.indent
        guard indent < 4, text < utf8.endIndex, utf8[text] == UInt8(ascii: "#") else { return nil }
        let after = run(from: text), level = utf8.distance(from: text, to: after)
        guard level <= 6, after == utf8.endIndex || utf8[after].isSpace else { return nil }
        return .heading(level: level, text: String(Substring(unicodeScalars[after...]).trimmed.closed))
    }

    /// The heading's text without its closing sequence of `#`.
    var closed: Substring {
        var end = utf8.endIndex
        while end > utf8.startIndex, utf8[utf8.index(before: end)] == UInt8(ascii: "#") { end = utf8.index(before: end) }
        guard end > utf8.startIndex else { return "" }
        return end < utf8.endIndex && utf8[utf8.index(before: end)].isSpace ? Substring(unicodeScalars[..<end]).trimmed : self
    }

    var isRule: Bool {
        let (indent, text) = self.indent
        guard indent < 4, text < utf8.endIndex, [UInt8(ascii: "*"), UInt8(ascii: "-"), UInt8(ascii: "_")].contains(utf8[text]) else { return false }
        let mark = utf8[text]
        var marks = 0
        for byte in utf8[text...] {
            if byte == mark { marks += 1 } else if !byte.isSpace { return false }
        }
        return marks >= 3
    }

    var quoted: Substring? {
        let (indent, text) = self.indent
        guard indent < 4, text < utf8.endIndex, utf8[text] == UInt8(ascii: ">") else { return nil }
        let marker = utf8.index(after: text)
        let content = marker < utf8.endIndex && utf8[marker].isSpace ? utf8.index(after: marker) : marker
        return Substring(unicodeScalars[content...])
    }

    /// The row's cells, at most `limit` of them, each trimmed and with its escaped pipes as pipes.
    func cells(upTo limit: Int) -> [String] {
        var row = trimmed
        if row.utf8.first == UInt8(ascii: "|") { row = Substring(row.unicodeScalars.dropFirst()) }
        if row.utf8.last == UInt8(ascii: "|"), row.utf8.dropLast().last != UInt8(ascii: "\\") { row = Substring(row.unicodeScalars.dropLast()) }
        var cells: [String] = [], start = row.utf8.startIndex, escaped = false
        for at in row.utf8.indices where cells.count < limit {
            if row.utf8[at] == UInt8(ascii: "|"), !escaped {
                cells.append(row.cell(start ..< at))
                start = row.utf8.index(after: at)
            }
            escaped = row.utf8[at] == UInt8(ascii: "\\")
        }
        if cells.count < limit { cells.append(row.cell(start ..< row.utf8.endIndex)) }
        return cells
    }

    func cell(_ range: Range<Index>) -> String {
        String(Substring(unicodeScalars[range]).trimmed).replacing("\\|", with: "|")
    }

    /// A delimiter row's alignments, when it is one of `count` cells.
    func alignments(_ count: Int) -> [MessageBlocks.Table.Alignment]? {
        let cells = self.cells(upTo: count + 1)
        guard cells.count == count else { return nil }
        var alignments: [MessageBlocks.Table.Alignment] = []
        for cell in cells {
            let left = cell.hasPrefix(":"), right = cell.hasSuffix(":")
            let dashes = cell.dropFirst(left ? 1 : 0).dropLast(right && cell.count > 1 ? 1 : 0)
            guard !dashes.isEmpty, dashes.allSatisfy({ $0 == "-" }) else { return nil }
            alignments.append(left && right ? .center : left ? .left : right ? .right : .none)
        }
        return alignments
    }
}
