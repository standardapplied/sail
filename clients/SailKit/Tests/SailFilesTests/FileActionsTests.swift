import Foundation
@testable import SailFiles
import SailTerminalWire
import Testing

/// What a person changes of a workspace's names: each action one frame, answered done or refused in
/// a true sentence; a folder never sent into itself; nothing answered that was not asked.
@Suite struct FileActionsTests {
    static let src: FileName = "/home/dev/workspace/src"

    static func sent(_ asked: FileActions.Asked) throws -> Frame {
        guard case let .send(frame, _) = asked else { throw Unsent(asked: asked) }
        return frame
    }

    struct Unsent: Error {
        let asked: FileActions.Asked
    }

    static func id(_ frame: Frame) -> Int32? {
        switch frame {
        case let .filesCreate(id, _, _), let .filesMove(id, _, _), let .filesRemove(id, _): id
        default: nil
        }
    }

    /// F11: each action is its one frame, numbered apart from the tree's ids; done answers it.
    @Test func eachActionIsOneFrameAnsweredDone() throws {
        var actions = FileActions(workspace: "dock")
        let made = try Self.sent(actions.ask(.create(Self.src.appending("notes.md"), folder: false)))
        let folder = try Self.sent(actions.ask(.create(Self.src.appending("docs"), folder: true)))
        let moved = try Self.sent(actions.ask(.move(from: Self.src.appending("a.ts"), to: "/home/dev/workspace/a.ts")))
        let removed = try Self.sent(actions.ask(.remove(Self.src)))
        let first = try #require(Self.id(made))
        #expect(first >= 1 << 28, "an action's id can meet the tree's")
        #expect(made == .filesCreate(id: first, path: "/home/dev/workspace/src/notes.md", folder: false))
        #expect(folder == .filesCreate(id: first + 1, path: "/home/dev/workspace/src/docs", folder: true))
        #expect(moved == .filesMove(id: first + 2, from: "/home/dev/workspace/src/a.ts", to: "/home/dev/workspace/a.ts"))
        #expect(removed == .filesRemove(id: first + 3, path: Self.src))
        #expect(actions.underWay)
        #expect(actions.receive(.filesDone(id: first + 2)) == .done(.move(from: "/home/dev/workspace/src/a.ts", to: "/home/dev/workspace/a.ts"), id: first + 2))
        #expect(actions.receive(.filesDone(id: first + 2)) == nil, "an action was answered twice")
        #expect(actions.receive(.filesDone(id: 1)) == nil && actions.receive(.filesTick) == nil, "the tree's frame was taken")
    }

    /// F7: every refusal, a taken name's and a folder partly removed's too, is said in the host's
    /// own words, never in words the app made.
    @Test(arguments: [
        (FileActions.Action.create("/home/dev/workspace/src/notes.md", folder: false), FileRefusal.exists),
        (.move(from: "/home/dev/workspace/a.md", to: "/home/dev/workspace/src/notes.md"), .exists),
        (.remove("/home/dev/workspace/src"), .partlyRemoved),
        (.remove("/home/dev/workspace/src"), .notPermitted),
        (.create("/home/dev/workspace/src/a", folder: true), .noSpace),
        (.move(from: "/home/dev/workspace/a", to: "/home/dev/workspace/b"), .notFound),
    ] as [(FileActions.Action, FileRefusal)])
    func everyRefusalIsInTheHostsWords(_ action: FileActions.Action, _ kind: FileRefusal) throws {
        var actions = FileActions(workspace: "dock")
        let id = try #require(Self.id(Self.sent(actions.ask(action))))
        #expect(actions.receive(.filesRefused(id: id, kind: .known(kind), message: "The host's words.")) == .refused(action, "The host's words.", id: id))
    }

    /// Each ask is answered by its own request's id: the same action asked twice is two requests.
    @Test func theSameActionAskedTwiceIsAnsweredByEachID() throws {
        var actions = FileActions(workspace: "dock")
        let rename = FileActions.Action.move(from: "/home/dev/workspace/a.md", to: "/home/dev/workspace/b.md")
        guard case let .send(once, first) = actions.ask(rename), case let .send(again, second) = actions.ask(rename) else {
            Issue.record("a rename was not sent")
            return
        }
        #expect(first != second && Self.id(once) == first && Self.id(again) == second)
        #expect(actions.receive(.filesDone(id: second)) == .done(rename, id: second))
        #expect(actions.receive(.filesRefused(id: first, kind: .known(.exists), message: "b.md is already there.")) == .refused(rename, "b.md is already there.", id: first))
    }

    /// M22: a folder moved into itself or under itself is refused before any frame, and nothing waits.
    @Test func aFolderIntoItselfSendsNothing() {
        var actions = FileActions(workspace: "dock")
        #expect(actions.ask(.move(from: Self.src, to: Self.src.appending("lib").appending("src"))) == .refused("A folder cannot be moved into itself."))
        #expect(actions.ask(.move(from: Self.src, to: Self.src)) == .refused(FileActions.intoItself))
        #expect(!actions.underWay)
        #expect(actions.ask(.move(from: Self.src, to: "/home/dev/workspace/srcs")) != .refused(FileActions.intoItself), "a sibling sharing the name's start was taken for its child")
    }

    /// A connection that ends answers every action under way, in order, in one true sentence.
    @Test func aDropEndsEveryActionUnderWay() throws {
        var actions = FileActions(workspace: "dock")
        _ = try Self.sent(actions.ask(.create(Self.src, folder: true)))
        _ = try Self.sent(actions.ask(.remove("/home/dev/workspace/old")))
        let said = "The connection to dock dropped before it answered; the tree shows what is there."
        #expect(actions.disconnected() == [.refused(.create(Self.src, folder: true), said, id: 1 << 28), .refused(.remove("/home/dev/workspace/old"), said, id: (1 << 28) + 1)])
        #expect(!actions.underWay && actions.disconnected().isEmpty)
    }

    /// A typed name is one entry's: no slash, not `.` or `..`, not empty.
    @Test func aNameIsOneEntrys() {
        #expect(["notes.md", ".env", "a b", "..x"].allSatisfy(FileActions.isName))
        #expect(!["", ".", "..", "a/b", "/"].contains(where: FileActions.isName))
    }

    /// M45: a path pasted at a prompt reads back as itself: plain as it is; a space, a quote or any
    /// character a shell reads as its own in single quotes, a quote closed and escaped; a control
    /// character (which a terminal's paste would turn into a space, or a newline, which would end
    /// the line) and a name not UTF-8 in ANSI-C quotes, written `\xHH`.
    @Test(arguments: [
        ("/home/dev/workspace/src/tide.ts", "/home/dev/workspace/src/tide.ts"),
        ("/home/dev/workspace/my notes.md", "'/home/dev/workspace/my notes.md'"),
        ("/home/dev/workspace/it's.md", "'/home/dev/workspace/it'\\''s.md'"),
        ("/home/dev/workspace/-rf", "/home/dev/workspace/-rf"),
        ("/home/dev/workspace/$HOME", "'/home/dev/workspace/$HOME'"),
        ("/home/dev/workspace/a;b", "'/home/dev/workspace/a;b'"),
        ("/home/dev/workspace/a&b", "'/home/dev/workspace/a&b'"),
        ("/home/dev/workspace/a|b", "'/home/dev/workspace/a|b'"),
        ("/home/dev/workspace/*.ts", "'/home/dev/workspace/*.ts'"),
        ("/home/dev/workspace/a?", "'/home/dev/workspace/a?'"),
        ("/home/dev/workspace/(a)", "'/home/dev/workspace/(a)'"),
        ("/home/dev/workspace/a<b", "'/home/dev/workspace/a<b'"),
        ("/home/dev/workspace/x>y", "'/home/dev/workspace/x>y'"),
        ("/home/dev/workspace/a!", "'/home/dev/workspace/a!'"),
        ("/home/dev/workspace/~a", "'/home/dev/workspace/~a'"),
        ("/home/dev/workspace/a`b`", "'/home/dev/workspace/a`b`'"),
        ("/home/dev/workspace/a\\b", "'/home/dev/workspace/a\\b'"),
        ("/home/dev/workspace/two\nlines", "$'/home/dev/workspace/two\\x0alines'"),
        ("/home/dev/workspace/a\tb", "$'/home/dev/workspace/a\\x09b'"),
        ("/home/dev/workspace/a\u{1B}b", "$'/home/dev/workspace/a\\x1bb'"),
        ("/home/dev/workspace/a\u{7F}b", "$'/home/dev/workspace/a\\x7fb'"),
        ("/home/dev/workspace/a\u{03}b", "$'/home/dev/workspace/a\\x03b'"),
        ("/home/dev/workspace/it's\u{1B}", "$'/home/dev/workspace/it\\x27s\\x1b'"),
    ])
    func aPathIsShellQuoted(_ path: String, _ quoted: String) {
        #expect(FileName(path).shellQuoted == quoted)
        #expect(!FileName(path).shellQuoted.hasPrefix("-"), "a path is read as an option")
    }

    @Test func aPathNotUTF8IsQuotedByteForByte() {
        #expect(FileName(Array("/home/dev/a".utf8) + [0xFF, 0x27, 0x5C]).shellQuoted == "$'/home/dev/a\\xff\\x27\\x5c'")
    }

    @Test func theLeafIsTheLastName() {
        #expect(Self.src.leaf == "src" && FileName("/home/dev/workspace/src/a.ts").leaf.shown == "a.ts")
    }
}
