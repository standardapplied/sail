import Foundation
import SailVT
import Testing

/// The review's probes of `cursorAtPrompt` beyond scenario 12 (both looks): at a prompt only now,
/// from a shell that marks its commands, with none running, on a row of prompt cells, and nothing
/// kept across a full reset.
@Suite struct ReviewPromptTests {
    func screen(_ fed: String) throws -> Screen {
        let screen = try Screen(size: GridSize(columns: 40, rows: 6), scrollbackBytes: 1 << 20, palette: .dark)
        _ = screen.feed(Array(fed.utf8))
        return screen
    }

    @Test(arguments: [
        ("A, B, a command and Enter, no C", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}sleep 100\r\n", false),
        ("A, B, a command, output, no C", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}cat\r\nsome output\r\n", false),
        ("A, B at an idle prompt, never a C", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}", false),
        ("A only", "\u{1b}]133;A\u{07}$ ", false),
        ("C then an unmarked remote prompt", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}ssh box\r\n\u{1b}]133;C\u{07}box$ ", false),
        ("a continuation line after A, B", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}for i in 1 2; do\r\n> ", false),
        ("A, B, C, D, then the next A, B", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}ls\r\n\u{1b}]133;C\u{07}out\r\n\u{1b}]133;D;0\u{07}\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}", true),
        ("A, B, C, D, then a command running under the next A, B", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}ls\r\n\u{1b}]133;C\u{07}\u{1b}]133;D;0\u{07}\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}sleep 9\r\n\u{1b}]133;C\u{07}", false),
        ("C once, then A, B and a command with no C", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}ls\r\n\u{1b}]133;C\u{07}out\r\n\u{1b}]133;D;0\u{07}\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}sleep 100\r\n", false),
        ("C once, a full reset, then A, B", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}ls\r\n\u{1b}]133;C\u{07}\u{1b}]133;D;0\u{07}\u{1b}c\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}", false),
        ("C once, a full reset, then a command with no C", "\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}ls\r\n\u{1b}]133;C\u{07}\u{1b}]133;D;0\u{07}\u{1b}c\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}sleep 100\r\n", false),
        ("a shell that marks commands runs a nested shell marking only its prompt, then sleep 100", "\u{1b}]133;C\u{07}\u{1b}]133;D;0\u{07}\u{1b}]133;A\u{07}% \u{1b}]133;B\u{07}bash\r\n\u{1b}]133;C\u{07}\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}sleep 100\r\n", false),
        ("the nested shell's idle prompt", "\u{1b}]133;C\u{07}\u{1b}]133;D;0\u{07}\u{1b}]133;A\u{07}% \u{1b}]133;B\u{07}bash\r\n\u{1b}]133;C\u{07}\u{1b}]133;A\u{07}$ \u{1b}]133;B\u{07}", false),
    ])
    func thePromptIsTrustedOnlyFromAShellThatMarksItsCommands(_ name: String, _ fed: String, _ atPrompt: Bool) throws {
        #expect(try screen(fed).cursorAtPrompt == atPrompt, "\(name)")
    }
}
