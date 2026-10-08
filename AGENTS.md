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
- Schema migrations are append-only: never reorder, edit or remove an entry; ship each with
  a test that seeds the prior shape and migrates. One-shot fix-ups are `DataMigration`s,
  tracked by name.
- `sail upgrade` runs the old binary; a fix to the upgrade path only takes effect one
  release later unless it rides `migrate`.
- Hook scripts drain stdin before anything else, or the hook writer gets `EPIPE`.
- `gh run view --log` truncates a job's log at about 5,000 lines. The whole log is
  `gh api repos/standardapplied/sail/actions/jobs/<jobId>/logs`.
- `CHANGELOG.md`: one entry per change, under the top unreleased version heading.
