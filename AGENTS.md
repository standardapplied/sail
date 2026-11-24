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

- No inline comments. Javadoc only, and only for the non-obvious why. If code needs a
  comment to be understood, rename or extract instead.
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
  a finding to fix in the same change.

## Tests

- Test behaviour through the production seam, with exact assertions (`assertEquals` on the
  whole message, never `contains` where equality is possible). No `Thread.sleep`: latches,
  callbacks, fake clocks with a horizon that throws.
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
it on. The gate is Helios's (`standardapplied/helios`), with its versions and limits.

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
and the pending files only shrink. `RuleSetsTest` (architecture) holds the gated and pending sets
of each kind together to Helios's rules and limits, so a rule deleted from pending, or moved with a
new limit, fails.

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

### Rules of the gate

- **Fix the code, never the gate.** No limit is raised, and no rule moves back to a pending file.
- **No suppression in source.** Neither PMD's suppression comment nor a `@SuppressWarnings`
  naming a PMD rule; `git grep` for both stays empty.
- **The exclusions file only shrinks.** No class is ever added to it and no rule to an entry. A
  spec that works in a listed class splits it and deletes its entry. `ci/exclusions` (run by
  `sail-infra`'s Linux unit job) fails a change whose file names a `class=rule` pair its merge
  base's does not, or names a class on two lines. `ExclusionsShrinkTest` (architecture) reads
  every module's PMD reports, which the exclusions do not filter, and fails on an entry whose
  class no longer breaks a rule it lists; the change that cleared it deletes it.
- **Adding an ArchUnit rule.** A spec that establishes or consolidates a pattern adds one `@Test`
  to `ArchitectureRulesTest`, named for the rule, whose `because` names the one allowed way. A new
  module is added to `architecture/pom.xml` and to `ExclusionsShrinkTest`'s modules.

### Running it

```bash
mvn -o clean verify                                       # everything, as CI's Linux unit jobs run it together
mvn -o verify -DskipTests                                 # enforcer, PMD and CPD only
mvn -o pmd:aggregate-cpd@cpd pmd:aggregate-cpd-check@cpd-check   # duplication alone, in seconds
ci/check exclusions                                       # the exclusions against where they left origin/main
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

- Native image reflection: every record a `--json` verb prints must be listed in
  `sail-infra/src/main/resources/META-INF/native-image/ai.singlr/sail-cli/reflect-config.json`
  and stringified in `PtySelfTestCommand.jsonProbe()`; the release smoke test is the only
  place an unregistered record fails.
- The schema is one baseline (`SchemaManager.BASELINE`): a database of another version is
  refused, never walked. A schema change edits the baseline and is a fleet reinstall until
  the first post-v1 migration exists.
- `sail upgrade` runs the old binary; a fix to the upgrade path only takes effect one
  release later unless it rides `migrate`, which the new binary runs.
- Hook scripts drain stdin before anything else, or the hook writer gets `EPIPE`.
- `gh run view --log` truncates a job's log at about 5,000 lines. The whole log is
  `gh api repos/standardapplied/sail/actions/jobs/<jobId>/logs`.
- A change's entry is one file, `changes/<short-slug>.md` (`changes/README.md`), never an edit of `CHANGELOG.md`, which only the release writes (`ci/changelog`).
