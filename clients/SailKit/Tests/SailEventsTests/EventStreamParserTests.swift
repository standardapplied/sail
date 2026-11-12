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

    @Test(arguments: ["\n", "\r\n", "\r"])
    func anEventOfSeveralDataLinesIsOneEventWhateverEndsItsLines(ending: String) {
        let stream = Array("data: one\ndata: two\n\n".replacingNewlines(with: ending).utf8)
        for offset in 0 ... stream.count {
            #expect(parse(cut(stream, at: offset)) == [ServerSentEvent(type: "message", data: "one\ntwo", lastEventID: "")])
        }
    }

    @Test func eachEventCarriesItsTypeDataAndTheLastIdTheServerGave() throws {
        let events = parse([try Goldens.bytes("events/room-stream.sse")])
        let turn = try #require(events.first)
        #expect(turn.type == "message")
        #expect(turn.lastEventID == "")
        #expect(events.dropFirst().first?.lastEventID == "1")
        let end = try #require(events.last)
        #expect(end.type == "error")
        #expect(end.lastEventID == events.dropLast().last?.lastEventID)
    }

    @Test func theServersOwnCommentsAreNotEvents() throws {
        let stream = try Goldens.bytes("events/stream.sse")
        let subscribed = try #require(range(of: ": subscribed\n\n", in: stream))
        let keepalive = try #require(range(of: ": keepalive\n\n", in: stream))
        #expect(subscribed.lowerBound == 0)
        #expect(parse([Array(stream[subscribed]), Array(stream[keepalive])]).isEmpty)
    }

    @Test func aRetryIsExposedAndIsNotAnEvent() throws {
        var parser = EventStreamParser()
        #expect(parser.retry == nil)
        #expect(parser.feed(Array("retry: 2500\n\n".utf8)).isEmpty)
        #expect(parser.retry == .milliseconds(2500))
        for notDigits in ["25x", "-5", "+5", "", " 7", "99999999999999999999"] {
            #expect(parser.feed(Array("retry: \(notDigits)\n\n".utf8)).isEmpty)
            #expect(parser.retry == .milliseconds(2500), "retry: \(notDigits)")
        }
        #expect(parser.feed(Array("retry\n\n".utf8)).isEmpty)
        #expect(parser.retry == .milliseconds(2500))
    }

    @Test func aLineAsLongAsTheLimitIsReadAndOneByteLongerStopsTheParser() {
        #expect(EventStreamParser.limit == 1_048_576)
        let longest = [UInt8](repeating: UInt8(ascii: "x"), count: EventStreamParser.limit - 5)
        var parser = EventStreamParser()
        let events = parser.feed(Array("data:".utf8) + longest + Array("\n\n".utf8))
        #expect(events.map(\.data.utf8.count) == [EventStreamParser.limit - 5])
        #expect(!parser.hasOverflowed)

        let kept = parser.feed(Array("id: 7\ndata: kept\n\ndata:".utf8) + longest + [UInt8(ascii: "x")])
        #expect(kept == [ServerSentEvent(type: "message", data: "kept", lastEventID: "7")])
        #expect(parser.hasOverflowed)
        #expect(parser.feed(Array("\n\nid: 8\ndata: after\n\n".utf8)).isEmpty)
        #expect(parser.lastEventID == "7")
    }

    @Test func anEventsDataAsLongAsTheLimitIsReadAndOneByteLongerStopsTheParser() {
        let line = Array("data:".utf8) + [UInt8](repeating: UInt8(ascii: "x"), count: 65535) + [UInt8(ascii: "\n")]
        let lines = EventStreamParser.limit / 65536
        var parser = EventStreamParser()
        for _ in 1 ... lines {
            #expect(parser.feed(line).isEmpty)
        }
        #expect(parser.feed(Array("\n".utf8)).map(\.data.utf8.count) == [EventStreamParser.limit - 1])
        #expect(!parser.hasOverflowed)

        for _ in 1 ... lines {
            #expect(parser.feed(line).isEmpty)
        }
        #expect(!parser.hasOverflowed)
        #expect(parser.feed(Array("data:\n\n".utf8)).isEmpty)
        #expect(parser.hasOverflowed)
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
