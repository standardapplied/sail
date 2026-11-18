import AppKit
import Foundation
@testable import SailControls
import SailLook
import Testing

/// List and sidebar rows, the tooltip, the banner and the empty state, drawn by AppKit and read
/// against swatches it composes the same way, each beside a wrong one; and their keys, menus and
/// VoiceOver actions.
@MainActor @Suite(.serialized) struct RowsTests {
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

    /// A list's rows: the head's tint, the hairline above a row and only one pixel of it; the
    /// chosen row's fill at full strength while its list has the keys and at half without them,
    /// with the 2 × 24 bar; hover's fill. At 1x and 2x, both looks.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func aListsRowsAreTheSheets(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        let list = try Self.list(.list, look: look, counts: Counts())
        let (window, backdrop) = Self.shown(list)
        defer { window.orderOut(nil) }
        let head = list.rect(ofRow: 0), pier = list.rect(ofRow: 1), dock = list.rect(ofRow: 2)
        #expect(head.height == 30 && pier.height == 53 && dock.height == 53, "\(name): rows \(head.height), \(pier.height), \(dock.height)")
        list.choose("dock")
        let chosen = try Self.row(list, 2)
        for scale in [1.0, 2.0] {
            let bare = ControlsTests.swatch(canvas, [], scale: scale), hover = ControlsTests.swatch(canvas, [NSColor(look.fillHover)], scale: scale)
            let tint = backdrop.colour(at: NSPoint(x: 200, y: head.midY), scale: scale)
            #expect(ControlsTests.tells(tint, hover, from: bare), "\(name) at \(scale)x: the head is \(tint)")
            let line = backdrop.colour(at: NSPoint(x: 200, y: pier.minY + 0.5), scale: scale), under = backdrop.colour(at: NSPoint(x: 200, y: pier.minY + 1.5), scale: scale)
            #expect(ControlsTests.tells(line, ControlsTests.swatch(canvas, [NSColor(look.gridLine)], scale: scale), from: bare) && ControlsTests.tells(under, bare, from: hover),
                    "\(name) at \(scale)x: the hairline \(line), under it \(under)")
            for emphasized in [true, false] {
                chosen.isEmphasized = emphasized
                let fill = backdrop.colour(at: NSPoint(x: 200, y: dock.midY), scale: scale), bar = backdrop.colour(at: NSPoint(x: 0.5, y: dock.midY), scale: scale)
                let full = ControlsTests.swatch(canvas, [NSColor(look.fillSelected)], scale: scale), half = ControlsTests.swatch(canvas, [NSColor(look.fillSelectedQuiet)], scale: scale)
                #expect(ControlsTests.tells(fill, emphasized ? full : half, from: emphasized ? half : full), "\(name) at \(scale)x, keys \(emphasized): the chosen fill is \(fill)")
                #expect(ControlsTests.tells(bar, ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: scale), from: emphasized ? full : half), "\(name): the bar is \(bar)")
                let outside = backdrop.colour(at: NSPoint(x: 0.5, y: dock.minY + 1.5 + 13), scale: scale)
                #expect(ControlsTests.tells(outside, emphasized ? full : half, from: ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: scale)), "\(name): the bar is longer than 24 pt")
            }
            let rest = try Self.row(list, 1)
            rest.hovered = true
            let hovered = backdrop.colour(at: NSPoint(x: 200, y: pier.midY), scale: scale)
            #expect(ControlsTests.tells(hovered, hover, from: bare), "\(name) at \(scale)x: the hovered row is \(hovered)")
            rest.hovered = false
        }
    }

    /// The sidebar's rows, as the left panel draws a project: 30 pt, in 6 pt from each side with no
    /// gap between rows, the hovered row's fill, the chosen row's fill and the bar at the list's own
    /// edge, 2 × 14.
    @Test(arguments: [("light", Look.light), ("dark", Look.dark)])
    func theSidebarsRowsAreTheSheets(_ name: String, _ look: Look) throws {
        let canvas = NSColor(Tint(look.canvas))
        let list = try Self.list(.sidebar, look: look, counts: Counts())
        list.entries = Array(list.entries.dropFirst())
        let (window, backdrop) = Self.shown(list)
        defer { window.orderOut(nil) }
        list.choose("dock")
        let pier = list.rect(ofRow: 0), dock = list.rect(ofRow: 1)
        #expect(pier.height == 30 && dock.minY == pier.maxY)
        let hovered = try Self.row(list, 0)
        hovered.hovered = true
        let chosen = try Self.row(list, 1)
        chosen.isEmphasized = true
        let bare = ControlsTests.swatch(canvas, [], scale: 2), hover = ControlsTests.swatch(canvas, [NSColor(look.fillHover)], scale: 2)
        let full = ControlsTests.swatch(canvas, [NSColor(look.fillSelected)], scale: 2)
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 5.5, y: dock.midY), scale: 2), bare, from: full), "\(name): the fill reaches into the margin")
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 6.5, y: dock.midY), scale: 2), full, from: bare), "\(name): the fill starts later than 6 pt in")
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 100, y: dock.minY + 0.5), scale: 2), full, from: bare), "\(name): a gap between the rows")
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 100, y: dock.midY), scale: 2), full, from: bare))
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 0.5, y: dock.midY), scale: 2), ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: 2), from: bare))
        let accent = ControlsTests.swatch(canvas, [NSColor(Tint(look.accent))], scale: 2)
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 0.5, y: dock.minY + 7.5), scale: 2), bare, from: accent), "\(name): the bar is longer than 14 pt")
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 0.5, y: dock.minY + 8.5), scale: 2), accent, from: bare), "\(name): the bar is shorter than 14 pt")
        let leadingCorner = backdrop.colour(at: NSPoint(x: 0.25, y: dock.minY + 8.25), scale: 2), trailingCorner = backdrop.colour(at: NSPoint(x: 1.75, y: dock.minY + 8.25), scale: 2)
        #expect(leadingCorner.near(accent) && !trailingCorner.near(accent), "\(name): the bar's corners are \(leadingCorner) and \(trailingCorner), not square at the edge and rounded away from it")
        #expect(ControlsTests.tells(backdrop.colour(at: NSPoint(x: 100, y: pier.midY), scale: 2), hover, from: bare), "\(name): the hovered row's fill")
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
        let tile = cell.convert(cell.tile.frame, to: row)
        let badge = cell.convert(pill.alignmentRect(forFrame: pill.frame), to: row)
        #expect(tile.minX == 31 && tile.width == 20 && abs(Double(tile.midY) - 15) <= 0.5, "the tile is at \(tile)")
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
        #expect(list.chosen == "w0" && scroll.contentView.bounds.origin.y == away, "new rows scrolled back to the chosen one")
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
        #expect(corner == NSPoint(x: Double(box.minX), y: first), "⋯'s menu's first item goes at \(corner), not its padding and 2 pt under the box \(box) at its leading corner")
        more.userInterfaceLayoutDirection = .rightToLeft
        let mirrored = more.convert(MenuOpener.corner(under: more), to: holder)
        #expect(mirrored == NSPoint(x: Double(box.maxX), y: first), "right to left, ⋯'s menu's first item goes at \(mirrored), not under the box's right corner")
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
    /// labelled button says nothing and an icon-only one its label again.
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
        #expect(labelled.tooltip?.isOn == false && icon.tooltip?.isOn == true && icon.tooltip?.text == "New Workspace" && icon.tooltip?.chip.showsHint == true)
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
}
