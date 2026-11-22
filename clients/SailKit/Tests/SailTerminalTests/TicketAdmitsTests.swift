import SailKitTestSupport
import SailTerminal
import SailTerminalWire
import Testing

/// The fake door admits on each ticket exactly what the host's tickets admit, as the host wrote it
/// to the admits golden: one list, the host's, never a second one kept here.
@Suite struct TicketAdmitsTests {
    @Test func theFakeDoorAdmitsWhatTheHostsTicketsAdmit() throws {
        let admits = try Goldens.admits()
        #expect(Set(admits.keys) == ["open", "attach", "files"])
        let frames = try Goldens.frames().map { ($0.name, try FrameCodec.decode(payload: $0.wire.dropFirst(4))) }
        guard case let .create(create)? = frames.first(where: { $0.0 == "Create" })?.1 else {
            Issue.record("the frames golden holds no Create")
            return
        }
        for action in admits.keys.sorted() {
            for (name, frame) in frames {
                let own = Self.own(frame)
                let ticket: TicketRequest = switch action {
                case "open": .open(project: create.project, workspace: create.workspace)
                case "attach": .attach(session: own ?? "lounge")
                default: .files(project: "harbor", workspace: "dock")
                }
                let admitted = FakeRunnerDoor.refusal(of: frame, under: ticket, opened: action == "open" ? own : nil) == nil
                let host = admits[action]?.contains(name) == true
                #expect(admitted == host, "\(action) ticket: the fake door \(admitted ? "admits" : "refuses") \(name), the host \(host ? "admits" : "refuses") it")
            }
        }
    }

    /// The session a frame names as its own, which a ticket aimed at it holds.
    static func own(_ frame: Frame) -> String? {
        switch frame {
        case let .attach(session, _, _, _): session
        case let .kill(session): session
        default: nil
        }
    }
}
