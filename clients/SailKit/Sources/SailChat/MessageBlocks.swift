public struct MessageBlocks: Sendable {
    public enum Block: Equatable, Sendable {
        case paragraph(String)
        case heading(level: Int, text: String)
        case code(language: String, text: String)
        case list(start: Int?, items: [[Block]])
        case quote([Block])
        case rule
        case table(Table)
    }

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

    public init(_ text: String = "") {
        append(text)
    }

    public var blocks: [Block] { settled + open }

    public static func split(_ text: String) -> [Block] { [] }

    public mutating func append(_ text: String) {}
}
