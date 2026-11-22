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
                let admitted = FakeRunnerDoor.refusal(of: frame, under: ticket, opened: action == "open" ? own : nil, writing: own) == nil
                let host = admits[action]?.contains(name) == true
                #expect(admitted == host, "\(action) ticket: the fake door \(admitted ? "admits" : "refuses") \(name), the host \(host ? "admits" : "refuses") it")
            }
        }
    }

    /// Aimed elsewhere, the fake refuses what the host's ticket refuses there: a create into
    /// another project or workspace, an attach or a kill of another session; every other frame as
    /// the golden says. A kill of its own session by a connection without the keyboard is refused
    /// in the host's words.
    @Test func theFakeDoorRefusesWhatIsAimedElsewhere() throws {
        let admits = try Goldens.admits()
        let frames = try Goldens.frames().map { ($0.name, try FrameCodec.decode(payload: $0.wire.dropFirst(4))) }
        let aimed: Set<String> = ["Create", "Attach", "Kill"]
        for action in admits.keys.sorted() {
            let ticket: TicketRequest = switch action {
            case "open": .open(project: "elsewhere", workspace: "other")
            case "attach": .attach(session: "other-session")
            default: .files(project: "elsewhere", workspace: "other")
            }
            for (name, frame) in frames {
                let opened = action == "open" ? "other-session" : nil
                let admitted = FakeRunnerDoor.refusal(of: frame, under: ticket, opened: opened, writing: "other-session") == nil
                let host = admits[action]?.contains(name) == true && !aimed.contains(name)
                #expect(admitted == host, "\(action) ticket aimed elsewhere: the fake \(admitted ? "admits" : "refuses") \(name)")
            }
        }
        #expect(FakeRunnerDoor.refusal(of: .kill(session: "lounge"), under: .attach(session: "lounge"), opened: nil, writing: nil) == FakeRunnerDoor.notTheWriter)
        #expect(FakeRunnerDoor.refusal(of: .kill(session: "lounge"), under: .attach(session: "lounge"), opened: nil, writing: "lounge") == nil)
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
