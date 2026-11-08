# Contributing to sail

Conventions for code and tests are in [AGENTS.md](AGENTS.md); the design and its contracts in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Build and verify

JDK 25 or newer and Maven 3.9 or newer.

```bash
ci/check                                    # format, then the tests of the classes changed against origin/main
ci/check full                               # every unit test and gate, as CI's unit jobs run them together
ci/check unit sail-infra                    # one module, exactly as its CI job runs it
mvn -o -Pintegration -Dsail.it.requireIncus=false clean verify     # plus the multi-node ITs; run before pushing
JAVA_HOME=/path/to/graalvm-jdk-25 mvn clean package -Pnative -DskipTests   # the native binary
```

`ci/check` is the loop while working: it names the tests it picked (`Foo*Test` for a changed
`Foo`, a changed test itself) and runs offline. `ci/check full` before a pull request.

The incus-backed integration tests need a real incus daemon and run in CI only.

## Continuous integration

One workflow, `.github/workflows/ci.yml`: the unit suite per module on Linux (with its gates)
and macOS, the dependency scan, one native build, the native fleet sync tests, the multi-node
integration tests and the incus-backed integration tests in four shards. Each test runs once
per pull request. A failed job uploads its surefire and failsafe reports and summarises the
failed tests. Details in ARCHITECTURE's "Continuous integration".

## The GCP fleet

`ci/fleet` proves a release on real boxes: four Ubuntu 24.04 VMs in GCP, a main and three FDE
nodes (eng, a member of every project; client, a member scoped to `acme`; observer, a viewer
scoped to `acme` and `beta`), driven only by the README's commands and the ones sail prints.

```bash
ci/fleet run 0.59.0          # create, install, init, join, check, delete; non-zero on any failure
FLEET_KEEP=1 ci/fleet run 0.59.0   # leave a failed fleet up to look at it
ci/fleet ssh <run> client 'sudo sail sync status --json'
ci/fleet down                # delete every fleet VM
```

It needs `gcloud` signed in to a project (`FLEET_PROJECT`, default `sail-mast-511621`). Every
VM is labelled `purpose=sail-fleet` and deletes itself after six hours whatever happens to the
run. Each check is a line in `~/.sail-fleet/<run>/report.txt`, beside the log of what it ran.

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
3. Push main and the tag. The tag triggers `release.yml`, which tests nothing again: it waits
   for the commit's own CI run on main and refuses to publish unless it passed, while it builds
   the optimized native images for Linux and macOS, then publishes a GitHub Release with
   checksums and keyless cosign bundles.
Upgrade main first, then nodes. Sync refuses a peer on another protocol before any data moves
and names the remedy.
