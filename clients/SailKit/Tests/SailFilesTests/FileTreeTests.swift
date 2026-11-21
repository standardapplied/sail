import Foundation
@testable import SailFiles
import SailTerminalWire
import Testing

/// A path under folder `folder`, as the tests spell one: `root + "/src"`.
func + (folder: FileName, rest: String) -> FileName {
    FileName(folder.bytes + Array(rest.utf8))
}

/// The tree of a workspace's files: lazy folders, each a fresh listing replacing the one held; the
/// watch set is what is shown, at the versions held; a reconnect sends it again and lists every shown
/// folder again; a tree with no tick for 2 s is not live.
@Suite struct FileTreeTests {
    static let root = FileTree.root

    static func file(_ name: String) -> FileEntry {
        FileEntry(name: FileName(name), kind: .known(.file), target: .known(.none), size: 1, mtimeNanos: 0, mode: 0o644)
    }

    static func folder(_ name: String) -> FileEntry {
        FileEntry(name: FileName(name), kind: .known(.folder), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o755)
    }

    static func link(_ name: String, to target: FileKind) -> FileEntry {
        FileEntry(name: FileName(name), kind: .known(.link), target: .known(target), size: 0, mtimeNanos: 0, mode: 0o777)
    }

    /// The id of the listing `frames` ask for `path`.
    static func id(listing path: FileName, in frames: [Frame]) -> Int32? {
        frames.lazy.compactMap { frame -> Int32? in
            guard case let .filesList(id, listed) = frame, listed == path else { return nil }
            return id
        }.first
    }

    static func watched(_ frames: [Frame]) -> [WatchedPath]? {
        frames.lazy.compactMap { frame -> [WatchedPath]? in
            guard case let .filesWatch(paths) = frame else { return nil }
            return paths
        }.last
    }

    /// A tree admitted at time 0 with the root listed as `entries` at version `v1`.
    static func listed(_ entries: [FileEntry]) throws -> FileTree {
        var tree = FileTree()
        let id = try #require(Self.id(listing: root, in: tree.admitted()))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v1", entries: entries, more: false, truncated: false)), at: 0)
        return tree
    }

    @Test func admittedItWatchesWhatItHoldsAndListsTheRoot() throws {
        var tree = FileTree()
        let frames = tree.admitted()
        #expect(Self.watched(frames) == [] && Self.id(listing: Self.root, in: frames) != nil, "\(frames)")
        #expect(tree.rows.isEmpty && !tree.rootKnown)
    }

    @Test func aListingInPagesIsHeldWholeFoldersFirstAsFinderSorts() throws {
        var tree = FileTree()
        let id = try #require(Self.id(listing: Self.root, in: tree.admitted()))
        #expect(tree.receive(.filesListing(FileListing(id: id, version: "v1", entries: [Self.file("b10.txt"), Self.folder("src")], more: true, truncated: false)), at: 0).isEmpty)
        let done = tree.receive(.filesListing(FileListing(id: id, version: "v1", entries: [Self.file("b9.txt"), Self.folder("Docs"), Self.file("a.txt")], more: false, truncated: false)), at: 0)
        #expect(tree.rows.map(\.name) == ["Docs", "src", "a.txt", "b9.txt", "b10.txt"], "\(tree.rows.map(\.name))")
        #expect(Self.watched(done) == [WatchedPath(path: Self.root, version: "v1")], "the root is not watched at the version it was listed at")
    }

    @Test func aChangeIsAFreshListingThatReplacesTheOneHeld() throws {
        var tree = try Self.listed([Self.file("a.txt"), Self.file("b.txt")])
        let again = tree.receive(.filesChanged(paths: [Self.root, "/home/dev/workspace/unwatched"]), at: 1)
        let id = try #require(Self.id(listing: Self.root, in: again))
        #expect(again.count == 1, "an unwatched path was listed: \(again)")
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v2", entries: [Self.file("a.txt"), Self.file("c.txt")], more: false, truncated: false)), at: 1)
        #expect(tree.rows.map(\.path) == [Self.root + "/a.txt", Self.root + "/c.txt"], "the listing was not replaced whole: \(tree.rows.map(\.name))")
        #expect(tree.watchSet == [WatchedPath(path: Self.root, version: "v2")])
    }

    @Test func aChangeWhileAListingIsUnderWayListsItOnceMoreAfter() throws {
        var tree = try Self.listed([Self.file("a.txt")])
        let first = try #require(Self.id(listing: Self.root, in: tree.receive(.filesChanged(paths: [Self.root]), at: 1)))
        #expect(tree.receive(.filesChanged(paths: [Self.root]), at: 1).isEmpty, "a second listing was sent while one is under way")
        let after = tree.receive(.filesListing(FileListing(id: first, version: "v2", entries: [], more: false, truncated: false)), at: 1)
        #expect(Self.id(listing: Self.root, in: after) != nil, "the change during the listing was lost: \(after)")
    }

    @Test func expandingListsAndWatchesCollapsingDropsItAndWhatIsExpandedUnderIt() throws {
        var tree = try Self.listed([Self.folder("src"), Self.file("a.txt")])
        let src = Self.root + "/src", deep = src + "/deep"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        let watched = tree.receive(.filesListing(FileListing(id: id, version: "s1", entries: [Self.folder("deep"), Self.file("main.ts")], more: false, truncated: false)), at: 0)
        #expect(Self.watched(watched)?.map(\.path) == [Self.root, src])
        #expect(tree.rows.map(\.depth) == [0, 1, 1, 0] && tree.rows[0].expanded, "\(tree.rows)")
        let deepID = try #require(Self.id(listing: deep, in: tree.expand(deep)))
        _ = tree.receive(.filesListing(FileListing(id: deepID, version: "d1", entries: [Self.file("x")], more: false, truncated: false)), at: 0)
        let collapsed = tree.collapse(src)
        #expect(Self.watched(collapsed)?.map(\.path) == [Self.root], "a collapsed folder, or one under it, is still watched: \(collapsed)")
        #expect(!tree.expanded.contains(deep) && tree.folders[deep] == nil && tree.rows.count == 2)
        #expect(tree.collapse(Self.root).isEmpty && tree.expanded.contains(Self.root), "the root collapsed")
    }

    @Test func aFolderGoneFromItsParentDropsItsChildrenAndItsWatch() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "s1", entries: [Self.file("main.ts")], more: false, truncated: false)), at: 0)
        let again = try #require(Self.id(listing: Self.root, in: tree.receive(.filesChanged(paths: [Self.root]), at: 1)))
        let after = tree.receive(.filesListing(FileListing(id: again, version: "v2", entries: [Self.file("README.md")], more: false, truncated: false)), at: 1)
        #expect(!tree.expanded.contains(src) && tree.folders[src] == nil, "the removed folder is still held")
        #expect(Self.watched(after)?.map(\.path) == [Self.root], "the removed folder is still watched")
    }

    @Test func aRefusedFolderSaysWhyInPlaceAndItsSiblingsStand() throws {
        var tree = try Self.listed([Self.folder("build"), Self.file("a.txt")])
        let build = Self.root + "/build"
        let id = try #require(Self.id(listing: build, in: tree.expand(build)))
        _ = tree.receive(.filesRefused(id: id, kind: .known(.notPermitted), message: "The workspace's user may not open build."), at: 0)
        #expect(tree.rows.map(\.name) == ["build", "The workspace's user may not open build.", "a.txt"] && tree.rows[1].kind == .note && tree.rows[1].depth == 1)
    }

    @Test func aReconnectSendsTheWatchSetAgainAndListsEveryShownFolderAgain() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "s1", entries: [], more: false, truncated: false)), at: 0)
        tree.disconnected()
        #expect(!tree.isLive(at: 0) && tree.rows.count == 1, "a dropped connection lost the tree or stayed live")
        let back = tree.admitted()
        #expect(Self.watched(back) == [WatchedPath(path: Self.root, version: "v1"), WatchedPath(path: src, version: "s1")], "the watch set was not sent again: \(back)")
        #expect(Self.id(listing: Self.root, in: back) != nil && Self.id(listing: src, in: back) != nil, "a shown folder was not listed again: \(back)")
    }

    @Test func notLiveAfterTwoSecondsWithoutATickLiveAgainOnTheNext() throws {
        var tree = try Self.listed([])
        #expect(tree.isLive(at: 2) && !tree.isLive(at: 2.01), "live past 2 s with no tick")
        _ = tree.receive(.filesTick, at: 5)
        #expect(tree.isLive(at: 6.5) && !tree.isLive(at: 7.5))
    }

    @Test func fiveThousandEntriesAndFiftyThousandSaySo() throws {
        let five = try Self.listed((0 ..< 5000).map { Self.file(String(format: "berth-%04d.json", $0)) })
        #expect(five.rows.count == 5000 && five.rows.first?.name == "berth-0000.json" && five.rows.last?.name == "berth-4999.json")
        var tree = FileTree()
        let id = try #require(Self.id(listing: Self.root, in: tree.admitted()))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v1", entries: (0 ..< 50000).map { Self.file("f\($0)") }, more: false, truncated: true)), at: 0)
        #expect(tree.rows.count == 50001 && tree.rows.last?.name == "This folder has more than 50,000 entries; Sail shows 50,000 of them.")
    }

    @Test func aLinkToAFolderExpandsAndABrokenLinkSaysSo() throws {
        let tree = try Self.listed([Self.link("lib", to: .folder), Self.link("loop", to: .loop), Self.link("gone", to: .missing), Self.link("notes", to: .file)])
        let kinds = Dictionary(uniqueKeysWithValues: tree.rows.map { ($0.name, $0.kind) })
        #expect(kinds == ["lib": .folder, "loop": .brokenLink, "gone": .brokenLink, "notes": .file], "\(kinds)")
    }

    @Test func anEntryThatAppearsThroughTheWatchIsNewForTenSecondsAndAFirstListingMarksNothing() throws {
        var tree = try Self.listed([Self.file("a.txt")])
        #expect(!tree.isNew(Self.root + "/a.txt", at: 0), "a first listing marked its entries new")
        let id = try #require(Self.id(listing: Self.root, in: tree.receive(.filesChanged(paths: [Self.root]), at: 3)))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v2", entries: [Self.file("a.txt"), Self.file("tide.ts")], more: false, truncated: false)), at: 3)
        #expect(tree.isNew(Self.root + "/tide.ts", at: 13) && !tree.isNew(Self.root + "/tide.ts", at: 13.01), "not new for exactly 10 s")
        #expect(!tree.isNew(Self.root + "/a.txt", at: 3), "an entry that was there already was marked new")
        tree.disconnected()
        let again = try #require(Self.id(listing: Self.root, in: tree.admitted()))
        _ = tree.receive(.filesListing(FileListing(id: again, version: "v3", entries: [Self.file("a.txt"), Self.file("tide.ts"), Self.file("b.txt")], more: false, truncated: false)), at: 4)
        #expect(tree.isNew(Self.root + "/b.txt", at: 4), "an entry that appeared while away was not marked new")
    }

    /// M61: the watch holds 1,024 paths; past them it follows the root and the folders expanded most
    /// recently, and an older one stays shown and is read again when expanded again.
    @Test func pastTheWatchCapTheMostRecentFoldersAreFollowed() throws {
        let names = (0 ..< FileTree.watchCap).map { String(format: "d%04d", $0) }
        var tree = try Self.listed(names.map(Self.folder))
        for name in names {
            let path = Self.root + "/" + name
            let id = try #require(Self.id(listing: path, in: tree.expand(path)))
            _ = tree.receive(.filesListing(FileListing(id: id, version: "v-\(name)", entries: [], more: false, truncated: false)), at: 0)
        }
        let watched = tree.watchSet.map(\.path)
        #expect(watched.count == FileTree.watchCap && watched.contains(Self.root), "\(watched.count) watched")
        #expect(!watched.contains(Self.root + "/d0000") && watched.contains(Self.root + "/d0001") && watched.contains(Self.root + "/\(names.last ?? "")"))
        #expect(!tree.followsAll && tree.folders[Self.root + "/d0000"] != nil, "the oldest folder was dropped, not only unwatched")
        _ = tree.collapse(Self.root + "/d0000")
        #expect(tree.followsAll)
        let again = tree.expand(Self.root + "/d0000")
        #expect(Self.id(listing: Self.root + "/d0000", in: again) != nil, "expanded again, it was not read again")
    }

    /// A rename is its old name gone and its new one come: only the new one is marked new.
    @Test func aRenameMarksOnlyItsNewName() throws {
        var tree = try Self.listed([Self.file("old.txt"), Self.file("b.txt")])
        let id = try #require(Self.id(listing: Self.root, in: tree.receive(.filesChanged(paths: [Self.root]), at: 1)))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v2", entries: [Self.file("new.txt"), Self.file("b.txt")], more: false, truncated: false)), at: 1)
        #expect(tree.isNew(Self.root + "/new.txt", at: 1) && !tree.isNew(Self.root + "/old.txt", at: 1) && !tree.isNew(Self.root + "/b.txt", at: 1))
    }

    /// M8 and M8b: opening a broken link says why under it, asking nothing; a pipe, socket or device
    /// opens nothing.
    @Test func openingABrokenLinkSaysWhyAndAnOtherOpensNothing() throws {
        let other = FileEntry(name: "fifo", kind: .known(.other), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o644)
        var tree = try Self.listed([Self.link("loop", to: .loop), Self.link("gone", to: .missing), other])
        #expect(tree.open(Self.root + "/loop").isEmpty && tree.open(Self.root + "/gone").isEmpty && tree.open(Self.root + "/fifo").isEmpty)
        #expect(tree.rows.map(\.name) == ["fifo", "gone", "This link leads nowhere.", "loop", "This link leads back to itself."], "\(tree.rows.map(\.name))")
        #expect(tree.rows.filter { $0.kind == .note }.allSatisfy { $0.depth == 1 })
        let src = try Self.listed([Self.folder("src")])
        var opened = src
        #expect(Self.id(listing: Self.root + "/src", in: opened.open(Self.root + "/src")) != nil, "opening a folder did not expand it")
    }

    /// Two listings of one folder out at once (collapsed and opened again): only the latest is
    /// taken, whichever answers last, and the older one marks nothing new.
    @Test func onlyAFoldersLatestListingIsTaken() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src"
        let older = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.collapse(src)
        let newer = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.receive(.filesListing(FileListing(id: newer, version: "s2", entries: [Self.file("new.ts")], more: false, truncated: false)), at: 1)
        _ = tree.receive(.filesListing(FileListing(id: older, version: "s1", entries: [Self.file("old.ts")], more: false, truncated: false)), at: 2)
        #expect(tree.folders[src]?.version == "s2" && tree.rows.map(\.name) == ["src", "new.ts"], "the older listing replaced the newer: \(tree.rows.map(\.name))")
        #expect(!tree.isNew(src + "/old.ts", at: 2) && !tree.isNew(src + "/new.ts", at: 2), "a stale or first listing marked an entry new")
    }

    /// A refusal that answers after its folder was shut is not kept: opened again, the folder waits
    /// for its own new answer.
    @Test func aRefusalForAShutFolderIsIgnored() throws {
        var tree = try Self.listed([Self.folder("build")])
        let build = Self.root + "/build"
        let id = try #require(Self.id(listing: build, in: tree.expand(build)))
        _ = tree.collapse(build)
        _ = tree.receive(.filesRefused(id: id, kind: .known(.notPermitted), message: "The workspace's user may not open build."), at: 1)
        #expect(tree.refusals[build] == nil, "a refusal for a shut folder was kept")
        _ = tree.expand(build)
        #expect(!tree.rows.contains { $0.kind == .note }, "the old refusal shows under the folder opened again")
    }

    /// A folder refused after it was listed drops the folders expanded under it, and their watch.
    @Test func aRefusedFolderDropsWhatWasExpandedUnderIt() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src", deep = src + "/deep"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "s1", entries: [Self.folder("deep")], more: false, truncated: false)), at: 0)
        let deepID = try #require(Self.id(listing: deep, in: tree.expand(deep)))
        _ = tree.receive(.filesListing(FileListing(id: deepID, version: "d1", entries: [], more: false, truncated: false)), at: 0)
        let again = try #require(Self.id(listing: src, in: tree.receive(.filesChanged(paths: [src]), at: 1)))
        let after = tree.receive(.filesRefused(id: again, kind: .known(.notPermitted), message: "The workspace's user may not open src."), at: 1)
        #expect(!tree.expanded.contains(deep) && tree.folders[deep] == nil, "a folder under the refused one is still expanded")
        #expect(Self.watched(after)?.contains { $0.path == deep } != true, "a folder under the refused one is still watched")
    }

    /// Admitted, the tree is live at once, before any tick.
    /// Admission alone is not the host's word that the tree is current: a host that admits but
    /// whose watch has stalled would show the tree live for its silence at every reconnect. Live
    /// once a tick or a listing comes after admission; until then the last time heard is said.
    @Test func admittedTheTreeIsLiveOnlyOnceTheHostIsHeard() throws {
        var tree = try Self.listed([Self.file("a.txt")])
        _ = tree.receive(.filesTick, at: 49.9)
        tree.disconnected()
        _ = tree.admitted()
        #expect(!tree.isLive(at: 50.5) && tree.heard == 49.9, "live at admission, before the host said anything")
        _ = tree.receive(.filesTick, at: 51)
        #expect(tree.isLive(at: 51.5))
        tree.disconnected()
        let id = try #require(Self.id(listing: Self.root, in: tree.admitted()))
        _ = tree.receive(.filesListing(FileListing(id: id, version: "v2", entries: [Self.file("a.txt")], more: false, truncated: false)), at: 60.2)
        #expect(tree.isLive(at: 60.5), "a listing after admission is the host heard")
    }

    /// A folder whose listing is refused as `changed` (it changed while being listed) is asked again
    /// at the next tick, not at once.
    @Test func aFolderChangedWhileListedIsAskedAgainAtTheNextTick() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        let refused = tree.receive(.filesRefused(id: id, kind: .known(.changed), message: "src changed while it was listed."), at: 1)
        #expect(Self.id(listing: src, in: refused) == nil, "asked again at once: \(refused)")
        #expect(tree.refusals[src] == nil, "a changed listing was said as a refusal")
        #expect(Self.id(listing: src, in: tree.receive(.filesTick, at: 2)) != nil, "not asked again at the tick")
        #expect(tree.receive(.filesTick, at: 3).isEmpty, "asked again at a second tick with no change")
    }

    /// An expanded folder that changes on every listing (node_modules under npm install) is asked
    /// at most once a tick, and the rest of the tree goes on: another folder's change is listed and
    /// taken, and a folder opened meanwhile is watched.
    @Test func aFolderThatKeepsChangingHoldsBackNothingElse() throws {
        var tree = try Self.listed([Self.folder("node_modules"), Self.folder("src"), Self.folder("docs")])
        let modules = Self.root + "/node_modules", src = Self.root + "/src", docs = Self.root + "/docs"
        var asked = try #require(Self.id(listing: modules, in: tree.expand(modules)))
        var listings = 1, ticks = 0, open = true
        /// Every listing of node_modules asked is refused as changed at once, as long as it is asked.
        func refuseModules(at now: Double) -> [Frame] {
            var said: [Frame] = []
            for _ in 0 ..< 100 where open {
                let answer = tree.receive(.filesRefused(id: asked, kind: .known(.changed), message: "node_modules changed while it was listed."), at: now)
                said += answer
                open = false
                if let again = Self.id(listing: modules, in: answer) {
                    asked = again
                    listings += 1
                    open = true
                }
            }
            return said
        }
        _ = refuseModules(at: 0.1)
        let docsID = try #require(Self.id(listing: docs, in: tree.expand(docs)))
        _ = tree.receive(.filesListing(FileListing(id: docsID, version: "d1", entries: [], more: false, truncated: false)), at: 0.2)
        var srcID: Int32?
        var srcWatch: [WatchedPath]?, docsWatch: [WatchedPath]?
        for second in 1 ... 10 {
            let now = Double(second)
            ticks += 1
            let atTick = tree.receive(.filesTick, at: now)
            if let again = Self.id(listing: modules, in: atTick) {
                asked = again
                listings += 1
                open = true
            }
            if second == 3 { srcID = Self.id(listing: src, in: tree.expand(src)) }
            if second == 4, let srcID {
                srcWatch = Self.watched(tree.receive(.filesListing(FileListing(id: srcID, version: "s1", entries: [Self.file("main.ts")], more: false, truncated: false)), at: now))
            }
            if second == 6 {
                let id = try #require(Self.id(listing: docs, in: tree.receive(.filesChanged(paths: [docs]), at: now)), "another folder's change was not listed")
                docsWatch = Self.watched(tree.receive(.filesListing(FileListing(id: id, version: "d2", entries: [Self.file("guide.md")], more: false, truncated: false)), at: now))
            }
            _ = refuseModules(at: now + 0.1)
        }
        #expect(listings <= ticks + 1, "\(listings) listings of node_modules in \(ticks) ticks")
        #expect(tree.rows.map(\.name).contains("guide.md") && tree.rows.map(\.name).contains("main.ts"), "the rest of the tree was held back: \(tree.rows.map(\.name))")
        #expect(srcWatch?.contains(WatchedPath(path: src, version: "s1")) == true, "src's listing, taken while node_modules was being listed, held back the watch: \(srcWatch ?? [])")
        #expect(docsWatch?.contains(WatchedPath(path: docs, version: "d2")) == true, "docs' listing held back the watch: \(docsWatch ?? [])")
    }

    /// A folder refused as changed waits for the tick however it is named: the host naming it again
    /// at every sweep lists it no sooner, so one that never settles is listed once a tick.
    @Test func aFolderWaitingForTheTickIsNotListedSoonerWhenNamedAgain() throws {
        var tree = try Self.listed([Self.folder("node_modules")])
        let modules = Self.root + "/node_modules"
        var asked = try #require(Self.id(listing: modules, in: tree.expand(modules)))
        for tick in 1 ... 4 {
            _ = tree.receive(.filesRefused(id: asked, kind: .known(.changed), message: "node_modules changed while it was listed."), at: Double(tick) - 0.9)
            for sweep in 1 ... 3 {
                let named = tree.receive(.filesChanged(paths: [modules]), at: Double(tick) - 0.9 + Double(sweep) * 0.25)
                #expect(Self.id(listing: modules, in: named) == nil, "tick \(tick), sweep \(sweep): listed again before the tick")
            }
            asked = try #require(Self.id(listing: modules, in: tree.receive(.filesTick, at: Double(tick))), "not listed at tick \(tick)")
        }
    }

    /// A folder refused as changed three times in a row is unsettled, as of its last listing taken;
    /// a listing taken settles it.
    @Test func aFolderRefusedAsChangedThreeTimesInARowIsUnsettled() throws {
        var tree = try Self.listed([Self.folder("node_modules")])
        let modules = Self.root + "/node_modules"
        var asked = try #require(Self.id(listing: modules, in: tree.expand(modules)))
        _ = tree.receive(.filesListing(FileListing(id: asked, version: "m1", entries: [], more: false, truncated: false)), at: 5)
        asked = try #require(Self.id(listing: modules, in: tree.receive(.filesChanged(paths: [modules]), at: 6)))
        for tick in 1 ... 3 {
            #expect(tree.unsettled == nil, "unsettled after \(tick - 1) refusals")
            _ = tree.receive(.filesRefused(id: asked, kind: .known(.changed), message: "node_modules changed while it was listed."), at: 6 + Double(tick) - 0.5)
            if tick < 3 { asked = try #require(Self.id(listing: modules, in: tree.receive(.filesTick, at: 6 + Double(tick)))) }
        }
        let unsettled = try #require(tree.unsettled)
        #expect(unsettled.path == modules && unsettled.shownAsOf == 5)
        asked = try #require(Self.id(listing: modules, in: tree.receive(.filesTick, at: 10)))
        _ = tree.receive(.filesListing(FileListing(id: asked, version: "m2", entries: [], more: false, truncated: false)), at: 10.2)
        #expect(tree.unsettled == nil, "a listing taken did not settle it")
    }

    /// A folder's refusals in a row are counted afresh after a reconnect: one refusal on the new
    /// connection never makes it unsettled.
    @Test func aReconnectCountsAFoldersRefusalsAfresh() throws {
        var tree = try Self.listed([Self.folder("node_modules")])
        let modules = Self.root + "/node_modules"
        var asked = try #require(Self.id(listing: modules, in: tree.expand(modules)))
        for tick in 1 ... FileTree.unsettledAfter - 1 {
            _ = tree.receive(.filesRefused(id: asked, kind: .known(.changed), message: "node_modules changed while it was listed."), at: Double(tick) - 0.5)
            asked = try #require(Self.id(listing: modules, in: tree.receive(.filesTick, at: Double(tick))))
        }
        tree.disconnected()
        asked = try #require(Self.id(listing: modules, in: tree.admitted()))
        _ = tree.receive(.filesRefused(id: asked, kind: .known(.changed), message: "node_modules changed while it was listed."), at: 10)
        #expect(tree.unsettled == nil, "refusals from before the reconnect were counted")
    }

    /// The watch set is sent only when it changed: a refusal that changes nothing sends none.
    @Test func anUnchangedWatchSetIsNotSentAgain() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        #expect(Self.watched(tree.receive(.filesListing(FileListing(id: id, version: "s1", entries: [], more: false, truncated: false)), at: 1)) != nil)
        let again = try #require(Self.id(listing: src, in: tree.receive(.filesChanged(paths: [src]), at: 2)))
        let refused = tree.receive(.filesRefused(id: again, kind: .known(.changed), message: "src changed while it was listed."), at: 2.1)
        #expect(Self.watched(refused) == nil, "an unchanged watch set was sent again: \(refused)")
    }

    /// A folder shut while it waits for the tick forgets the wait: opened again and listed afresh,
    /// it is not listed a second time for the old wait.
    @Test func aFolderShutWhileWaitingForTheTickForgetsTheWait() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.receive(.filesRefused(id: id, kind: .known(.changed), message: "src changed while it was listed."), at: 1)
        _ = tree.collapse(src)
        let fresh = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.receive(.filesTick, at: 2)
        let taken = tree.receive(.filesListing(FileListing(id: fresh, version: "s1", entries: [], more: false, truncated: false)), at: 2.1)
        #expect(Self.id(listing: src, in: taken) == nil, "listed again for a wait it had before it was shut: \(taken)")
    }

    /// A listing that answers after its folder was shut is not taken: the folder stays shut.
    @Test func aListingAnsweringAfterItsFolderWasShutIsNotTaken() throws {
        var tree = try Self.listed([Self.folder("src")])
        let src = Self.root + "/src"
        let id = try #require(Self.id(listing: src, in: tree.expand(src)))
        _ = tree.collapse(src)
        _ = tree.receive(.filesListing(FileListing(id: id, version: "s1", entries: [Self.file("main.ts")], more: false, truncated: false)), at: 1)
        #expect(tree.folders[src] == nil && tree.rows.map(\.name) == ["src"], "a shut folder's late listing was taken: \(tree.rows.map(\.name))")
    }

    /// Two names that read alike sort by their bytes, the same whichever came first.
    @Test func namesThatReadAlikeSortByTheirBytes() {
        let low = FileEntry(name: FileName([0x61, 0xFE]), kind: .known(.file), target: .known(.none), size: 1, mtimeNanos: 0, mode: 0o644)
        let high = FileEntry(name: FileName([0x61, 0xFF]), kind: .known(.file), target: .known(.none), size: 1, mtimeNanos: 0, mode: 0o644)
        #expect(low.name.shown == high.name.shown, "the two names do not read alike")
        #expect(FileTree.sorted([high, low]).map(\.name) == [low.name, high.name])
        #expect(FileTree.sorted([low, high]).map(\.name) == [low.name, high.name])
    }

    /// The watch set goes once for a batch of listings, after the last of them, not with each.
    @Test func theWatchSetIsSentOnceForABatch() throws {
        var tree = try Self.listed((0 ..< 40).map { Self.folder("d\($0)") })
        for index in 0 ..< 40 {
            let path = Self.root + "/d\(index)"
            let id = try #require(Self.id(listing: path, in: tree.expand(path)))
            _ = tree.receive(.filesListing(FileListing(id: id, version: "v\(index)", entries: [], more: false, truncated: false)), at: 0)
        }
        tree.disconnected()
        let reconnect = tree.admitted()
        var watches = reconnect.filter { if case .filesWatch = $0 { true } else { false } }.count
        for frame in reconnect {
            guard case let .filesList(id, path) = frame else { continue }
            let answered = tree.receive(.filesListing(FileListing(id: id, version: "r-" + path.shown, entries: path == Self.root ? (0 ..< 40).map { Self.folder("d\($0)") } : [], more: false, truncated: false)), at: 1)
            watches += answered.filter { if case .filesWatch = $0 { true } else { false } }.count
        }
        #expect(watches == 2, "\(watches) watch frames for a reconnect of 41 folders: one at admission, one after the batch")
    }

    /// Two folder names that are not UTF-8 and read alike ("a\u{FFFD}") are two rows, two paths and
    /// two keys: one opens, the other stays shut, each listed and watched by its own bytes.
    @Test func lookAlikeNamesAreKeptApartByTheirBytes() throws {
        func folder(_ bytes: [UInt8]) -> FileEntry {
            FileEntry(name: FileName(bytes), kind: .known(.folder), target: .known(.none), size: 0, mtimeNanos: 0, mode: 0o755)
        }
        var tree = try Self.listed([folder([0x61, 0xFF]), folder([0x61, 0xFE])])
        #expect(tree.rows.count == 2 && tree.rows[0].name == tree.rows[1].name && tree.rows[0].path != tree.rows[1].path, "\(tree.rows)")
        #expect(tree.rows[0].path.key != tree.rows[1].path.key && tree.rows[0].name == "a\u{FFFD}")
        let first = tree.rows[0].path
        let id = try #require(Self.id(listing: first, in: tree.expand(first)))
        let watched = tree.receive(.filesListing(FileListing(id: id, version: "x1", entries: [Self.file("inside")], more: false, truncated: false)), at: 0)
        #expect(tree.expanded.contains(first) && !tree.expanded.contains(tree.rows.last?.path ?? first), "opening one look-alike opened the other")
        #expect(tree.rows.map(\.name) == ["a\u{FFFD}", "inside", "a\u{FFFD}"] && Self.watched(watched)?.contains { $0.path == first } == true)
        #expect(first.key.hasPrefix("\u{0}") && Self.root.appending("plain").key == "/home/dev/workspace/plain", "a key is not the path for UTF-8 names, hex after a NUL for others")
    }

    @Test func nothingIsSentWhileNoConnectionIsAdmitted() throws {
        var tree = try Self.listed([Self.folder("src")])
        tree.disconnected()
        #expect(tree.expand(Self.root + "/src").isEmpty && tree.expanded.contains(Self.root + "/src"), "an expand while away sent, or was forgotten")
    }
}
