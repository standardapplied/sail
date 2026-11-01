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

The SQLite schema is one baseline, `SchemaManager.BASELINE` in `sail-core`, stamped
`SchemaManager.CURRENT_VERSION`. A fresh database gets it in one transaction; a database
stamped with any other version is refused naming what to do, never migrated. A change to the
schema edits the baseline and its test (`SchemaManagerTest` lists every table), and until the
first post-v1 migration exists, a box carries no database across it: it is reinstalled.

## Cutting a release

1. Main is green, including the integration checks.
2. Bump the version in the four poms (`pom.xml`, `sail-core`, `sail-harness`, `sail-infra`)
   in one commit, `chore: bump version to X.Y.Z`, and tag it `vX.Y.Z`.
3. Push main and the tag. The tag triggers `release.yml`: the test suite on Linux and macOS,
   native images for both, and a GitHub Release with checksums and keyless cosign bundles.
Upgrade main first, then nodes. Sync refuses a peer on another protocol before any data moves
and names the remedy.
