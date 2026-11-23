import Foundation
import Testing

/// Rules the app's own tests keep, read from their sources.
@Suite struct TestRulesTests {
    /// A helper never returns past a wait it only expected: its caller relies on the wait having
    /// held, so a wait that ran out goes on into an index or a missing view, and an index out of
    /// range ends the suite's whole process with every test still running in it. It requires the
    /// wait (`try #require(await …)`). Read as: a one-line `#expect(await …)` followed, before the
    /// next expectation or closing brace, by a `return` of a value.
    @Test func noHelperReturnsPastAWaitItOnlyExpected() throws {
        let tests = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let files = try #require(FileManager.default.enumerator(at: tests, includingPropertiesForKeys: nil))
        var read = 0, faults: [String] = []
        for case let file as URL in files where file.pathExtension == "swift" {
            let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
            read += 1
            for index in lines.indices where lines[index].hasPrefix("#expect(await ") && lines[index].hasSuffix(")") {
                let after = lines[(index + 1)...].prefix { !$0.hasPrefix("#expect") && !$0.hasPrefix("}") }
                if after.contains(where: { $0.hasPrefix("return ") }) { faults.append("\(file.lastPathComponent):\(index + 1)") }
            }
        }
        #expect(read >= 25, "read \(read) test sources")
        #expect(faults.isEmpty, "these return past a wait they only expected; require it: \(faults.joined(separator: ", "))")
    }
}
