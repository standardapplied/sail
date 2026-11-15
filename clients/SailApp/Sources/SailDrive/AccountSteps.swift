import AppKit
import SailAccount
import SailAPI
import SailShell
import SailTestPasskey
import Security

/// The drive's steps on the account: they act through the pane's own fields and buttons, and read
/// what the pane says. No step's detail holds a ticket or a token.
extension Driver {
    func performAccount(_ step: Step) async -> (Bool, String)? {
        let pane = shell.account
        if let item = step.select {
            guard let chosen = Rail.Item(rawValue: item), let button = shell.rail.button(chosen) else { return (false, "no rail item \(item)") }
            button.performClick(nil)
            return (shell.rail.selected == chosen, "the rail shows \(shell.rail.selected?.rawValue ?? "nothing")")
        }
        if let address = step.address {
            pane.address.stringValue = address
            return (true, "")
        }
        if let answer = step.passkey {
            guard let chosen = SoftwarePasskey.Answer(rawValue: answer) else { return (false, "no passkey answer \(answer)") }
            passkey.answer(chosen)
            return (true, "")
        }
        if let variable = step.enroll {
            guard let ticket = ProcessInfo.processInfo.environment[variable], !ticket.isEmpty else { return (false, "\(variable) holds no ticket") }
            pane.ticket.stringValue = ticket
            let idle = await settled(step) { pane.enroll.performClick(nil) }
            return (idle && pane.ticket.stringValue.isEmpty, "the ticket field is \(pane.ticket.stringValue.isEmpty ? "empty" : "not empty"); \(said)")
        }
        if step.signIn == true {
            let idle = await settled(step) { pane.signIn.performClick(nil) }
            if signed, let plane = try? ControlPlane(address: pane.address.stringValue), let held = try? KeychainVault().session(for: plane) {
                tokens.insert(held.token)
            }
            return (idle, said)
        }
        if step.signOut == true {
            return (await settled(step) { pane.signOut.performClick(nil) }, said)
        }
        if let lines = step.exactly {
            let found = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.text.split(separator: "\n").map(String.init) == lines }
            return (found, said)
        }
        if let text = step.pane {
            let found = await waitUntil(step.seconds ?? 10) { !pane.busy && pane.text.contains(text) }
            return (found, said)
        }
        if let words = step.sail {
            return run(words.map { $0 == "{credential}" ? passkey.credentialIds.last ?? "" : $0 })
        }
        if step.activate == true {
            let idle = await settled(step) { NotificationCenter.default.post(name: NSApplication.didBecomeActiveNotification, object: NSApp) }
            return (idle, said)
        }
        if let count = step.asked {
            let asked = passkey.asked - askedAtStart
            return (asked == count, "the software passkey was asked \(asked) times")
        }
        if let wanted = step.keychain {
            return keychain(wanted)
        }
        if step.accent == true {
            return accent()
        }
        if step.sweep == true {
            return sweep()
        }
        return nil
    }

    /// What the pane says, as one line.
    private var said: String {
        "the pane says: " + shell.account.text.replacingOccurrences(of: "\n", with: " | ")
    }

    /// Does `act` and waits until the pane has ended what it started.
    private func settled(_ step: Step, _ act: () -> Void) async -> Bool {
        let before = shell.account.ended
        act()
        return await waitUntil(step.seconds ?? 20) { self.shell.account.ended > before && !self.shell.account.busy }
    }

    private func run(_ words: [String]) -> (Bool, String) {
        let sail = Bundle.main.bundleURL.appending(path: "Contents/Helpers/sail")
        let process = Process()
        process.executableURL = sail
        process.arguments = words
        let output = Pipe()
        process.standardOutput = output
        process.standardError = output
        do {
            try process.run()
        } catch {
            return (false, "sail could not be started: \(error)")
        }
        let said = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        process.waitUntilExit()
        let shown = words.prefix(3).joined(separator: " ")
        return (process.terminationStatus == 0, "sail \(shown) … exited \(process.terminationStatus): \(said.suffix(300))")
    }

    /// Scenarios 3, 16 and S2: the item as item 3 says, holding a session 30 days long, or gone.
    private func keychain(_ wanted: String) -> (Bool, String) {
        guard let plane = try? ControlPlane(address: shell.account.address.stringValue) else { return (false, "the address names no control plane") }
        var query = KeychainVault.item(plane)
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if wanted == "gone" { return (status == errSecItemNotFound, "the Keychain answered \(status)") }
        guard status == errSecSuccess, let item = found as? [String: Any] else { return (false, "the Keychain answered \(status)") }
        let group = item[kSecAttrAccessGroup as String] as? String ?? ""
        let session = try? KeychainVault().session(for: plane)
        let left = (session?.expiresAt.timeIntervalSinceNow ?? 0) / 3600
        let checks = [
            "a session for uday ending 30 days from now": session?.fde == "uday" && abs(left - 30 * 24) < 1,
            "service": item[kSecAttrService as String] as? String == KeychainVault.service,
            "account": item[kSecAttrAccount as String] as? String == plane.description,
            "when unlocked, this device only": item[kSecAttrAccessible as String] as? String == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String,
            "not synchronizable": (item[kSecAttrSynchronizable as String] as? Bool ?? false) == false,
            "the app's default access group": group.hasSuffix(".sh.standardapplied.sail") && group.split(separator: ".").first?.count == 10,
        ]
        let failed = checks.filter { !$0.value }.keys.sorted()
        return (failed.isEmpty, failed.isEmpty ? "the item is \(checks.keys.sorted().joined(separator: ", "))" : "the item is not: \(failed.joined(separator: ", "))")
    }

    /// Scenario 18: the sign-in button drawn into an sRGB bitmap, its commonest colour the fill and
    /// its darkest the label, each within 2 of 255 of the token, whatever the system accent is.
    private func accent() -> (Bool, String) {
        let button = shell.account.signIn
        button.display()
        let scale = 2
        let width = Int(button.bounds.width) * scale, height = Int(button.bounds.height) * scale
        guard width > 0, height > 0, let layer = button.layer, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
              ), let bytes = context.data
        else { return (false, "the button could not be drawn") }
        context.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
        layer.render(in: context)
        let pixels = bytes.bindMemory(to: UInt8.self, capacity: width * height * 4)
        var counts: [UInt32: Int] = [:]
        var darkest: (sum: Int, rgb: UInt32) = (Int.max, 0)
        for index in 0 ..< width * height {
            let red = pixels[index * 4], green = pixels[index * 4 + 1], blue = pixels[index * 4 + 2]
            guard pixels[index * 4 + 3] == 255 else { continue }
            let rgb = UInt32(red) << 16 | UInt32(green) << 8 | UInt32(blue)
            counts[rgb, default: 0] += 1
            let sum = Int(red) + Int(green) + Int(blue)
            if sum < darkest.sum { darkest = (sum, rgb) }
        }
        guard let fill = counts.max(by: { $0.value < $1.value })?.key else { return (false, "the button drew nothing opaque") }
        func near(_ found: UInt32, _ wanted: UInt32) -> Bool {
            [16, 8, 0].allSatisfy { abs(Int(found >> $0 & 0xFF) - Int(wanted >> $0 & 0xFF)) <= 2 }
        }
        let system = NSColor.controlAccentColor.usingColorSpace(.sRGB).map { String(format: "%02X%02X%02X", Int($0.redComponent * 255), Int($0.greenComponent * 255), Int($0.blueComponent * 255)) } ?? "unknown"
        return (
            near(fill, 0xFC4926) && near(darkest.rgb, 0x0A0E11),
            String(format: "fill #%06X, label #%06X; the system accent is #", fill, darkest.rgb) + system
        )
    }

    /// Scenario 17: the session token, `sess_` and the enrollment ticket searched for in the
    /// process's own memory of
    /// what it shows, every file named for the bundle id under the Library folders, the drive's
    /// output, the last 15 minutes of the system log, every process's arguments and the
    /// pasteboard. Paths are reported, never what was found.
    private func sweep() -> (Bool, String) {
        let ticket = ProcessInfo.processInfo.environment["SAIL_DRIVE_TICKET"].map { [Data($0.utf8)] } ?? []
        let needles = tokens.map { Data($0.utf8) } + [Data("sess_".utf8)] + ticket
        var places: [String] = []
        func search(_ data: Data, at place: String) {
            if needles.contains(where: { data.range(of: $0) != nil }) { places.append(place) }
        }
        search(Data(shell.account.text.utf8), at: "the account pane")
        search(Data(results.map(\.detail).joined(separator: "\n").utf8), at: "the drive's report")
        let library = FileManager.default.homeDirectoryForCurrentUser.appending(path: "Library")
        var files = 0
        for folder in ["Preferences", "Caches", "HTTPStorages", "Application Support", "Saved Application State"] {
            for file in Self.files(under: library.appending(path: folder)) where file.path.contains("sh.standardapplied.sail") {
                files += 1
                if let data = try? Data(contentsOf: file) { search(data, at: file.path) }
            }
        }
        for file in Self.files(under: out) {
            files += 1
            if let data = try? Data(contentsOf: file) { search(data, at: file.path) }
        }
        search(Self.output(of: "/usr/bin/log", ["show", "--last", "15m", "--style", "compact"]), at: "log show --last 15m")
        search(Self.output(of: "/bin/ps", ["-axww", "-o", "args"]), at: "ps -axww -o args")
        search(Data((NSPasteboard.general.string(forType: .string) ?? "").utf8), at: "the pasteboard")
        let searched = "\(tokens.count) token(s), sess_ and \(ticket.count) ticket in \(files) files, the log, the processes and the pasteboard"
        return (places.isEmpty, places.isEmpty ? "found in none of \(searched)" : "found in \(places.joined(separator: ", ")) of \(searched)")
    }

    private static func files(under folder: URL) -> [URL] {
        guard let walk = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: [.isRegularFileKey]) else { return [] }
        return walk.compactMap { $0 as? URL }.filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
    }

    private static func output(of tool: String, _ words: [String]) -> Data {
        let process = Process()
        process.executableURL = URL(filePath: tool)
        process.arguments = words
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return Data() }
        let data = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return data
    }
}
