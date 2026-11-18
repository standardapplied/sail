import CoreText
import Foundation

/// JetBrains Mono as the controls set it: Medium for their labels (eyebrows, pills, keycaps,
/// shortcut hints), Regular for a mono line of body text, SemiBold for a project tile's initial; read
/// from the files the app ships and checked to be those faces. Apart from `TerminalFonts`, so a
/// terminal never needs the controls' faces to open.
public struct LabelFont {
    public enum Weight: String, CaseIterable, Sendable {
        case regular = "JetBrainsMono-Regular", medium = "JetBrainsMono-Medium", semibold = "JetBrainsMono-SemiBold"

        public var file: String {
            rawValue + ".ttf"
        }
    }

    private let descriptors: [Weight: CTFontDescriptor]

    public init(directory: URL) throws(LookError) {
        var descriptors: [Weight: CTFontDescriptor] = [:]
        for weight in Weight.allCases {
            let url = directory.appending(path: weight.file)
            guard let found = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let descriptor = found.first
            else { throw .missing("no font in \(url.path)") }
            let name = CTFontCopyPostScriptName(CTFontCreateWithFontDescriptor(descriptor, 12, nil)) as String
            guard name == weight.rawValue else { throw .missing("\(url.path) is \(name), not \(weight.rawValue)") }
            descriptors[weight] = descriptor
        }
        self.descriptors = descriptors
    }

    public func font(_ points: Double, _ weight: Weight = .medium) -> CTFont {
        descriptors[weight].map { CTFontCreateWithFontDescriptor($0, points, nil) } ?? CTFontCreateWithName("Menlo" as CFString, points, nil)
    }
}
