# AGENTS.md

Sail is the orchestration layer around coding agents: one Incus container per project, a
SQLite control plane synced between an org's main box and each engineer's node, specs as the
unit of work, and a review loop that runs a build, reviewers and fix agents. Java 25, three
Maven modules (`sail-core`, `sail-harness`, `sail-infra`), one GraalVM native binary. Design
and contracts: `ARCHITECTURE.md`. Build, gates, migrations, releases: `CONTRIBUTING.md`.

## Build and test

- `ci/check quick` is the loop for a change: it formats, then runs every unit test of every
  module, compiling incrementally and without JaCoCo, in about two minutes on an 8-core box. It
  does not run the coverage gates, the `*IT` suites, the macOS suite or the native build, and it
  does not `clean`: a deleted or renamed class leaves its compiled `.class` in `target`, so after
  one, run the gate.
- `mvn clean verify` is the gate CI runs: every unit test, JaCoCo coverage gates, spotless and
  the quality gate (see "Quality gate"; two and a half to three minutes). `mvn spotless:apply`
  formats. Build offline with `-o` when the repository is warm.
- Before pushing: `mvn -o -Pintegration -Dsail.it.requireIncus=false clean verify`. It adds
  the multi-node integration tests (`*IT`); the incus-backed ones skip here and run in CI.
- One module and class: `mvn -o -pl sail-infra -am -Dtest=StageSkillsTest test`
  (`-am` is required, or sail-core resolves stale from `~/.m2`).
- Native binary: `JAVA_HOME=<graalvm-jdk-25> mvn clean package -Pnative -DskipTests` →
  `sail-infra/target/sail`.
- Log a long verify to a file and read its `BUILD` line and `Tests run:` totals; never judge
  it from a `| grep | head` pipeline.

## Working a spec

- Specs live in the sail database. Use the `spec` CLI (`spec board`, `spec show <id>`,
  `spec comment <id> --body <text>`, `--question` when blocked). Never change a spec's status;
  sail does.
- Branch `agent/<spec-id>`, one pull request, squash-merged. Post progress and the final
  summary in the spec's room.
- No AI attribution anywhere: no `Co-Authored-By` trailers, no "Generated with" lines.
- The pull request body names what the change deleted, with the `git diff --shortstat` count. A
  change that only adds is read twice.
- The spec on the board (`spec show <id>`) is the contract; read it in full first. Design before
  code: the classes that change, what is deleted, the one seam, the risks, and the main-source lines
  you expect to add and remove. Past about 1500 main-source lines, stop and propose the split.
- A ruling in the spec that is wrong, or a scenario that cannot hold, is asked in the room
  (`spec comment <id> --question`) with your recommendation; never built around. Go on with the
  rest meanwhile.
- A change's entry is one file (`changes/README.md`): a branch `agent/<name>` keeps it in
  `changes/<name>.md`, so a spec's branch `agent/<spec-id>` adds `changes/<spec-id>.md`, its later
  pull requests edit it (or add it again once a release has folded it), and a second branch of one
  spec has an entry file of its own. A pull request may correct another entry not yet released
  but adds or deletes no other, and none edits `CHANGELOG.md`, which only the release writes
  (`ci/changelog`). A schema or wire change is bumped once and said in that entry.
- A change to what a working install does changes its `ci/fleet` rows in the same pull request;
  a task given to a fleet agent is one no repository already meets and asks no question.
- Stage the files you mean by name: never `git add -A`, never `git stash`. Push only on a green
  local gate, one push a round; a pull request that conflicts with main starts no CI, so merge
  main first. After a push read every lane (the Incus ITs run only in CI) and root-cause any red
  from its log; a lane is never re-run to green.
- Done is a draft pull request green on every lane whose body maps each invariant and scenario to
  its test, names the mutants that prove the tests bite, the deletions and net lines, every
  deviation from the spec with its reason, and anything wrong found nearby, fixed in it when this
  pull request ships it.

## Review

A reviewer owes the change nothing. A finding is shown from the code or from a run (the command
and its output), never asserted. The bar: functionally correct, reliable, covered, readable, one
abstraction a job, SRP, DRY, KISS, this file's conventions and nothing unnecessary. Each finding
has a severity, `file:line`, what is wrong, the evidence and the fix: BLOCKER for wrong behaviour,
a widened permission, a broken invariant or a test that cannot fail; MAJOR for a structural defect
by this file's bar, changed behaviour without coverage or a misleading document; MINOR for naming,
wording and small duplication. A review ends with what was checked and found sound.

## Shape of a change

The bar is the same for the agent that builds, the agent that reviews, the agent that fixes
findings and the person who merges: a change is judged on what it leaves behind, not only on
whether it works. Each of these is a finding at review and a reason not to merge:

- A gap in an existing mechanism fixed beside it instead of inside it: a second reconciler,
  probe, sweep or branch next to the one that already owns the case. Fold the case into the
  owner; if the owner cannot take it, the spec says why.
- A verb added to a facade that already has one of everything (`SailOperations`, `FdeCommand`,
  `ServerStartCommand.execute`, `SyncOperations`). New behaviour lands in the lane class for its
  domain; the facade delegates.
- A state compared or written as a string literal where an enum exists (`RunStatus`,
  `SpecStatus`); a stringly `kind` where an enum would do.
- A field set after construction (`use*` setters, `volatile` wiring) and the null guard it needs.
  One constructor, built whole.
- An overload, constructor or record copied with one more parameter. One parameter record, one
  constructor, static factories.
- Forwarding methods, hand-written `toMap()` bodies, a second parser for the same request, a
  second catch ladder. One seam, used from both sides.
- A test fake that re-implements a support class (`ScriptedShellExecutor`, `ManualClock`,
  `SyncBox`, `Fleet`), a private row builder, a copied preamble.
- Compatibility with an older release: a box of another release is refused at its database
  and at the sync hello, so nothing reads what an older one wrote.

A refactor spec is behaviour-preserving and its bar is the net deletion it names; a feature spec
names the mechanism its behaviour folds into. Before merge: a full verify logged to a file, an
independent review of the delta against the spec's bar, the fix-reverting mutants that prove the
tests bite, and the deletion count in the body.

## Code

- No inline comments, in Java or Swift: only a documentation comment (Javadoc, `///`), and only
  for the non-obvious why. If code needs a comment to be understood, rename or extract instead.
- Modern Java: `var` for locals, records for values, sealed interfaces for alternatives,
  text blocks for multi-line strings, `List.of`/`Map.of` for constants, pattern matching,
  try-with-resources, `java.time`, `ProcessBuilder`. No wildcard imports (PMD fails them), no
  inline fully qualified names.
- Shared seams are in `ai.singlr.sail.common` (`Ids`, `Strings`, `DateTimeUtils`); use them.
- Two runtime libraries, picocli and SnakeYAML Engine, and no more. Do not add a dependency.
- Every command is idempotent and offers `--dry-run` and `--json`. Every error says what
  happened and what to do.
- Untrusted input (names, paths, spec text, agent output) is validated once, at the edge,
  and reaches a shell only as an argument, never interpolated into script text.
- Every sentence Sail prints that names a command works when that command is pasted as printed,
  and a test proves it. A `--json` refusal carries the API's error code.
- No secret in any command line, log, event, test output or pull request body.
- Fresh installs only: no migration, upgrade or reinstall polish.
- One rule, one place: every permission is `Access.decide` over the `Policy` table
  (`ai.singlr.sail.access`), every read of a row `ReadFilter`, every resource and its owners
  built by `Resources` from the facts its caller selects of the row, every door's credential
  resolved to its principal by `RoleRule.principal`, one `Actor` bound at each door, and every
  web and socket route declaring one `Gate` (`RouteGateSweepTest`): the router applies the box
  and people gates; a deciding gate names the action its operation decides. Every host command
  line is `SailCommandLine`'s, which reads as the box's person (`SailCommandLineTest`). A type
  rule (`ai.singlr.sail.authority`) names the actions a revision takes and keeps only its own
  facts. Nothing else reads a role, a lane, a scope or a capability to decide
  (`OneDecisionSweepTest`); a second derivation is a bug, not a convenience. The journal
  decides every write as it records the revision; a door asks `Access`, or the journal of the
  write it is about to make, never a write rule, and a rule never reads from the database the
  row it is deciding (it has `held`). The one exception is
  `MessageAuthority.post`: a post with no message to journal (the terminal's room pin, a room
  session's report) still answers to the message rule's own fact that a post is someone's, so
  those two doors ask the rule's post decision (`OneDecisionPointTest` pins them).
- The permission snapshot (`ai.singlr.sail.access.baseline`, the `*BaselineTest` corpora and
  their `access/baseline/*.tsv`) records every answer a cast of principals gets at the journal,
  main's commit, the event rule, the read paths and the doors, and each test fails on any cell that
  answers otherwise. A change that alters an answer on purpose re-records it with
  `-Dsail.access.baseline=record` (the command is in `Baseline`'s Javadoc), and the `.tsv` diff in
  its pull request is the list of what changed, cell by cell. The goldens a native client decodes
  (`ClientGoldensTest`, `clients/SailKit/Tests/Goldens`) are a snapshot of the same kind
  (`GoldenFiles`), re-recorded by the same switch.
- Prefer deleting over adding. The smallest change in the right place; a duplicated seam is
  a finding to fix in the same change. Finish a move end to end: the old path goes in the change
  that adds the new.
- Follow the patterns this repository has; add no new one without need.

## Tests

- Test behaviour through the production seam, with exact assertions (`assertEquals` on the
  whole message, never `contains` where equality is possible). No `Thread.sleep`: latches,
  callbacks, fake clocks with a horizon that throws. Every wait is bounded.
- Failing tests first, for every behaviour, and each test proven to bite: a mutant that reverts
  the fix turns it red (PIT, the `mutation` profile, for Java; by hand for Swift and the scripts).
- A fake answers only for what exists: a fake container that answered to any instance name once
  hid a bug.
- No network and no external service in a unit test. A test that fails once is a bug to
  root-cause with evidence; "flaky" is not a diagnosis and a rerun is not a fix.
- A unit test of the Mac app never runs AppKit's own mouse tracking (a control's `mouseDown`) or
  posts events to the app's queue (`NSApp.postEvent`): with no `NSApp.run` under it, that stopped
  the main run loop and ended the suite's process (#499); real clicks and keys are the drive's.
- The drive's timing gates are of two kinds, and a new one is one of them, never a third. A
  figure taken over tries (`f-budgets`, `p-figures`) goes through `TimingGate`: the step takes its
  tries, the machine is read once over the union of their spans (`MachineReading`), and when the
  other processes left us under 5% of at least 100 ticks (idle plus our own share, so our own work
  never excuses itself) the step's gates are judged at twice their ceilings, a `::warning::` and the
  job summary name it, and the step is "not measured cleanly". The files flood's change p95 takes
  the same path, but its tries are a few milliseconds long and its reading is always too short, so
  it is judged at its ceiling. A single long wait (a flood, a split, the terminal's latency) is
  judged by `ours()`, less the time `MachineShare` proves the machine starved it, our share kept
  ours by the same `MachineShare.ourShare`. A ceiling sits outside the runner's own range as seen
  on main and other branches, with that range written beside it. Every run's figures, marks and
  reading are in `figures.tsv` and the job summary; `clients/SailApp/ci/figures-trend` prints
  main's last 30 runs as one series a figure.
- Nothing a layout pass of the Mac app reaches (`layout()` and what it calls, `updateConstraints`,
  `viewDidLayout`, `resizeSubviews`, `setFrameSize`) changes what forces another pass: an intrinsic
  size, `preferredMaxLayoutWidth`, a label's text or font, a constraint's `constant` or `isActive`.
  AppKit then asks for another pass from inside one and ends the app (#502). A view whose height
  follows its width alone may invalidate its size when its width changes.
- A precondition a test then relies on (an index, a force, a returned view) is `try #require`,
  never `#expect`, which goes on; `TestRulesTests` holds the app's and SailKit's tests to it.
- A Mac app suite whose tests wait on the main actor beside long sweeps runs one at a time
  (`.serialized`, its reason beside it); the proof is a parallel repeat on the efficiency cores.
- Coverage: `ai.singlr.sail.api.*` is held to 100% line and method coverage; new classes
  anywhere should be fully covered in the same pull request. Gates never go down.
- Prompts, hook files and launch commands are pinned whole by golden tests; a change to them
  changes the golden text deliberately.
- A fake ends a connection as the real host does: it closes it, never shuts it down alone. On
  macOS a unix socket's `shutdown()` may not wake the other end's reader blocked in `recv()`; a
  close always does.
- A socket test waits for what it needs (a frame, a session's event, a held wait), never for a
  time: its suite's `.timeLimit` is its only bound. A client limit the test is not about is
  `Patience.answer`, past every suite's limit; elapsed time a session decides on (an attachment's,
  a runner's patience, a ticket's age) comes from a clock the test holds (`SessionClock`); a fake
  reads what the client sends on a thread of its own. A 3-core macOS runner once held the tests'
  tasks for 20 s, and every 5 s wait that straddled it failed (#551).
- A test that runs a container script for real (`flock`, `mv -T`, `/proc`) is
  `@EnabledOnOs(OS.LINUX)`: the release workflow runs the suite on macOS too.
- An Incus IT's Sail home logs (`target/it-home-*/.sail/logs`) ship whole in a failed lane's
  artifact, so nothing secret is ever written there: no token, no key, not a fake one that looks
  real.
- A change to a contract in `ARCHITECTURE.md` (the sync, loop and skill rows) changes the
  row and the tests it names in the same pull request.

## Quality gate

`mvn verify` fails on a size, coupling, import or architecture violation in main or test code
and prints the class, line, rule and its limit. Duplication is reported until its burn-down turns
it on. The gate is Helios's (`standardapplied/helios`), with its versions and limits. The rules a
script decides over the Swift's and the Java's text, with no build, are `ci/rules`' (below).

| Tool | Enforces | Where |
|---|---|---|
| Enforcer | Java 25 or later, Maven 3.9.14 or later, a version on every plugin | root `pom.xml` |
| PMD (`maven-pmd-plugin`, every module) | the limits below, main and test code | `config/quality/pmd-main.xml`, `pmd-test.xml` |
| CPD (same plugin, at the reactor root) | no block of 100 tokens or more repeated, main and test, within or across modules; reports only, until its burn-down | root `pom.xml` |
| ArchUnit (`architecture` module, build-only, never shipped) | one way to do each thing: module directions, the two runtime libraries, picocli in `sail-infra` only, `Clock.system*` only as a field's initial value, a UUID minted only by `Ids`, nothing `@Deprecated` | `architecture/src/test/java/.../ArchitectureRulesTest.java` |
| PIT (`mutation` profile, by hand) | which mutants a change's tests kill; it replaces hand-made mutants in a report | root `pom.xml` |

Gated today, failing at the first measured value shown:

| PMD rule | Measures | Fails at |
|---|---|---|
| `TooManyFields` | non-static, non-final fields of a class | 16 |
| `CouplingBetweenObjects` | distinct types a class uses in fields, locals and return types | 21 |
| `GodClass` | weighted methods (WMC ≥ 47), foreign data access (ATFD > 5) and low cohesion (TCC < 1/3) together; main code only | all three |
| `TooManyMethods` | methods of a class, not counting `get*`, `set*`, `is*`, `with*`; main code only | 26 |
| `SwitchDensity` | statements per `switch` label | 10 |
| `OnDemandImport` | `import a.b.*`, `import static a.B.*` | 1 |
| `AvoidDeeplyNestedIfStmts` | depth of nested `if`; test code (main code is pending) | 3 |

Still to burn down, each family its own pull request in this order, with what it broke on
1b2ff7fa5. Their rules wait in `config/quality/pmd-main-pending.xml` and `pmd-test-pending.xml`,
which no build analyses; a family's pull request moves its rules, unchanged, into the gated file,
and the pending files only shrink. `config/quality/helios-pmd-main.xml` and `helios-pmd-test.xml`
are Helios's two rule files verbatim, which no build reads. `RuleSetsTest` (architecture) holds the
gated and pending sets of each kind together to them: every rule is in exactly one of the two and
equals Helios's in every attribute, property and XPath, only its message aside (and Helios's
test-helper package read as `ai.singlr.sail.test`), so a rule deleted, weakened or in both fails.

1. The wall clock read statically (8 reads in 5 classes), an `HttpClient` built outside one
   factory (11 classes), `Thread.sleep` outside one sleeper (17 sites): ArchUnit rules.
2. Top-level types named `*Util`, `*Utils`, `*Helper`, `*Helpers` or `*Manager` (10, `DateTimeUtils`
   among them).
3. `ProcessBuilder` outside `engine.ShellExecutor` and `pty.Pty` (102 uses in 23 classes).
4. Time in tests, through one `ai.singlr.sail.test.Await` with `LineSink` and
   `FeedableInputStream`: `TimedWaitInTest` (314), `ClockReadInTest` (251), `PipedStreamInTest`
   (46), `SleepInTest` (14).
5. Duplication: 104 blocks, 12 of them in main code.
6. Method-level complexity, per module: `CognitiveComplexity` 15 (80 main, 31 test),
   `CyclomaticComplexity` 15 a method and 80 a class (110, 10), `NPathComplexity` 200 (49, 5),
   `AvoidDeeplyNestedIfStmts` 3 (13 main), `NcssCount` 60 a method and 600 a class (18 main),
   `ExcessiveParameterList` 10 (51, 2).
7. Test size: `NcssCount` in test code, 60 a method and 600 a class (36).

The classes that broke the class-level rules when the gate went on (118 classes and 186 rule
breaks, measured on main d5ffc5e9a) are listed once in `config/quality/pmd-exclusions.properties`,
each with only the rules it broke. Test classes that break a class-level rule (coupling, too
many fields) are listed in the same file on the same terms; test-class size is not a listed rule
and is burned down in its own pull request.

### The rules a script decides (`ci/rules`)

`ci/check rules [BASE]` (`ci/rules check`) decides these with no build, in about a second on the devbox; it
reads a rule not yet on only to its first finding. CI runs it in the
first job of both workflows (`What the change touches`), which every other job needs, so no lane,
and no macOS lane, starts on a change that breaks one; a pull request's run gives it the pull
request's base, which also holds the pull request to its entry. It fails on a finding of a rule
that is on, and on a rule not yet on that finds nothing, whose findings it only counts. A finding
is one line, `file:line: rule: what it found: the rule's sentence`; `ci/rules list` prints each
rule, whether it is on, and its sentence. One lexer reads the Swift under `clients/` and the Java
under `*/src/`, so nothing in a string literal or a comment is taken for code (Swift's nested block
comments, raw and multi-line strings and their interpolations, whose code is code; Java's text
blocks and character literals). `CiRulesTest` holds each rule to an offending and a clean fixture.

| Rule | On | Decides | Where, and what is exempt by role |
|---|---|---|---|
| `ellipsis` | yes | A menu item's or a button's label names its action, with no "…", "..." or `\u{2026}`; progress text keeps its ellipsis. Decided on a literal given as a label of the app's controls, on its call's line or a later one, on either side of a conditional (chained or in parentheses too) or after a `??`: `SailButton`'s title and `label:`, `LinkButton`'s title, `SailList.Action`'s title, `Sheet`'s and `WorkspacesModel.Question`'s `confirm:`, `Card.show`'s `button:` and `second:`, `Confirmation.ask`'s `action:`, `Command`'s `title:`, the menu bar's `item`, `directed` and `submenu` (`ShellApp.menu()`), AppKit's `NSMenuItem`, `NSMenu` and `NSButton` titles and `addItem`, `insertItem` and `addButton(withTitle:)` | `clients/*/Sources/`; the drive, `SailTestPasskey` |
| `lists` | yes | A list is a `SailList` and its rows are its: an AppKit table, outline, collection, row or cell view made (called, through `.init`, qualified by `AppKit.`, or as a declared type's `.init()`) or inherited, also on the line after the class's colon | `clients/*/Sources/` outside `clients/SailApp/Sources/SailControls/`; the design system, the drive, `SailTestPasskey` |
| `changes` | yes, a pull request's | Its entry (`ci/changelog check BASE`): on `agent/<name>` it adds or edits `changes/<name>.md` and adds no other; on a branch of another name it adds at most one and adds or edits one; it may edit another entry, never delete or rename one; it never edits `CHANGELOG.md` | a push to main, where the release folds, has no base |
| `comments` | not yet | "No inline comments" (Code): a `//` or `/* */` comment that is not `///` or `/** */`; a `// MARK:` is one | Swift under `clients/`, Java under `*/src/`; a `Package.swift`'s first-line tools-version, a Java file's licence header (a block comment opening its first line) |
| `sleeps` | not yet | A Swift test waits for the event it needs, never for time: a call of `Task.sleep`, `Thread.sleep`, `usleep`, `nanosleep`, `sleep`, or a `ContinuousClock()` or `SuspendingClock()` `sleep` | `clients/*/Tests/`; a fake clock's own `sleep`, declared or called on it |
| `tokens` | not yet | A view takes its colours, fonts, spacing, radii and edge widths from the design system, never a literal: no colour or font constructor, no system colour but `.clear`, no literal but 0 for `spacing`, `NSEdgeInsets`, `cornerRadius`, `x`/`yRadius`, `lineWidth` or `borderWidth` | `clients/*/Sources/`; the design system (`clients/SailKit/Sources/SailDesign/`, `SailVT/Palette.swift`, `clients/SailApp/Sources/SailLook/`), the drive, `SailTestPasskey` |
| `components` | not yet | An AppKit button, editable field (`NSTextField()`, `(string:)`, `(frame:)`, secure or search), alert or panel made as `lists` reads it, a subclass of one, or a `beginSheet`: a view uses `SailButton`, `SailTextField` or `Sheet` | as `lists` |
| `function-lines` | not yet | A function is at most 80 code lines (not blank, not only a comment) | Swift under `clients/` |
| `parameters` | not yet | A `func` or `subscript` takes at most 6 parameters | Swift under `clients/`; an `init`, which takes its type's stored properties |
| `nesting` | not yet | A function's body nests at most 4 blocks deep | Swift under `clients/` |
| `file-lines` | not yet | A file is at most 800 code lines | Swift under `clients/` |

What a script cannot decide, a reviewer holds to the same sentences: a bare number used as a
size or a spacing in frame arithmetic or an anchor; a view that styles a primitive it did not
make; a label set after construction (`.title =`) or passed in a variable. Every record a `--json`
verb prints has its entry in
`sail-infra/src/main/resources/META-INF/native-image/ai.singlr/sail-cli/reflect-config.json`, and
a new one joins
`PtySelfTestCommand.jsonProbeValues()`: `CliJsonReflectionTest` (sail-infra) reads the records from
those values and their components' declared types, so a record printed outside the probe is the
reviewer's to catch.

A lesson from a red lane ends as a sentence here and, where a script can decide it, as a rule of
`ci/rules` with its two fixtures, in the pull request that fixes the lane.

### Rules of the gate

- **Fix the code, never the gate.** No limit is raised, and no rule moves back to a pending file.
- **No suppression in source.** Neither PMD's suppression comment, CPD's `CPD-OFF`/`CPD-ON`
  comments, nor a `@SuppressWarnings` naming a PMD rule, `PMD` or `all`. PMD honours these however
  they are spelled (concatenated, in a constant, through a unicode escape) and has no switch to
  turn them off, so `ci/exclusions` fails, naming file and line, any Java line holding `NOPMD`,
  `CPD-OFF` or `CPD-ON`, a `@SuppressWarnings` that is not one line of lowercase literals such as
  `@SuppressWarnings("unchecked")`, `"all"` in one, or a printable ASCII character written as a
  unicode escape (a control character such as `\u001b` stays allowed). That check needs no history
  and runs first, in `sail-infra`'s Linux unit job and in `ci/check full`.
- **The exclusions file only shrinks.** No class is ever added to it and no rule to an entry. A
  spec that works in a listed class splits it and deletes its entry. `ci/exclusions` (run by
  `sail-infra`'s Linux unit job and `ci/check full`) fails a change whose file names a
  `class=rule` pair its merge base's does not, or names a class on two lines. PMD reads the file
  as Java properties, which also take `:` or a blank as the separator, join a line ending in `\`,
  read escapes and end a line at a lone carriage return, so `ci/exclusions` also fails any line
  that is not a `#` comment, blank, or exactly `fully.qualified.Class=Rule1,Rule2` from the first
  column: what it accepts, Properties reads as it does. `ExclusionsShrinkTest` (architecture) reads
  every module's PMD reports, which the exclusions do not filter, and fails on an entry whose
  class no longer breaks a rule it lists; the change that cleared it deletes it.
- **A rule of `ci/rules` is on or not yet, never in part.** It has no list of tolerated sites; a
  file it must not judge is named once, in the script, by its role. The pull request that clears a
  rule moves its name from `NOT_YET` to `ON` in `ci/rules` and turns its row above on:
  `ci/rules check` fails on a rule not yet on that finds nothing, so it fails that pull request,
  whichever lanes it runs, until it does. A control that takes a label a person presses or
  chooses adds its `callee:argument` to `LABEL` in `ci/rules`, with an offending fixture, in the
  pull request that adds the control.
- **Adding an ArchUnit rule.** A spec that establishes or consolidates a pattern adds one `@Test`
  to `ArchitectureRulesTest`, named for the rule, whose `because` names the one allowed way. A new
  module is added to `architecture/pom.xml` and to `ExclusionsShrinkTest`'s modules.

### Running it

```bash
ci/check full                                             # everything, as CI's Linux unit jobs run it together
mvn -o verify -DskipTests                                 # enforcer, PMD and CPD only
mvn -o pmd:aggregate-cpd@cpd pmd:aggregate-cpd-check@cpd-check   # duplication alone, in seconds
ci/check exclusions                                       # no suppression in source; the exclusions against where they left origin/main
ci/check rules "$(git merge-base origin/main HEAD)"      # the rules ci/rules decides, and this change's entry from where it left main
ci/rules run tokens                                       # one rule's findings, on or not yet (ci/rules list names them)
mvn -o -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage -DtargetClasses=<classes> -DtargetTests=<tests> -DfailWhenNoMutations=false -Dpmd.skip=true -Dcpd.skip=true -Dspotless.check.skip=true   # mutation score, by hand
```

Never add `-q`: what to fix is logged as warnings (`PMD Failure: <class>:<line> Rule:<rule>
<message>`), only the summary as an error. Reports: `target/pmd.xml` (main),
`target/pmd-test/pmd.xml` (tests) and, at the root, `target/cpd/cpd.xml`. CPD reads the
reactor's sources, so a `-pl` build checks no duplication. The rule sets resolve through
`${maven.multiModuleProjectDirectory}`, the root that holds `.mvn/`. The `architecture` module's
tests run in `verify`, after every module has written this build's reports, so `mvn test` and
`mvn package` do not reach them. CI runs the gate in the Linux unit jobs (`ci/check unit`;
`sail-infra`'s adds `ci/exclusions`, `architecture` and CPD); the macOS jobs run the tests alone.
A job's install of its upstream modules skips PMD, except `sail-infra`'s Linux job, which needs
their reports and writes them without judging; each module's own job judges it.

## Gotchas

- The schema is one baseline (`SchemaManager.BASELINE`): a database of another version is
  refused, never walked. A schema change edits the baseline and is a fleet reinstall until
  the first post-v1 migration exists.
- `sail upgrade` runs the old binary; a fix to the upgrade path only takes effect one
  release later unless it rides `migrate`, which the new binary runs.
- Hook scripts drain stdin before anything else, or the hook writer gets `EPIPE`.
- `gh run view --log` truncates a job's log at about 5,000 lines. The whole log is
  `gh api repos/standardapplied/sail/actions/jobs/<jobId>/logs`.
