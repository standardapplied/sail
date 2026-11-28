import SailAPI
import SailKitTestSupport
import Testing

@Suite struct VocabularyTests {
    @Test func eachEnumHoldsTheWordsTheServersOwnEnumDoesInItsOrder() throws {
        #expect(ErrorCode.allCases.map(\.rawValue) == (try Goldens.vocabulary("error_codes")))
        #expect(Role.allCases.map(\.rawValue) == (try Goldens.vocabulary("roles")))
        #expect(SpecStatus.allCases.map(\.rawValue) == (try Goldens.vocabulary("spec_statuses")))
        #expect(MemberMode.allCases.map(\.rawValue) == (try Goldens.vocabulary("member_modes")))
        #expect(Runner.allCases.map(\.rawValue) == (try Goldens.vocabulary("remedy_runners")))
        #expect(WakeMode.allCases.map(\.rawValue) == (try Goldens.vocabulary("wake_modes")))
        #expect(RunStatus.allCases.map(\.rawValue) == (try Goldens.vocabulary("run_statuses")))
        #expect(RunPresence.allCases.map(\.rawValue) == (try Goldens.vocabulary("run_presences")))
    }

    /// A chat's words: an item, a detail, a tool kind, a change kind or a decision the server adds
    /// turns this red until the app reads it.
    @Test func eachChatEnumHoldsTheWordsOfTheServersChatInItsOrder() throws {
        #expect(ChatItemType.allCases.map(\.rawValue) == (try Goldens.vocabulary("chat_item_types")))
        #expect(ChatDetailType.allCases.map(\.rawValue) == (try Goldens.vocabulary("chat_detail_types")))
        #expect(ChatToolKind.allCases.map(\.rawValue) == (try Goldens.vocabulary("chat_tool_kinds")))
        #expect(ChatChangeKind.allCases.map(\.rawValue) == (try Goldens.vocabulary("chat_change_kinds")))
        #expect(ChatDecision.allCases.map(\.rawValue) == (try Goldens.vocabulary("chat_decisions")))
    }

    @Test func aKnownWordReadsAsItsCaseAndAnyOtherAsItself() {
        #expect(Open<SpecStatus>(rawValue: "in_progress") == .known(.inProgress))
        #expect(Open<SpecStatus>(rawValue: "drydock") == .unknown("drydock"))
        #expect(Open(SpecStatus.awaitingMerge).rawValue == "awaiting_merge")
        #expect(Open<SpecStatus>.unknown("drydock").rawValue == "drydock")
    }
}
