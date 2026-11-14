import Foundation
@testable import sail_term_probe
import Testing

@Suite struct ProbeTests {
    /// MINOR 11: a capture is a new file only its owner reads; one that exists is not overwritten.
    @Test func aCaptureIsANewFileOnlyItsOwnerReads() throws {
        let path = FileManager.default.temporaryDirectory.appending(path: "capture-\(UUID().uuidString)").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let capture = try Capture(path: path)
        capture.append(Array("bytes".utf8))
        try capture.close()
        let attributes = try FileManager.default.attributesOfItem(atPath: path)
        #expect((attributes[.posixPermissions] as? NSNumber)?.intValue == 0o600)
        #expect(throws: ProbeError.self) { try Capture(path: path) }
        #expect(try String(contentsOfFile: path, encoding: .utf8) == "bytes")
    }

    /// MINOR 11: a report names what a step did, never what it typed.
    @Test func aReportNamesAStepsKindNotWhatItTyped() {
        #expect(Step.type("secret words\r").description == "type 13 characters")
    }
}
