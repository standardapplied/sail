import SailAPI
@testable import SailFiles
import SailTerminalWire
import Testing

/// The panel's one quiet line: only when the tree is not the workspace's live truth, in the words the
/// design gives, gone once it no longer applies.
@Suite struct FilesLineTests {
    static let root = FileTree.root

    static func listed(_ entries: [FileEntry], at now: Double = 0) throws -> FileTree {
        var tree = FileTree()
        let id = try #require(FileTreeTests.id(listing: root, in: tree.admitted()))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v1", entries: entries, more: false, truncated: false)), at: now)
        return tree
    }

    static func line(_ tree: FileTree, _ phase: FilesSession.Phase, at now: Double) -> FilesLine? {
        FilesLine(tree: tree, phase: phase, workspace: "dock", at: now) { "\(Int($0))s" }
    }

    @Test func aLiveTreeSaysNothing() throws {
        let tree = try Self.listed([FileTreeTests.file("a.txt")])
        #expect(Self.line(tree, .connected, at: 1) == nil)
    }

    @Test func listingIsSaidAfter300MillisecondsAndSlowAfterFiveSeconds() {
        var tree = FileTree(openedAt: 10)
        #expect(Self.line(tree, .connecting, at: 10.29) == nil, "said before 300 ms")
        #expect(Self.line(tree, .connecting, at: 10.3)?.words == "Listing ~/workspace", "a first connect is not said as listing")
        _ = tree.admitted()
        #expect(Self.line(tree, .connected, at: 10.3) == FilesLine(words: "Listing ~/workspace", mark: .working))
        #expect(Self.line(tree, .connected, at: 14.9)?.words == "Listing ~/workspace")
        #expect(Self.line(tree, .connected, at: 15.9)?.words == "Listing ~/workspace", "slow before 6 s")
        #expect(Self.line(tree, .connected, at: 16.2)?.words == "Still listing ~/workspace (6 s). The terminal still works.")
    }

    @Test func aTreeNotLiveSaysSinceWhenAndIsDimmed() throws {
        var tree = try Self.listed([FileTreeTests.file("a.txt")], at: 100)
        let quiet = try #require(Self.line(tree, .connected, at: 102.5))
        #expect(quiet == FilesLine(words: "Not live since 100s.", mark: .waiting, dims: true), "M1: said reconnecting while still connected: \(quiet)")
        tree.disconnected()
        #expect(Self.line(tree, .reconnecting("the network is down"), at: 103)?.words == "Not live since 100s. Reconnecting.")
        #expect(Self.line(tree, .connecting, at: 103)?.words == "Not live since 100s. Reconnecting.")
    }

    @Test func unreachableOffersTryAgainAndStoppedOffersStartAsThePersonsOwnAct() throws {
        let tree = try Self.listed([])
        #expect(Self.line(tree, .unreachable("refused"), at: 1) == FilesLine(words: "Can't reach the files.", mark: .bad, action: .tryAgain, dims: true))
        #expect(Self.line(FileTree(), .stopped("dock is stopped."), at: 1) == FilesLine(words: "dock is stopped.", mark: .quiet, action: .start, dims: true))
        #expect(Self.line(tree, .stopped("Workspace dock of harbor was stopped by ana."), at: 1)?.words == "Workspace dock of harbor was stopped by ana.", "M35: not the stop's own record")
        #expect(Self.line(FileTree(), .stopped(""), at: 1)?.words == "dock is stopped.")
    }

    /// Two states at once say the more severe: stopped, then can't reach, then not live, then listing.
    @Test func theMoreSevereStateIsSaid() throws {
        var tree = try Self.listed([FileTreeTests.file("a.txt")])
        tree.disconnected()
        #expect(Self.line(tree, .unreachable("refused"), at: 30)?.words == "Can't reach the files.", "not live outranked can't reach")
        #expect(Self.line(tree, .stopped("dock is stopped."), at: 30)?.words == "dock is stopped.")
        #expect(Self.line(tree, .reconnecting("down"), at: 30)?.mark == .waiting)
    }

    /// M61: past the watch's 1,024 folders the line says which it follows.
    @Test func pastTheWatchCapTheLineSaysWhatIsFollowed() throws {
        var tree = try Self.listed((0 ... FileTree.watchCap).map { FileTreeTests.folder("d\($0)") })
        for index in 0 ... FileTree.watchCap {
            _ = tree.expand(Self.root + "/d\(index)")
        }
        #expect(Self.line(tree, .connected, at: 0) == FilesLine(words: "Following the 1,024 most recent folders live.", mark: .quiet))
    }

    /// Calm: the panel wakes only when time alone changes what it shows: the first listing's 300 ms
    /// and each second of its wait from 6 s, 2 s after the last tick, a cue's 10 s; never on a beat.
    @Test func timeAloneChangesTheScreenOnlyAtTheseMoments() throws {
        var waiting = FileTree(openedAt: 10)
        #expect(FilesLine.nextChange(tree: waiting, phase: .connecting, at: 10) == 10.3)
        #expect(FilesLine.nextChange(tree: waiting, phase: .connecting, at: 10.3) == 16)
        #expect(FilesLine.nextChange(tree: waiting, phase: .connecting, at: 16.2) == 17)
        _ = waiting.admitted()
        #expect(FilesLine.nextChange(tree: waiting, phase: .stopped("dock is stopped."), at: 12) == nil, "a stopped workspace's line waits on nothing")
        var tree = try Self.listed([FileTreeTests.file("a.txt")], at: 100)
        #expect(FilesLine.nextChange(tree: tree, phase: .connected, at: 100.5) == 100 + FileTree.silence, "no wake for the silence")
        let id = try #require(FileTreeTests.id(listing: Self.root, in: tree.receive(.filesChanged(paths: [Self.root]), at: 101)))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v2", entries: [FileTreeTests.file("a.txt"), FileTreeTests.file("b.txt")], more: false, truncated: false)), at: 101)
        _ = tree.receive(.filesTick, at: 110.5)
        #expect(FilesLine.nextChange(tree: tree, phase: .connected, at: 110.6) == 111, "no wake for the cue's end")
        #expect(tree.newPaths(at: 110.6) == [Self.root + "/b.txt"] && tree.newPaths(at: 111.01).isEmpty)
    }

    /// The rows change only when the tree does: what a beat redraws nothing for.
    @Test func theTreesRevisionMovesOnlyWithItsRows() throws {
        var tree = try Self.listed([FileTreeTests.folder("src")])
        let listed = tree.revision
        _ = tree.receive(.filesTick, at: 1)
        #expect(tree.revision == listed, "a tick moved the revision")
        _ = tree.expand(Self.root + "/src")
        #expect(tree.revision != listed, "an expand did not move it")
    }

    /// One connection over a cap says so in the words given, with Try Again, the tree dimmed.
    @Test func overACapItSaysSoWithTryAgain() throws {
        let tree = try Self.listed([FileTreeTests.file("a.txt")])
        #expect(Self.line(tree, .limited("You have 16 files connections open; close one to open another."), at: 1)
            == FilesLine(words: "You have 16 files connections open; close one to open another.", mark: .bad, action: .tryAgain, dims: true))
    }

    @Test func anEmptyWorkspaceSaysSo() throws {
        #expect(Self.line(try Self.listed([]), .connected, at: 0) == FilesLine(words: "~/workspace is empty.", mark: .none))
    }

    @Test func goneRefusedAndARefusedRootAreSaidInTheirOwnWords() throws {
        #expect(Self.line(FileTree(), .gone("Workspace dock of harbor no longer exists."), at: 0) == FilesLine(words: "Workspace dock of harbor no longer exists.", mark: .bad, dims: true))
        #expect(Self.line(FileTree(), .refused(["You may not read dock.", "Ask its owner."]), at: 0) == FilesLine(words: "You may not read dock. Ask its owner.", mark: .bad, dims: true), "a refused tree is not dimmed")
        var tree = FileTree()
        let id = try #require(FileTreeTests.id(listing: Self.root, in: tree.admitted()))
        _ = tree.receive(.filesRefused(id: id, kind: .known(.notPermitted), message: "The workspace's user may not open ~/workspace."), at: 0)
        #expect(Self.line(tree, .connected, at: 0)?.words == "The workspace's user may not open ~/workspace.")
        #expect(tree.rows.isEmpty, "the root's refusal is said twice")
    }
}
