import Foundation
import SailAPI
import Testing

/// The once-a-minute rule on an injected clock: a heartbeat only while focused with input in the
/// last minute, and never two within a minute.
@Suite struct FocusHeartbeatTests {
    let start = Date(timeIntervalSince1970: 1_794_000_000)

    func at(_ seconds: TimeInterval) -> Date {
        start.addingTimeInterval(seconds)
    }

    @Test func aFocusedViewWithRecentInputSendsAtMostOnceAMinute() {
        var heartbeat = FocusHeartbeat()
        var due: [Bool] = []
        heartbeat.input(at: at(0))
        due.append(heartbeat.due(at: at(1), focused: true))
        heartbeat.input(at: at(30))
        due.append(heartbeat.due(at: at(31), focused: true))
        due.append(heartbeat.due(at: at(60.9), focused: true))
        due.append(heartbeat.due(at: at(61), focused: true))
        #expect(due == [true, false, false, true])
    }

    @Test func noHeartbeatWithoutFocusOrWithoutInputInTheLastMinute() {
        var heartbeat = FocusHeartbeat()
        var due: [Bool] = []
        due.append(heartbeat.due(at: at(0), focused: true))
        heartbeat.input(at: at(0))
        due.append(heartbeat.due(at: at(1), focused: false))
        due.append(heartbeat.due(at: at(60), focused: true))
        heartbeat.input(at: at(100))
        due.append(heartbeat.due(at: at(100), focused: true))
        due.append(heartbeat.due(at: at(130), focused: false))
        due.append(heartbeat.due(at: at(170), focused: true))
        #expect(
            due == [false, false, false, true, false, false],
            "no input yet; in the background; input a minute old; due; in the background; input 70 s old")
    }

    @Test func theHeartbeatSendsTheConversationAloneAndReadsTheServersMinute() throws {
        let request = try Endpoint.attention(conversation: "harbor-docks").request
        #expect(request.method == .post)
        #expect(request.path == "/v1/attention")
        #expect(request.body == Data(#"{"conversation":"harbor-docks"}"#.utf8))
        let minute = try Endpoint<AttentionMinute>.attention(conversation: "harbor-docks").decode(
            HTTPResponse(
                status: 200,
                body: Data(#"{"conversation": "harbor-docks", "minute": "2026-11-13T09:07:00.000Z", "recorded": true}"#.utf8)))
        #expect(minute.conversation == "harbor-docks")
        #expect(minute.recorded)
        #expect(minute.minute.timeIntervalSince1970 == 1_794_560_820)
    }
}
