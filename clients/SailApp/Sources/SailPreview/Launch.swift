import AppKit

extension PreviewApp {
    /// Runs the app from its bundle, `configure` given the app before it launches; a bundle
    /// missing its fonts or host says so in an alert and quits.
    public static func run(configure: @MainActor (PreviewApp) -> Void = { _ in }) -> Never {
        let application = NSApplication.shared
        application.setActivationPolicy(.regular)
        let preview: PreviewApp
        do {
            preview = try PreviewApp(bundle: .main)
        } catch {
            let alert = NSAlert()
            alert.messageText = "\(title) cannot start"
            alert.informativeText = sentence(error.description)
            alert.runModal()
            exit(1)
        }
        configure(preview)
        application.delegate = preview
        application.run()
        exit(0)
    }
}
