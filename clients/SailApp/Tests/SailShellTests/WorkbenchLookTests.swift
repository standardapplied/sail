import AppKit
import Foundation
import SailLook
import SailVT
@testable import SailShell
import SailTerminalView
import SailTestPasskey
import SailWorkbench
import Testing

/// The workbench's controls in the Sail look, never the system's accent (the runner's accent is
/// graphite, so each colour is asserted, not only seen); every command in the menu with the
/// table's key (W8); no pane's view added anywhere but its tab's view (W2, scenario 33).
@MainActor @Suite struct WorkbenchLookTests {
    static func shell(_ look: Look) throws -> ShellApp {
        let folder = FileManager.default.temporaryDirectory.appending(path: "sail-tabs-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let passkey = SoftwarePasskey(file: folder.appending(path: "passkey.json"))
        return try ShellApp(fonts: LookTests.fonts, helper: nil, look: look) { _ in passkey }
    }

    static func items() -> [TabStrip.Item] {
        [TabStrip.Item(id: TabID(1), title: "api", zoomed: false), TabStrip.Item(id: TabID(2), title: "This Mac", zoomed: true)]
    }

    @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theStripsTabsAreTheLooks(_ name: String, _ look: Look) throws {
        let shell = try Self.shell(look)
        let strip = shell.thisMac.strip
        strip.showsTabs = true
        strip.show(Self.items(), selected: TabID(1))
        let selected = try #require(strip.tabs.first), other = try #require(strip.tabs.last)
        #expect(strip.layer?.backgroundColor == NSColor(look.chrome).cgColor, "\(name): the strip is not chrome")
        #expect(selected.layer?.backgroundColor == NSColor.clear.cgColor, "\(name): the selected tab is filled")
        #expect(selected.label.textColor == NSColor(look.text))
        #expect(selected.label.font == NSFont.systemFont(ofSize: Look.Step.callout.points, weight: .semibold), "\(name): the selected title is not semibold")
        #expect(other.label.font == NSFont.systemFont(ofSize: Look.Step.callout.points, weight: .regular))
        strip.frame = NSRect(x: 0, y: 0, width: 600, height: ShellApp.bandHeight)
        strip.layoutSubtreeIfNeeded()
        selected.layoutSubtreeIfNeeded()
        #expect(strip.isFlipped && !selected.isFlipped, "\(name): the tab's own y no longer runs up from its bottom edge")
        let bar = selected.bar.frame, width = Double(selected.bounds.width)
        #expect(width > 2 * Look.contentRadius, "\(name): the tab was not laid out (\(width) pt wide)")
        #expect(abs(Double(bar.minY)) <= 0.5 && abs(Double(bar.height) - TabButton.barHeight) <= 0.5,
                "\(name): the bar is at y \(bar.minY), \(bar.height) pt high, not along the tab's bottom edge")
        #expect(abs(Double(bar.minX) - Look.contentRadius) <= 0.5 && abs(Double(bar.maxX) - (width - Look.contentRadius)) <= 0.5,
                "\(name): the bar runs \(bar.minX)–\(bar.maxX) in a tab \(width) pt wide")
        #expect(selected.bar.layer?.backgroundColor == NSColor(look.accent).cgColor, "\(name): the bar is not the accent")
        #expect(selected.drawnMarks == [.accentLine] && other.drawnMarks.isEmpty, "\(name): the tabs draw \(selected.drawnMarks) and \(other.drawnMarks)")
        #expect(!selected.bar.isHidden && other.bar.isHidden, "\(name): the bar marks the wrong tab")
        #expect(other.layer?.backgroundColor == NSColor.clear.cgColor)
        #expect(other.label.textColor == NSColor(look.textSecondary))
        #expect(selected.close.isHidden && other.close.isHidden, "\(name): a close button shows without the pointer on its tab")
        other.mouseEntered(with: try #require(NSEvent.otherEvent(
            with: .applicationDefined, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, subtype: 0, data1: 0, data2: 0
        )))
        #expect(other.layer?.backgroundColor == NSColor.clear.cgColor, "\(name): a hovered tab is filled")
        #expect(!other.close.isHidden)
        for button in [selected.close, other.close, strip.add] {
            #expect(!button.isBordered, "\(name): \(button.toolTip ?? "") draws the system's bezel")
            #expect(button.focusRingType == .none, "\(name): \(button.toolTip ?? "") draws the system's focus ring")
            #expect(button.contentTintColor == nil && button.image?.isTemplate == false, "\(name): \(button.toolTip ?? "") is a tinted template")
            let symbol = button === strip.add ? "plus" : "xmark"
            #expect(button.image === TabButton.symbol(symbol, accessibility: button.toolTip ?? "", colour: look.textSecondary), "\(name): \(button.toolTip ?? "") is not textSecondary")
            #expect(button.keyEquivalent.isEmpty)
            #expect(button.layer?.borderWidth == 0, "\(name): \(button.toolTip ?? "") is ringed unfocused")
            #expect(button.becomeFirstResponder())
            #expect(button.layer?.borderWidth == 2, "\(name): \(button.toolTip ?? "") shows no keyboard focus")
            #expect(button.layer?.borderColor == NSColor(look.accent).cgColor, "\(name): \(button.toolTip ?? "")'s focus is not the accent")
            #expect(button.resignFirstResponder())
            #expect(button.layer?.borderWidth == 0)
        }
        #expect(selected.layer.map { Double($0.cornerRadius) } == Look.contentRadius)
        #expect(selected.label.font == NSFont.systemFont(ofSize: 13, weight: .semibold))
        #expect(selected.toolTip == "api")
        #expect(!selected.mouseDownCanMoveWindow && strip.mouseDownCanMoveWindow, "\(name): a tab moves the window, or the strip's empty part does not")
        #expect(strip.accessibilityRole() == .tabGroup)
        #expect(selected.accessibilityRole() == .radioButton)
        #expect(selected.accessibilitySubrole() == TabButton.tabButton)
        var pressed: TabID?
        strip.onSelect = { pressed = $0 }
        #expect(other.accessibilityPerformPress())
        #expect(pressed == TabID(2), "VoiceOver's press selects no tab")
    }

    /// A tab's title is centred in the tab, to the point, at three widths, with the close button
    /// shown (the pointer on the tab) and hidden; a title too long for the tab is cut in the middle,
    /// still centred, clear of the close button and the zoom mark.
    @Test(arguments: [100.0, 180.0, 240.0])
    func aTabsTitleIsCentredInTheTab(_ width: Double) throws {
        let hover = try #require(NSEvent.otherEvent(
            with: .applicationDefined, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, subtype: 0, data1: 0, data2: 0
        ))
        for title in ["api", "linuxuser@lightgrid: ~/a/rather/long/working/directory"] {
            let tab = TabButton(id: TabID(1), look: .dark)
            tab.frame = NSRect(x: 0, y: 0, width: width, height: 20)
            tab.show(TabStrip.Item(id: TabID(1), title: title, zoomed: true), selected: true, look: .dark)
            for hovered in [false, true] {
                if hovered { tab.mouseEntered(with: hover) }
                tab.layoutSubtreeIfNeeded()
                let label = tab.label.alignmentRect(forFrame: tab.label.frame)
                #expect(tab.close.isHidden == !hovered)
                #expect(abs(label.midX - tab.bounds.midX) <= 0.5, "\(title) at \(width) pt, hovered \(hovered): the title's middle is \(label.midX), the tab's \(tab.bounds.midX)")
                #expect(label.minX >= tab.close.frame.maxX - 1 && label.maxX <= tab.bounds.maxX - TabButton.titleInset + 1, "\(title) at \(width) pt overlaps the close button or the zoom mark: \(label)")
            }
            #expect(tab.label.lineBreakMode == .byTruncatingMiddle)
        }
    }

    /// Mast's shell: one band across the window's top in the chrome (sampled under the buttons'
    /// inset, in the middle and at the end), the rail's top edge at the band's bottom, the pane's
    /// card beside it, and no view over a window button; in full screen the band is the window's
    /// own top row, the tabs 8 pt in, the rail under it. Both panes, both looks.
    @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theBandRunsAcrossTheTopAndTheRailStartsUnderIt(_ name: String, _ look: Look) throws {
        let shell = try Self.shell(look)
        let window = shell.window
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        for pane in [Rail.Item.thisMac, .account] {
            shell.select(pane)
            for full in [false, true] {
                shell.placeTheStrip(fullScreen: full)
                window.layoutIfNeeded()
                let root = try #require(window.contentView)
                let band = full ? ShellApp.bandHeight : Double(root.bounds.height - window.contentLayoutRect.maxY)
                let context = try #require(Self.drawn(root))
                let pixels = try #require(context.data).bindMemory(to: UInt8.self, capacity: context.width * context.height * 4)
                func isChrome(_ x: Double, _ y: Double, _ chrome: RGB) -> Bool {
                    let at = (Int(y * 2) * context.width + Int(x * 2)) * 4
                    let found = [pixels[at], pixels[at + 1], pixels[at + 2]], wanted = [chrome.red, chrome.green, chrome.blue]
                    return zip(found, wanted).allSatisfy { abs(Int($0) - Int($1)) <= 2 }
                }
                for x in [40.0, Double(root.bounds.width) / 2, Double(root.bounds.width) - 6] {
                    #expect(isChrome(x, band / 2, look.chrome), "\(name) \(pane) full \(full): the band at x \(x) is not the chrome")
                }
                #expect(isChrome(Rail.width / 2, band + 4, look.chrome), "\(name) \(pane) full \(full): the rail under the band is not the chrome")
                #expect(isChrome(4, Double(root.bounds.height) - 4, look.chrome), "\(name) \(pane) full \(full): the chrome at the bottom is not the band's one fill")
                let railTop = Double(root.bounds.height - shell.rail.frame.maxY)
                #expect(abs(railTop - band) < 0.5, "\(name) \(pane) full \(full): the rail starts at \(railTop), the band ends at \(band)")
                #expect(band >= 44, "\(name): the band is \(band) pt")
                guard !full else {
                    #expect(shell.thisMac.strip.leading == ShellApp.fullScreenInset)
                    continue
                }
                let views: [NSView] = [shell.rail, shell.thisMac, shell.account, shell.thisMac.strip]
                for kind in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
                    let button = try #require(window.standardWindowButton(kind))
                    let frame = button.convert(button.bounds, to: nil)
                    #expect(abs(Double(frame.midY) - (Double(root.bounds.height) - band / 2)) < 1.5, "\(name): \(kind) is not centred in the band")
                    for view in views where view.window != nil && !view.isHiddenOrHasHiddenAncestor {
                        #expect(!view.convert(view.bounds, to: nil).intersects(frame), "\(name) \(pane): \(type(of: view)) lies over \(kind)")
                    }
                }
                let strip = shell.thisMac.strip
                strip.show(Self.items(), selected: TabID(1))
                strip.layoutSubtreeIfNeeded()
                if pane == .thisMac {
                    let firstTab = try #require(strip.tabs.first)
                    let tabStart = Double(firstTab.convert(firstTab.bounds, to: nil).minX)
                    #expect(tabStart >= ShellApp.buttonsInset - 0.5 && tabStart <= ShellApp.buttonsInset + Look.Space.s, "\(name): the first tab starts at \(tabStart), not past the buttons' 96 pt inset")
                }
                let card = try #require(shell.thisMac.superview)
                #expect(card.layer.map { Double($0.cornerRadius) } == Look.contentRadius && card.layer?.masksToBounds == true, "\(name): the pane is not a card with 6 pt corners")
                #expect(abs(Double(card.frame.minX - shell.rail.frame.maxX) - ShellApp.cardMargin) < 0.5 && abs(Double(root.bounds.maxX - card.frame.maxX) - ShellApp.cardMargin) < 0.5
                    && abs(Double(card.frame.minY) - ShellApp.cardMargin) < 0.5, "\(name): the card's chrome margin is not 2 pt: \(card.frame)")
                let row = strip.convert(strip.bounds, to: nil)
                #expect(abs(Double(row.height) - band) < 1 && abs(Double(row.maxX) - Double(root.bounds.width)) < 1,
                        "\(name): the strip \(row) does not fill the band after the buttons (\(band) pt)")
            }
        }
    }

    static func drawn(_ view: NSView) -> CGContext? {
        view.display()
        let width = Int(view.bounds.width) * 2, height = Int(view.bounds.height) * 2
        guard width > 0, height > 0, let layer = view.layer, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.scaleBy(x: 2, y: 2)
        layer.render(in: context)
        return context
    }

    /// The rename field: no system focus ring and no box of its own; the tab's pill is the surface,
    /// ringed in the accent; the field's font and centring are the title's; its selection
    /// `textSelection` and its caret the accent.
    @Test(arguments: [("dark", Look.dark), ("light", Look.light)])
    func theRenameFieldIsTheLooks(_ name: String, _ look: Look) throws {
        let shell = try Self.shell(look)
        shell.select(.thisMac)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let strip = shell.thisMac.strip
        strip.show(Self.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        let tab = try #require(strip.tabs.first)
        tab.rename()
        let field = try #require(tab.field)
        let editor = try #require(field.currentEditor() as? NSTextView)
        #expect(field.focusRingType == .none)
        #expect(!field.isBezeled && !field.isBordered && !field.drawsBackground, "\(name): the field draws a box of its own")
        #expect(field.font == tab.label.font && field.alignment == .center, "\(name): the field's text is not set as the title's")
        #expect(tab.layer?.borderWidth == 2, "\(name): the tab is not ringed")
        #expect(tab.layer?.borderColor == NSColor(look.accent).cgColor, "\(name): the ring is not the accent")
        #expect(tab.layer?.backgroundColor == NSColor(look.surface).cgColor, "\(name): the pill is not the surface")
        #expect(editor.selectedTextAttributes[.backgroundColor] as? NSColor == NSColor(look.textSelection))
        #expect(editor.insertionPointColor == NSColor(look.accent))
    }

    /// The name does not jump when the field opens: its text's ink is the title's, within half a
    /// point in y, and both are centred in the tab.
    @Test func theRenameFieldsTextIsWhereTheTitleWas() throws {
        let shell = try Self.shell(.light)
        shell.select(.thisMac)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let strip = shell.thisMac.strip
        strip.show(Self.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        let tab = try #require(strip.tabs.first)
        #expect(!tab.bar.isHidden, "the selected tab shows no bar")
        let title = try #require(Self.ink(tab, except: Look.light.accent), "the title drew no ink")
        tab.rename()
        let editor = try #require(tab.field?.currentEditor() as? NSTextView)
        editor.setSelectedRange(NSRange(location: editor.string.utf16.count, length: 0))
        let text = try #require(Self.ink(tab, except: Look.light.accent), "the field drew no ink")
        let middle = Double(tab.bounds.midX)
        #expect(abs(text.minY - title.minY) <= 0.5 && abs(text.maxY - title.maxY) <= 0.5, "the text is at \(text), the title was at \(title)")
        #expect(abs(title.midX - middle) <= 1 && abs(text.midX - middle) <= 1, "the tab's middle is \(middle); the title's \(title.midX), the text's \(text.midX)")
    }

    /// A rename goes on when the field loses the keys to nothing (the window itself first responder,
    /// as when the title bar is laid out again) and through a change of appearance, and Escape then
    /// restores the name; the keys taken by another view, the name typed is kept.
    @Test func aRenameOutlivesLosingTheKeysToNothingAndEscapeRestoresTheName() async throws {
        let shell = try Self.shell(.light)
        shell.select(.thisMac)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let strip = shell.thisMac.strip
        strip.show(Self.items(), selected: TabID(1))
        strip.layoutSubtreeIfNeeded()
        var renamed: [String?] = []
        strip.onRename = { _, name in renamed.append(name) }
        let tab = try #require(strip.tabs.first)
        func type(_ text: String) throws {
            let editor = try #require(tab.field?.currentEditor() as? NSTextView)
            editor.selectAll(nil)
            editor.insertText(text, replacementRange: editor.selectedRange())
        }
        tab.rename()
        try type("build")
        shell.window.makeFirstResponder(nil)
        await Self.mainQueueTurns()
        #expect(strip.isRenaming, "the rename ended when the field lost the keys to nothing")
        shell.window.appearance = NSAppearance(named: .darkAqua)
        shell.apply(.dark)
        shell.window.displayIfNeeded()
        await Self.mainQueueTurns()
        #expect(strip.isRenaming, "a change of appearance ended the rename")
        let editor = try #require(tab.field?.currentEditor() as? NSTextView)
        #expect(editor.string == "build")
        editor.doCommand(by: #selector(NSResponder.cancelOperation(_:)))
        #expect(!strip.isRenaming && renamed == [nil], "Escape did not restore the name: \(renamed)")
        #expect(tab.label.stringValue == "api" && !tab.label.isHidden)
        tab.rename()
        try type("deploy")
        let other = NSTextField()
        shell.window.contentView?.addSubview(other)
        shell.window.makeFirstResponder(other)
        await Self.mainQueueTurns()
        #expect(!strip.isRenaming && renamed.last == "deploy", "the keys taken by another view, the name typed was not kept: \(renamed)")
    }

    /// A rename under way in a background tab goes on through redraws that choose no tab (a focus
    /// change, a shell ending in another tab, the selected tab closing, another tab's close button)
    /// and Escape then keeps nothing; another tab chosen ends it with the name typed.
    @Test func aBackgroundTabsRenameOutlivesRedrawsThatChooseNoTab() async throws {
        let shell = try Self.shell(.light)
        shell.select(.thisMac)
        shell.window.orderFront(nil)
        defer { shell.window.orderOut(nil) }
        let bench = shell.thisMac
        let place = Place.thisMac(folder: "/")
        let panes = ["a", "b", "c", "d", "e"].map { bench.model.newTab(session: "mac-\($0)", place: place) }
        bench.apply()
        let ids = bench.model.tabs.map(\.id)
        let strip = bench.strip
        strip.layoutSubtreeIfNeeded()
        let tab = try #require(strip.tabs.first)
        func type(_ text: String) throws {
            let editor = try #require(tab.field?.currentEditor() as? NSTextView)
            editor.selectAll(nil)
            editor.insertText(text, replacementRange: editor.selectedRange())
        }
        tab.rename()
        try type("build")
        bench.apply()
        _ = bench.model.close(panes[1])
        bench.apply()
        _ = bench.model.close(panes[4])
        bench.apply()
        let selected = bench.model.selected
        strip.onClose(ids[2])
        await Self.mainQueueTurns()
        #expect(strip.isRenaming, "a redraw that chose no tab ended the background tab's rename")
        #expect(bench.model.selected == selected, "another tab's close button selected it")
        let editor = try #require(tab.field?.currentEditor() as? NSTextView)
        #expect(editor.string == "build")
        editor.doCommand(by: #selector(NSResponder.cancelOperation(_:)))
        await Self.mainQueueTurns()
        #expect(!strip.isRenaming && bench.model.tabs.first?.name == nil, "Escape kept a name: \(String(describing: bench.model.tabs.first?.name))")
        tab.rename()
        try type("deploy")
        strip.onSelect(ids[2])
        await Self.mainQueueTurns()
        #expect(!strip.isRenaming && bench.model.tabs.first?.name == "deploy", "another tab chosen did not keep the name typed")
    }

    /// Closing a tab that is not the one shown, by its X, names it in the question, as the strip
    /// names it; the shown tab is "this tab", or "this terminal" when it holds one.
    @Test func theCloseQuestionNamesATabThatIsNotShown() throws {
        let shell = try Self.shell(.light)
        let bench = shell.thisMac
        let place = Place.thisMac(folder: "/")
        _ = bench.model.newTab(session: "mac-api", place: place)
        _ = bench.model.newTab(session: "mac-shown", place: place)
        let behind = try #require(bench.model.tabs.first)
        let shown = try #require(bench.model.tabs.last)
        bench.model.rename(behind.id, to: "api")
        #expect(bench.closeQuestion(for: behind, terminals: 1) == "Close api?")
        #expect(bench.closeQuestion(for: behind, terminals: 2) == "Close api?")
        #expect(bench.closeQuestion(for: shown, terminals: 1) == "Close this terminal?")
        #expect(bench.closeQuestion(for: shown, terminals: 2) == "Close this tab?")
    }

    /// A change of look reaches every pane, a background tab's as the shown one's: the spec's
    /// "every tab, focused or not".
    @Test func aBackgroundTabsPanesTakeTheNewLook() throws {
        let shell = try Self.shell(.dark)
        let bench = shell.thisMac
        let place = Place.thisMac(folder: "/")
        let ids = ["behind", "shown"].map { bench.model.newTab(session: "mac-\($0)", place: place) }
        let views = try ids.map { id in
            let pane = try #require(bench.model.panes[id])
            return try #require(bench.makeView(pane, start: .attach, points: 15))
        }
        let behind = try #require(views[0].superview)
        behind.isHidden = true
        #expect(behind.isHidden && views[1].superview?.isHidden == false, "the background tab's view is not the only one hidden")
        bench.look = .light
        for (name, view) in zip(["the background tab's", "the shown tab's"], views) {
            #expect(view.look == .light, "\(name) pane kept the dark look")
            #expect(view.layer?.backgroundColor == NSColor(Look.light.palette.background).cgColor, "\(name) pane is not drawn light")
        }
    }

    /// The window's first frame fits its screen: the designed 1180 × 800 on a 1024 × 743 visible
    /// frame is 1024 × 743, and a frame that fits is kept.
    @Test func theWindowsFirstFrameFitsItsScreen() {
        let visible = NSRect(x: 0, y: 25, width: 1024, height: 743)
        #expect(ShellApp.fitted(NSRect(x: 10, y: 20, width: 1180, height: 800), in: visible) == NSRect(x: 10, y: 20, width: 1024, height: 743))
        #expect(ShellApp.fitted(NSRect(x: 10, y: 20, width: 900, height: 600), in: visible) == NSRect(x: 10, y: 20, width: 900, height: 600))
    }

    /// Turns of the main queue, each after whatever was queued before it.
    static func mainQueueTurns(_ count: Int = 3) async {
        for _ in 0 ..< count {
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in DispatchQueue.main.async { done.resume() } }
        }
    }

    /// The rectangle, in points from the top left, of `view`'s mostly opaque pixels darker than
    /// `limit` (red, green and blue summed, of 765): the text, in the light look. Drawn at 2x as
    /// AppKit draws views (`cacheDisplay`): a layer's render left out the title's text. The pixels
    /// of `except` are left out: a glyph's ink, not a mark of that colour beside it.
    static func ink(_ view: NSView, darkerThan limit: Double = 300, except: RGB? = nil) -> CGRect? {
        view.layoutSubtreeIfNeeded()
        guard view.bounds.width > 0, view.bounds.height > 0, let image = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: Int(view.bounds.width * 2), pixelsHigh: Int(view.bounds.height * 2), bitsPerSample: 8,
            samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        ) else { return nil }
        image.size = view.bounds.size
        view.cacheDisplay(in: view.bounds, to: image)
        let scale = Double(image.pixelsWide) / Double(view.bounds.width)
        var found: (minX: Int, minY: Int, maxX: Int, maxY: Int)?
        for y in 0 ..< image.pixelsHigh {
            for x in 0 ..< image.pixelsWide {
                guard let colour = image.colorAt(x: x, y: y)?.usingColorSpace(.sRGB), colour.alphaComponent > 0.5,
                      Double(colour.redComponent + colour.greenComponent + colour.blueComponent) * 255 < limit,
                      !Self.matches(colour, except)
                else { continue }
                found = found.map { (minX: min($0.minX, x), minY: min($0.minY, y), maxX: max($0.maxX, x), maxY: max($0.maxY, y)) } ?? (minX: x, minY: y, maxX: x, maxY: y)
            }
        }
        return found.map {
            CGRect(x: Double($0.minX) / scale, y: Double($0.minY) / scale, width: Double($0.maxX - $0.minX + 1) / scale, height: Double($0.maxY - $0.minY + 1) / scale)
        }
    }

    /// Whether `colour` is `rgb`, within 12 levels a channel: room for the bitmap's colour space,
    /// while a title's antialiased ink comes no nearer than 74 levels to the bar's.
    static func matches(_ colour: NSColor, _ rgb: RGB?) -> Bool {
        guard let rgb else { return false }
        let found = [colour.redComponent, colour.greenComponent, colour.blueComponent].map { Int((Double($0) * 255).rounded()) }
        return zip(found, [Int(rgb.red), Int(rgb.green), Int(rgb.blue)]).allSatisfy { abs($0 - $1) <= 12 }
    }

    /// A change of look recolours a tab's dividers where they are: nothing is laid out again, and
    /// the line takes the new look's colour at once.
    @Test func aChangeOfLookRecoloursTheDividersWhereTheyAre() throws {
        var bench = Workbench()
        _ = bench.newTab(session: "mac-left", place: .thisMac(folder: "/"))
        let size = CGSize(width: 800, height: 600)
        let right = bench.split(.sideBySide, session: "mac-right", place: .thisMac(folder: "/"), in: size, minimum: CGSize(width: 100, height: 100), scale: 2)
        try #require(right != nil, "the tab did not split")
        let dividers = bench.frames(in: size, scale: 2).dividers
        try #require(dividers.count == 1)
        let room = TabView(frame: NSRect(origin: .zero, size: size))
        room.show(dividers, look: .dark)
        let divider = try #require(room.subviews.compactMap { $0 as? DividerView }.first)
        let frame = divider.frame, line = divider.line.frame
        #expect(divider.line.backgroundColor == NSColor(Look.dark.separatorStrong).cgColor)
        room.paint(.light)
        #expect(divider.line.backgroundColor == NSColor(Look.light.separatorStrong).cgColor, "the divider kept the dark look's colour")
        #expect(near(divider.frame, frame) && near(divider.line.frame, line), "a change of look moved the divider")
    }

    @Test func theScrimIsTheTerminalBackgroundAt30PercentAndTakesNoClick() throws {
        let shell = try Self.shell(.dark)
        var bench = Workbench()
        let id = bench.newTab(session: "mac-scrim", place: .thisMac(folder: "/"))
        let pane = try TerminalPane(
            try #require(bench.panes[id]), start: .attach, host: shell.thisMac.host, runner: nil, fonts: TerminalFonts(directory: LookTests.fonts),
            points: 15, look: .dark
        )
        let scrim = try #require(pane.subviews.first { $0 is Scrim })
        #expect(scrim.layer?.backgroundColor == NSColor(Look.dark.palette.background).withAlphaComponent(0.3).cgColor)
        #expect(!pane.isDimmed && scrim.isHidden)
        pane.isDimmed = true
        #expect(!scrim.isHidden)
        #expect(scrim.hitTest(NSPoint(x: 10, y: 10)) == nil)
    }

    /// The look changes while the pane runs: its background and scrim take the other palette's.
    @Test func aPaneTakesTheOtherLooksBackgroundAndScrimAtOnce() throws {
        let shell = try Self.shell(.dark)
        var bench = Workbench()
        let id = bench.newTab(session: "mac-look", place: .thisMac(folder: "/"))
        let pane = try TerminalPane(
            try #require(bench.panes[id]), start: .attach, host: shell.thisMac.host, runner: nil, fonts: TerminalFonts(directory: LookTests.fonts),
            points: 15, look: .dark
        )
        let scrim = try #require(pane.subviews.first { $0 is Scrim })
        #expect(pane.layer?.backgroundColor == NSColor(Look.dark.palette.background).cgColor)
        pane.look = .light
        #expect(pane.layer?.backgroundColor == NSColor(Look.light.palette.background).cgColor)
        #expect(scrim.layer?.backgroundColor == NSColor(Look.light.palette.background).withAlphaComponent(0.3).cgColor)
    }

    /// W8: every command is a menu item with the table's key and modifiers.
    @Test func everyCommandHasItsMenuItemAndTheTablesKey() throws {
        let shell = try Self.shell(.dark)
        func items(_ menu: NSMenu) -> [NSMenuItem] {
            menu.items.flatMap { [$0] + ($0.submenu.map(items) ?? []) }
        }
        let all = items(shell.menu())
        func arrow(_ key: Int) -> String {
            UnicodeScalar(UInt16(key)).map { String(Character($0)) } ?? ""
        }
        let command: NSEvent.ModifierFlags = .command
        var table: [(String, String, NSEvent.ModifierFlags)] = [
            ("New Tab", "t", command), ("Split Right", "d", command), ("Split Down", "D", [.command, .shift]), ("Close", "w", command),
            ("Close Tab", "w", [.command, .option]), ("Close Window", "W", [.command, .shift]), ("Cut", "x", command), ("Copy", "c", command),
            ("Paste", "v", command), ("Select All", "a", command), ("Bigger", "+", command), ("Smaller", "-", command), ("Actual Size", "0", command),
            ("Rename Tab", "", command), ("Show Latency", "l", [.command, .option]), ("Minimize", "m", command),
            ("Zoom Split", "\r", [.command, .shift]), ("Select Previous Split", "[", command), ("Select Next Split", "]", command),
            ("Select Split Above", arrow(NSUpArrowFunctionKey), [.command, .option]), ("Select Split Below", arrow(NSDownArrowFunctionKey), [.command, .option]),
            ("Select Split Left", arrow(NSLeftArrowFunctionKey), [.command, .option]), ("Select Split Right", arrow(NSRightArrowFunctionKey), [.command, .option]),
            ("Equalize Splits", "=", [.command, .control]), ("Move Divider Up", arrow(NSUpArrowFunctionKey), [.command, .control]),
            ("Move Divider Down", arrow(NSDownArrowFunctionKey), [.command, .control]), ("Move Divider Left", arrow(NSLeftArrowFunctionKey), [.command, .control]),
            ("Move Divider Right", arrow(NSRightArrowFunctionKey), [.command, .control]), ("Show Previous Tab", "{", [.command, .shift]),
            ("Show Next Tab", "}", [.command, .shift]), ("Select Last Tab", "9", command),
        ]
        for number in 1 ... 8 {
            table.append(("Select Tab \(number)", "\(number)", command))
        }
        for (title, key, modifiers) in table {
            let found = all.filter { $0.title == title }
            #expect(found.contains { $0.keyEquivalent == key && $0.keyEquivalentModifierMask == modifiers }, "\(title) is not in the menu with its key")
        }
        #expect(!all.contains { $0.title.hasSuffix("…") || $0.title.hasSuffix("...") }, "a menu item ends in an ellipsis")
        #expect(!all.contains { $0.title == "Close" && $0.keyEquivalent == "w" && $0.action == #selector(NSWindow.performClose(_:)) }, "⌘W still closes the window")
    }

    /// P1: each command a context menu offers is a menu bar item with the same action and target,
    /// so both are one action validated alike; a context menu's item shows no key (Apple's
    /// guidelines), and with nothing to act on the workbench's commands are left out, not dimmed.
    @Test func everyContextMenuCommandIsTheMenuBarsOwn() throws {
        let shell = try Self.shell(.dark)
        func items(_ menu: NSMenu) -> [NSMenuItem] {
            menu.items.flatMap { [$0] + ($0.submenu.map(items) ?? []) }
        }
        let bar = items(shell.menu())
        let workbench = shell.thisMac
        let offered: [(Command, AnyObject?)] = [
            (.copy, nil), (.paste, nil), (.selectAll, nil), (.splitRight, workbench), (.splitDown, workbench), (.zoomSplit, workbench),
            (.close, workbench), (.renameTab, workbench), (.closeTab, workbench), (.closeOtherTabs, workbench), (.newTab, workbench),
        ]
        for (command, target) in offered {
            let context = command.item(target: target, keyed: false)
            #expect(context.keyEquivalent.isEmpty, "\(command.title) shows a key in a context menu")
            #expect(bar.contains { $0.action == context.action && $0.target === context.target && $0.title == command.title }, "\(command.title) is not the menu bar's own")
        }
        let pane = workbench.paneMenu().items.map { $0.isSeparatorItem ? "-" : $0.title }
        #expect(pane == ["Copy", "Paste", "Select All"], "with no terminal, only the edit commands, dimmed by the responder chain: \(pane)")
        #expect(workbench.tabMenu(for: TabID(1)).items.map(\.title) == ["New Tab"])
    }

    /// The workbench takes its own keys before the menu bar: each finds its menu item, shifted
    /// keys by what the keyboard sends (`charactersIgnoringModifiers` "D", "{"), arrows by their
    /// function-key characters; a key of no workbench item finds none.
    @Test func eachShortcutFindsItsMenuItem() throws {
        let shell = try Self.shell(.dark)
        let menu = shell.menu()
        func key(_ characters: String, _ flags: NSEvent.ModifierFlags) throws -> NSEvent {
            try #require(NSEvent.keyEvent(
                with: .keyDown, location: .zero, modifierFlags: flags, timestamp: 0, windowNumber: 0, context: nil, characters: characters,
                charactersIgnoringModifiers: characters, isARepeat: false, keyCode: 0
            ))
        }
        let left = String(Character(try #require(UnicodeScalar(UInt16(NSLeftArrowFunctionKey)))))
        let cases: [(NSEvent, Selector?)] = [
            (try key("d", .command), #selector(WorkbenchView.splitRight(_:))),
            (try key("D", [.command, .shift]), #selector(WorkbenchView.splitDown(_:))),
            (try key(left, [.command, .option, .function, .numericPad]), #selector(WorkbenchView.selectSplit(_:))),
            (try key("\r", [.command, .shift]), #selector(WorkbenchView.zoomSplit(_:))),
            (try key("{", [.command, .shift]), #selector(WorkbenchView.showPreviousTab(_:))),
            (try key("9", .command), #selector(WorkbenchView.selectTab(_:))),
            (try key("c", .command), nil),
            (try key("d", [.command, .option]), nil),
        ]
        for (event, action) in cases {
            let found = WorkbenchView.shortcut(for: event, in: menu, target: shell.thisMac)
            #expect(found?.action == action, "\(event.charactersIgnoringModifiers ?? "") found \(found?.title ?? "nothing")")
        }
    }

    /// A shortcut the workbench takes is validated as the menu would: with no tab, ⌘D does nothing
    /// and is taken (no program gets it); a key no workbench item owns is left to the view and the
    /// menu (⌘K to the program, ⌘C to Edit).
    @Test func aDisabledShortcutDoesNothingAndAnUnownedKeyPassesOn() throws {
        let shell = try Self.shell(.dark)
        NSApp.mainMenu = shell.menu()
        shell.select(.thisMac)
        func key(_ characters: String, _ flags: NSEvent.ModifierFlags) throws -> NSEvent {
            try #require(NSEvent.keyEvent(
                with: .keyDown, location: .zero, modifierFlags: flags, timestamp: 0, windowNumber: 0, context: nil, characters: characters,
                charactersIgnoringModifiers: characters, isARepeat: false, keyCode: 0
            ))
        }
        #expect(shell.thisMac.model.tabs.isEmpty)
        #expect(shell.thisMac.performKeyEquivalent(with: try key("d", .command)), "a disabled ⌘D was passed on")
        #expect(shell.thisMac.model.tabs.isEmpty, "a disabled ⌘D acted")
        #expect(!shell.thisMac.performKeyEquivalent(with: try key("k", .command)), "⌘K, no item's, was taken")
        #expect(!shell.thisMac.performKeyEquivalent(with: try key("c", .command)), "⌘C, Edit's, was taken")
    }

    /// W2 and scenario 33: the workbench adds a pane's view in one place, `TabView.addPane`,
    /// called once, when the pane is made; nothing else it adds is a pane.
    @Test func aPanesViewIsAddedOnlyToItsTabsView() throws {
        let file = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources/SailShell/WorkbenchView.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        let added = source.matches(of: /addSubview\((\w+)/).map { String($0.output.1) }
        #expect(Set(added) == ["room", "empty", "banner", "pane", "divider"], "\(added)")
        #expect(added.filter { $0 == "pane" }.count == 1)
        #expect(source.matches(of: /\.addPane\(/).count == 1, "a pane's view is added again somewhere")
        #expect(source.contains("func addPane(_ pane: TerminalPane) {\n        addSubview(pane, positioned: .below, relativeTo: dividers.first)"))
    }
}
