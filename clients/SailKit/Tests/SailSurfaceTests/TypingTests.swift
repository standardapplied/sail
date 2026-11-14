import SailSurface
import SailVT
import Testing

@Suite struct TypingTests {
    private let control: UInt = 1 << 18
    private let option: UInt = 1 << 19
    private let shift: UInt = 1 << 17

    private func bytes(_ inputs: [KeyInput], on screen: Screen) -> [UInt8] {
        inputs.flatMap { screen.encode($0) }
    }

    /// Scenario 7: Ctrl-C sends 0x03; Option-e then e sends é once (the dead key is composed, so
    /// its first press sends nothing); an arrow in a program's cursor mode sends the application
    /// arrow.
    @Test func controlADeadKeyAndAnApplicationArrowEncodeAsATerminalDoes() throws {
        let screen = try screen(20, 2)
        let controlC = MacKeyEvent(keyCode: 0x08, modifierFlags: control, characters: "\u{3}", charactersWithoutControl: "c", unmodified: "c")
        #expect(bytes(Typing.inputs(for: controlC, committed: [], composedBefore: false, composingNow: false), on: screen) == [0x03])

        let optionE = MacKeyEvent(keyCode: 0x0E, modifierFlags: option, characters: "", unmodified: "e")
        #expect(Typing.inputs(for: optionE, committed: [], composedBefore: false, composingNow: true).isEmpty)
        let e = MacKeyEvent(keyCode: 0x0E, characters: "e", charactersWithoutControl: "e", unmodified: "e")
        #expect(bytes(Typing.inputs(for: e, committed: ["é"], composedBefore: true, composingNow: false), on: screen) == Array("é".utf8))

        let up = MacKeyEvent(keyCode: 0x7E, characters: "\u{F700}", charactersWithoutControl: "\u{F700}", unmodified: "\u{F700}")
        #expect(bytes(Typing.inputs(for: up, committed: [], composedBefore: false, composingNow: false), on: screen) == Array("\u{1b}[A".utf8))
        _ = screen.fed("\u{1b}[?1h")
        #expect(bytes(Typing.inputs(for: up, committed: [], composedBefore: false, composingNow: false), on: screen) == Array("\u{1b}OA".utf8))
    }

    /// V4 and scenario 8: while text is marked, no key sends anything; the commit sends exactly the
    /// committed text, once, even when a key such as Return committed it.
    @Test func composingSendsNothingUntilTheCommitThenExactlyIt() throws {
        let screen = try screen(20, 2)
        for code: UInt16 in [0x0F, 0x22, 0x33, 0x31] {
            let typed = MacKeyEvent(keyCode: code, characters: "x", charactersWithoutControl: "x", unmodified: "x")
            #expect(Typing.inputs(for: typed, committed: [], composedBefore: true, composingNow: true).isEmpty)
        }
        let escape = MacKeyEvent(keyCode: 0x35, characters: "\u{1b}", charactersWithoutControl: "\u{1b}", unmodified: "\u{1b}")
        #expect(Typing.inputs(for: escape, committed: [], composedBefore: true, composingNow: false).isEmpty, "a cancel sends nothing")
        let enter = MacKeyEvent(keyCode: 0x24, characters: "\r", charactersWithoutControl: "\r", unmodified: "\r")
        let commit = Typing.inputs(for: enter, committed: ["にほん"], composedBefore: true, composingNow: false)
        #expect(bytes(commit, on: screen) == Array("にほん".utf8))
        #expect(bytes(Typing.inputs(for: enter, committed: [], composedBefore: false, composingNow: false), on: screen) == [0x0D])
        #expect(Typing.inserted("") == nil)
        #expect(Typing.inserted("ü").map { screen.encode($0) } == Array("ü".utf8))
    }

    @Test func typedTextKeepsItsKeyAndWhatTheLayoutUsedUp() throws {
        let shifted = MacKeyEvent(keyCode: 0x00, modifierFlags: shift | 0x02, characters: "A", charactersWithoutControl: "A", unmodified: "a")
        let input = try #require(Typing.inputs(for: shifted, committed: ["A"], composedBefore: false, composingNow: false).first)
        #expect(input.key == .a)
        #expect(input.text == "A")
        #expect(input.modifiers == .shift)
        #expect(input.consumedModifiers == .shift)
        #expect(input.unshifted == "a")
        let function = MacKeyEvent(keyCode: 0x7A, characters: "\u{F704}", charactersWithoutControl: "\u{F704}", unmodified: "\u{F704}")
        let f1 = try #require(Typing.inputs(for: function, committed: [], composedBefore: false, composingNow: false).first)
        #expect(f1.key == .f1 && f1.text.isEmpty && f1.unshifted == nil && f1.consumedModifiers.isEmpty)
        let release = MacKeyEvent(keyCode: 0x00, characters: "a", charactersWithoutControl: "a", unmodified: "a", action: .release)
        #expect(Typing.inputs(for: release, committed: [], composedBefore: false, composingNow: false).first?.text == "")
    }

    @Test func theRightHandModifiersAreTold() {
        #expect(Typing.modifiers(1 << 17 | 0x04) == [.shift, .rightShift])
        #expect(Typing.modifiers(1 << 18 | 0x2000) == [.control, .rightControl])
        #expect(Typing.modifiers(1 << 19 | 0x40) == [.alt, .rightAlt])
        #expect(Typing.modifiers(1 << 20 | 0x10) == [.super, .rightSuper])
        #expect(Typing.modifiers(1 << 16 | 1 << 21) == [.capsLock])
    }

    @Test func everyKeyCodeNamesItsOwnKey() {
        let named = (UInt16(0) ... 0x7F).map(Typing.key).filter { $0 != .unidentified }
        #expect(Set(named).count == named.count)
        #expect(named.count >= 110)
        for key in named {
            #expect(Typing.code(of: key).map(Typing.key) == key)
        }
        #expect(Typing.key(0xFFFF) == .unidentified)
    }

    @Test func compositionHoldsTheMarkedText() {
        var composition = Composition()
        #expect(!composition.isComposing)
        composition.mark("にほ")
        #expect(composition.isComposing && composition.marked == "にほ")
        composition.unmark()
        #expect(!composition.isComposing)
    }
}

@Suite struct PointerTests {
    private let a = CellPoint(x: 1, y: 1)
    private let b = CellPoint(x: 5, y: 2)

    @Test func aDragSelectsFromWhereItStarted() {
        var pointer = Pointer()
        #expect(pointer.down(at: a, clicks: 1, shift: false, programTracksMouse: false) == .clearSelection)
        #expect(pointer.dragged(to: b) == .select(.range(from: a, to: b)))
        #expect(pointer.up() == .nothing)
        #expect(pointer.down(at: CellPoint(x: 9, y: 3), clicks: 1, shift: true, programTracksMouse: false) == .select(.range(from: a, to: CellPoint(x: 9, y: 3))))
    }

    @Test func doubleAndTripleClicksSelectAWordAndALine() {
        var pointer = Pointer()
        #expect(pointer.down(at: a, clicks: 2, shift: false, programTracksMouse: false) == .select(.word(at: a)))
        #expect(pointer.down(at: a, clicks: 3, shift: false, programTracksMouse: false) == .select(.line(at: a)))
        #expect(pointer.dragged(to: b) == .nothing)
    }

    @Test func aProgramThatTracksTheMouseGetsItUnlessShiftIsHeld() {
        var pointer = Pointer()
        #expect(pointer.down(at: a, clicks: 1, shift: false, programTracksMouse: true) == .report)
        #expect(pointer.dragged(to: b) == .report)
        #expect(pointer.up() == .report)
        #expect(pointer.down(at: a, clicks: 1, shift: true, programTracksMouse: true) == .clearSelection)
        #expect(pointer.dragged(to: b) == .select(.range(from: a, to: b)))
    }

    @Test func scrollDeltasAreGatheredIntoWholeLines() {
        var gatherer = ScrollGatherer()
        #expect(gatherer.lines(deltaY: 15, precise: true, lineHeight: 20) == 0)
        #expect(gatherer.lines(deltaY: 15, precise: true, lineHeight: 20) == -1)
        #expect(gatherer.lines(deltaY: -50, precise: true, lineHeight: 20) == 2)
        gatherer.reset()
        #expect(gatherer.lines(deltaY: 3, precise: false, lineHeight: 20) == -3)
        #expect(gatherer.lines(deltaY: 0.4, precise: false, lineHeight: 20) == 0)
        #expect(gatherer.lines(deltaY: 0.7, precise: false, lineHeight: 20) == -1)
        #expect(gatherer.lines(deltaY: 1e300, precise: false, lineHeight: 20) == -10_000)
        #expect(gatherer.lines(deltaY: .nan, precise: true, lineHeight: 20) == 0)
    }
}

@Suite struct LatencyMarksTests {
    @Test func aKeysEchoIsTheFirstOutputAfterItsBytesAndShowsWhenItsFrameIsPresented() {
        var marks = LatencyMarks()
        marks.keyWritten(event: 1.000, written: 1.001)
        marks.output(at: 1.004)
        marks.committed(frame: 1, at: 1.010, read: 1.008)
        marks.presented(frame: 1, at: 1.025)
        let figures = marks.figures
        #expect(abs(figures.keyToWritten.p95 - 1) < 1e-6)
        #expect(abs(figures.outputToCommitted.p50 - 6) < 1e-6)
        #expect(abs(figures.outputToPresented.p50 - 21) < 1e-6)
        #expect(abs(figures.keyToEcho.p50 - 25) < 1e-6)
    }

    @Test func outputSeenAfterTheReadWaitsForTheNextFrameOrIsPutToTheOneThatShowedIt() {
        var marks = LatencyMarks()
        marks.committed(frame: 1, at: 1.002, read: 1.000)
        marks.output(at: 1.001)
        marks.presented(frame: 1, at: 1.016)
        marks.unchanged(read: 1.017)
        #expect(marks.figures.outputToPresented.count == 1)
        #expect(marks.figures.outputToCommitted.count == 0)
        marks.output(at: 1.020)
        marks.committed(frame: 2, at: 1.035, read: 1.033)
        marks.presented(frame: 3, at: 0)
        #expect(marks.figures.droppedFrames == 1)
        #expect(marks.figures.outputToCommitted.count == 1)
    }

    @Test func outputACallbackCouldNotDrawIsLate() {
        var marks = LatencyMarks()
        marks.output(at: 1.000)
        marks.skipped(read: 1.005)
        marks.committed(frame: 1, at: 1.022, read: 1.021)
        #expect(marks.figures.lateOutputs == 1)
        marks.output(at: 1.030)
        marks.committed(frame: 2, at: 1.040, read: 1.038)
        #expect(marks.figures.lateOutputs == 1)
    }

    @Test func theLongestGapBetweenFramesWhileOutputWaitedIsKept() {
        var marks = LatencyMarks()
        marks.output(at: 1.000)
        marks.committed(frame: 1, at: 1.001, read: 1.001)
        marks.output(at: 1.005)
        marks.presented(frame: 1, at: 1.016)
        marks.committed(frame: 2, at: 1.090, read: 1.089)
        marks.presented(frame: 2, at: 1.120)
        marks.output(at: 2.000)
        marks.committed(frame: 3, at: 2.001, read: 2.001)
        marks.presented(frame: 3, at: 2.016)
        #expect(abs(marks.figures.largestPresentGap - 104) < 1e-6)
    }
}
