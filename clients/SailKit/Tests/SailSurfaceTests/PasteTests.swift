import SailSurface
import Testing

@Suite struct PasteTests {
    private let shot = PastedFile(path: "/Users/ada/Desktop/shot one.png", size: 412_000, folder: false)

    /// Scenario 14: the first flavour the writer listed decides, here and on a runner.
    @Test func theFirstFlavourTheWriterListedDecides() {
        for place in [PastePlace.thisMac, .remote] {
            #expect(PasteContent.classify(types: [.image], place: place) == .image)
            #expect(PasteContent.classify(types: [.text, .image], place: place) == .text)
            #expect(PasteContent.classify(types: [.other, .image, .text], place: place) == .image)
            #expect(PasteContent.classify(types: [.richText], place: place) == .plainText)
            #expect(PasteContent.classify(types: [.other], place: place) == .nothing)
            #expect(PasteContent.classify(types: [], place: place) == .nothing)
        }
        #expect(PasteContent.classify(types: [.files([shot]), .text], place: .thisMac) == .paths("/Users/ada/Desktop/shot\\ one.png "))
        #expect(PasteContent.classify(types: [.files([shot]), .text], place: .remote) == .upload([shot]))
    }

    @Test func aPasteHoldsAtMost32FilesAnd256MBAndNoFolderGoesToARunner() {
        let many = [PastedFile](repeating: shot, count: 33)
        for place in [PastePlace.thisMac, .remote] {
            #expect(PasteContent.classify(types: [.files(many)], place: place) == .refused(.tooMany(count: 33)))
            #expect(PasteContent.classify(types: [.files(Array(many.prefix(32)))], place: place) != .refused(.tooMany(count: 32)))
        }
        let heavy = [PastedFile](repeating: PastedFile(path: "/a", size: 64 << 20, folder: false), count: 5)
        #expect(PasteContent.classify(types: [.files(heavy)], place: .remote) == .refused(.tooLarge(bytes: 320 << 20)))
        let folder = PastedFile(path: "/Users/ada/src", size: 0, folder: true)
        #expect(PasteContent.classify(types: [.files([shot, folder])], place: .remote) == .refused(.folders))
        #expect(PasteContent.classify(types: [.files([folder])], place: .thisMac) == .paths("/Users/ada/src "))
        #expect(PasteRefusal.tooMany(count: 33).sentence == "This paste holds 33 files; at most 32 are uploaded at once.")
        #expect(PasteRefusal.tooLarge(bytes: 320 << 20).sentence == "This paste holds 320 MB; at most 256 MB are uploaded at once.")
        #expect(PasteRefusal.folders.sentence == "Folders are not uploaded; drop the files.")
    }

    @Test func aPasteboardTypeNamesItsFlavour() {
        #expect(PasteFlavor(type: "public.utf8-plain-text") == .text)
        #expect(PasteFlavor(type: "public.file-url") == .files([]))
        #expect(["public.png", "public.tiff", "public.jpeg", "public.heic"].map(PasteFlavor.init(type:)) == [.image, .image, .image, .image])
        #expect(PasteFlavor(type: "public.rtf") == .richText)
        #expect(PasteFlavor(type: "com.apple.flat-rtfd") == .other)
    }

    /// Scenario 15: a path escaped as Ghostty escapes a dropped file.
    @Test func aPathIsEscapedAsGhosttyEscapesIt() {
        #expect(ShellEscape.escape("/a b/c'd") == "/a\\ b/c\\'d")
        #expect(ShellEscape.escape("/x(1)/y!") == "/x\\(1\\)/y\\!")
        for special in #"\ ()[]{}<>"'`!#$&;|*?"# + "\t" {
            #expect(ShellEscape.escape("a\(special)b") == "a\\\(special)b", "\(special.debugDescription)")
        }
        #expect(ShellEscape.escape("/plain/file-1.png") == "/plain/file-1.png")
    }

    /// Scenario 5's mirror: the name the host will store a file under, row for row.
    @Test func aNameIsReducedAsTheHostReducesIt() {
        #expect(UploadName.reduce("../../etc/passwd") == "..-..-etc-passwd")
        #expect(UploadName.reduce("a b(1).png") == "a-b-1-.png")
        #expect(UploadName.reduce(String(repeating: "x", count: 300)).utf8.count == 96)
        #expect(UploadName.reduce(".") == "file")
        #expect(UploadName.reduce("..") == "file")
        #expect(UploadName.reduce("\0") == "-")
        #expect(UploadName.reduce("") == "file")
        #expect(UploadName.reduce("café.png") == "caf--.png")
        #expect(UploadName.reduce("a/b") == "a-b")
    }
}
