@testable import SailTerminal
import Synchronization
import Testing

@Suite struct RelayTests {
    /// A function called through a relay as often as a flood's outputs runs at the same stack depth
    /// on its twenty-thousandth call as on its first: nothing wraps it as it is read.
    @Test func aFunctionCalledAsOftenAsAFloodRunsAtTheSameDepthEveryTime() {
        let relay = Relay<Int>()
        let depths = Mutex<[Int: UInt]>([:])
        relay.set { call in
            var local = 0
            let here = withUnsafeMutablePointer(to: &local) { UInt(bitPattern: $0) }
            if call == 0 || call == 19999 { depths.withLock { $0[call] = here } }
        }
        for call in 0 ..< 20000 {
            relay(call)
        }
        let seen = depths.withLock { $0 }
        let first = seen[0] ?? 0, last = seen[19999] ?? 0
        #expect(first > 0 && last > 0)
        #expect(first >= last && first - last < 4096, "the 20000th call ran \(Int(first) - Int(last)) bytes deeper than the first")
    }

    @Test func aRelayCallsTheFunctionSetLastAndNothingOnceCleared() {
        let relay = Relay<Int>()
        let calls = Mutex<[String]>([])
        relay(0)
        relay.set { value in calls.withLock { $0.append("a\(value)") } }
        relay(1)
        relay.set { value in calls.withLock { $0.append("b\(value)") } }
        relay(2)
        #expect(relay.isSet)
        relay.set(nil)
        relay(3)
        #expect(!relay.isSet)
        #expect(calls.withLock { $0 } == ["a1", "b2"])
    }
}
