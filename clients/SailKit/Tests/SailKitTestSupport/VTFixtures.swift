import Foundation

/// The byte-stream fixtures in `Tests/Fixtures/vt`: each stream, the grid it is fed into, and the
/// golden of the screen it leaves, which `regenerate` writes with SailVT's own dump.
public enum VTFixtures {
    static let root = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "Fixtures/vt")

    public struct Fixture: Sendable, CustomStringConvertible {
        public let name: String
        public let columns: Int
        public let rows: Int

        public var bytes: [UInt8] {
            get throws { [UInt8](try Data(contentsOf: VTFixtures.root.appending(path: "\(name).bytes"))) }
        }

        public var golden: String {
            get throws { try String(contentsOf: VTFixtures.root.appending(path: "\(name).golden"), encoding: .utf8) }
        }

        public var description: String {
            name
        }
    }

    /// Every fixture `sizes` lists, as `name COLUMNSxROWS` lines.
    public static func all() throws -> [Fixture] {
        try String(contentsOf: root.appending(path: "sizes"), encoding: .utf8)
            .split(separator: "\n")
            .map { line in
                let fields = line.split(separator: " ")
                let size = fields.count == 2 ? fields[1].split(separator: "x").compactMap { Int($0) } : []
                guard size.count == 2 else { throw GoldenError(reason: "sizes holds '\(line)', not 'name COLUMNSxROWS'") }
                return Fixture(name: String(fields[0]), columns: size[0], rows: size[1])
            }
    }
}
