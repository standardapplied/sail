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

    /// A ⌘ chord no menu took reaches the view and sends nothing: ⌘K is not "k".
    @Test func aCommandChordSendsNothing() throws {
        let screen = try screen(20, 2)
        for (code, letter) in [(UInt16(0x28), "k"), (0x0D, "w"), (0x00, "a")] {
            let press = MacKeyEvent(keyCode: code, modifierFlags: 1 << 20, characters: letter, charactersWithoutControl: letter, unmodified: letter)
            #expect(Typing.inputs(for: press, committed: [], composedBefore: false, composingNow: false).isEmpty)
            #expect(Typing.inputs(for: press, committed: [letter], composedBefore: false, composingNow: false).flatMap { screen.encode($0) }.isEmpty)
            var release = press
            release.action = .release
            #expect(Typing.inputs(for: release, committed: [], composedBefore: false, composingNow: false).isEmpty)
        }
        let shiftCommand = MacKeyEvent(keyCode: 0x28, modifierFlags: 1 << 20 | 1 << 17, characters: "K", charactersWithoutControl: "K", unmodified: "k")
        #expect(Typing.inputs(for: shiftCommand, committed: [], composedBefore: false, composingNow: false).isEmpty)
    }

    /// A modifier alone reaches only a program that asked for every key (Kitty flag 8).
    @Test func aModifierAloneIsReportedOnlyWhenAsked() throws {
        let screen = try screen(20, 2)
        let shiftDown = MacKeyEvent(keyCode: 0x38, modifierFlags: 1 << 17 | 0x02)
        let shiftUp = MacKeyEvent(keyCode: 0x38, modifierFlags: 0)
        let press = try #require(Typing.modifierKey(shiftDown))
        let release = try #require(Typing.modifierKey(shiftUp))
        #expect(press.key == .shiftLeft && press.action == .press)
        #expect(release.action == .release)
        #expect(Typing.modifierKey(MacKeyEvent(keyCode: 0x00)) == nil)
        #expect(screen.encode(press).isEmpty && screen.encode(release).isEmpty)
        _ = screen.fed("\u{1b}[>10u")
        #expect(!screen.encode(press).isEmpty)
        #expect(!screen.encode(release).isEmpty)
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

    /// Scenario 9's portable half: a drag across a wrapped line selects it, and the screen gives
    /// its text with the wrapped rows joined.
    @Test func aDragAcrossAWrappedLineSelectsItJoined() throws {
        let screen = try screen(10, 4).fed("abcdefghijklmno")
        var pointer = Pointer()
        _ = pointer.down(at: CellPoint(x: 0, y: 0), clicks: 1, shift: false, programTracksMouse: screen.tracksMouse)
        guard case let .select(request) = pointer.dragged(to: CellPoint(x: 4, y: 1)) else {
            Issue.record("a drag selected nothing")
            return
        }
        #expect(screen.select(request))
        #expect(screen.selectedText() == "abcdefghijklmno")
    }

    /// A double click selects a word and a triple click a line; a drag from either extends it by
    /// whole words or lines, and nothing is selected by moving with no button down.
    @Test func doubleAndTripleClicksSelectAWordAndALineAndADragExtendsByThem() {
        var pointer = Pointer()
        #expect(pointer.down(at: a, clicks: 2, shift: false, programTracksMouse: false) == .select(.word(at: a)))
        #expect(pointer.dragged(to: b) == .select(.words(from: a, to: b)))
        #expect(pointer.down(at: a, clicks: 3, shift: false, programTracksMouse: false) == .select(.line(at: a)))
        #expect(pointer.dragged(to: b) == .select(.lines(from: a, to: b)))
        #expect(pointer.up() == .nothing)
        #expect(pointer.dragged(to: a) == .nothing)
    }

    /// Shift-click extends the selection by the unit it was made in, and a drag goes on extending.
    @Test func shiftClickExtendsAfterADoubleOrTripleClick() {
        let c = CellPoint(x: 9, y: 4)
        for (clicks, by, dragged) in [
            (2, SelectionRequest.words(from: a, to: b), SelectionRequest.words(from: a, to: c)),
            (3, .lines(from: a, to: b), .lines(from: a, to: c)),
        ] {
            var pointer = Pointer()
            _ = pointer.down(at: a, clicks: clicks, shift: false, programTracksMouse: false)
            _ = pointer.up()
            #expect(pointer.down(at: b, clicks: 1, shift: true, programTracksMouse: false) == .select(by), "after \(clicks) clicks")
            #expect(pointer.dragged(to: c) == .select(dragged), "a drag after the shift-click, after \(clicks) clicks")
        }
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
    /// The hop from the session's thread to the main one is kept: the wake the main thread took
    /// latest after the oldest change it carried, the two stamps of that wake's hand-off (so a late
    /// hand-off and a late take are told apart, and a stamp the take overtook stays unknown), how
    /// many wakes it took and how many changes.
    @Test func theLongestHopFromTheScreenToTheMainThreadIsKept() {
        var marks = LatencyMarks()
        marks.told(at: 1.002, since: 1.000, handing: 1.0005, handed: 1.001, changes: 1)
        marks.told(at: 2.180, since: 2.000, handing: 2.165, handed: 2.170, changes: 3)
        marks.told(at: 3.000, since: 3.001, handing: 3.001, handed: 3.001, changes: 1)
        #expect(marks.figures.longestToTold == Span(2.000, 2.180))
        #expect(marks.figures.longestToToldHanding == 2.165 && marks.figures.longestToToldHanded == 2.170, "the longest wake's hand-off, not the last one's")
        #expect(LatencyMarks().figures.longestToToldHanding == nil && LatencyMarks().figures.longestToToldHanded == nil)
        #expect(marks.figures.wakes == 3)
        #expect(marks.figures.changes == 5)
        marks.told(at: 4.400, since: 4.000, handing: 4.001, handed: nil, changes: 1)
        #expect(marks.figures.longestToToldHanding == 4.001 && marks.figures.longestToToldHanded == nil,
                "a wake the main thread took before its hand-off was stamped keeps that stamp unknown, never the take's time")
        #expect(LatencyMarks().figures.longestToTold == nil)
    }

    /// The drawable asks a span holds are those overlapping it; an ask still out counts up to now.
    @Test func theDrawableAsksInASpanAreThoseOverlappingItAnOpenOneUpToNow() {
        var marks = LatencyMarks()
        marks.fetchStarted(at: 1.000)
        marks.fetchEnded(at: 1.010)
        marks.fetchStarted(at: 2.000)
        marks.fetchEnded(at: 2.500)
        marks.fetchStarted(at: 3.000)
        #expect(marks.asks(in: Span(1.900, 2.100), now: 3.200) == [Span(2.000, 2.500)], "asks outside the span are left out")
        #expect(marks.asks(in: Span(2.900, 3.100), now: 3.200) == [Span(3.000, 3.200)], "an ask still out counts up to now")
        #expect(marks.asks(in: Span(0.000, 0.500), now: 3.200).isEmpty)
    }

    @Test func aKeysEchoIsTheFirstOutputAfterItsBytesAndShowsWhenItsFrameIsPresented() {
        var marks = LatencyMarks()
        marks.keyWritten(event: 1.000, sent: 1.0005, written: 1.001)
        marks.output(at: 1.004)
        marks.committed(frame: 1, at: 1.010, read: 1.008, target: 1.0267)
        marks.presented(frame: 1, at: 1.025, told: 1.025)
        let figures = marks.figures
        #expect(abs(figures.keyToWritten.p95 - 1) < 1e-6)
        #expect(figures.keyWrites == [Span(1.000, 1.001)])
        #expect(abs(figures.outputToCommitted.p50 - 6) < 1e-6)
        #expect(abs(figures.outputToPresented.p50 - 21) < 1e-6)
        #expect(abs(figures.keyToEcho.p50 - 25) < 1e-6)
    }

    /// A frame's completion can be told before its commit is marked: where the display tells no
    /// time, the key's echo counts at that completion once the commit is.
    @Test func aFrameCompletedBeforeItsCommitStillCountsItsEcho() {
        var marks = LatencyMarks()
        marks.keyWritten(event: 1.000, sent: 1.0005, written: 1.001)
        marks.output(at: 1.004)
        marks.completed(frame: 1, at: 1.012, ok: true)
        marks.committed(frame: 1, at: 1.010, read: 1.008, target: 1.0267)
        let figures = marks.figures
        #expect(figures.keyToEchoEstimated.count == 1)
        #expect(abs(figures.keyToEchoEstimated.p50 - 12) < 1e-6)
        #expect(abs(figures.outputToPresentedEstimated.p50 - 8) < 1e-6)
        #expect(figures.keyToEcho.count == 0, "nothing was measured on the display")
        #expect(figures.presentedBeforeCommit == 1)
    }

    /// An echo can reach the screen after the key's bytes began to go but before the write call
    /// returned: it is that key's echo, not the next output's.
    @Test func anOutputBetweenTheSendAndTheWrittenStampIsTheKeysEcho() {
        var marks = LatencyMarks()
        marks.keyWritten(event: 1.000, sent: 1.001, written: 1.003)
        marks.output(at: 1.002)
        marks.committed(frame: 1, at: 1.010, read: 1.008, target: 1.0267)
        marks.presented(frame: 1, at: 1.025, told: 1.025)
        #expect(marks.figures.keyToEcho.count == 1)
        #expect(abs(marks.figures.keyToEcho.p50 - 25) < 1e-6)
    }

    /// The screen-change consumer can tell the echo, and a frame draw it, before the input queue
    /// tells the key written: the key is put to that output.
    @Test func anOutputToldAndDrawnBeforeTheKeyIsToldWrittenIsTheKeysEcho() {
        var marks = LatencyMarks()
        marks.output(at: 1.002)
        marks.committed(frame: 1, at: 1.010, read: 1.008, target: 1.0267)
        marks.keyWritten(event: 1.000, sent: 1.001, written: 1.003)
        marks.presented(frame: 1, at: 1.025, told: 1.025)
        #expect(marks.figures.keyToEcho.count == 1)
        var undrawn = LatencyMarks()
        undrawn.output(at: 1.002)
        undrawn.keyWritten(event: 1.000, sent: 1.001, written: 1.003)
        undrawn.committed(frame: 1, at: 1.010, read: 1.008, target: 1.0267)
        undrawn.presented(frame: 1, at: 1.025, told: 1.025)
        #expect(undrawn.figures.keyToEcho.count == 1)
    }

    /// A key's echo is counted once: a key told after its echo is not put to a later output as
    /// well, and an output before the key's send is not its echo.
    @Test func eachKeysEchoIsCountedOnce() {
        var marks = LatencyMarks()
        marks.output(at: 0.999)
        marks.output(at: 1.002)
        marks.keyWritten(event: 1.000, sent: 1.001, written: 1.003)
        marks.output(at: 1.005)
        marks.committed(frame: 1, at: 1.010, read: 1.008, target: 1.0267)
        marks.presented(frame: 1, at: 1.025, told: 1.025)
        #expect(marks.figures.keyToEcho.count == 1)
        #expect(abs(marks.figures.keyToEcho.p50 - 25) < 1e-6)
    }

    @Test func outputSeenAfterTheReadWaitsForTheNextFrameOrIsPutToTheOneThatShowedIt() {
        var marks = LatencyMarks()
        marks.committed(frame: 1, at: 1.002, read: 1.000, target: 1.0187)
        marks.output(at: 1.001)
        marks.presented(frame: 1, at: 1.016, told: 1.016)
        marks.unchanged(read: 1.017)
        #expect(marks.figures.outputToPresented.count == 1)
        #expect(marks.figures.outputToCommitted.count == 0)
        marks.output(at: 1.020)
        marks.committed(frame: 2, at: 1.035, read: 1.033, target: 1.0517)
        marks.presented(frame: 3, at: 0, told: 1.04)
        #expect(marks.figures.unreportedPresents == 1)
        #expect(marks.figures.outputToCommitted.count == 1)
        marks.completed(frame: 2, at: 1.037, ok: true)
        #expect(marks.figures.outputToPresentedEstimated.count == 0, "a display that reports times waits for the frame's own")
        marks.presented(frame: 2, at: 0, told: 1.06)
        #expect(marks.figures.unreportedPresents == 2)
        #expect(marks.figures.reportedPresents == 1)
        #expect(marks.figures.outputToPresented.count == 1, "a frame with no reported time is not measured on the display")
        #expect(abs(marks.figures.outputToPresentedEstimated.max - (1.037 - 1.020) * 1000) < 1e-6, "a dropped frame counts at its completion")
        #expect(abs(marks.figures.outputToShown.max - (1.037 - 1.020) * 1000) < 1e-6)
    }

    /// Where the display has told no presentation time, a frame counts as shown when its command
    /// buffer completed (the `latency-overlay` red: a present callback late or never, the window
    /// right); once and only once, never for a failed buffer, and not once the display tells times.
    @Test func aCompletedFrameIsShownWhereTheDisplayTellsNoTime() {
        var marks = LatencyMarks()
        marks.keyWritten(event: 1.000, sent: 1.0005, written: 1.001)
        marks.output(at: 1.004)
        marks.committed(frame: 1, at: 1.010, read: 1.008, target: 1.0267)
        marks.completed(frame: 1, at: 1.0113, ok: true)
        #expect(marks.figures.keyToEchoEstimated.count == 1, "counted without waiting for the present callback")
        #expect(abs(marks.figures.keyToEchoEstimated.p50 - 11.3) < 1e-6)
        marks.presented(frame: 1, at: 0, told: 2.9)
        #expect(marks.figures.keyToEchoEstimated.count == 1, "the late callback counts it no second time")
        marks.output(at: 3.0)
        marks.committed(frame: 2, at: 3.010, read: 3.008, target: 3.0267)
        marks.completed(frame: 2, at: 3.012, ok: false)
        marks.presented(frame: 2, at: 0, told: 3.5)
        #expect(marks.figures.outputToPresentedEstimated.count == 1, "a failed buffer shows nothing")
        marks.output(at: 4.0)
        marks.committed(frame: 3, at: 4.010, read: 4.008, target: 4.0267)
        marks.presented(frame: 3, at: 4.025, told: 4.03)
        marks.output(at: 5.0)
        marks.committed(frame: 4, at: 5.010, read: 5.008, target: 5.0267)
        marks.completed(frame: 4, at: 5.012, ok: true)
        #expect(marks.figures.outputToPresented.count == 1 && marks.figures.outputToPresentedEstimated.count == 1, "a display that tells times waits for them")
        marks.presented(frame: 4, at: 5.026, told: 5.03)
        #expect(marks.figures.outputToPresented.count == 2)
    }

    /// The longest gap between commits says whether any output reached the screen inside it, and
    /// for how long a drawable was being asked for in it.
    @Test func theLongestCommitGapSaysWhatReachedTheScreenAndWhatWasAskedFor() {
        var marks = LatencyMarks()
        marks.committed(frame: 1, at: 10.0, read: 9.999, target: 10.016)
        marks.output(at: 10.05)
        marks.fetchStarted(at: 10.1)
        marks.fetchEnded(at: 10.15)
        marks.committed(frame: 2, at: 10.2, read: 10.199, target: 10.216)
        let figures = marks.figures
        #expect(figures.longestBetweenCommits == Span(10.0, 10.2))
        #expect(figures.longestGapOutputs == 1)
        #expect(abs(figures.longestGapFetching - 50) < 1e-6)
    }

    @Test func outputACallbackCouldNotDrawIsLate() {
        var marks = LatencyMarks()
        marks.output(at: 1.000)
        marks.skipped(read: 1.005)
        marks.committed(frame: 1, at: 1.022, read: 1.021, target: 1.0387)
        #expect(marks.figures.lateOutputs == 1)
        marks.output(at: 1.030)
        marks.committed(frame: 2, at: 1.040, read: 1.038, target: 1.0567)
        #expect(marks.figures.lateOutputs == 1)
    }

    @Test func refreshTimesAreKept() {
        var marks = LatencyMarks()
        marks.refreshed(taking: 0.002, working: 0.001)
        marks.refreshed(taking: 0.004, working: 0.0015)
        #expect(abs(marks.figures.refresh.max - 4) < 1e-9)
        #expect(abs(marks.figures.refreshWork.max - 1.5) < 1e-9)
    }

    /// Scenario 19's metric: output that waited through a stall of the main thread is seen however
    /// the frames before it went. A gap counted only between frames that each drew output missed
    /// this 480 ms wait (it reported 0); each output's time to the frame that showed it does not.
    @Test func outputWaitingThroughAStallIsSeen() {
        var marks = LatencyMarks()
        marks.output(at: 1.000)
        marks.committed(frame: 1, at: 1.001, read: 1.0005, target: 1.016)
        marks.presented(frame: 1, at: 1.016, told: 1.016)
        marks.output(at: 1.020)
        marks.committed(frame: 2, at: 1.5, read: 1.49, target: 1.5)
        marks.presented(frame: 2, at: 1.5, told: 1.5)
        #expect(abs(marks.figures.outputToShown.max - 480) < 1e-6)
        var unreported = LatencyMarks()
        unreported.output(at: 1.020)
        unreported.committed(frame: 1, at: 1.5, read: 1.49, target: 1.516)
        unreported.completed(frame: 1, at: 1.5, ok: true)
        #expect(abs(unreported.figures.outputToShown.max - 480) < 1e-6, "at its completion, where the display tells no time")
        #expect(marks.figures.longestToShown == Span(1.020, 1.5), "the longest wait keeps when it was")
        #expect(marks.figures.longestBetweenCommits == Span(1.001, 1.5), "and so does the longest gap between commits")
    }
}

/// A frame Metal never tells presented says whether its commands completed: the window server
/// had it, or the GPU work stalled.
@Suite struct UnpresentedFrameTests {
    @Test func eachCommittedFrameNotToldPresentedSaysItsAgeAndItsCommands() {
        var marks = LatencyMarks()
        marks.completed(frame: 1, at: 1.004, ok: true)
        marks.committed(frame: 1, at: 1.001, read: 1.0, target: 1.016)
        marks.committed(frame: 2, at: 1.020, read: 1.019, target: 1.033)
        marks.completed(frame: 2, at: 1.026, ok: true)
        marks.committed(frame: 3, at: 1.040, read: 1.039, target: 1.050)
        marks.presented(frame: 1, at: 0, told: 2.0)
        let left = marks.unpresented(at: 6.040)
        #expect(left.map(\.frame) == [2, 3])
        #expect(abs(left[0].ageMs - 5020) < 1e-6 && abs((left[0].completedAfterMs ?? 0) - 6) < 1e-6 && left[0].completedOK == true)
        #expect(left[1].completedAfterMs == nil && left[1].completedOK == nil)
        #expect(left[1].description == "frame 3 committed 5000 ms ago, its commands not completed")
        let figures = marks.figures
        #expect(figures.commitToCompleted.count == 2 && abs(figures.commitToCompleted.max - 6) < 1e-6, "a completion told before its commit mark counts too")
        #expect(figures.commitToPresentTold.count == 1 && abs(figures.commitToPresentTold.max - 999) < 1e-6)
    }
    /// On a display that tells no presentation time (a CI runner's), a frame not told presented
    /// says nothing of the display: they are counted and that is said, never listed as if late.
    @Test func framesNotToldPresentedAreListedOnlyWhereTheDisplayTellsPresentationTimes() {
        var marks = LatencyMarks()
        marks.committed(frame: 1, at: 1.001, read: 1.0, target: 1.016)
        marks.committed(frame: 2, at: 1.020, read: 1.019, target: 1.033)
        marks.completed(frame: 2, at: 1.026, ok: true)
        marks.presented(frame: 1, at: 0, told: 1.030)
        var figures = marks.figures
        #expect(UnpresentedFrame.said(marks.unpresented(at: 4.020), reported: figures.reportedPresents, unreported: figures.unreportedPresents)
            == "the display tells no presentation time (1 presents told without one), so the 1 frames not told presented say nothing of it")
        marks.committed(frame: 3, at: 1.040, read: 1.039, target: 1.050)
        marks.presented(frame: 3, at: 1.050, told: 1.052)
        figures = marks.figures
        #expect(UnpresentedFrame.said(marks.unpresented(at: 4.020), reported: figures.reportedPresents, unreported: figures.unreportedPresents)
            == "not yet told presented: frame 2 committed 3000 ms ago, its commands completed 6.0 ms after")
        #expect(UnpresentedFrame.said([], reported: 1, unreported: 0) == "not yet told presented: none")
    }
}
