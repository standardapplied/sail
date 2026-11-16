import AppKit
import SailLook

/// A sheet asking to confirm an action, the action in the accent. Neither button answers Return:
/// the action would run what the sheet warns of, and a default Cancel is drawn in the system
/// accent. Escape cancels, watched for while the sheet is up.
@MainActor
public enum Confirmation {
    private static var escapeWatch: Any?

    public static func ask(_ question: String, detail: String, action: String, on window: NSWindow, answer: @escaping @MainActor (Bool) -> Void) {
        let alert = NSAlert()
        alert.messageText = question
        alert.informativeText = detail
        let confirm = alert.addButton(withTitle: action)
        _ = alert.addButton(withTitle: "Cancel")
        let look: Look = window.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .aqua ? .light : .dark
        confirm.bezelColor = NSColor(look.accent)
        confirm.attributedTitle = NSAttributedString(
            string: action, attributes: [.foregroundColor: NSColor(look.onAccent), .font: NSFont.boldSystemFont(ofSize: NSFont.systemFontSize)]
        )
        alert.beginSheetModal(for: window) { response in
            MainActor.assumeIsolated {
                stopWatchingEscape()
                answer(response == .alertFirstButtonReturn)
            }
        }
        confirm.keyEquivalent = ""
        watchEscape(cancelling: alert.window, on: window)
    }

    private static func watchEscape(cancelling sheet: NSWindow, on window: NSWindow) {
        stopWatchingEscape()
        let escape: UInt16 = 0x35
        escapeWatch = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak sheet, weak window] event in
            guard event.keyCode == escape, let sheet, event.window === sheet else { return event }
            MainActor.assumeIsolated { window?.endSheet(sheet, returnCode: .alertSecondButtonReturn) }
            return nil
        }
    }

    private static func stopWatchingEscape() {
        guard let watch = escapeWatch else { return }
        NSEvent.removeMonitor(watch)
        escapeWatch = nil
    }
}
