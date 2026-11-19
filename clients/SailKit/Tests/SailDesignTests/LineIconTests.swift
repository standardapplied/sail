import Foundation
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

    /// The cloud's outline (`M7 18.5h10.2a4.3 4.3 0 0 0 .7-8.5 6 6 0 0 0-11.5 1.3A3.6 3.6 0 0 0 7 18.5z`)
    /// drawn as its arcs, clockwise from the bottom left, each meeting the next where the path does.
    @Test func theCloudIsOneOutline() {
        var ends: [(LineIcon.Point, LineIcon.Point)] = []
        for stroke in LineIcon.cloudOff.strokes {
            switch stroke {
            case let .arc(centre, radius, from, to):
                let at = { (angle: Double) in LineIcon.Point(centre.x + radius * cos(angle * .pi / 180), centre.y + radius * sin(angle * .pi / 180)) }
                ends.append((at(from), at(to)))
            case let .lines(points, _): ends.append((points[0], points[points.count - 1]))
            default: Issue.record("the cloud draws \(stroke)")
            }
        }
        let path = [(7.0, 18.5), (6.4, 11.3), (17.9, 10.0), (17.2, 18.5), (7.0, 18.5)]
        for (index, (start, end)) in ends.prefix(4).enumerated() {
            #expect(abs(start.x - path[index].0) < 0.05 && abs(start.y - path[index].1) < 0.05, "stroke \(index) starts at \(start)")
            #expect(abs(end.x - path[index + 1].0) < 0.05 && abs(end.y - path[index + 1].1) < 0.05, "stroke \(index) ends at \(end)")
        }
        #expect(LineIcon.cloudOff.strokes.last == .lines([.init(4, 4), .init(20, 20)], closed: false))
    }

    static func line(_ points: (Double, Double)..., closed: Bool = false) -> LineIcon.Stroke {
        .lines(points.map { LineIcon.Point($0.0, $0.1) }, closed: closed)
    }

    /// The rest of the mockup's `IC` table, path for path.
    @Test func theOtherIconsAreTheMockupsToo() {
        let ring = LineIcon.Stroke.circle(.init(12, 12), radius: 9)
        #expect(LineIcon.plus.strokes == [Self.line((12, 5), (12, 19)), Self.line((5, 12), (19, 12))])
        #expect(LineIcon.box.strokes == [
            Self.line((12, 2.8), (20.2, 7.4), (20.2, 16.6), (12, 21.2), (3.8, 16.6), (3.8, 7.4), closed: true),
            Self.line((3.8, 7.4), (12, 12), (20.2, 7.4)), Self.line((12, 12), (12, 21.2)),
        ])
        #expect(LineIcon.layers.strokes == [
            Self.line((12, 3.5), (20.5, 8.1), (12, 12.7), (3.5, 8.1), closed: true), Self.line((3.5, 12), (12, 16.6), (20.5, 12)),
            Self.line((3.5, 15.9), (12, 20.5), (20.5, 15.9)),
        ])
        #expect(LineIcon.info.strokes == [ring, Self.line((12, 11), (12, 16.5)), Self.line((12, 7.6), (12, 7.7))])
        #expect(LineIcon.warning.strokes == [Self.line((12, 3.8), (21, 19.5), (3, 19.5), closed: true), Self.line((12, 10), (12, 14.2)), Self.line((12, 17), (12, 17.1))])
        #expect(LineIcon.error.strokes == [ring, Self.line((12, 7.5), (12, 13)), Self.line((12, 16.2), (12, 16.3))])
    }
}
