import Foundation
@testable import SailChat
import Testing

typealias Block = MessageBlocks.Block

func p(_ text: String) -> Block { .paragraph(text) }
func h(_ level: Int, _ text: String) -> Block { .heading(level: level, text: text) }
func code(_ language: String, _ text: String) -> Block { .code(language: language, text: text) }
func code(_ text: String) -> Block { .code(language: "", text: text) }
func bullets(_ items: [Block]...) -> Block { .list(start: nil, items: items) }
func ordered(_ start: Int, _ items: [Block]...) -> Block { .list(start: start, items: items) }
func quote(_ blocks: Block...) -> Block { .quote(blocks) }
func table(_ alignments: [MessageBlocks.Table.Alignment], _ header: [String], _ rows: [[String]]) -> Block {
    .table(MessageBlocks.Table(alignments: alignments, header: header, rows: rows))
}

/// Real replies of Claude Code's, from a probe chat, in `Tests/Fixtures/markdown`.
enum Replies {
    static let names = ["debounce", "merge-or-rebase"]

    static func text(_ name: String) throws -> String {
        let fixtures = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appending(path: "Fixtures/markdown")
        return try String(contentsOf: fixtures.appending(path: "\(name).md"), encoding: .utf8)
    }

    static let debounceCode = #"""
        final class Debouncer {
            private let delay: TimeInterval
            private let queue: DispatchQueue
            private var workItem: DispatchWorkItem?

            init(delay: TimeInterval, queue: DispatchQueue = .main) {
                self.delay = delay
                self.queue = queue
            }

            func debounce(_ action: @escaping () -> Void) {
                workItem?.cancel()
                let item = DispatchWorkItem(block: action)
                workItem = item
                queue.asyncAfter(deadline: .now() + delay, execute: item)
            }
        }

        let debouncer = Debouncer(delay: 0.3)
        debouncer.debounce { print("Searching for: query") }
        """#

    static let debounceOpening = [
        h(2, "Debouncing a Function in Swift"),
        p("A debounced function delays its execution until a set amount of time has passed without it being called again, so rapid repeated calls (such as keystrokes in a search field) collapse into a single invocation. In Swift, you can implement this with a `DispatchWorkItem` that is cancelled and rescheduled on every call, or with a `Task` that is cancelled and recreated."),
    ]

    static let debounce = debounceOpening + [
        code("swift", debounceCode),
        bullets(
            [p("Each call cancels the pending work item, so only the last call within the window runs.")],
            [
                p("The action runs on the queue you supply (main by default), so UI work is safe, but captured references may need `[weak self]` to avoid retain cycles."),
                bullets([p("If the owner of the debouncer is deallocated while work is pending, the closure still runs unless you cancel it in `deinit`.")]),
            ]
        ),
        ordered(
            1,
            [p("Create a `Debouncer` with the desired delay in seconds.")],
            [p("Store it as a property so it lives as long as the caller needs it.")],
            [p("Call `debounce { ... }` from each event handler; only the final call within the delay window executes.")]
        ),
        quote(p("Choose the delay carefully: too short and it barely reduces calls, too long and the UI feels unresponsive.")),
    ]

    static let mergeOrRebase = [
        h(1, "Git Merge vs. Git Rebase"),
        p("Both `git merge` and `git rebase` integrate changes from one branch into another, but they do it differently. Merge preserves history exactly as it happened, while rebase rewrites it to look linear."),
        table(
            [.left, .center, .right],
            ["Aspect", "Merge", "Rebase"],
            [
                ["History", "Preserves true branch history", "Rewrites into a linear sequence"],
                ["Commits", "Creates a new merge commit", "Replays commits onto new base"],
                ["Safety", "Safe on shared branches", "Avoid on shared, already-pushed branches"],
            ]
        ),
        .rule,
        p("Typical usage:"),
        code("bash", "git checkout feature\ngit rebase main"),
        p("Steps to integrate with merge, instead:"),
        ordered(
            3,
            [p("Check out the target branch, such as `main`.")],
            [p("Run `git merge feature` to create a merge commit that joins both histories.")],
            [p("Resolve any conflicts, then commit the result.")]
        ),
    ]
}

/// The CommonMark spec's examples (0.31.2) for the block kinds read, and GFM's (0.29) for its
/// tables, with the blocks each reads as. Examples whose answer turns on what this first build
/// leaves out (indented code, setext headings, lazy lines) are in `MessageBlocksTests.whatIsLeftOutIsText`.
enum Examples {
    static let headings: [(String, [Block])] = [
        ("# foo\n## foo\n### foo\n#### foo\n##### foo\n###### foo", (1 ... 6).map { h($0, "foo") }),
        ("####### foo", [p("####### foo")]),
        ("#5 bolt\n\n#hashtag", [p("#5 bolt"), p("#hashtag")]),
        ("\\## foo", [p("\\## foo")]),
        ("#                  foo                     ", [h(1, "foo")]),
        (" ### foo\n  ## foo\n   # foo", [h(3, "foo"), h(2, "foo"), h(1, "foo")]),
        ("## foo ##\n  ###   bar    ###", [h(2, "foo"), h(3, "bar")]),
        ("# foo ##################################\n##### foo ##", [h(1, "foo"), h(5, "foo")]),
        ("### foo ###     ", [h(3, "foo")]),
        ("### foo ### b", [h(3, "foo ### b")]),
        ("# foo#", [h(1, "foo#")]),
        ("### foo \\###\n## foo #\\##\n# foo \\#", [h(3, "foo \\###"), h(2, "foo #\\##"), h(1, "foo \\#")]),
        ("****\n## foo\n****", [.rule, h(2, "foo"), .rule]),
        ("Foo bar\n# baz\nBar foo", [p("Foo bar"), h(1, "baz"), p("Bar foo")]),
        ("## \n#\n### ###", [h(2, ""), h(1, ""), h(3, "")]),
    ]

    static let rules: [(String, [Block])] = [
        ("***\n---\n___", [.rule, .rule, .rule]),
        ("+++", [p("+++")]),
        ("===", [p("===")]),
        ("--\n**\n__", [p("--\n**\n__")]),
        (" ***\n  ***\n   ***", [.rule, .rule, .rule]),
        ("_____________________________________", [.rule]),
        (" - - -", [.rule]),
        (" **  * ** * ** * **", [.rule]),
        ("-     -      -      -", [.rule]),
        ("- - - -    ", [.rule]),
        ("_ _ _ _ a\n\na------\n\n---a---", [p("_ _ _ _ a"), p("a------"), p("---a---")]),
        (" *-*", [p("*-*")]),
        ("- foo\n***\n- bar", [bullets([p("foo")]), .rule, bullets([p("bar")])]),
        ("Foo\n***\nbar", [p("Foo"), .rule, p("bar")]),
        ("* Foo\n* * *\n* Bar", [bullets([p("Foo")]), .rule, bullets([p("Bar")])]),
        ("- Foo\n- * * *", [bullets([p("Foo")], [.rule])]),
    ]

    static let fences: [(String, [Block])] = [
        ("```\n<\n >\n```", [code("<\n >")]),
        ("~~~\n<\n >\n~~~", [code("<\n >")]),
        ("``\nfoo\n``", [p("``\nfoo\n``")]),
        ("```\naaa\n~~~\n```", [code("aaa\n~~~")]),
        ("~~~\naaa\n```\n~~~", [code("aaa\n```")]),
        ("````\naaa\n```\n``````", [code("aaa\n```")]),
        ("```", [code("")]),
        ("`````\n\n```\naaa", [code("\n```\naaa")]),
        ("> ```\n> aaa\n\nbbb", [quote(code("aaa")), p("bbb")]),
        ("```\n\n  \n```", [code("\n  ")]),
        ("```\n```", [code("")]),
        (" ```\n aaa\naaa\n```", [code("aaa\naaa")]),
        ("  ```\naaa\n  aaa\naaa\n  ```", [code("aaa\naaa\naaa")]),
        ("   ```\n   aaa\n    aaa\n  aaa\n   ```", [code("aaa\n aaa\naaa")]),
        ("```\naaa\n  ```", [code("aaa")]),
        ("   ```\naaa\n  ```", [code("aaa")]),
        ("```\naaa\n    ```", [code("aaa\n    ```")]),
        ("``` ```\naaa", [p("``` ```\naaa")]),
        ("~~~~~~\naaa\n~~~ ~~", [code("aaa\n~~~ ~~")]),
        ("foo\n```\nbar\n```\nbaz", [p("foo"), code("bar"), p("baz")]),
        ("```ruby\ndef foo(x)\n  return 3\nend\n```", [code("ruby", "def foo(x)\n  return 3\nend")]),
        ("~~~~    ruby startline=3 $%@#$\ndef foo(x)\n  return 3\nend\n~~~~~~~", [code("ruby", "def foo(x)\n  return 3\nend")]),
        ("````;\n````", [code(";", "")]),
        ("``` aa ```\nfoo", [p("``` aa ```\nfoo")]),
        ("~~~ aa ``` ~~~\nfoo\n~~~", [code("aa", "foo")]),
        ("```\n``` aaa\n```", [code("``` aaa")]),
    ]

    static let quotes: [(String, [Block])] = [
        ("> # Foo\n> bar\n> baz", [quote(h(1, "Foo"), p("bar\nbaz"))]),
        ("># Foo\n>bar\n> baz", [quote(h(1, "Foo"), p("bar\nbaz"))]),
        ("   > # Foo\n   > bar\n > baz", [quote(h(1, "Foo"), p("bar\nbaz"))]),
        ("> foo\n---", [quote(p("foo")), .rule]),
        (">", [quote()]),
        (">\n>  \n> ", [quote()]),
        (">\n> foo\n>  ", [quote(p("foo"))]),
        ("> foo\n\n> bar", [quote(p("foo")), quote(p("bar"))]),
        ("> foo\n> bar", [quote(p("foo\nbar"))]),
        ("> foo\n>\n> bar", [quote(p("foo"), p("bar"))]),
        ("foo\n> bar", [p("foo"), quote(p("bar"))]),
        ("> aaa\n***\n> bbb", [quote(p("aaa")), .rule, quote(p("bbb"))]),
    ]

    static let lists: [(String, [Block])] = [
        ("- foo\n- bar\n+ baz", [bullets([p("foo")], [p("bar")]), bullets([p("baz")])]),
        ("1. foo\n2. bar\n3) baz", [ordered(1, [p("foo")], [p("bar")]), ordered(3, [p("baz")])]),
        ("Foo\n- bar\n- baz", [p("Foo"), bullets([p("bar")], [p("baz")])]),
        ("The number of windows in my house is\n14.  The number of doors is 6.", [p("The number of windows in my house is\n14.  The number of doors is 6.")]),
        ("The number of windows in my house is\n1.  The number of doors is 6.", [p("The number of windows in my house is"), ordered(1, [p("The number of doors is 6.")])]),
        ("- foo\n\n- bar\n\n\n- baz", [bullets([p("foo")], [p("bar")], [p("baz")])]),
        ("- foo\n  - bar\n    - baz\n\n\n      bim", [bullets([p("foo"), bullets([p("bar"), bullets([p("baz"), p("bim")])])])]),
        ("- a\n - b\n  - c\n   - d\n  - e\n - f\n- g", [bullets([p("a")], [p("b")], [p("c")], [p("d")], [p("e")], [p("f")], [p("g")])]),
        ("1. a\n\n  2. b\n\n   3. c", [ordered(1, [p("a")], [p("b")], [p("c")])]),
        ("- a\n- b\n\n- c", [bullets([p("a")], [p("b")], [p("c")])]),
        ("* a\n*\n\n* c", [bullets([p("a")], [], [p("c")])]),
        ("- a\n- b\n\n  c\n- d", [bullets([p("a")], [p("b"), p("c")], [p("d")])]),
        ("- a\n  > b\n  ```\n  c\n  ```\n- d", [bullets([p("a"), quote(p("b")), code("c")], [p("d")])]),
        ("- a\n  - b\n\n    c\n- d", [bullets([p("a"), bullets([p("b"), p("c")])], [p("d")])]),
        ("123456789. ok", [ordered(123_456_789, [p("ok")])]),
        ("1234567890. not ok", [p("1234567890. not ok")]),
        ("0. ok", [ordered(0, [p("ok")])]),
        ("003. ok", [ordered(3, [p("ok")])]),
        ("-1. not ok", [p("-1. not ok")]),
        ("- foo\n  - bar\n    - baz\n      - boo", [bullets([p("foo"), bullets([p("bar"), bullets([p("baz"), bullets([p("boo")])])])])]),
        ("10) foo\n    - bar", [ordered(10, [p("foo"), bullets([p("bar")])])]),
        ("- - foo", [bullets([bullets([p("foo")])])]),
        ("1. - 2. foo", [ordered(1, [bullets([ordered(2, [p("foo")])])])]),
        ("-\n  foo\n-\n  ```\n  bar\n  ```", [bullets([p("foo")], [code("bar")])]),
        ("- foo\n-\n- bar", [bullets([p("foo")], [], [p("bar")])]),
        ("1. foo\n2.\n3. bar", [ordered(1, [p("foo")], [], [p("bar")])]),
        ("*", [bullets([])]),
        ("foo\n*\n\nfoo\n1.", [p("foo\n*"), p("foo\n1.")]),
        (" 1.  A paragraph\n     with two lines.", [ordered(1, [p("A paragraph\nwith two lines.")])]),
    ]

    static let tables: [(String, [Block])] = [
        ("| foo | bar |\n| --- | --- |\n| baz | bim |", [table([.none, .none], ["foo", "bar"], [["baz", "bim"]])]),
        ("| abc | defghi |\n:-: | -----------:\nbar | baz", [table([.center, .right], ["abc", "defghi"], [["bar", "baz"]])]),
        ("| f\\|oo  |\n| ------ |\n| b `\\|` az |\n| b **\\|** im |", [table([.none], ["f|oo"], [["b `|` az"], ["b **|** im"]])]),
        ("| abc | def |\n| --- | --- |\n| bar | baz |\n> bar", [table([.none, .none], ["abc", "def"], [["bar", "baz"]]), quote(p("bar"))]),
        ("| abc | def |\n| --- | --- |\n| bar | baz |\nbar\n\nbar", [table([.none, .none], ["abc", "def"], [["bar", "baz"], ["bar", ""]]), p("bar")]),
        ("| abc | def |\n| --- |\n| bar |", [p("| abc | def |\n| --- |\n| bar |")]),
        ("| abc | def |\n| --- | --- |\n| bar |\n| bar | baz | boo |", [table([.none, .none], ["abc", "def"], [["bar", ""], ["bar", "baz"]])]),
        ("| abc | def |\n| --- | --- |", [table([.none, .none], ["abc", "def"], [])]),
    ]

    static let all = headings + rules + fences + quotes + lists + tables
}

@Suite(.timeLimit(.minutes(1))) struct MessageBlocksTests {
    @Test(arguments: Examples.all) func eachSpecExampleReadsAsItsBlocks(_ text: String, _ blocks: [Block]) {
        #expect(MessageBlocks.split(text) == blocks, "\(text.debugDescription)")
    }

    @Test func aReplyWithAHeadingAParagraphASwiftFenceAndListsReadsAsThem() throws {
        #expect(MessageBlocks.split(try Replies.text("debounce")) == Replies.debounce)
    }

    @Test func aReplyWithATableARuleAndAListFromThreeReadsAsThem() throws {
        #expect(MessageBlocks.split(try Replies.text("merge-or-rebase")) == Replies.mergeOrRebase)
    }

    @Test func aReplyCutInsideItsFenceEndsInCodeHoldingWhatCame() throws {
        let reply = try Replies.text("debounce")
        let cut = try #require(reply.range(of: "        self.queue = queue"))
        let came = String(reply[..<cut.upperBound])
        let held = try #require(Replies.debounceCode.range(of: "        self.queue = queue"))
        #expect(MessageBlocks.split(came) == Replies.debounceOpening + [code("swift", String(Replies.debounceCode[..<held.upperBound]))])
    }

    @Test func htmlIsTextWhereverItStands() {
        #expect(MessageBlocks.split("<script>alert(1)</script>") == [p("<script>alert(1)</script>")])
        #expect(MessageBlocks.split("<div>\n*hi*\n</div>") == [p("<div>\n*hi*\n</div>")])
        #expect(MessageBlocks.split("<!-- note -->\n\n<img src=x onerror=alert(1)>") == [p("<!-- note -->"), p("<img src=x onerror=alert(1)>")])
        #expect(MessageBlocks.split("# <b>x</b>\n- <i>y</i>\n> <u>z</u>") == [h(1, "<b>x</b>"), bullets([p("<i>y</i>")]), quote(p("<u>z</u>"))])
        #expect(MessageBlocks.split("| <a> |\n| - |\n| </a> |") == [table([.none], ["<a>"], [["</a>"]])])
    }

    @Test func anImageAndALinkAreTheirTextAsWritten() {
        #expect(MessageBlocks.split("![a diagram](https://example.com/d.png)") == [p("![a diagram](https://example.com/d.png)")])
        #expect(MessageBlocks.split("See [this](javascript:alert(1)) and <https://x.dev>.") == [p("See [this](javascript:alert(1)) and <https://x.dev>.")])
    }

    @Test func eachBlockKeepsItsInlineMarkdownWhole() {
        let inline = "Use **bold**, `a | b`, [a link](https://x.dev \"t\") and _this_\\*."
        #expect(MessageBlocks.split(inline) == [p(inline)])
        #expect(MessageBlocks.split("## **Bold** `code`") == [h(2, "**Bold** `code`")])
        #expect(MessageBlocks.split("| *a* | `b` |\n|---|---|\n| [c](d) | **e** |") == [table([.none, .none], ["*a*", "`b`"], [["[c](d)", "**e**"]])])
    }

    @Test func aCodeBlocksTextIsItsLinesExactly() {
        let lines = "  <script>\n\ttab\ttab  \n``\n~~~\n\n# not a heading\n- not a list"
        #expect(MessageBlocks.split("```js\n\(lines)\n```\nafter") == [code("js", lines), p("after")])
        #expect(MessageBlocks.split("```\r\na  \r\n\tb\r\n```\r\n") == [code("a  \n\tb")])
    }

    @Test func aTableAlignsItsColumnsAndPadsItsRaggedRows() {
        #expect(MessageBlocks.split("| a | b | c | d |\n|:---:|:--|--:|---|\n| 1 |\n| 1 | 2 | 3 | 4 | 5 |") == [
            table([.center, .left, .right, .none], ["a", "b", "c", "d"], [["1", "", "", ""], ["1", "2", "3", "4"]]),
        ])
    }

    @Test func anOrderedListKeepsItsStartAndNestsABulletList() {
        #expect(MessageBlocks.split("3) one\n   - inner\n   - more\n4) two") == [ordered(3, [p("one"), bullets([p("inner")], [p("more")])], [p("two")])])
    }

    /// What this first build leaves out reads as paragraph text, never as a block it is not.
    @Test func whatIsLeftOutIsText() {
        #expect(MessageBlocks.split("    let indented = 1\n    code()") == [p("let indented = 1\ncode()")])
        #expect(MessageBlocks.split("Title\n=====") == [p("Title\n=====")])
        #expect(MessageBlocks.split("Title\n-----") == [p("Title"), .rule])
        #expect(MessageBlocks.split("> quoted\nlazy") == [quote(p("quoted")), p("lazy")])
        #expect(MessageBlocks.split("text\n| a |\n| - |") == [p("text\n| a |\n| - |")])
        #expect(MessageBlocks.split("[ref]: https://x.dev\n\n$$x$$") == [p("[ref]: https://x.dev"), p("$$x$$")])
    }

    @Test func blankTextHasNoBlocks() {
        #expect(MessageBlocks.split("").isEmpty)
        #expect(MessageBlocks.split(" \n\t\n\r\n").isEmpty)
    }
}

/// Text streams in: what has settled stays as the whole text reads it, and the rest settles as it
/// comes, so the view lays a settled block out once.
@Suite(.timeLimit(.minutes(1))) struct MessageBlocksStreamTests {
    static func texts() throws -> [String] {
        try Replies.names.map(Replies.text) + Examples.all.map(\.0) + ["Some text\n# Title\nmore", "| a |\n| - |\n| 1 |\n---x\n\nend"]
    }

    static func prefixes(of text: String) -> [String] {
        let scalars = text.unicodeScalars
        return Array(scalars.indices).map { String(scalars[..<$0]) } + [text]
    }

    static func pieces(of text: String, by size: Int) -> [String] {
        let scalars = Array(text.unicodeScalars)
        return stride(from: 0, to: scalars.count, by: size).map { String(String.UnicodeScalarView(scalars[$0 ..< min($0 + size, scalars.count)])) }
    }

    @Test func everyPrefixSettlesOnlyWhatTheWholeTextReads() throws {
        var faults: [String] = []
        for text in try Self.texts() {
            let whole = MessageBlocks.split(text)
            for prefix in Self.prefixes(of: text) {
                let read = MessageBlocks(prefix)
                if read.blocks != MessageBlocks.split(prefix) { faults.append("reads otherwise whole: \(prefix.debugDescription)") }
                if !whole.starts(with: read.settled) { faults.append("settled what the whole text reads otherwise: \(prefix.debugDescription)") }
                if prefix.utf8.last == UInt8(ascii: "\n"), read.open.count > 1 { faults.append("left more than its last block open at a line's end: \(prefix.debugDescription)") }
            }
        }
        #expect(faults.isEmpty, "\(faults.prefix(5))")
    }

    @Test(arguments: [1, 2, 3, 5, 8, 64, 1000]) func appendedInPiecesItReadsAsTheWholeText(_ size: Int) throws {
        var faults: [String] = []
        for text in try Self.texts() {
            let whole = MessageBlocks.split(text)
            var stream = MessageBlocks()
            for piece in Self.pieces(of: text, by: size) {
                let before = stream.settled
                stream.append(piece)
                if !stream.settled.starts(with: before) { faults.append("unsettled a block: \(text.debugDescription)") }
                if !whole.starts(with: stream.settled) { faults.append("settled what the whole text reads otherwise: \(text.debugDescription)") }
            }
            if stream.blocks != whole { faults.append("ended otherwise: \(text.debugDescription)") }
        }
        #expect(faults.isEmpty, "\(faults.prefix(5))")
    }

    @Test func anUnclosedFenceIsCodeFromItsFirstLine() throws {
        let text = "Intro.\n\n```swift\nlet a = 1\n\nlet b = 2\n```"
        let opened = try #require(text.range(of: "```")).upperBound
        for prefix in Self.prefixes(of: text) where prefix.endIndex >= opened {
            let last = try #require(MessageBlocks.split(prefix).last)
            guard case .code = last else {
                Issue.record("\(prefix.debugDescription) ends in \(last)")
                continue
            }
        }
        #expect(MessageBlocks.split(String(text.dropLast(4))) == [p("Intro."), code("swift", "let a = 1\n\nlet b = 2")])
    }

    @Test func aBlockSettlesOnceAFinishedLineStartsAnother() {
        var stream = MessageBlocks("Some text\n#")
        #expect(stream.settled.isEmpty)
        #expect(stream.open == [p("Some text"), h(1, "")])
        stream.append("x")
        #expect(stream.settled.isEmpty)
        #expect(stream.open == [p("Some text\n#x")])
        stream.append("\n# Title\n")
        #expect(stream.settled == [p("Some text\n#x")])
        #expect(stream.open == [h(1, "Title")])

        var rows = MessageBlocks("| a |\n| - |\n| 1 |\n---")
        #expect(rows.open == [table([.none], ["a"], [["1"]]), .rule])
        rows.append("x")
        #expect(rows.open == [table([.none], ["a"], [["1"], ["---x"]])])
        rows.append("\n\nend")
        #expect(rows.settled == [table([.none], ["a"], [["1"], ["---x"]])])
        #expect(rows.open == [p("end")])
    }
}

/// The text is the agent's, so no text makes the split recurse without bound or take more than
/// time linear in it: containers nest at most `MessageBlocks.depth` deep, deeper ones read as text
/// at the deepest, and a table has at most `MessageBlocks.columns` columns.
@Suite(.timeLimit(.minutes(1))) struct MessageBlocksBoundsTests {
    static let codePoints = 65_536

    struct Shape: Sendable, CustomTestStringConvertible {
        let testDescription: String
        let head: String
        let unit: String
        let blocks: Int
        let depth: Int

        init(_ name: String, head: String = "", _ unit: String, blocks: Int, depth: Int) {
            testDescription = name
            self.head = head
            self.unit = unit
            self.blocks = blocks
            self.depth = depth
        }

        var text: String {
            String(String(head + String(repeating: unit, count: MessageBlocksBoundsTests.codePoints / unit.unicodeScalars.count + 1)).unicodeScalars.prefix(MessageBlocksBoundsTests.codePoints))
        }
    }

    static let wideHeader = "|" + String(repeating: "a|", count: MessageBlocks.columns) + "\n|" + String(repeating: "-|", count: MessageBlocks.columns) + "\n"

    static let shapes = [
        Shape("one line of words", "word ", blocks: 1, depth: 0),
        Shape("one line of quote markers", ">", blocks: 1, depth: 8),
        Shape("one line of ordered markers", "1. ", blocks: 1, depth: 8),
        Shape("one line of bullet markers", "- ", blocks: 1, depth: 0),
        Shape("one line of hashes", "#", blocks: 1, depth: 0),
        Shape("one line of escaped pipes", "\\|", blocks: 1, depth: 0),
        Shape("only spaces and tabs", " \t", blocks: 0, depth: 0),
        Shape("quote lines", ">\n", blocks: 1, depth: 1),
        Shape("deep quote lines", ">>>>>>>>>>>>>>>\n", blocks: 1, depth: 8),
        Shape("quotes and lists in turn", "> - > - > - >-x\n", blocks: 1, depth: 7),
        Shape("bullet items", "- a\n", blocks: 1, depth: 1),
        Shape("items apart", "- a\n\n\n\n", blocks: 1, depth: 1),
        Shape("an item's blank lines", head: "- a\n", "\n\n\n  b\n", blocks: 1, depth: 1),
        Shape("items indented deeper", (0 ..< 9).map { String(repeating: "  ", count: $0) + "- x\n" }.joined(), blocks: 1, depth: 8),
        Shape("fences", "```\n", blocks: 8_192, depth: 0),
        Shape("an unclosed fence", head: "```\n", "a\n", blocks: 1, depth: 0),
        Shape("headings", "#\n", blocks: 32_768, depth: 0),
        Shape("pipes", "|\n", blocks: 1, depth: 0),
        Shape("table rows", head: "| a | b |\n|---|---|\n", "| 1 | 2 |\n", blocks: 1, depth: 0),
        Shape("the widest table's short rows", head: wideHeader, "|\n", blocks: 1, depth: 0),
        Shape("a header row of pipes", head: String(repeating: "|", count: 32_000) + "\n" + String(repeating: "-|", count: 15_000) + "\n", "|\n", blocks: 1, depth: 0),
    ]

    static func depth(_ blocks: [Block]) -> Int {
        blocks.map { block in
            switch block {
            case let .quote(inner): 1 + depth(inner)
            case let .list(_, items): 1 + (items.map(depth).max() ?? 0)
            default: 0
            }
        }.max() ?? 0
    }

    /// The blocks inside `levels` containers, each the only block of its level and, for a list,
    /// its only item.
    static func inside(_ blocks: [Block], levels: Int) -> [Block]? {
        var blocks = blocks
        for _ in 0 ..< levels {
            switch blocks.first {
            case let .quote(inner) where blocks.count == 1: blocks = inner
            case let .list(_, items) where blocks.count == 1 && items.count == 1: blocks = items[0]
            default: return nil
            }
        }
        return blocks
    }

    @Test(arguments: shapes) func aTextOfTheLongestSplitsInBoundedTime(_ shape: Shape) throws {
        let text = shape.text
        try #require(text.unicodeScalars.count == Self.codePoints)
        let blocks = MessageBlocks.split(text)
        #expect(blocks.count == shape.blocks)
        #expect(Self.depth(blocks) == shape.depth)
    }

    @Test func tenThousandQuoteMarkersAreDrawnAtTheEighthLevel() {
        #expect(Self.inside(MessageBlocks.split(String(repeating: ">", count: 10_000)), levels: 8) == [p(String(repeating: ">", count: 9_992))])
        #expect(Self.inside(MessageBlocks.split(String(repeating: "> ", count: 10_000) + "x"), levels: 8) == [p(String(repeating: "> ", count: 9_992) + "x")])
    }

    @Test func tenThousandNestedListMarkersAreDrawnAtTheEighthLevel() {
        #expect(Self.inside(MessageBlocks.split(String(repeating: "- ", count: 10_000) + "x"), levels: 8) == [p(String(repeating: "- ", count: 9_992) + "x")])
        #expect(Self.inside(MessageBlocks.split(String(repeating: "1. ", count: 10_000) + "x"), levels: 8) == [p(String(repeating: "1. ", count: 9_992) + "x")])
    }

    @Test func aLineOfTheLongestIsOneParagraph() {
        let line = String(repeating: "é", count: Self.codePoints)
        #expect(MessageBlocks.split(line) == [p(line)])
    }

    @Test func aTableWiderThanTheBoundIsText() {
        let cells = MessageBlocks.columns + 1
        let text = "|" + String(repeating: "a|", count: cells) + "\n|" + String(repeating: "-|", count: cells) + "\n| 1 |"
        #expect(MessageBlocks.split(text) == [p(text)])
        #expect(MessageBlocks.split(Self.wideHeader + "| 1 |") == [table(Array(repeating: .none, count: MessageBlocks.columns), Array(repeating: "a", count: MessageBlocks.columns), [["1"] + Array(repeating: "", count: MessageBlocks.columns - 1)])])
    }
}
