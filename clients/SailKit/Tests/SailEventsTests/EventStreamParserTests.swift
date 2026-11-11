import SailEvents
import SailKitTestSupport
import Testing

@Suite struct EventStreamParserTests {
    @Test(arguments: ["events/stream.sse", "events/room-stream.sse"])
    func aGoldenStreamCutAtEveryByteOffsetReadsAsTheSameEvents(golden: String) throws {
        let stream = try Goldens.bytes(golden)
        let whole = parse([stream])
        #expect(whole.count == stream.split(separator: UInt8(ascii: "\n"), omittingEmptySubsequences: false)
            .filter { $0.starts(with: Array("data:".utf8)) }.count)
        for offset in 0 ... stream.count {
            #expect(parse(cut(stream, at: offset)) == whole, "cut at \(offset)")
        }
    }

    @Test(arguments: ["\r\n", "\r"])
    func aStreamWhoseLinesEndOtherwiseReadsAsTheSameEventsHoweverItIsCut(ending: String) throws {
        let stream = try Goldens.bytes("events/stream.sse")
        let whole = parse([stream])
        let ended = Array(String(decoding: stream, as: UTF8.self).replacingNewlines(with: ending).utf8)
        for offset in 0 ... ended.count {
            #expect(parse(cut(ended, at: offset)) == whole, "cut at \(offset)")
        }
        for seed in UInt64(1) ... 500 {
            #expect(parse(chunking(of: ended, seed: seed)) == whole, "seed \(seed)")
        }
    }

    @Test func eachEventCarriesItsTypeDataAndTheLastIdTheServerGave() throws {
        let events = parse([try Goldens.bytes("events/room-stream.sse")])
        let turn = try #require(events.first)
        #expect(turn.type == "message")
        #expect(turn.lastEventID == "")
        #expect(turn.data.hasPrefix(#"{"agent": "sail", "data": {"open": false}"#))
        #expect(events.dropFirst().first?.lastEventID == "1")
        let end = try #require(events.last)
        #expect(end.type == "error")
        #expect(end.lastEventID == events.dropLast().last?.lastEventID)
    }

    @Test func commentsAreNotEventsAndARetryIsExposed() throws {
        var parser = EventStreamParser()
        #expect(parser.feed(Array(": subscribed\n\n: keepalive\n\n".utf8)).isEmpty)
        #expect(parser.retry == nil)
        #expect(parser.feed(Array("retry: 2500\n\n".utf8)).isEmpty)
        #expect(parser.retry == .milliseconds(2500))
        #expect(parser.feed(Array("retry: 25x\n\nretry\n\n".utf8)).isEmpty)
        #expect(parser.retry == .milliseconds(2500))
    }

    @Test func fieldsAreReadAsTheEventSourceStandardSays() {
        var parser = EventStreamParser()
        let events = parser.feed(Array("""
        \u{FEFF}data:first
        data
        data:  spaced
        event: error
        berth: 4
        id: 7

        id: 8\u{0}
        data: kept

        data:

        id: 9


        """.utf8))
        #expect(events == [
            ServerSentEvent(type: "error", data: "first\n\n spaced", lastEventID: "7"),
            ServerSentEvent(type: "message", data: "kept", lastEventID: "7"),
            ServerSentEvent(type: "message", data: "", lastEventID: "7"),
        ])
        #expect(parser.lastEventID == "9")
    }

    @Test func anEventTheStreamEndsBeforeIsNeverDispatched() {
        var parser = EventStreamParser(lastEventID: "3")
        #expect(parser.feed(Array("id: 4\ndata: half".utf8)).isEmpty)
        #expect(parser.lastEventID == "3")
        var resumed = EventStreamParser(lastEventID: "3")
        #expect(resumed.feed(Array("data: whole\n\n".utf8)) == [ServerSentEvent(type: "message", data: "whole", lastEventID: "3")])
    }
}

func parse(_ chunks: [[UInt8]]) -> [ServerSentEvent] {
    var parser = EventStreamParser()
    return chunks.flatMap { parser.feed($0) }
}

extension String {
    func replacingNewlines(with ending: String) -> String {
        split(separator: "\n", omittingEmptySubsequences: false).joined(separator: ending)
    }
}
