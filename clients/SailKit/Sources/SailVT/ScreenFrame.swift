/// One colour, eight bits a channel.
public struct RGB: Hashable, Sendable, CustomStringConvertible {
    public var red: UInt8
    public var green: UInt8
    public var blue: UInt8

    public init(red: UInt8, green: UInt8, blue: UInt8) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    /// `0xRRGGBB`.
    public init(_ hex: UInt32) {
        self.init(red: UInt8(hex >> 16 & 0xFF), green: UInt8(hex >> 8 & 0xFF), blue: UInt8(hex & 0xFF))
    }

    /// This colour moved `fraction` of the way to `other`, each channel rounded.
    public func blended(toward other: RGB, by fraction: Double) -> RGB {
        func mix(_ from: UInt8, _ to: UInt8) -> UInt8 {
            UInt8((Double(from) + (Double(to) - Double(from)) * fraction).rounded())
        }
        return RGB(red: mix(red, other.red), green: mix(green, other.green), blue: mix(blue, other.blue))
    }

    /// `#rrggbb`.
    public var description: String {
        "#" + [red, green, blue].map { ($0 < 16 ? "0" : "") + String($0, radix: 16) }.joined()
    }
}

/// A grid's size in cells.
public struct GridSize: Hashable, Sendable, CustomStringConvertible {
    public var columns: Int
    public var rows: Int

    public init(columns: Int, rows: Int) {
        self.columns = columns
        self.rows = rows
    }

    public var description: String {
        "\(columns)x\(rows)"
    }
}

/// A cell of the viewport: column `x` of row `y`, both from zero.
public struct CellPoint: Hashable, Sendable {
    public var x: Int
    public var y: Int

    public init(x: Int, y: Int) {
        self.x = x
        self.y = y
    }
}

/// Everything a renderer draws one frame from: the viewport's rows, the cursor, the default
/// colours, and which rows changed since the frame before.
public struct ScreenFrame: Equatable, Sendable {
    public var size: GridSize
    public var rows: [Row]
    public var cursor: Cursor
    public var colors: DefaultColors
    /// Which rows' cells changed since the last ``Screen/frame()``; the cursor and colours are
    /// always current.
    public var changed: Change

    public struct Row: Equatable, Sendable {
        public var cells: [Cell]

        public init(cells: [Cell]) {
            self.cells = cells
        }
    }

    public enum Change: Equatable, Sendable {
        case nothing
        case rows(Set<Int>)
        case all

        func merged(with other: Change) -> Change {
            switch (self, other) {
            case (.all, _), (_, .all): .all
            case (.nothing, let change), (let change, .nothing): change
            case let (.rows(some), .rows(more)): .rows(some.union(more))
            }
        }
    }
}

/// One cell as it is drawn. `foreground` and `background` are resolved: the palette looked up,
/// unset colours taken from the defaults, inverse swapped, and dim blended toward the background.
public struct Cell: Equatable, Sendable {
    /// The cell's grapheme cluster, empty when it holds no text.
    public var grapheme: String
    public var width: CellWidth
    public var foreground: RGB
    public var background: RGB
    public var attributes: CellAttributes
    public var underline: Underline
    public var selected: Bool

    public init(
        grapheme: String, width: CellWidth, foreground: RGB, background: RGB, attributes: CellAttributes,
        underline: Underline, selected: Bool
    ) {
        self.grapheme = grapheme
        self.width = width
        self.foreground = foreground
        self.background = background
        self.attributes = attributes
        self.underline = underline
        self.selected = selected
    }
}

public enum CellWidth: Equatable, Sendable {
    case narrow
    /// The first of two cells a wide character takes.
    case wide
    /// The second cell of a wide character: draw nothing.
    case spacerTail
    /// The last cell of a row that wrapped before a wide character: draw nothing.
    case spacerHead
}

public struct CellAttributes: OptionSet, Hashable, Sendable {
    public let rawValue: UInt8

    public init(rawValue: UInt8) {
        self.rawValue = rawValue
    }

    public static let bold = CellAttributes(rawValue: 1 << 0)
    public static let italic = CellAttributes(rawValue: 1 << 1)
    public static let dim = CellAttributes(rawValue: 1 << 2)
    public static let inverse = CellAttributes(rawValue: 1 << 3)
    public static let invisible = CellAttributes(rawValue: 1 << 4)
    public static let strikethrough = CellAttributes(rawValue: 1 << 5)
}

public enum Underline: Equatable, Sendable {
    case none, single, double, curly, dotted, dashed
}

public struct Cursor: Equatable, Sendable {
    /// Where the cursor is, nil when it is outside the viewport (scrolled into history).
    public var position: CellPoint?
    public var style: CursorStyle
    public var visible: Bool
    public var blinking: Bool

    public init(position: CellPoint?, style: CursorStyle, visible: Bool, blinking: Bool) {
        self.position = position
        self.style = style
        self.visible = visible
        self.blinking = blinking
    }
}

public enum CursorStyle: Equatable, Sendable {
    case bar, block, underline, blockHollow
}

public struct DefaultColors: Equatable, Sendable {
    public var foreground: RGB
    public var background: RGB
    /// The cursor colour a program set, nil when it set none: draw the palette's.
    public var cursor: RGB?

    public init(foreground: RGB, background: RGB, cursor: RGB?) {
        self.foreground = foreground
        self.background = background
        self.cursor = cursor
    }
}
