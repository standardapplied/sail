import Foundation
import SailPreview

/// The preview, driven: `--drive <script.json> --out <directory>`. Never shipped: it is its own
/// executable, bundled only for CI's drive runs.
let arguments = CommandLine.arguments
func value(after flag: String) -> String? {
    arguments.firstIndex(of: flag).flatMap { arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil }
}

guard let scriptPath = value(after: "--drive"), let outPath = value(after: "--out") else {
    FileHandle.standardError.write(Data("usage: SailDrive --drive <script.json> --out <directory>\n".utf8))
    exit(2)
}
let out = URL(filePath: outPath)
let script: Script
do {
    try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
    script = try JSONDecoder().decode(Script.self, from: Data(contentsOf: URL(filePath: scriptPath)))
} catch {
    FileHandle.standardError.write(Data("the drive script \(scriptPath) cannot be read: \(error)\n".utf8))
    exit(2)
}

PreviewApp.run { preview in
    let driver = Driver(preview: preview, script: script, out: out)
    preview.onAttached = { _ in driver.start() }
}
