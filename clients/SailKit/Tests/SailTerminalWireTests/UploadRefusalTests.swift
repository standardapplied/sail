import SailAPI
import SailKitTestSupport
import SailTerminalWire
import Testing

@Suite struct UploadRefusalTests {
    private func said(_ kind: Open<UploadRefusal.Kind>, limit: Int64 = 0, size: Int64 = 412_000) -> String {
        UploadRefusal(kind: kind, limit: limit, file: "screenshot.png", size: size).sentence(box: "box-3", project: "acme")
    }

    @Test func everyRefusalIsOneSentenceThatSaysWhatToDo() {
        #expect(said(.known(.tooLarge), limit: 64 << 20, size: 70 << 20) == "screenshot.png is 70 MB; files up to 64 MB are uploaded.")
        #expect(said(.known(.tooLarge), limit: 64 << 20, size: (64 << 20) + 1) == "screenshot.png is 65 MB; files up to 64 MB are uploaded.")
        #expect(said(.known(.tooMany), limit: 256)
            == "This session already holds 256 uploaded files, the most it keeps; open a new terminal to upload more.")
        #expect(said(.known(.sessionFull), limit: 1 << 30)
            == "screenshot.png would take this session past 1 GB of uploads, the most it keeps; open a new terminal to upload more.")
        #expect(said(.known(.notWriter)) == "You are watching this session; take the write token to paste files.")
        #expect(said(.known(.noContainer)) == "This terminal runs on box-3 itself, not in a workspace, so files are not uploaded to it.")
        #expect(said(.known(.sharedContainer))
            == "This terminal runs in acme's shared container, which every member's terminal reads, so files are not pasted here; paste them into a terminal in your own workspace of acme.")
        #expect(said(.known(.sessionEnded)) == "The session ended before screenshot.png was uploaded; nothing was saved.")
        #expect(said(.known(.noSpace), limit: 18 << 20, size: 50 << 20)
            == "The workspace on box-3 has 18 MB free, too little for screenshot.png (50 MB), so it was not saved; free some space in it, then paste the file again.")
        #expect(said(.known(.noSpace), limit: 0, size: 412_000)
            == "The workspace on box-3 has 0 MB free, too little for screenshot.png (1 MB), so it was not saved; free some space in it, then paste the file again.")
        #expect(said(.known(.stalled)) == "The workspace on box-3 stopped taking screenshot.png, so it was not saved; paste it again.")
        #expect(said(.known(.busy), limit: 16)
            == "16 files are already uploading to box-3, the most it takes at once; paste screenshot.png again when one has finished.")
        #expect(said(.known(.failed)) == "screenshot.png could not be saved on box-3; nothing was pasted. Paste it again.")
        #expect(said(.known(.notKnown))
            == "The connection to box-3 dropped after screenshot.png was sent, and whether it was saved is not known; paste it again if it is not in your workspace.")
        #expect(said(.known(.connectionLost))
            == "The connection to box-3 dropped while screenshot.png was uploading, before it was sent whole, so nothing was saved; paste it again.")
        #expect(said(.unknown("quota")) == said(.known(.failed)), "a kind from a newer host reads as a failure")
    }

    /// Every kind the host sends is one this client words, by the host's own list.
    @Test func theHostsKindsAreTheVocabularysAndTheRestAreTheClientsOwn() throws {
        #expect(UploadRefusal.Kind.allCases.filter { !UploadRefusal.Kind.clientsOwn.contains($0) }.map(\.rawValue)
            == (try Goldens.vocabulary("upload_refusals")))
    }

    @Test func theKindsAreTheHostsWords() {
        #expect(UploadRefusal.Kind.allCases.map(\.rawValue) == [
            "too_large", "too_many", "session_full", "not_writer", "no_container", "shared_container",
            "session_ended", "no_space", "failed", "stalled", "busy", "not_known", "connection_lost",
        ])
    }
}
