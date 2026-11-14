import CoreGraphics
import CoreText
import Foundation
import SailSurface

/// JetBrains Mono's four faces, read from the files the app ships (`Fonts/` in the package,
/// `Contents/Resources/Fonts` in the bundle), each checked to be the face it is named for. Fonts are
/// made from the files' own descriptors, so nothing is registered system-wide.
public struct TerminalFonts {
    public static let files: [FontStyle: String] = [
        .regular: "JetBrainsMono-Regular.ttf", .bold: "JetBrainsMono-Bold.ttf",
        .italic: "JetBrainsMono-Italic.ttf", .boldItalic: "JetBrainsMono-BoldItalic.ttf",
    ]
    public static let family = "JetBrains Mono"

    private let descriptors: [FontStyle: CTFontDescriptor]

    public init(directory: URL) throws(LookError) {
        var descriptors: [FontStyle: CTFontDescriptor] = [:]
        for (style, file) in Self.files {
            let url = directory.appending(path: file)
            guard let found = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let descriptor = found.first
            else { throw .missing("no font in \(url.path)") }
            let font = CTFontCreateWithFontDescriptor(descriptor, 12, nil)
            let family = CTFontCopyFamilyName(font) as String
            guard family == Self.family, Self.style(of: font) == style else {
                throw .missing("\(url.path) is \(family), not \(Self.family) \(style)")
            }
            descriptors[style] = descriptor
        }
        self.descriptors = descriptors
    }

    /// The faces at `points`, drawn at `scale` pixels a point: Core Text works in pixels.
    public func faces(points: Double, scale: Double) throws(LookError) -> TerminalFaces {
        guard points.isFinite, points > 0, scale.isFinite, scale > 0 else { throw .missing("no font at \(points) pt and scale \(scale)") }
        var fonts: [FontStyle: CTFont] = [:]
        for (style, descriptor) in descriptors {
            fonts[style] = CTFontCreateWithFontDescriptor(descriptor, points * scale, nil)
        }
        return try TerminalFaces(points: points, scale: scale, fonts: fonts)
    }

    private static func style(of font: CTFont) -> FontStyle {
        let traits = CTFontGetSymbolicTraits(font)
        switch (traits.contains(.traitBold), traits.contains(.traitItalic)) {
        case (false, false): return .regular
        case (true, false): return .bold
        case (false, true): return .italic
        case (true, true): return .boldItalic
        }
    }
}

/// The four faces at one size and scale, and the cell they make.
public struct TerminalFaces {
    public let points: Double
    public let scale: Double
    public let metrics: CellMetrics
    private let fonts: [FontStyle: CTFont]
    private let regular: CTFont

    init(points: Double, scale: Double, fonts: [FontStyle: CTFont]) throws(LookError) {
        guard let regular = fonts[.regular], fonts.count == FontStyle.allCases.count else { throw .missing("a face is missing") }
        var glyph = CGGlyph()
        var character: UniChar = 0x4D
        var advance = CGSize.zero
        guard CTFontGetGlyphsForCharacters(regular, &character, &glyph, 1) else { throw .missing("JetBrains Mono has no M") }
        CTFontGetAdvancesForGlyphs(regular, .horizontal, &glyph, &advance, 1)
        let measures = FontMetrics(
            advance: advance.width, ascent: CTFontGetAscent(regular), descent: CTFontGetDescent(regular),
            leading: CTFontGetLeading(regular), underlineOffset: -CTFontGetUnderlinePosition(regular),
            underlineThickness: CTFontGetUnderlineThickness(regular), xHeight: CTFontGetXHeight(regular)
        )
        do {
            metrics = try CellMetrics(font: measures, scale: scale)
        } catch {
            throw .missing("JetBrains Mono at \(points) pt makes no cell: \(error)")
        }
        self.points = points
        self.scale = scale
        self.fonts = fonts
        self.regular = regular
    }

    public func font(_ style: FontStyle) -> CTFont {
        fonts[style] ?? regular
    }
}

public enum LookError: Error, Equatable, CustomStringConvertible {
    case missing(String)

    public var description: String {
        switch self {
        case let .missing(what): what
        }
    }
}
