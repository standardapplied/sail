import AppKit
import SailControls
import SailLook
@testable import SailShell
import SailTerminalView
import SailWorkbench
import Testing

/// The calm look, counted by token so it cannot erode: terracotta fills only the one primary action
/// in view and marks where a person acts; no row, tab or rail item is tinted with it. The numbers
/// are the approved page's: 4 marks on a plain Workspaces page, 3 on a terminal page with a split.
@MainActor @Suite struct CalmTests {
    struct Count: Equatable, CustomStringConvertible {
        var fill = 0, line = 0, tint = 0, error = 0

        init(_ marks: [Mark]) {
            for mark in marks {
                switch mark {
                case .accentFill: fill += 1
                case .accentLine: line += 1
                case .accentTint: tint += 1
                case .errorFill, .errorInk: error += 1
                }
            }
        }

        var accent: Int {
            fill + line + tint
        }

        var description: String {
            "\(fill) accent fills, \(line) accent lines, \(tint) accent tints, \(error) error marks"
        }
    }

    /// The rail's chosen item: one accent bar on an ink fill.
    static func rail(_ look: Look) -> Count {
        let rail = Rail(look: look)
        rail.show(.workspaces)
        return Count(rail.drawnMarks)
    }

    /// Every page the project's view can draw, in both looks, wide and narrow: never a tint of the
    /// accent, never two accent fills; a refusal is said in the error ink, never as a tinted row.
    @Test(arguments: [Look.light, .dark])
    func everyWorkspacesPageIsCalm(_ look: Look) throws {
        for width in [900.0, WorkspacesViewTests.narrowestCard] {
            let (project, window) = try WorkspacesViewTests.project(width: width)
            defer { window.orderOut(nil) }
            project.look = look
            for (name, show) in WorkspacesViewTests.scenes(project) {
                show()
                project.layoutSubtreeIfNeeded()
                project.list.layoutSubtreeIfNeeded()
                let count = Count(marks(in: window))
                #expect(count.tint == 0 && count.fill <= 1, "\(name) at \(width) pt: \(count)")
                if name == "refused" { #expect(count.error > 0, "refused at \(width) pt says nothing in the error ink: \(count)") }
            }
        }
    }

    /// A1: harbor and dock chosen: the rail's bar, the panel's project bar, dock's bar and Open, and
    /// no tinted fill.
    @Test(arguments: [Look.light, .dark])
    func thePlainWorkspacesPageHasFourMarks(_ look: Look) async throws {
        let (view, window, _) = try await WorkspacesViewTests.listed()
        defer { window.orderOut(nil) }
        view.look = look
        view.project.list.choose("dock")
        #expect(await RunnerTabTests.until { view.project.list.chosen == "dock" })
        view.layoutSubtreeIfNeeded()
        view.project.list.layoutSubtreeIfNeeded()
        let section = Count(marks(in: window)), rail = Self.rail(look)
        #expect(section == Count([.accentLine, .accentLine, .accentFill]), "the section draws \(section)")
        #expect(rail == Count([.accentLine]), "the rail draws \(rail)")
        #expect(section.accent + rail.accent == 4 && section.tint + rail.tint == 0)
    }

    /// A5: three tabs and a split: the rail's bar, the chosen tab's bar, and the cursor of the pane
    /// with the keys; the other pane's cursor is ink. A6: an ended terminal's cursor is ink too, and
    /// the card's Open a New One is the only accent fill.
    @Test(arguments: [Look.light, .dark])
    func aTerminalPageWithASplitHasThreeMarks(_ look: Look) throws {
        let shell = try WorkbenchLookTests.shell(look)
        let strip = shell.thisMac.strip
        strip.showsTabs = true
        strip.show(WorkbenchLookTests.items() + [TabStrip.Item(id: TabID(3), title: "web", zoomed: false)], selected: TabID(1))
        let fonts = try TerminalFonts(directory: LookTests.fonts)
        let typing = try TerminalView(fonts: fonts), other = try TerminalView(fonts: fonts)
        let split = NSStackView(views: [typing, other])
        let window = KeyWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 400), styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = split
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        window.makeFirstResponder(typing)
        let page = Count(marks(in: shell.rail)) + Count(marks(in: strip)) + Count(marks(in: window))
        #expect(page == Count([.accentLine, .accentLine, .accentLine]), "the terminal page draws \(page)")
        typing.inert = true
        let banner = StatusBanner(look: look)
        banner.show("This terminal ended while you were away.", button: "Open a New One")
        let ended = Count(marks(in: window)) + Count(marks(in: banner))
        #expect(ended == Count([.accentFill]), "an ended terminal and its card draw \(ended)")
    }
}

extension CalmTests.Count {
    static func + (one: Self, other: Self) -> Self {
        var sum = one
        sum.fill += other.fill
        sum.line += other.line
        sum.tint += other.tint
        sum.error += other.error
        return sum
    }
}
