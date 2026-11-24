import Foundation
@testable import SailVT
import Testing

/// A web address in a terminal's line is found under the pointer, whole, less the punctuation of
/// the sentence it ends and a bracket it never opened; nothing else is one.
@Suite struct TerminalURLTests {
    @Test func theAddressUnderThePointerIsFoundWhole() throws {
        let line = "Open this link in your browser https://auth.openai.com/codex/device and sign in"
        let found = try #require(TerminalURL.find(in: line, at: 40))
        #expect(found.url.absoluteString == "https://auth.openai.com/codex/device" && found.range == 31 ..< 67)
        #expect(TerminalURL.find(in: line, at: 32)?.range == 31 ..< 67 && TerminalURL.find(in: line, at: 67)?.range == 31 ..< 67)
        #expect(TerminalURL.find(in: line, at: 31) == nil && TerminalURL.find(in: line, at: 68) == nil)
    }

    @Test func theSentencesPunctuationAndAnUnopenedBracketAreNotTheAddress() {
        #expect(TerminalURL.find(in: "see https://example.com/a.", at: 6)?.url.absoluteString == "https://example.com/a")
        #expect(TerminalURL.find(in: "(at http://example.com/b)", at: 6)?.url.absoluteString == "http://example.com/b")
        #expect(TerminalURL.find(in: "https://en.wikipedia.org/wiki/Tide_(sea)!", at: 3)?.url.absoluteString == "https://en.wikipedia.org/wiki/Tide_(sea)")
        #expect(TerminalURL.find(in: "<https://example.com/c>", at: 2)?.range == 1 ..< 22)
    }

    @Test func nothingElseIsAnAddress() {
        #expect(TerminalURL.find(in: "src/a.ts:4 ftp://example.com example.com", at: 15) == nil)
        #expect(TerminalURL.find(in: "src/a.ts:4 ftp://example.com example.com", at: 32) == nil)
        #expect(TerminalURL.find(in: "", at: 1) == nil && TerminalURL.find(in: "https://x.y", at: 0) == nil)
    }
}
