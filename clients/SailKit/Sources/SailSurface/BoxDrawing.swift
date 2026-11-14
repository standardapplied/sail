/// Box drawing (U+2500-257F) and block elements (U+2580-259F), drawn on whole pixels so that a
/// line leaves every cell at the same place it enters the next. A light line is `stroke` thick,
/// centred; a heavy one twice that; a double line is two light lines either side of where the
/// light one would be.
enum BoxDrawing {
    static func covers(_ scalar: UInt32) -> Bool {
        (0x2500 ... 0x259F).contains(scalar)
    }

    enum Weight: Int, Comparable {
        case none, light, heavy, double

        static func < (left: Weight, right: Weight) -> Bool {
            left.rawValue < right.rawValue
        }

        init(_ code: Character) {
            switch code {
            case "l": self = .light
            case "h": self = .heavy
            case "d": self = .double
            default: self = .none
            }
        }
    }

    /// The arms of a line character: up, right, down, left.
    struct Arms: Equatable {
        let up: Weight
        let right: Weight
        let down: Weight
        let left: Weight

        init(_ code: String) {
            let weights = code.map(Weight.init)
            up = weights[0]
            right = weights[1]
            down = weights[2]
            left = weights[3]
        }
    }

    /// U+2500-254B, with `nil` for the dashed lines U+2504-250B.
    private static let lines: [String?] = [
        ".l.l", ".h.h", "l.l.", "h.h.", nil, nil, nil, nil, nil, nil, nil, nil,
        ".ll.", ".hl.", ".lh.", ".hh.", "..ll", "..lh", "..hl", "..hh",
        "ll..", "lh..", "hl..", "hh..", "l..l", "l..h", "h..l", "h..h",
        "lll.", "lhl.", "hll.", "llh.", "hlh.", "hhl.", "lhh.", "hhh.",
        "l.ll", "l.lh", "h.ll", "l.hl", "h.hl", "h.lh", "l.hh", "h.hh",
        ".lll", ".llh", ".hll", ".hlh", ".lhl", ".lhh", ".hhl", ".hhh",
        "ll.l", "ll.h", "lh.l", "lh.h", "hl.l", "hl.h", "hh.l", "hh.h",
        "llll", "lllh", "lhll", "lhlh", "hlll", "llhl", "hlhl", "hllh",
        "hhll", "llhh", "lhhl", "hhlh", "lhhh", "hlhh", "hhhl", "hhhh",
    ]

    /// U+2550-256C.
    private static let doubles: [String] = [
        ".d.d", "d.d.", ".dl.", ".ld.", ".dd.", "..ld", "..dl", "..dd",
        "ld..", "dl..", "dd..", "l..d", "d..l", "d..d", "ldl.", "dld.",
        "ddd.", "l.ld", "d.dl", "d.dd", ".dld", ".ldl", ".ddd", "ld.d",
        "dl.l", "dd.d", "ldld", "dldl", "dddd",
    ]

    /// U+2574-257F.
    private static let halves: [String] = [
        "...l", "l...", ".l..", "..l.", "...h", "h...", ".h..", "..h.", ".h.l", "l.h.", ".l.h", "h.l.",
    ]

    /// The arms of `scalar`, nil when it is no plain line character.
    static func arms(of scalar: UInt32) -> Arms? {
        switch scalar {
        case 0x2500 ... 0x254B: lines[Int(scalar - 0x2500)].map(Arms.init)
        case 0x2550 ... 0x256C: Arms(doubles[Int(scalar - 0x2550)])
        case 0x2574 ... 0x257F: Arms(halves[Int(scalar - 0x2574)])
        default: nil
        }
    }

    static func draw(_ scalar: UInt32, into bitmap: inout AlphaBitmap, stroke: Int) {
        let bands = Bands(width: bitmap.width, height: bitmap.height, stroke: stroke)
        if let arms = arms(of: scalar) {
            bands.draw(arms, into: &bitmap)
            return
        }
        switch scalar {
        case 0x2504 ... 0x250B: bands.dashes(scalar - 0x2504, base: 3, into: &bitmap)
        case 0x254C ... 0x254F: bands.dashes(scalar - 0x254C, base: 2, into: &bitmap)
        case 0x256D: bands.arc(right: true, down: true, into: &bitmap)
        case 0x256E: bands.arc(right: false, down: true, into: &bitmap)
        case 0x256F: bands.arc(right: false, down: false, into: &bitmap)
        case 0x2570: bands.arc(right: true, down: false, into: &bitmap)
        case 0x2571 ... 0x2573: bands.diagonals(scalar, into: &bitmap)
        case 0x2580 ... 0x259F: block(scalar, into: &bitmap)
        default: break
        }
    }

    /// Where each weight's line lies across a cell: `x` for vertical lines, `y` for horizontal.
    struct Bands {
        let width: Int
        let height: Int
        let stroke: Int

        func across(_ length: Int, _ weight: Weight) -> Range<Int> {
            let thickness = weight == .heavy ? 2 * stroke : stroke
            let start = (length - thickness) / 2
            return start ..< start + thickness
        }

        func x(_ weight: Weight) -> Range<Int> {
            across(width, weight)
        }

        func y(_ weight: Weight) -> Range<Int> {
            across(height, weight)
        }

        func draw(_ arms: Arms, into bitmap: inout AlphaBitmap) {
            if arms.up != .none {
                for (columns, end) in vertical(arms.up, arms, toward: .up) {
                    bitmap.fill(columns.lowerBound, 0, columns.upperBound, end)
                }
            }
            if arms.down != .none {
                for (columns, start) in vertical(arms.down, arms, toward: .down) {
                    bitmap.fill(columns.lowerBound, start, columns.upperBound, height)
                }
            }
            if arms.left != .none {
                for (rows, end) in horizontal(arms.left, arms, toward: .left) {
                    bitmap.fill(0, rows.lowerBound, end, rows.upperBound)
                }
            }
            if arms.right != .none {
                for (rows, start) in horizontal(arms.right, arms, toward: .right) {
                    bitmap.fill(start, rows.lowerBound, width, rows.upperBound)
                }
            }
        }

        enum Toward { case up, down, left, right }

        /// The column ranges of an up or down arm, and where each one stops short of the far edge.
        private func vertical(_ weight: Weight, _ arms: Arms, toward: Toward) -> [(Range<Int>, Int)] {
            let up = toward == .up
            let opposite = up ? arms.down : arms.up
            let stop = Stops(along: y(.light), stroke: stroke, nearIsLow: up)
            let single = max(arms.left == .double ? .none : arms.left, arms.right == .double ? .none : arms.right)
            let lightX = x(.light)
            if weight != .double {
                let end: Int
                if single != .none {
                    end = stop.far(of: y(single))
                } else if arms.left == .double || arms.right == .double {
                    end = arms.left == .double && arms.right == .double && opposite == .none ? stop.nearLineFar : stop.farLineFar
                } else {
                    end = stop.far(of: y(weight))
                }
                return [(x(weight), end)]
            }
            func line(_ perpendicular: Weight) -> Int {
                if perpendicular == .double { return stop.nearLineFar }
                if single != .none || opposite == .double { return stop.far(of: y(.light)) }
                return stop.farLineFar
            }
            return [
                (lightX.lowerBound - stroke ..< lightX.lowerBound, line(arms.left)),
                (lightX.upperBound ..< lightX.upperBound + stroke, line(arms.right)),
            ]
        }

        /// The row ranges of a left or right arm, and where each one stops short of the far edge.
        private func horizontal(_ weight: Weight, _ arms: Arms, toward: Toward) -> [(Range<Int>, Int)] {
            let left = toward == .left
            let opposite = left ? arms.right : arms.left
            let stop = Stops(along: x(.light), stroke: stroke, nearIsLow: left)
            let single = max(arms.up == .double ? .none : arms.up, arms.down == .double ? .none : arms.down)
            let lightY = y(.light)
            if weight != .double {
                let end: Int
                if single != .none {
                    end = stop.far(of: x(single))
                } else if arms.up == .double || arms.down == .double {
                    end = arms.up == .double && arms.down == .double && opposite == .none ? stop.nearLineFar : stop.farLineFar
                } else {
                    end = stop.far(of: x(weight))
                }
                return [(y(weight), end)]
            }
            func line(_ perpendicular: Weight) -> Int {
                if perpendicular == .double { return stop.nearLineFar }
                if single != .none || opposite == .double { return stop.far(of: x(.light)) }
                return stop.farLineFar
            }
            return [
                (lightY.lowerBound - stroke ..< lightY.lowerBound, line(arms.up)),
                (lightY.upperBound ..< lightY.upperBound + stroke, line(arms.down)),
            ]
        }

        /// Stopping places for an arm coming in from one edge across the perpendicular lines:
        /// "far" is the side away from the arm's own edge.
        struct Stops {
            let along: Range<Int>
            let stroke: Int
            /// The arm comes from the low edge (top or left), so it ends at an upper bound;
            /// otherwise from the high edge, and it starts at a lower bound.
            let nearIsLow: Bool

            func far(of band: Range<Int>) -> Int {
                nearIsLow ? band.upperBound : band.lowerBound
            }

            /// The far side of the double line nearer the arm's edge.
            var nearLineFar: Int {
                nearIsLow ? along.lowerBound : along.upperBound
            }

            /// The far side of the double line farther from the arm's edge.
            var farLineFar: Int {
                nearIsLow ? along.upperBound + stroke : along.lowerBound - stroke
            }
        }

        /// U+2504-250B (`base` 3) and U+254C-254F (`base` 2): light or heavy, across or down, in
        /// `base` or (for the second four of the first set) four dashes, gaps split between cells.
        func dashes(_ index: UInt32, base: Int, into bitmap: inout AlphaBitmap) {
            let count = base == 3 && index >= 4 ? 4 : base
            let weight: Weight = index % 2 == 0 ? .light : .heavy
            let vertical = index % 4 >= 2
            let length = vertical ? height : width
            let segment = Double(length) / Double(count)
            let gap = max(1.0, (segment / 4).rounded())
            for dash in 0 ..< count {
                let start = Int((Double(dash) * segment + gap / 2).rounded())
                let end = Int((Double(dash + 1) * segment - gap / 2).rounded())
                if vertical {
                    bitmap.fill(x(weight).lowerBound, start, x(weight).upperBound, end)
                } else {
                    bitmap.fill(start, y(weight).lowerBound, end, y(weight).upperBound)
                }
            }
        }

        /// A rounded corner joining the light lines from the bottom or top edge and from the right
        /// or left edge, with a straight pixel at least where it leaves each edge.
        func arc(right: Bool, down: Bool, into bitmap: inout AlphaBitmap) {
            let columns = x(.light)
            let rows = y(.light)
            let cx = Double(columns.lowerBound + columns.upperBound) / 2
            let cy = Double(rows.lowerBound + rows.upperBound) / 2
            let radius = max(0, min(right ? Double(width) - cx : cx, down ? Double(height) - cy : cy) - 1)
            let centreX = cx + (right ? radius : -radius)
            let centreY = cy + (down ? radius : -radius)
            let half = Double(stroke) / 2
            bitmap.shade { px, py in
                guard (px - centreX) * (right ? 1 : -1) <= 0, (py - centreY) * (down ? 1 : -1) <= 0 else { return false }
                let dx = px - centreX
                let dy = py - centreY
                return abs((dx * dx + dy * dy).squareRoot() - radius) <= half
            }
            if down {
                bitmap.fill(columns.lowerBound, Int(centreY.rounded(.down)), columns.upperBound, height)
            } else {
                bitmap.fill(columns.lowerBound, 0, columns.upperBound, Int(centreY.rounded(.up)))
            }
            if right {
                bitmap.fill(Int(centreX.rounded(.down)), rows.lowerBound, width, rows.upperBound)
            } else {
                bitmap.fill(0, rows.lowerBound, Int(centreX.rounded(.up)), rows.upperBound)
            }
        }

        /// ╱ (U+2571), ╲ (U+2572) and ╳ (U+2573), corner to corner.
        func diagonals(_ scalar: UInt32, into bitmap: inout AlphaBitmap) {
            let w = Double(width)
            let h = Double(height)
            let length = (w * w + h * h).squareRoot()
            let half = Double(stroke) / 2
            let rising = scalar != 0x2572
            let falling = scalar != 0x2571
            bitmap.shade { px, py in
                let fromFalling = abs(h * px - w * py) / length
                let fromRising = abs(h * px + w * py - w * h) / length
                return (falling && fromFalling <= half) || (rising && fromRising <= half)
            }
        }
    }

    /// U+2580-259F: halves, eighths, shades and quadrants, edges on whole pixels.
    static func block(_ scalar: UInt32, into bitmap: inout AlphaBitmap) {
        let w = bitmap.width
        let h = bitmap.height
        func eighth(_ length: Int, _ count: Int) -> Int {
            (length * count + 4) / 8
        }
        let midX = eighth(w, 4)
        let midY = h - eighth(h, 4)
        switch scalar {
        case 0x2580: bitmap.fill(0, 0, w, midY)
        case 0x2581 ... 0x2588: bitmap.fill(0, h - eighth(h, Int(scalar - 0x2580)), w, h)
        case 0x2589 ... 0x258F: bitmap.fill(0, 0, eighth(w, Int(0x2590 - scalar)), h)
        case 0x2590: bitmap.fill(midX, 0, w, h)
        case 0x2591: bitmap.fill(0, 0, w, h, 64)
        case 0x2592: bitmap.fill(0, 0, w, h, 128)
        case 0x2593: bitmap.fill(0, 0, w, h, 191)
        case 0x2594: bitmap.fill(0, 0, w, eighth(h, 1))
        case 0x2595: bitmap.fill(w - eighth(w, 1), 0, w, h)
        default:
            let quadrants: [UInt32: [Int]] = [
                0x2596: [2], 0x2597: [3], 0x2598: [0], 0x2599: [0, 2, 3], 0x259A: [0, 3], 0x259B: [0, 1, 2],
                0x259C: [0, 1, 3], 0x259D: [1], 0x259E: [1, 2], 0x259F: [1, 2, 3],
            ]
            for quadrant in quadrants[scalar] ?? [] {
                let left = quadrant % 2 == 0
                let top = quadrant < 2
                bitmap.fill(left ? 0 : midX, top ? 0 : midY, left ? midX : w, top ? midY : h)
            }
        }
    }
}
