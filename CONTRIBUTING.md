# Contributing to sail

Conventions for code and tests are in [AGENTS.md](AGENTS.md); the design and its contracts in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Build and verify

JDK 25 or newer and Maven 3.9 or newer.

```bash
mvn clean verify                                                   # what CI's unit job runs
mvn -o -Pintegration -Dsail.it.requireIncus=false clean verify     # plus the multi-node ITs; run before pushing
JAVA_HOME=/path/to/graalvm-jdk-25 mvn clean package -Pnative -DskipTests   # the native binary
```

The incus-backed integration tests need a real incus daemon and run in CI only.

## Continuous integration

One workflow, `.github/workflows/ci.yml`, with five checks that start together: the unit suite
and its gates, the dependency scan, the native fleet sync tests, the multi-node integration
tests and the incus-backed integration tests. Each test runs once per pull request; the unit
verdict lands in about eight minutes and the last check in about fifteen. A failed job uploads
its surefire and failsafe reports as `<job>-test-reports` and summarises the failed tests.
Details in ARCHITECTURE's "Continuous integration".

## Coverage policy

Gates are ratchets: each protects the current baseline and only moves up.

- `ai.singlr.sail.api.*` requires 100% line and method coverage, minus a documented exclude
  list of streaming and socket I/O classes.
- Every module has bundle-level line, method, branch and class gates at its verified baseline.
- New pure logic (domain services, validators, mappers, renderers, command metadata) targets
  100% line and method coverage in the pull request that adds it. Process boundaries are
  covered through fakes before their gates are raised.
- A gate is never lowered without the reason in the pull request.

## Database migration policy

The SQLite schema is versioned by `SchemaManager` in `sail-core`.

- **Append-only within a major version.** New migrations go after the current baseline and
  are never reordered, edited or removed once released. A wrong migration is fixed by
  appending another.
- **Every migration ships with a seeded-data test** that stages a database at the prior
  version, seeds representative rows, migrates and asserts the rows survived.
- **Baselining happens only at a major version with a published floor.** The floor's final
  schema and the new baseline must be structurally identical, proven by the `FloorSchema`
  fixture and the schema-diff test; anything below the floor is refused with an error naming
  the release that can still carry it forward. The current floor is schema v118 (sail 0.14.x).
- **No downgrade path.** Schema changes are forward-only.
- **One-shot data fix-ups are `DataMigration`s**, run exactly once and tracked by name in
  `data_migrations`, never schema migrations.

## Cutting a release

1. Main is green, including the integration checks.
2. Bump the version in the four poms (`pom.xml`, `sail-core`, `sail-harness`, `sail-infra`)
   in one commit, `chore: bump version to X.Y.Z`, and tag it `vX.Y.Z`.
3. Push main and the tag. The tag triggers `release.yml`: the test suite on Linux and macOS,
   native images for both, and a GitHub Release with checksums and keyless cosign bundles.
4. Once the release is published, move `.github/released-tag` to `vX.Y.Z` in a pull request
   of its own (`ci: the fleet upgrades from vX.Y.Z`). That file is the release the upgrade
   rehearsals (`NativeFleetIT`, `UpgradeE2EIT`) start from, so publishing never changes an
   open branch's checks.

Upgrade main first, then nodes. Sync refuses a peer below the fleet floor before any data moves
and names the remedy.
