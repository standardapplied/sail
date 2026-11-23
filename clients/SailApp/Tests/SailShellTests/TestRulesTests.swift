import Foundation
import Testing

/// Rules the tests of the app and of SailKit keep, read from their sources. A precondition a test
/// then relies on is `try #require`, never `#expect` (AGENTS.md, Tests): a failed `#expect` goes on,
/// and an index out of range or a failed force-cast after it ends the suite's whole process, every
/// test still running in it, with no word of what failed.
@Suite struct TestRulesTests {
    /// Every test source of the app and of SailKit, as its lines trimmed.
    static func sources() throws -> [(name: String, lines: [String])] {
        let clients = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        var sources: [(name: String, lines: [String])] = []
        for root in ["SailApp/Tests", "SailKit/Tests"] {
            let files = try #require(FileManager.default.enumerator(at: clients.appending(path: root), includingPropertiesForKeys: nil))
            for case let file as URL in files where file.pathExtension == "swift" {
                let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
                sources.append((file.lastPathComponent, lines))
            }
        }
        return sources
    }

    /// The line on which the statement starting at `start` ends: where its parentheses balance,
    /// those inside string literals not counted, so a closure's own lines are inside it.
    static func end(of start: Int, in lines: [String]) -> Int {
        var depth = 0, opened = false
        for index in start ..< lines.count {
            var quoted = false, escaped = false
            for character in lines[index] {
                if quoted {
                    if escaped { escaped = false } else if character == "\\" { escaped = true } else if character == "\"" { quoted = false }
                } else if character == "\"" {
                    quoted = true
                } else if character == "(" {
                    depth += 1
                    opened = true
                } else if character == ")" {
                    depth -= 1
                }
            }
            if opened, depth <= 0 { return index }
        }
        return lines.count - 1
    }

    /// The lines after the statement that ends at `end`, up to the next test or function, at most 12.
    static func after(_ end: Int, in lines: [String]) -> ArraySlice<String> {
        lines[(end + 1) ..< min(lines.count, end + 13)].prefix { !$0.hasPrefix("@Test") && !$0.hasPrefix("func ") }
    }

    /// The names whose count or prefix (`name.count`, `name.prefix(`) or whose type
    /// (`CFGetTypeID(name)`) the left side of an expectation's comparisons speaks of.
    static func expected(in statement: String) throws -> (counted: Set<String>, typed: Set<String>) {
        let clauses = statement.dropFirst("#expect(".count).components(separatedBy: "&&")
        let comparison = try NSRegularExpression(pattern: "==|>=|<=|!=|>|<")
        let left = clauses.map { clause in
            comparison.firstMatch(in: clause, range: NSRange(clause.startIndex..., in: clause)).flatMap { Range($0.range, in: clause) }.map { String(clause[..<$0.lowerBound]) } ?? clause
        }.joined(separator: " ")
        func names(_ pattern: String) throws -> Set<String> {
            let found = try NSRegularExpression(pattern: pattern).matches(in: left, range: NSRange(left.startIndex..., in: left))
            return Set(found.compactMap { Range($0.range(at: 1), in: left).map { String(left[$0]) } })
        }
        return (try names(#"(?<![\w.)\]])([A-Za-z_][\w.]*)\.(?:count|prefix\()"#), try names(#"CFGetTypeID\(([A-Za-z_][\w.]*)\)"#))
    }

    /// A helper never returns past a wait it only expected: its caller relies on the wait having
    /// held. Read as: an `#expect(await …)` followed, before the next expectation or closing brace,
    /// by a `return` of a value after the expectation's own end (a `return` inside its wait's
    /// closure is the closure's).
    @Test func noHelperReturnsPastAWaitItOnlyExpected() throws {
        var read = 0, faults: [String] = []
        for (name, lines) in try Self.sources() {
            read += 1
            for index in lines.indices where lines[index].hasPrefix("#expect(await ") {
                let following = Self.after(Self.end(of: index, in: lines), in: lines).prefix { !$0.hasPrefix("#expect") && !$0.hasPrefix("try #require") && !$0.hasPrefix("}") }
                if following.contains(where: { $0.hasPrefix("return ") }) { faults.append("\(name):\(index + 1)") }
            }
        }
        #expect(read >= 100, "read \(read) test sources")
        #expect(faults.isEmpty, "these return past a wait they only expected; require it: \(faults.joined(separator: ", "))")
    }

    /// A test never indexes what it only expected the count of, nor force-casts what it only
    /// expected the type of: `#expect(rows.count == 2)` then `rows[1]`, or
    /// `#expect(CFGetTypeID(image) == …)` then `image as!`, until a `try #require` of it.
    @Test func noTestIndexesOrForcesWhatItOnlyExpected() throws {
        var faults: [String] = []
        for (name, lines) in try Self.sources() {
            for index in lines.indices where lines[index].hasPrefix("#expect(") {
                let end = Self.end(of: index, in: lines)
                let (counted, typed) = try Self.expected(in: lines[index ... end].joined(separator: " "))
                let required = lines[max(0, index - 3) ..< index].filter { $0.hasPrefix("try #require") }
                for subject in counted.union(typed) where !required.contains(where: { $0.contains(subject) }) {
                    let indexed = try NSRegularExpression(pattern: #"(?<![\w.])"# + NSRegularExpression.escapedPattern(for: subject) + #"\[[0-9]"#)
                    for line in Self.after(end, in: lines) {
                        if line.hasPrefix("try #require"), line.contains(subject) { break }
                        let indexes = counted.contains(subject) && indexed.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) != nil
                        if indexes || (typed.contains(subject) && line.contains("\(subject) as!")) {
                            faults.append("\(name):\(index + 1) (\(subject))")
                            break
                        }
                    }
                }
            }
        }
        #expect(faults.isEmpty, "these rely on what they only expected; require it: \(faults.joined(separator: ", "))")
    }
}
