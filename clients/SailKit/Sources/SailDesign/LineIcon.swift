/// The design's own line icons, as its SVGs draw them: strokes in a 24-unit box, y down, 1.75 wide
/// with round caps and joins (the ⋯ is three filled dots). Drawn by the app, so every icon a control
/// shows is the design's, not a system symbol.
public enum LineIcon: String, CaseIterable, Sendable {
    case refresh, hammer, plus, more, info, warning, error, box, layers, cloudOff
    /// A folder's disclosure: shut (pointing along the line, mirrored right to left) and open.
    case right, down

    public static let side = 24.0, width = 1.75

    public struct Point: Equatable, Sendable {
        public let x: Double, y: Double

        public init(_ x: Double, _ y: Double) {
            self.x = x
            self.y = y
        }
    }

    /// One stroke: a polyline (closed or not), a circle, an arc whose angle grows from `from` to `to`
    /// degrees (clockwise on screen, y down), or a filled dot.
    public enum Stroke: Equatable, Sendable {
        case lines([Point], closed: Bool)
        case circle(Point, radius: Double)
        case arc(Point, radius: Double, from: Double, to: Double)
        case dot(Point, radius: Double)
    }

    public var strokes: [Stroke] {
        func line(_ points: (Double, Double)..., closed: Bool = false) -> Stroke {
            .lines(points.map { Point($0.0, $0.1) }, closed: closed)
        }
        switch self {
        case .refresh: return [.arc(Point(12, 12), radius: 8.5, from: 0, to: 314.04), line((20.5, 4), (20.5, 8.5), (16, 8.5))]
        case .hammer: return [line((14.5, 4.5), (19.5, 9.5), (17, 12), (12, 7), closed: true), line((12, 7), (4, 15), (7, 18), (15, 10))]
        case .plus: return [line((12, 5), (12, 19)), line((5, 12), (19, 12))]
        case .box:
            return [
                line((12, 2.8), (20.2, 7.4), (20.2, 16.6), (12, 21.2), (3.8, 16.6), (3.8, 7.4), closed: true), line((3.8, 7.4), (12, 12), (20.2, 7.4)),
                line((12, 12), (12, 21.2)),
            ]
        case .layers:
            return [
                line((12, 3.5), (20.5, 8.1), (12, 12.7), (3.5, 8.1), closed: true), line((3.5, 12), (12, 16.6), (20.5, 12)),
                line((3.5, 15.9), (12, 20.5), (20.5, 15.9)),
            ]
        case .info: return [.circle(Point(12, 12), radius: 9), line((12, 11), (12, 16.5)), line((12, 7.6), (12, 7.7))]
        case .warning: return [line((12, 3.8), (21, 19.5), (3, 19.5), closed: true), line((12, 10), (12, 14.2)), line((12, 17), (12, 17.1))]
        case .error: return [.circle(Point(12, 12), radius: 9), line((12, 7.5), (12, 13)), line((12, 16.2), (12, 16.3))]
        case .cloudOff:
            return [
                .arc(Point(6.7, 14.9), radius: 3.61, from: 85.2, to: 265.2), .arc(Point(12.33, 12.23), radius: 6, from: -171.1, to: -21.8),
                .arc(Point(17, 14.2), radius: 4.3, from: -77.9, to: 87.3), line((17.2, 18.5), (7, 18.5)), line((4, 4), (20, 20)),
            ]
        case .right: return [line((9, 6), (15, 12), (9, 18))]
        case .down: return [line((6, 9), (12, 15), (18, 9))]
        case .more: return [.dot(Point(5.5, 12), radius: 1.2), .dot(Point(12, 12), radius: 1.2), .dot(Point(18.5, 12), radius: 1.2)]
        }
    }
}
