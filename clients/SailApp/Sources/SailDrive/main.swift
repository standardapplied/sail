import AppKit
import Foundation
import SailAPI
import SailControls
import SailKitTestSupport
import SailShell
import SailTestPasskey

/// The app, driven: `--drive <script.json> --out <directory>`, the software passkey in the
/// platform's place, its credentials kept in `SAIL_DRIVE_PASSKEY` (beside the output when unset)
/// so a later launch signs in with them; `SAIL_DRIVE_PRIVATE` names the run's private folder, where
/// the drive makes the files its steps use; `SAIL_SIGNING=on` says the build is signed;
/// `SAIL_DRIVE_GOLDENS=on` answers every control-plane call from the server's goldens. Never shipped:
/// it is its own executable, bundled only for CI's drive runs.
LineBuffered.standardOutput()
ExitWatch.watchExceptions()
let arguments = CommandLine.arguments
func value(after flag: String) -> String? {
    arguments.firstIndex(of: flag).flatMap { arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil }
}

guard let scriptPath = value(after: "--drive"), let outPath = value(after: "--out") else {
    FileHandle.standardError.write(Data("usage: SailDrive --drive <script.json> --out <directory>\n".utf8))
    exit(2)
}
let out = URL(filePath: outPath)
let environment = ProcessInfo.processInfo.environment
var isFolder: ObjCBool = false
guard Driver.privateFolder.hasPrefix("/"), FileManager.default.fileExists(atPath: Driver.privateFolder, isDirectory: &isFolder), isFolder.boolValue else {
    FileHandle.standardError.write(Data("SAIL_DRIVE_PRIVATE names no folder: the drive makes its steps' files in the run's private folder, which ci/drive makes\n".utf8))
    exit(2)
}
let script: Script
do {
    try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
    script = try JSONDecoder().decode(Script.self, from: Data(contentsOf: URL(filePath: scriptPath)))
} catch {
    FileHandle.standardError.write(Data("the drive script \(scriptPath) cannot be read: \(error)\n".utf8))
    exit(2)
}
ExitMark.arm(at: out.appending(path: "exited"))
let passkey = SoftwarePasskey(
    file: environment["SAIL_DRIVE_PASSKEY"].map { URL(filePath: $0) } ?? out.deletingLastPathComponent().appending(path: "software-passkey.json")
)

ShellApp.run(passkeys: { _ in passkey }, connect: { plane in
    ProcessInfo.processInfo.environment["SAIL_DRIVE_GOLDENS"] == "on" ? GoldenDesk.shared as any Transport : URLSessionTransport(base: plane.url)
}) { shell in
    SailList.stopsOnMisuse = true
    let driver = Driver(shell: shell, passkey: passkey, signed: environment["SAIL_SIGNING"] == "on", script: script, out: out)
    Task { @MainActor in driver.start() }
}
