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
| `sail-core` | `auth`, `common`, `config`, `engine`, `gen`, `harness`, `ssh`, `store`, `sync`, `webauthn` | Domain model and config records, the Panama-SQLite store, the DB-sync engine and replicas, passkey and WebAuthn primitives, the SSH gateway tokenizer, pure file generators, and shared id, time, and string seams. No CLI, no HTTP, no native binary. Its only external dependency is SnakeYAML Engine. |
| `sail-harness` | `engine` | The in-container agent harness that runs inside project containers: agent sessions, the guardrail checker, reporting, the in-container `spec` CLI helper, the webhook notifier, and the hook renderer that writes each harness's hook file. |
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
| `SpecStore` | `specs` and their dependencies, repos, and content | Yes, entity `spec` |
| `ProjectStore` | `projects`, the `sail.yaml` descriptor blob plus attribution | Yes, entity `project` |
| `FileStore` | `project_files`, shared workspace files keyed by project and path | Yes, entity `file` |
| `FdeStore` | `fdes`, the human principals and roles | One-way, main to node roster pull |
| `FdeSshKeyStore` | `fde_ssh_keys`, SSH fingerprint to FDE | Local |
| `EventStore` | `events`, the audit stream persisted from the bus | Local |
| `ReviewStore` | `reviews`, `review_stages`, and `review_findings` as the projection of each review's content | Yes, entity `review`, its findings as content |
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

### The sync contract

Every invariant the sync layer holds, each stated so a test can prove it. A change to sync names
the invariants it touches and the tests that prove them; a scenario that breaks one is a bug in
the change that allowed it, fixed there. An invariant marked **open** has a failing reproduction
and the spec that closes it.

| | Invariant | Proven by |
|---|---|---|
| I1 | Every write on every box is made by exactly one bound `Actor`, whichever door it came through. | `ActorTest`, `OperationsTakeNoActorTest` |
| I2 | On a node only the box's FDE, its runs and FDE-less credentials write; any other credential reads. | `RoleRuleTest`, `NodeWritesTest`, `DeniedSyncTest` |
| I3 | Main and the node agree who the node is, and one box syncs as each FDE. | `NodeIdentitySyncTest`, `SyncServerCommandTest` (main's own FDE), `FdeCommandTest` (`release-box`) |
| I4 | Every run a box executes carries that box's handle as `node` and `owner`. | `BoxRunsSyncTest`, `RunAuthorityTest`, `RunStoreTest` (stamps; never another box's run), `SyncConfigTest` (one handle), `HandleChangeTest`, `JoinCommandTest`, `HostConfigSetCommandTest`, `HostSyncCommandTest`, `RoomWakeLaunchTest` |
| A1 | Who may write a synced row is one rule per type in sail-core; the same write gets the same refusal kind and code at every door and on main. | `OneDecisionTest` (spec edits, posts); open for every door: `sail-journal-authority` |
| A2 | No code path writes a synced row without its rule deciding it. | open: `sail-journal-authority` |
| A3 | An event, hook or reactor never makes the machinery do what its sender could not. | the rule: `EventAuthorityTest` (every listed type and rule; every unlisted type refused; a run decides what its event is about and its spec whose it is; only this box's FDE reports what this box observed); both doors with the real subscribers behind them: `EventDoorTest` (a member's stop, completion, failure and review evidence for another member's spec, run or room never reach the bus, so the spec stays, the run stays and the reconciler still rescues; a run a member pushed is no key to another member's spec; an owner is not taken at their word for what this box observed; an owner's retelling and an admin's report land; a run publishes only its hooks; the server's clock, publisher and stored message replace the sender's); every real publisher through its own code path: `EventPublishersTest` (the host CLI's dispatch, restart, ad-hoc run and stop, the watcher's stop, a node's sync announcement, main's sync bridge relaying as the FDE who pushed, and refusing a pushed run on another member's spec); the socket's scoping to the credential's run: `LocalApiRouterTest`, `AgentPrincipalLifecycleTest` |
| A4 | Local prune and main's erase-on-request ask one erase rule, and only main writes erasures. | `EraseAuthorityTest`, `EraseRequestTest`, `SpecPruneTest`; purge case open: `sail-journal-authority` |
| T1 | Every box records the same author for the same revision, and a revision names only whom its writer may write as. | `PushAuthoritySyncTest`, `CreatorSyncTest` (a revision main recorded with no author), `WireAuthorSyncTest`, `ConvergenceSyncTest` (a resolve holds main's revision under main's author, a deletion's included), `ConflictOperationsTest`, `MessageSyncTest` and `ReplyChainSyncTest` (a message under its poster), `ProjectSyncTest` (a rename's deletion under its deleter), `NativeFleetIT` (change-log heads) |
| T2 | A spec's and a room's creator is written once, restores included, and every box holds the same one. | `PushAuthoritySyncTest` (spec and room restores), `ConvergenceSyncTest` (restored and re-created on another box), every sync test through `SyncBox.assertEqualToMain` |
| L1 | Every offer settles within a bounded number of rounds; no type's round fails forever. | `LivenessAuditTest` (posts, born-in specs, reviews, oversized files, a commit that throws beside an accepted offer, an erased project, a room deleted after or before its sync; every settlement within a stated number of rounds, ending equal to main), `SyncRpcServerTest` (a throwing commit is that offer's refusal), `SyncContentFailureTest` |
| L2 | Main refuses, rather than decides, only while what it needs will arrive by sync order. | `LivenessAuditTest` (a reply whose parent main has not taken is refused with it, never denied), `DeniedSyncTest`, `MessageSyncTest`, `SpecAuthorityTest`/`MessageAuthorityTest` (main's `Decidability` reads pending, never denies) |
| L3 | A node holds back what main cannot decide yet instead of failing the round. | `LivenessAuditTest` (a born-in spec behind its room is withheld, not refused; an agent's spec, room, file and review land in the round its run does), `DeniedSyncTest`, `PushAuthoritySyncTest` |
| L4 | Main's version, by denial, pull or merge, never removes or rewrites a run or review still running here. | `BoxRunsSyncTest` (pulls, converged versions, merges, lost answers, another box's run and review), `DeniedSyncTest`, `PushAuthoritySyncTest` |
| L5 | A run whose process is gone is finished on the box that ran it: by its watcher's stop, or within one reconciler pass of no watcher being left. | `MissedStopReconcilerTest`, `MissedStopsTest`; re-stamped runs: `RunTrackerTest`, `StopOperationsTest`, `AgentLogStreamerTest`, `WatcherRearmerTest`, `RunPresenceEmitterTest`; reviewer and fix runs, reconciled as a build is once no watcher is left to report them: `ReviewLanesTest` (a reviewer that exited while the daemon was down; a fix agent that exits unwatched), `ReviewLoopRecoveryTest` (a run its watcher still covers is left to it; a run whose container stopped) |
| L6 | Offers main committed in a round that then failed converge next round, with no conflict and no second revision. | lost answers, merged offers and deletions included: `LostAnswerSyncTest`, `ProjectSyncTest`, `BoxRunsSyncTest`; a refused batch: `LivenessAuditTest` |
| C1 | After one round per box with no new writes, every replica equals main: fields, author, creator, revision, tombstone, erasure. | `ConvergenceSyncTest` (every deletable type restored after an adopted deletion, resolves, rooms re-created), every sync test through `SyncBox.quiesce` and `SyncBox.assertEqualToMain`, `NativeFleetIT` (change-log heads); reviews with their findings: `ReviewFindingsSyncTest` (two iterations, a dispute and a resolution reach main and every box alike; a denied review adopted as main's exactly), `ReviewSyncTest`, `PushAuthoritySyncTest` |
| C2 | State that never replicates is removed only with its entity's erasure or by the box's own action, never by adopting main's version. | reviews hold no such state: a review's findings and its follow-up links are its synced content, so adopting main's version loses nothing (`ReviewFindingsSyncTest`: a follow-up's links survive its spec's delete and restore on every box; `ReviewStoreTest`: the projection is written only from the adopted content) |
| C3 | Whether a disk copy is this box's output or a person's edit is decided without retained history. | each box records what it wrote or published, box-locally (`MaterializedFiles`, through `FileStore.copyOf`): `FilesAuditTest` (a copy main wrote is refreshed, and a deletion removes copy and record, after more pushes than history keeps and a compaction; a hand edit and a hand revert are kept; a mode-only change survives the same; the upgrade of a main that compacted publishes neither its stale copy nor a deleted file's; every scenario ends converged), `FileStoreTest` (every version in flight until one lands, then that one alone; an undecided copy; a publish recorded; the record goes with an erasure and moves with a rename), `FileMaterializerTest` (a write that died before moving the file; an undecided copy kept until a version this box writes lands in its place), `FileImporterTest` (a publish superseded by main before any materialize; an undecided copy never published), `MaterializedFilesMigrationTest` (the upgrade seeds the record once, resumably, from history — its one use — superseded and deleted versions included; a copy matching no retained version is left undecided, a legacy copy's deliberate execute bit a person's), `MaterializedFilesSyncTest` (the record never reaches a node or the change log), `SyncSchemaConvergenceTest` (the sync lane leaves the seed to `sail migrate`); compaction through the sweep and `sail sync gc`: `RetentionSweeperTest`, `SyncCommandTest`; the upgrade through `sail migrate`, `sail project files add` and `pull`: `FilesUpgradeCliTest`, `ProjectFilesCommandTest` |
| C4 | Work only this box held leaves only by main's denial or erasure, kept in the change log and announced, or by its owner's act. | a gone dependency's offer, re-homed or withdrawn and announced (`Settlement.Settled`): `LivenessAuditTest`, `ConvergenceSyncTest` (a denied run's agent's spec edit), `ErasureAuditTest` (a denied offer's content; a member's and an admin's child of a pruned room re-homed and landed); a review main never took, withdrawn with its findings kept in the change log and announced, and a node's legacy findings denied after a reassign: `ReviewFindingsSyncTest`; files: no box republishes a stale copy or resurrects a deleted file by mistaking its own output for a person's edit, nor overwrites a person's edit by the reverse, and a person's copy is published once: `FilesAuditTest`, `FileImporterTest`, `FilesUpgradeCliTest`; an erased file's copy stays with its project and its record goes with main's erase, a node's adoption and a node's prune: `FilesAuditTest` |
| E1 | An erased id is never written again on any box; what belongs to it goes with it; a node removes only what main never acknowledged. | `ErasureTest`, `ErasureSyncTest`; a prune racing a born-in spec, on an admin and a member node: `ErasureAuditTest` |

Every test in the `sync` package that drives a round between boxes ends each scenario by
quiescing every box and asserting each replica equals main — fields, author, revision, head kind
and the merge base it descends from — so a divergence cannot pass unseen: `SyncBox.quiesce` and
`SyncBox.assertEqualToMain`, together `SyncBox.assertConverged`; `SyncBox.assertConvergedWithin`
settles a fleet of boxes round by round first, counting a refusal or a settlement as unsettled.
No scenario is excepted from equality: every entity converges. The fleet lane (`NativeFleetIT`)
asserts after every scenario's final round that every box's change-log heads (id, rev, kind,
author) equal main's for every type.

### What syncs, and how

`sail sync` runs one bidirectional reconciliation per registered entity type, in the
registry's dependency order — runs first, so an agent's run lands before anything its
principals wrote, then specs, rooms, files, projects, reviews and messages — plus one
one-way roster pull:

| Entity | Direction | Notes |
|---|---|---|
| specs | Bidirectional, field-level three-way merge | The team board |
| rooms and messages | Bidirectional; messages are immutable once posted | The conversation |
| project definitions | Bidirectional | The `sail.yaml` catalog |
| shared workspace files | Bidirectional; what this box wrote of each stays box-local (`MaterializedFiles`) | The `files/` bundle, opaque content |
| runs and reviews | Bidirectional; a run is pushed only by the box that executes it; a review carries its findings as content | Execution provenance |
| FDE roster | One-way, main-authoritative pull | Handle, name, email, role, status, and never keys or tokens |

Content — a spec's body and plan, a shared file's bytes, a review's findings — is not in any
row or snapshot. A synced store names its content fields (`SyncedStore.contentFields`), a
snapshot carries a SHA-256 in their place, and the bytes live once in the `BlobStore`: content-defined chunks
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

A review's findings are its content. Every stage's findings — each field, its resolution and
evidence, what it was carried from, and the follow-up spec drafted from it — are serialized
canonically and hashed into the review's snapshot as `findings_hash`, so every box, main and
Mast hold the same review. `review_findings` is a projection of that content, written only by
applying a review revision, on every box; every finding query reads it, and a stage's
`finding_counts` in the snapshot are counted from it. Adding, resolving, disputing or carrying a
finding is one revision of its review, decided by the review rule like any other write, and a
node adopting main's version takes main's findings exactly. A finding names the follow-up spec
drafted from it in that content, so the link survives the follow-up's delete and restore on
every box, and the follow-up reaching `done` resolves the finding `FIXED` in the same content —
one revision of the source review, written by main: locally when main marks the follow-up done,
and on every spec transition a node commits there and as every session opens (`ShippedFollowUps`,
which resolves the findings of every shipped follow-up, so a run lost to a busy database is made
good as the next session opens; main also catches up before a done spec of its own leaves `done`
or is deleted through the API, and after one reaches it),
since the source review is as often another FDE's, which a node may not write — so the
resolution outlives the follow-up's archive and erasure. Findings two boxes changed at once merge
finding by finding (`ReviewFindingsContent.merge`, the review store's `FieldMerger`): main fixing
one as a follow-up ships while the owner's review rules on another is no conflict; only one
finding both changed differently parks one. A review main denies is
adopted as main's; one main never took is withdrawn, its findings recoverable from the content
its change-log entries name. The upgrade folds each box's finding rows from before into one
revision per review (`ReviewFindingsMigration`), which a node's next round pushes; a review
whose findings never became content — run on a box retired before the upgrade — keeps the
counts its snapshot replicated in the legacy `finding_counts` column, which nothing writes for
a review with content.

One `StoreReplica` adapter implements both `LocalReplica` and `MainReplica` over any synced
store, so the same box acts as the node when it syncs up and as the authority when another
node syncs to it. Every synced store keeps a `change_log` of full snapshots and, beside it, a
`change_heads` row per entity naming its latest entry, so the reads the protocol makes are
O(what it asks for), never O(history). A node records each offer it makes, and the state it was
made from, in `sync_offers` before main is asked and drops it once the answer is heard — or once
main's version is adopted, which is the answer — so an answer lost on the way back is recovered
against exactly that state and main's edits the offer merged in stay. The record is used only
when main's answer is to that offer: main answers the latest version it took from the box, which
is an earlier offer's when the recorded one never reached it.

### The wire: sync protocol 4

A session opens with `hello` (protocol, build, fleet floor, box id) and is `welcome`d or
`refuse`d once; floors compare as versions. The `welcome` names the handle main authenticated the
session as and main's `limits.file_max`, so the node enforces the lower of its own limit and
main's before a file ever reaches a sync; a node whose configured sync handle is blank or another
does nothing that round (`NodeRound.begin`); an older main names neither and is not asked. Main records the first box that
syncs as each FDE (`fde_boxes`, never synced; main's own FDE's box is main) and refuses a session
from any other until an admin runs `sail fde release-box`; removing an FDE releases its box. A
node's answer to an offer main took can be lost on the way back. Main records the box each
revision came from (its `peer`), so its answer to a `need` also names, per id, the latest version
it took from the asking box after the version the node last heard of it (`accepted`), ordered in
main's own change log against the latest entry carrying that rev; when compaction has removed
that version, main answers only a version among its newest retained history. A box mints each rev
from the entity's latest entry, tombstones included, so a rev never recurs for one entity; the
latest-entry match covers histories from before that. A resolve adopts main's side of a parked
conflict at main's rev and author, exactly as a pull would, so it is a version heard from main.
Before
each type, the node asks only that (`accepted_only`) for every row it changed, which almost always
answers nothing, and adopts what comes back as the row's merge base, keeping its own row on top
under the author who wrote it; the round then reconciles three-way against exactly what main took,
so a change main made since is never reverted and one the box made since is never lost. Before its first type, the node asks the same of every
run it made (its oldest entry is its own write) that main never acknowledged — a box that was main
holds every box's runs with no base, and another box's run is never its to stamp — and stamps with
its handle every one main does not hold, and every run of its own that acts for no one, so main
takes it. A handle change asks the same before re-stamping anything, and holds every round of the
box off from the ask until its stamps are written (`SyncOperations.holdRounds`, a lock file
beside the database), so no run is offered, and its answer lost, in between. The box id names the node in main's log; who the
node is stays the authenticated SSH principal, bound as the `SYNC` actor around
every commit and erase: it is each revision's `peer`, and its author unless the revision
offers its own `_actor`. The node binds `MAIN` around its round, so what it adopts records
the author main recorded, with `main` as the peer. Wherever main hands the node a revision
that carries no snapshot to name its author in, main says who made it: an `accepted` result
names the author main recorded for the node's own offer (the pusher, when the offer named
none), and a tombstone or erasure `entry` and a `denied` tombstone name theirs. The engine
adopts each under `Actor.main(author)`, so every replica records the same author for the same
revision. Each field is optional on the wire, and an older peer ignores it. The node
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
— and a commit naming a hash main does not hold is refused. A read-only principal's `announce`
is answered lacking nothing, so main never stores a byte of its content, and each offer it
then pushes is denied. Every protocol-4 message names an `op` and no earlier
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
theirs only. A conflict keeps main's side with the rev main holds it at and the author main
recorded (`remote_rev`, `remote_author`). Resolving adopts that version exactly as a pull would
(`RevisionJournal.resolveConflict`, one path for every journal store), so `--theirs` holds main's
revision under main's author, a deletion's deleter included, and `--mine` or `--merge` writes the
choice over it as this box's own revision for the next round to offer. The conflict cannot
re-raise, and every version stays in the change log, so no choice loses work. A conflict parked
before conflicts kept main's revision names none, and every strategy on it is refused until
`sail sync` re-records it.

One rule in sail-core (`Decidability`) names what each offer depends on — a spec on the room it
is born in, a review on its spec, a message on its conversation, its reply parent and the run of
an agent author, any revision on the run its `_actor` names, and every type on the project it
belongs to — and reads each dependency's standing (`Standing`: held, pending or gone) from the
box asked (`Holdings`): main holds a dependency while it has any live or deleted entry for it,
awaits one it has never seen, and has lost one it erased; a node reads its copy for what main
holds — a live row main has taken (its `base_rev` set) is held, one main has not taken is
pending, a deletion heard from main or made over a row main had taken is main's word, and a
withdrawal or a deletion of a row main never took is something main never held and never will.
A deleted dependency main did hold is a fact main knows: an update of something main holds is
main's to decide, while a new spec born in such a room has nowhere to land and is gone. Main's
answer (`StoreReplica.commit`), the node's hold-back (`StoreReplica.dirtyIds`) and the node's
settlement (`Settlement`) all read this rule; no store keeps a copy of it.

Main answers each offer on its own: `accepted` with the rev it minted, `stale` when it moved
since the node fetched, `refused` when a dependency has not arrived yet or a commit throws, or
`denied` when this principal may not make the change. A denial is decided inside the commit's
transaction, after its compare-and-set and before its first write, never thrown, and never fails
the offers beside it; so is a refusal — any exception inside one commit is that offer's refusal
with its reason, and an offer that cannot be taken is refused alone while the batch's others keep
their answers. Main denies what the type's write rule refuses the pusher (below, **One rule per
type**), and a reply to a message in another room. Main refuses, never denies, while a
dependency has simply not arrived — a born-in spec's room, a message's conversation and the
message it replies to, an agent revision's or post's run, a review's spec — because they sync in
order (runs first, and a parent before its replies), so the next round decides it; and the node
holds such an offer back rather than offering it to be refused, through the same rule. The node
reports every refusal (`TypeReport.refusals`, the round's notices, `sail sync --json`), so a
refusal that repeats round after round is seen for what it is: a dependency that will never
arrive. A dependency main has erased is terminal: main denies the offer with no
version and marks it `gone`, and the node settles it that round. The answer carries main's
current version of the entity: a revision, a tombstone, or nothing. The node budgets every offer
for its answer as well as its bytes, and a version that would take the answer past that room is
withheld; the node fetches it with `need`, as it does for a stale offer. A push from a node of
this release therefore never gets results larger than the frame it fit.

The node settles what can never be decided (`Settlement`), reading the same rule at the start
and at the end of every round — so a `gone` answer is settled in the round that heard it, once
the deletions and erasures that round paged are in hand: a born-in spec whose room alone is gone
— deleted before its first sync, denied, or deleted on main before the spec reached it — is
re-homed into its own identity room under its own author, which it may because main never took
it; an edit of something main holds whose dependency is gone reverts to main's version; anything
else is withdrawn as a denial is, kept in the change log under a `denied` entry that no round
offers again. Work still live here is left to its pipeline, and an offer whose answer may merely
be lost to the round's recovery. Each settlement is its own write transaction, and one that
cannot be settled is reported, never a wedge for the rest. Every settlement is announced in the
round's notices and report, as a denial is, naming what became of the work. A file already stored
above main's `limits.file_max`, which the welcome carries, is withdrawn once rather than refused
mid-upload — main is asked first which of them it took, since an oversized offer gets no answer
per offer, only a refused channel, and one main took is acknowledged, not withdrawn — and the
node refuses an oversized file at ingest against the lower of its own limit and main's, naming
both, so one never breaks the channel. Content uploaded for an offer of a
content-bearing type main then denies is collected, never left on main.

The node settles a denial as it settles a pull, and counts it as one: it adopts main's version at
main's rev, or removes its row when main holds none. Adopting a deletion makes main's tombstone the
row's merge base: the box holds main's deletion, never one of its own to offer, so a restore or a
re-add main or another box makes later is pulled, never undone, and parks no conflict however old
the revision it restores. A row written over its own tombstone, restored or re-created, continues
the entity's revision counter and descends from the base that tombstone records. A project
rename's deletion crosses as its resurrection block and is adopted with it. A room adopts every
synced field main holds, its creator and creation time included; both are otherwise written once.
What main recorded with no author is adopted with none, and a message is journaled under the
author it names on every box, whoever posted it there. Equal revisions are converged only under
one author and one head: a box that adopted a revision under an earlier release's reading of it
takes main's again, and a box that minted main's revision itself from the same content
acknowledges it as its base. Every node walks main's heads once more after upgrading to this
release, so what an earlier release left different heals in one round. When a local write lands
while the box's own offer is in flight, the version main took becomes the row's merge base with
the newer row kept on top, and the round offers it, so the box never conflicts with itself. A
conflict parked on an entity whose base then moves this way is settled, and re-parked only if
the row still clashes. A denied message leaves the room with the
replies this box posted under it. Work still under way here is the exception, whether main's
version arrives by denial, by pull or inside a merge with this box's own change: a run this box executes that has not finished (it carries
this box's handle) keeps its row, credential and room guard, is offered again, and settles once it
has finished. One guard in the engine covers every adoption; another box's run is always adopted
as main holds it. The node's own revision stays in its change log, no conflict is parked, and the round
carries on, so the next round has nothing to offer again. Each denial is announced as main
answers it, naming where the node's version is kept, so `sail sync` and a node's running server
print it even when the round then fails; `sail sync --json` and `GET /v1/sync` list them (type,
id, reason). On the wire a denial is a `refused` result marked `denied: true`, so
a 0.46 node reads a refusal and fails that type's round naming the reason: as before for a
read-only push and a forged author, while a run main may not take from it, which a 0.46 main
answered as stale, now fails its run type until the node upgrades. A terminal denial carries an
optional `gone: true`; a node at the same floor that predates it reads a plain denial and removes
the offer, as before, so nothing new is lost.

A conflict is decided on what the box holds now. Every strategy writes a recorded snapshot, so
a resolve is refused (`409` over the API) when the live row no longer matches the conflict's
recorded local side, ignoring latest-wins fields such as a run's heartbeat; `sail sync`
re-records every parked conflict, after which the same resolve applies. `--mine` and `--theirs`
need no such guard on main's side: the side they choose and the merge base come from the same
row, the recorded remote only becomes the merge base, and the next round runs the three-way
against main's current row, so a disjoint change on main merges and the same field parks again.
`--merge` is different. A merged record is a full record made from the conflict as it was when
the merge started, and a round re-records the conflict with main's news meanwhile; applied to
the fresh version it would read every field main moved as a local edit back to its old value,
and that edit would win main's compare-and-set. So a merge is bound to the conflict it was made
from. `sail conflicts show <id> --template` (and `--merge`, which opens it in `$EDITOR`; `GET
/v1/conflicts/<id>?template=true` over either API) emits the record with a `_conflict` key: a
short SHA-256 over the work in the conflict's recorded base, local and remote snapshots (who
wrote a side is no news, as conflict detection holds), which a re-record that brings no news
keeps. A merge resolve without it is refused (`400`), one made from another version is refused
(`409`) with nothing written, a conflict that is not open is `404`, and the key never reaches
the row, the change log or the wire. A refused `--merge` keeps the edited file and prints its
path, as reference for the redo. Ids are unique only within a type and a spec's room carries
the spec's id, so a conflict is addressed by type and id: `--type` on the CLI, `?type=` over
the API. An id parked under several types is refused naming them (`400`) rather than guessed.

### Archive, delete, prune

Three verbs remove work, and each makes a different promise.

- **Archive** takes a spec off the board and keeps everything. It is a status. `archived_at` and `cancelled_at` record when a spec entered those statuses, which is the time retention ages on.
- **Delete** writes a tombstone that keeps the entity's last state. The spec's runs, reviews and room stay, so a restore from any retained revision, including the tombstone, brings the spec back with the identity room it minted.
- **Prune** erases the entity everywhere, for good. A spec is pruned from archived, cancelled or deleted, never from work still on the board, and never while a run of it is unfinished.

An erasure is a kind of change-log entry, next to revision and tombstone. One transaction removes the live row, its open conflicts, the box-local rows keyed by it (events, Slack threads, run credentials, container leases, what this box wrote of a file) and every history entry, and records one erasure row: an empty snapshot plus a fresh rev naming who pruned and when. The head then points at that row, and an erasure is terminal: the journal (`ChangeLog.append`) refuses any later write to the id, so a pruned spec id or project name is never used again, and every node pages the erasure as the entity's last word however long it was offline.

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
admits the `_sync` session. The write gate lives next to the write: every commit of a `_sync`
session is denied to a read-only viewer role, which may still pull.

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
the box's local `git config`. The agent's context, written by `agent context regen` and
`agent run`, names the same identity: the row's `${GIT_NAME}` and `${GIT_EMAIL}` are replaced
by the box's git values where it has them and left as they are where it has none, and no
other placeholder is touched. `${SSH_PUBLIC_KEY}` resolves to the box's registered
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

**The harness contract** (`ai.singlr.sail.harness`): sail drives two coding harnesses,
Claude Code and Codex, and will drive more. Everything that differs between them sits behind
one interface, `Harness`, with one package-private adapter per harness (`ClaudeCode`,
`Codex`) and a registry, `Harnesses`, that resolves a `sail.yaml` name (`of`), lists every
harness (`all`) and names the default (`DEFAULT`). No main code outside the adapters names
one, compares a harness with a name or switches on one: a capability is an answer the
adapter gives, not a name a caller recognises. Adding a harness is adding one adapter.

| `Harness` method | What it answers |
|---|---|
| `yamlName()`, `binaryName()`, `displayName()`, `installCommand()` | identity: the `sail.yaml` name, the binary on PATH, the name people read, how to install it |
| `homeContextPath()`, `skillsDir()` | where the sail-owned context file and skills live under `$HOME` |
| `languageRulePath(name)`, `languageRule(name, paths, body)` | where a project's language rule lands and what it holds, in the harness's native load-when-relevant channel |
| `headless(Launch)` | the command for a build, a reviewer, a fix agent or a full chat turn; fresh when the launch's `resumeSessionId` is null, resumed otherwise |
| `readOnly(Launch)`, `readOnlyRefusal()` | the room lane's harness-restricted command, or why the harness has none |
| `interactive(fullPermissions)`, `attach(sessionId)` | the TTY session, fresh or resuming a recorded conversation exactly by id |
| `honoursReasoningEffort()`, `loginTunnelPort()`, `interactiveTip()` | whether a reasoning effort means anything, which port the login flow needs forwarded, what to tell an engineer before an interactive session |
| `hooks()` | the harness's `HookFile`: which of its events run which of sail's `SailHook`s |
| `isSafeSessionId(id)`, `requireSafeSessionId(id)` | whether a hook-reported, replicated session id may touch a shell string, and the one check every adapter makes before it does |

A `Launch` carries the task file, whether every action is auto-approved, the model, the
reasoning effort, the session to resume and whether to stream. A malformed session id throws
before it can reach a shell string, in `headless`, `readOnly` and `attach` alike; `readOnly`
ignores permissions and reasoning effort, and a harness with a refusal throws rather than
build one. The login port is the adapter's alone to name: `sail agent run`
prints the tunnel for the harness it starts, and `sail project connect` for each harness sail
knows that needs one.

Hooks are data. `SailHook` names the eight things sail runs at a harness's events:
`SESSION_STARTED`, `SESSION_REPORT`, `TOOL_STARTED`, `TOOL_FINISHED`, `ROOM_RELAY`,
`STOP_GATE`, `BATCH_RESOLVED`, `SESSION_ENDED`; the first six are required of every
harness, and a `HookFile` whose groups do not name one cannot be built, nor one whose path
is not absolute inside a directory. `HarnessHooks`
(`sail-harness`) renders the file from the adapter's declaration, one switch giving each hook
its script and timeout, and `ContainerSailSetup` installs and fingerprints the file of every
harness in `Harnesses.all()`.

| Claude Code event (`~/.sail/claude-settings.json`) | Matcher | Hooks |
|---|---|---|
| `SessionStart` | `startup` | `SESSION_STARTED` |
| `SessionStart` | none | `SESSION_REPORT` |
| `PreToolUse` | none | `TOOL_STARTED` |
| `PostToolUse` | none | `TOOL_FINISHED`, `ROOM_RELAY` |
| `PostToolUseFailure` | none | `TOOL_FINISHED` |
| `PostToolBatch` | none | `BATCH_RESOLVED` |
| `Stop` | none | `STOP_GATE` |
| `SessionEnd` | none | `SESSION_ENDED` |

The file also carries `includeCoAuthoredBy: false` and the `permissions.deny` read rules that
belt-and-brace the room lane's top credentials.

| Codex event (`~/.codex/hooks.json`) | Matcher | Hooks |
|---|---|---|
| `SessionStart` | none | `SESSION_STARTED`, `SESSION_REPORT` |
| `PreToolUse` | none | `TOOL_STARTED` |
| `PostToolUse` | none | `TOOL_FINISHED`, `ROOM_RELAY` |
| `Stop` | none | `STOP_GATE` |

Golden tests (`HarnessGoldenTest`, `HarnessHooksGoldenTest`) pin every launch command and
both hook files as text; `HarnessContractTest` runs what every harness must answer over
`Harnesses.all()`.

**Guardrails and rollback:** a guardrails block sets a `max_duration`, a `max_idle` stall
window, and an action (`snapshot-and-stop`, `stop`, or `notify`), and each lane reads its
own: `agent.guardrails` bounds a build, an ad-hoc run and a chat turn (default `4h` / `20m`
/ `stop`), `agent.review_pipeline.guardrails` a reviewer and a fix agent (default `45m` /
`20m` / `stop`). Both parse through the one `Guardrails` record, which refuses an invalid
duration, a zero limit or an unknown action where the descriptor is read, naming the block,
the key and the accepted forms. A watcher (`sail agent watch`, started with every run; the
loop is `RunWatch`) waits on the project's event stream for the sooner of the wall-clock
deadline and the stall deadline, and asks the container whether the unit is still active at
least every 15 seconds, on the clock, so a busy project never delays noticing that a run
ended. Progress events of its own run (its tool calls starting and finishing) push the stall
deadline out, so an agent that keeps calling tools is never killed for a stall and a silent
one is. A tool call in flight is work, not a stall: the watcher counts its run's calls that
started and have not finished, and while one is in flight and a `max_duration` bounds the
run there is no stall deadline; the window starts again when the last call in flight
finishes. A call that ran is told finished whether it succeeded or failed: Claude Code fires
`PostToolUse` only for a call that succeeded and `PostToolUseFailure` for one that failed (a
command that exits non-zero), and both publish `agent_tool_finished`; Codex fires
`PostToolUse` for both. A call Claude Code denies before running it fires neither, and a
finish can be lost on its way to the daemon, so the count is also set to none whenever the
main agent's batch of calls resolves: `PostToolBatch` publishes `agent_tool_finished`
carrying `batch: true`, for the main agent only, since a subagent's batch says nothing of
the calls its parent still waits on. A count that went wrong is right again at the next
batch. The count is the run's, not each agent's: the main agent's batch resolving also ends
a call a background subagent still has in flight, which is then bounded by the stall window
from its last event like any call the watcher did not count. A call that began where the watcher could not hear it — before a re-armed watcher
started, or while its feed was down — is not counted, and has one stall window from then.
Codex has no batch hook; there a lost finish leaves the wall clock as the bound. With no
`max_duration` the stall window is the run's only bound, so it runs through a tool call
too. The stall window counts only time the watcher could see: a watcher outlives the daemon it listens to, and
when its event stream ends with a daemon restart it opens the stream again at its next poll
and starts the window afresh, the calls in flight still counted, while the wall clock runs
on (a daemon that accepts the connection and does not answer is given a few seconds, not
the watch). The watcher is handed its run's
limits on its command line when it is spawned (`--max-duration`, `--max-idle`, `--action`)
and never reads the project's guardrails itself, so an edit applies to the next run; it is
handed the run row's `started_at` the same way (`--started-at`), which anchors the wall
clock, and never reads the run's session file for it, which the agent can write. On a
trip with a stopping action it kills the unit's whole cgroup in the container and, once the
container itself answers that the unit is gone, records the trigger beside the run
(`~/.sail/runs/<runId>/guardrail-triggered.yaml`) and publishes the run's stop carrying the
reason (`time limit (45m)`, `stall (20m)`). The kill says what it did (`AgentSession.Halt`):
`Ended` when a signal was delivered and the container answered that the unit is gone,
`Survived` when both signals were sent and it answered that the unit is still active, and
`Unanswered` when the signal could not be delivered or the unit's manager did not answer
afterwards. Only on `Ended` is the unit reset and the run's pid file removed, so a run whose
kill nothing answered for still probes as alive. A foreground session, which has no unit, is
halted by its pid file the same way: its signals and the question of whether the process is
gone run as one script in the container, and only what that script prints is believed, so a
pid file that could not be read, a signal that could not be delivered and an exec that came
back with no answer are each `Unanswered`. Silence is never an agent's death: a unit that
survives the kill is not reported ended and is killed again at the next poll, no kill is
tried while the container cannot say whether it worked, a kill counts as tried only on
`Ended` or `Survived` (an agent no signal reached that then ends exited on its own, and its
stop says so), and a watcher whose unit's manager gives no answer while the agent is known
gone — its container stopped, or its process dead in a container that answers — ends its
watch with no stop, since nothing there says how the run ended; the missed-stop reconciler
speaks for that run. How a unit that answers ended is its manager's to say, and that is
what a watcher publishes a stop on. Where nothing else says, whether an agent is gone is
one reading (`AgentPresence`), shared by the watcher whose manager went silent or that
finds no session to watch, the reconciler, an operator's stop and a failed launch's
cleanup: one script in the container looked for the run's process, by its pid file or
else its unit, and printed that it is gone — or incus lists the container stopped or
absent. Only the printed word is believed, so no number of lost commands reads a live
agent as gone; a container that runs no command says nothing of its agents. The watch
ends with its run: its run's authoritative stop or cancel, published by anyone, ends it
with nothing published and no trigger written. Rollback uses Incus
snapshots, which are instant on `zfs` and full copies on `dir`, and the pre-dispatch
snapshot is the restore point.

The watcher runs detached, as the systemd transient unit `sail-watch-<runId>` — the same
mechanism that runs the agent — so it survives Ctrl-C on the dispatch stream, the SSH
session ending, and daemon restarts. Every run has its own identity end to end, whichever
lane launched it:
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

**The loop lanes.** A build, a reviewer, a fix agent and a chat turn are the same kind of
process — a headless coding agent in the project container — and sail runs them one way. The
lane (`Lane`: `build`, `adhoc`, `review`, `fix`, `room`, `room-full`) is data: it names what
the run's stop means to the loop and whose limits bound it, never how the run launches or
ends. The contract, each row with the tests that prove it (`ReviewLanesTest` and
`ReviewLoopRecoveryTest` drive the production launch path, watcher loop, pipeline, tracker,
reconciler and bus over a fake container shell and clock; `ReviewAgentLoopIT` runs the launch
path, the units, the pipeline and the tracker against a real container, standing in for the
watcher):

- **P1. One run per invocation, one launch.** Every agent sail starts is its own run row with
  its own `AgentUnit.forRun(runId)` (unit `sail-agent-<runId>`, session, task, pid and log
  under `~/.sail/runs/<runId>/`), launched through `RunLauncher.launchSession` with a
  `LaunchSpec` naming its lane. A reviewer's and a fix agent's run rows name the review they
  serve (`runs.review_id`); the review id is not a run id. No lane runs an agent through a
  blocking exec, and no lane has a log, unit or pid path of its own shape.
  *`aReviewerThatCompletesHasItsStopRoutedByLaneAndItsStageResolvedFromItsOwnLog`,
  `twoSpecsPipelinesInOneContainerEachAdvanceOnlyOnTheirOwnRunsStop`,
  `RunStoreTest.aReviewerAndAFixAgentAreEachTheirOwnRunNamingTheReviewTheyServe`,
  `ReviewAgentLoopIT.theReviewLoopReachesAwaitingMergeWithEveryAgentAsItsOwnUnit`.*
- **P2. One supervisor.** Every such run has a watcher (`sail agent watch --run --unit`),
  spawned the way the build lane spawns it, enforcing that run's lane's guardrails. A trip
  kills the unit in the container, records the trigger beside the run, then publishes the
  authoritative `agent_session_stopped` with `run_id`, `run_role` and `reason`. No host
  process waits on an agent. A run's end is its watcher's to report: the reconciler never
  publishes a stop for a run while a `sail agent watch` process for it is running on the host
  (`WatcherCoverage.watching`; the re-armer asks the same record whether to arm one).
  *`RunWatchTest.aRunPastItsTimeLimitIsKilledThenItsTriggerRecordedThenItsStopPublishedWithTheReason`,
  `RunWatchTest.aRunThatSurvivesTheKillIsNeverReportedEndedAndIsKilledAgainAtTheNextPoll`,
  `RunWatchTest.aBusyProjectNeverDelaysNoticingThatTheRunEnded`,
  `RunWatchTest.aFeedThatEndedIsOpenedAgainAndItsSilenceIsNeverCountedAsAStall`,
  `RunWatchTest.aContainerThatDoesNotAnswerIsNeverTakenForAKilledAgent`,
  `RunWatchTest.aStoppedContainerEndsTheWatchWithNoStopSinceNothingSaysHowTheRunEnded`,
  `RunWatchTest.aRunWhoseUnitManagerDiedWithItsAgentEndsTheWatchWithNoStop`,
  `ReviewLoopRecoveryTest.aSweepNeverSpeaksForARunItsWatcherStillCovers`,
  `aDaemonRestartWhileAReviewerRunsFailsNothingAndReArmsAWatcherThatDiedWithIt`.*
- **P3. One stop, one router.** A run ends exactly one way: the watcher's, or the
  reconciler's, authoritative stop (`RunStops`). `RunTracker` finishes the row as it does for
  a build, and `ReviewPipelineController` routes the stop by the lane of the run that
  stopped, read from this box's own row: a build's stop starts the review the spec is due; a
  reviewer's resolves its stage from the findings in that run's own log; a fix agent's runs
  `ensureCommitted` and starts the re-review as the next iteration; a room run's is ignored.
  Never by a return value, and never by the spec's status, which is `in_progress` both while
  a build runs and while a fix agent does.
  *`aFixAgentThatCompletesIsFollowedByTheReReviewAsTheNextIterationWithItsOwnRun`,
  `aBuildsStopWhileTheSpecsFixAgentRunsIsNotTakenAsTheFixAgentsStop`,
  `aPlainReviewerAndAStreamingOneAreEachJudgedOnTheirOwnRunsLog`.*
- **P4. One hook set.** Every lane's agent runs with the same hooks and the same
  `SAIL_SPEC_ID`, `SAIL_RUN_ID`, `SAIL_RUN_ROLE`, `SAIL_RUN_CREDENTIAL` environment, so
  progress (`agent_tool_*`) resets every lane's stall timer the same way. The one stop gate
  asks by lane: a build for a clean, pushed tree behind a pull request whose checks it
  watches; a fix agent for clean and pushed only; a reviewer for nothing, since its last
  message is the verdict the pipeline parses. The pipeline's router, not a missing hook, is
  what keeps a reviewer's stop from starting a review.
  *`SailStopGateTest.aFixRunIsAskedToCommitAndPushAndNeverToWatchCi`,
  `SailStopGateTest.aReviewerIsNeverBlockedByADirtyTreeNorByTheRoom`, and the launch
  environment asserted in P1's first test.*
- **P5. One guardrails record, per-lane values.** `Guardrails` is the only limits type,
  parsed one way; `SailYaml.Agent.guardrailsFor(lane)` is the one place a lane is mapped to
  its block and its defaults, and `lifetimeFor(lane)` beside it bounds the run's credential.
  The watcher receives its run's limits at spawn.
  *`aReviewLanesLimitsDefaultTo45MinutesAndAnEditAppliesToTheNextRunOnly`,
  `SailYamlTest.eachLaneReadsItsOwnGuardrailsAndItsOwnDefaults`,
  `SailYamlTest.aRunsLifetimeIsItsLanesLimitAndABuildNothingBoundsHasNone`,
  `SailYamlTest.aReviewLaneGuardrailsValueThatIsNoBlockIsRefusedRatherThanReadAsTheDefaults`,
  `GuardrailsTest.anInvalidDurationIsRefusedWhereTheBlockIsParsedNamingItsKeyAndTheAcceptedForms`.*
- **P6. The loop's state is rows that already exist.** Which stage a review is in is its
  stage rows' statuses; which iteration, the review row's `iteration`; which run it waits on,
  the newest live run of this box that names it. What a review is owed next is one reading of
  those rows (`LoopFacts.Rows.owed`) that the pipeline acts on and the reconciler rescues
  by, so the two never disagree. Nothing is held on a thread across an agent's lifetime, and
  nothing at daemon start marks a running review failed: a run still going whose watcher
  died is re-armed with one (`WatcherRearmer`), and one that ended unobserved has its stop
  published by `MissedStopReconciler`, which reconciles a reviewer and a fix agent as it does
  a build.
  *`aDaemonRestartWhileAReviewerRunsFailsNothingAndReArmsAWatcherThatDiedWithIt`,
  `aReviewerThatExitedWhileTheDaemonWasDownHasItsStopPublishedAtStart`,
  `aFixAgentThatExitsUnwatchedIsReconciledAsABuildIsAndTheLoopGoesOn`,
  `aReReviewWhoseReviewerNeverLaunchedGoesOnWhenItsFixAgentsStopIsReplayed`,
  `aStageWhoseReviewerNeverLaunchedIsNeverJudgedOnTheStageBeforeItsLog`,
  `aGateFailedReviewWhoseFixAgentNeverLaunchedGetsItAtTheNextSweep`,
  `ReviewLoopRecoveryTest.aFinishedFixWhoseReReviewWasNeverWrittenIsRescued`,
  `ReviewLoopRecoveryTest.aWaitingReviewIsRescuedOnceItsHolderEndsWithNoStopOnTheBus`,
  `ReviewLoopRecoveryTest.aReviewWaitingToLaunchIsNeverReplayedWhileARunHoldsItsClaim`,
  `ReviewLoopRecoveryTest.aStageHeldUpByARunThatEndedBeforeAnySweepSawItIsStillRescued`,
  `ReviewLoopRecoveryTest.aRescueThatNeedsNoClaimIsNotHeldUpByARunThatHoldsTheRepo`,
  `ReviewLoopRecoveryTest.aReviewWithNoStageRowsWhoseFirstStageIsAPersonsIsOpenedWhoeverHoldsTheRepo`,
  `ReviewLoopRecoveryTest.aPersonsStageLeftUnopenedIsOpenedWhoeverHoldsTheRepo`,
  `ReviewLoopRecoveryTest.aStaleBuildStopStartsNoReviewBesideTheBuildThatReplacedIt`,
  `ReviewLoopRecoveryTest.aReviewRescuedOnceThatThenErrorsIsStillRetried`,
  `ReviewLoopRecoveryTest.aStopElsewhereNeverRelaunchesAReviewerWhoseOwnStopIsStillOnItsWay`,
  `MissedStopReconcilerTest.aRunningReviewNoRunServesHasItsNewestLoopStopReplayedOnce`.*
- **P7. A reaped agent is dead.** After a trip there is no live agent process for that run in
  the container, nothing of a killed fix agent's is committed, and the room says why:
  `review_errored` (`reviewer killed: time limit (45m)`, `reviewer failed: exit 1`) and
  `review_iteration_failed` (`fix agent killed: stall (20m)`), both narrated in Slack and in
  Mast's room. That holds however the stop is heard: the watcher's own, a replay of one it
  recorded, or — when the kill's stop never reached the daemon — the reconciler's, which
  reads the trigger the watcher left beside the run and the exit code a failed unit still
  holds.
  *`aReviewerPastItsTimeLimitIsKilledAndItsReviewErrorsWithTheReasonWithinTheRetryBudget`,
  `aFixAgentPastItsTimeLimitIsKilledNothingOfItsLandsAndTheRoomIsToldWhy` (both run the real
  watcher loop to its trip), `ReviewLoopRecoveryTest.aKillWhoseStopNeverReachedTheDaemonIsStillAKill`,
  `ReviewLoopRecoveryTest.aKilledReviewersHalfWrittenVerdictNeverPassesItsReview`,
  `ReviewLoopRecoveryTest.aCrashedFixAgentNoWatcherCoversIsReconciledWithTheExitCodeItsUnitStillHolds`,
  `ReviewAgentLoopIT.aKilledReviewerLeavesNoAgentProcessBehindAndItsReviewErrorsWithTheReason`.*

The loop closes: every wait is recorded, every review ends, and every stop says only what
happened. Each clause with the tests that prove it (`ReviewLoopRecoveryTest` unless another
class is named):

- **C1. A wait is a recorded fact.** A review whose launch was refused its claim records the
  run that holds it (`reviews.waiting_on`, local to the box like a run's pid, never synced),
  and the room is told in the same write, so once per (review, holder): "Review is waiting
  for run `<runId>` (`<role>` of `<specId>`) to finish." A wait whose line could not be
  written is not recorded; a review that changes status, is superseded or is approved waits
  on nothing.
  *`aReviewerRefusedItsClaimRecordsTheRunItWaitsOnAndIsLaunchedByThatRunsStop`,
  `aWaitIsSaidOncePerRunWaitedOnHoweverManyStopsFindItStillHeld`,
  `aFixAgentRefusedItsClaimWaitsOnItsHolderAndIsReplayedOnceWhenThatRunEndsWithNoStop`,
  `ReviewStoreTest.aWaitIsRecordedOnceAndWhatTellsOfItIsWrittenWithIt`,
  `ReviewStoreTest.aWaitWhoseTellingFailsIsNotRecorded`,
  `ReviewStoreTest.aWaitOnASyncedReviewIsThisBoxsOwnNeverDirtyARevisionOrOnTheWire`,
  `ReviewStoreTest.aReviewThatChangesStatusIsSupersededOrIsApprovedWaitsOnNoRunAnyMore`,
  `ReviewStoreTest.adoptingMainsRevisionKeepsTheRunThisBoxWaitsOn`,
  `ReviewAgentLoopIT.aReviewerRefusedTheRepoARealRunHoldsWaitsOnItAndStartsWhenItStops`.*
- **C2. Recovery acts on the record, once.** The reconciler rescues a waiting review once
  the run it waits on has been terminal for longer than the launch grace, or is gone from
  the store, once per (review, holder). It samples no gate and has no retry budget.
  *`aWaitingReviewWhoseHolderEndsWithNoStopIsReplayedExactlyOnce`,
  `aHolderThatOnlyJustEndedIsNotRescuedOverWhileItsOwnStopMayStillBeOnItsWay`,
  `aReviewRefusedThreeTimesRunningByRunsThatEndWithNoStopWaitsOnEachAndIsReplayedOncePerWait`,
  `aReviewTheDaemonDiedBeforeLaunchingIsReplayedOnceAndARefusedReplayBecomesARecordedWait`,
  `MissedStopReconcilerTest.aRunningReviewNoRunServesHasItsNewestLoopStopReplayedOnce`.*
- **C3. A stage is `running` only when a run exists for it or a person owns it.** The stage
  row turns `running` once its reviewer's claim has landed and before that reviewer's unit
  starts; a refused claim leaves it as it was, so main announces no stage that has no
  reviewer.
  *`aRefusedReviewersStageStaysPendingAndMainIsToldNoStageStarted`,
  `aLaunchedReviewersStageIsRunningAndItsRunRecordedBeforeItsUnitStarts`,
  `ReviewLaneLauncherTest.whatMustHoldOnlyWhileARunServesTheReviewIsWrittenOnceTheClaimLandedAndBeforeTheUnitStarts`,
  `ReviewLaneLauncherTest.aClaimTheGateRefusesWritesNothingOfTheLaunchThatDidNotHappen`.*
- **C4. Every review ends.** From every state the stores can hold, one pipeline step leaves
  the review served by a run, waiting on a recorded run, owned by a person, passed, or
  escalated with a reason. A failed gate with nothing left open is reviewed again as the next
  iteration. A review the project's pipeline can no longer judge is escalated saying so: the
  pipeline lost its stages, a stage the review holds is not the pipeline's stage in that
  place any more (renamed, removed or retyped while the review ran), or the project's
  definition in the catalog cannot be read — which is never taken for a project with no
  pipeline. A build that ends while the definition cannot be read gets its review written and
  escalated for that reason, not a review under the default pipeline and not a stop replayed
  until the row heals; a failure of the store itself is not an unreadable pipeline and is
  replayed. What runs a project reads its definition from the catalog row (`ProjectReader`,
  handed to commands as `HostCatalog.definitions`): the loop's pipeline and reviewer, every
  API lane, Slack and webhook notifications, and `agent watch`, `status`, `report`,
  `attach`, `context regen` and `run`. None opens `~/.sail/projects/<name>/sail.yaml`, and no
  row is ever substituted by a file, so a revision that reaches the catalog by sync or by a
  command is what the next loop event, dispatch and notification use, with nothing else run;
  a watcher already running keeps the notifications it started with. The file is still
  written, in one move (`ProjectDefinitions.write`), as a copy nothing running reads; the
  management commands of `ProjectDefinitions` still read it as their fallback until it is
  removed. An escalation closes any stage still `running`.
  *`ReviewLoopEveryStateTest.everyStateTheStoresCanHoldIsOneStepFromServedWaitingOwnedPassedOrEscalated`
  (every review status and error × stage statuses × pipeline as written or changed × spec
  status × serving run × recorded wait, walked on `LoopDecision.next`),
  `ReviewLoopEveryStateTest.aStateForEachStepTheLoopTakesEndsInTheRealStoresWhereTheDecisionSaidItWould`,
  `ReviewLoopEveryStateTest.theWalkEndsWhereTheRealStoresDoAcrossTheSpace` (every state of a
  spec the loop moves, and every thirteenth of the rest, through the real stores and a real
  stop),
  `aFailedGateWhoseFindingsAPersonResolvedBeforeTheFixLaunchedIsReviewedAgainNotFixed`,
  `aRunningReviewWhosePipelineLostItsStagesIsEscalatedSayingSo`,
  `aRunningReviewWhosePipelineLostItsStagesClosesTheStageItsReviewerRan`,
  `aStageThePipelineNoLongerHasWhenItsReviewerStopsEscalatesItsReviewSayingSo`,
  `aSecondStageThePipelineReplacedEscalatesItsReviewNamingTheStageItHolds`,
  `aStageThePipelineMadeAPersonsWhileItsReviewerRanEscalatesItsReview`,
  `anErroredReviewIsNotRetriedUnderAPipelineThatChangedSinceItsStagesWereWritten`,
  `aFixIsNotReReviewedUnderAPipelineThatChangedWhileItRan`,
  `aDescriptorThatCannotBeReadIsNeverTakenForAPipelineThatChanged`,
  `aBuildThatEndsWhileItsProjectsDescriptorCannotBeReadIsHandedToAPersonNotReplayed`,
  `ProjectDefinitionsTest.aDescriptorIsReplacedInOneMoveKeepingItsModeAndLeavingNothingBeside`,
  `ProjectReaderTest.aRowThatDoesNotParseIsUnreadableNamingTheProjectAndWhatTheParserSaid`,
  `ReviewLoopRecoveryTest.aPipelineRevisionInTheCatalogIsWhatTheNextLoopEventRunsUnderWithNothingElseRun`,
  `LoopFactsReaderTest.aProjectsPipelineIsNoneStagedOrUnreadable`,
  `CatalogNotificationsResolverTest.aProjectsNotificationsAreItsCatalogRowsAndARevisionIsWhatTheNextEventIsSentUnder`.*
- **C5. A review's end is one write.** The review's final status, its reason, its spec's
  status and the room line commit together or not at all (`ReviewStore.pass` and
  `escalate`); a person's sign-off is one write too (`approve`: the stage that waited on
  them, the review and its spec). The spec moves only if it is still the loop's
  (`SpecStore.moveFromLoop`), whoever ends the review. Events are published, and the sync
  triggered, after the commit. The room line is cut to what a room message holds, so a verdict or a reason of
  any length still lands; the reason is kept whole on the review.
  *`aPassWhoseWriteFailsHalfwayLeavesTheReviewRunningTheSpecInReviewAndTheRoomUntold`,
  `anEscalationWhoseWriteFailsHalfwayLeavesTheReviewAndItsSpecAsTheyWereAndTheRoomUntold`,
  `anEscalationWhoseReasonOutgrowsARoomMessageStillLandsWithItsReasonKeptWhole`,
  `aPassWhoseVerdictOutgrowsARoomMessageStillLands`,
  `ReviewStoreTest.aReviewsEndIsWrittenWithWhatEndsBesideIt`,
  `ReviewStoreTest.aReviewsEndWhoseCompanionWriteFailsLeavesTheReviewAsItWas`,
  `MessageStoreTest.aBodyTooLongForARoomIsCutBetweenCharactersAndSaysSo`,
  `ReviewOperationsTest.anApprovalWhoseSpecCannotBeMovedLeavesTheReviewAndItsStageAsTheyWere`.*
- **C6. A stop says only what happened.** A run is reported ended for a limit, or finalized
  as stopped by an operator, only after a signal was delivered to it and the container
  answered that the unit is gone. An unanswered question is never an answer, and there is
  one reading of whether an agent is there where nothing else says (`AgentPresence`):
  running, gone — one script in the container looked for the run's process and printed so,
  or incus lists the container stopped or absent — or unanswered. The stop that records an agent already gone, the sweep that finishes a run
  nobody reported, the watcher whose unit's manager went silent and the launch that releases
  a claim all act only on gone; unanswered fails the stop with nothing written and leaves
  the others to ask again.
  *`RunWatchTest.anAgentNoSignalReachedThatThenExitsOnItsOwnIsReportedAsThePlainExitItWas`,
  `RunWatchTest.aKillNothingAnsweredForKeepsThePidFileAndIsAskedAgainAtTheNextPoll`,
  `AgentSessionTest.aSigtermThatCouldNotBeDeliveredIsUnansweredAndNothingElseIsTouched`,
  `AgentSessionTest.aManagerThatDoesNotAnswerAfterTheSigtermIsUnansweredAndThePidFileIsKept`,
  `AgentSessionTest.aUnitStillActiveAfterBothSignalsSurvivedAndItsPidFileIsKept`,
  `AgentSessionTest.aUnitThatDiesInTheGraceIsEndedWithNoSigkillAndItsPidFileRemoved`,
  `AgentSessionTest.aContainerLostAfterThePidWasReadIsUnansweredAndThePidFileIsKept`,
  `AgentSessionTest.aPidFileThatCouldNotBeReadIsUnansweredNeverAnAgentTakenForGone`,
  `AgentSessionTest.aProcessAlreadyGoneWhenTheSignalIsSentWasNotEndedByIt`,
  `AgentSessionTest.aRealProcessThatIgnoresTheSigtermIsEndedByTheSigkill`,
  `AgentSessionTest.aContainerThatRunsNoCommandSaysNothingOfItsAgent`,
  `AgentSessionTest.commandsLostWhileAnAgentLivesNeverReadItAsGone`,
  `AgentSessionTest.anAnswerThatIsNeitherWordIsNoAnswer`,
  `AgentPresenceScriptTest.aPidFileNamingAProcessThatEndedIsAnAgentGone`,
  `AgentPresenceScriptTest.aUnitWhoseManagerDoesNotAnswerAndWhosePidFileNamesNothingIsNotKnownGone`,
  `AgentPresenceScriptTest.aUnitItsManagerSaysHasNoProcessIsGone`,
  `AgentWatchCommandTest.aRunWhoseContainerGivesNoAnswerAboutItsAgentIsWatched`,
  `AgentPresenceTest.aRunningContainerThatRanNoCommandLeavesTheQuestionOpen`,
  `AgentPresenceTest.aContainerThatIsStoppedRunsNoAgent`,
  `StopOperationsTest.anOperatorsStopTheManagerDoesNotAnswerFailsAndKeepsTheClaimForARetryToFinish`,
  `StopOperationsTest.aStopWhoseContainerGivesNoAnswerAboutTheAgentFailsWithNothingWritten`,
  `StopOperationsTest.aRetriedStopWhoseContainerStillGivesNoAnswerKeepsItsClaim`,
  `StopOperationsTest.oneLostLivenessCommandNeverRecordsALiveAgentAsGone`,
  `StopOperationsTest.aHaltTheAgentSurvivedRestoresTheSpecAndLeavesTheRunReconcilable`,
  `RunReservationTest.anAgentAContainerThatRunsNoCommandCannotSpeakForIsTreatedAsLive`,
  `RunLauncherTest.aLostLaunchWhoseAgentSurvivedItsHaltIsNeverSaidTornDown`,
  `RunLauncherTest.aRunFinishedUnderItsLaunchInAContainerThatGivesNoAnswerIsNotTakenForOneThatEndedItself`.*
- **C7. Every watcher ends** when its run's authoritative stop or cancel is published, by
  anyone.
  *`RunWatchTest.aWatcherPollingASilentManagerEndsWhenItsRunsStopIsPublishedByTheReconciler`,
  `RunWatchTest.aWatcherEndsWhenItsRunsCancelIsPublished`,
  `RunWatchTest.theAgentsOwnTurnEndStopAndAnotherRunsEndNeverEndTheWatch`.*
- **C8. A run's wall clock comes from its run row.** The spawner passes the row's
  `started_at` to the watcher, first and at every re-arm; nothing in the container, which the
  agent can write, moves it, and a session file the agent wrote nonsense into still reads.
  *`ReviewLanesTest.aRunIsHeldToItsTimeLimitFromItsRowsStartWhateverItsSessionFileIsRewrittenToSay`,
  `ReviewLanesTest.aDaemonRestartWhileAReviewerRunsFailsNothingAndReArmsAWatcherThatDiedWithIt`,
  `AgentWatchCommandTest.theWatchHoldsItsRunToTheStartItWasGivenWhateverTheAgentsSessionFileSays`,
  `AgentWatchCommandTest.refusesToStartWithoutTheRunRowsStart`,
  `AgentSessionTest.aSessionFileTheAgentWroteNonsenseIntoNeverStopsItsStatusBeingRead`,
  `WatcherSpawnerTest.watchCommandForRunAddressesTheRunAndItsRecordedUnit`.*
- **C9. A run with a tool call in flight is not stalled** when a wall-clock limit bounds it.
  The watcher counts the calls it heard start. A call is over at its own finish, or when the
  main agent's batch resolves: Claude Code fires no finish for a call it denies, and a finish
  can be lost on its way to the daemon, so the hook for a resolved batch (`PostToolBatch`,
  posted as `agent_tool_finished` with `batch: true`, the main agent's only) sets the count
  to none. A call that began where the watch could not hear it — before a re-armed watch
  started, or while its feed was down — is not counted and gets one whole stall window from
  then. Codex has no batch hook: its count rests on each call's own finish.
  *`RunWatchTest.aToolCallLongerThanTheStallWindowIsWorkNotAStall`,
  `RunWatchTest.aToolCallThatNeverFinishesIsEndedAtTheWallClockLimit`,
  `RunWatchTest.aToolCallThatNeverFinishesIsAStallWhenNoWallClockLimitBoundsTheRun`,
  `RunWatchTest.oneOfTwoToolCallsStillInFlightIsNotAStall`,
  `RunWatchTest.aRunThatCallsNoToolForItsStallWindowIsKilledForTheStall`,
  `RunWatchTest.aToolCallInFlightIsStillInFlightAfterTheFeedOpensAgain`,
  `RunWatchTest.aToolCallTheCliDeniedIsOverWhenItsBatchResolvesAndTheSilenceAfterItIsAStall`,
  `RunWatchTest.aBatchThatResolvesSaysNothingOfACallTheNextBatchStarts`,
  `RunWatchTest.aFinishNoStartWasHeardForNeverHidesACallThatStartsAfterIt`,
  `RunWatchTest.whatTheAgentLogsWhileACallIsInFlightTakesNothingOffTheCount`,
  `SailEventHelperScriptTest.theMainAgentsBatchEndIsPostedAsAToolFinishThatMarksTheBatch`,
  `SailEventHelperScriptTest.aSubagentsBatchEndIsNotPosted`,
  `HarnessHooksTest.aToolCallThatFailedIsToldFinishedAsOneThatSucceededIs`,
  `HarnessHooksTest.aBatchThatResolvedIsToldThroughItsOwnHook`,
  `ReviewAgentLoopIT.aRealToolCallLongerThanTheStallWindowIsNotKilledAsAStall`.*
- **C10. Main says what the driving box said, and a replayed stop is not said again.** An
  escalation's reason rides the synced review row (`reviews.error`) into main's
  `review_escalated`; a stop the reconciler publishes for a run whose stop was already said
  carries `replay: true`, which the pipeline routes and Slack and webhooks skip. A run's
  first stop carries none, however long ago its row ended. A stage closed for an error —
  its reviewer could not run, or its review was escalated under it — is told on main by its
  review's error or escalation, as on the driving box, never as a stage that failed its
  gate.
  *`anEscalatedReviewsSyncedRowCarriesItsReasonAndMainSaysWhatThisBoxSaid`,
  `aReplayedStopOfABuildThatHadAlreadyStoppedMovesTheLoopAndIsNotSaidAgain`,
  `theFirstStopOfARunThatDiedUnwatchedIsSaidThoughTheReconcilerPublishesIt`,
  `theOnlyStopOfARunFinishedInPlaceWithNoneIsSaidHoweverLongAgoItsRowEnded`,
  `SyncTransitionEventsTest.anEscalatedReviewBecomesReviewEscalatedSayingWhyAsItsRowRecordsIt`,
  `SyncTransitionEventsTest.aStageClosedForAnErrorIsToldByItsReviewNotAsAFailedGate`,
  `SlackReactorTest.aStopTheReconcilerReplaysIsNotSaidAgainAndItsFirstStopIs`,
  `WebhookReactorTest.aStopTheReconcilerReplaysIsNotNotifiedAgainAndItsFirstStopIs`.*
- **C11. `generate` then parse returns the configuration it was given.** The `agent` block
  `sail project init` writes is `SailYaml.Agent.toMap()`, the one writer. A review limit left
  at its default is not written, so rewriting a project's `sail.yaml` never pins it to
  today's defaults; an empty list of notification events is no list; and written YAML
  indents a list's items under their key and never folds a long value.
  *`SailYamlGeneratorTest.anAgentWithEveryFieldSetComesBackFromGenerateThenParseAsItWent`,
  `ReviewPipelineConfigTest.aLimitTheProjectLeftAtItsDefaultIsNotWrittenIntoItsBlock`,
  `NotificationsTest.anEmptyListOfEventsIsNoListOfEventsSoItIsWrittenBackAsItWasRead`,
  `YamlUtilTest.aListsItemsSitUnderTheirKeyAndALongValueStaysOnItsLine`.*
- **C12. One fix agent per review.** No fix agent starts for a review while a process started
  for that review by a server older than 0.46.4 is alive: such a server exported the review
  id as `SAIL_RUN_ID`, and `LegacyFixAgent` kills every process carrying exactly that entry
  after the fix run's claim lands and before its unit starts.
  *`ReviewLanesTest.aFixAgentIsLaunchedOnlyAfterAnyAgentAnOlderServerLeftForItsReviewIsKilled`,
  `LegacyFixAgentTest.aProcessAnOlderServerStartedForTheReviewIsKilled`,
  `LegacyFixAgentTest.aProcessOfAnotherRunIsLeftUntouched`,
  `ReviewLaneLauncherTest.aFixLaunchWhoseLegacyCheckTheContainerWillNotRunFailsAndLeavesNoRunRunning`,
  `ReviewAgentLoopIT.aFixAgentAnOlderServerLeftRunningForTheReviewIsKilledBeforeTheFixRunStarts`.*

A stop the loop is not waiting on — a duplicate, a replay, the stop of a run a newer one has
replaced, including a re-dispatched build — never repeats a step. While a run still serves
the spec's latest review it changes nothing; otherwise the loop goes on from what the
review's rows say is owed, under the project's pipeline as it is then — and a review whose
stages are no longer that pipeline's, or whose pipeline cannot be read, is escalated
saying so before any step is taken, a retry and the iteration after a fix included: an
errored review is retried as the same iteration within
`MAX_ERRORED_RETRIES`, a `running` one continues from its stages (a stage is judged only on
the log of the reviewer that was live when the stage started), a gate-failed one that never
got its fix agent gets it — or, with nothing left open, is reviewed again — and one that
recorded a wait takes the step the gate refused it, once the run it waits on has ended.
That one path is both the loop's retry and its crash recovery; the reconciler's replays
drive it. A review whose reviewer or fix agent has ended is owed
that run's own stop and nothing else: no other stop relaunches over a verdict about to be
heard. The loop acts only on a spec that is still `in_progress` or `review` — a cancelled
spec gets no fix agent — and counts only the runs this box executed as serving a review, so
a run row another box pushed that names this box's review never holds it; unasked, a box
moves only the loops whose newest run it executed.

A reviewer and a fix agent reserve their spec's repos through the dispatch gate, like a
build, so neither starts beside a live build of its own spec, a full chat turn or another
spec's run over the same repos. A claim refused for a run in the way is not an error, and it
is known where it happens: `RunReservation.reserveForReview` answers `Held`, naming the run
that holds the claim, and the launch is `Deferred` to that run. Nothing is started, no
attempt is spent, nothing of the stage is written, and the review records the run it waits
on (C1). Every stop in the project takes the step of each review whose recorded holder has
ended, since a stop is what frees a claim; a refusal by another run records that run
instead, and tells the room once. A container
held by a snapshot restore is an error like any other: a reviewer's is retried within the
errored budget, a fix agent's fails its iteration. The pipeline finishes the run that stopped before it
reserves the one that follows. A launch that reports failure after its agent started — the
launch command or the status read failing over a live unit — leaves a run serving the
review, and the loop waits for that run's stop rather than escalate over a working agent; a
launch whose row was finished under it (an operator's cancel, or a sweep that outran a
launch slower than its grace) tears its agent down unless the agent has ended. The
watcher addresses a stop to the run named on its own command line, never to one read back
from the container, and a run that died before its watcher attached ends by a stop carrying
the exit code its unit still holds.

An operator's `sail agent stop` on a reviewer or a fix agent is a person's decision about
the loop, so the review escalates rather than retrying over it. The stop's claim marks the
run the operator's (`runs.stop_source`) in the transaction that claims it, and the mark
outlives the claim, so every stop of that run — the operator's cancel, the watcher's stop of
the unit that died under the halt, a replay of either — escalates the review in whatever
order they are heard, also when the stop landed while the run was still launching, and none
reads as the run's own end. A build an operator stopped starts no review. The stop is
finalized on what the halt itself says (`AgentSession.Halt`, the one verification of a
halt): `Ended` finalizes it; `Survived` gives the run back, since its agent is verified
still running; `Unanswered`, or a halt that threw, fails the stop and keeps the claim for the
operator's retry or the reconciler's interrupted-stop pass, so neither silence nor a signal
that landed is ever mistaken for the run ending on its own. A stop the reconciler replays carries the reason the watcher recorded
when it killed the run. A fix agent is told to verify locally, commit and push, and not to
watch CI: the re-review judges the branch, and the pull request shows its checks to whoever
merges. A watcher re-armed after its own death takes the lane's limits as the project sets
them at that moment, and is handed the run row's `started_at`, the anchor its first watcher
had.

**The loop machine.** From a build's stop to `awaiting_merge`, one pure function decides every
step of the loop and one class carries each step out. Each part has one job, all in
`ai.singlr.sail.api`:

| Class | Its one job |
|---|---|
| `ReviewPipelineController` | routes: an authoritative stop becomes a trigger for one spec's loop |
| `LoopFactsReader` | reads the stores into `LoopFacts`, the only reader for a decision |
| `LoopDecision.next(LoopFacts, LoopTrigger)` | decides the one `LoopStep`; no store, clock, bus or container |
| `LoopSteps` | acts: one method a step, every write of a review, a spec's status or a wait, and every launch |
| `StageVerdicts` | reads a reviewer's verdict from its run's log, writes what it ruled and closes the stage it judged |
| `LoopNarrator` | says what happened, on the bus and in the room (`ReviewNarration` words it) |
| `StrandedReviewRescue` | the reconciler's rescue of a review owed something nothing is coming to give it |

`LoopFacts` is what a decision reads of one spec. Its `Rows` are what the spec's rows say: its
status; its latest review that is not superseded, with that review's stage rows in order; the
runs this box executed that serve the review, newest first; the spec's newest loop run; and
whether the run the review waits on has ended. `owed()`, `due()` (a wait gives way to the step
it holds once its holder has ended), `awaited(run, statuses)`, `stageReviewedBy(run)` and
`drivenHere()` are pure methods over the rows, and the rows are all the reconciler's rescue
reads. Beside them `LoopFacts` holds the pipeline reading (`Staged` with the project's roster
reviewer, `None`, or `Unreadable(why)`), the count of errored attempts of the review's
iteration, and, only when a fix is due, the review's open findings and the findings its first
failed stage holds, with their ages: no decision on any other review weighs a finding, and
none is read for one. The pipeline is resolved once per event, at the first read of
one of the project's specs, and serves every decision and step of that event.

A trigger is what happened; it carries no decision:

| Trigger | When |
|---|---|
| `BuildEnded(runId, exitCode)` | an authoritative stop of a build or ad-hoc run, or of a run with no known lane; `runId` only when this box holds the run's row |
| `ReviewerEnded(run, failure)` | an authoritative stop of a reviewer; `failure` is the guardrail it was ended for (`killed: time limit (45m)`) or its non-zero exit (`failed: exit 1`), else empty |
| `FixEnded(run, failure)` | the same, for a fix agent |
| `OperatorStopped(run)` | `agent_cancelled`, or a stop of a run an operator's stop claimed |
| `Freed` | a stop freed whatever its run held; raised, after the stop's own loop is driven, for every spec of the project in `review` or `in_progress` |
| `GoOn` | a gate just failed: the loop goes on from what the rows owe |
| `ReviewRunning` | a step left the review `running` with its spec in `review` |
| `StageJudged(reviewId, stage, outcome)` | a step judged a stage of that review: `Passed`, `GateFailed`, `Errored(why)` |
| `ReviewerNotStarted(reviewId, stage, why)` / `FixNotStarted(reviewId, why)` | a launch for that review (building its prompt or task included) threw and no live run serves it |
| `FixCommitted(run)` / `FixNotCommitted(run, why)` | a step tried to commit and push what fix agent `run` left |

The first four and `Freed` are raised by the router, the rest only by `LoopSteps`. A step is
what to do, and a stage is named by its place in the pipeline, which is its row's place in the
review:

| Step | What `LoopSteps` does | Follow-up trigger |
|---|---|---|
| `Nothing` | nothing | none |
| `SayBuildFailed(exitCode)` | publishes `agent_failed` with `exit <code>` | none |
| `ParkForAPerson` | moves the spec to `review` | none |
| `StartReview(iteration)` | writes the review `running`, then moves the spec to `review` | `ReviewRunning` |
| `EscalateNew(iteration, reason)` | writes the review and escalates it in the next write | none |
| `Resume(review)` | sets a legacy `pending` review `running`, then moves the spec to `review` (`SpecStore.moveFromLoop`, also when it is there already) | `ReviewRunning` |
| `LaunchReviewer(review, stage, agent)` | completes the review's stage rows, launches through `ReviewLanes`; the stage turns `running` when the claim lands; once the run serves, clears any recorded wait and publishes `review_stage_started`; a refused claim records the wait and its room line in one write | `ReviewerNotStarted` only as defined above; none when started, when refused, or when it threw with a live run serving the review (treated as started) |
| `AwaitAPerson(review, stage)` | completes the stage rows, opens the human stage, publishes `review_stage_started`, tells the room | none |
| `Pass(review)` | `ReviewStore.pass` with the spec's move to `awaiting_merge` and the room verdict, then publishes `review_completed` | none |
| `ReadVerdict(review, stage, run, error)` | with an `error`, closes the stage `failed` for it; else `StageVerdicts.read`: reads the run's log, reconciles findings, closes the stage `passed` or `failed`, publishes `review_stage_passed` or `review_stage_failed` | `StageJudged` |
| `ErrorReview(review, stage, why, closed)` | unless `closed`, completes the stage rows and closes the stage `failed` with the reason; fails the review with the error, publishes `review_errored` | none |
| `FailGate(review)` | marks the review failed, posts the verdict to the room | `GoOn` |
| `LaunchFix(review, findings)` | launches through `ReviewLanes`; a refused claim records the wait and its room line in one write; once the run serves, clears any recorded wait, moves the spec to `in_progress` and publishes `review_iteration_started` | `FixNotStarted`, as for `LaunchReviewer` |
| `CommitFixLeftovers(review, run)` | `ReviewLanes.ensureCommitted`; publishes the guardrail event when it committed something | `FixCommitted` or `FixNotCommitted` |
| `FailFix(review, why)` | escalates with `ReviewNarration.fixFailed`, then publishes `review_iteration_failed` and `review_escalated` | none |
| `Escalate(review, reason)` | `ReviewStore.escalate` with the spec's move to `review` and the room line, then publishes `review_escalated` | none |

The decision is the table below, tried in order; the first row that matches wins. "Awaited in
X" means: the run is this box's, no newer run of the spec's loop exists, the spec is
`in_progress` or `review`, and the review the run names is the spec's latest and its status is
in X. "The loop's" means the spec is `in_progress` or `review`. "Due" is what the rows owe, a
wait counting as the step it holds once its holder has ended. Rows marked **P** first ask
`facts.unfit()`: when it is present the step is `Escalate(review, that reason)`
(`ReviewNarration.noStages`, `pipelineUnreadable(why)`, `pipelineChanged(stage)`). A
follow-up trigger a slow step raises is about the review that step acted on and no other:
`StageJudged`, `ReviewerNotStarted` and `FixNotStarted` name it, `FixCommitted` and
`FixNotCommitted` name the fix run that served it, and when that review is no longer the
spec's latest, because a re-dispatch superseded or replaced it while the step ran, the step is
`Nothing`. `ReviewRunning` and `GoOn` follow a write with nothing slow before it, name no
review and go on with the latest.

| # | Trigger | When | Step |
|---|---|---|---|
| A1 | `BuildEnded` | the spec is missing or not the loop's (a stop that names no spec is routed to no trigger) | `Nothing` |
| A2 | | the stop names a run this box holds, and that run is not the newest build, review or fix run of its spec | as `GoOn` |
| A3 | | exit code present and non-zero | `SayBuildFailed` |
| A4 | | the spec has a review | as `GoOn` |
| A5 | | pipeline `Staged` | `StartReview(1)` |
| A6 | | pipeline `None` | `ParkForAPerson` |
| A7 | | pipeline `Unreadable` | `EscalateNew(1, pipelineUnreadable)` |
| W1 | `Freed` | this box did not execute the spec's newest loop run | `Nothing` |
| W2 | | the rows owe anything but a recorded wait | `Nothing` |
| W3 | | otherwise | as `GoOn` |
| G0 | `GoOn` | the spec is not the loop's | `Nothing` |
| G1 | | due `Nothing` or `Stop` | `Nothing` |
| G2 | | due `Waiting`: its holder has not ended | `Nothing` |
| G4 | | due `Retry`, errored attempts of the iteration ≥ `MAX_ERRORED_RETRIES` | `Escalate(erroredOut)` |
| G5 | | due `Retry` (**P**) | `StartReview(same iteration)` |
| G6 | | due `Advance` (**P**) | `Resume` |
| G11 | | due `Fix` (**P**): a finding of the failed stage that its gate blocks has age ≥ `maxFindingAge` | `Escalate(stuckOn)` |
| G12 | | due `Fix` (**P**): iteration ≥ `maxIterations` | `Escalate(iterationsExhausted)` |
| G13 | | due `Fix` (**P**): no open finding | `StartReview(iteration + 1)` |
| G14 | | due `Fix` (**P**) | `LaunchFix(open findings)` |
| G6b | `ReviewRunning` | the spec has no review: a re-dispatch superseded it | `Nothing` |
| G6c | | (**P**) a stage has yet to pass, and the spec is no longer the loop's: someone took it while the step before ran | `Nothing` |
| G7 | | (**P**) the stage the review is in is human and not running | `AwaitAPerson` |
| G8 | | (**P**) the stage the review is in is human and running | `Nothing` |
| G9 | | (**P**) the stage the review is in is an agent stage and no reviewer resolves for it | `ErrorReview(stage, "no reviewer agent resolved; set stages[].agent or agent.install in sail.yaml")` |
| G9b | | (**P**) the stage the review is in is an agent stage | `LaunchReviewer` |
| G10 | | (**P**) every configured stage has a row and it is `passed` | `Pass` |
| R1 | `ReviewerEnded` | not awaited in `pending`, `running` | as `GoOn` |
| R2 | | (**P**) no running agent stage was started while this run was live | as `GoOn` |
| R3 | | (**P**) `failure` present | `ReadVerdict` with the error `"reviewer " + failure` |
| R4 | | (**P**) otherwise | `ReadVerdict` |
| J0 | `StageJudged` | the review judged is no longer the spec's latest | `Nothing` |
| J1 | | `Passed` | rows G6c–G10 |
| J2 | | `GateFailed` | `FailGate` |
| J3 | | `Errored(why)` | `ErrorReview(stage, why)`, its stage already closed |
| F1 | `FixEnded` | not awaited in `failed`, or the review failed by error | as `GoOn` |
| F2 | | `failure` present | `FailFix("fix agent " + failure)` |
| F3 | | otherwise | `CommitFixLeftovers` |
| F4 | `FixCommitted(run)` | `run` no longer awaited in `failed`, whether or not anything was committed | `Nothing` |
| F5 | | (**P**) otherwise | `StartReview(iteration + 1)` |
| F6 | `FixNotCommitted(run, why)` | `run` no longer awaited in `failed` | `Nothing` |
| F7 | | otherwise | `FailFix("fix agent's work could not be committed: " + why)` |
| L0 | `ReviewerNotStarted`, `FixNotStarted` | the review the launch was for is no longer the spec's latest | `Nothing` |
| L1 | `ReviewerNotStarted` | otherwise | `ErrorReview(stage, "reviewer could not start: " + why)` |
| L2 | `FixNotStarted` | otherwise | `FailFix("fix agent could not start: " + why)` |
| O1 | `OperatorStopped` | the run's row is not terminal | `Nothing` |
| O2 | | not awaited in `pending`, `running`, `failed` | `Nothing` |
| O3 | | otherwise | `Escalate(stoppedByAnOperator(lane))` |

"The stage the review is in" is the first configured stage whose row is missing or not
`passed`; its kind comes from the configuration, and a missing row counts as not running: a
review just written has no stage rows, and is in its first stage. The spec's move to `review`
is its own step, `Resume`, which is why a review that was just started, and a stage that just
passed, go on from their stages without a second move.

The controller routes one event this way:

1. it ignores an event that is not authoritative;
2. for `agent_session_stopped` only, and only for a run this box owns, it finishes the run's
   row before anything else, whatever its lane; the row read back after that write is the one
   judged. `agent_cancelled` skips this;
3. it maps the event to a trigger by the lane of the run's row, else of the stop's `run_role`.
   Room lanes, retired roles, a review or fix stop naming no run this box holds, an
   `agent_cancelled` naming none and a stop naming no spec map to no trigger. `BuildEnded` is
   for build, ad-hoc, and a role that is null or unknown and not retired. A run an operator's
   stop claimed maps to `OperatorStopped` whatever its lane;
4. it drives: read facts, decide, act, and repeat with the follow-up trigger until a step
   returns none. More than 8 steps for one event throws `IllegalStateException` naming the
   spec and the steps taken;
5. then, always, even when steps 2–4 threw, it raises `Freed` for every spec of the project in
   `review`, then `in_progress`, and drives each the same way. A failure here for one spec is
   logged, never published, never replaces the routed event's failure, and never costs the
   specs after it;
6. on a failure of steps 2–4, it publishes `review_pipeline_error`.

The sync trigger tells main of what a box wrote, at these places and no others:

| Place | When |
|---|---|
| routing, after finishing the stopped run's row | when it wrote |
| every spec move outside a review-end write | when it moved |
| `StartReview`, `EscalateNew` | after the review row is written |
| the first step that needs stage rows | after creating the missing rows |
| `Pass`, `Escalate`, `FailFix` | after the write, before the events |
| `AwaitAPerson` | last, after the event and the room line |
| a launch that was refused | after the wait is recorded |
| a launch whose run serves | after the wait is cleared |
| `ReadVerdict` | after the stage is closed, before the outcome is acted on |
| `ErrorReview` | after the review is failed, before the event |
| every room line posted outside a review-end write (`FailGate`'s verdict, a person's stage, a ruling set aside) | after it is posted; `FailGate`'s status write rides this one |
| `Resume` of a legacy `pending` review | with the spec's move, when it moved |

*`LoopDecisionTest.theLoopTakesTheStepItsTableNames` (one case a row of the decision table,
on facts built in memory), `LoopStepsTest` (each step leaves the rows in the state its name
says and tells main where the table above says:
`startReviewWritesTheIterationRunningAndMovesItsSpecToReview`,
`launchReviewerRefusedItsClaimRecordsTheWaitAndItsRoomLineAndStartsNoStage`,
`launchReviewerWhoseLaunchFailsAfterItsAgentStartedWaitsForThatAgentsStop`,
`passEndsTheReviewParksItsSpecAndSaysTheVerdictInOneWrite`,
`escalateEndsTheReviewClosesItsRunningStageAndMovesItsSpecInOneWrite` and one for every other
step), `LoopFactsReaderTest.findingsAreReadOnlyForAReviewAFixIsDueFor`,
`ReviewLoopRecoveryTest.aKilledFixAgentEscalatesItsReviewThoughAFindingOfItCannotBeRead`,
`ReviewLoopRecoveryTest.anUnheardFixStopIsReplayedThoughAFindingOfItsReviewCannotBeRead`,
`ReviewLoopRecoveryTest.aStopWhoseOwnLoopCannotBeDrivenStillWakesTheReviewItFreed`,
`ReviewLoopRecoveryTest.aStopThatFreesTheRepoTwoReviewsWaitForWakesTheSpecInReviewBeforeTheOneInProgress`,
`ReviewLoopRecoveryTest.theStoppedRunIsFinishedAndMainToldOfItOnlyWhenTheRouterWroteIt`,
`ReviewPipelineControllerTest.aLoopThatWouldTakeMoreStepsThanItsTableAllowsIsAFaultNamingTheStepsItTook`,
`ReviewLoopRecoveryTest.aSpecWhoseRowsCannotBeReadCostsTheWaitingReviewsOfItsProjectNothing`,
`LoopNarratorTest`.*

**Recovery without losing work.** The git branch is the durable record: every coding agent
(build and fix) commits before it stops, and neither a guardrail stop nor an escalation ever
discards it. So an FDE always recovers by returning to the branch. The loop recovers itself
from a daemon restart or a dead watcher (P6): the missed-stop sweep publishes the stop of
any loop run that ended with no watcher left to report it, and replays the newest loop stop
for a review that is owed something nothing is coming to give it, and the pipeline goes on
from the review's rows. Every such rescue is one-shot, keyed by what is owed: a spec in
`review` with no review at all, by the spec (the dropped kickoff); and, by the review, an
errored review (the retry); a reviewer or fix agent that ended with nothing coming
of it; a review `running` with no run serving it, no person to wait on and no wait recorded
(per stage); a failed gate with no fix agent and no wait recorded; and a recorded wait whose
holder has ended, per holder (C2). A wait is rescued only once the run it names is terminal
for longer than the launch grace, or gone: while that run lives its own stop is what wakes
the review, and a replay would only be refused. Nothing is sampled and nothing is retried:
a replay whose launch is refused again records the new holder, and that new wait is rescued
in its turn. A replay of a stop already said carries `replay: true` and is not narrated
again (C10). When a spec is stuck: a guardrail-killed or
failed dispatch leaves the work committed, so `sail spec dispatch --restart` resumes on the
branch; an escalated review parks in `review` with its findings (in the review store), each
reviewer's and fix agent's own run log, and every fix commit intact, so the FDE reads it with
`sail agent review <project>` plus `sail agent log <project> --review` (or `--fix`), then
resolves with `sail spec update <id> --status done` (accept the work as-is) or `--status
pending` (send it back to be re-dispatched). Nothing is deleted along the way.

**Events:** an in-process `EventBus` (lock-light, with bounded per-subscriber queues that
are lossy by design so publishers never block) fans out to the SSE stream and to startup
reactors: audit persistence, the webhook reactor, and the spec lifecycle reactor, which
advances a spec from `in_progress` to `review` when its agent session ends. A
`board_updated` event after a sync that changed the board surfaces an updates-available
banner in the CLI and in GUI clients.

**What a client may publish.** The bus's subscribers act on every event as this box's
machinery (`SYSTEM`), which the write rules let through, so the doors are where an event is
held to what its sender may drive (A3). `POST /v1/events` and the in-container socket hand
every event to one `EventDoor`, which asks one rule in sail-core, `EventAuthority`, as the
bound actor. The rule is deny by default: a type it does not list is the server's own,
emitted after the write it describes (`spec_status_changed`, the engagement and snapshot
restore/delete types, presence, log chunks, pty facts, `agent_session_completed`,
`guardrail_triggered`, `review_pipeline_error`, `spec_stranded`, the sync health pair), and
is refused from every client, an admin included.

Two kinds of sender speak about work. This box's FDE, whom the host token acts as, reports
what this box did and observed: the host CLI and the watcher. Anyone else only *retells*
what another box did, marking the event `source: sync`: that is main's sync server relaying a
node's transitions, and it speaks as the FDE who pushed them, whose session the gateway hands
it. A retold event starts no review, sends no webhook and finishes no run here. So a member is
never taken at their word for what this box observed, and a run another box pushed is never a
key to the spec it names: its pusher wrote that row, and only the spec's own owner speaks for
the spec. Each listed type has one rule:

| Rule | Who may publish | Types | Published by |
|---|---|---|---|
| `DRIVES` | an admin; this box's FDE, for a run this box executed (`RunRow.ownedBy`) or a spec or room it owns; retold, an actor who acts for the owner of the spec (`Ownership.ownerOf`) or room (`RoomStore.owners`) it names or that the run it names works — a run that works neither is its own owners' (`RunAuthority.owners`). Never a run's own principal | `agent_session_stopped`, `agent_cancelled`, `agent_failed`, `spec_dispatched`, `spec_restarted`, `review_iteration_started`, `review_stage_started`, `review_stage_passed`, `review_stage_failed`, `review_completed`, `review_errored`, `review_escalated` | the watcher (`agent_session_stopped`); the host CLI's dispatch (`spec_dispatched`, `spec_restarted`) and stop (`agent_cancelled`); main's sync bridge, retelling what a node's synced transition tells (`SyncTransitionEvents`) |
| `NARRATES` | an admin; this box's FDE for a run this box executed; a run's own principal for that run alone | `agent_session_started`, `agent_stop_nudged`, `agent_tool_started`, `agent_tool_finished` | the in-container hooks over the socket; the host CLI's dispatch and `sail run` (`agent_session_started`) |
| `ANNOUNCES` | an admin; this box's FDE; retold, an actor who acts for an owner of the conversation the message is in (`RoomStore.owners`), who may post there | `spec_message_posted` | a node's sync, for each message it pulled |
| `BOX` | an admin, or this box's FDE | `snapshot_created`, `board_updated` | the host CLI's dispatch (`snapshot_created`); a node's sync, for a round that changed the board |

The host token acts as the box's FDE: an admin on main or a standalone box, the synced
roster role on a node. The socket's event route takes a run credential only; the box
credential has no run to speak for. A member's own session on main's gateway is not the
box's FDE, so lifecycle events from a `sail` command run through it are refused and the
missed-stop reconciler finishes its run.

Holding a relayed run to the owner of the spec it works, never to the FDE who pushed it,
has one cost: a spec an admin reassigns while another FDE's box is still running it loses
that run's relayed stop on main, since main cannot tell that run from one its pusher wrote
against a spec they never owned. The run finishes on its own box; main records nothing of
it, and since that box's move of the spec to review is denied on the sync lane for the same
reason, the spec stays `in_progress` on both boxes until an admin moves it. A run create rule
on the sync lane (`sail-journal-authority`) is where a pushed run comes to prove it works a
spec its owner held when it was reserved.

What each type drives, which is why its sender is checked: `SpecLifecycleReactor` moves a spec
to `review` on `agent_session_stopped`; `ReviewPipelineController` starts a review on one this
box observed; `RunTracker` finishes the run it names, revoking its credential and releasing
its repos, on `agent_session_stopped` and `agent_session_completed`; `RoomWakeReactor`
launches a wake on `spec_message_posted` and runs the room commit guard on a room run's stop;
`RunActivityStamper` and the watcher's stall timer read the tool and log-chunk types as
progress; `SlackReactor` and `WebhookReactor` notify on the dispatch, stop, failure, nudge,
snapshot, guardrail, sync-health and review types; and the audit persisters record every
event, where `MissedStopReconciler` reads `agent_session_stopped`, `agent_failed`,
`review_stage_started`, `review_stage_failed`, `review_errored` and `review_escalated` as
evidence that a stop was observed and acted on.

The door then stamps what the server knows, so nothing a subscriber acts on or the log records
rests on the sender's word. A run this box holds decides its event's conversation, so a sender
cannot pair a run with another spec; a sender may leave the conversation out, as the hooks of
a run that works only a room do. The spec or room, worked by the run or named directly, decides
the project. `ts` is the server's clock. `publisher` (handle, role, lane) is the authenticated
actor, stored with the event (`events.publisher`) and served on every read; an event the
server emitted itself carries none, and `agent` is a label, never proof of who published. A
`spec_message_posted` announcement names a message and nothing else: sync writes messages
straight to the database and announces each, and the door decides it on the conversation that
message is in, never on a run named beside it, and rebuilds the event whole from the stored row, as the messages route emits it
for a local post, refusing a message this box does not hold. A refusal is a 403 through
`Refusals`, naming the event type and what the sender may not drive.

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
  unknown or blank role fails safe to viewer. Attribution (`created_by`, `updated_by`) is the
  bound actor (below), never client input.
- **One identity per FDE, whichever door.** One rule, `RoleRule` (`ai.singlr.sail.identity`),
  decides the role a credential that names an FDE acts with: this box's operator on main or a
  standalone box, the FDE its sync handle names, is admin, because they hold root on it; every
  other FDE acts with the role main's roster gives it; the credential's own role caps both; and
  an FDE the roster marks `disabled`, or one it does not know, is refused. An API token, a
  passkey session, the box credential on the socket, a run's credential, the host CLI
  (`CliOperator`), a sync session, the SSH gateway and the terminal all decide through it, so
  demoting or disabling an FDE on main takes effect at every door on every box once the roster
  syncs. A node mirrors main's roster each round: an FDE main no longer lists is disabled there
  too (`FdeStore.disableUnlisted`). Main itself never syncs against another box, whatever it is
  asked (`SyncOperations.resolveMain`): a round adopts the other side's versions and roster.
  - **The host CLI is the box's FDE.** Its API token (`HostToken`, named `admin`) names no FDE
    of its own: when it is used, `TokenAuth` resolves it to the FDE the box's sync handle names
    at that moment, so a host CLI write names that FDE and acts with the role `RoleRule` gives
    it, however the handle or the roster changes after minting. Nothing binds it and nothing
    needs migrating.
  - **A machine token** (any other token that names no FDE) acts as no FDE, so it records no
    author, with the role of the box's FDE capped by its minted role (`RoleRule.roleOfUnbound`):
    on a node it never acts beyond the FDE whose box it lives on. On main or a standalone box
    with no sync handle there is no FDE to be, and a machine token acts with its minted role.
  - **Before the roster arrives.** On a node whose roster does not know its own FDE yet, or
    that names no FDE, a token that acts for the box's FDE is answered `409` naming the fix
    (`sudo sail sync`, or setting the sync handle), and the host CLI, the box credential and
    the terminal are refused: nothing acts as an FDE the box cannot place
    (`CliOperator.unplaced`).
  - **A run's credential** acts as the run's principal, with the role `RoleRule` gives the FDE
    the run acts for, capped by its lane (`member` for an agent run, `viewer` for a room run).
    A run whose FDE is disabled is refused at the socket.
- **One owner rule.** `Ownership.ownerOf(assignee, createdBy)` is the only derivation of whose
  a spec or room is: its assignee, or its creator while it is unassigned. The spec rule, the
  erase rule, the room rule, the room wake and membership (whose box serves a room's agents: the
  room's own owner, one box) and the review rule read it. Three rules derive from it and are each
  implemented once:
  - **Who owns a room** (`RoomStore.ownerOf`): a spec's identity room is its spec's owner's, as
    this box last knew the spec, live or deleted (`SpecStore.lastKnown`); any other room is its
    own row's. A room's settings (roster, wake, title, assignee) are its owner's.
  - **Who owns a conversation** (`RoomStore.owners`): the room's owner, and the owner of every
    spec born in it. The posting rule (`PostingRule`, room lane included), the terminal door and
    main's decision on a synced message all read it, so the same post is admitted or refused
    alike on every box. Owning a spec born in a room is a voice there, never its settings.
  - **Who owns a run** (`RunAuthority.owners`): the FDE the run acts for, and its spec's owner
    (for a spec-less run, the box that ran it). Its owners or an admin read its log, stop it and
    change it; a run's own FDE keeps it after its spec is reassigned.

  A spec left without an assignee stays unassigned, and so does its identity room; any member
  may claim it by assigning it to themselves, an agent for the FDE it acts for, and dispatch
  refuses it until then. Owning a spec gives a voice in its conversation, so a spec born in a
  room is claimed, or created with an assignee, only by one who may already post there and only
  for themselves (the claim rule, in `SpecAuthority`); giving it to anyone else is an admin's act. A claim
  never opens someone else's room. An assignee is an FDE handle, never an agent type or a run's principal
  (`RunStore.isPrincipalHandle`). `created_by` is the acting FDE (`Actor.actingFde`: the
  handle, or the FDE a run acts for), written once at create. A spec's creator travels as
  `_created_by` beside `_actor`: main keeps the one it holds, records the pusher when a
  node-born create names none, and fills a creator it never recorded only from that creator's
  own push; a node adopts main's, the pushing node from the creator main names when it accepts
  the push.
- **One rule per type, asked by the doors and by main's commit.** Who may write a synced row
  is one `WriteAuthority` per type in sail-core (`ai.singlr.sail.authority`), declared by its
  store (`SyncedStore.authority`): `SpecAuthority`, `RoomAuthority`, `ReviewAuthority`,
  `RunAuthority`, `MessageAuthority`, and `WriterAuthority` for files and projects. A rule reads
  the type's synced projection — `held`, what this box holds (the last live state over a
  tombstone), and `next`, the revision (null for a tombstone) — may read this box's database for
  owners, never writes, and reads an owner of the row it decides from `held`, so a revision never
  admits itself. It answers a `Refusal` (`READ_ONLY`, `NOT_OWNER`, `ADMIN_ONLY`, `NOT_AUTHOR`,
  `FIXED`, and the erase rule's `NOT_PRUNABLE`) with the message and fix clients see. `MAIN` and `SYSTEM` always pass; a read-only
  role is refused, except a run's principal reporting its own session and a room principal
  posting where the posting rule lets it.
  - **The doors** ask the rule where they decide today — HTTP, the host CLI, the socket, the
    terminal — and one translator (`Refusals`) turns a refusal into the error clients get.
    Admission for side effects keeps its place (`DispatchPolicy`, `LaunchAdmission`,
    `RoomWakePolicy`, the run-owner rule for stop and logs), reading the same predicates.
  - **Main's commit** (`RevisionJournal`, `ProjectStore`, `MessageStore`, each handed the type's
    rule by `StoreReplica.commit`) asks it for every pushed revision with the pusher as the
    actor. On `SYNC` it also decides whom a revision names: its `_actor` is the pusher, `sail` or
    a principal of a run the pusher owns (a principal of a run main does not hold yet is refused,
    not denied, until the run lands); a create's creator is the pusher; a message's author is the
    pusher, its runs' principals, or `sail` where a run of its is in the conversation. A run is its
    executing box's: `node` is the pusher, it acts for the pusher or no one, every principal names
    the run itself, and a deleted run is never brought back; so a review run acts for the box's
    own FDE. Blob presence is checked after the rule, not before: a denied offer's content is
    never uploaded, so a read-only session's push is denied, not refused for a missing blob, and
    an accepted one still lands only with its blob. A spec born in a room main has never held is
    refused, not denied, like a message there: specs sync before rooms, so the next round decides
    it. A spec is never born over a room holding its id, live or deleted, unless that moves no
    ownership (a node's own identity room reaching main first) or an admin asks, and a restore
    keeps the creator its tombstone recorded, for rooms as for specs. Main's denial never removes
    work still under way on the node (`SyncedStore.live`): a run that has not finished, or a
    review the node is still running; once it finishes, the denial settles it.
  - **The erase rule** (`EraseAuthority`) is one rule for a local prune and main's decision on a
    node's request: write capability, the owner or an admin, a whole project admin-only, a
    prunable status, no unfinished run; on a request, only a spec or project main holds.
  - **A node's writes speak for its FDE.** On a node, `RoleRule` caps a credential naming any FDE
    but the box's own at `viewer`: only the box's FDE and the runs acting for it write there,
    because anything else would reach main as that FDE's on the box's session, and be denied.
- **Every write names who is acting.** One `Actor` (`ai.singlr.sail.identity`: handle,
  `Role`, `Lane`, owner) is the identity of every write, whichever door it came through. Its
  lane names the door:

  | Lane | Who | Handle and role |
  |---|---|---|
  | `CLI` | the box's operator, root on this box | `CliOperator`: the box FDE with the role `RoleRule` gives it — admin on main or a standalone box, the synced roster role on a node; refused while the roster is unsynced or the FDE is disabled |
  | `API` | an HTTP token or passkey session | its FDE (the box's FDE for the host token) with the role `RoleRule` gives it, or no handle and the box FDE's role for a machine token |
  | `AGENT`, `ROOM` | a run's principal on the socket | the principal, owned by the FDE it acts for, with that FDE's role capped by the lane |
  | `SYNC` | an FDE pushing through `_sync` to main | the role `RoleRule` gives the session's FDE |
  | `MAIN` | a node adopting main's revisions | `main`, or the author main recorded for what it adopts; main has already decided them |
  | `SYSTEM` | this box's own machinery | `sail` |

  Each entry point binds the actor it acts as, at its edge and nowhere deeper, and that binding
  is the one channel for who is acting: no operation takes an `Actor` argument
  (`OperationsTakeNoActorTest`), and every door reads `Actor.current()` where it decides and
  hands it to the rule it asks; the admissions for side effects — `DispatchPolicy`,
  `LaunchAdmission`, `RoomWakePolicy` — read it too. It binds with
  `Actor.run`/`Actor.call` (a `ScopedValue`): `ApiRouter` around routing, `LocalApiRouter`
  per request, a command that writes without the API around its write, the `_sync` session
  around each commit and erase, the node's round as `MAIN`, and each background entry that
  writes (the event bus drain, the reactors, retention, the reconcilers, periodic passes, the
  sync scheduler, the room-wake launch, migrations) as `SYSTEM`. A
  `ScopedValue` does not cross into a plain executor, so work that continues a request is
  submitted through `Actor.carrying(task)` and captures its requester. Resolving a conflict
  adopts main's version as `MAIN`, so it keeps main's author, before the chosen state is
  written as the resolver. `ChangeLog.append` and `erase` read
  `Actor.current()` for every revision, tombstone and erasure: its handle is the author in
  `change_log.actor`, the pushing FDE or `main` is the `peer`, and a write with nothing bound
  throws and rolls its row back, as a pruned id does. A synced revision keeps the author it
  offers in `_actor` (main committing a push, a node adopting main's), falling back to the
  actor. A run, review or file has no author column, so the snapshot it offers carries its
  journal head's author. Every store mutator stamps `updated_by` (and `created_by` on a create) from the same
  actor, so the row and its history always agree. Deliberately unjournaled, and so naming no
  one: `RunStore.stampActivity` (a latest-wins heartbeat), `Erasure.discard`,
  `ChangeLog.purge` and `ChangeLog.compact` (history rewritten under erasure and retention),
  and the `ContentMigration`/`SchemaManager` rewrites. There is no default actor, in
  production or in tests: a test binds only the writes it makes itself (`@ActingAs`,
  `Acting`), and a binding an entry point makes is proven by a test that drives that entry
  unbound wherever a test can drive it (the interactive files picker needs a console).
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
3. **FDE removal propagates as `disabled`, not a tombstone.** Revoking an FDE on main locks
   them out everywhere, since every door refuses a disabled FDE and a node disables an FDE
   main no longer lists, but the row lingers on nodes as disabled rather than disappearing. True delete-propagation is a roster protocol
   change.
4. **One platform per OS.** Mac arm64 and Linux amd64 only.

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
  on-disk descriptors are a copy nothing running reads: the loop, the API lanes, notifications
  and the agent commands read a project's catalog row through `ProjectReader`, with no file
  fallback. Writes go through the catalog first, and a definition the catalog did not take is
  written nowhere, so an edit can never diverge or be lost on the next sync.
- Every write names who is acting: one bound `Actor`, read by the journal and by every policy,
  never a string or an argument a caller threads through. A write with nothing bound fails.
- One owner rule (`Ownership.ownerOf`) and one role rule (`RoleRule`), each implemented once
  and read by every door: a second derivation of either is a bug.
- One write rule per synced type (`ai.singlr.sail.authority`), implemented once in sail-core and
  asked by every door that writes the type and by main's commit of every pushed revision: a
  door that decides a write itself, or a commit that writes without asking, is a bug.
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
