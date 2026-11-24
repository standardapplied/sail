import Foundation

/// A web address in a terminal's line, as a person reads one: `http://` or `https://` and what
/// follows up to a space or a character no address holds, less the punctuation that ends a
/// sentence after it and a closing bracket it never opened.
public enum TerminalURL {
    private static let pattern = try? NSRegularExpression(pattern: #"https?://[^\s<>"'`]+"#)

    /// The address under character `column` (1-based) of `line`, and where it sits in the line, in
    /// characters; nil where none is.
    public static func find(in line: String, at column: Int) -> (url: URL, range: Range<Int>)? {
        guard let pattern, column >= 1 else { return nil }
        let text = line as NSString
        for match in pattern.matches(in: line, range: NSRange(location: 0, length: text.length)) {
            let found = trimmed(text.substring(with: match.range))
            let start = text.substring(to: match.range.location).count, range = start ..< start + found.count
            guard range.contains(column - 1) else { continue }
            return URL(string: found).map { ($0, range) }
        }
        return nil
    }

    /// `address` less what ends a sentence after it, and closing brackets it never opened.
    private static func trimmed(_ address: String) -> String {
        var kept = Substring(address)
        while let last = kept.last {
            let pairs: [Character: Character] = [")": "(", "]": "[", "}": "{"]
            if ".,;:!?".contains(last) {
                kept.removeLast()
            } else if let opening = pairs[last], kept.filter({ $0 == opening }).count < kept.filter({ $0 == last }).count {
                kept.removeLast()
            } else {
                break
            }
        }
        return String(kept)
    }
}
