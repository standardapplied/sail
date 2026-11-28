import AppKit
@testable import SailControls
import SailKitTestSupport
import SailLook
@testable import SailShell
import SailWorkbench
import Testing

/// The title bar's strip right to left, and the one wait that owns the Workspaces card today. (The
/// band's geometry in a window is `WorkbenchLookTests`'.)
@MainActor @Suite struct TitleBarAndMarkTests {
    init() {
        ExitWatch.install()
    }

    /// Checking the session held at launch is Sail's mark drawing itself, "Connecting to" the
    /// control plane with no ellipsis; signed out the card holds nothing (the welcome stands in the
    /// section's place).
    @Test func checkingTheHeldSessionIsTheMarkDrawingItself() throws {
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        view.waitForSignIn(at: "sail.example.com")
        let mark = try #require(view.card.subviews.first as? BrandMark, "the card holds \(view.card.subviews)")
        #expect(view.card.subviews.count == 1 && mark.presentation == .drawing(label: "Connecting to sail.example.com") && mark.markSide == BrandMark.side)
        #expect(view.text == "Workspaces\nConnecting to sail.example.com" && view.asking)
        view.signedOut()
        #expect(view.card.subviews.isEmpty && !view.asking, "signed out, the card holds \(view.card.subviews)")
    }

    /// The asking mark's centre is the card's, exactly, in a window of either parity: the mark then
    /// centres what it draws on it.
    @Test(arguments: [720.0, 721.0])
    func theAskingMarkStandsOnTheCardsCentre(_ width: Double) throws {
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: 480), styleMask: [.borderless], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = view
        view.waitForSignIn(at: "sail.example.com")
        view.layoutSubtreeIfNeeded()
        let mark = try #require(view.card.subviews.first as? BrandMark, "the card holds \(view.card.subviews)")
        #expect(mark.frame.midX == view.card.bounds.midX, "in a window \(width) pt wide the mark \(mark.frame) stands off the card's centre \(view.card.bounds.midX)")
    }

    /// A redraw while asking, for a change of look or the same wait told again, keeps the mark that
    /// is drawing and its cycle: only its look changes.
    @Test func aRedrawWhileAskingKeepsTheMarkDrawingOn() throws {
        let motion = Motion.shared, was = motion.reduced
        defer { motion.reduced = was }
        motion.reduced = false
        let view = WorkspacesView(look: .light, title: try TitleFont(directory: LookTests.fonts), mono: try LabelFont(directory: LookTests.fonts))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 480), styleMask: [.borderless], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = view
        view.waitForSignIn(at: "sail.example.com")
        let mark = try #require(view.card.subviews.first as? BrandMark, "the card holds \(view.card.subviews)")
        let cycle = try #require(mark.sigma.animation(forKey: BrandMark.drawKey), "in a window, asking, the mark does not draw")
        view.look = .dark
        view.waitForSignIn(at: "sail.example.com")
        #expect(view.card.subviews.count == 1 && view.card.subviews.first === mark, "redrawn, the card holds \(view.card.subviews), not the mark it drew")
        #expect(mark.look == .dark && mark.sigma.animation(forKey: BrandMark.drawKey) === cycle, "redrawn, the mark is not in the dark look or began its cycle again")
    }

    /// Right to left is AppKit's one answer, the window's title bar's direction: whatever the
    /// strip's own direction was, the strip is placed and runs its tabs that way, its first tab
    /// `buttonsGap` from the window buttons.
    @Test func theStripRunsTheWayTheTitleBarIsLaidOut() throws {
        let shell = try WorkbenchLookTests.shell(.light)
        let window = shell.window
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        shell.select(.sessions)
        let strip = shell.thisMac.strip, titleBar = window.windowTitlebarLayoutDirection
        strip.userInterfaceLayoutDirection = titleBar == .rightToLeft ? .leftToRight : .rightToLeft
        shell.placeTheStrip()
        strip.show(WorkbenchLookTests.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        let buttons = try #require(ShellApp.buttons(of: window)), first = try #require(strip.tabs.first)
        let tab = first.convert(first.bounds, to: nil)
        let gap = titleBar == .rightToLeft ? Double(buttons.minX - tab.maxX) : Double(tab.minX - buttons.maxX)
        #expect(strip.userInterfaceLayoutDirection == titleBar, "the strip runs \(strip.userInterfaceLayoutDirection.rawValue), the title bar \(titleBar.rawValue)")
        #expect(abs(gap - ShellApp.buttonsGap) < 0.5, "the first tab \(tab) is \(gap) pt from the window buttons \(buttons)")
    }

    /// Right to left the strip runs from its right edge, beside the window buttons AppKit puts
    /// there: the first tab ends `leading` from it, the next tab and `+` follow leftward, and a
    /// dragged tab is placed by its middle read from that edge; scrolling, the count stands at the
    /// strip's left end and the leading fade at the right.
    @Test func rightToLeftTheTabsRunFromTheRight() throws {
        let strip = try Self.strip()
        strip.userInterfaceLayoutDirection = .rightToLeft
        strip.frame = NSRect(x: 0, y: 0, width: 600, height: ShellApp.titleRow)
        strip.leading = 16
        strip.show(WorkbenchLookTests.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        let first = try #require(strip.tabs.first), second = try #require(strip.tabs.last)
        let one = first.convert(first.bounds, to: strip), two = second.convert(second.bounds, to: strip)
        #expect(abs(Double(one.maxX) - 584) < 0.5, "the first tab ends at \(one.maxX), not 16 pt from the strip's right edge")
        #expect(two.maxX <= one.minX && strip.add.frame.maxX <= two.minX, "the tabs \(one) \(two) and + \(strip.add.frame) do not run leftward")
        var moved: (TabID, Int)?
        strip.onMove = { moved = ($0, $1) }
        first.dragging = true
        first.setFrameOrigin(NSPoint(x: second.frame.minX, y: first.frame.minY))
        first.onDrop(first)
        #expect(moved?.0 == TabID(1) && moved?.1 == 1, "the first tab dropped on the second's place moved to \(String(describing: moved))")
        strip.show(Self.items(12), selected: TabID(12))
        strip.layoutSubtreeIfNeeded()
        #expect(!strip.count.isHidden && abs(Double(strip.count.frame.minX) - Look.Space.s) < 0.5, "right to left the count stands at \(strip.count.frame)")
        #expect(!strip.fades.before.isHidden && strip.fades.before.frame.maxX > strip.fades.after.frame.maxX || strip.fades.after.isHidden,
                "right to left the leading fade \(strip.fades.before.frame) is not on the right")
        #expect(!strip.fades.before.solidOnTheLeft, "right to left the leading fade is solid on its left")
    }

    static func strip() throws -> TabStrip {
        TabStrip(look: .light, mono: try LabelFont(directory: LookTests.fonts))
    }

    /// `count` tabs titled as the drive's longest names run: "This Mac" and "This Mac 2" on.
    static func items(_ count: Int) -> [TabStrip.Item] {
        (1 ... count).map { TabStrip.Item(id: TabID($0), title: $0 == 1 ? "This Mac" : "This Mac \($0)", zoomed: false) }
    }

    /// The strip of a window at its usual 1180 pt: after the buttons and the gap, 8 pt in.
    static func shown(_ count: Int, selected: Int, width: Double = 1094) throws -> TabStrip {
        let strip = try Self.strip()
        strip.frame = NSRect(x: 0, y: 0, width: width, height: ShellApp.titleRow)
        strip.leading = Look.Space.s
        strip.show(Self.items(count), selected: TabID(selected))
        strip.layoutSubtreeIfNeeded()
        return strip
    }

    /// The tabs a person sees whole: in the lane and under no fade.
    static func visible(_ strip: TabStrip) -> [Int] {
        let fades = [strip.fades.before, strip.fades.after].filter { !$0.isHidden }.map(\.frame)
        return strip.tabs.indices.filter { index in
            let tab = strip.tabs[index], frame = tab.convert(tab.bounds, to: strip)
            return !tab.isHidden && strip.lane.bounds.contains(tab.frame) && !fades.contains { $0.intersects(frame) }
        }
    }

    /// Scenarios 1 and 2: three tabs as shipped, with no count and no fade; twelve at 124 pt from
    /// the first, the selected one shown, the trailing edge fading over the next tab, "12" at the
    /// strip's end, `+` after the tabs, and nothing drawn over anything else.
    @Test func twelveTabsShrinkTo124AndTheCountShows() throws {
        let three = try Self.shown(3, selected: 1)
        #expect(three.count.isHidden && three.fades.before.isHidden && three.fades.after.isHidden, "three tabs show the count or a fade")
        #expect(three.tabs.allSatisfy { !$0.isHidden && abs(Double($0.frame.width) - TabRow.widest) < 0.5 })
        let strip = try Self.shown(12, selected: 6)
        let whole = Self.visible(strip)
        #expect(whole == Array(0 ..< 7) && strip.tabs.filter { !$0.isHidden }.allSatisfy { abs(Double($0.frame.width) - TabRow.narrowest) < 0.5 }, "twelve tabs show \(whole) whole")
        #expect(!strip.count.isHidden && strip.count.attributedTitle.string == "12" && abs(Double(strip.count.frame.maxX) - (1094 - Look.Space.s)) < 0.5,
                "the count \(strip.count.attributedTitle.string) stands at \(strip.count.frame)")
        #expect(strip.fades.before.isHidden && !strip.fades.after.isHidden && strip.fades.after.solidOnTheLeft == false)
        let peek = try #require(strip.tabs[7].isHidden ? nil : strip.tabs[7], "the eighth tab shows nothing under the fade")
        let peekFrame = peek.convert(peek.bounds, to: strip), fade = strip.fades.after.frame
        #expect(peekFrame.minX < fade.maxX && peekFrame.maxX > strip.lane.frame.maxX, "the eighth tab \(peekFrame) is not cut under the fade \(fade)")
        let under = NSPoint(x: fade.midX, y: fade.midY)
        #expect(strip.fades.after.hitTest(under) == nil && strip.hitTest(under) === peek, "a click on the fade reaches \(String(describing: strip.hitTest(under)))")
        let controls = Self.visible(strip).map { strip.tabs[$0].convert(strip.tabs[$0].bounds, to: strip) } + [strip.add.frame, strip.count.frame]
        for (index, control) in controls.enumerated() {
            #expect(strip.bounds.contains(control), "\(control) is outside the strip")
            for other in controls[(index + 1)...] { #expect(!control.intersects(other), "\(control) is drawn over \(other)") }
        }
        #expect(strip.add.frame.minX > strip.lane.frame.maxX && strip.count.frame.minX - strip.add.frame.maxX >= TabRow.stretch, "+ \(strip.add.frame) is not after the tabs")
    }

    /// Scenario 7 and invariant 6: at the least window the strip shows one tab whole, the count says
    /// four, and `+` and the count stay inside it, apart.
    @Test func theLeastWindowShowsOneTabTheCountAndThePlus() throws {
        let strip = try Self.shown(4, selected: 4, width: Double(ShellApp.leastFrame.width) - 86)
        #expect(Self.visible(strip) == [3] && strip.count.attributedTitle.string == "4" && !strip.count.isHidden, "at the least window \(Self.visible(strip)) show")
        #expect(strip.bounds.contains(strip.add.frame) && strip.bounds.contains(strip.count.frame) && !strip.add.frame.intersects(strip.count.frame))
    }

    /// Scenario 5: two steps of a mouse's sideways wheel move the shown tabs by two and select
    /// none; past the end the strip stays at it; a title changing keeps where the scroll left the
    /// strip, and a tab chosen brings it into view.
    @Test func aSidewaysScrollMovesTheTabsAStepAndSelectsNone() throws {
        let strip = try Self.shown(12, selected: 6)
        var chosen: [TabID] = []
        strip.onSelect = { chosen.append($0) }
        func scroll(_ lines: Int32) throws {
            let made = try #require(CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 2, wheel1: 0, wheel2: lines, wheel3: 0))
            strip.scrollWheel(with: try #require(NSEvent(cgEvent: made)))
            strip.layoutSubtreeIfNeeded()
        }
        try scroll(-1)
        try scroll(-1)
        #expect(strip.row.first == 2 && Self.visible(strip).first == 2 && chosen.isEmpty, "two steps left the strip at \(strip.row.first), chose \(chosen)")
        #expect(!strip.fades.before.isHidden && !strip.fades.after.isHidden, "in the middle an edge does not fade")
        for _ in 0 ..< 10 { try scroll(-1) }
        #expect(strip.row.first == 5 && !strip.row.fadesAfter, "past the end the strip is at \(strip.row.first)")
        var retitled = Self.items(12)
        retitled[3] = TabStrip.Item(id: TabID(4), title: "make test", zoomed: false)
        strip.show(retitled, selected: TabID(6))
        strip.layoutSubtreeIfNeeded()
        #expect(strip.row.first == 5, "a title changing moved the strip to \(strip.row.first)")
        strip.show(retitled, selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        #expect(strip.row.first == 0 && Self.visible(strip).contains(0), "the first tab chosen is not brought into view")
        try scroll(1)
        #expect(strip.row.first == 0, "a step back from the start moved the strip to \(strip.row.first)")
    }

    static func bench(tabs: Int) throws -> (ShellApp, WorkbenchView) {
        let shell = try WorkbenchLookTests.shell(.light)
        let bench = shell.thisMac
        for index in 1 ... tabs { _ = bench.model.newTab(session: "mac-\(index)", place: .thisMac(folder: "/")) }
        bench.apply()
        return (shell, bench)
    }

    static func said(_ items: [NSMenuItem]) -> [String] {
        items.map { ($0.state == .on ? "✓ " : "") + $0.title + ($0.keyEquivalent.isEmpty ? "" : " ⌘" + $0.keyEquivalent) }
    }

    /// Scenarios 3 and 4: the count's menu lists the twelve tabs in the strip's order with their
    /// titles, ⌘1 to ⌘8 and ⌘9 on the twelfth, the selected checked, each with a 10 pt slot before
    /// its icon; a long title cut in the middle and whole in its tooltip; choosing the tenth selects
    /// it and the strip shows it.
    @Test func theCountsMenuListsEveryTabWithItsKey() throws {
        let (shell, bench) = try Self.bench(tabs: 12)
        let long = "linuxuser@lightgrid: ~/workspace/a/rather/long/path/to/a/project/with/a/deep/folder"
        bench.model.rename(bench.model.tabs[1].id, to: long)
        bench.apply()
        let menu = bench.tabsMenu()
        let titles = bench.model.tabs.map { bench.model.title(of: $0.id) }
        try #require(menu.items.count == 12)
        #expect(menu.items.map(\.toolTip) == titles)
        #expect(menu.items.map(\.keyEquivalent) == ["1", "2", "3", "4", "5", "6", "7", "8", "", "", "", "9"])
        #expect(menu.items.allSatisfy { $0.keyEquivalentModifierMask == .command && $0.target === bench })
        #expect(menu.items.map { $0.state == .on } == bench.model.tabs.map { $0.id == bench.model.selected })
        let cut = menu.items[1].title
        #expect(cut != long && cut.contains("\u{2026}") && cut.hasPrefix("linuxuser@") && cut.hasSuffix("folder"), "the long title is listed as \(cut)")
        #expect(NSAttributedString(string: cut, attributes: [.font: NSFont.menuFont(ofSize: 0)]).size().width <= WorkbenchView.listedTitleWidth)
        #expect(menu.items[2].title == titles[2])
        let image = try #require(menu.items[0].image)
        #expect(image.isTemplate && Double(image.size.width) > TabMark.slot + Look.Space.s, "the item's image \(image.size) holds no slot before its icon")
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        bench.strip.layoutSubtreeIfNeeded()
        menu.performActionForItem(at: 9)
        bench.strip.layoutSubtreeIfNeeded()
        #expect(bench.model.selected == bench.model.tabs[9].id && bench.strip.row.shows(9) && shell.rail.selected == .sessions,
                "choosing the tenth selected \(String(describing: bench.model.selected)), the strip from \(bench.strip.row.first)")
    }

    /// ⌘9 selects the last tab with eight tabs or fewer, where the last tab's item has its own
    /// number and no item holds ⌘9; ⌘8 with two tabs selects nothing.
    @Test func commandNineSelectsTheLastTabWithEightOrFewer() throws {
        let (shell, bench) = try Self.bench(tabs: 3)
        NSApp.mainMenu = shell.menu()
        shell.select(.sessions)
        func key(_ characters: String) throws -> NSEvent {
            try #require(NSEvent.keyEvent(
                with: .keyDown, location: .zero, modifierFlags: .command, timestamp: 0, windowNumber: 0, context: nil, characters: characters,
                charactersIgnoringModifiers: characters, isARepeat: false, keyCode: 0
            ))
        }
        bench.change { $0.selectTab(at: 0) }
        #expect(bench.performKeyEquivalent(with: try key("9")) && bench.model.selected == bench.model.tabs.last?.id, "⌘9 with three tabs selected \(String(describing: bench.model.selected))")
        bench.change { $0.selectTab(at: 0) }
        #expect(bench.performKeyEquivalent(with: try key("3")) && bench.model.selected == bench.model.tabs.last?.id)
        bench.change { $0.selectTab(at: 0) }
        #expect(!bench.performKeyEquivalent(with: try key("8")) && bench.model.selected == bench.model.tabs.first?.id, "⌘8 with three tabs selected a tab")
    }

    /// Invariant 4 and scenario 6: the Window menu lists every tab after its own items with the
    /// count's titles and keys; it follows a rename, a close, a move and a selection at once; ⌘1
    /// is its first tab's key; with no tab it lists none.
    @Test func theWindowMenuListsEveryTabAndFollowsEveryChange() throws {
        let (shell, bench) = try Self.bench(tabs: 12)
        let bar = shell.menu()
        let window = try #require(bar.items.first { $0.title == "Window" }?.submenu)
        func tabs() -> [NSMenuItem] {
            window.items.filter { $0.action == #selector(WorkbenchView.selectTab(_:)) }
        }
        #expect(Self.said(tabs()) == Self.said(bench.tabsMenu().items) && tabs().count == 12)
        let shipped = try #require(window.items.firstIndex { $0.title == "Show Next Tab" })
        #expect(window.items[shipped + 1].isSeparatorItem && window.items.count == shipped + 14)
        let ids = bench.model.tabs.map(\.id)
        bench.model.rename(ids[1], to: "deploy")
        bench.apply()
        let fifth = try #require(bench.model.tabs[4].layout.leaves.first)
        _ = bench.model.close(fifth)
        bench.apply()
        try #require(tabs().count == 11, "after a rename and a close the Window menu lists \(Self.said(tabs()))")
        #expect(tabs()[1].title == "deploy" && tabs().map(\.keyEquivalent) == ["1", "2", "3", "4", "5", "6", "7", "8", "", "", "9"],
                "after a rename and a close the Window menu lists \(Self.said(tabs()))")
        bench.change { $0.moveTab(ids[0], to: 3) }
        bench.change { $0.select(ids[2]) }
        #expect(tabs().map { $0.representedObject as? TabID } == bench.model.tabs.map(\.id) && tabs().filter { $0.state == .on }.map { $0.representedObject as? TabID } == [ids[2]])
        let one = try #require(NSEvent.keyEvent(
            with: .keyDown, location: .zero, modifierFlags: .command, timestamp: 0, windowNumber: 0, context: nil, characters: "1",
            charactersIgnoringModifiers: "1", isARepeat: false, keyCode: 0
        ))
        #expect(WorkbenchView.shortcut(for: one, in: bar, target: bench)?.representedObject as? TabID == bench.model.tabs.first?.id)
        bench.change { bench in
            for tab in bench.tabs { tab.layout.leaves.forEach { _ = bench.close($0) } }
        }
        #expect(tabs().isEmpty && window.items.count == shipped + 1, "with no tab the Window menu holds \(window.items.map(\.title))")
    }
}
