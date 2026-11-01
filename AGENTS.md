# AGENTS.md

Sail is the orchestration layer around coding agents: one Incus container per project, a
SQLite control plane synced between an org's main box and each engineer's node, specs as the
unit of work, and a review loop that runs a build, reviewers and fix agents. Java 25, three
Maven modules (`sail-core`, `sail-harness`, `sail-infra`), one GraalVM native binary. Design
and contracts: `ARCHITECTURE.md`. Build, gates, migrations, releases: `CONTRIBUTING.md`.

## Build and test

- `mvn clean verify` is the gate CI runs: every unit test, JaCoCo coverage gates, spotless.
  `mvn spotless:apply` formats. Build offline with `-o` when the repository is warm.
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
- Compatibility with an older release added anywhere but the places that already hold it; the
  fleet is upgraded to latest and the migration code deleted before v1, so a new tolerance is
  debt with a known end date.

A refactor spec is behaviour-preserving and its bar is the net deletion it names; a feature spec
names the mechanism its behaviour folds into. Before merge: a full verify logged to a file, an
independent review of the delta against the spec's bar, the fix-reverting mutants that prove the
tests bite, and the deletion count in the body.

## Code

- No inline comments. Javadoc only, and only for the non-obvious why. If code needs a
  comment to be understood, rename or extract instead.
- Modern Java: `var` for locals, records for values, sealed interfaces for alternatives,
  text blocks for multi-line strings, `List.of`/`Map.of` for constants, pattern matching,
  try-with-resources, `java.time`, `ProcessBuilder`. No wildcard imports (CI fails them), no
  inline fully qualified names.
- Shared seams are in `ai.singlr.sail.common` (`Ids`, `Strings`, `DateTimeUtils`); use them.
- Two runtime libraries, picocli and SnakeYAML Engine, and no more. Do not add a dependency.
- Every command is idempotent and offers `--dry-run` and `--json`. Every error says what
  happened and what to do.
- Untrusted input (names, paths, spec text, agent output) is validated once, at the edge,
  and reaches a shell only as an argument, never interpolated into script text.
- One rule, one place: ownership (`Ownership.ownerOf`), roles (`RoleRule`), one write rule
  per synced type (`ai.singlr.sail.authority`), one `Actor` bound at each door. A second
  derivation of any of these is a bug, not a convenience. The journal decides every write as
  it records the revision; a door never asks a write rule, and a rule never reads from the
  database the row it is deciding (it has `held`).
- Prefer deleting over adding. The smallest change in the right place; a duplicated seam is
  a finding to fix in the same change.

## Tests

- Test behaviour through the production seam, with exact assertions (`assertEquals` on the
  whole message, never `contains` where equality is possible). No `Thread.sleep`: latches,
  callbacks, fake clocks with a horizon that throws.
- No network and no external service in a unit test. A test that fails once is a bug to
  root-cause with evidence; "flaky" is not a diagnosis and a rerun is not a fix.
- Coverage: `ai.singlr.sail.api.*` is held to 100% line and method coverage; new classes
  anywhere should be fully covered in the same pull request. Gates never go down.
- Prompts, hook files and launch commands are pinned whole by golden tests; a change to them
  changes the golden text deliberately.
- A test that runs a container script for real (`flock`, `mv -T`, `/proc`) is
  `@EnabledOnOs(OS.LINUX)`: the release workflow runs the suite on macOS too.
- A change to a contract in `ARCHITECTURE.md` (the sync, loop and skill rows) changes the
  row and the tests it names in the same pull request.

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
- `CHANGELOG.md`: one entry per change, under the top unreleased version heading.
