@testable import SailSurface
import Testing

@Suite struct SpriteTests {
    static let sizes: [(points: Double, scale: Double)] = [(10, 1), (10, 2), (15, 1), (15, 2), (32, 2)]
    static let lineCharacters: [UInt32] = Array(0x2500 ... 0x257F).filter { BoxDrawing.arms(of: $0) != nil }

    /// The pixels a line of `weight` covers along an edge of `length`.
    private func profile(_ weight: BoxDrawing.Weight, _ length: Int, stroke: Int) -> [Bool] {
        let bands = BoxDrawing.Bands(width: length, height: length, stroke: stroke)
        let light = bands.x(.light)
        return (0 ..< length).map { pixel in
            switch weight {
            case .none: false
            case .light, .heavy: bands.x(weight).contains(pixel)
            case .double: (light.lowerBound - stroke ..< light.lowerBound).contains(pixel) || (light.upperBound ..< light.upperBound + stroke).contains(pixel)
            }
        }
    }

    /// Scenario 2: at 10, 15 and 32 pt every line character leaves each edge exactly where a line
    /// of its arm's weight enters the next cell, fully covered, and covers nothing on an edge it
    /// has no arm on: so neighbouring cells meet with no gap and no overlap.
    @Test(arguments: sizes) func everyLineMeetsItsNeighbourAtTheCellEdge(size: (points: Double, scale: Double)) throws {
        let cell = try metrics(points: size.points, scale: size.scale)
        for scalar in Self.lineCharacters {
            let arms = try #require(BoxDrawing.arms(of: scalar))
            let bitmap = Sprite.box(scalar).render(cell)
            let top = (0 ..< cell.width).map { bitmap[$0, 0] == 255 }
            let bottom = (0 ..< cell.width).map { bitmap[$0, cell.height - 1] == 255 }
            let left = (0 ..< cell.height).map { bitmap[0, $0] == 255 }
            let right = (0 ..< cell.height).map { bitmap[cell.width - 1, $0] == 255 }
            let anyTop = (0 ..< cell.width).contains { bitmap[$0, 0] != 0 }
            #expect(top == profile(arms.up, cell.width, stroke: cell.stroke), "U+\(String(scalar, radix: 16)) top at \(size)")
            #expect(bottom == profile(arms.down, cell.width, stroke: cell.stroke), "U+\(String(scalar, radix: 16)) bottom at \(size)")
            #expect(left == heightProfile(arms.left, cell), "U+\(String(scalar, radix: 16)) left at \(size)")
            #expect(right == heightProfile(arms.right, cell), "U+\(String(scalar, radix: 16)) right at \(size)")
            #expect(anyTop == (arms.up != .none), "U+\(String(scalar, radix: 16)) partial coverage on the top edge")
        }
    }

    private func heightProfile(_ weight: BoxDrawing.Weight, _ cell: CellMetrics) -> [Bool] {
        let bands = BoxDrawing.Bands(width: cell.width, height: cell.height, stroke: cell.stroke)
        let light = bands.y(.light)
        return (0 ..< cell.height).map { pixel in
            switch weight {
            case .none: false
            case .light, .heavy: bands.y(weight).contains(pixel)
            case .double: (light.lowerBound - cell.stroke ..< light.lowerBound).contains(pixel) || (light.upperBound ..< light.upperBound + cell.stroke).contains(pixel)
            }
        }
    }

    @Test func aDoubleCornerJoinsItsOuterLinesAndItsInnerLines() throws {
        let cell = try metrics(points: 15)
        let corner = Sprite.box(0x2554).render(cell)
        let bands = BoxDrawing.Bands(width: cell.width, height: cell.height, stroke: cell.stroke)
        let outerX = bands.x(.light).lowerBound - cell.stroke
        let outerY = bands.y(.light).lowerBound - cell.stroke
        let innerX = bands.x(.light).upperBound
        let innerY = bands.y(.light).upperBound
        #expect(corner[outerX, outerY] == 255, "╔'s outer corner is drawn")
        #expect(corner[innerX, innerY] == 255, "╔'s inner corner is drawn")
        #expect(corner[innerX - 1, innerY - 1] == 0, "╔'s gap between its lines stays open")
        #expect(corner[innerX - 1, innerY] == 0, "╔'s inner across line starts at the inner down line")
        #expect(corner[innerX, innerY - 1] == 0, "╔'s inner down line starts at the inner across line")
        #expect(corner[outerX - 1, outerY - 1] == 0, "nothing outside the outer corner")
    }

    @Test(arguments: sizes) func roundedCornersLeaveTheirEdgesAsStraightLines(size: (points: Double, scale: Double)) throws {
        let cell = try metrics(points: size.points, scale: size.scale)
        let light = profile(.light, cell.width, stroke: cell.stroke)
        let lightDown = heightProfile(.light, cell)
        let arcs: [(UInt32, top: Bool, right: Bool)] = [(0x256D, false, true), (0x256E, false, false), (0x256F, true, false), (0x2570, true, true)]
        for (scalar, top, right) in arcs {
            let bitmap = Sprite.box(scalar).render(cell)
            let edgeRow = top ? 0 : cell.height - 1
            let edgeColumn = right ? cell.width - 1 : 0
            #expect((0 ..< cell.width).map { bitmap[$0, edgeRow] == 255 } == light, "U+\(String(scalar, radix: 16)) at \(size)")
            #expect((0 ..< cell.height).map { bitmap[edgeColumn, $0] == 255 } == lightDown, "U+\(String(scalar, radix: 16)) at \(size)")
        }
    }

    @Test(arguments: sizes) func blocksTileTheCellWithoutOverlap(size: (points: Double, scale: Double)) throws {
        let cell = try metrics(points: size.points, scale: size.scale)
        func covered(_ scalar: UInt32) -> [Bool] {
            Sprite.box(scalar).render(cell).pixels.map { $0 == 255 }
        }
        let pairs: [(UInt32, UInt32)] = [(0x2580, 0x2584), (0x258C, 0x2590), (0x2598, 0x259F), (0x259D, 0x2599), (0x259A, 0x259E)]
        for (one, other) in pairs {
            let a = covered(one)
            let b = covered(other)
            #expect(zip(a, b).allSatisfy { $0 != $1 }, "U+\(String(one, radix: 16)) and U+\(String(other, radix: 16)) at \(size)")
        }
        #expect(covered(0x2588).allSatisfy { $0 })
        #expect(Sprite.box(0x2592).render(cell).pixels.allSatisfy { $0 == 128 })
        #expect(covered(0x2581).filter { $0 }.count == cell.width * ((cell.height + 4) / 8))
    }

    @Test func onlyBoxDrawingAndBlocksAreSprites() {
        #expect(Sprite.drawing("─") == .box(0x2500))
        #expect(Sprite.drawing("▟") == .box(0x259F))
        #expect(Sprite.drawing("⠿") == nil, "braille comes from the font")
        #expect(Sprite.drawing("a") == nil)
        #expect(Sprite.drawing("─\u{301}") == nil)
    }

    @Test func linesAndCursorsSitWhereTheCellSays() throws {
        let cell = try metrics(points: 15)
        let underline = Sprite.underline(.single).render(cell)
        let rows = (0 ..< cell.height).filter { underline[0, $0] == 255 }
        #expect(rows == Array(cell.underlineTop ..< cell.underlineTop + cell.stroke))
        let strike = Sprite.strikethrough.render(cell, cells: 2)
        #expect(strike.width == 2 * cell.width)
        #expect((0 ..< strike.width).allSatisfy { strike[$0, cell.strikethroughTop] == 255 })
        let hollow = Sprite.cursor(.hollow).render(cell)
        #expect(hollow[0, 0] == 255 && hollow[cell.width / 2, cell.height / 2] == 0)
        #expect(Sprite.cursor(.block).render(cell).pixels.allSatisfy { $0 == 255 })
        for style: SailSurface.Sprite in [.underline(.double), .underline(.curly), .underline(.dotted), .underline(.dashed), .overline, .cursor(.bar)] {
            #expect(style.render(cell).pixels.contains { $0 > 0 }, "\(style) draws something")
        }
    }
}
