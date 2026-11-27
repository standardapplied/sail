/// The design's own line icons, as its SVGs draw them: strokes in a 24-unit box, y down, 1.75 wide
/// with round caps and joins (the ⋯ is three filled dots). Drawn by the app, so every icon a control
/// shows is the design's, not a system symbol.
public enum LineIcon: String, CaseIterable, Sendable {
    case refresh, hammer, plus, more, info, warning, error, box, layers, cloudOff, close
    /// The welcome's: the control plane's chip, a passkey's Touch ID, a passkey made.
    case cloud, fingerprint, check
    /// A folder's disclosure: shut (pointing along the line, mirrored right to left) and open.
    case right, down
    /// This Mac's: its group's head, its project's tile and a shell, and its page's "not synced".
    case laptop, terminal, lock

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
        case .close: return [line((7, 7), (17, 17)), line((17, 7), (7, 17))]
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
        case .cloud:
            return [
                line((7, 18.5), (17.5, 18.5)), .arc(Point(7.44, 14.02), radius: 4.5, from: 95.6, to: 259.3),
                .arc(Point(12.21, 11.72), radius: 6, from: -159.3, to: -11.2), .arc(Point(17.47, 14.5), radius: 4, from: -80.9, to: 89.6),
            ]
        case .fingerprint:
            return [
                line((12, 11.5), (12, 15.5)), .arc(Point(11.9, 11.44), radius: 3.6, from: -156.5, to: 1),
                line((15.5, 11.5), (15.5, 13.7), (15.44, 14.77), (15.23, 15.82), (14.88, 16.84), (14.4, 17.8)),
                line((8.4, 13.4), (8.29, 15.1), (7.97, 16.5), (7.47, 17.67), (6.8, 18.7)),
                .arc(Point(12, 12.21), radius: 7.4, from: -149.9, to: -1.6), line((19.4, 12), (19.4, 13.6)), line((4.6, 12.6), (4.6, 13.8)),
                line((17.2, 18.6), (17.61, 17.82), (17.95, 16.97), (18.21, 16.1), (18.4, 15.2)),
            ]
        case .check: return [line((5, 12.5), (9.5, 17), (19, 7.5))]
        case .right: return [line((9, 6), (15, 12), (9, 18))]
        case .down: return [line((6, 9), (12, 15), (18, 9))]
        case .more: return [.dot(Point(5.5, 12), radius: 1.2), .dot(Point(12, 12), radius: 1.2), .dot(Point(18.5, 12), radius: 1.2)]
        case .laptop: return Self.box(4.5, 5, 15, 10.5, corner: 1.5) + [line((2.5, 18.5), (21.5, 18.5))]
        case .terminal: return Self.box(3, 4.5, 18, 15, corner: 2.5) + [line((7.5, 9.5), (10.5, 12), (7.5, 14.5)), line((12.5, 15), (16.5, 15))]
        case .lock: return Self.box(5, 10.5, 14, 9.5, corner: 2) + [line((8.5, 10.5), (8.5, 8)), .arc(Point(12, 8), radius: 3.5, from: 180, to: 360), line((15.5, 8), (15.5, 10.5))]
        }
    }

    /// A rectangle with round corners, as the design's SVG `rect` with `rx` draws it: its sides and
    /// its corners' quarter arcs, clockwise from the top.
    private static func box(_ x: Double, _ y: Double, _ width: Double, _ height: Double, corner r: Double) -> [Stroke] {
        let right = x + width, bottom = y + height
        return [
            .lines([Point(x + r, y), Point(right - r, y)], closed: false), .arc(Point(right - r, y + r), radius: r, from: -90, to: 0),
            .lines([Point(right, y + r), Point(right, bottom - r)], closed: false), .arc(Point(right - r, bottom - r), radius: r, from: 0, to: 90),
            .lines([Point(right - r, bottom), Point(x + r, bottom)], closed: false), .arc(Point(x + r, bottom - r), radius: r, from: 90, to: 180),
            .lines([Point(x, bottom - r), Point(x, y + r)], closed: false), .arc(Point(x + r, y + r), radius: r, from: 180, to: 270),
        ]
    }
}
