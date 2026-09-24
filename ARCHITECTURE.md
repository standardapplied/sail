# Sail Architecture

> Status: living document, reconciled against the code at v0.13.126 (2026-07-01).
> It captures the positioning and the design decisions behind `sail`.

## What Sail is

Sail is to coding agents what Kubernetes is to containers: the orchestration layer for
agentic software development. It does not compete with Claude Code, Codex, or any other
coding agent. Those are the agents. Sail is everything around them: isolated environments,
spec-driven task management, cross-agent review, and multi-engineer coordination.

Sail's job is to coordinate agents. Writing code is the agent's job, and Sail does not try
to do it. Every feature is judged by whether it helps coordinate agents.

### Core value proposition

- **Isolated dev environments.** One Incus system container per project on bare metal,
  each a full Ubuntu 24.04 userspace with its own filesystem, network, and rootless Podman
  runtime. Isolation is hard: a runaway agent in one project cannot touch another.
- **Spec-driven task management.** Agent-native specs held in a SQLite control plane, with
  a status lifecycle, assignees, and dependencies. The model is flat and global, closer to
  Linear than to a project-nested tracker.
- **One shared source of truth across a team.** One main box holds the org's specs and
  project definitions. Every other engineer's box syncs them down and pushes its own work
  back, over pure SSH keys with conflict resolution. There is no GitHub in this loop and no
  central scheduler.
- **Cross-agent review pipelines.** When one agent writes the code, another can review it.
- **Remote operation.** Through the CLI (on the box or from a Mac thin client), through
  a desktop GUI client, and through future mobile clients.

### Glossary

- **FDE.** Forward Deployed Engineer: the human operating coding agents at a customer. Each
  FDE has their own bare-metal box.
- **Box or host.** A bare-metal server running Incus, Podman, and the Sail control plane.
  Every FDE's box is a full working environment.
- **Main.** The one box designated the org's source of truth for specs and project
  definitions. A **node** is a box pointed at a main that syncs to it. A **standalone** box
  has no sync peer. Standalone is the default, and sync is opt-in.
- **`sail-api`.** The per-box control-plane service: a loopback HTTP API and event stream
  that the CLI, in-container agents, and GUI clients talk to. It runs as a systemd unit.
- **Client.** A thin machine, typically a Mac, that drives a box remotely over SSH and runs
  no control plane of its own.
- **GUI clients.** Desktop clients for FDEs who prefer a GUI connect to a box's API for
  specs and review, and to its workspaces for execution.

## Topology: one main, many nodes

```
   Node box (FDE: mady)                        Main box (FDE: uday, org source of truth)
   ┌────────────────────────────┐             ┌──────────────────────────────────────────┐
   │ sail-api (127.0.0.1:7070)   │  sail sync  │ sail-api (127.0.0.1:7070)                  │
   │  SQLite control plane       │  over SSH   │  SQLite control plane (authoritative for   │
   │   specs / projects / files ◀┼─────────────┼▶  specs, projects, files, roster source)   │
   │   (local replica)           │ sail@main   │                                            │
   │  Incus containers (per      │  sync lane  │  Incus containers (per project)            │
   │   project) + Podman + agents│             │  + Podman + agents                         │
   └────────────────────────────┘             └──────────────────────────────────────────┘
         ▲                                                ▲
         │ SSH (gateway: sail@box, _gateway --fde)        │
   ┌─────┴───────────┐                              GUI client / browser, HTTP via a TLS reverse
   │ Mac thin client │                              proxy with passkeys, to sail-api
   │ sail (darwin)   │
   └─────────────────┘

  Specs, project definitions, and shared workspace files are reconciled between main and
  node. Compute is never scheduled across boxes. Each FDE runs agents only in their own
  containers. The star coordinates state, not execution.
```

A box's role is plain declared state in `host.yaml` (`SyncConfig`): `role: main`, or `role:
node` with `main: <ssh-target>`, or unset for standalone. `sail host sync --as-main` and
`sail host sync --main <target>` set it. Main never initiates. Nodes pull from and push to
main. Main failover is manual and out of scope for v1.

## Module layout

Three Maven modules, Java 25, one native binary.

| Module | Package roots | Responsibility |
|---|---|---|
| `sail-core` | `auth`, `common`, `config`, `engine`, `gen`, `ssh`, `store`, `sync`, `webauthn` | Domain model and config records, the Panama-SQLite store, the DB-sync engine and replicas, passkey and WebAuthn primitives, the SSH gateway tokenizer, pure file generators, and shared id, time, and string seams. No CLI, no HTTP, no native binary. Its only external dependency is SnakeYAML Engine. |
| `sail-harness` | `engine` | The in-container agent harness that runs inside project containers: agent sessions, the guardrail checker, reporting, the in-container `spec` CLI helper, the webhook notifier, and hook config writers. |
| `sail-infra` | `api`, `commands`, `engine` (plus `Main`, `Sail`, `SailVersion`) | The picocli CLI, the loopback HTTP control-plane API and its reactors, and the host-side provisioning engines (Incus, Podman, ZFS, and systemd drivers). It builds the GraalVM native binary `sail`. |

Coverage discipline: `ai.singlr.sail.api.*` is held to 100% line and method coverage under
JaCoCo, minus a documented exclude list of streaming and socket I/O classes that need fault
injection. The rest of the bundle has ratcheted floors set just under the actuals.

## Control plane: SQLite over Panama FFM

`sail server start` runs the control plane on the same bare metal that hosts the Incus
containers. Server and node are one process, and there is no separate node binary. It
normally runs as the `sail-api` systemd service rather than by hand.

- SQLite is the single source of truth, not git. Agents interact with specs through the CLI
  and API, never the filesystem.
- Access is Panama FFM directly over `libsqlite3.so.0`. JDK 25's finalized Foreign Function
  and Memory API calls the system SQLite directly: WAL mode, `foreign_keys=ON`, a 5 second
  busy timeout, and full-mutex serialized mode. There is no JDBC anywhere. `sqlite4j` was
  rejected for being single-threaded with no WAL. The wrapper (`store/Sqlite`) exposes only
  execute, query, and transaction, and is deliberately not a general JDBC driver.
- Specs are global entities, filterable by project, assignee, or status. The model is flat,
  closer to Linear than to a project-nested tracker.

### The store layer (`ai.singlr.sail.store`)

Sixteen `*Store` classes plus the sync and journal machinery, all over `Sqlite`:

| Store | Owns | Synced across boxes? |
|---|---|---|
| `SpecStore` | `specs` and their dependencies, repos, content, and attachments | Yes, entity `spec` |
| `ProjectStore` | `projects`, the `sail.yaml` descriptor blob plus attribution | Yes, entity `project` |
| `FileStore` | `project_files`, shared workspace files keyed by project and path | Yes, entity `file` |
| `FdeStore` | `fdes`, the human principals and roles | One-way, main to node roster pull |
| `FdeSshKeyStore` | `fde_ssh_keys`, SSH fingerprint to FDE | Local |
| `EventStore` | `events`, the audit stream persisted from the bus | Local |
| `ReviewStore` | `reviews`, `review_stages`, `review_findings` | Local |
| `SessionStore` | `agent_sessions`, agent lifecycle | Local |
| `AuthSessionStore` | `sessions`, login and gateway sessions | Local |
| `TokenStore` | `api_tokens`, SHA-256 hashed with optional expiry | Local |
| `WebauthnCredentialStore` | `webauthn_credentials`, passkeys | Local |
| `PendingChallengeStore` | `webauthn_challenges`, single-use ceremony challenges | Local |
| `EnrollmentTicketStore` | `enrollment_tickets`, one-time passkey enrollment | Local |

The sync and journal support classes live in the same package: `ChangeLog` (the append-only
revision journal that is the durability spine of sync), `Revisions` (content-addressed
revision ids), `ConflictDetector` (a pure three-way merge), `ConflictResolver` (implemented
by the three synced stores), `SyncConflicts` (parked conflicts), `SyncState` (per-peer
checkpoints), `ExpiredRowSweeper` (hourly housekeeping), and `PushOutcome` (the CAS result).

Migrations are two-layer and idempotent. `SchemaManager` owns the schema layer: the v1
baseline creates the current schema directly on a fresh database, a database at or past
the published 0.14 floor (schema v118, the version every released 0.14.x binary reaches)
rides the on-ramp — the post-floor migrations that never shipped in a 0.14.x release —
to the baseline, and a database below the floor is refused with the remedy — install
sail 0.14.x, run `sail upgrade`, retry — never a silent replay of deleted steps. A
separate `DataMigration` framework runs one-shot content fix-ups exactly once, tracked by
name. `MigrationRunner.applyAll` runs schema migrations then data migrations.
`MigrateCommand` additionally imports on-disk descriptors and shared files, scrubs
per-developer identity to placeholders, and seeds the bundled demo. Schema and registered
data migrations run on `sail migrate`, on `sail upgrade` (which spawns the new binary's
`migrate` so a release's new migrations actually execute), and on every daemon start, so a
failed upgrade-time migration self-heals on the next service start. Imports run only from
the explicit migrate lane. Host state (the `sail` user's forced commands and the systemd
units) names the installed binary, `/usr/local/bin/sail`, so only that binary's migrate
converges the host. Under a `SAIL_DATA_DIR` override, migrate is a rehearsal that migrates
and imports into the copy alone. Any other binary is refused before it opens the database:
it would migrate the database past what the installed binary can open.

**Migration policy.** Schema migrations are append-only within a major version: entries
are added after the baseline, never reordered, edited, or removed, and each one ships in
the PR that needs it together with a test that migrates a seeded database and asserts the
data survived. Collapsing the chain into a new baseline is allowed only at a major
version with a published floor: the floor's final schema and the new baseline must be
structurally equivalent (the `FloorSchema` fixture replays the released chain verbatim,
and the schema-diff test in `SchemaManagerTest` pins floor-plus-on-ramp against a fresh
baseline), the floor must be a version a released binary can actually reach, and
everything below the floor is refused with an actionable error naming the release that
can still carry it. Sync peers enforce the same floor in the wire handshake before any rows are
exchanged. There is no downgrade path: pre-1.0 explicitly has none, and post-1.0 policy
is forward-only with the documented floor mechanism. The pre-1.0 chain is recoverable
from git history only.

## The sync layer: one main, many nodes

This is the heart of multi-FDE coordination. Sync is opt-in, and a standalone box does
nothing here.

### What syncs, and how

`sail sync` runs one bidirectional reconciliation per registered entity type, in the
registry's dependency order (a spec before its runs, a room before its messages), plus one
one-way roster pull:

| Entity | Direction | Notes |
|---|---|---|
| specs | Bidirectional, field-level three-way merge | The team board |
| rooms and messages | Bidirectional; messages are immutable once posted | The conversation |
| project definitions | Bidirectional | The `sail.yaml` catalog |
| shared workspace files | Bidirectional | The `files/` bundle, opaque content |
| runs and reviews | Bidirectional; a run is pushed only by the box that executes it | Execution provenance |
| FDE roster | One-way, main-authoritative pull | Handle, name, email, role, status, and never keys or tokens |

Content — a spec's body and plan, a shared file's bytes — is not in any row or snapshot. A
synced store names its content fields (`SyncedStore.contentFields`), a snapshot carries a
SHA-256 in their place, and the bytes live once in the `BlobStore`: content-defined chunks
(`FastCdc`, 64 KiB–1 MiB) under a manifest per blob, every chunk hashed before it is written
and every blob assembled only when each chunk is present and the whole hashes right. The
materialized text stays where reads find it (`spec_content`, a file's row carries hash, size,
mode and kind), so no read path opens a blob to answer a listing. History therefore grows by a
hash per revision, an edit to a large file moves only the chunks it touched, two projects
sharing a file store it once, and nothing on any path holds a whole file: ingest, sync,
materialization and download all stream. `sail sync gc` compacts history and frees what no
live row, retained history row or open conflict references; a session or an ingest holds a shared lease
(`BlobRetention`, one file lock beside the database) that GC's exclusive lease waits for, so
a chunk that just arrived is never collected under a round.

One `StoreReplica` adapter implements both `LocalReplica` and `MainReplica` over any synced
store, so the same box acts as the node when it syncs up and as the authority when another
node syncs to it. Every synced store keeps a `change_log` of full snapshots and, beside it, a
`change_heads` row per entity naming its latest entry, so the reads the protocol makes are
O(what it asks for), never O(history).

### The wire: sync protocol 4

A session opens with `hello` (protocol, build, fleet floor, box id) and is `welcome`d or
`refuse`d once; floors compare as versions. The box id names the node in main's log; who the
node is stays the authenticated SSH principal, which every commit is attributed to. The node
then asks `heads` for main's high-water per type and, for each
type whose tip moved past its checkpoint, `pull`s main's change log since that checkpoint one
bounded `page` at a time — a seed from any history size costs the same per page as an idle
round. Each page is its own engine round: every adoption is one atomic store operation, no
transaction spans the wire, and the checkpoint (kept per peer and per type) advances only after
the page and only to what the node has actually seen, so a round that dies mid-page re-pulls it
and re-adopts nothing. After the
pages the node asks `need` for main's current rows of whatever it changed itself, so the
engine sees main's real state for a local edit, and `push`es its offers in batches. Every
message is one JSON line under a single 16 MiB frame bound (`SyncWire.MAX_FRAME`). Content
crosses as bytes announced by a line: the wire is a byte stream with one reader (`readLine`,
strict UTF-8; `readBytes(n)`), and only `chunk { hash, size }` is followed by raw bytes. Before
a page or a `need` answer reaches the engine the node brings each blob it lacks home in turn —
`fetch` → `manifest`, `fetch_chunks` in frame-bounded batches → `chunk`s, assemble — so a page's
content costs one manifest of memory however many files it names, a chunk is stored the moment
it verifies, and a round that dies mid-transfer resumes at the chunk it lost. Before each push
batch the node `announce`s the blob hashes the batch references and main answers `lack`; the
node sends those manifests, main validates every one against its own `limits.file_max` and
answers `lack` again with the chunks it does not hold; the node sends exactly those and main
assembles. `lack` has one meaning in both replies — of what you just named, I hold none of these
— and a commit naming a hash main does not hold is refused. A read-only principal is refused at
`announce`, before main stores a byte. Every protocol-4 message names an `op` and no earlier
protocol did, so a main that answers `hello` with a message naming none is on an older protocol
and the node fails naming the remedy: upgrade main. Anything on the channel that is not a
message at all is reported as that, never blamed on main's version.

Passkeys stay box-local by design: identity crosses boxes via the roster pull, but a
WebAuthn credential is an RP-scoped secret bound to one box's origin and never leaves it.
Each box enrolls its own passkeys (`sail fde enroll`), and each box lists and revokes them
on its own (`sail fde passkey list|rm`).

### The engine

`SyncEngine.reconcile(local, main)` is entity-agnostic, order-independent, idempotent, and
stateless. All state lives in the replicas. For each entity it feeds three snapshots into
the pure `ConflictDetector`: the merge base (the revision the local row descended from on
main), the local current, and the remote current on main. The detector classifies at field
granularity:

- **Converged.** Both sides already agree, even if they reached the state independently.
  The engine links the shared revision and moves on.
- **TakeRemote or KeepLocal.** Only one side moved. The engine fast-forwards, including
  deletes.
- **Merged.** Different fields changed on each side. The engine auto-merges.
- **Conflict.** The same field changed to different values, or a delete raced an edit. The
  local row is left untouched and the conflict is parked.

Pushes are compare-and-set. Main mints the revision and rejects a stale expected revision,
so the engine re-reconciles against main's fresh state under a bounded retry rather than
overwriting silently. Revisions are content-addressed as `<counter>-<shortHash>`, so two
boxes that independently reach the same content mint the same revision and converge with no
conflict. That property is what makes the migration's baseline revisions and identity scrub safe
across a mixed-version fleet. Reserved snapshot keys prefixed with `_` (such as `_actor`,
which carries author attribution) ride along but are excluded from conflict detection, so
attribution propagates without ever causing a false conflict.

Parked conflicts live in the `sync_conflicts` table, one open conflict per entity, and are
resolved with `sail conflicts`. It takes `--mine` or `--theirs`, and `--merge` for specs. A
file's content and a project's definition are single opaque blobs, so they take mine or
theirs only. Resolving rebases the row onto main's version and writes the choice, so a
follow-up sync converges and the conflict cannot re-raise. Every version stays in the change
log, so no choice loses work.

A conflict is decided on what the box holds now. Every strategy writes a recorded snapshot, so
a resolve is refused (`409` over the API) when the live row no longer matches the conflict's
recorded local side, ignoring latest-wins fields such as a run's heartbeat; `sail sync`
re-records every parked conflict, after which the same resolve applies. Main's side needs no
such guard: the recorded remote only becomes the merge base, and the next round runs the
three-way against main's current row, so a disjoint change on main merges and the same field
parks again. Ids are unique only within a type and a spec's room carries the spec's id, so a
conflict is addressed by type and id: `--type` on the CLI, `?type=` over the API. An id parked
under several types is refused naming them (`400`) rather than guessed.

### Archive, delete, prune

Three verbs remove work, and each makes a different promise.

- **Archive** takes a spec off the board and keeps everything. It is a status. `archived_at` and `cancelled_at` record when a spec entered those statuses, which is the time retention ages on.
- **Delete** writes a tombstone that keeps the entity's last state. The spec's runs, reviews and room stay, so a restore from any retained revision, including the tombstone, brings the spec back with the identity room it minted.
- **Prune** erases the entity everywhere, for good. A spec is pruned from archived, cancelled or deleted, never from work still on the board, and never while a run of it is unfinished.

An erasure is a kind of change-log entry, next to revision and tombstone. One transaction removes the live row, its open conflicts, the box-local rows keyed by it (events, Slack threads, run credentials, container leases) and every history entry, and records one erasure row: an empty snapshot plus a fresh rev naming who pruned and when. The head then points at that row, and an erasure is terminal: the journal (`ChangeLog.append`) refuses any later write to the id, so a pruned spec id or project name is never used again, and every node pages the erasure as the entity's last word however long it was offline.

What a prune takes with it is declared once, in `Erasure.LINKS`:

- a spec takes its runs and its reviews;
- a room takes its messages and runs, and a message takes its replies;
- a project takes its specs, rooms, files and runs;
- the room a spec minted goes once that spec is erased or going and no spec left behind, live or restorable, still converses in it, so a room other specs were born into outlives the spec that minted it.

The journal reads the same links: a revision whose state names an erased owner is refused, whoever writes it (a local create, a file import, main's commit of a push). Rows that belong to a single entity go with it through the database's own cascades: a spec's content and dependencies, a run's principals and delivery ledger, a review's stages and findings. There are deliberately no foreign keys between synced entities. A tombstone deletes its row, so such a key would cascade a delete through dependents without a log entry. It would also make adopting a child depend on its parent's page and on the parent's parked conflicts.

Main is the only author of erasures. `SpecPruner.prune` serves the CLI, the API, Mast and `project destroy --purge`: on main it erases in bounded transactions, each selecting, authorizing and reading what belongs to its targets inside its own write transaction, and then collects content. A node discards on the spot what main never acknowledged (nothing to ask, and asking would publish it), and records the rest in `erase_requests`. The first type of its next session offers every pending request as an `erase` offer before main's tips are read, so the erasure rows main writes for them and for everything that belongs to them page in that same round. A request whose entity has a change main has not taken yet (a spec archived on the node a moment before its prune) waits for that type's push, since main decides on its own copy; the types after it read main's tips afresh. Main decides each offer against its own copy (`EraseAuthority`), erases, and answers the erasure's rev.

A node applies an erasure the moment it arrives, whether in a page, a `need` answer or the refresh after a stale push. It does this outside the engine, so no conflict is ever parked against one, and it counts as pulled, so the board hears of it.

- An erasure the node already holds changes nothing.
- An entity the node never held gets only the erasure row.
- The node removes with the entity only what main never acknowledged, and replies, which cannot outlive their message. Everything main held gets an erasure row of its own, decided on main's copy, so a link that differs between the boxes (a spec main moved to another project) never takes what main kept.
- Main answers every commit to an erased id as stale against its erasure, so a stale push cannot bring it back.

A dry run is the erasure itself, rehearsed in a transaction that is rolled back (`Sqlite.rehearse`), so it reports what the real run does on this box's copy; on a node, main decides the apply on its own.

Retention is opt-in. A `retention` block in main's `host.yaml` sets the ages: archived specs, messages, and finished runs. With the block, main's daily `RetentionSweeper` erases by that policy through `SpecPruner.retain`, in bounded transactions; it never takes a spec with an unfinished run, an agent's question still awaiting its answer, or a message a younger reply main holds still needs. A reply written offline to a message retention then retires cannot outlive it and goes with it when the node adopts the erasure. Without the block the sweeper only compacts and collects. Nodes never evaluate retention.

History compaction never crosses the wire. It is a compiled constant (`ChangeLog.HISTORY_REVISIONS`), so no two boxes can disagree. Every box keeps each entity's newest 20 entries, its synced base (the merge base of an open conflict included), and every tombstone and erasure. A node compacts what each round touched; main compacts daily and from `sail sync gc`. Protocol 4 pages the latest entry per entity, so a node whose checkpoint predates main's compaction still converges. Compaction runs under the exclusive content lease and is refused inside a transaction. Once a revision's row is gone its blob is unreferenced, and the collection that follows frees it.

A purge erases a project's rows everywhere, not other boxes' containers: their project directories and materialized files stay on disk with the container, and neither `sail migrate`'s import nor the materializer touches the files of a pruned project again.

### The transport: pure SSH keys, no network enroll

A node reaches main over a single SSH subprocess, `ssh sail@main sail _sync`, using that
process's stdio as a newline-framed JSON RPC pipe. Auth is pure SSH keys, with
`PasswordAuthentication=no` and `IdentitiesOnly=yes` pinning the sail-managed key that `sail
join` generated, so a missing key fails fast. On main, that key sits on the locked `sail`
user's `authorized_keys` as a forced command,
`command="…/sail _gateway --fde <handle>",restrict …`. The gateway resolves the FDE and
admits the `_sync` session. The write gate lives next to the write: the `_sync` server
refuses pushes from read-only viewer roles while still letting them pull.

### Identity isolation in synced projects

A project definition mixes shared infrastructure with two fields that are inherently
per-developer: the git identity that commits are authored with, and the SSH key authorized
into that box's containers. Neither may ride onto a teammate's box.

The synced definition therefore carries placeholders, not concrete values.
`PersonalFields.redact` rewrites `git.name` and `git.email` to `${GIT_NAME}` and
`${GIT_EMAIL}`, and `ssh.authorized_keys` to `[${SSH_PUBLIC_KEY}]`, at the single
catalog-write seam (`ProjectStore.upsert`). Redaction is pure, idempotent, and
deterministic, so every box agrees on an identity-free definition and the engine converges.

Each box resolves the placeholders locally, once, at provision time
(`ProjectDefinitions.resolveForProvisioning` calling `LocalIdentity`). Git fields come from
the box's local `git config`. `${SSH_PUBLIC_KEY}` resolves to the box's registered
workstation key at `~/.sail/workstation_key.pub`, which is the laptop key the box owner
connects to containers with. This is a different key from the machine sync key that a node
presents to main. Because each box has a single owner, one workstation key authorizes that
owner into every container the box provisions. It is registered once with `sail host config
set ssh-public-key`, which auto-detects it from the box's `authorized_keys` when no value is
given. A missing or invalid value fails loud with the fixing command rather than
provisioning a container no laptop can reach. The result is that each engineer's containers
commit as them and trust only their own key.

### Live resource resync

A project's `resources` (cpu, memory, disk) sync like any other field: bump them on any box
and they propagate. After a sync that pulled or merged a project,
`ProjectResourceReconciler` resizes that project's running container in place to the new
limits, with no recreate and no restart, because a background sync must never disrupt a live
container. It is best-effort and never fatal. An unprivileged sync with no incus access is a
quiet no-op, and a disk shrink the backend refuses (below used space on ZFS, advisory on the
`dir` backend) is reported and skipped.

### No GitHub in the project loop

Project descriptors are distributed entirely by DB sync. There is no `project pull` or
`project push`, and the demo project is bundled in the binary and seeded into the catalog
idempotently, never resurrected once purged. The remaining git-token plumbing exists only to
clone a project's source repos into its container at provision time, not to move descriptors.

## Runtime modes: host vs client

`Main` chooses a mode at startup through `RuntimeMode.detect()`:

- **Host mode.** `~/.sail/host.yaml` exists, which is the default. Commands execute locally:
  lifecycle commands drive `incus` and `podman`, and API commands hit the local `sail-api`.
- **Client mode.** Only `~/.sail/config.yaml` exists. Commands are forwarded to a box over
  SSH by `RemoteCommandRunner`, in lanes:
  - Local commands (`--version`, `upgrade`, `init`, `client`, `login`) run on the client.
  - Host-only commands (`host …`) error with guidance to SSH in.
  - FDE-gateway commands (`spec`, `agent`, `events`, `fde`) target `sail@host`. The
    engineer's SSH key hits the forced command `sail _gateway --fde <handle>`, which
    resolves their FDE, mints a short-lived session token, and re-executes the command, so
    the loopback API sees who is acting and `Authorizer` enforces their role.
  - Everything else (project lifecycle, interactive `shell` and `exec`) is forwarded as the
    plain SSH host, because it needs host privileges the `sail` user must never have.

This is why a loopback-only API is not a limitation for a Mac client: gateway commands run
on the host, where the API is reachable at `127.0.0.1:7070`.

## Onboarding

There is one convergent, idempotent, root-aware command per box, `sail init`:

- `sudo sail init --as-main` makes this box the org's source of truth.
- `sudo sail init --main <target>` joins an existing main as a node.

It figures out what is missing and does only that, decided by a pure, unit-tested
`InitPlan`: provision the host if needed, install and start `sail-api` (a system service
under root, or a per-user service for the `sudo` user otherwise), and take on identity. On
main that means publishing the SSH identity and declaring `--as-main`. On a node it means
`join`, which generates the box's sync key and prints the `sail fde add … --key …` line for
the operator to run on main. The granular commands it orchestrates (`host init`, `host
service install`, `host ssh-identity`, `host sync`, `join`) all remain usable on their own.

A thin client, a Mac that drives a box but runs no control plane, is set up separately with
`sail client <host>`, which writes `~/.sail/config.yaml` pointing at the box by IP,
hostname, or `~/.ssh/config` alias. The release pipeline builds `sail-darwin-arm64` alongside
`sail-linux-amd64`. `install.sh` detects the platform, verifies the magic bytes and the
SHA-256, and strips the Gatekeeper quarantine flag.

## Provisioning and execution engine

`sail` is a thin orchestrator. It drives `incus`, `podman`, `systemd`, and `ssh` through
`ShellExecutor`, and every command maps to calls the operator could run by hand.

**Container provisioning** (`ProjectProvisioner`) is an idempotent, resumable pipeline of
roughly twenty steps, and a `ProvisionTracker` lets a failed run pick up where it stopped.
It launches the Incus container from the configured image, bind-mounts the host `sail-api`
Unix socket in, installs the in-container `spec` CLI and event hooks, applies the disk quota
(advisory on `dir`, hard via `refquota` on `zfs`) and the cpu and memory limits (with
`security.nesting` and unconfined AppArmor so rootless Podman runs inside), installs
packages, the SSH user and its authorized key, Podman (with linger and a restart service),
Testcontainers wiring, and the JDK, Node, and Maven runtimes, configures the git identity
and clones the source repos, pushes the shared `files/` bundle, starts the Podman services
under `--restart=always`, installs the agent CLIs, and generates agent context.
`ProjectApplier` is the live-delta path for `sail project apply` on an already-running
container. CPU and memory changes that need a restart are applied by the dedicated resources
path, not silently here.

**The `sail-api` service** (`SystemdServiceInstaller`) is the per-box control plane: a `sail
server start` systemd unit, in system scope under root (`/etc/systemd/system`) or in user
scope per-user (`systemctl --user` with linger). It opens the control-plane SQLite database,
runs registered migrations, ensures an admin token, and serves the REST API and the SSE
event stream (`/v1/events/stream`) on loopback, the passkey endpoints when configured, and
a Unix-socket listener so project containers publish events and drive `spec` over the
bind-mounted socket with no TCP. Requests on that socket authenticate with the per-run
credential minted at dispatch (`SAIL_RUN_CREDENTIAL`), which resolves to the run's agent
principal; a missing or revoked credential is refused with 401. Every box runs it, and it
is what lets an engineer dispatch agents locally.

Event history has two read shapes. `GET /v1/events/recent?limit=` is the unscoped window,
and `GET /v1/events?spec=<id>[&since=<eventId>][&limit=]` serves one spec's durable history
from the `EventStore`: RECORD-class events only (telemetry is pruned by retention and never
served from history), oldest first, with `since` an exclusive monotonic event-id cursor for
gap-fill after an SSE reconnect. The record is **node-local** — there is no event replica,
so events recorded on another FDE's box never land in this box's audit store (same
provenance posture as agent logs). The route serves the local record completely; the
fleet-consistent room content is and remains the durable synced stores — messages, reviews,
runs.

**Dispatch and agent execution** (`DispatchCommand` plus `sail-harness`): autonomous
dispatch reads the next ready spec from the database, honoring `depends_on` and assignee,
marks it `in_progress`, snapshots the container for rollback, creates the work branch, and
launches the agent headless under `systemd-run --user` with `SAIL_SPEC_ID`, `SAIL_AGENT`,
`SAIL_RUN_ID`, and the run's `SAIL_RUN_CREDENTIAL` in its environment so in-container hooks
correlate events back to the spec and authenticate as the run's principal. The agent's
output streams to the log live (Claude Code via `--output-format stream-json`), which also
feeds the watcher's liveness signal. Agents inside the container manage specs through a
dependency-free `spec` shell script that talks to `sail-api` over the bind-mounted Unix
socket, needing no `sail` binary or files — it presents the run credential on every
request, so every write is attributed to the run's minted principal. `sail spec dispatch --restart` re-runs a
spec whose status is no longer pending, resetting it to pending and recording the restart as
a lifecycle event.

**Agent context: sail owns the home layer, the engineer owns the workspace**
(`AgentContextGenerator`): both Claude Code and Codex natively merge a home-level context
file with project-level files, so sail writes its layer to the home namespace
(`~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`) and overwrites it every run, while the engineer
owns `~/workspace/CLAUDE.md` and `~/workspace/AGENTS.md` outright. Sail never creates or
touches those, and being closer to the code they override sail's layer on conflict. There is
no `@import` pointer and no `--force`, and sail only ever overwrites its own home namespace.
The body encodes project orientation (tech stack, conventions, runtimes, services),
language-agnostic engineering principles, a short security stance (deeper security review
belongs in a review-pipeline stage), the spec-driven workflow (DB-authoritative, with no
`specs/` directory to edit), and the autonomous-operation protocol. Methodology and spec
skills land under the home skills namespace, and sail ships no hardcoded language standards.
A project may supply its own through `agent_context.rules`, a map of name to `{paths, body}`.
Sail materializes each into the agent's native load-only-when-relevant channel: a
path-scoped Claude rule (`~/.claude/rules/<name>.md` with a `paths:` glob) and a
description-loaded Codex skill. Java standards then reach the agent only while it edits Java,
never bloating the always-loaded context. The bodies are project-supplied, and sail ships
none.

**Guardrails and rollback:** `agent.guardrails` sets a `max_duration`, a `max_idle` stall
window, and an action (`snapshot-and-stop`, `stop`, or `notify`). An event-driven watcher
(`sail agent watch`, auto-started by dispatch) merges the wall-clock deadline with the stall
deadline and with agent-exit events off the SSE stream. Progress events (tool calls and log
chunks) push the stall deadline out, so a working agent is never killed and a silent one is.
On a trip it snapshots or kills the agent and fires notifications. Rollback uses Incus
snapshots, which are instant on `zfs` and full copies on `dir`, and the pre-dispatch
snapshot is the restore point.

The watcher runs detached, as the systemd transient unit `sail-watch-<runId>` — the same
mechanism that runs the agent — so it survives Ctrl-C on the dispatch stream, the SSH
session ending, and daemon restarts. Every dispatched run has its own identity end to end:
the agent runs as `sail-agent-<runId>` with its pid, session, task, and log files under
`~/.sail/runs/<runId>/`, the run records the unit it was launched with, and every later
consumer (stop, probe, reconciler, watcher) addresses that recorded unit — which is what
lets dispatches on disjoint repo sets share one container concurrently, refused only when
their repo sets overlap. The overlap gate is an atomic reservation: one `BEGIN IMMEDIATE`
SQLite transaction checks every running local run and inserts the new run with its reserved
repos, so concurrent dispatches — even a CLI and the server in separate processes — can never
both claim the same repo, and a reservation failure aborts the dispatch before any spec is
claimed or branch checked out. An ad-hoc `sail agent run --task` session is a run like any
other: it mints a `role='adhoc'` row with no spec and an empty repo set — the
whole-container reservation — through the same transaction, so ad-hoc and dispatched agents
exclude each other atomically and are stopped, listed, and reconciled by the same run-scoped
machinery. Coverage is probed rather than bookkept, per run, and the periodic
re-armer — whose coverage probe is process-level, seeing units in
any user's systemd scope and plain fallback processes alike — relaunches unit-or-nothing for
any running agent nothing covers, resuming the original deadline rather than granting a
fresh budget. The missed-stop sweep still replays the stop of any agent that manages to
finish unobserved. Units launch with `Type=exec` so exec-phase failures fail the spawn
loudly, and the `SAIL_*` environment is forwarded explicitly since transient units start
from systemd's clean environment. Where no systemd scope is available (busless test
environments) the spawn falls back to a plain detached process, loudly marked degraded.
Every lane — CLI dispatch and API dispatch — spawns through the one `WatcherSpawner`, so
their survival properties cannot diverge.

**Multi-agent review loop:** review is agent-agnostic across claude-code and codex. When the
coder's dispatch stops cleanly, the spec moves to `review` and a secondary reviewer runs at
spec completion (falling back to self-review when only one agent is installed; a per-spec
`agent` overrides the project default). The reviewer is read-only and emits findings; if a
stage's gate fails, a fix agent addresses the open findings on the same branch and the
reviewer runs again, bounded by `max_iterations`, after which the spec `escalates` and parks
in `review` for a human.

Findings have identity across iterations. The re-review receives the previous review's open
findings and must return a verdict envelope — `{"verdicts": [...], "findings": [...]}`, the
only response shape the parser admits — ruling each carried finding `fixed`, `still_open`, or
`disputed`, with evidence required for `fixed` and `disputed`. Fail-closed: an unmentioned
carried finding defaults to `still_open` and re-attaches to the new review as a fresh row
chained through `carried_from`, so the gate keeps failing on a high the reviewer stopped
mentioning, and a passed review resolves nothing implicitly. The fix agent has a dispute lane:
it argues a wrong finding in the spec room instead of coding around it, the re-review rules on
the argument, and a `disputed` finding is excluded from the gate but listed in the room verdict
for the human. A gate-blocking finding still open after `max_finding_age` fix iterations
(default 2) escalates by name — a convergence measure that catches a stuck loop long before
`max_iterations` burns down.

A gate pass is not completion: the PR is still open on whatever forge hosts the repo, so the
spec parks in `awaiting_merge`. Sail never talks to the forge — the FDE reviews and merges
the PR there, then closes the loop with `sail spec update <id> --status done`. Deciding about
escalated findings (parks in `review`) and merging a passed PR (parks in `awaiting_merge`)
are different human acts and get distinct states. Because only `done` satisfies
`depends_on`, a dependent spec never dispatches against a main that lacks its parent's
unmerged work.

Reviewer and fix agents run on the same agent command and log handling as dispatch, but not
its process wrapper. Dispatch is fire-and-forget (it launches a detached `systemd-run --user`
unit and an external `sail agent watch` monitors it); a review blocks the pipeline until it
has findings, so `ContainerReviewAgentRunner` runs it as a bounded foreground `shell.exec`
(30-minute per-invocation timeout) that needs no systemd user manager or D-Bus session, so it
works in any container. Each review owns its files under `~/.sail/runs/<reviewId>/`: the
pipeline reviews concurrently completed specs on concurrent virtual threads, so a shared
prompt or log would cross-contaminate them. The output streams to the review's own
`review.log` (appended, so one attempt's reviewer-and-fix negotiation lands in one
live-followable log), findings are parsed from the bytes that run appended (read by offset so
a plain agent's re-review is never fed a prior iteration's findings) via `StreamJsonResult`. The reviewer runs
clean (empty `SAIL_SPEC_ID`, no hooks) so its own completion never re-enters the pipeline.
Follow it live with `sail agent log <project> --review`.

The run aggregate records that negotiation as a `role=review` run using the review UUID. A
stage UUID would be less truthful: reviewer and fix invocations deliberately share the review's
file set, while a stage-scoped run ID would resolve to a directory they never write. The run is
created before the first agent process starts, completes after the review's foreground work ends,
and syncs like a build run. Retention always protects running run directories, including review
runs that fall outside the normal keep window during concurrent work.

**Recovery without losing work.** The git branch is the durable record: every coding agent
(build and fix) commits before it stops, and neither a guardrail stop nor an escalation ever
discards it. So an FDE always recovers by returning to the branch. When a spec is stuck: a
guardrail-killed or failed dispatch leaves the work committed, so `sail spec dispatch
--restart` resumes on the branch; an escalated review parks in `review` with its findings (in
the review store), its negotiation (`review.log`), and every fix commit intact, so the FDE
reads it with `sail agent review <project>` plus `sail agent log <project> --review`, then
resolves with `sail spec update <id> --status done` (accept the work as-is) or `--status
pending` (send it back to be re-dispatched). Nothing is deleted along the way.

**Events:** an in-process `EventBus` (lock-light, with bounded per-subscriber queues that
are lossy by design so publishers never block) fans out to the SSE stream and to startup
reactors: audit persistence, the webhook reactor, and the spec lifecycle reactor, which
advances a spec from `in_progress` to `review` when its agent session ends. A
`board_updated` event after a sync that changed the board surfaces an updates-available
banner in the CLI and in GUI clients.

## Security model

**One ingress by design.** The only network surface a box exposes is sshd, which the
operator runs anyway. `sail-api` binds `127.0.0.1` and is never network-reachable by
default. There are exactly two identity doors, and both land on the same `fdes` row, role,
and `Authorizer`:

1. **Terminal: SSH key to FDE.** Each registered key is pinned in the `sail` user's
   `authorized_keys` to `command="…/sail _gateway --fde <handle>",restrict`, with no shell
   and no forwarding. Command classification is default-deny: only `spec`, `agent`, and
   `events` reach the loopback API, `fde` is admin-gated at the gateway (with two
   self-service carve-outs: any active FDE may `fde passkey list|rm` and `fde enroll` its
   own pinned handle, which is what lets `sail enroll` self-mint an enrollment ticket),
   `_sync` is admitted, and everything else is refused. A short-lived session is minted per invocation. `sail fde
   add <handle> --key "<pubkey>"` is the whole enrollment, and removing the key revokes SSH
   and API access in one step.
2. **Web, opt-in and off by default: passkeys and WebAuthn.** For GUI and browser clients
   only. Until the operator configures the `webauthn` block (RP id and origins) the
   endpoints answer `503`. Login start and finish are unauthenticated by design.
   Registration needs an enrollment ticket or an authenticated admin.

**Three SSH key roles.** A box's sync key (`~/.sail/sync_ed25519`) is what a node presents to
main for the sync lane. An FDE's gateway key (stored in `fde_ssh_keys` and pinned in the
`sail` user's `authorized_keys`) authorizes CLI and API access through the forced-command
gateway. A box's workstation key (`~/.sail/workstation_key.pub`) is authorized into that
box's project containers so its owner can SSH from their laptop into a container. Keeping
these roles distinct is what lets the synced catalog stay identity-free.

- **Roles and Authorizer.** The roles are `admin`, `member`, and `viewer`, enforced at the
  API boundary: GET maps to READ, mutating verbs to WRITE, and sensitive routes to ADMIN. An
  unknown or blank role fails safe to viewer. Attribution (`created_by`, `updated_by`,
  `decided_by`) is stamped server-side from the validated token's FDE, never from client
  input.
- **Agent principals.** Every run — dispatch, ad-hoc, review — mints an agent principal
  inside its reservation transaction: a handle (`claude/a1b2c3`) plus the FDE it acts for,
  stamped on the run row (they replicate with the run), and an opaque run credential hashed
  at rest in the local-only `run_credentials` table. The credential authenticates the
  in-container lane; every run finisher revokes it and the expired-row sweep collects
  stragglers. Agent principals are member-tier on the spec/event surface — never admin —
  and the dispatch and stop routes refuse the agent lane outright. Agent-ness is a
  relationship, not a type: the owner link is attribution and policy tiering, never a
  separate authorization system.
- **Tokens.** Host-local bearer tokens are SHA-256 hashed at rest, returned in plaintext
  once, and stored `0600` under a `0700` `~/.sail`. They carry a 90-day default expiry, where
  a null TTL means never-expires and is kept only for break-glass and bootstrap, and an
  hourly `ExpiredRowSweeper` prunes expired tokens, sessions, challenges, and tickets. The
  token resolution order (`--token`, then `--token-file`, then `SAIL_TOKEN`, then
  `SAIL_TOKEN_FILE`, then config) lets a token stay off the process list.
- **Rate limiting.** A per-credential token-bucket limiter, defaulting to 600 per minute,
  sits after auth in the API router and returns `429` when exceeded.
- **Transport and TLS are out of scope by design.** `sail-api` serves plain HTTP on loopback
  and never terminates TLS or issues certificates. For network or browser access (a GUI client,
  passkeys) the operator fronts it with a TLS-terminating reverse proxy such as Caddy,
  Traefik, or nginx, which owns the public hostname and the cert lifecycle. For passkeys, the
  secure context and the RP id are the browser's view of the proxy's `https://` origin.
  Binding off loopback requires an explicit `sail server start --host 0.0.0.0` and prints a
  plaintext-HTTP warning.
- **Input hardening.** Repo and git URLs are validated and rejected when they start with `-`,
  so `git clone` cannot read them as options, with `--` guards on git invocations. Paths and
  refs reject `..` traversal. FDE handles are a fail-closed boundary for the `authorized_keys`
  forced command. Webhook URLs are SSRF-checked at config-parse time and re-resolved at send
  time as a DNS-rebinding defense, range-checking every resolved A and AAAA record including
  obfuscated and IPv4-mapped-IPv6 forms.
- **Untrusted by design.** Spec markdown and agent output are untrusted prompt input. The
  reviewer and handoff flows never treat them as authoritative instructions.
- **Releases are signed.** The release workflow builds the GraalVM native images, runs the
  test suite, and publishes `sail-linux-amd64` and `sail-darwin-arm64` (plus a `sail` alias),
  each with a `.sha256` and a keyless-cosign `.cosign.bundle` (Sigstore via GitHub OIDC).
  Every GitHub Action is pinned to a full commit SHA.

See `SECURITY_AUDIT.md` for the full checklist and the accepted risks.

## Known gaps and evolution

These are the deliberate edges between today's tool and the multi-FDE platform that must
support GUI and direct-API clients:

1. **Project lifecycle is host-privileged, not API-backed.** `project up` and `project
   create` drive `incus` directly, so they cannot ride the FDE gateway, and provisioning
   needs admin SSH. The fix is for the control plane, which is already root on the box, to
   own provisioning and for the CLI to become a pure client. This is the largest gap before a
   member-role FDE can create containers without host privileges.
2. **Two remote-config models.** `ClientConfig` (SSH-forward through `host` and `user`) and
   `ServerConnectionConfig` (HTTP API through `server` and `token`) both read
   `~/.sail/config.yaml` with different keys. SSH-forwarding papers over this today, but a
   direct-API client (a GUI client, or a future direct-mode CLI) needs them reconciled.
   `sail login` and `sail enroll` now run their passkey ceremonies from a forwarding client over a
   supervised SSH tunnel at the canonical origin `http://localhost:7070`, but the stored
   session token still has no forwarded-command consumer.
3. **Attribution gaps in synced files.** Per-actor attribution rides via `_actor` for specs
   and projects, but shared files have no author column at all, which is a schema change for
   low value, and the change-log's internal author column stays null for projects and files.
4. **FDE removal propagates as `disabled`, not a tombstone.** Revoking an FDE on main locks
   them out everywhere, since the gateway refuses a disabled role, but the row lingers on
   nodes as disabled rather than disappearing. True delete-propagation is a roster protocol
   change.
5. **One platform per OS.** Mac arm64 and Linux amd64 only.

## The operations seam

Three doors, three interfaces, each a strict superset of the one below it:

| Door | Interface | Who holds it |
|---|---|---|
| in-container socket | `LocalLaneOperations` | `LocalApiRouter`, for agents |
| HTTP | `Operations` | `ApiRouter`, for Mast and the remote CLI |
| host, in-process | `HostOperations` | `OperationsFactory.open()`, for commands on the box |

`HostOperations` adds nothing flat; it hands out five facets, each a small role interface a
command depends on by name: `dispatching()` (run and stop agents, the runs that gate them),
`catalog()` (projects and spec rows, rename and purge), `identity()` (tokens, FDEs, SSH keys,
the gateway decision), `pty()` (what the pty host asks: who a session is, which room it may
join), and `schema()` (version, migration, readiness to sync). The routers never see a facet,
so a host-privileged verb cannot leak onto a door, and a new host verb goes into the facet
it belongs to rather than widening a facade. Sync rounds, conflict resolution and shared
files are web-lane verbs and stay on `Operations`. Incus provisioning and the container half
of rename/destroy remain host operations (gap 1).
Opening the factory does not migrate a database: bootstrap and sync explicitly prepare it,
which preserves the failure behavior of ordinary reads and event writes.

`SyncedEntities` is the ordered registry for spec, room, file, project, run, review, and
message stores. It owns replica construction, push policies, resolver lookup, and transition
detection. The node and main server use the same registry and reports reduce in that order.
`SailOperations.sync` records each round in the local `sync_health` table. CLI (`sail sync
status`), HTTP (`GET /v1/sync`), and Mast read the same persisted attempt, success, failure,
and report. `last_attempt_at` is stamped when a round starts and again when it finishes, so
retry delays start after a failed connection finishes timing out. `state` makes in-flight rounds visible; `stale_since` preserves the first failure
when a node has never reached main. Health is local and is not another replicated entity.

`SyncScheduler` combines debounced writes with periodic/read freshness and the pure `Backoff`
policy: 15/30/60/120 seconds with ±20% jitter, then an open circuit at five failures. Reads
never probe an open circuit; a write, manual sync, or five-minute timer does. The scheduler
reads stored outcomes, including manual rounds, so a successful manual sync resets its circuit.
Failure and recovery emit one record event per transition. Slack remains main-only; this brick
does not introduce a separate transport to report a disconnected node's events to main.

Conflict resolution uses the registry for all seven entity types. Web and local credential
lanes require the node's own FDE (`SyncConfig.handle`) with write access, or an admin. Agents
act for their run's owner; a read-only room principal cannot resolve conflicts.

Review every control-plane change with `CommandsUseTheSeamTest` and these searches:

- `Sqlite.open` and `new *Store(` in `commands/`: only `MigrateCommand`, `JoinCommand`,
  `ServerStartCommand`, `SyncServerCommand`, and `FdeCommand` may construct stores.
- Entity-type switches outside `SyncedEntities`, duplicate replica maps, and nested
  `combine(combine(` calls.
- Additional `DispatchOperations` or `StopOperations` construction outside `SailOperations`.
- A method added to `Operations` or `HostOperations` directly instead of to the facet it
  belongs to; a facet that grows past one role.

## Design invariants to preserve

- One binary, zero runtime dependencies, fully declarative. `sail.yaml` is the source of
  truth, and the container is derived state that can be destroyed and recreated.
- The database is the replicated source of truth for specs, projects, and shared files, and
  on-disk descriptors are a materialized view. Reads are catalog-first, and writes go through
  the catalog, so an edit can never diverge or be lost on the next sync.
- Sync is CAS-safe, idempotent, order-independent, and conflict-parking, so local work is
  never lost. The `SyncEngine` is entity-agnostic, and a new synced entity adds a replica,
  not engine logic.
- Per-developer identity (git identity and SSH keys) never rides the synced definition. It is
  placeholdered in the catalog and resolved locally, per box, at provision time.
- Every state-mutating command is idempotent and supports `--dry-run` and `--json`.
- No magic: every command maps to a small number of `incus`, `podman`, `systemd`, and `ssh`
  calls the operator could run by hand.
- No tmux. Infrastructure services use Podman `--restart=always` with linger, and interactive
  dev happens over SSH remote editing.
- Single-box users need zero new commands, and sync stays opt-in.
- Sail orchestrates. It never reimplements virtualization or container runtimes.
