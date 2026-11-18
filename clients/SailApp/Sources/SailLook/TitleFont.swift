import CoreText
import Foundation
import SailDesign

/// Newsreader 500 for titles, read from the file the app ships beside JetBrains Mono and checked to
/// be that face; made from the file's own descriptor, so nothing is registered system-wide.
public struct TitleFont {
    public static let file = "Newsreader16pt-Medium.ttf"
    public static let family = "Newsreader 16pt"

    private let descriptor: CTFontDescriptor

    public init(directory: URL) throws(LookError) {
        let url = directory.appending(path: Self.file)
        guard let found = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let descriptor = found.first
        else { throw .missing("no font in \(url.path)") }
        let font = CTFontCreateWithFontDescriptor(descriptor, 12, nil)
        let family = CTFontCopyFamilyName(font) as String
        let style = CTFontCopyName(font, kCTFontStyleNameKey).map { $0 as String } ?? ""
        guard family == Self.family, style == "Medium" else { throw .missing("\(url.path) is \(family) \(style), not \(Self.family) Medium") }
        self.descriptor = descriptor
    }

    public func font(_ step: Look.Step) -> CTFont {
        CTFontCreateWithFontDescriptor(descriptor, step.points, nil)
    }
}
