import Foundation

/// A drive script: steps run in order once the preview's session attached. Each step is one
/// object naming one action; `name` labels it in the report and its saved image.
struct Script: Decodable {
    let steps: [Step]
    /// A failed step is recorded and the next one runs: a script whose frames each set their own
    /// state shows every frame whatever fails, and fails at its end naming every failed step.
    var goesOn: Bool?
}

struct Step: Decodable {
    var name: String?
    /// Text typed one key at a time, through the window's `sendEvent` and the input context.
    var type: String?
    /// A key pressed with `modifiers` ("control", "option", "shift", "command"): a letter, a digit,
    /// or "enter", "escape", "tab", "backspace", "arrowUp", "arrowDown", "arrowLeft", "arrowRight".
    var press: String?
    var modifiers: [String]?
    /// Marked texts set in order through the input client, then `commit` inserted.
    var compose: [String]?
    var commit: String?
    /// A click at a cell `[column, row]`, `clicks` times, with Shift when `shift`.
    var click: [Int]?
    var clicks: Int?
    var shift: Bool?
    /// A drag from one cell to another, `[[column, row], [column, row]]`.
    var drag: [[Int]]?
    /// Wheel lines, up negative.
    var scroll: Int?
    /// The window's content size in points, `[width, height]`.
    var resize: [Double]?
    /// "bigger", "smaller" or "reset".
    var font: String?
    /// ⌘C, then the pasteboard must hold exactly `pasteboard` when given.
    var copy: Bool?
    var pasteboard: String?
    /// `paste` put on the pasteboard, then ⌘V; `confirm` answers a confirmation sheet.
    var paste: String?
    var confirm: Bool?
    /// The drawable count from now on, 2 or 3.
    var drawables: Int?
    /// Waits until a visible line, trimmed, equals `line` (and the line above it equals `after`,
    /// when given), the top line equals `top`, a line is the grid's column count (`gridColumns`),
    /// the screen contains `text`, or the banner contains `banner`, within `seconds` (10 by
    /// default).
    var line: String?
    var after: String?
    var top: String?
    var gridColumns: Bool?
    var text: String?
    var banner: String?
    /// The workbench's own banner says this, as a closed tab's session going on elsewhere is said.
    var workbenchSays: String?
    /// The focused pane's banner no longer says this, within `seconds` (15 by default).
    var bannerGone: String?
    var seconds: Double?
    /// The visible text stays what it was when the step began while output reaches the screen,
    /// waited for at most `seconds`.
    var still: Bool?
    /// No output waited longer than this many ms to be shown, nor was the main thread held longer
    /// over the measure, each net of the drive's own thread's stall during it.
    var shownWithin: Double?
    /// Types `measure` keys, one each `interval` ms (50 by default), and records the latency.
    var measure: Int?
    var interval: Int?
    /// Measures the renderer's CPU side in this release build: a full 200x60 redraw's p95 must be
    /// 4 ms or less.
    var bench: Bool?
    /// ⌥⌘L, then the latency overlay must show its figures.
    var latencyOverlay: Bool?
    /// Starts the latency from nothing.
    var resetLatency: Bool?
    /// The host holds, live, no This Mac session that no pane holds (``Driver/heldAtHost(within:)``).
    var heldAtHost: Bool?
    /// Says what the system reports of this process now (``MachineState``) and fails unless it was
    /// launched as a person's Sail is: a foreground application whose parent is launchd and whose
    /// main thread is user-interactive.
    var machineState: Bool?
    /// Kills the host the preview started, as a crash would.
    var killHost: Bool?
    /// Makes the flood's bytes, once, at ``Driver/floodFiles``.
    var floodFile: Bool?
    /// Stops this Mac's host (true) so nothing it is sent is answered, or lets it go on (false).
    var pauseHost: Bool?
    /// Waits until the session is attached; false, until it is not.
    var attached: Bool?
    /// The view's font size must be this many points.
    var fontPoints: Double?
    /// The rail's item to select: "thisMac" or "account".
    var select: String?
    /// The control plane's address, typed into the account's field.
    var address: String?
    /// What the software passkey answers from now on: "credential", "none" or "cancel".
    var passkey: String?
    /// Enrolls with the ticket the environment variable of this name holds, then waits until the
    /// pane is idle.
    var enroll: String?
    /// Signs in, or out, then waits until the pane is idle.
    var signIn: Bool?
    var signOut: Bool?
    /// The Account pane shows a sign-in of the drive's own, for its frames: "long" (the longest
    /// handle, a long name, a long control plane, confirmed), "long-unconfirmed" (the same, not yet
    /// confirmed: the control plane out of reach), or "none".
    var accountShows: String?
    /// The user menu opened from the rail's user button: "click", a click on the button as a
    /// person makes it (again, it closes the menu), or "keys", the button given the keys and Space
    /// pressed (the kit runs, which turn Keyboard Navigation on).
    var menu: String?
    /// A click as a person makes it on the open user menu's item or Appearance segment of this
    /// title, or "outside" (on the card's middle); the step waits for what it did: the look drawn
    /// for an Appearance, the sign-out ended for Sign Out.
    var menuClick: String?
    /// The user menu says exactly this (``Driver/menuSaid``), within `seconds` (2 by default).
    var menuSays: String?
    /// Waits until the account pane's text contains this, or, with `exactly`, equals these lines.
    var pane: String?
    var exactly: [String]?
    /// Runs the bundled `sail` with these words, `{credential}` the software passkey's newest
    /// credential; it must succeed.
    var sail: [String]?
    /// Tells the app it became active, as a click on its window does.
    var activate: Bool?
    /// The software passkey was asked for this many ceremonies since the drive started.
    var asked: Int?
    /// The Keychain item of `address`'s control plane: "kept", with item 3's attributes, or "gone".
    var keychain: String?
    /// The sign-in pane's button drawn: its fill terracotta and its label `onAccent`.
    var accent: Bool?
    /// The window's title bar: transparent, the platform's buttons, the rail and the pane under it.
    var titleBar: Bool?
    /// Searches everywhere a token could be left for the session token and for `sess_`.
    var sweep: Bool?
    /// The step runs only on a signed build, or only on an unsigned one; skipped otherwise.
    var signed: Bool?
    /// The workbench must come to this within `seconds` (5 by default).
    var arrangement: Expected?
    /// A click at the middle of the selected tab's pane at this place in reading order.
    var clickPane: Int?
    /// The selected tab's divider at this index dragged `by` points in `moves` moves `interval` ms
    /// apart; the panes must follow each move.
    var dragDivider: Int?
    var by: Double?
    var moves: Int?
    /// The tab at the first index dragged to the middle of the tab at the second.
    var dragTab: [Int]?
    /// The tab at this index double-clicked, `rename` typed into its field, then `renameWith`
    /// pressed ("enter" by default, "escape", or "none" to leave the field open).
    var renameTab: Int?
    var rename: String?
    var renameWith: String?
    /// A tab's name is being edited with the keys in its field (true), or no field is open (false),
    /// within 2 s.
    var renaming: Bool?
    /// The sheet on the window answered with its button of this title, the sheet saying `asking`
    /// when given; "none": no sheet asks within a second.
    var sheet: String?
    var asking: String?
    /// The sheet on the window says these words, within `seconds` (5 by default), answered by none.
    var sheetShows: String?
    /// The workspaces run's control plane answers this route (`["POST /v1/terminal/tickets",
    /// "refusal-forbidden-not-assignee"]`) as that golden from now on, held this many seconds when
    /// a third is given.
    var golden: [String]?
    /// The Workspaces section says this, within `seconds` (10 by default), no call under way.
    var workspaces: String?
    /// The controls' gallery sheet of this name ("button", "field", "pill", "spinner") shot in
    /// every state and look, cut into `<control>-<state>-<look>.png`.
    var kit: String?
    /// The projects listed from now on, each its name and its template's state ("missing").
    var projects: [[String]]?
    /// harbor's workspaces listed from now on as this many made-up rows, half the person's and
    /// half another's, through every state.
    var rows: Int?
    /// This project's workspaces listed from now on as none.
    var noRows: String?
    /// harbor's three workspaces listed from now on with one more, the person's, of this name.
    var takenName: String?
    /// A scroll-wheel turn over `["list" | "panel", fraction]`: the Workspaces list or the left
    /// panel scrolled that fraction of the way down, as a person's trackpad does.
    var scrollTo: [String]?
    /// The Workspaces list's ("list") or the panel's ("panel") scroll thumb dragged down by a real
    /// mouse-down, drag and mouse-up through the window; it passes when the thumb was drawn dragged
    /// and the list moved.
    var dragThumb: String?
    /// `[row, item]`: the Workspaces row's ⋯ menu opened by a real click, and its item of this
    /// title chosen while the menu is open (AppKit's own `performActionForItem`: a posted event does
    /// not reach a menu while AppKit tracks it).
    var menuItem: [String]?
    /// The focused pane says ending it may end a program (true: the cursor has left the shell's
    /// marked prompt, as a command it runs moves it), within `seconds` (10 by default): a close sent
    /// before the shell has answered a command's Return finds the cursor still at the prompt.
    var mayEndAProgram: Bool?
    /// `[name, "stopped" | "gone"]`: harbor's list from now on as it is answered now, that row stopped
    /// or left out, as the runner lists it after a stop or a delete.
    var relistAs: [String]?
    /// A plain delete of that harbor workspace refused as holding work, in the server's sentence
    /// naming it.
    var refuseDelete: String?
    /// This route's answers are held, from now on, until `releaseAnswers` names it.
    var holdAnswers: String?
    /// The answers held for this route are given now, and its later ones at once.
    var releaseAnswers: String?
    /// `[words, never]`: the pane comes to say `words` within `seconds` (10 by default) and never says
    /// `never` meanwhile, the pane read every 10 ms.
    var saysWithoutFirst: [String]?
    /// How many Delete Anyway questions wait behind the sheet open, within `seconds` (10 by default).
    var questionsWaiting: Int?
    /// `[route, words]`: the control plane has answered `route` while the sheet still says `words`,
    /// within `seconds` (10 by default); not shown when the sheet changed first or the answer never came.
    var answeredWhileSheetShows: [String]?
    /// Every delete the control plane was asked, in order, is exactly these routes, within `seconds`
    /// (5 by default) for the last of them to come.
    var deletesAsked: [String]?
    /// The button of this title on the sheet now asking, clicked with real mouse events through the
    /// sheet's own window.
    var sheetClick: String?
    /// ⌃⌘S pressed (View ▸ Show Sidebar), and whether the panel is then shown (over the content)
    /// or hidden again, within `seconds` (5 by default).
    var showSidebar: Bool?
    /// The Workspaces section shows its skeleton bars (true) or none (false), within `seconds` (5 by
    /// default).
    var skeletons: Bool?
    /// The Workspaces row of this name selected and Open pressed: a tab on the runner.
    var open: String?
    /// The Workspaces row of this name clicked: chosen, with the list holding the keys (its Open
    /// says ⏎), within `seconds` (5 by default).
    var chooseRow: String?
    /// The left panel's project of this name clicked once the panel lists it; the section then
    /// names it, within `seconds` (10 by default).
    var chooseProject: String?
    /// The window's frame made `[width, height]`, its top left kept: each frame shot at the
    /// design's size.
    var window: [Double]?
    /// The window's frame made the least it takes (`ShellApp.leastFrame`), its top left kept.
    var leastWindow: Bool?
    /// The Workspaces section no longer says this, within `seconds` (10 by default).
    var notSaid: String?
    /// The Workspaces button of this title clicked with a real mouse-down and mouse-up through the
    /// window ("Create", "Cancel").
    var clickButton: String?
    /// The create row is open (true) or gone (false), within `seconds` (5 by default).
    var createRowOpen: Bool?
    /// The name field holds the keys (true) or does not (false); with `fieldHolds`, it holds that name.
    var keysInField: Bool?
    var fieldHolds: String?
    /// The list holds the keys (true) or does not (false), within `seconds` (5 by default).
    var keysInList: Bool?
    /// The Workspaces section says this while a person's action is under way, within `seconds` (5
    /// by default).
    var whileBusy: String?
    /// The Workspaces row chosen is the one of this name, within `seconds` (10 by default).
    var listChosen: String?
    /// A proxy answers this route ("GET /v1/projects/harbor/workspaces") with a 502 from now on;
    /// empty, no route.
    var proxyFails: String?
    /// This route ("GET /v1/projects") cannot reach the control plane from now on; `proxyFails`
    /// empty clears it.
    var unreachable: String?
    /// The drive's runner door holds this many sessions, within `seconds` (15 by default): the
    /// host's session list.
    var doorLists: Int?
    /// Someone else holds the focused terminal's write token at the runner's door from now on.
    var doorHoldsWrite: Bool?
    /// Someone else lets go of it.
    var doorReleasesWrite: Bool?
    /// The runner's door holds a session of this name, as one its person opened from another Mac.
    var doorHolds: String?
    /// The person's other Mac takes the focused terminal's keyboard at the runner's door.
    var doorTakesKeyboard: Bool?
    /// The client not the app's that holds the focused terminal's keyboard leaves it, as its person
    /// quitting Sail on the other Mac does.
    var doorHolderLeaves: Bool?
    /// The runner's door ends every session it holds in these words, as a workspace's stop does.
    var doorEnds: String?
    /// The focused terminal's banner button of this title pressed.
    var pressStatus: String?
    /// The drive's runner door's workspace folder (a path under `/home/dev/workspace`, "" the root)
    /// holds these entries from now on, a trailing "/" a folder, its watchers told; with
    /// `filesCount`, that many files `berth-0000.json` on in its place.
    var filesFolder: String?
    var filesEntries: [String]?
    var filesCount: Int?
    /// A listing of this folder is refused as not permitted, in the workspace's words.
    var filesRefuse: String?
    /// The files panel shows this many rows, within `seconds` (10 by default).
    var filesRows: Int?
    /// The files panel's line says words starting with this ("" no line), within `seconds`.
    var filesSays: String?
    /// The entry of this name is marked new, within `seconds`.
    var filesNew: String?
    /// The files panel's row of this name clicked as a person clicks it.
    var filesClick: String?
    /// The files panel's row of this name double-clicked on its name as a person does.
    var filesDoubleClick: String?
    /// The files panel's line's button of this title clicked.
    var filesButton: String?
    /// The door holds every listing from now on (true) or answers them (false).
    var filesHold: Bool?
    /// The door's watch ticks each second (true) or falls silent (false).
    var filesTicks: Bool?
    /// The door ends every files connection with this record and code (`workspace_stopped`).
    var filesEnd: [String]?
    /// The door drops every files connection with no word.
    var filesDrop: Bool?
    /// The window in both looks, then right to left in both, saved as `window/<this>`.
    var filesShot: String?
    /// The files panel measured against the spec's figures: opening, a 5,000-entry listing,
    /// scrolling it, a change, a burst of 2,000.
    var filesBudgets: Bool?
    /// The tab's terminal floods at the panes4 load while the panel beside it is open and its tree
    /// changes: the flood judged as each panes4 flood is, and each change to the screen.
    var filesFlood: Bool?
    /// Sign Out pressed on the account, answered no further: what follows it asks.
    var signOutNow: Bool?
    /// A ticket was asked with these fields (`{"action": "open", "workspace": "dock"}`), within
    /// `seconds` (15 by default).
    var ticketAsked: [String: String]?
    /// The app is asked to quit, as Quit Sail does, from the run loop: AppKit waits in the quit
    /// until a sheet it asks on is answered, which a later step does.
    var quit: Bool?
    /// The whole screen in both looks, a sheet over the window included, saved as `window/<this>`.
    var shotScreen: String?
    /// The focused pane's session kept for `listed`.
    var remember: Bool?
    /// The session `remember` kept is listed live by the host, or not, within `seconds`.
    var listed: Bool?
    /// The context menu of the selected tab's pane, or of the tab, at `at`, asked as a right-click
    /// asks it (a pane is focused first, a tab selected); it must hold `items` ("-" a separator),
    /// and `choose` is done as a click on it.
    var contextMenu: String?
    var at: Int?
    var items: [String]?
    var choose: String?
    /// The focused pane's cursor blinks: two frames a blink apart differ only in the cursor's cell;
    /// unfocused, nothing is drawn and nothing wakes; blinking, its display link does not fire; the
    /// wakeups a second are reported.
    var blink: Bool?
    /// Another copy of Sail's host and this app (third run): "kept" (it has a client, so the app
    /// ended nothing and said what holds it), "free" (the host serving lists no session with a
    /// client: a client that quit has been seen to leave), "replaced" (no client: the app said its
    /// terminals are closing, ended it, and its own host serves, the only one).
    var otherHost: String?
    /// The app's window as the screen shows it (`screencapture -l`, so what AppKit draws in the
    /// title bar and the Metal layers are in it), in the light look and the dark, saved as
    /// `window/<this>-light.png` and `-dark.png` beside the runs' folders.
    var shot: String?
    /// The focused pane's context menu open, the whole screen taken while it shows, in both looks.
    var shotMenu: String?
    /// The window in full screen, the whole screen taken, in both looks; then out of full screen.
    var fullScreen: String?
    /// Every process whose command line holds this is ended (`pkill -f`); none running is no failure.
    var kill: String?
    /// The tab strip's hit testing: a tab's middle reaches the tab, the empty end moves the window.
    var stripHits: Bool?
    /// The four-pane measure (scenario 32), the look switched with their scrollback full among it.
    /// Its own waits reach 180 + 60 s, so its script gives `seconds` (300) as the step's whole budget,
    /// which ci/drive's hang watcher reads.
    var panes4: Bool?
    /// "menu": `panes4`'s looks switched by clicks on the user menu's Appearance, the menu open
    /// over the panes, not by the system's appearance changing.
    var switchBy: String?
    /// Makes the colour asker, once, at ``Driver/colourAsker``.
    var colourScript: Bool?
    /// The app's look set to "light", "dark" or "system" (the system's again), as the system's
    /// appearance changing sets it; the shell must take it and every terminal shown draw a frame
    /// in its palette within 5 s.
    var look: String?
    /// Waits this many seconds.
    var wait: Double?
    /// Each pane of the selected tab shows a line `winch-<columns>` (a program printing one at each
    /// SIGWINCH) only for each time the last divider drag held still for the session's resize hold,
    /// and once at its end, the last its view's grid, within `seconds`: one size per settle (W5),
    /// whatever pace the machine let the moves keep.
    var onceResized: Bool?
    /// The focused pane's screen must not hold this text.
    var absent: String?
    /// The focused pane's screen holds `occurs` exactly `times` times, within `seconds`.
    var occurs: String?
    var times: Int?
    /// The focused pane's program sets its title this many times: no arrangement may be written.
    var titleBurst: Int?
    /// ⌃⌘→ pressed this many times at once: the arrangement is written once, after.
    var dividerBurst: Int?
    /// Keeps the focused pane's view; `heldPaused` finds its display link paused while its tab is
    /// hidden, and `heldResumed` finds it drawing again once shown.
    var hold: Bool?
    var heldPaused: Bool?
    var heldResumed: Bool?
    /// Keeps the focused pane's top line; `sameTop` finds it there still.
    var markTop: Bool?
    var sameTop: Bool?

    /// The workbench: panes in each tab, the selected tab and focused pane (from 0, reading order),
    /// the selected tab's pane frames as fractions of its room `[x, y, width, height]`, and points
    /// added to each when four more are given, within 1 pt;
    /// whether a pane is zoomed, each tab's title.
    struct Expected: Decodable {
        var tabs: [Int]?
        var selected: Int?
        var focus: Int?
        var frames: [[Double]]?
        var zoomed: Bool?
        var titles: [String]?
        /// Which of the selected tab's panes lie under the scrim, in reading order.
        var dimmed: [Bool]?
        /// "No terminal is open" shows.
        var empty: Bool?
        /// Which tabs the strip marks as holding a terminal that may still be running.
        var unended: [Bool]?
    }
}
