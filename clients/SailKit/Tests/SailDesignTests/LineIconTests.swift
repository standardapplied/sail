import SailDesign
import Testing

/// The icons are the design's SVGs, point for point, inside their 24-unit box.
@Suite struct LineIconTests {
    @Test func everyIconStaysInItsBox() {
        for icon in LineIcon.allCases {
            #expect(!icon.strokes.isEmpty, "\(icon) draws nothing")
            for stroke in icon.strokes {
                let points: [LineIcon.Point] = switch stroke {
                case let .lines(points, _): points
                case let .circle(centre, radius), let .arc(centre, radius, _, _), let .dot(centre, radius):
                    [LineIcon.Point(centre.x - radius, centre.y - radius), LineIcon.Point(centre.x + radius, centre.y + radius)]
                }
                #expect(points.allSatisfy { (0 ... LineIcon.side).contains($0.x) && (0 ... LineIcon.side).contains($0.y) }, "\(icon) leaves its box")
            }
        }
    }

    /// The SVG paths, as `workspaces-mockup.html` writes them.
    @Test func theIconsAreTheMockups() {
        #expect(LineIcon.hammer.strokes == [
            .lines([.init(14.5, 4.5), .init(19.5, 9.5), .init(17, 12), .init(12, 7)], closed: true),
            .lines([.init(12, 7), .init(4, 15), .init(7, 18), .init(15, 10)], closed: false),
        ])
        #expect(LineIcon.refresh.strokes.first == .arc(.init(12, 12), radius: 8.5, from: 0, to: 314.04))
        #expect(LineIcon.refresh.strokes.last == .lines([.init(20.5, 4), .init(20.5, 8.5), .init(16, 8.5)], closed: false))
        #expect(LineIcon.more.strokes == [.dot(.init(5.5, 12), radius: 1.2), .dot(.init(12, 12), radius: 1.2), .dot(.init(18.5, 12), radius: 1.2)])
        #expect(LineIcon.width == 1.75 && LineIcon.side == 24)
    }
}
