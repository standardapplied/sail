import SailVT
import Testing

/// Grapheme clustering (mode 2027) is on from the terminal's creation and survives every reset:
/// an emoji sequence, a flag and a skin-toned emoji each take one two-cell cluster.
@Suite struct ClusteringTests {
    static let sequences = ["👨\u{200D}👩\u{200D}👧", "🇯🇵", "👍🏽"]

    private func screen() throws -> Screen {
        try Screen(size: GridSize(columns: 20, rows: 2), scrollbackBytes: 0, palette: .dark)
    }

    private func expectOneCluster(_ sequence: String, on screen: Screen, _ when: String) throws {
        _ = screen.feed(Array("\u{1b}[H\u{1b}[2J\(sequence)x".utf8))
        let cells = try screen.frame().rows[0].cells
        #expect(cells[0].grapheme == sequence, "\(when): \(sequence.unicodeScalars.map { String($0.value, radix: 16) })")
        #expect(cells[0].width == .wide, "\(when)")
        #expect(cells[1].width == .spacerTail, "\(when)")
        #expect(cells[2].grapheme == "x", "\(when): the next character takes the third cell")
    }

    @Test(arguments: sequences) func aSequenceIsOneTwoCellCluster(_ sequence: String) throws {
        try expectOneCluster(sequence, on: screen(), "at creation")
    }

    @Test(arguments: sequences) func clusteringSurvivesAResetAndAReplay(_ sequence: String) throws {
        let screen = try screen()
        _ = screen.feed(Array("\u{1b}c".utf8))
        try expectOneCluster(sequence, on: screen, "after RIS")
        screen.reset()
        try expectOneCluster(sequence, on: screen, "after reset()")
        screen.beginReplay()
        screen.endReplay()
        try expectOneCluster(sequence, on: screen, "after a replay")
    }

    @Test func aProgramAskingSeesModeTwentyTwentySevenSet() throws {
        let screen = try screen()
        let answer = screen.feed(Array("\u{1b}[?2027$p".utf8)).reply
        #expect(answer == Array("\u{1b}[?2027;1$y".utf8))
    }

    @Test func aGraphemeIsMeasuredAsTheTerminalPrintsIt() {
        #expect(Graphemes.cells("a") == 1)
        #expect(Graphemes.cells("に") == 2)
        #expect(Graphemes.cells("👨\u{200D}👩\u{200D}👧") == 2)
        #expect(Graphemes.cells("é") == 1)
        #expect(Graphemes.cells("") == 0)
    }
}
