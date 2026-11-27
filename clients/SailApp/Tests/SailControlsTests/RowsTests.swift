import AppKit
import Foundation
import SailKitTestSupport
@testable import SailControls
import SailLook
import Testing

/// List and sidebar rows, the tooltip, the banner and the empty state, drawn by AppKit and read
/// against swatches it composes the same way, each beside a wrong one; and their keys, menus and
/// VoiceOver actions.
@MainActor @Suite(.serialized) struct RowsTests {
    init() {
        ExitWatch.install()
    }

    final class Counts {
        var opened = 0, copied = 0
        var chosen: [String?] = []
    }

    static func list(_ style: RowStyle, look: Look, counts: Counts) throws -> SailList {
        let list = SailList(rowStyle: style, look: look, mono: try ControlsTests.mono())
        let item = { (id: String) in
            SailList.Item(id: id, name: id, voice: "\(id), running", content: NSTextField(labelWithString: id), actions: [
                SailList.Action("Open") { counts.opened += 1 },
                SailList.Action("Copy Name", key: "c", modifiers: .command, onRow: false) { counts.copied += 1 },
            ])
        }
        list.entries = [.head("Yours", count: 2), .item(item("pier")), .item(item("dock"))]
        list.frame = NSRect(x: 0, y: 0, width: 440, height: 160)
        return list
    }

    static func row(_ list: SailList, _ index: Int) throws -> SailRowView {
        list.layoutSubtreeIfNeeded()
        return try #require(list.rowView(atRow: index, makeIfNecessary: true) as? SailRowView)
    }

    static func shown(_ list: SailList) -> (TestWindow, Backdrop) {
        let base = NSColor(Tint(list.look.canvas))
        return ControlsTests.window(holding: list, on: base)
    }

    /// A list's rows on paper: the head is its eyebrow alone, with no band; the hairline above a row
    /// is one pixel; the chosen row is not filled, with the keys or without, and is found by its
    /// 2 × 24 bar; the hovered row is not filled. At 1x and 2x, both looks.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aListsRowsAreTheSheets(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        let list = try Self.list(.list, look: look, counts: Counts())
        let (window, backdrop) = Self.shown(list)
        defer { window.orderOut(nil) }
        let head = list.rect(ofRow: 0), pier = list.rect(ofRow: 1), dock = list.rect(ofRow: 2)
        #expect(near(head.height, 34) && near(pier.height, 53) && near(dock.height, 53), "\(name): rows \(head.height), \(pier.height), \(dock.height)")
        list.choose("dock")
        let chosen = try Self.row(list, 2)
        for scale in [1.0, 2.0] {
            let bare = ControlsTests.swatch(canvas, [], scale: scale), grey = ControlsTests.swatch(canvas, [NSColor(look.fillPressed)], scale: scale)
            let accent = ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: scale)
            let band = backdrop.colour(at: NSPoint(x: 200, y: head.midY), scale: scale)
            #expect(ControlsTests.tells(band, bare, from: grey), "\(name) at \(scale)x: the head has a band, \(band)")
            let line = backdrop.colour(at: NSPoint(x: 200, y: pier.minY + 0.5), scale: scale), under = backdrop.colour(at: NSPoint(x: 200, y: pier.minY + 1.5), scale: scale)
            #expect(ControlsTests.tells(line, ControlsTests.swatch(canvas, [NSColor(look.gridLine)], scale: scale), from: bare) && ControlsTests.tells(under, bare, from: grey),
                    "\(name) at \(scale)x: the hairline \(line), under it \(under)")
            let idle = ControlsTests.swatch(canvas, [NSColor(look.barIdle)], scale: scale)
            for emphasized in [true, false] {
                chosen.isEmphasized = emphasized
                let fill = backdrop.colour(at: NSPoint(x: 200, y: dock.midY), scale: scale), bar = backdrop.colour(at: NSPoint(x: 0.5, y: dock.midY), scale: scale)
                #expect(ControlsTests.tells(fill, bare, from: grey), "\(name) at \(scale)x, keys \(emphasized): the chosen row is filled, \(fill)")
                #expect(ControlsTests.tells(bar, emphasized ? accent : idle, from: emphasized ? idle : accent), "\(name), keys \(emphasized): the bar is \(bar)")
                #expect(chosen.drawnMarks == (emphasized ? [.accentLine] : []), "\(name), keys \(emphasized): the row counts \(chosen.drawnMarks)")
                let outside = backdrop.colour(at: NSPoint(x: 0.5, y: dock.minY + 1.5 + 13), scale: scale)
                #expect(ControlsTests.tells(outside, bare, from: accent), "\(name): the bar is longer than 24 pt")
            }
            let rest = try Self.row(list, 1)
            rest.hovered = true
            let hovered = backdrop.colour(at: NSPoint(x: 200, y: pier.midY), scale: scale)
            #expect(ControlsTests.tells(hovered, bare, from: grey), "\(name) at \(scale)x: the hovered row is filled, \(hovered)")
            rest.hovered = false
        }
        #expect(chosen.paint.bar && chosen.paint.strong, "\(name): the chosen row is not found by its bar and weight")
    }

    /// The sidebar's rows, as the left panel draws a project: 30 pt with no gap between rows, no fill
    /// hovered or chosen, and the chosen row's bar at the list's own edge, 2 × 14.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func theSidebarsRowsAreTheSheets(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        let list = try Self.list(.sidebar, look: look, counts: Counts())
        list.entries = Array(list.entries.dropFirst())
        let (window, backdrop) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.choose("dock")
        let pier = list.rect(ofRow: 0), dock = list.rect(ofRow: 1)
        #expect(near(pier.height, 30) && near(dock.minY, pier.maxY))
        let hovered = try Self.row(list, 0)
        hovered.hovered = true
        let chosen = try Self.row(list, 1)
        chosen.isEmphasized = true
        let bare = ControlsTests.swatch(canvas, [], scale: 2), grey = ControlsTests.swatch(canvas, [NSColor(look.fillPressed)], scale: 2)
        for (x, y) in [(6.5, Double(dock.midY)), (100, Double(dock.minY) + 0.5), (100, Double(dock.midY))] {
            #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: x, y: y), scale: 2), bare, from: grey), "\(name): the chosen row is filled at \(x), \(y)")
        }
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 0.5, y: dock.midY), scale: 2), ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: 2), from: bare))
        let accent = ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: 2)
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 0.5, y: dock.minY + 7.5), scale: 2), bare, from: accent), "\(name): the bar is longer than 14 pt")
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 0.5, y: dock.minY + 8.5), scale: 2), accent, from: bare), "\(name): the bar is shorter than 14 pt")
        let leadingCorner = backdrop.colour(at: NSPoint(x: 0.25, y: dock.minY + 8.25), scale: 2), trailingCorner = backdrop.colour(at: NSPoint(x: 1.75, y: dock.minY + 8.25), scale: 2)
        #expect(leadingCorner.near(accent) && !trailingCorner.near(accent), "\(name): the bar's corners are \(leadingCorner) and \(trailingCorner), not square at the edge and rounded away from it")
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 100, y: pier.midY), scale: 2), bare, from: grey), "\(name): the hovered row is filled")
        chosen.isEmphasized = false
        let idle = ControlsTests.swatch(canvas, [NSColor(look.barIdle)], scale: 2)
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 0.5, y: dock.midY), scale: 2), idle, from: accent), "\(name): the bar without the keys is not the quiet ink")
    }

    /// N2: where the card's edge is drawn over the list's leading edge (the left panel, in dark), the
    /// chosen row's bar starts a hairline in, read at 2x: the edge's point holds no bar, the two
    /// after it are the bar, its outer end still square; mirrored right to left.
    static func plain(_ id: String, _ name: String) -> SailList.Entry {
        .item(SailList.Item(id: id, name: name, voice: name, content: NSTextField(labelWithString: name)))
    }

    /// A row holding an inline edit (a rename, a new file's name) is never remade: rows go and come
    /// around it, past a fill's count too, and its own change waits for the edit's end, which
    /// remakes it.
    @Test func aRowBeingEditedIsNeverRemade() throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = [Self.plain("a", "a"), Self.plain("b", "b"), Self.plain("c", "c")]
        list.frame = NSRect(x: 0, y: 0, width: 440, height: 400)
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.layoutSubtreeIfNeeded()
        let content = try #require((list.view(atColumn: 0, row: 1, makeIfNecessary: true) as? RowCell)?.content)
        list.editing = "b"
        let many = (0 ..< 300).map { Self.plain("n\($0)", "n\($0)") }
        list.apply([Self.plain("new", "new"), Self.plain("a", "a"), Self.plain("b", "b renamed")] + many, changed: ["b"])
        list.layoutSubtreeIfNeeded()
        let during = try #require(list.rowView(atRow: 2, makeIfNecessary: true) as? SailRowView)
        #expect((list.view(atColumn: 0, row: 2, makeIfNecessary: true) as? RowCell)?.content === content && during.item?.name == "b", "the edited row was remade: \(during.item?.name ?? "none")")
        #expect(list.editing == "b")
        list.editing = nil
        list.layoutSubtreeIfNeeded()
        let after = try #require(list.rowView(atRow: 2, makeIfNecessary: true) as? SailRowView)
        #expect(after.item?.name == "b renamed" && (list.view(atColumn: 0, row: 2, makeIfNecessary: true) as? RowCell)?.content !== content, "its change waited past the edit's end: \(after.item?.name ?? "none")")
    }

    /// A fill (new rows, a look changed) remakes every row: an edit under way ends, and `editing`
    /// names no row.
    @Test func aFillEndsAnEditCleanly() throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = [Self.plain("a", "a"), Self.plain("b", "b")]
        list.editing = "b"
        list.look = .dark
        #expect(list.editing == nil, "a look change left editing naming a row it remade")
        list.editing = "b"
        list.entries = [Self.plain("a", "a"), Self.plain("b", "b")]
        #expect(list.editing == nil, "a fill left editing naming a row it remade")
    }

    /// A row that leaves the list leaves its edit with it.
    @Test func anEditedRowThatLeavesEndsTheEdit() throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = [Self.plain("a", "a"), Self.plain("b", "b")]
        list.editing = "b"
        list.apply([Self.plain("a", "a")], changed: [])
        #expect(list.editing == nil)
    }

    /// The hovered row, remade because it changed, is still hovered while the pointer is still over
    /// it; one that rows inserted above moved from under the pointer is not.
    @Test func aRemadeRowKeepsItsHover() throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = [Self.plain("a", "a"), Self.plain("b", "b")]
        list.frame = NSRect(x: 0, y: 0, width: 440, height: 200)
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.layoutSubtreeIfNeeded()
        let overFirst = list.convert(NSPoint(x: 20, y: list.rect(ofRow: 0).midY), to: nil)
        list.pointer = { overFirst }
        let before = try #require(list.rowView(atRow: 0, makeIfNecessary: true) as? SailRowView)
        before.hovered = true
        list.apply([Self.plain("a", "a changed"), Self.plain("b", "b")], changed: ["a"])
        list.layoutSubtreeIfNeeded()
        let after = try #require(list.rowView(atRow: 0, makeIfNecessary: true) as? SailRowView)
        #expect(after.item?.name == "a changed" && after.hovered, "the hover was lost with the row it was on: \(after.item?.name ?? "none"), hovered \(after.hovered)")
        list.apply([Self.plain("new", "new"), Self.plain("a", "a changed again"), Self.plain("b", "b")], changed: ["a"])
        list.layoutSubtreeIfNeeded()
        let moved = try #require(list.rowView(atRow: 1, makeIfNecessary: true) as? SailRowView)
        #expect(moved.item?.name == "a changed again" && !moved.hovered, "a row moved from under the pointer was marked hovered")
    }

    /// What a row places by arithmetic lands on whole pixels of its window, as Auto Layout placed
    /// it: a note centred a fraction of a pixel off is drawn re-sampled, not as it was.
    @Test(arguments: [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft])
    func aRowsViewsLandOnWholePixels(_ direction: NSUserInterfaceLayoutDirection) throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.userInterfaceLayoutDirection = direction
        list.entries = [.item(SailList.Item(id: "dock", name: "dock", voice: "dock", content: NSTextField(labelWithString: "dock"), actions: [SailList.Action("Open") {}], note: "about 3 s", height: 37.5))]
        list.frame = NSRect(x: 0, y: 0, width: 440.5, height: 160)
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.layoutSubtreeIfNeeded()
        let cell = try #require(list.view(atColumn: 0, row: 0, makeIfNecessary: true))
        cell.layoutSubtreeIfNeeded()
        func placed(_ view: NSView) -> [NSView] {
            view.subviews.flatMap { [$0] + placed($0) }
        }
        let off = placed(cell).filter { view in
            let box = window.convertToBacking(view.convert(view.bounds, to: nil))
            return [box.minX, box.minY, box.maxX, box.maxY].contains { abs($0 - $0.rounded()) > 0.001 }
        }
        #expect(off.isEmpty, "off the pixel grid: \(off.map { "\(type(of: $0)) \($0.frame)" })")
    }

    @Test(arguments: [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft])
    func theBarStartsClearOfTheCardsEdge(_ direction: NSUserInterfaceLayoutDirection) throws {
        let look = Look.dark, canvas = NSColor(Tint(look.canvas))
        let list = try Self.list(.sidebar, look: look, counts: Counts())
        list.userInterfaceLayoutDirection = direction
        list.barInset = Look.hairline
        let (window, backdrop) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.choose("dock")
        let row = try Self.row(list, 2)
        row.isEmphasized = true
        row.userInterfaceLayoutDirection = direction
        let dock = list.rect(ofRow: 2), width = Double(list.bounds.width)
        let at = { (x: Double) in direction == .rightToLeft ? width - x : x }
        let bare = ControlsTests.swatch(canvas, [], scale: 2), accent = ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: 2)
        let side = direction == .rightToLeft ? "right to left" : "left to right"
        for x in [0.25, 0.75] {
            #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: at(x), y: dock.midY), scale: 2), bare, from: accent), "\(side): the bar is under the card's edge at \(x) pt")
        }
        for x in [1.25, 1.75, 2.25, 2.75] {
            #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: at(x), y: dock.midY), scale: 2), accent, from: bare), "\(side): no bar at \(x) pt")
        }
        let outer = backdrop.colour(at: NSPoint(x: at(1.25), y: dock.minY + 8.25), scale: 2)
        #expect(outer.near(accent), "\(side): the bar's outer corner is \(outer), not square")
    }

    /// A sidebar row lays a project out as the left panel does: the tile after the disclosure's
    /// 12 pt slot, the name's ink starting where the mockup's does (x 58, 7 pt after the tile), the
    /// pill ending 12 pt in from the row's trailing edge, at whatever width the row is.
    @Test func aSidebarRowIsThePanelsProjectRow() throws {
        let mono = try ControlsTests.mono()
        let list = SailList(rowStyle: .sidebar, look: .light, mono: mono)
        let cell = SidebarRowCell("demo", template: .noTemplate, look: .light, mono: mono)
        list.entries = [.item(SailList.Item(id: "demo", name: "demo", voice: "demo", content: cell))]
        list.frame = NSRect(x: 0, y: 0, width: 235, height: 30)
        let (window, backdrop) = Self.shown(list)
        defer { window.orderOut(nil) }
        let row = try Self.row(list, 0)
        row.layoutSubtreeIfNeeded()
        let pill = try #require(cell.pill)
        let tile = cell.convert(try #require(cell.tile).frame, to: row)
        let badge = cell.convert(pill.alignmentRect(forFrame: pill.frame), to: row)
        #expect(near(tile.minX, 31) && near(tile.width, 20) && abs(Double(tile.midY) - 15) <= 0.5, "the tile is at \(tile)")
        let name = try #require(backdrop.ink(in: NSRect(x: 52, y: 2, width: 60, height: 26)))
        #expect(abs(Double(name.minX) - 58) <= 1, "the name's ink starts at \(name.minX), not at the mockup's 58")
        let trailing = Double(row.bounds.width) - 12
        #expect(abs(Double(badge.maxX) - trailing) <= 0.5, "the pill ends at \(badge.maxX), not 12 pt in from the row's \(row.bounds.width)")
    }

    /// A row's state set to what it already is touches nothing: the table sets it on every layout
    /// pass, so a row that relaid its actions each time would never let layout settle.
    @Test func anUnchangedRowStateLeavesLayoutSettled() throws {
        let list = try Self.list(.list, look: .light, counts: Counts())
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.choose("pier")
        let row = try Self.row(list, 1)
        window.layoutIfNeeded()
        let cell = try #require(row.view(atColumn: 0) as? RowCell)
        let settled = !row.needsLayout && !cell.needsLayout && !cell.actions.needsLayout
        let selected = row.isSelected, emphasized = row.isEmphasized, hovered = row.hovered
        row.isSelected = selected
        row.isEmphasized = emphasized
        row.hovered = hovered
        #expect(settled && !row.needsLayout && !cell.needsLayout && !cell.actions.needsLayout, "setting a row's state to what it was asked for layout again")
    }

    /// New rows keep the chosen item by its id, wherever it now stands, and say nothing of it; a
    /// choice whose item went is said, as nothing chosen.
    @Test func newRowsKeepTheChosenItem() throws {
        let counts = Counts()
        let list = try Self.list(.list, look: .light, counts: counts)
        list.onChoose = { counts.chosen.append($0) }
        list.choose("dock")
        counts.chosen.removeAll()
        let entries = list.entries
        list.entries = [entries[0], entries[2], entries[1]]
        let moved = counts.chosen
        #expect(list.chosen == "dock" && list.selectedRow == 1 && moved.isEmpty, "dock moved up and the choice is \(list.chosen ?? "nothing") at \(list.selectedRow), said \(moved)")
        list.entries = [entries[0], entries[1]]
        let went = counts.chosen
        #expect(list.chosen == nil && went == [String?.none], "dock went, and the list said \(went)")
    }

    /// New rows keep the person's scroll: the chosen row is not scrolled back into view.
    @Test func newRowsKeepTheScroll() throws {
        let mono = try ControlsTests.mono()
        let list = SailList(rowStyle: .list, look: .light, mono: mono)
        let entries: [SailList.Entry] = (0 ..< 30).map { .item(SailList.Item(id: "w\($0)", name: "w\($0)", voice: "w\($0)", content: NSView())) }
        list.entries = entries
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 440, height: 200))
        scroll.documentView = list
        list.frame = NSRect(x: 0, y: 0, width: 440, height: Double(list.rect(ofRow: 29).maxY))
        list.choose("w0")
        list.scrollRowToVisible(29)
        let away = scroll.contentView.bounds.origin.y
        #expect(away > 0)
        list.entries = entries
        #expect(list.chosen == "w0" && near(scroll.contentView.bounds.origin.y, away), "new rows scrolled back to the chosen one")
    }

    /// `apply` changes only what differs: a new row inserted where it goes, a gone one removed, the
    /// rows left in place kept as the same views unless named changed, and the choice on its row.
    @Test func applyChangesOnlyWhatDiffers() throws {
        let list = SailList(rowStyle: .tree, look: .light, mono: try ControlsTests.mono())
        func entries(_ ids: [String]) -> [SailList.Entry] {
            ids.map { .item(SailList.Item(id: $0, name: $0, voice: $0, content: NSView())) }
        }
        list.entries = entries(["a", "b", "c", "d"])
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.choose("c")
        let kept = (try Self.row(list, 3).view(atColumn: 0) as? RowCell)?.content
        let remade = (try Self.row(list, 1).view(atColumn: 0) as? RowCell)?.content
        let made = list.rowsMade
        list.apply(entries(["a", "b", "b2", "d"]), changed: ["b"])
        #expect(list.entries.count == 4 && list.chosen == nil, "c went and the choice stayed on a row: \(list.chosen ?? "nothing")")
        let keptNow = (try Self.row(list, 3).view(atColumn: 0) as? RowCell)?.content, remadeNow = (try Self.row(list, 1).view(atColumn: 0) as? RowCell)?.content
        #expect(kept != nil && keptNow === kept, "d's row was made again with nothing changed in it")
        #expect(remadeNow !== remade, "b, named changed, was not made over with its content again")
        #expect(list.rowsMade - made <= 2, "made \(list.rowsMade - made) rows for one inserted and one changed, not only those")
        list.choose("d")
        list.apply(entries(["a", "a1", "b", "b2", "d"]), changed: [])
        #expect(list.chosen == "d", "the choice did not follow its row")
        let first = (try Self.row(list, 0).view(atColumn: 0) as? RowCell)?.content
        let bound = (0 ..< SailList.applied).map { "n\($0)" }
        list.apply(entries(["a", "a1", "b", "b2", "d"] + bound), changed: [])
        let atBound = (try Self.row(list, 0).view(atColumn: 0) as? RowCell)?.content
        #expect(list.numberOfRows == 5 + bound.count && atBound === first, "at its bound apply made the rows again")
        let past = (0 ..< SailList.applied + 1).map { "m\($0)" }
        list.apply(entries(["a", "a1", "b", "b2", "d"] + past), changed: [])
        #expect(list.numberOfRows == 5 + past.count && list.chosen == "d", "past its bound apply did not fill the table afresh, keeping the choice")
        let pastBound = (try Self.row(list, 0).view(atColumn: 0) as? RowCell)?.content
        #expect(pastBound !== first, "past its bound the rows were not made afresh")
    }

    /// A row is laid out by frames, with no constraint of its own, its content from its leading inset
    /// to its trailing one, mirrored right to left.
    @Test func aBareRowIsLaidOutByFramesWithNoConstraint() throws {
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let list = SailList(rowStyle: .tree, look: .light, mono: try ControlsTests.mono())
            let content = NSView()
            list.entries = [.item(SailList.Item(id: "a", name: "a", voice: "a", content: content))]
            let (window, _) = Self.shown(list)
            defer { window.orderOut(nil) }
            let row = try Self.row(list, 0)
            let cell = try #require(row.view(atColumn: 0) as? RowCell)
            cell.userInterfaceLayoutDirection = direction
            cell.needsLayout = true
            cell.layoutSubtreeIfNeeded()
            let made = cell.constraints.filter { !String(describing: type(of: $0)).contains("Autoresizing") }
            #expect(made.isEmpty && content.translatesAutoresizingMaskIntoConstraints && cell.actions.views.isEmpty, "\(direction): a bare row carries constraints of its own: \(made)")
            let inset = RowStyle.tree.margin + RowStyle.tree.leading, end = RowStyle.tree.margin + RowStyle.tree.trailing
            #expect(near(Double(content.frame.minX), direction == .rightToLeft ? end : inset), "\(direction): \(content.frame)")
            #expect(near(Double(content.frame.maxX), Double(cell.bounds.width) - (direction == .rightToLeft ? inset : end)), "\(direction): \(content.frame)")
        }
    }

    /// A row scrolled out refused, chosen and hovered, with actions and a note, leaves nothing in the
    /// views a plain row then takes: not hovered, not refused, no actions, no note or its tooltip, the
    /// plain row's own content and words, laid out its way; in both looks and right to left.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aReusedRowKeepsNothingOfTheRowBefore(_ name: String, _ look: Look) throws {
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let list = SailList(rowStyle: .list, look: look, mono: try ControlsTests.mono())
            list.userInterfaceLayoutDirection = direction
            let marked = SailList.Item(
                id: "r0", name: "r0", voice: "r0, refused", content: NSView(), actions: [SailList.Action("Open") {}, SailList.Action("Delete", onRow: false, destructive: true) {}],
                note: "The workspace's user may not open it.", paint: .refused
            )
            let plain = (1 ..< 80).map { SailList.Item(id: "r\($0)", name: "r\($0)", voice: "r\($0)", content: NSView()) }
            list.entries = ([marked] + plain).map { .item($0) }
            let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 400, height: 200))
            scroll.documentView = list
            list.frame = NSRect(x: 0, y: 0, width: 400, height: Double(list.rect(ofRow: 79).maxY))
            let (window, _) = ControlsTests.window(holding: scroll, on: NSColor(Tint(look.canvas)))
            defer { window.orderOut(nil) }
            list.choose("r0")
            let row = try Self.row(list, 0)
            row.hovered = true
            let cell = try #require(row.view(atColumn: 0) as? RowCell)
            #expect(!cell.actions.views.isEmpty && cell.noteTip != nil, "\(name) \(direction): the marked row is not marked")
            list.scrollRowToVisible(79)
            list.layoutSubtreeIfNeeded()
            #expect(list.rowsReused > 0, "\(name) \(direction): no row was reused")
            let visible = list.rows(in: list.visibleRect)
            for index in visible.lowerBound ..< visible.upperBound {
                let view = try Self.row(list, index)
                let reused = try #require(view.view(atColumn: 0) as? RowCell)
                let voice = "r\(index)"
                #expect(!view.hovered && !view.paint.bar && view.item?.paint == nil, "\(name) \(direction): row \(index) kept the old row's state")
                #expect(reused.actions.views.isEmpty && reused.noteTip == nil, "\(name) \(direction): row \(index) kept the old row's actions or note")
                #expect(view.accessibilityLabel() == voice && view.accessibilityCustomActions()?.isEmpty != false, "\(name) \(direction): row \(index) says \(view.accessibilityLabel() ?? "nothing")")
                #expect(view.userInterfaceLayoutDirection == direction && reused.userInterfaceLayoutDirection == direction, "\(name) \(direction): row \(index) is laid out the other way")
                reused.layoutSubtreeIfNeeded()
                let inset = RowStyle.list.margin + RowStyle.list.leading, end = RowStyle.list.margin + RowStyle.list.trailing
                let start = direction == .rightToLeft ? end : inset
                #expect(near(Double(reused.content.frame.minX), start) && near(Double(reused.content.frame.width), Double(reused.bounds.width) - inset - end),
                        "\(name) \(direction): row \(index)'s content at \(reused.content.frame)")
            }
            #expect(list.rowsMade - list.rowsReused <= visible.length + 4, "\(name) \(direction): made \(list.rowsMade - list.rowsReused) new rows for a screenful of \(visible.length)")
        }
    }

    /// An item whose content and accessory are one view each, as the sidebar's rows and the
    /// creating row are: shown again through another pooled cell, the view moves there, and the
    /// cell it left, made over for another row, leaves it where it now is.
    @Test func aViewShownAgainThroughAnotherCellStaysThere() throws {
        let mono = try ControlsTests.mono(), view = NSView(), spinner = NSView(frame: NSRect(x: 0, y: 0, width: 12, height: 12))
        let creating = SailList.Item(id: "r", name: "r", voice: "r", content: view, accessory: spinner)
        let other = SailList.Item(id: "s", name: "s", voice: "s", content: NSView())
        let left = RowCell(creating, rowStyle: .list, actionsColumn: nil, look: .light, mono: mono)
        let now = RowCell(other, rowStyle: .list, actionsColumn: nil, look: .light, mono: mono)
        now.configure(creating, actionsColumn: nil, returnKey: true, look: .light)
        #expect(view.superview === now && spinner.superview === now.actions)
        left.configure(other, actionsColumn: nil, returnKey: true, look: .light)
        #expect(view.superview === now && spinner.superview === now.actions, "the row showing it lost its content or spinner to the cell it left")
    }

    /// In the real list: sidebar rows each holding one view of their own, scrolled out and back
    /// three times, every row on screen still shows its own.
    @Test func sidebarRowsScrolledOutAndBackKeepTheirViews() throws {
        let list = SailList(rowStyle: .sidebar, look: .light, mono: try ControlsTests.mono())
        let views = (0 ..< 60).map { _ in NSView() }
        list.entries = views.indices.map { index in
            let view = views[index]
            return .item(SailList.Item(id: "p\(index)", name: "p\(index)", voice: "p\(index)", content: view))
        }
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 260, height: 180))
        scroll.documentView = list
        list.frame = NSRect(x: 0, y: 0, width: 260, height: Double(list.rect(ofRow: 59).maxY))
        let (window, _) = ControlsTests.window(holding: scroll, on: NSColor(Tint(Look.light.canvas)))
        defer { window.orderOut(nil) }
        for round in 0 ..< 3 {
            for target in [59, 0, 30, 5] {
                list.scrollRowToVisible(target)
                list.layoutSubtreeIfNeeded()
                let visible = list.rows(in: list.visibleRect)
                for index in visible.lowerBound ..< visible.upperBound {
                    let cell = list.view(atColumn: 0, row: index, makeIfNecessary: true)
                    #expect(views[index].superview === cell, "round \(round), at \(target): row \(index) went blank")
                }
            }
        }
        #expect(list.rowsReused > 0, "no row was reused")
    }

    /// Two items handing the list one view are two rows showing it: where misuse stops the process
    /// (tests, debug builds, the drive), the list stops it, saying so.
    @Test func twoItemsSharingAViewStopTheProcess() async {
        await #expect(processExitsWith: .failure) {
            await MainActor.run {
                guard let mono = try? ControlsTests.mono() else { return }
                let shared = NSView()
                let one = RowCell(SailList.Item(id: "a", name: "a", voice: "a", content: shared), rowStyle: .list, actionsColumn: nil, look: .light, mono: mono)
                _ = RowCell(SailList.Item(id: "b", name: "b", voice: "b", content: shared), rowStyle: .list, actionsColumn: nil, look: .light, mono: mono)
                _ = one
            }
        }
    }

    /// Every kind of row a list holds, by what its item carries.
    static func kinds() -> [(String, SailList.Entry)] {
        let accessory = { NSView(frame: NSRect(x: 0, y: 0, width: 12, height: 12)) }
        let actions = [SailList.Action("Open") {}, SailList.Action("Copy Name", key: "c", modifiers: .command, onRow: false) {}]
        return [
            ("plain", .item(SailList.Item(id: "k", name: "plain", voice: "plain", content: NSTextField(labelWithString: "plain")))),
            ("actions, note, accessory", .item(SailList.Item(id: "k", name: "full", voice: "full, running", content: NSTextField(labelWithString: "full"), actions: actions, note: "about 3 s", accessory: accessory()))),
            ("refused", .item(SailList.Item(id: "k", name: "refused", voice: "refused", content: NSTextField(labelWithString: "refused"), actions: actions, note: "The workspace's user may not open it.", paint: .refused))),
            ("creating", .item(SailList.Item(id: "k", name: "creating", voice: "creating", content: NSTextField(labelWithString: "creating"), note: "about 3 s", paint: .creating, accessory: accessory()))),
            ("create row", .item(SailList.Item(id: "k", name: "", voice: "A new workspace's name", content: NSView(frame: NSRect(x: 0, y: 0, width: 200, height: 40)), height: 88, paint: .naming, full: true))),
            ("tree", .item(SailList.Item(id: "k", name: "src", voice: "src, folder, expanded", content: NSTextField(labelWithString: "src"), outline: SailList.Outline(level: 2, expanded: true)))),
            ("inert", .item(SailList.Item(id: "k", name: "note", voice: "note", content: NSTextField(labelWithString: "note"), inert: true))),
            ("head", .head("Yours", count: 3)),
        ]
    }

    /// What a value holds, field by field, with views by kind, state, frame and what they hold, and
    /// other objects by kind: two rows alike read alike, whatever their views' addresses.
    static func shape(_ value: Any, depth: Int = 0) -> String {
        guard depth < 8 else { return "…" }
        if let view = value as? NSView {
            let held = view.subviews.map { shape($0, depth: depth + 1) }.joined(separator: ", ")
            let words = (view as? NSTextField).map { " says \"\($0.attributedStringValue.string)\"" } ?? (view as? NSButton).map { " titled \"\($0.title)\"" } ?? ""
            return "\(type(of: view))(hidden \(view.isHidden), alpha \(view.alphaValue), frame \(view.frame)\(words), [\(held)])"
        }
        if let menu = value as? NSMenu { return "NSMenu[\(menu.items.map(\.title).joined(separator: ", "))]" }
        if value is NSObject { return "\(type(of: value))" }
        let mirror = Mirror(reflecting: value)
        if mirror.displayStyle == .optional { return mirror.children.first.map { shape($0.value, depth: depth) } ?? "nil" }
        guard !mirror.children.isEmpty else { return String(describing: value) }
        return "\(type(of: value))(" + mirror.children.map { "\($0.label ?? "_"): \(shape($0.value, depth: depth + 1))" }.joined(separator: ", ") + ")"
    }

    /// A row view's and its cell's every stored property, and what VoiceOver hears of the row.
    static func fields(_ view: NSTableRowView, _ cell: NSView?) -> [String] {
        var fields = Mirror(reflecting: view).children.map { "row \($0.label ?? "_"): \(shape($0.value))" }
        fields += cell.map { cell in Mirror(reflecting: cell).children.map { "cell \($0.label ?? "_"): \(shape($0.value))" } } ?? []
        fields.append("voice \(view.accessibilityLabel() ?? "nil"), \(view.accessibilitySubrole()?.rawValue ?? "nil"), level \(view.accessibilityDisclosureLevel()), expanded \(view.isAccessibilityExpanded()), actions \(view.accessibilityCustomActions()?.map(\.name) ?? [])")
        return fields
    }

    /// A row of every kind made over for every other kind is, field by field and word for word,
    /// the row made fresh for it, in every list style, both looks and both directions, the earlier
    /// row chosen and hovered: a stored property `configure` forgets to renew fails here without
    /// being named.
    @Test(arguments: [RowStyle.list, .sidebar, .tree])
    func aRowOfEveryKindMadeOverForAnotherIsAFreshOne(_ style: RowStyle) throws {
        for look in [Look.light, .dark] {
            for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
                try madeOverAsFresh(style, look, direction)
            }
        }
    }

    private func madeOverAsFresh(_ style: RowStyle, _ look: Look, _ direction: NSUserInterfaceLayoutDirection) throws {
        let mono = try ControlsTests.mono(), kinds = Self.kinds(), said = "\(style == .list ? "list" : style == .sidebar ? "sidebar" : "tree"), \(look == .dark ? "dark" : "light"), \(direction == .rightToLeft ? "right to left" : "left to right")"
        func shown(_ entry: SailList.Entry) -> (SailList, TestWindow) {
            let list = SailList(rowStyle: style, look: look, mono: mono)
            list.userInterfaceLayoutDirection = direction
            list.pointer = { NSPoint(x: -1000, y: -1000) }
            list.entries = [entry]
            list.frame = NSRect(x: 0, y: 0, width: 440, height: 200)
            let (window, _) = Self.shown(list)
            list.layoutSubtreeIfNeeded()
            list.choose("k")
            list.layoutSubtreeIfNeeded()
            return (list, window)
        }
        func row(_ list: SailList) throws -> [String] {
            let view = try #require(list.rowView(atRow: 0, makeIfNecessary: true))
            let cell = list.view(atColumn: 0, row: 0, makeIfNecessary: true)
            view.layoutSubtreeIfNeeded()
            cell?.layoutSubtreeIfNeeded()
            return Self.fields(view, cell)
        }
        for (name, entry) in kinds {
            let (fresh, freshWindow) = shown(entry)
            defer { freshWindow.orderOut(nil) }
            let want = try row(fresh)
            for (before, earlier) in kinds where before != name {
                let (list, window) = shown(earlier)
                defer { window.orderOut(nil) }
                (list.rowView(atRow: 0, makeIfNecessary: true) as? SailRowView)?.hovered = true
                let reused = list.rowsReused
                list.entries = [entry]
                list.choose("k")
                list.layoutSubtreeIfNeeded()
                let got = try row(list)
                if case .item = entry, case .item = earlier { #expect(list.rowsReused > reused, "\(said): \(before) to \(name): the cell was not reused") }
                for (wanted, made) in zip(want, got) where wanted != made {
                    Issue.record("\(said): \(before), chosen and hovered, made over as \(name) differs from a fresh \(name):\n  fresh:  \(wanted)\n  reused: \(made)")
                }
                #expect(want.count == got.count)
            }
        }
    }

    /// A row's actions fade on a layer of their own, apart from the one its cell and words share.
    @Test func aRowsActionsFadeOnALayerOfTheirOwn() throws {
        let list = try Self.list(.list, look: .light, counts: Counts())
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        let row = try Self.row(list, 1)
        let cell = try #require(row.view(atColumn: 0) as? RowCell)
        #expect(cell.actions.wantsLayer && cell.actions.layer != nil && cell.actions.layer !== row.layer)
    }

    /// A row made over for another puts away its note's tooltip, shown while it was hovered.
    @Test func aRowMadeOverPutsAwayItsNotesTooltip() throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = [.item(SailList.Item(id: "a", name: "a", voice: "a", content: NSTextField(labelWithString: "a"), actions: [SailList.Action("Open") {}], note: "The workspace's user may not open it."))]
        list.frame = NSRect(x: 0, y: 0, width: 440, height: 120)
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.layoutSubtreeIfNeeded()
        let cell = try #require(list.view(atColumn: 0, row: 0, makeIfNecessary: true) as? RowCell)
        let tip = try #require(cell.noteTip)
        tip.onlyWhenCut = false
        tip.show()
        #expect(tip.isShown, "the note's tooltip did not show")
        cell.configure(SailList.Item(id: "b", name: "b", voice: "b", content: NSTextField(labelWithString: "b")), actionsColumn: nil, returnKey: true, look: .light)
        #expect(!tip.isShown, "the old note's tooltip stayed on screen over another row")
        let button = SailButton(icon: .refresh, label: "List Again", look: .light, target: nil, action: nil)
        button.frame = NSRect(x: 100, y: 10, width: 40, height: 40)
        list.addSubview(button)
        let next = try #require(button.tooltip)
        next.mouseEntered(with: try ControlsTests.entered())
        #expect(!next.isShown, "a tooltip put away with its row warmed the next to show at once, as only the pointer leaving one does")
        next.hide()
    }

    /// In a person's app two items handing the list one view do not stop it: the view stays with
    /// the row that asked last, and the other row's cell is made again from its own closure, or,
    /// when that closure hands back the same one, holds an empty view: blank until the next fill.
    @Test func inAPersonsAppTwoItemsSharingAViewGoOnSafely() throws {
        let mono = try ControlsTests.mono()
        SailList.stopsOnMisuse = false
        defer { SailList.stopsOnMisuse = true }
        let shared = NSView()
        var asked = 0
        func firstTheSharedOne() -> NSView {
            asked += 1
            return asked == 1 ? shared : NSView()
        }
        let one = RowCell(SailList.Item(id: "a", name: "a", voice: "a", content: firstTheSharedOne()), rowStyle: .list, actionsColumn: nil, look: .light, mono: mono)
        let other = RowCell(SailList.Item(id: "b", name: "b", voice: "b", content: shared), rowStyle: .list, actionsColumn: nil, look: .light, mono: mono)
        #expect(shared.superview === other, "the view did not stay with the row that asked last")
        #expect(one.content !== shared && one.content.superview === one && asked == 2, "the other row was not made again from its closure")
        let third = RowCell(SailList.Item(id: "c", name: "c", voice: "c", content: shared), rowStyle: .list, actionsColumn: nil, look: .light, mono: mono)
        #expect(shared.superview === third && other.content !== shared && other.content.superview === other, "a closure handing back the taken view left its row holding nothing")
    }

    /// A row with no actions, as every sidebar row is, opens no menu on a right-click.
    @Test func aRowWithNoActionsHasNoMenu() throws {
        let list = SailList(rowStyle: .sidebar, look: .light, mono: try ControlsTests.mono())
        list.entries = [.item(SailList.Item(id: "demo", name: "demo", voice: "demo", content: NSView()))]
        list.frame = NSRect(x: 0, y: 0, width: 235, height: 30)
        let row = try Self.row(list, 0)
        #expect(row.menu(for: try Self.key(0, "")) == nil)
    }

    /// The actions show on hover or choice and are gone at rest; the first is loud only on the
    /// chosen row; ⋯ carries the menu-only ones.
    @Test func actionsShowOnHoverOrChoice() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = true
        let list = try Self.list(.list, look: .light, counts: Counts())
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        let row = try Self.row(list, 1)
        let cell = try #require(row.view(atColumn: 0) as? RowCell)
        #expect(cell.actions.alphaValue == 0 && cell.actions.isHidden, "a row's actions at rest take clicks and the keys")
        row.hovered = true
        #expect(cell.actions.alphaValue == 1 && !cell.actions.isHidden)
        let titles = cell.actions.views.compactMap { $0 as? SailButton }.filter { !$0.isHidden }.map { "\($0.kind) \($0.title)" }
        #expect(titles == ["secondary Open", "quiet More"], "the hovered row's actions are \(titles)")
        row.hovered = false
        #expect(cell.actions.alphaValue == 0 && cell.actions.isHidden)
        list.choose("pier")
        #expect(cell.actions.alphaValue == 1)
        let chosen = cell.actions.views.compactMap { $0 as? SailButton }.filter { !$0.isHidden }.map { "\($0.kind) \($0.title)" }
        #expect(chosen == ["primary Open", "quiet More"], "the chosen row's actions are \(chosen)")
    }

    /// A note among a row's actions is cut short at its end where the actions column is narrow,
    /// never dropped and never past the row, and said in full on hover only while it is cut.
    @Test func aNoteIsCutShortAndSaidInFullOnlyWhenCut() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = true
        let note = "Only uday or an admin opens it"
        for (column, cut) in [(144.0, true), (Double?.none, false)] {
            let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
            list.actionsColumn = column
            list.entries = [.item(SailList.Item(id: "dock", name: "dock", voice: "dock", content: NSTextField(labelWithString: "dock"), actions: [
                SailList.Action("Copy Name", key: "c", modifiers: .command, onRow: false) {},
            ], note: note))]
            list.frame = NSRect(x: 0, y: 0, width: 440, height: 60)
            let (window, _) = Self.shown(list)
            defer { window.orderOut(nil) }
            list.choose("dock")
            let row = try Self.row(list, 0)
            let cell = try #require(row.view(atColumn: 0) as? RowCell)
            cell.layoutSubtreeIfNeeded()
            let label = try #require(cell.actions.views.compactMap { $0 as? NSTextField }.first { $0.stringValue == note }, "the note was dropped")
            let frame = cell.convert(label.alignmentRect(forFrame: label.frame), from: label.superview)
            #expect(!label.isHiddenOrHasHiddenAncestor && frame.width > 0 && frame.maxX <= cell.bounds.maxX + 0.5, "the note is at \(frame)")
            #expect(frame.height < 2 * Look.Step.footnote.points, "the note wraps to \(frame.height) pt")
            #expect(cell.noteTip?.text == note && cell.noteTip?.shows == cut, "\(column.map { "\($0) pt" } ?? "a free column"): the tip shows \(cell.noteTip?.shows == true)")
        }
    }

    /// Rows drawn again (new entries of the same list, a change of look) keep the row under the
    /// pointer hovered, its actions shown, though the pointer has not moved.
    @Test func aRedrawKeepsTheHoveredRow() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = true
        let list = try Self.list(.list, look: .light, counts: Counts())
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        try Self.row(list, 1).hovered = true
        for redraw in [{ list.entries = list.entries }, { list.look = .dark }] {
            redraw()
            let row = try Self.row(list, 1)
            let cell = try #require(row.view(atColumn: 0) as? RowCell)
            #expect(row.hovered && !cell.actions.isHidden, "a redraw dropped the hovered row's actions")
        }
    }

    /// A list of `count` rows under a head in a quiet scroll view `height` tall, in a window.
    static func scrolling(_ count: Int, height: Double) throws -> (SailList, QuietScrollView, NSWindow) {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = [.head("Yours", count: count)] + (1 ... count).map { index in
            .item(SailList.Item(id: "ws-\(index)", name: "ws-\(index)", voice: "ws-\(index)", content: NSTextField(labelWithString: "ws-\(index)")))
        }
        let scroll = QuietScrollView(look: .light)
        scroll.documentView = list
        scroll.frame = NSRect(x: 0, y: 0, width: 400, height: height)
        let rows = Double(list.rect(ofRow: list.numberOfRows - 1).maxY)
        list.frame = NSRect(x: 0, y: 0, width: 400, height: rows)
        let window = TestWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: height), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = scroll
        window.orderFront(nil)
        scroll.tile()
        return (list, scroll, window)
    }

    /// R2-2: a redraw lights the row under the pointer only where the pointer really is over it:
    /// over a row on screen, yes; over a foot below the scrolled list (a row scrolled out lies
    /// under it) or over a view laid over the list (the panel slid over the content), no.
    @Test func aRedrawLightsOnlyARowThePointerIsReallyOver() throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = (1 ... 30).map { .item(SailList.Item(id: "ws-\($0)", name: "ws-\($0)", voice: "ws-\($0)", content: NSTextField(labelWithString: "ws-\($0)"))) }
        let scroll = QuietScrollView(look: .light)
        scroll.documentView = list
        scroll.frame = NSRect(x: 0, y: 60, width: 400, height: 200)
        list.frame = NSRect(x: 0, y: 0, width: 400, height: Double(list.rect(ofRow: list.numberOfRows - 1).maxY))
        let foot = NSView(frame: NSRect(x: 0, y: 0, width: 400, height: 60)), over = NSView(frame: NSRect(x: 0, y: 160, width: 200, height: 100))
        let page = NSView(frame: NSRect(x: 0, y: 0, width: 400, height: 260))
        for view in [scroll, foot, over] {
            page.addSubview(view)
        }
        let window = TestWindow(contentRect: page.frame, styleMask: [.titled], backing: .buffered, defer: false)
        defer { window.orderOut(nil) }
        window.contentView = page
        window.orderFront(nil)
        func lit(at point: NSPoint) -> [Int] {
            list.pointer = { point }
            let same = list.entries
            list.entries = same
            return (0 ..< list.numberOfRows).filter { (list.rowView(atRow: $0, makeIfNecessary: false) as? SailRowView)?.hovered == true }
        }
        #expect(lit(at: NSPoint(x: 300, y: 200)).count == 1, "the row under the pointer was not lit")
        #expect(lit(at: NSPoint(x: 300, y: 30)) == [], "a row scrolled out under the foot was lit")
        #expect(lit(at: NSPoint(x: 100, y: 200)) == [], "a row under a view laid over the list was lit")
    }

    /// A tooltip up over a row is put away when the rows are drawn again (a refresh, a change of
    /// look): its row is a new one, and the old tooltip would stay up over the window, in the old look.
    @Test func aRedrawPutsAwayATooltipThatIsUp() throws {
        let list = SailList(rowStyle: .list, look: .light, mono: try ControlsTests.mono())
        list.entries = [.item(SailList.Item(id: "dock", name: "dock", voice: "dock", content: NSTextField(labelWithString: "dock"), note: "Only uday or an admin opens it"))]
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.choose("dock")
        let cell = try #require(try Self.row(list, 0).view(atColumn: 0) as? RowCell)
        let tip = try #require(cell.noteTip)
        tip.show()
        #expect(window.childWindows?.isEmpty == false, "the tooltip never showed")
        let same = list.entries
        list.entries = same
        #expect(window.childWindows?.isEmpty != false, "a tooltip stayed up over rows drawn again")
    }

    /// Home chooses the first item and shows the head above it, as AppKit's lists scroll to their
    /// very top; End chooses the last.
    @Test func homeShowsTheHeadAboveTheFirstItem() throws {
        let (list, scroll, window) = try Self.scrolling(30, height: 200)
        defer { window.orderOut(nil) }
        list.scrollRowToVisible(list.numberOfRows - 1)
        #expect(list.act(on: try Self.key(115, "\u{F729}")))
        #expect(list.chosen == "ws-1" && abs(Double(scroll.contentView.bounds.minY)) < 0.5, "Home left the list \(scroll.contentView.bounds.minY) pt down")
        #expect(list.act(on: try Self.key(119, "\u{F72B}")))
        #expect(list.chosen == "ws-30")
    }

    /// A mouse-down grabs the thumb across the track's width beside it, and nowhere above or below it
    /// on the track; the drag itself is driven in the app (`dragThumb`), since AppKit's own tracking
    /// loop cannot be run inside a test process.
    @Test func aMouseDownGrabsTheThumbOnlyBesideIt() throws {
        let (_, scroll, window) = try Self.scrolling(30, height: 200)
        defer { window.orderOut(nil) }
        let scroller = scroll.scroller, thumb = scroller.thumb
        try #require(thumb.height > 1, "nothing to grab: the thumb is \(thumb)")
        #expect(scroller.grabsThumb(at: NSPoint(x: thumb.midX, y: thumb.midY)) && scroller.grabsThumb(at: NSPoint(x: scroller.bounds.minX + 1, y: thumb.midY)))
        let away = thumb.maxY + 20 < scroller.bounds.maxY ? thumb.maxY + 20 : thumb.minY - 20
        #expect(!scroller.grabsThumb(at: NSPoint(x: thumb.midX, y: away)), "the track away from the thumb grabbed it")
    }

    /// A link given new words paints them as a link's: the words in ink, the underline in the
    /// accent under the words alone, 1 pt thick, below their baseline.
    @Test(arguments: [Look.light, .dark])
    func aLinkPaintsNewWordsInInkOverAnAccentUnderline(_ look: Look) {
        let link = LinkButton("Show dock", look: look, target: nil, action: nil)
        link.title = "Show pier"
        link.frame = NSRect(origin: .zero, size: link.intrinsicContentSize)
        let words = link.attributedTitle
        #expect(words.string == "Show pier" && (words.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? NSColor) == NSColor(look.text), "\(words)")
        let under = link.underline, title = link.cell?.titleRect(forBounds: link.bounds) ?? .zero
        #expect(near(under.height, 1) && under.width > 20 && under.width <= title.width + 0.5, "the underline is \(under) under \(title)")
        #expect(link.underlineInk == Tint(look.accent) && link.drawnMarks == [.accentLine])
    }

    /// The underline runs under the words from their leading edge, which is the trailing side of
    /// the title's rect when the layout runs right to left.
    @Test func aLinksUnderlineStartsAtItsLeadingEdgeInEitherDirection() {
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let link = LinkButton("Show dock", look: .light, target: nil, action: nil)
            link.userInterfaceLayoutDirection = direction
            link.frame = NSRect(x: 0, y: 0, width: link.intrinsicContentSize.width + 40, height: link.intrinsicContentSize.height)
            let under = link.underline, title = link.cell?.titleRect(forBounds: link.bounds) ?? .zero
            let leading = direction == .rightToLeft ? near(under.maxX, title.maxX) : near(under.minX, title.minX)
            #expect(leading && under.width < title.width, "\(direction == .rightToLeft ? "right to left" : "left to right"): the underline is \(under) in \(title)")
        }
    }

    /// N4: drawn, the underline runs under the words, pixel for pixel, in either direction: its first
    /// and last accent columns within a point and a half of the words' first and last ink columns
    /// (a glyph's side bearing), the link 40 pt wider than its words.
    @Test(arguments: [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft])
    func aLinksUnderlineIsUnderItsWordsByPixels(_ direction: NSUserInterfaceLayoutDirection) throws {
        let look = Look.light, scale = 2.0
        let link = LinkButton("Show dock", look: look, target: nil, action: nil)
        link.userInterfaceLayoutDirection = direction
        link.frame = NSRect(x: 0, y: 0, width: ceil(link.intrinsicContentSize.width) + 40, height: ceil(link.intrinsicContentSize.height) + 4)
        let image = try #require(Backdrop(NSColor(Tint(look.surface)), holding: link).drawn(scale: scale))
        let accent = [Double(look.accent.red), Double(look.accent.green), Double(look.accent.blue)]
        var words: [Int] = [], line: [Int] = []
        for x in 0 ..< image.pixelsWide {
            for y in 0 ..< image.pixelsHigh {
                guard let colour = image.colorAt(x: x, y: y)?.usingColorSpace(.sRGB) else { continue }
                let rgb = [colour.redComponent, colour.greenComponent, colour.blueComponent].map { Double($0) * 255 }
                if zip(rgb, accent).allSatisfy({ abs($0 - $1) <= 24 }) {
                    line.append(x)
                } else if 0.3 * rgb[0] + 0.59 * rgb[1] + 0.11 * rgb[2] < 110 {
                    words.append(x)
                }
            }
        }
        let side = direction == .rightToLeft ? "right to left" : "left to right"
        let ink = try #require(words.min().flatMap { min in words.max().map { (min, $0) } }, "\(side): no words drawn")
        let under = try #require(line.min().flatMap { min in line.max().map { (min, $0) } }, "\(side): no underline drawn")
        let slack = Int(1.5 * scale)
        #expect(abs(ink.0 - under.0) <= slack && abs(ink.1 - under.1) <= slack, "\(side): the words' ink spans pixels \(ink.0)–\(ink.1), the underline \(under.0)–\(under.1)")
    }

    /// A tree of `ids`, each row's actions all in its menu, as the files panel's are; `ran` hears
    /// which ran, and Reveal is on only while `revealing`.
    @MainActor final class Tree {
        let list: SailList
        var ran: [String] = []
        var revealing = false
        var dropped: [([String], String)] = []

        init(_ ids: [String]) throws {
            list = SailList(rowStyle: .tree, look: .light, mono: try ControlsTests.mono())
            list.entries = ids.map { id in
                .item(SailList.Item(id: id, name: id, voice: id, content: NSTextField(labelWithString: id), actions: [
                    SailList.Action("Rename", key: "\u{F705}", onRow: false) { [unowned self] in ran.append("Rename \(id)") },
                    SailList.Action("Reveal in Terminal", onRow: false, enabled: { [unowned self] in revealing }) { [unowned self] in ran.append("Reveal \(id)") },
                    SailList.Action("Delete", key: "\u{8}", modifiers: .command, onRow: false, startsGroup: true, destructive: true) { [unowned self] in ran.append("Delete \(id)") },
                ]))
            }
            list.frame = NSRect(x: 0, y: 0, width: 224, height: 96)
            list.moves = SailList.Moves(lands: { over in over == "notes" ? nil : over == "src" ? "src" : "" }, drop: { [unowned self] ids, into in dropped.append((ids, into)) })
        }
    }

    /// A tree row's actions are its ⋯ alone, 20 pt in the 24 pt row: gone at rest, there on the row
    /// under the pointer and on the chosen one.
    @Test func aTreeRowsActionsAreItsMoreButtonOnHoverOrChoice() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = true
        let tree = try Tree(["src", "README.md"])
        let (window, _) = Self.shown(tree.list)
        defer { window.orderOut(nil) }
        let row = try Self.row(tree.list, 1), cell = try #require(row.view(atColumn: 0) as? RowCell)
        #expect(cell.actions.isHidden, "a button stands on a row neither chosen nor under the pointer")
        row.hovered = true
        let buttons = cell.actions.views.compactMap { $0 as? SailButton }
        #expect(!cell.actions.isHidden && buttons.map(\.title) == ["More"] && buttons.allSatisfy { $0.size == .row }, "\(buttons.map(\.title))")
        row.hovered = false
        #expect(cell.actions.isHidden)
        tree.list.choose("README.md")
        #expect(!cell.actions.isHidden, "the chosen row has no ⋯")
    }

    /// An action that cannot be done now is dimmed in its menu and its key does nothing; the menu
    /// holds the same items, Delete under its own separator.
    @Test func anActionThatCannotBeDoneIsDimmedAndItsKeyDoesNothing() throws {
        let tree = try Tree(["src"])
        tree.list.choose("src")
        let menu = try #require(try Self.row(tree.list, 0).menu(for: try Self.key(0, "")))
        #expect(menu.items.map { $0.isSeparatorItem ? "-" : $0.title } == ["Rename", "Reveal in Terminal", "-", "Delete"])
        let reveal = try #require(menu.items.first { $0.title == "Reveal in Terminal" })
        #expect((reveal.target as? NSMenuItemValidation)?.validateMenuItem(reveal) == false, "Reveal is on with nothing to paste into")
        tree.revealing = true
        #expect((reveal.target as? NSMenuItemValidation)?.validateMenuItem(reveal) == true)
        #expect(tree.list.act(on: try Self.key(120, "\u{F705}", [.function])) && tree.ran == ["Rename src"], "F2 did not rename: \(tree.ran)")
        tree.revealing = false
        _ = (reveal.target as? NSObject)?.perform(reveal.action, with: reveal)
        #expect(tree.ran == ["Rename src"], "a dimmed action ran")
    }

    /// Rows dragged within a tree land where `moves` says, the whole list for its root, a note
    /// nowhere; a drag from elsewhere lands nowhere; a drop hands over the dragged rows' ids.
    @Test func aTreesRowsMoveByADragWithinIt() throws {
        let tree = try Tree(["src", "notes", "README.md"])
        let list = tree.list
        let writer = try #require(list.tableView(list, pasteboardWriterForRow: 2) as? NSPasteboardItem)
        #expect(writer.string(forType: SailList.rowType) == "README.md")
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("rows-\(UUID())"))
        pasteboard.clearContents()
        pasteboard.writeObjects([writer])
        let within = Dragging(source: list, pasteboard: pasteboard), outside = Dragging(source: NSView(), pasteboard: pasteboard)
        #expect(list.tableView(list, validateDrop: within, proposedRow: 0, proposedDropOperation: .on) == .move)
        #expect(list.tableView(list, validateDrop: within, proposedRow: 1, proposedDropOperation: .on) == [], "a drop over a note landed")
        #expect(list.tableView(list, validateDrop: outside, proposedRow: 0, proposedDropOperation: .on) == [], "a drag from outside landed")
        #expect(list.tableView(list, validateDrop: within, proposedRow: 2, proposedDropOperation: .above) == .move)
        #expect(list.tableView(list, acceptDrop: within, row: 0, dropOperation: .on) && list.tableView(list, acceptDrop: within, row: -1, dropOperation: .on))
        #expect(tree.dropped.map(\.0) == [["README.md"], ["README.md"]] && tree.dropped.map(\.1) == ["src", ""], "\(tree.dropped)")
        list.moves = nil
        #expect(list.tableView(list, pasteboardWriterForRow: 2) == nil, "a list that moves nothing began a drag")
    }

    /// What a drag says to a list: its source and its pasteboard; nothing else is asked.
    @MainActor final class Dragging: NSObject, @preconcurrency NSDraggingInfo {
        let draggingSource: Any?
        let draggingPasteboard: NSPasteboard

        init(source: Any, pasteboard: NSPasteboard) {
            draggingSource = source
            draggingPasteboard = pasteboard
        }

        var draggingDestinationWindow: NSWindow? { nil }
        var draggingSourceOperationMask: NSDragOperation { .move }
        var draggingLocation: NSPoint { .zero }
        var draggedImageLocation: NSPoint { .zero }
        var draggedImage: NSImage? { nil }
        var draggingSequenceNumber: Int { 1 }
        var draggingFormation: NSDraggingFormation {
            get { .default }
            set {}
        }
        var animatesToDestination: Bool {
            get { false }
            set {}
        }
        var numberOfValidItemsForDrop: Int {
            get { 1 }
            set {}
        }
        var springLoadingHighlight: NSSpringLoadingHighlight { .none }
        func slideDraggedImage(to _: NSPoint) {}
        func enumerateDraggingItems(
            options _: NSDraggingItemEnumerationOptions, for _: NSView?, classes _: [AnyClass], searchOptions _: [NSPasteboard.ReadingOptionKey: Any],
            using _: (NSDraggingItem, Int, UnsafeMutablePointer<ObjCBool>) -> Void
        ) {}
        func resetSpringLoading() {}
    }

    static func key(_ code: UInt16, _ characters: String, _ flags: NSEvent.ModifierFlags = []) throws -> NSEvent {
        try #require(NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: flags, timestamp: 0, windowNumber: 0, context: nil, characters: characters,
            charactersIgnoringModifiers: characters, isARepeat: false, keyCode: code
        ))
    }

    /// Home and End choose the first and last item, never a head; Return presses the chosen row's
    /// first action and ⌘C its Copy Name; VoiceOver reads the row and offers both actions; the
    /// row's menu holds both with their keys.
    @Test func theRowKeysMenuAndVoiceOverActions() throws {
        let counts = Counts()
        let list = try Self.list(.list, look: .light, counts: counts)
        let (window, _) = Self.shown(list)
        defer { window.orderOut(nil) }
        let holds = window.makeFirstResponder(list)
        #expect(holds)
        list.keyDown(with: try Self.key(119, "\u{F72B}"))
        #expect(list.chosen == "dock")
        list.keyDown(with: try Self.key(115, "\u{F729}"))
        #expect(list.chosen == "pier")
        #expect(!list.tableView(list, shouldSelectRow: 0) && list.tableView(list, typeSelectStringFor: nil, row: 2) == "dock")
        list.keyDown(with: try Self.key(36, "\r"))
        let copied = list.performKeyEquivalent(with: try Self.key(8, "c", .command))
        #expect(counts.opened == 1 && copied && counts.copied == 1)
        let row = try Self.row(list, 1)
        #expect(row.accessibilityLabel() == "pier, running")
        let offered = row.accessibilityCustomActions() ?? []
        #expect(offered.map { $0.name } == ["Open", "Copy Name"])
        _ = offered.first?.handler?()
        #expect(counts.opened == 2)
        let menu = try #require(row.menu(for: try Self.key(0, "")))
        #expect(menu.items.map { $0.title } == ["Open", "Copy Name"] && menu.items.last?.keyEquivalent == "c" && menu.items.last?.keyEquivalentModifierMask == .command)
        let more = SailButton(icon: .more, label: "More", size: .small, look: .light, target: nil, action: nil)
        let holder = NSView(frame: NSRect(x: 0, y: 0, width: 100, height: 100))
        more.frame = NSRect(x: 20, y: 40, width: 34, height: 34)
        holder.addSubview(more)
        let corner = more.convert(MenuOpener.corner(under: more), to: holder), box = more.alignmentRect(forFrame: more.frame)
        let first = Double(box.minY) - 2 - MenuOpener.menuPadding
        #expect(near(corner, NSPoint(x: Double(box.minX), y: first)), "⋯'s menu's first item goes at \(corner), not its padding and 2 pt under the box \(box) at its leading corner")
        more.userInterfaceLayoutDirection = .rightToLeft
        let mirrored = more.convert(MenuOpener.corner(under: more), to: holder)
        #expect(near(mirrored, NSPoint(x: Double(box.maxX), y: first)), "right to left, ⋯'s menu's first item goes at \(mirrored), not under the box's right corner")
    }

    /// An icon-only button's tooltip: not at once, shown within the platform's delay under the
    /// button, saying its label, and gone when the pointer leaves.
    @Test func anIconButtonsTooltipShowsAfterItsDelayAndGoes() async throws {
        let button = SailButton(icon: .refresh, label: "List Again", look: .light, target: nil, action: nil)
        button.frame = NSRect(x: 100, y: 100, width: 40, height: 40)
        let holder = NSView(frame: NSRect(x: 0, y: 0, width: 300, height: 200))
        holder.addSubview(button)
        let (window, _) = ControlsTests.window(holding: holder, on: .white)
        defer { window.orderOut(nil) }
        if let screen = window.screen?.visibleFrame { window.setFrameOrigin(NSPoint(x: screen.midX - 150, y: screen.midY - 100)) }
        let tooltip = try #require(button.tooltip)
        #expect(button.accessibilityHelp() == "List Again" && button.toolTip == nil)
        tooltip.mouseEntered(with: try ControlsTests.entered())
        #expect(!tooltip.isShown)
        let shown = await ControlsTests.until(3) { tooltip.isShown }
        #expect(shown)
        let anchor = window.convertToScreen(button.convert(button.alignmentRect(forFrame: button.bounds), to: nil))
        let chip = try #require(tooltip.chip.window).frame.insetBy(dx: TooltipChip.room, dy: TooltipChip.room)
        #expect((chip.maxY <= anchor.minY || chip.minY >= anchor.maxY) && abs(Double(chip.midX - anchor.midX)) <= 1, "the chip \(chip) is not under the button \(anchor)")
        tooltip.mouseExited(with: try ControlsTests.entered())
        #expect(!tooltip.isShown)
        let presses = Presses()
        button.target = presses
        button.action = #selector(Presses.press(_:))
        tooltip.mouseEntered(with: try ControlsTests.entered())
        let again = await ControlsTests.until(3) { tooltip.isShown }
        #expect(again)
        // A press here goes through performClick, not a mouse-down sent through the window:
        // NSButton's own tracking runs a nested event loop, and with it the test process ended
        // without a result. The drive's kit-the-more-click step clicks ⋯ for real, its chip showing.
        button.performClick(nil)
        #expect(presses.count == 1 && !tooltip.isShown, "a click pressed the button \(presses.count) times and left the chip shown: \(tooltip.isShown)")
    }

    /// A disabled button says why through the same tooltip, shown on hover and read by VoiceOver;
    /// an icon-only one says it in place of its label and drops its shortcut; with no reason, a
    /// labelled button says nothing and an icon-only one its label again, its shortcut only once it
    /// is enabled: the chip's key follows the button's key working, not a reason being said.
    @Test func aDisabledButtonSaysWhy() async throws {
        let mono = try ControlsTests.mono()
        let labelled = SailButton("New Workspace", kind: .secondary, look: .light, target: nil, action: nil)
        let icon = SailButton(icon: .plus, label: "New Workspace", kind: .secondary, look: .light, target: nil, action: nil)
        icon.shortcut("n", modifiers: .command, mono: mono)
        let holder = NSView(frame: NSRect(x: 0, y: 0, width: 400, height: 200))
        labelled.frame = NSRect(x: 40, y: 80, width: 160, height: 32)
        icon.frame = NSRect(x: 260, y: 80, width: 32, height: 32)
        holder.addSubview(labelled)
        holder.addSubview(icon)
        let (window, _) = ControlsTests.window(holding: holder, on: .white)
        defer { window.orderOut(nil) }
        #expect(labelled.tooltip == nil)
        for button in [labelled, icon] {
            button.isEnabled = false
            button.why = "demo has no template yet"
            let tooltip = try #require(button.tooltip)
            #expect(tooltip.text == "demo has no template yet" && button.accessibilityHelp() == "demo has no template yet")
            #expect(!tooltip.chip.showsHint, "a disabled button's chip still says its shortcut")
            tooltip.mouseEntered(with: try ControlsTests.entered())
            let shown = await ControlsTests.until(3) { tooltip.isShown }
            #expect(shown, "the reason never showed on hover")
            tooltip.mouseExited(with: try ControlsTests.entered())
            button.why = nil
        }
        #expect(labelled.tooltip?.isOn == false && icon.tooltip?.isOn == true && icon.tooltip?.text == "New Workspace")
        #expect(icon.tooltip?.chip.showsHint == false, "a disabled button with no reason says its shortcut")
        icon.isEnabled = true
        #expect(icon.tooltip?.chip.showsHint == true, "enabled again, the chip does not say its shortcut")
    }

    /// The chip's keycap trails its words, and leads them right to left.
    @Test func theChipsKeycapFollowsTheReadingDirection() throws {
        let look = Look.light, canvas = NSColor(Tint(look.canvas))
        let ink = ControlsTests.swatch(canvas, [NSColor(Tint(look.ink))], scale: 2)
        let keycap = ControlsTests.swatch(canvas, [NSColor(Tint(look.ink)), NSColor(Tint(look.onInk, alpha: TooltipStyle.keycapFill))], scale: 2)
        for direction in [NSUserInterfaceLayoutDirection.leftToRight, .rightToLeft] {
            let chip = TooltipChip("List Again", hint: "⌘R", look: look, mono: try ControlsTests.mono())
            chip.userInterfaceLayoutDirection = direction
            let box = chip.chip, room = TooltipChip.room, y = room + Double(box.height) / 2
            let end = room + Double(box.width) - TooltipStyle.trailing - 1.5, start = room + TooltipStyle.trailing + 1.5
            let keyAt = direction == .leftToRight ? end : start
            #expect(ControlsTests.tells(ControlsTests.colour(chip, on: canvas, at: NSPoint(x: keyAt, y: y), scale: 2), keycap, from: ink), "\(direction.rawValue): no keycap at \(keyAt)")
        }
    }

    /// A banner: the tone mixed into the surface, its edge the tone at 35%, its words to VoiceOver,
    /// its action at the trailing edge.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func eachBannerToneIsTheSheets(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        for tone in BannerTone.allCases {
            let action = SailButton("Try Again", kind: .secondary, size: .small, look: look, target: nil, action: nil)
            let banner = Banner(tone, title: "This runner is creating another workspace", line: "That takes about a minute.", action: action, look: look)
            banner.frame = NSRect(x: 0, y: 0, width: 440, height: 100)
            banner.frame.size.height = banner.intrinsicContentSize.height
            banner.layoutSubtreeIfNeeded()
            let paint = BannerPaint(tone, look: look)
            let fill = ControlsTests.colour(banner, on: canvas, at: NSPoint(x: 6, y: Double(banner.frame.height) - 4), scale: 2)
            let edge = ControlsTests.colour(banner, on: canvas, at: NSPoint(x: 0.5, y: Double(banner.frame.height) / 2), scale: 2)
            let filled = ControlsTests.swatch(canvas, [NSColor(Tint(paint.fill))], scale: 2)
            #expect(ControlsTests.tells(fill, filled, from: ControlsTests.swatch(canvas, [NSColor(Tint(look.surface))], scale: 2)), "\(name) \(tone): the fill is \(fill)")
            #expect(ControlsTests.tells(edge, ControlsTests.swatch(canvas, [NSColor(Tint(paint.fill)), NSColor(paint.edge)], scale: 2), from: filled), "\(name) \(tone): the edge is \(edge)")
            #expect(banner.accessibilityLabel() == "This runner is creating another workspace" && banner.accessibilityHelp() == "That takes about a minute.")
            let box = action.alignmentRect(forFrame: action.frame)
            #expect(abs(Double(banner.bounds.width - box.maxX) - 11) < 0.5 && abs(Double(box.minY) - 11) < 0.5, "\(name): the action is not 10 pt inside the edge: \(box)")
            #expect(Double(banner.frame.height) >= 11 + 18 + 2 + 17 + 11)
            banner.userInterfaceLayoutDirection = .rightToLeft
            banner.needsLayout = true
            banner.layoutSubtreeIfNeeded()
            let mirrored = action.alignmentRect(forFrame: action.frame)
            #expect(abs(Double(mirrored.minX) - 11) < 0.5, "\(name): right to left, the action is at \(mirrored)")
        }
    }

    /// A card over a terminal: drawn in its tone as a banner is, its mark in the leading padding,
    /// its words and both its actions inside its edge, the primary first, neither over the other,
    /// each reached by a click at its middle and 2 pt inside each edge, at most 420 pt wide, and the
    /// mark mirrored right to left; hidden, it offers nothing.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aCardHoldsItsWordsAndBothActionsInsideItsEdgeEachReached(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        let card = Card(look: look)
        card.show(
            "This terminal ended: Workspace dock of harbor was stopped by ana, after a long afternoon of work in it.",
            tone: .warning, button: "Open a New One", second: "Close"
        )
        let room = NSView(frame: NSRect(x: 0, y: 0, width: 600, height: 400))
        card.translatesAutoresizingMaskIntoConstraints = false
        room.addSubview(card)
        NSLayoutConstraint.activate([card.leadingAnchor.constraint(equalTo: room.leadingAnchor), card.topAnchor.constraint(equalTo: room.topAnchor)])
        room.layoutSubtreeIfNeeded()
        #expect(card.frame.width <= 420.5 && card.frame.width > 200, "\(name): the card is \(card.frame)")
        let buttons = card.subviews.flatMap(\.subviews).flatMap { [$0] + $0.subviews }.compactMap { $0 as? SailButton }.filter { !$0.isHidden }
        #expect(buttons.map(\.title) == ["Open a New One", "Close"] && card.offers == ["Open a New One", "Close"])
        let boxes = buttons.map { card.convert($0.alignmentRect(forFrame: $0.bounds), from: $0) }
        for box in boxes {
            #expect(card.bounds.insetBy(dx: 10, dy: 10).contains(box), "\(name): an action at \(box) is not inside the card's padding \(card.bounds)")
        }
        #expect(boxes[0].maxX <= boxes[1].minX, "\(name): the primary \(boxes[0]) is not before the secondary \(boxes[1])")
        #expect(abs(Double(boxes[0].minY - boxes[1].minY)) < 0.5)
        let words = try #require(card.subviews.flatMap(\.subviews).first { $0 is NSTextField })
        let wordsBox = card.convert(words.alignmentRect(forFrame: words.frame), from: words.superview)
        #expect(wordsBox.minX >= card.markBox.maxX && wordsBox.maxX <= card.bounds.maxX - 10 && wordsBox.maxY <= boxes[0].minY, "\(name): the words at \(wordsBox)")
        #expect(card.markBox.minX >= 12 && card.markBox.maxX <= wordsBox.minX)
        for (button, face) in zip(buttons, boxes) {
            let inner = face.insetBy(dx: 2, dy: 2)
            for point in [NSPoint(x: inner.midX, y: inner.midY), NSPoint(x: inner.minX, y: inner.midY), NSPoint(x: inner.maxX, y: inner.midY), NSPoint(x: inner.midX, y: inner.minY), NSPoint(x: inner.midX, y: inner.maxY)] {
                let hit = card.hitTest(room.convert(point, from: card))
                #expect(hit === button || hit?.isDescendant(of: button) == true, "\(name): a click at \(point) on \(button.title) reaches \(String(describing: hit))")
            }
        }
        // Its fill and edge are its layer's colours, not a drawing: a change of look paints nothing
        // over the panes, so they are read from the layer.
        let paint = BannerPaint(.warning, look: look)
        #expect(card.layer?.backgroundColor == NSColor(Tint(paint.fill)).cgColor && card.layer?.borderColor == NSColor(paint.edge).cgColor && card.layer?.borderWidth == 1,
                "\(name): the fill or edge is not the warning's")
        #expect(NSColor(Tint(paint.fill)).cgColor != NSColor(Tint(look.surface)).cgColor)
        _ = canvas
        card.userInterfaceLayoutDirection = .rightToLeft
        #expect(abs(Double(card.bounds.width - card.markBox.maxX) - Banner.padding.leading) < 0.5, "\(name): right to left, the mark is at \(card.markBox)")
        card.hide()
        #expect(card.offers.isEmpty && card.shown == nil)
    }

    /// The empty state: the tile's 8 pt grid on the surface, solid or dashed, the column at most
    /// 400 pt, and VoiceOver's order: the title, the body, then the action.
    @Test func theEmptyStateIsTheSheets() throws {
        let look = Look.light, canvas = NSColor(Tint(look.canvas))
        let action = SailButton("New Workspace", kind: .primary, size: .small, look: look, target: nil, action: nil)
        let empty = EmptyState(
            icon: .box, title: "No workspaces in harbor yet", body: "A workspace is your own copy of harbor on the runner.", actions: [action], look: look,
            titleFont: try TitleFont(directory: ControlsTests.fonts), mono: try ControlsTests.mono()
        )
        empty.frame = NSRect(x: 0, y: 0, width: 600, height: 300)
        empty.layoutSubtreeIfNeeded()
        let tile = empty.frames(width: 600).tile
        let line = ControlsTests.colour(empty, on: canvas, at: NSPoint(x: tile.minX + 9.5, y: tile.minY + 4), scale: 2)
        let between = ControlsTests.colour(empty, on: canvas, at: NSPoint(x: tile.minX + 12, y: tile.minY + 4), scale: 2)
        let surface = ControlsTests.swatch(canvas, [NSColor(Tint(look.surface))], scale: 2)
        #expect(ControlsTests.tells(line, ControlsTests.swatch(canvas, [NSColor(Tint(look.surface)), NSColor(look.gridLine)], scale: 2), from: surface) && ControlsTests.tells(between, surface, from: line))
        #expect(empty.body.frame.width <= 404 && empty.title.frame.maxY <= empty.body.frame.minY && empty.body.frame.maxY <= action.frame.minY)
        #expect((empty.title.attributedStringValue.attribute(.font, at: 0, effectiveRange: nil) as? NSFont)?.familyName == TitleFont.family)
        #expect(EmptyTilePaint(missing: true, look: look).dashed)
    }

    /// A body that wraps keeps every line: each label's frame is as tall as the label draws its
    /// words at the frame's width (an admin's three-line body once lost its last word).
    @Test func anEmptyStateKeepsEveryLineOfItsWords() throws {
        let empty = EmptyState(
            icon: .box, title: "No project is open to you yet",
            body: "Your sign-in may not create projects on 127.0.0.1. Once one is added, it appears on the left, and you can create your own workspaces in it.", actions: [],
            line: "signed in as uday · admin · 127.0.0.1", lineFirst: true, look: .light, titleFont: try TitleFont(directory: ControlsTests.fonts), mono: try ControlsTests.mono()
        )
        for width in [440.0, 404.0, 320.0] {
            empty.frame = NSRect(x: 0, y: 0, width: width, height: 400)
            empty.layoutSubtreeIfNeeded()
            for label in [empty.title, empty.body] {
                let drawn = try #require(label.cell).cellSize(forBounds: NSRect(x: 0, y: 0, width: label.frame.width, height: 10000)).height
                #expect(label.frame.height >= drawn - 0.5, "\(width) pt: \(label.stringValue.prefix(16))… is \(label.frame.height) pt, its words \(drawn)")
            }
            let line = try #require(empty.line)
            #expect(line.frame.height >= line.height(width: Double(line.frame.width)) - 0.5, "\(width) pt: the line is \(line.frame.height) pt")
        }
    }
}
