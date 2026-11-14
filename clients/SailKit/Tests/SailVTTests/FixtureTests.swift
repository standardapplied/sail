import SailKitTestSupport
import SailVT
import Testing

/// Scenario 11: each fixture, fed whole, a byte at a time and in 500 seeded chunkings, leaves the
/// screen, what it provoked and the modes its golden holds.
@Suite(.timeLimit(.minutes(5))) struct FixtureTests {
    @Test(arguments: try VTFixtures.all())
    func aFixtureFedWholeLeavesItsGolden(_ fixture: VTFixtures.Fixture) throws {
        #expect(try dump(fixture, fed: [try fixture.bytes]) == (try fixture.golden))
    }

    @Test(arguments: try VTFixtures.all())
    func aFixtureFedOneByteAtATimeLeavesItsGolden(_ fixture: VTFixtures.Fixture) throws {
        #expect(try dump(fixture, fed: try fixture.bytes.map { [$0] }) == (try fixture.golden))
    }

    @Test(arguments: try VTFixtures.all())
    func aFixtureFedIn500SeededChunkingsLeavesItsGoldenEachTime(_ fixture: VTFixtures.Fixture) throws {
        let bytes = try fixture.bytes
        let golden = try fixture.golden
        for seed in 1 ... 500 as ClosedRange<UInt64> {
            let fed = try dump(fixture, fed: chunking(of: bytes, seed: seed))
            #expect(fed == golden, "seed \(seed)")
            if fed != golden { return }
        }
    }

    @Test func everyStreamTheSpecNamesHasAFixture() throws {
        let names = Set(try VTFixtures.all().map(\.name))
        let named: Set = [
            "wrapping", "sgr-colors", "sgr-attributes", "cursor-shapes", "alternate-screen", "scroll-region", "wide-cjk",
            "emoji-zwj", "combining", "box-drawing", "osc8-links", "osc52", "device-status", "bracketed-paste",
            "kitty-push", "kitty-pop", "vim", "less",
        ]
        #expect(named.subtracting(names).isEmpty, "missing: \(named.subtracting(names).sorted())")
    }

    private func dump(_ fixture: VTFixtures.Fixture, fed chunks: [[UInt8]]) throws -> String {
        let screen = try Screen(size: GridSize(columns: fixture.columns, rows: fixture.rows), scrollbackBytes: 1 << 20, palette: .dark)
        var effects = Effects()
        for chunk in chunks {
            let fed = screen.feed(chunk)
            effects.reply += fed.reply
            effects.events += fed.events
        }
        return try screen.dump(after: effects)
    }
}
