@testable import SailVT
import Testing

/// A screen takes the other look's palette while it runs: its content, scrollback, selection and
/// cursor stay; colours a program set itself stay as it set them; a program asking for the
/// colours or the scheme is answered with the new ones; one that subscribed to scheme reports
/// (mode 2031) is told.
@Suite struct LiveLookTests {
    static let dark = Array("\u{1b}[?997;1n".utf8)
    static let light = Array("\u{1b}[?997;2n".utf8)

    static func answer(_ screen: Screen, _ query: String) -> String {
        String(decoding: screen.feed(Array(query.utf8)).reply, as: UTF8.self)
    }

    @Test func aRunningScreenTakesTheOtherPaletteAndKeepsWhatItHolds() throws {
        let screen = try Screen(size: GridSize(columns: 40, rows: 4), scrollbackBytes: 1 << 20, palette: .dark)
        _ = screen.feed(Array((0 ..< 30).map { "\u{1b}[31mline \($0)\u{1b}[0m\r\n" }.joined().utf8))
        #expect(screen.select(.word(at: CellPoint(x: 1, y: 0))))
        _ = try screen.frame()
        let before = try screen.frame()
        let text = try screen.text()
        let selected = screen.selectedText()

        _ = try screen.setPalette(.light)
        let after = try screen.frame()

        #expect(after.colors.background == Palette.light.background && after.colors.foreground == Palette.light.foreground)
        #expect(after.changed == .all, "every row drawn again")
        #expect(after.rows.map(\.text) == before.rows.map(\.text) && after.cursor == before.cursor)
        #expect(try screen.text() == text, "the scrollback is the same")
        #expect(screen.selectedText() == selected && selected != nil, "the selection is the same")
        #expect(after.rows[0].cells[0].foreground == Palette.light.ansi[1], "the program's red is the light look's red")
    }

    @Test func theCursorTakesThePalettesColour() throws {
        let screen = try Screen(size: GridSize(columns: 40, rows: 4), scrollbackBytes: 1 << 20, palette: .dark)
        var look = Palette.light
        look.cursor = RGB(0x123456)
        _ = try screen.setPalette(look)
        #expect(try screen.frame().colors.cursor == look.cursor)
    }

    @Test func aProgramAskingForTheColoursIsAnsweredWithTheNewOnesAndKeepsItsOwn() throws {
        let screen = try Screen(size: GridSize(columns: 40, rows: 4), scrollbackBytes: 1 << 20, palette: .dark)
        _ = screen.feed(Array("\u{1b}]4;5;rgb:12/34/56\u{1b}\\".utf8))
        #expect(Self.answer(screen, "\u{1b}]11;?\u{1b}\\") == "\u{1b}]11;rgb:0a0a/0e0e/1111\u{1b}\\")
        #expect(Self.answer(screen, "\u{1b}[?996n") == String(decoding: Self.dark, as: UTF8.self))

        _ = try screen.setPalette(.light)

        #expect(Self.answer(screen, "\u{1b}]11;?\u{1b}\\") == "\u{1b}]11;rgb:f8f8/fafa/fcfc\u{1b}\\")
        #expect(Self.answer(screen, "\u{1b}]10;?\u{1b}\\") == "\u{1b}]10;rgb:0e0e/1212/1717\u{1b}\\")
        #expect(Self.answer(screen, "\u{1b}]4;1;?\u{1b}\\") == "\u{1b}]4;1;rgb:b4b4/2323/1818\u{1b}\\")
        #expect(Self.answer(screen, "\u{1b}]4;5;?\u{1b}\\") == "\u{1b}]4;5;rgb:1212/3434/5656\u{1b}\\", "the program's own colour stays")
        #expect(Self.answer(screen, "\u{1b}[?996n") == String(decoding: Self.light, as: UTF8.self), "the scheme is the new one")
    }

    /// Mode 2031: a subscriber is told when the scheme changes, and on asking for the report again
    /// (a writer's attach); a program that did not subscribe is told nothing, and learns by asking.
    @Test func aSubscriberIsToldOfTheSchemeAndNoOneElseIs() throws {
        let screen = try Screen(size: GridSize(columns: 40, rows: 4), scrollbackBytes: 1 << 20, palette: .dark)
        #expect(try screen.setPalette(.light).reply.isEmpty, "not subscribed: nothing said")
        #expect(screen.schemeReport().isEmpty)
        _ = screen.feed(Array("\u{1b}[?2031h".utf8))
        #expect(try screen.setPalette(.dark).reply == Self.dark)
        #expect(try screen.setPalette(.dark).reply.isEmpty, "the same scheme again: nothing new to tell")
        #expect(screen.schemeReport() == Self.dark, "asked for again, at an attach")
        _ = screen.feed(Array("\u{1b}[?2031l".utf8))
        #expect(try screen.setPalette(.light).reply.isEmpty, "unsubscribed: nothing said")
    }
}
