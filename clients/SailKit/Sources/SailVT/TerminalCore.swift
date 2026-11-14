import GhosttyVt

/// One terminal and the encoders synced from it. Every use is under the screen's terminal lock.
final class TerminalCore {
    private let terminal: Owned
    private let keyEncoder: Owned
    private let keyEvent: Owned
    private let mouseEncoder: Owned
    private let mouseEvent: Owned
    let sink: EffectSink
    var size: GridSize
    var cellPixels: (width: UInt32, height: UInt32) = (0, 0)
    var optionAsAlt = OptionAsAlt.none
    private var heldButtons = Set<MouseButton>()

    var handle: GhosttyTerminal {
        terminal.pointer
    }

    init(size: GridSize, scrollbackBytes: Int, palette: Palette, now: @escaping @Sendable () -> ContinuousClock.Instant) throws(ScreenError) {
        terminal = try Owned.make("ghostty_terminal_new", {
            ghostty_terminal_new(nil, $0, UInt16(size.columns), UInt16(size.rows))
        }) { ghostty_terminal_free($0) }
        keyEncoder = try Owned.make("ghostty_key_encoder_new", { ghostty_key_encoder_new(nil, $0) }) { ghostty_key_encoder_free($0) }
        keyEvent = try Owned.make("ghostty_key_event_new", { ghostty_key_event_new(nil, $0) }) { ghostty_key_event_free($0) }
        mouseEncoder = try Owned.make("ghostty_mouse_encoder_new", { ghostty_mouse_encoder_new(nil, $0) }) { ghostty_mouse_encoder_free($0) }
        mouseEvent = try Owned.make("ghostty_mouse_event_new", { ghostty_mouse_event_new(nil, $0) }) { ghostty_mouse_event_free($0) }
        sink = EffectSink(isDark: palette.isDark, now: now)
        self.size = size
        try sink.install(on: terminal.pointer)
        var scrollback = scrollbackBytes
        try set(terminal.pointer, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &scrollback)
        try paint(palette)
        var clustering = GhosttyTerminalModeConfig(mode: ghostty_mode_new(2027, false), value: true)
        try set(terminal.pointer, GHOSTTY_TERMINAL_OPT_MODE_DEFAULT, &clustering)
        try resize(size)
    }

    /// What the last call into the terminal produced, the title and directory read back if they
    /// changed.
    func drain() -> Effects {
        let taken = sink.take()
        var events = taken.events
        if taken.title {
            events.append(.title(read(GHOSTTY_TERMINAL_DATA_TITLE)))
        }
        if taken.workingDirectory {
            events.append(.workingDirectory(read(GHOSTTY_TERMINAL_DATA_PWD)))
        }
        return Effects(reply: taken.reply, events: events)
    }

    func resize(_ size: GridSize) throws(ScreenError) {
        try require("ghostty_terminal_resize", ghostty_terminal_resize(
            handle, UInt16(size.columns), UInt16(size.rows), cellPixels.width, cellPixels.height
        ))
        self.size = size
        sink.size = GhosttySizeReportSize(
            rows: UInt16(size.rows), columns: UInt16(size.columns), cell_width: cellPixels.width,
            cell_height: cellPixels.height
        )
    }

    /// Starts the terminal again from nothing: every mode and the screen (`ghostty_terminal_reset`),
    /// and the colours a program set with OSC 10, 11 and 12, which a reset keeps; their own resets
    /// (OSC 110, 111, 112) are fed for that, through libghostty-vt's parser.
    func restart() {
        ghostty_terminal_reset(handle)
        let resets = Array("\u{1b}]110\u{07}\u{1b}]111\u{07}\u{1b}]112\u{07}".utf8)
        resets.withUnsafeBufferPointer { ghostty_terminal_vt_write(handle, $0.baseAddress, $0.count) }
    }

    /// Whether the program's render hold has lasted `limit` or longer; such a hold is ended here,
    /// as the terminal has no clock to end it.
    func holdOutlasted(_ limit: Duration) -> Bool {
        guard let since = sink.heldSince, sink.now() - since >= limit else { return false }
        var mode = GhosttyTerminalModeConfig(mode: ghostty_mode_new(2026, false), value: false)
        _ = ghostty_terminal_set(handle, GHOSTTY_TERMINAL_OPT_MODE, &mode)
        sink.heldSince = nil
        return true
    }

    func scrollToBottom() {
        var bottom = GhosttyTerminalScrollViewport()
        bottom.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM
        _ = ghostty_terminal_scroll_viewport(handle, bottom)
    }

    func encode(_ key: KeyInput) -> [UInt8] {
        let encoder = keyEncoder.pointer
        let event = keyEvent.pointer
        guard ghostty_key_encoder_set_from_terminal(encoder, handle) == GHOSTTY_SUCCESS else { return [] }
        var alt = optionAsAlt.ghostty
        _ = ghostty_key_encoder_set(encoder, GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT, &alt)
        _ = ghostty_key_event_set_key(event, GhosttyKey(rawValue: .init(key.key.rawValue)))
        _ = ghostty_key_event_set_action(event, key.action.ghostty)
        _ = ghostty_key_event_set_mods(event, key.modifiers.rawValue)
        _ = ghostty_key_event_set_consumed_mods(event, key.consumedModifiers.rawValue)
        _ = ghostty_key_event_set_composing(event, key.composing)
        _ = ghostty_key_event_set_unshifted_codepoint(event, key.unshifted?.value ?? 0)
        var text = Array(key.text.utf8)
        return text.withUnsafeMutableBufferPointer { utf8 in
            _ = ghostty_key_event_set_utf8(event, GhosttyString(ptr: utf8.baseAddress, len: utf8.count))
            defer { _ = ghostty_key_event_set_utf8(event, GhosttyString(ptr: nil, len: 0)) }
            return encoded { ghostty_key_encoder_encode(encoder, event, $0, $1, $2) }
        }
    }

    /// Pastes as text (`GHOSTTY_PASTE_SOURCE_TEXT`), never as a Kitty paste event (OSC 5522):
    /// this screen answers every clipboard read denied, and installing that read callback is what
    /// turns paste events on, so an event's follow-up read would be denied and the paste lost. A
    /// text paste is still framed by mode 2004. Serving the follow-up read needs the app's clipboard
    /// here, which spec 2 or 3 brings.
    func paste(_ text: String, confirmed: Bool) -> PasteOutcome {
        var data = Array(text.utf8)
        var mime = Array("text/plain".utf8)
        let wasReplaying = sink.replaying
        let earlier = sink.reply
        sink.replaying = false
        sink.reply = []
        defer {
            sink.replaying = wasReplaying
            sink.reply = earlier
        }
        let result = data.withUnsafeMutableBufferPointer { data in
            mime.withUnsafeMutableBufferPointer { mime in
                var source = UnsafeBufferPointer(data)
                var mimes = [GhosttyString(ptr: mime.baseAddress, len: mime.count)]
                return withUnsafeMutablePointer(to: &source) { source in
                    mimes.withUnsafeMutableBufferPointer { mimes in
                        var request = GhosttyPaste()
                        request.size = MemoryLayout<GhosttyPaste>.size
                        request.location = GHOSTTY_CLIPBOARD_LOCATION_STANDARD
                        request.source = GHOSTTY_PASTE_SOURCE_TEXT
                        request.mimes = UnsafePointer(mimes.baseAddress)
                        request.mimes_len = mimes.count
                        request.reader = GhosttyMimeReader(read: { userdata, _, writer in
                            guard let bytes = userdata?.assumingMemoryBound(to: UnsafeBufferPointer<UInt8>.self).pointee,
                                  let base = bytes.baseAddress, !bytes.isEmpty
                            else { return true }
                            return writer.write?(writer.userdata, base, bytes.count) ?? false
                        }, userdata: UnsafeMutableRawPointer(source))
                        request.allow_unsafe = confirmed
                        var pasted = false
                        let result = ghostty_terminal_paste(handle, &request, &pasted)
                        return (result, pasted)
                    }
                }
            }
        }
        switch result {
        case (GHOSTTY_SUCCESS, true): return .send(sink.reply)
        case (GHOSTTY_REJECTED, _): return .needsConfirmation
        default: return .nothingToPaste
        }
    }

    func focus(_ focused: Bool) -> [UInt8] {
        guard mode(1004) else { return [] }
        return encoded(guess: 8) { ghostty_focus_encode(focused ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST, $0, $1, $2) }
    }

    func encode(_ mouse: MouseInput) -> [UInt8] {
        if let button = mouse.button, !button.isWheel, mouse.action == .press {
            heldButtons.insert(button)
        } else if let button = mouse.button, mouse.action == .release {
            heldButtons.remove(button)
        }
        let encoder = mouseEncoder.pointer
        let event = mouseEvent.pointer
        guard ghostty_mouse_encoder_set_from_terminal(encoder, handle) == GHOSTTY_SUCCESS else { return [] }
        var surface = GhosttyMouseEncoderSize()
        surface.size = MemoryLayout<GhosttyMouseEncoderSize>.size
        surface.cell_width = max(cellPixels.width, 1)
        surface.cell_height = max(cellPixels.height, 1)
        surface.screen_width = surface.cell_width.multipliedReportingOverflow(by: UInt32(size.columns)).partialValue
        surface.screen_height = surface.cell_height.multipliedReportingOverflow(by: UInt32(size.rows)).partialValue
        _ = ghostty_mouse_encoder_set(encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &surface)
        var held = !heldButtons.isEmpty
        _ = ghostty_mouse_encoder_set(encoder, GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED, &held)
        _ = ghostty_mouse_event_set_action(event, mouse.action.ghostty)
        if let button = mouse.button {
            _ = ghostty_mouse_event_set_button(event, button.ghostty)
        } else {
            _ = ghostty_mouse_event_clear_button(event)
        }
        _ = ghostty_mouse_event_set_mods(event, mouse.modifiers.rawValue)
        _ = ghostty_mouse_event_set_position(event, GhosttyMousePosition(x: Float(mouse.x), y: Float(mouse.y)))
        return encoded(guess: 32) { ghostty_mouse_encoder_encode(encoder, event, $0, $1, $2) }
    }

    func scroll(lines: Int, x: Double, y: Double) -> [UInt8] {
        guard lines != 0 else { return [] }
        if tracksMouse {
            let wheel = MouseInput(action: .press, button: lines < 0 ? .wheelUp : .wheelDown, x: x, y: y)
            return (0 ..< abs(lines)).flatMap { _ in encode(wheel) }
        }
        if onAlternateScreen, mode(1007) {
            let arrow = KeyInput(key: lines < 0 ? .arrowUp : .arrowDown)
            return (0 ..< abs(lines)).flatMap { _ in encode(arrow) }
        }
        var delta = GhosttyTerminalScrollViewport()
        delta.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA
        delta.value.delta = lines
        _ = ghostty_terminal_scroll_viewport(handle, delta)
        return []
    }

    func select(_ request: SelectionRequest) -> Bool {
        let made: GhosttySelection? = switch request {
        case let .word(point): extent(point, line: false)
        case let .line(point): extent(point, line: true)
        case let .range(from, to): gridRef(from).flatMap { start in gridRef(to).map { GhosttySelection(start: start, end: $0, rectangle: false) } }
        case let .words(from, to): spanning(from, to, line: false)
        case let .lines(from, to): spanning(from, to, line: true)
        }
        guard var selection = made else { return false }
        return ghostty_terminal_set(handle, GHOSTTY_TERMINAL_OPT_SELECTION, &selection) == GHOSTTY_SUCCESS
    }

    /// The word or line at `point`; nil when the cell is outside the viewport or holds none.
    private func extent(_ point: CellPoint, line: Bool) -> GhosttySelection? {
        guard let ref = gridRef(point) else { return nil }
        var selection = GhosttySelection()
        if line {
            var options = GhosttyTerminalSelectLineOptions()
            options.size = MemoryLayout<GhosttyTerminalSelectLineOptions>.size
            options.ref = ref
            guard ghostty_terminal_select_line(handle, &options, &selection) == GHOSTTY_SUCCESS else { return nil }
        } else {
            var options = GhosttyTerminalSelectWordOptions()
            options.size = MemoryLayout<GhosttyTerminalSelectWordOptions>.size
            options.ref = ref
            guard ghostty_terminal_select_word(handle, &options, &selection) == GHOSTTY_SUCCESS else { return nil }
        }
        return selection
    }

    /// From the start of the earlier cell's word (or line) to the end of the later one's, in either
    /// order; a cell holding no word stands for itself.
    private func spanning(_ from: CellPoint, _ to: CellPoint, line: Bool) -> GhosttySelection? {
        guard let fromRef = gridRef(from), let toRef = gridRef(to) else { return nil }
        let (first, firstRef, last, lastRef) = (from.y, from.x) <= (to.y, to.x) ? (from, fromRef, to, toRef) : (to, toRef, from, fromRef)
        let start = extent(first, line: line)?.start ?? firstRef
        let end = extent(last, line: line)?.end ?? lastRef
        return GhosttySelection(start: start, end: end, rectangle: false)
    }

    func selectedText() -> String? {
        var selection = GhosttySelection()
        guard ghostty_terminal_get(handle, GHOSTTY_TERMINAL_DATA_SELECTION, &selection) == GHOSTTY_SUCCESS,
              let formatter = try? Owned.make("ghostty_formatter_terminal_new", {
                  ghostty_formatter_terminal_new(nil, $0, handle)
              }, release: { ghostty_formatter_free($0) })
        else { return nil }
        var unwrap = true
        _ = ghostty_formatter_set(formatter.pointer, GHOSTTY_FORMATTER_OPT_UNWRAP, &unwrap)
        _ = ghostty_formatter_set(formatter.pointer, GHOSTTY_FORMATTER_OPT_SELECTION, &selection)
        var bytes: UnsafeMutablePointer<UInt8>?
        var length = 0
        guard ghostty_formatter_format_alloc(formatter.pointer, nil, &bytes, &length) == GHOSTTY_SUCCESS, let bytes else {
            return nil
        }
        defer { ghostty_free(nil, bytes, length) }
        return String(decoding: UnsafeBufferPointer(start: bytes, count: length), as: UTF8.self)
    }

    var tracksMouse: Bool {
        var tracking = false
        return ghostty_terminal_get(handle, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking) == GHOSTTY_SUCCESS && tracking
    }

    private var onAlternateScreen: Bool {
        var screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY
        return ghostty_terminal_get(handle, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen) == GHOSTTY_SUCCESS
            && screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE
    }

    /// Whether DEC private mode `number` is on.
    private func mode(_ number: UInt16) -> Bool {
        var query = GhosttyTerminalModeConfig(mode: ghostty_mode_new(number, false), value: false)
        return ghostty_terminal_get(handle, GHOSTTY_TERMINAL_DATA_MODE, &query) == GHOSTTY_SUCCESS && query.value
    }

    private func gridRef(_ point: CellPoint) -> GhosttyGridRef? {
        guard (0 ..< size.columns).contains(point.x), (0 ..< size.rows).contains(point.y) else { return nil }
        var place = GhosttyPoint()
        place.tag = GHOSTTY_POINT_TAG_VIEWPORT
        place.value.coordinate = GhosttyPointCoordinate(x: UInt16(point.x), y: UInt32(point.y))
        var ref = GhosttyGridRef()
        return ghostty_terminal_grid_ref(handle, place, &ref) == GHOSTTY_SUCCESS ? ref : nil
    }

    private func read(_ data: GhosttyTerminalData) -> String {
        var value = GhosttyString()
        guard ghostty_terminal_get(handle, data, &value) == GHOSTTY_SUCCESS else { return "" }
        return string(value)
    }

    private func paint(_ palette: Palette) throws(ScreenError) {
        var foreground = palette.foreground.ghostty
        var background = palette.background.ghostty
        var cursor = palette.cursor.ghostty
        try set(handle, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &foreground)
        try set(handle, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &background)
        try set(handle, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor)
        var colors = [GhosttyColorRgb](repeating: GhosttyColorRgb(), count: 256)
        colors.withUnsafeMutableBufferPointer { ghostty_color_palette_default($0.baseAddress) }
        for (slot, color) in palette.ansi.enumerated() {
            colors[slot] = color.ghostty
        }
        try colors.withUnsafeMutableBufferPointer { (colors: inout UnsafeMutableBufferPointer<GhosttyColorRgb>) throws(ScreenError) in
            try set(handle, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, colors.baseAddress)
        }
    }
}

private extension OptionAsAlt {
    var ghostty: GhosttyOptionAsAlt {
        switch self {
        case .none: GHOSTTY_OPTION_AS_ALT_FALSE
        case .both: GHOSTTY_OPTION_AS_ALT_TRUE
        case .left: GHOSTTY_OPTION_AS_ALT_LEFT
        case .right: GHOSTTY_OPTION_AS_ALT_RIGHT
        }
    }
}

private extension KeyAction {
    var ghostty: GhosttyKeyAction {
        switch self {
        case .press: GHOSTTY_KEY_ACTION_PRESS
        case .repeat: GHOSTTY_KEY_ACTION_REPEAT
        case .release: GHOSTTY_KEY_ACTION_RELEASE
        }
    }
}

private extension MouseAction {
    var ghostty: GhosttyMouseAction {
        switch self {
        case .press: GHOSTTY_MOUSE_ACTION_PRESS
        case .release: GHOSTTY_MOUSE_ACTION_RELEASE
        case .motion: GHOSTTY_MOUSE_ACTION_MOTION
        }
    }
}

private extension MouseButton {
    var isWheel: Bool {
        switch self {
        case .wheelUp, .wheelDown, .wheelLeft, .wheelRight: true
        case .left, .right, .middle: false
        }
    }

    var ghostty: GhosttyMouseButton {
        switch self {
        case .left: GHOSTTY_MOUSE_BUTTON_LEFT
        case .right: GHOSTTY_MOUSE_BUTTON_RIGHT
        case .middle: GHOSTTY_MOUSE_BUTTON_MIDDLE
        case .wheelUp: GHOSTTY_MOUSE_BUTTON_FOUR
        case .wheelDown: GHOSTTY_MOUSE_BUTTON_FIVE
        case .wheelLeft: GHOSTTY_MOUSE_BUTTON_SIX
        case .wheelRight: GHOSTTY_MOUSE_BUTTON_SEVEN
        }
    }
}
