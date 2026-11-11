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
    }

    @Test func aKnownWordReadsAsItsCaseAndAnyOtherAsItself() {
        #expect(Open<SpecStatus>(rawValue: "in_progress") == .known(.inProgress))
        #expect(Open<SpecStatus>(rawValue: "drydock") == .unknown("drydock"))
        #expect(Open(SpecStatus.awaitingMerge).rawValue == "awaiting_merge")
        #expect(Open<SpecStatus>.unknown("drydock").rawValue == "drydock")
    }
}
