# Sail Architecture

> The design and its contracts as they stand on main. Each contract row names the tests that
> prove it; a change to a contract changes the row and its tests in the same pull request.
> Working conventions are in `AGENTS.md`, the build, gates and release steps in `CONTRIBUTING.md`.

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
- **Remote operation.** Through the CLI (on the box or from a Mac thin client) and through
  Mast, the desktop client.

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
- **Mast.** The desktop client (a separate repository): a thin client of a box's API and
  event stream for specs, rooms and reviews, and of its pty host for terminals and files.

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

Main's database is the org. Back up `/var/lib/sail/sail.db` (`sqlite3 /var/lib/sail/sail.db ".backup
<copy>"`, or the file with sail-api stopped). Every database carries a catalog id
(`DatabaseCatalog`), random, minted with the database and kept by every upgrade and every copy of
the file, and main's welcome names it. A node records the catalog of the first main database that
welcomes it and refuses any other before anything moves (`NodeRound.admit`): "Main at <target> is
not the database this box synced with (catalog <old>, now <new>). Restore main's database
(/var/lib/sail/sail.db) from a copy, then sync again." A main older than catalogs names none and
could be any database, so it is refused too, before anything moves: "Main at <target> names no
database catalog, so this box cannot tell whether it is the database it synced with: upgrade main
('sudo sail upgrade' on main), then sync again." So a main set up again from a copy (`sudo sail init
--as-main`, the copy put in place of its new database, then `sudo sail migrate`, which also restores
the sail user's keys from it) is the same main to every node, which then pushes whatever it has not
pushed yet (work main took after the copy is lost with it); a main set up empty is a new org, and
its nodes refuse it. A main restored in place from an older copy keeps its box id, so its log sits
below the node's checkpoint: the node then walks every head again from the copy instead of reading
nothing and saying it is in sync. A box that stops being a node forgets main's catalog as it forgets
its scope. A copy taken before the release that mints catalog ids is minted a new one when restored,
so take a fresh copy after upgrading. Main is never rebuilt from its nodes.

## Module layout

Three Maven modules, Java 25, one native binary.

| Module | Package roots | Responsibility |
|---|---|---|
| `sail-core` | `auth`, `authority`, `common`, `config`, `engine`, `gen`, `harness`, `identity`, `pty`, `ssh`, `store`, `sync`, `webauthn` | Domain model and config records, the write rules (`authority`) and the actor (`identity`), the Panama-SQLite store, the sync engine and replicas, the pty session core, passkey and WebAuthn primitives, the SSH gateway tokenizer, the harness adapters, pure file generators, and the shared id, time and string seams. No CLI, no HTTP, no native binary. Its only external dependency is SnakeYAML Engine. |
| `sail-harness` | `engine`, `pty` | What runs beside an agent in its container and on the host for it: agent sessions and their halt, the run watcher, the in-container `spec` and event helpers, the hook renderer and the pty host. |
| `sail-infra` | `api`, `commands`, `engine` (plus `Main`, `Sail`, `SailVersion`) | The picocli CLI, the loopback HTTP control-plane API, the review loop and its reactors, and the host-side provisioning engines (Incus, Podman, ZFS and systemd drivers). It builds the GraalVM native binary `sail`. |

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

Eighteen `*Store` classes plus the sync and journal machinery, all over `Sqlite`:

| Store | Owns | Synced across boxes? |
|---|---|---|
| `SpecStore` | `specs` and their dependencies, repos and content | Yes, entity `spec` |
| `RoomStore` | `rooms`, the conversations: a spec's identity room and free-standing rooms | Yes, entity `room` |
| `MessageStore` | `room_messages`, immutable once posted | Yes, entity `message` |
| `ProjectStore` | `projects`, the definition plus attribution | Yes, entity `project` |
| `FileStore` | `project_files`, shared workspace files keyed by project and path; `materialized_files`, what this box wrote of each | Yes, entity `file`; the materialized record is local |
| `RunStore` | `runs`, every agent run with its lane, unit, principal and reservation | Yes, entity `run`, pushed only by the box that executes it |
| `ReviewStore` | `reviews`, `review_stages`, and `review_findings` as the projection of each review's content | Yes, entity `review`, its findings as content |
| `BlobStore` | `blobs` and their chunks, the content every synced content field names by hash | Content crosses by hash, fetched on demand |
| `FdeStore` | `fdes`, the human principals and roles | One-way, main to node roster pull |
| `FdeSshKeyStore` | `fde_ssh_keys`, SSH fingerprint to FDE | Local |
| `FdePairingStore` | `fde_pairings`, the public key and token name of each FDE's Mast pairing | Local |
| `EventStore` | `events`, the audit stream persisted from the bus | Local |
| `AuthSessionStore` | `sessions`, login and gateway sessions | Local |
| `TokenStore` | `api_tokens`, SHA-256 hashed with optional expiry | Local |
| `BoxCredentialStore` | the box credential interactive container sessions present on the socket | Local |
| `WebauthnCredentialStore` | `webauthn_credentials`, passkeys | Local |
| `PendingChallengeStore` | `webauthn_challenges`, single-use ceremony challenges | Local |
| `EnrollmentTicketStore` | `enrollment_tickets`, one-time passkey enrollment | Local |
| `SlackThreadStore` | the Slack thread each spec's narration continues in | Local, main only |

The sync and journal support classes live in the same package: `ChangeLog` (the append-only
revision journal that is the durability spine of sync), `Revisions` (content-addressed
revision ids), `ConflictDetector` (a pure three-way merge), `ConflictResolver` (implemented
by the three synced stores), `SyncConflicts` (parked conflicts), `SyncState` (per-peer
checkpoints), `ExpiredRowSweeper` (hourly housekeeping), and `PushOutcome` (the CAS result).

The schema is one baseline. `SchemaManager` creates it on a fresh database in one
transaction and stamps it `CURRENT_VERSION`; a database stamped with that version opens as it
is, and any other is refused with a remedy: one made by a release before v1 is replaced (move
it aside, `sail init`), a newer one needs a newer binary. There is no version to walk and no
data fix-up framework. `BoxDatabase.prepare` opens the schema on every daemon start and every
`sail migrate`, then lets a box that is not a node forget the scope main last named it.
`sail migrate` is how a release converges its box: it prepares the database, seeds the demo
project, and on the box itself re-issues the host token, re-syncs the `sail` user's
`authorized_keys`, writes the host's drop-ins, holds Incus and reinstalls the pty session host, and `sail upgrade` runs the new
binary's `migrate` once it is installed, so new-release code does that as root. Host state
(the `sail` user's forced commands and the systemd units) names the installed binary,
`/usr/local/bin/sail`, so only that binary's migrate converges the host. Under a
`SAIL_DATA_DIR` override, migrate prepares the copy alone. Any other binary is refused before
it opens the database.

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
| I5 | A node syncs only with the main database it first synced with: a welcome naming another catalog, or none at all, ends the round before any row moves, "Already in sync" is said only after a round with the recorded one, and a log that is no longer the one the node read is walked again from its first head, however far it has grown since: beside each checkpoint the node keeps the last entry it read, its seq, id and rev, written before the page adopts anything, or that it read none, `Heads` names it, and main's `Tips` names each type whose log no longer holds that entry at that seq (`ChangeLog.holds`: an erasure of it still to page holds it, and so does `compacted_entries` naming that entry, which `ChangeLog.compact` keeps of each entry it drops and an erasure removes, never newer revisions alone; an admitted head between the read and the checkpoint does not; an id outside the session's scope is never held, so a mark tells a node nothing of it), as well as a log below the checkpoint. After a walk of every head of a database it synced with before, the node withdraws each row it heard from main that main no longer offered, its history kept and nothing pushed to rebuild main; one carrying a change main never took is refused with it, never offered. | `DatabaseCatalogTest` (minted per database, kept by upgrades and copies, the first main recorded), `SchemaManagerTest` (a database from before catalogs; a node from before marks walks once), `SyncStateTest`, `ChangeLogTest` (what a log holds at a mark; a seq compaction emptied is held only for the entry it kept), `SyncWireTest` (a node or a main from before marks names none), `SyncRpcServerTest`, `ScopedSyncTest` (a mark naming an id outside the scope is answered as one naming an id main never held), `ProjectStoreSyncTest` (a node adopting main's project takes main's creator; a push never moves it), `NodeRoundAdmitTest`, `MainRebuiltRoundTest` (an empty main under a new or the same box id refused with nothing moved either way; a main that names no catalog refused with nothing moved before the node recorded one; a main restored from a copy takes the node's newer work; one restored in place from an older copy is paged from the copy, also once it has written as many entries as it lost, or 50 after losing 50, and a project `migrate` imports into it reaches the node, its creator included; an erasure of the entry at the node's checkpoint is an ordinary round, and one that revised the read spec past history and compacted it still walks; a scoped node that read nothing of a type, and a round cut short between a page's adoptions and its checkpoint, still walk; either way the specs, rooms, projects and messages main lost leave the node, named once per type, in their history, a node's change to one refused and never pushed, a scoped node weighing only its scope, an ordinary round dropping nothing, a round that only withdraws reported as one that moved, a box meeting a database for the first time withdrawing nothing, a lost run under way here left and weighed again once it ends, a round cut short naming what it withdrew; a node from before catalogs records main's), `BoxRunsSyncTest` (a former main joining a new main withdraws nothing), `HandleChangeTest` and `SyncCliTest` (a box that stops being a node forgets main's catalog), `NativeFleetIT` (an empty main set up again at the node's address; one restored from a copy; one restored in place from an older copy, the node holding main's rows after one round; one restored in place that writes more than it lost before the node syncs, the node equal to main after one round) |
| A1 | Who may write a synced row is one rule per type in sail-core; the same write gets the same refusal kind and code at every door and on main. | the rules: `SpecAuthorityTest`, `RoomAuthorityTest`, `ReviewAuthorityTest`, `RunAuthorityTest` (a run is created only for the owner of the spec or room it names, on every lane), `MessageAuthorityTest`, `WriteAuthorityTest` (files and project definitions, the table's rows alone; a rename also purges the old name, an admin's); the doors: `NodeWritesTest` (one refusal — kind, code, message and fix — through an API token, a passkey session, a gateway session, the box credential, a run and a room run; the terminal keeps its own text), `AuthorityAuditTest` (a viewer's edit over HTTP as on the host CLI; a room's delete and its membership; a spec born over its owner's room at the door as on main; a merged resolve), `SailOperationsSeamTest` (a resolve: adopting main's side decides nothing, keeping or merging is an edit, on the socket and both web lanes), `CatalogAuthorityAuditTest` (the host CLI's catalog edit, project files and rename), `AdhocDispatchTest` (ad-hoc runs), `OneDecisionTest` (spec edits, posts); main: `PushAuthoritySyncTest` (a pushed run on another FDE's spec is denied, and so is one naming its own spec and another member's room; one ahead of a spec its box made lands), `DeniedSyncTest` |
| A2 | No code path writes a synced row without its rule deciding it: a pushed revision at main's commit, every other write at the journal, each exactly once. | `JournalDecidesEveryWriteTest` (every public store method that reaches the journal, read from the stores' bytecode, is refused on the CLI, API, agent and room lanes and leaves nothing; a revision never admits itself; a refusal swallowed inside a transaction still rolls it back; a pushed revision is not decided twice), `OneDecisionPointTest` (in every module's classes only the journal and the message store append a revision, only they ask a write rule, and only main's commit takes a store's rule), `RevisionJournalTest`, `SqliteTest` (a doomed transaction); an operation's rows commit together or not at all: `GlobalSpecOperationsTest` (an update with its wake; the move to done; a delete), `ReviewOperationsTest` (a follow-up with its links), `BuildDispatchCoverageTest` (a dispatch and a restart: reservation, reset, claim, supersede), `SpecPruneTest` (a restore with its room) |
| A3 | An event, hook or reactor never makes the machinery do what its sender could not. | the rule: `EventAuthorityTest` (every listed type and rule; every unlisted type refused; a run decides what its event is about and its spec whose it is; only this box's FDE reports what this box observed); both doors with the real subscribers behind them: `EventDoorTest` (a member's stop, completion, failure and review evidence for another member's spec, run or room never reach the bus, so the spec stays, the run stays and the reconciler still rescues; a run a member pushed is no key to another member's spec; an owner is not taken at their word for what this box observed; an owner's retelling and an admin's report land; a run publishes only its hooks; the server's clock, publisher and stored message replace the sender's); every real publisher through its own code path: `EventPublishersTest` (the host CLI's dispatch, restart, ad-hoc run and stop, the watcher's stop, a node's sync announcement, main's sync bridge relaying as the FDE who pushed, and refusing a pushed run on another member's spec); the socket's scoping to the credential's run: `LocalApiRouterTest`, `AgentPrincipalLifecycleTest` |
| A4 | Local prune and main's erase-on-request ask one erase rule, and only main writes erasures. | `EraseAuthorityTest` (a project purged whole never waits for a run; anything narrower does), `EraseRequestTest`, `SpecPruneTest` (a purge takes a project's runs, going or not) |
| T1 | Every box records the same author for the same revision, and a revision names only whom its writer may write as. | `PushAuthoritySyncTest`, `CreatorSyncTest` (a revision main recorded with no author), `WireAuthorSyncTest`, `ConvergenceSyncTest` (a resolve holds main's revision under main's author, a deletion's included), `ConflictOperationsTest`, `MessageSyncTest` and `ReplyChainSyncTest` (a message under its poster), `ProjectSyncTest` (a rename's deletion under its deleter), `NativeFleetIT` (change-log heads) |
| T2 | A spec's and a room's creator is written once, restores included, and every box holds the same one. | `PushAuthoritySyncTest` (spec and room restores), `ConvergenceSyncTest` (restored and re-created on another box), every sync test through `SyncBox.assertEqualToMain` |
| T3 | A record's times are its author's: a revision carries when its entity was created and when it was made, and an archived or cancelled spec's when it entered that status, outside the comparison, and every box applying it, on every lane, writes them verbatim — except that main keeps the creation time of a row it already holds, whatever an offer carries, and its answer carries it back, so a row from before the times converges on main's; a merge takes the status time from the side whose status it keeps, so every box holds the same `created_at`, `updated_at`, `archived_at`/`cancelled_at` and board order for every spec, project and room; a merge offered to main, a rebase, a resolve, a restore and a project rename are stamped when they are made; a conflict shows and merges no metadata. | `RecordTimesSyncTest` (made on main or a node, edited, archived, one priority from three boxes, a project and a room made on a node, an archive edited before it syncs, concurrent archives, an archive merged with an edit elsewhere, a body edit, a room made on two boxes and merged, a row from before the times, a merge offered to main, a restored room, a room revision from a box before the times; times alone never conflict, for specs and rooms), `ConflictDetectorTest` (the status time rides with the status), `ConflictMergeTest` (no metadata shown or merged), `SpecStoreTest` (a rename's revisions stamped), `ProjectStoreSyncTest`, `SchemaManagerTest` (the status times are the transition's revision's), every sync test through `SyncBox.assertConverged` (a version carries its times), `NativeFleetIT` (spec times on main and two nodes over ssh) |
| L1 | Every offer settles within a bounded number of rounds; no type's round fails forever. | `LivenessAuditTest` (posts, born-in specs, reviews, oversized files, a commit that throws beside an accepted offer, an erased project, a room deleted after or before its sync; every settlement within a stated number of rounds, ending equal to main), `SyncRpcServerTest` (a throwing commit is that offer's refusal), `SyncContentFailureTest` |
| L2 | Main refuses, rather than decides, only while what it needs will arrive by sync order. | `LivenessAuditTest` (a reply whose parent main has not taken is refused with it, never denied), `DeniedSyncTest`, `MessageSyncTest`, `SpecAuthorityTest`/`MessageAuthorityTest` (main's `Decidability` reads pending, never denies) |
| L3 | A node holds back what main cannot decide yet instead of failing the round. | `LivenessAuditTest` (a born-in spec behind its room is withheld, not refused; an agent's spec, room, file and review land in the round its run does), `DeniedSyncTest`, `PushAuthoritySyncTest` |
| L4 | Main's version, by denial, pull or merge, never removes or rewrites a run or review still running here. | `BoxRunsSyncTest` (pulls, converged versions, merges, lost answers, another box's run and review), `DeniedSyncTest`, `PushAuthoritySyncTest` |
| L5 | A run whose process is gone is finished on the box that ran it: by its watcher's stop, or within one reconciler pass of no watcher being left. | `MissedStopReconcilerTest`, `MissedStopsTest`; re-stamped runs: `RunTrackerTest`, `StopOperationsTest`, `AgentLogStreamerTest`, `WatcherRearmerTest`, `RunPresenceEmitterTest`; reviewer and fix runs, reconciled as a build is once no watcher is left to report them: `ReviewLanesTest` (a reviewer that exited while the daemon was down; a fix agent that exits unwatched), `ReviewLoopRecoveryTest` (a run its watcher still covers is left to it; a run whose container stopped) |
| L6 | Offers main committed in a round that then failed converge next round, with no conflict and no second revision. | lost answers, merged offers and deletions included: `LostAnswerSyncTest`, `ProjectSyncTest`, `BoxRunsSyncTest`; a refused batch: `LivenessAuditTest` |
| C1 | After one round per box with no new writes, every replica equals main: fields, author, creator, revision, tombstone, erasure. | `ConvergenceSyncTest` (every deletable type restored after an adopted deletion, resolves, rooms re-created), every sync test through `SyncBox.quiesce` and `SyncBox.assertEqualToMain`, `NativeFleetIT` (change-log heads); reviews with their findings: `ReviewFindingsSyncTest` (two iterations, a dispute and a resolution reach main and every box alike; a denied review adopted as main's exactly), `ReviewSyncTest`, `PushAuthoritySyncTest` |
| C2 | State that never replicates is removed only with its entity's erasure or by the box's own action, never by adopting main's version. | reviews hold no such state: a review's findings and its follow-up links are its synced content, so adopting main's version loses nothing (`ReviewFindingsSyncTest`: a follow-up's links survive its spec's delete and restore on every box; `ReviewStoreTest`: the projection is written only from the adopted content) |
| C3 | Whether a disk copy is this box's output or a person's edit is decided without retained history. | each box records what it wrote or published, box-locally (`MaterializedFiles`, through `FileStore.copyOf`): `FilesAuditTest` (a copy main wrote is refreshed, and a deletion removes copy and record, after more pushes than history keeps and a compaction; a hand edit and a hand revert are kept; a mode-only change survives the same; every scenario ends converged), `FileStoreTest` (every version in flight until one lands, then that one alone; an undecided copy; a publish recorded; the record goes with an erasure and moves with a rename), `FileMaterializerTest` (a write that died before moving the file; an undecided copy kept until a version this box writes lands in its place), `MaterializedFilesSyncTest` (the record never reaches a node or the change log); compaction through the sweep and `sail sync gc`: `RetentionSweeperTest`, `SyncCommandTest`; `sail project files add` and `pull`: `ProjectFilesCommandTest` |
| C4 | Work only this box held leaves only by main's denial or erasure, kept in the change log and announced, by its owner's act, or with a project an admin revoked from the box (S4), announced and not kept. | a gone dependency's offer, re-homed or withdrawn and announced (`Settlement.Settled`): `LivenessAuditTest`, `ConvergenceSyncTest` (a denied run's agent's spec edit), `ErasureAuditTest` (a denied offer's content; a member's and an admin's child of a pruned room re-homed and landed); a review main never took, withdrawn with its findings kept in the change log and announced: `ReviewFindingsSyncTest`; files: no box republishes a stale copy or resurrects a deleted file by mistaking its own output for a person's edit, nor overwrites a person's edit by the reverse, and a person's copy is published once: `FilesAuditTest`; an erased file's copy stays with its project and its record goes with main's erase, a node's adoption and a node's prune: `FilesAuditTest` |
| C5 | A box takes a spec or room only into a project its catalog holds within the scope main grants it: a birth, a move or a restore into any other is refused before anything is written, for every write a box decides as its own (main's word and the box's own machinery are not asked); main denies such a pushed row as gone and the node keeps it, offering it again — unless the project lies outside the pusher's scope, which the scope rule answers naming nothing of main's catalog; a row staying in its project is never refused, and one in a project the node made waits for main to take the project. Only a node is scoped: on main or a standalone box that scope is every project, whatever it last heard as a node, decided from a whole `host.yaml`, never from one absent or half written. | `UnheldProjectTest` (the refusal, its list and a scoped node's grant line; a project held beyond the scope; a move; a restore; a legacy row; a room; main's and the machinery's lanes), `UnheldProjectSyncTest` (a forged row denied every round and kept; a scoped pusher hears only the scope rule; a restore over main's tombstone; a project the node made; an edit of a row main holds), `ScopedSyncTest` (a project row main holds but the pusher is not granted), `SailOperationsSyncTest` (the door's code and message), `HandleChangeTest` (a box leaving the node role forgets its scope; a renamed node keeps it), `SyncCliTest` (main's and a standalone box's migrate, and the migration every start runs, forget a scope a role change left behind, a node's keeps it, one that starts while a node rewrites its identity, or finds `host.yaml` gone or empty, leaves the scope), `HostProvisionerTest` (a re-run `host init` keeps the sync identity, and an identity change that finished while it ran), `HostYamlTest` (any other rewrite of `host.yaml` waits out an identity write and keeps it) |
| C6 | Sync's notices are true and each prints once per round: a node never seeds the demo, and lets go of one it seeded before it joined, so a fresh node drops nothing; parked conflicts are a warning, and the round exits 0. | `DemoSeederTest` (the seed, and the node's first round through `SyncOperations` announcing nothing), `SyncCliTest` (a node's migrate and demo route seed nothing), `SyncCommandTest` (a round's summary repeats no notice; the conflict line), `ScopedSyncTest` |
| E1 | An erased id is never written again on any box; what belongs to it goes with it; a node removes only what main never acknowledged. | `ErasureTest`, `ErasureSyncTest`; a prune racing a born-in spec, on an admin and a member node: `ErasureAuditTest` |
| S1 | A scoped node holds exactly the rows of its granted projects: no row, erasure, blob or roster email of another project reaches it over any wire. | `ScopedSyncTest` (every type from an empty node; a blob fetched by hash and a chunk no served manifest lists are refused; an erasure reaches the nodes that held the row and no other; a version main took in a project since revoked is neither handed back nor its content served; an offer names only content its session sent or was served, and a scoped session is asked for every blob and chunk it announces, held on main or not), `ScopedSyncServerTest` (the roster of those who share a project, no email but the node's own, no assignee disabled; a scoped node removes whom main no longer lists, with their credentials, and never its own FDE), `NativeFleetIT` (real processes: rows, heads, blobs and roster) |
| S2 | A scoped actor reads and writes inside its scope on every box; a write outside it is refused in the journal, on every lane, before the type's own rule. | `ScopeRuleTest` (a create into a hidden project is a project not found; a write over a hidden row says only that the id is taken; specs, posts, files and catalog rows on this box's lanes; a write over a review is decided by where the review is filed, wherever its spec is now; a prune), `MessageStoreTest` (a conversation is read message by message by the project each was filed under, the page as long as its limit of what its reader sees; a reply answers only a message its writer may read), `ScopedSyncTest` (a forged push, a project row, an erase request, and one for a spec moved into the scope whose earlier review is not; a row is born naming only what its pusher may see: a spec into or over a conversation outside the scope, a room over one, a run of a spec or room outside it, none told whose it is), `EraseAuthorityTest` (a scoped owner erases a spec only when all that belongs to it is inside the scope), `ScopedReadsTest` (every read route under `/v1` answers a scoped caller byte for byte the same whether or not another project exists, held complete against `Operations`; a write into a hidden project is a project not found and one addressed to a hidden row meets what a missing row answers; the terminal door; a run sees its owner's scope; a spec is born neither into nor over a hidden room; a spec moved into the scope shows nothing of the room and run it left behind, nor any review written before the move, its findings, their count or a follow-up drafted from them, and is not its owner's to prune, rehearsed or real, while anything belonging to it is filed outside the scope; a room's refusal names only the specs its reader sees; every write route that addresses a row answers one of a hidden project as one nobody holds, held complete against `Operations`; a moved spec is neither seated in nor restored to what it left behind, and shows no question asked in the room it left, nor is a message of that room announced under the spec's project; a run never names the owner of a spec that left the scope; a spec moved into the scope brings none of the talk held in the project it left, through its room, its page cursors, its open question or a run's inbox; a run is delivered nothing of a project its FDE no longer holds; a scoped owner's write on its spec rewrites no review filed outside its scope), `BuildDispatchCoverageTest` (a scoped caller dispatches no spec whose earlier review is filed outside its scope, and its prompt carries no talk filed outside it), `ReadFilterTest` (the read filter; an unrestricted read never asks a row's project), `SseHandlerTest` and `AgentLogStreamerTest` (the event and run-log streams; a scope changed while a run-log stream is open), `OpenStreamRecheckTest` (an open event stream follows a grant and a revoke from the next event a second later, asks once per second, and ends when its FDE is removed or disabled), `ConflictOperationsTest`, `ScopedRosterApiTest` (a session and a token of a scoped FDE both resolve to a scoped caller), `NativeFleetIT` (the gateway's `spec list` with a scoped node's key) |
| S3 | Main decides scope alone, from its grants and the project each change head carries, and remembers nothing of what it sent. | `ScopedHeadsTest` (every type's head carries its project from its first write, through a deletion, a withdrawal, an erasure and a purge; a review and a message stay filed where they were first written when their spec moves and they are written again; the backfill), `MessageStoreTest` (a reply is filed with the message it answers), `ScopedSyncTest` (a thousand foreign heads are paged past by their heads alone, no row loaded, and the checkpoint reaches main's tip), `ProjectScopeTest` |
| S4 | A grant shows after the next round; a revocation removes the project in the same round with its container and directory, unpushed work included, announced once and counting the unpushed rows only when there are any; a narrowing revokes every project it does not grant, and a widening grants every project. | `ScopedSyncTest` (a grant resets the checkpoints and what was held re-adopts unwritten; a revocation with nothing unpushed says so; a revocation drops rows, history, heads, offers, requests and content at the round's start; a project with a run under way here is kept until the run ends; what was erased or deleted before the node first synced leaves with its project; with a spec moved each way between two projects, a revocation takes what main files under the project and leaves what it files under the other, and the node equals main within its scope; a thread begun before its room moved arrives and leaves whole), `SyncScopeTest`, `ScopeChangeRoundTest` (a narrowing and a widening through a whole round; a container Sail provisioned deleted and one it did not kept; an unreachable Incus leaves the container to the next round; a run under way keeps both; an out-of-scope project's leftovers taken by the first round; an unchanged round's one `incus list`, and none unscoped), `ProjectTeardownTest`, `ScopeTeardownIT` (a real Incus daemon), `NativeFleetIT` (an FDE that joined unscoped narrowed in place, keeping its key) |
| S5 | An unscoped FDE is untouched: a fleet with no scoped FDE behaves as it did. | every sync test, unchanged; `ScopedSyncTest` and `NativeFleetIT` (an unscoped node beside a scoped one equals main), `ScopedSyncServerTest` (the whole roster, with emails) |
| S6 | A welcome that names no scope is an unscoped FDE's: the node holds every project. | `SyncWireTest` (a welcome naming no scope; a key this build does not know), `ScopedSyncTest` (a welcome naming none after one naming an empty scope pages everything), `NativeFleetIT` (two scoped nodes are each sent only their scope and drop the project main revokes) |
| S7 | Scope fails closed: an FDE added or narrowed scoped sees nothing until granted, a session naming no FDE receives nothing, a head of unknown project reaches unscoped nodes only, and a request whose auth stamped no scope reads none. | `ScopedSyncServerTest` (no grant; no token, a blank one and one never issued), `ScopedSyncTest`, `ScopedHeadsTest` (a head pruned before heads carried a project stays unknown), `SyncWireTest` (a scope key that names nothing readable grants nothing), `ProjectScopeTest` (an admin is never scoped; a widening forgets the grants), `FdeCommandTest` (a narrowing keeps the FDE's id and every credential, commits with its grants or not at all, and is refused for this box's own FDE and an admin) |
| S8 | A refusal for scope carries nothing of main's: it names the id, no snapshot and no rev, and is decided before the erasure and staleness checks. | `ScopedSyncTest` (a row still held after a revoke, an id main erased, a message, an id taken in another project: denied with no version, the node withdraws its own, and a denial by another rule carries main's version only inside the scope) |

Every test in the `sync` package that drives a round between boxes ends each scenario by
quiescing every box and asserting each replica equals main — fields, author, revision, head kind
and the merge base it descends from — so a divergence cannot pass unseen: `SyncBox.quiesce` and
`SyncBox.assertEqualToMain`, together `SyncBox.assertConverged`; `SyncBox.assertConvergedWithin`
settles a fleet of boxes round by round first, counting a refusal or a settlement as unsettled.
No scenario is excepted from equality: every entity converges. The fleet lane (`NativeFleetIT`)
asserts after every scenario's final round that every box's change-log heads (id, rev, kind,
author) equal main's for every type.

### Scope: which projects a box holds

An FDE sees every project unless main's roster scopes it (`fdes.scope = 'granted'`, set by
`sail fde add --scoped`, or later by `sail fde update --scoped [--grant <project>]...` in one
transaction with the grants it names; `sail fde update --unscoped` sets it back and forgets the
grants, so a later narrowing grants only what it names), and then exactly the projects main granted it (`project_grants`,
box-local on main like `fde_ssh_keys`; `sail fde grant` and `sail fde revoke`). That set is a
`ProjectScope`, the fifth component of every `Actor`, the value the audience is decided from: a
row in hand is read through `ReadFilter` and a query filters by `Access.audience`, so every door
asks one audience and none keeps a list of projects of its own. A row of no
known project is admitted by an unrestricted scope only. The scope is resolved with the
role, by the one rule every door asks (`RoleRule.principal`): the API's session and token
authentication, and with them the SSH gateway's commands, the host CLI, the socket, the terminal
and main's `_sync` server, which serves no session that no FDE stands behind. Main's own FDE (an
admin, and so never scoped, whatever the roster says of it) and a box's machinery see every
project, and so does every actor on a node: a node mirrors no scope and decides no visibility. It
holds what main sent and drops what main stops sending. The host CLI's operator, the host token,
a machine token, the box credential and a run acting for no FDE see what the box's person sees; a
run's principal sees what the FDE it acts for sees.

**Heads carry their project.** `change_heads.project` is written with every append from
`ChangeLog.projectOf`, the one derivation: the `project` a spec, room or run names, the one a
file's id begins with (a file's synced state is its content alone), a project's own id, and
for a review or a message the project of its parent's head, its spec's or its conversation's,
as that parent stood when the review or message was first written: neither names a project
itself, so its head is filed once and a later write of it never files it anew, where a spec,
room or run is filed again whenever it names another project. A spec moved between projects
therefore leaves its earlier reviews where they were written, however often they are written
again (a re-dispatch supersedes them); a review begun after the move is the new project's.
A reply is filed with the message it answers, before its conversation is asked: a thread is
one project's wherever its room has moved since. A reply must hold the message it answers
(`room_messages.reply_to`), so a reply filed apart from it could neither reach a node that
is not sent the other nor stay on one that loses it; filed together, a thread arrives and
leaves whole.
A state that names none (a withdrawal, a deletion of a row the box never held) keeps the
project the head already has, and `erase` and `purge` carry it across the erasure row, which
has no snapshot to read. A node files what it adopts under the project main's head names,
which every entry carries across the wire (`SyncWire.Entry.project`, `ChangeLog.fileUnder`),
and never under one it works out itself: a tombstone or an erasure of a row it never held
gives it no head to keep a project from, and a review's project is its spec's at the moment
main wrote the review, which is not where the node finds that spec once it has moved. So a
node and main agree, head for head, on which project a row leaves with.
A column and not a join, because a pruned row has no row to join, yet
its erasure must reach the nodes that held it and never one that did not.

**Main's sync server** decides everything from the session's actor and the heads:

- a page passes over a head the scope does not admit without reading its row, and `next`
  moves to its seq all the same, so a page may be empty yet not the last;
- a `Need` omits an id main holds outside the scope exactly as one it does not hold. The node
  then offers it as new, whatever it once heard of it (`ScopedLocalReplica.unseen`), and
  main's denial withdraws it there. What main took from the node earlier (`accepted`) is the
  one answer read from history and not the head, so it is handed back only when that version
  is itself of a project in the scope: a row that moved into the scope since is answered with
  its current state alone, and the older version's content is never the session's to fetch;
- a blob is the session's to fetch only once an entry main sent it referenced it or the
  session sent it whole, and a chunk only once a manifest it was served listed it;
- a hash is no licence to the bytes behind it. Main asks a scoped session for every blob it
  announces and every chunk of it that the two have not shared this session, held on main
  or not (`SyncRpcServer.receiveContent`): answering which hashes main holds would tell the
  session of content outside its scope, and taking a held chunk as sent would let a manifest
  stand in for bytes the session never had. An offer that names content the session neither
  sent nor was served is refused before it is decided, so a hash learned elsewhere is never
  written into a row main then serves. A scoped node therefore sends again the content of a
  row it changes when main already holds it; an unscoped session is asked only for what main
  lacks, as before;
- the roster is `FdeStore.visibleTo`: everyone whose own scope admits one of the principal's
  projects, without their emails, and the principal's own row whole;
- a push is decided for scope first (`ScopeRule.pushed`), before the erasure and staleness
  checks that would otherwise answer about a row the pusher may not see, and its denial
  carries no version; an erase request for an id outside the scope is answered as one main
  does not hold.

**Erasing** takes a spec with everything that belongs to it (`Erasure.closure`), and the walk
follows links, not projects: a spec moved between projects left runs, reviews and its room
in the one it came from. So the one rule both doors ask (`EraseAuthority.spec`: the API's
prune, rehearsed or real, and main's answer to a node's request) refuses a scoped owner a
spec whose closure holds anything filed outside the scope, whole and naming none of it
("Pruning spec 'x' would erase work outside your project scope."); an admin, never scoped,
prunes it. What the box holds no history of, a room the spec never minted, is nothing to
protect.

**The journal** asks `ScopeRule` ahead of each type's own rule, on every lane: what a write
is made over is answered only as a taken id, a project the write itself names as one that
does not exist (`OUT_OF_SCOPE`, a 404 to an API client), and one it reaches through a parent
is not named. A row is born naming only rows inside the scope (`ScopeRule.bornBeyond`): the
conversation a spec is born in and the spec or room a run works are read by the type's rule
for who owns them, and its refusal says so, so a named row filed outside the scope is one
that was not found before that rule is asked. A room is read here as its conversation, which
is its spec's where no room was minted. A spec and the room on its id are one conversation,
so the birth of either over a conversation outside the scope is answered as an id that is
taken, and a scoped actor's spec never adopts a room it cannot see.

**The node** brings itself in line at the start of every round (`NodeRound.rescope`), before
anything is offered or adopted. Every project it holds a head of that the welcome's scope
does not admit is dropped whole (`Erasure.dropLocal`: main's full walk and whatever else its
history files under the project, each row with its conflicts, box-local rows, entries, head
and offer, erasures included; nothing is journalled), and the round's collection frees the
content. What the walk reaches that a head files under another project stays: a spec moved
into the dropped project left its room, its thread and its runs in the one it came from,
which main still sends the node and, their heads being behind its checkpoints, would never
send again. A thread is never split so, a reply being filed with what it answers. It is decided from what the box holds, never from what it was last told, so a drop
put off by a failed round or by a run still under way here happens on the next round that
finds it. So does the content of a row main denied after a revoke: its `denied` tombstone
keeps the row's last state, so its blobs stay referenced until the next round's start finds
the head still filed under the revoked project and drops it, content included. A scope that admits a project the recorded one (`sync_scope`) did not resets the
node's checkpoints (`SyncState.reset`), since the project's history sits below them. A
welcome naming no scope records every project.
A narrowing is therefore a revocation of every project it does not grant, and a widening a
grant of every project; the node needs to know neither which command changed its scope nor
when.

**What else a scoped node holds** follows the same scope, decided from what the box holds and
never from what a round dropped, so a box an earlier release, a failed round or an
unreachable Incus left behind is brought in line by its next round, with no migration. Its
roster is exactly the one main lists to it: an FDE main stops listing is removed with its
box-local credentials (`FdeStore.removeUnlisted`), so the box keeps neither its name nor its
email; the box's own FDE is never removed, and one main lists again arrives as a new row with
no credentials. The round's last step (`SyncOperations.tearDownOutOfScope`) takes off the box
each project the scope does not admit and the box holds no head of: its
`~/.sail/projects/<name>` directory, and its container when Sail provisioned it
(`IncusDeviceManager.sailManaged`, the one trust gate, which `project apply --all` asks too),
through `ProjectTeardown`, the seam `sail project destroy` without `--purge` tears down
through. A container Sail did not provision is never touched, and is named once, in the round
that drops its project. One `incus list` serves the round, a device query is asked only of a
container outside the scope, and an unscoped box asks Incus nothing. When `incus list` fails
(an unprivileged `sail sync`), nothing is deleted and the round that dropped a project says
the next round that can reach Incus deletes its container. A project kept for a run under way
here is still held, so it keeps both until the round after the run ends.

**Main's API** has no single read seam, so one read filter (`ReadFilter`, the Read rule asked of
each row where it is filed) is applied in each read:
a row outside the caller's scope is the same not-found a missing row is, a list leaves it
out, and an open event or run-log stream asks its caller's authentication again through the
door it opened with (`StreamRecheck`), before an event or a heartbeat once a second or more has
passed since it last asked: a grant, a revoke or a demotion holds from that event on, and a
caller that no longer authenticates (removed, disabled, its token or session revoked) ends the
stream with an `error` event saying why. The lookup an
operation addresses a row by is the same for a read and a write, and comes before any rule
that would say whose the row is, so a write addressed to a hidden row meets a missing one,
code and message, before the journal is ever asked: seating or dismissing a room's member,
stopping a run, drafting a follow-up, restoring a spec or pruning one. An event a client
publishes about work outside its scope is decided as one about work this box does not hold
(`EventDoor`), and a message is announced under the project it is filed in, its room's, to
no sender whose scope does not admit that project. A row shown with another row's fields is not that row's licence to them: a
spec's wake, engagement and open question are its room's and its latest run is a run's, each
of its own project, which a spec moved between projects left behind, so each is read through
the filter too; a member is seated through a spec only when its caller may see the room the
spec lives in, a spec is born into a room only when its caller may see that room, a revision
made in another project is one the spec's history does not hold, and a run names the owner
of the spec it worked only to a reader who may see that spec. A review is read by the
project its own head is filed under (`ReviewStore.projectOf`), the one sync pages it by and a
revocation takes it by, never by where its spec is now: a spec moved into the scope shows
none of the reviews written before the move, by id or in its list, counts none of their
open findings, and drafts no follow-up from them. A message is read the same way, by its own
filing (`MessageStore.projectOf`), and a conversation is not one project's: what was said in
a spec's room before the spec moved stays in the project it left. So every read of a
conversation asks the predicate of each message inside its query (`MessageStore.readable`),
where a page's limit counts only what its reader sees: a room's page and its cursors, a run's
inbox, a room's latest message and its open question, and the talk a dispatch puts in an
agent's prompt. A run's credential outlives a revoked grant and its scope does not: the
local socket resolves the run's actor on every request, and the run it addresses is looked up
through the same filter, so a run whose FDE lost the project is answered as a run nobody holds. A
read reads as the actor
its door bound (`Actor.reader`); one no door bound an actor to is the box's own machinery
describing what the box holds, and sees every project. The HTTP and local-socket routers bind
their caller before they route, and a run's principal carries its owning FDE's scope. Every host
command runs through the one command line `SailCommandLine` builds, which reads as the box's person
(`Actor.readingAs`), resolved at the command's first read, so a command that reads nothing needs
no placed person. Only five commands read as the box, each named with its reason: the server,
`sync`, `sync status`, `migrate` and `upgrade` (`SailCommandLineTest`, which also fails a command
line built anywhere else). The commands that read the catalog read it as the box's person; the
container verbs (`project start`, `stop`, `switch`, `containers`, `logs`, `exec`, `shell`,
`connect` and `project snapshot`) act on this box's containers by name with its own privileges,
which its root shell already has, and never consult the catalog.

**A write over a row** is decided by where that row is filed (`ScopeRule`, reading the head
as a push over it is decided), so a review or a message is no more a scoped actor's to
rewrite than to read once its spec has moved, by its id or through its spec. A write that
reaches reviews through their spec does not get past that: a dispatch, which must close every
review of the spec's earlier attempts or the loop would read the last of them as its own, is
refused whole while one is filed outside its caller's scope ("Dispatching spec 'x' would
close a review outside your project scope."; an admin dispatches it once), and resolving
findings as a spec reaches `done` passes over a review its actor could not read.

Known edges. A spec moved between projects is the one row that moves: a review whose spec
left the scope is read there and refused any decision (the decision would move a spec the
actor cannot see), a room that kept a spec which moved away names that spec's owner among
those who may post there, a review the loop begins after the move carries forward the open findings
of the last one before it, and a review a scoped owner could not read keeps open what the
spec shipped with until an admin marks the spec done. A room wake reads the conversation as
the box's own machinery, so the prompt of an agent woken on main carries all of it: main's own
FDE is taken to be unscoped.
A project rename is a new project to a grant, and the rooms, runs, reviews and
messages recorded under the old name keep it. A row an unscoped actor moves out of a scoped
node's project stays on that node, as it was, until the node edits it or loses the project.
A box's own events
(a sync that degraded) carry the stand-in project `sail`, so they reach unscoped readers
only.

### What syncs, and how

`sail sync` runs one bidirectional reconciliation per registered entity type, in the
registry's dependency order — runs first, so an agent's run lands before anything its
principals wrote, then specs, rooms, files, projects, reviews and messages — plus one
one-way roster pull:

| Entity | Direction | Notes |
|---|---|---|
| specs | Bidirectional, field-level three-way merge | The team board |
| rooms and messages | Bidirectional; messages are immutable once posted | The conversation |
| project definitions | Bidirectional | The catalog: one definition per project, each section a field of its own |
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
its change-log entries name.

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

### The wire: sync protocol 8

A session opens with `hello` (protocol, build, box id) and is `welcome`d or `refuse`d once: a node
on another protocol is refused: told to upgrade when it speaks Sail 0.58.0's protocol 4, whose
database an upgrade converts, to reinstall itself when its protocol is any other older one
(protocol 8's run and spec snapshots carry cost and times, and the database before it is not
converted), and to upgrade main first when it is newer. The `welcome` always names the handle main authenticated the
session as and main's `limits.file_max`, and a welcome missing either is a protocol failure; the
node enforces the lower of its own limit and main's before a file ever reaches a sync, and a node
whose configured sync handle is blank or another does nothing that round (`NodeRound.begin`). It
names main's database catalog too, which the node checks before anything else
(`NodeRound.admit`, see Topology). Main records the first box that
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
its handle every one main does not hold, so main takes it. A handle change asks the same before re-stamping anything, and holds every round of the
box off from the ask until its stamps are written (`FileMutex.holdRounds`, a lock file
beside the database), so no run is offered, and its answer lost, in between. The box id names the node in main's log; who the
node is stays the authenticated SSH principal, bound as the `SYNC` actor around
every commit and erase: it is each revision's `peer`, and its author unless the revision
offers its own `_actor`. The node binds `MAIN` around its round, so what it adopts records
the author main recorded, with `main` as the peer. Wherever main hands the node a revision
that carries no snapshot to name its author in, main says who made it: an `accepted` result
names the author main recorded for the node's own offer (the pusher, when the offer named
none), and a tombstone or erasure `entry` and a `denied` tombstone name theirs. The engine
adopts each under `Actor.main(author)`, so every replica records the same author for the same
revision. The node
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
awaits one it has never seen, and has lost one it erased — but a spec or room born or moved into
a project its catalog lacks is gone (C5), which the node keeps; a node reads its copy for what main
holds — a live row main has taken (its `base_rev` set) is held, one main has not taken is
pending, a project included, a deletion heard from main or made over a row main had taken is main's word, and a
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
acknowledges it as its base. When a local write lands
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
id, reason). On the wire a denial is a `refused` result marked `denied: true`, and a terminal
denial carries `gone: true`.

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

A purge erases a project's rows everywhere, not other boxes' containers: their project directories and materialized files stay on disk with the container, and the materializer never touches the files of a pruned project again.

### The transport: pure SSH keys, no network enroll

A node reaches main over a single SSH subprocess, `ssh sail@main sail _sync`, using that
process's stdio as a newline-framed JSON RPC pipe. Auth is pure SSH keys, with
`PasswordAuthentication=no` and `IdentitiesOnly=yes` pinning the sail-managed key that `sail
join` generated, so a missing key fails fast. Host keys are checked in batch mode against the
account's `known_hosts`, where join pinned main's ed25519 key (see Onboarding); a round that
fails on it quotes ssh's own last stderr line ("ssh said: Host key verification failed.") and
names the whole recovery in `MainHostKey.rebuiltRecovery`'s words, the same sentence join's
refusal of a changed key carries: if main was rebuilt from a copy of its database, remove the
old pin with `sudo ssh-keygen -R <host>`, re-run `sudo sail init --main <target>` and compare
the fingerprint it prints with main's (`sudo ssh-keygen -lf
/etc/ssh/ssh_host_ed25519_key.pub` on main). When ssh closes the round with "Permission denied
(publickey)" for the box's own sync key, main has not authorised the node yet. The join keeps the
`fde add` line it printed beside the host config (`SshSyncChannel.keep`, `join.yaml`: the handle,
the roster name and email the join was given, the key and the pinned fingerprint, each one shell
word), and the sync repeats it (`SshSyncChannel.authorisation`) while it still names this box's
handle, key, main and pin, telling the node to send it to main's operator and sync again, before
ssh's words. On main, that key sits on the locked `sail`
user's `authorized_keys` as a forced command,
`command="…/sail _gateway --fde <handle>",restrict …`. The gateway resolves the FDE and
admits the `_sync` session. The write gate lives next to the write: every commit of a `_sync`
session is denied to a read-only viewer role, which may still pull.

### A project's definition

A project is one typed definition (`ProjectDefinition`) held in its catalog row as a JSON
document, its only copy. It holds a name and ten sections (`ProjectDefinition.Section`):
`description`, `image`, `resources`, `runtimes`, `repos`, `services`, `processes`, `packages`,
`agent` and `pipeline`, the review loop's stages and limits. `ProjectDefinitionJson` is the one
codec, for the whole document and for each section's JSON value, and every definition it reads
is checked whole, so whatever any door writes is a definition the catalog can hold.

A section is written on its own over the version its writer read. Each section has a tag, a
hash of its JSON value (`ProjectStore.tagOf`); `ProjectStore.writeSection` takes the tag its
writer last read and refuses a section changed since as `StaleSection`, leaving the project as
it is, and `writeSections` writes several in one revision or none. `sail project create` writes
a new definition, from flags, prompts on a terminal or a whole document on stdin
(`--definition -`), refused when the name is catalogued; `sail project edit` opens the
definition or one `--section` in `$EDITOR` as JSON and writes each section that changed;
`sail project config show|get|set` shows the definition and reads or writes one section. Over
HTTP, `GET /v1/projects/{p}/definition` answers the document with each section's tag,
`PUT /v1/projects/{p}/definition/{section}` writes `{"value": …}` (null unsets it) over the tag
its `If-Match` names and answers 412 when the section changed, and `POST /v1/projects`
creates one, 409 when the name is taken; each is a project not found to a caller whose scope
does not admit it
(`ProjectStoreTest.aSectionWriteIsOneRevisionOverTheSectionItsWriterRead`,
`ProjectStoreTest.aWriteOfSeveralSectionsIsOneRevisionOrNone`,
`ProjectStoreTest.createRefusesANameTheCatalogHolds`,
`ProjectDefinitionOperationsTest.aSectionIsWrittenOverTheTagItWasReadAtAndAStaleOneIsRefused`,
`ProjectDefinitionOperationsTest.aProjectIsCreatedOnceAndAnExistingNameIsAConflict`,
`ProjectDefinitionOperationsTest.aProjectOutsideTheReadersScopeOrNotHeldIsNotFound`,
`ProjectEditCommandTest.anEditOfOneSectionReplacesItAndKeepsTheRest`). A sync round names the
projects new to the box with the `sail project apply` that provisions each
(`SyncOperations.announceNewProjects`); no definition is written to disk, only the
project's shared files the materializer writes under `files/`.

### Identity isolation in synced projects

A project definition is shared infrastructure only. It has no field for the two things that
are inherently per-developer, the git identity commits are authored with and the SSH key
authorized into a box's containers, so neither can ride onto a teammate's box: a definition
naming a `git` or `ssh` key is refused like any key no definition has
(`ProjectDefinitionTest.aDefinitionHoldsItsNameAndItsSectionsAndNothingElse`).

Each box supplies both itself, when it provisions or applies. The git identity is the box's
own `git config --global user.name` and `user.email` (`LocalIdentity.detect().git()`): on a
box `sudo sail init` set up, root's, which init gives the `--name` and `--email` it is passed
(or asked for) when root has none, never overwriting one. A missing value fails before
anything is provisioned, naming the command that sets it for the account it is read from,
`sudo git config --global …` when root's identity is read for a `sudo` caller
(`LocalIdentityTest.aBoxWithoutGitFailsNamingTheCommandThatSetsTheValue`,
`LocalIdentityTest.rootsMissingIdentityUnderSudoNamesTheSudoThatSetsRootsGitConfig`). The
git step writes it into the container's global git config, so everything that commits there,
an agent included, commits as the box's owner. Repos clone over https with the box's forge
token; a repo URL in ssh form is refused when the definition is read, naming its https form
(`ProjectDefinitionTest.aRepoIsClonedOverHttpsAndAnSshUrlIsRefusedNamingItsHttpsForm`).

The one key a container trusts is the box's registered workstation key at
`.sail/workstation_key.pub` in the box owner's home (`SailPaths.workstationPublicKeyPath`, the
one resolver a pairing, `host config set ssh-public-key`, connect's warning and the key a
container trusts all read), which is the laptop key the box owner connects to containers
with. This is a different key from the machine sync key that a node presents to main. Because
each box has a single owner, one workstation key authorizes that owner into every container the
box provisions. It is registered once with `sail host config set ssh-public-key`, which
auto-detects it from the box's `authorized_keys` when no value is given and writes it under
`PairingTrust`, as a pairing does, so an apply never reads the key on one side of a change and
installs it on the other. A box with none registered provisions containers that trust no
laptop, and connect warns of it; a key file that holds no readable key is refused rather than
read as none (`WorkstationIdentity.registered`). The result is that each engineer's containers
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

Project definitions are distributed entirely by DB sync. There is no `project pull` or
`project push`, and the demo project is bundled in the binary and seeded into the catalog of
main or a standalone box idempotently, never resurrected once purged; a node gets it from main
like any project, once granted. The remaining git-token plumbing exists only to
clone a project's source repos into its container at provision time, not to move definitions.

## Runtime modes: host vs client

`Main` chooses a mode at startup through `RuntimeMode.detect()`:

- **Host mode.** A host config (`/var/lib/sail/host.yaml` or `~/.sail/host.yaml`) exists, or
  nothing else applies, which is the default. Commands execute locally:
  lifecycle commands drive `incus` and `podman`, and API commands hit the local `sail-api`.
- **Refused.** The box's `sail-api` is root's system unit (`/etc/systemd/system/sail-api.service`),
  the account is not root, names no `SAIL_DATA_DIR`, and sees no host config, neither
  `/var/lib/sail/host.yaml` nor its own. Its `~/.sail` would silently diverge from root's state,
  so `Main` runs nothing: it prints "sail-api on this box runs as root, so <user>'s sail would
  see none of its specs, projects or FDEs. Run it with sudo: sudo sail <args>" and exits 1.
  Only `--version`, `-V`, `--help` and `-h` are exempt, when picocli reads them as a request for
  help (not after `--`, where they belong to the command), and what only this machine runs: the
  account's own terminal host (`_pty-host --local`), its shim (`_pty-child`) and the self-test
  (`Main.refuses`). The `sail` gateway account reads
  `/var/lib/sail/host.yaml` and is Host; an account with its own `~/.sail/host.yaml` keeps its
  own box. For such an account, `sudo` resolves a project's personal placeholders from that account's
  home (`SailPaths.identityHome`), even beside root's system unit. Every `sail` command a box
  prints for its operator to type is rendered by `OperatorCommand`: a `sudo` caller reads it
  with `sudo`, since root's sail acts on root's state and the caller's own plain `sail` never
  reaches it; root logged in directly reads it plain; a non-root process reads its own plain
  `sail`, and `sudo` for a verb only root runs.
  - **A remedy is data until the door that prints it renders it.** Code that cannot see who
    reads what it says (`sail-core`'s stores, migrations, sync and authority, `sail-harness`,
    the `api` package, sail-api, the pty host, an agent watcher, the sync lane) never holds an
    `OperatorCommand`: it names a command as a `Remedy` (words, and commands each a verb and its
    arguments), a failure carries one through `Remedied` (`Result.Failure.remedy()` for an API
    answer, a refusal's `fix`), and a stored sync error keeps it as data. The CLI's doors
    (`Main.report`, `CliCommand.run`) render it with `OperatorCommand.here()`, the reader typing;
    the API serialises it (`remedy` beside a verb-only `action`, no `sail` sentence), and the
    terminal that reads the answer renders it for its own reader, so a `sudo` caller, a thin
    client's gateway account and root logged in directly each read the command they type. A
    daemon's log line prints the plain reading (`Remedy.plain`). `OperatorCommand` lives in
    `sail-infra`, so neither `sail-core` nor `sail-harness` can reach it, and
    `OperatorHintsTest.codeThatCannotSeeItsReaderNeverRendersACommand` fails on any use in the
    `api` package or a daemon's classes.
  - The operator is resolved once per command, at the command's door (`OperatorCommand.here()`
    in the command class), and a class only the CLI runs may take it as a parameter.
  - A refusal of a verb only root runs names the caller's own command, every word as typed,
    shell-quoted, with `sudo` in front (`RootRequired`); `RootRequiredTest` runs every root-only
    verb with every option it has and parses the re-run back.
  - No literal in `src/main` spells a `sail` command: `OperatorHintsTest` scans every string
    literal for `sudo sail` and for `sail <verb>` outside `OperatorCommand`, and parses every
    command rendered through the seam, a `Remedy`'s included, against the command tree.
- **Client mode.** Only `~/.sail/config.yaml` exists. Commands are forwarded to a box over
  SSH by `RemoteCommandRunner`, in lanes:
  - Local commands (`--version`, `upgrade`, `init`, `client`, `login`, `enroll`) run on the
    client, and so do the terminal host of the client's own person (`_pty-host --local`), the
    shim it spawns sessions under (`_pty-child`) and the binary's self-test (`_pty-selftest`),
    which have nothing to do on a server (`RemoteCommandRunnerTest`).
  - Host-only commands (`host …`) error with guidance to SSH in.
  - FDE-gateway commands (`spec`, `events`, `fde`, and `agent stream|sessions|review`) target
    `sail@host`; the other `agent` verbs read the control plane directly as the box's own
    person, so the gateway refuses them and the client sends them to the plain host. The
    engineer's SSH key hits the forced command `sail _gateway --fde <handle>`, which
    resolves their FDE, mints a short-lived session token, and re-executes the command, so
    the loopback API sees who is acting and asks `Access` for each thing they do.
  - Everything else (project lifecycle, interactive `shell` and `exec`) is forwarded as the
    plain SSH host, because it needs host privileges the `sail` user must never have.

This is why a loopback-only API is not a limitation for a Mac client: gateway commands run
on the host, where the API is reachable at `127.0.0.1:7070`.

## Onboarding

There is one convergent, idempotent, root-aware command per box, `sail init`:

- `sudo sail init --as-main --handle <you>` makes this box the org's source of truth, with you
  as its operator.
- `sudo sail init --main <target>` joins an existing main as a node.

It requires root and figures out what is missing and does only that, decided by a pure,
unit-tested `InitPlan`: provision the host if needed, install and start `sail-api` as the
system service (whoever invoked `sudo`; init never runs a step as another account), and take
on identity. A `sudo` user who already runs `sail-api` as a user service is refused before
anything is written, since a second control plane would contend for its port. On main that
means publishing the SSH identity and declaring `--as-main` with its operator's handle
(`--handle`, else the one the box already acts as, else asked for; a box acting as another
handle is refused before any step): `host sync --as-main --handle` writes the role and the
sync handle in one identity change and adds that FDE as admin when the roster lacks it. On a node it means `join`, which
first pins main's ed25519 host key into the invoking account's `known_hosts` (`MainHostKey`:
`ssh-keyscan -H` on port 22, appended when absent, kept when equal, refused when it differs or
cannot be read, all before the host config is written), prints "Pinned main's host key
SHA256:…. Check it on main: sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", then
generates the box's sync key and prints the `sudo sail fde add … --key … --main-host-key
SHA256:…` line for the operator to run on main. A re-join with the sync key the box already had opens one sync session to main as the
joined handle (`SyncOperations.welcomedAs`): a welcome as that handle means main holds the key,
and the join says so instead of printing the line (`--json` still carries it); anything else,
main unreachable included, is no evidence, and the line is printed. The probe prints nothing of
its own.
Each step runs through `Main.report`, so a refusal inside init is printed once with no trace,
then "Setup stopped" once. Main's `fde add` compares that fingerprint to its own host key before its
transaction and refuses a mismatch, so a join that something else answered is never
authorized. The granular commands it orchestrates (`host init`, `host
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
nineteen steps, one per `ProjectPhase`, and a `ProvisionTracker` lets a failed run pick up where
it stopped. The tracker resumes only for the definition it was written for: its state carries the
SHA-256 of the definition's JSON document (`ProjectDefinitionJson.write`) and the workstation
key's fingerprint (`ProjectProvisioner.inputs`), and progress recorded for another (or by a release that recorded none)
restarts the run from the first step, each step re-checking its own effect. A finished run records
that SHA-256 on the container itself (the Incus key `user.sail.inputs`) and deletes its progress
file: root, which provisions, and the operator, whose plain apply only converges, read the one
record on any box, and a deleted container takes its record with it. A run clears that record
before it changes an existing container, so a run that fails part way leaves none. An apply whose definition
differs from the container's record, or finds none, runs every step again and installs only the
difference. The container's user is always `dev` (`ProjectDefinition.USER`) at uid 1000, and a
definition has no field that names another; step 6 is recorded only once that user exists at
that uid.
It launches the Incus container from the configured image, bind-mounts the host `sail-api`
Unix socket in, installs the in-container `spec` CLI and event hooks, applies the disk quota
(the root disk's `size`: advisory on `dir`; on `zfs`, Incus sets it as the dataset's ZFS
`quota`, which counts the container's own snapshots, since Sail sets no `zfs.use_refquota`; see
**Storage**) and the cpu and memory limits (with
`security.nesting` and unconfined AppArmor so rootless Podman runs inside), installs
packages, the SSH user and the workstation key it trusts, Podman (with linger and a restart
service), Testcontainers wiring, and the JDK, Node, and Maven runtimes, configures the box's git
identity and forge credentials and clones the source repos over https, pushes the project's
shared files as the catalog's file store materialized them (`SailPaths.projectFilesDir`), starts
the Podman services under `--restart=always`, installs the agent CLIs, and installs the
project's skills and sail's.
`ProjectApplier` is the live-delta path for `sail project apply` on an already-running
container. CPU and memory changes that need a restart are applied by the dedicated resources
path, not silently here. Neither path deletes or re-clones a checkout already in the
container, which may hold work: one whose `origin` is not the definition's repository, or that
is no git checkout, is refused before the step is recorded (`RepoOrigin`, which reads the same
host and path as one repository whatever the scheme, user, port or `.git`).

**Storage** (`HostProvisioner`, `IncusStorage`, `PoolDevices`, `ZfsCacheCap`). Incus owns
every pool: `host init` has it create the pool with one `incus storage create`, built only by
`IncusStorage.createCommand`, and Sail makes no dataset of its own inside one. The sources are
`--storage dir` (the host filesystem, `incus storage create <pool> dir`), and for `--storage zfs`
a whole disk or a partition (`--disk <device>`, `source=<device>`), a sparse file Incus makes
under `/var/lib/incus/disks/` (`--pool-file-size <size>`, `size=<n>MiB`), slower than a disk and
said once to be meant for trials. Incus runs `zpool create -m none -O compression=on` itself,
never with `-f`: Sail never passes `source.wipe`.

- **Z1. What `host init` reports as done is what Incus has.** The base profile's root disk is
  converged (added when missing, an existing one kept) and the pool it names must be one Incus
  lists, else init fails; `host.yaml` states that pool, its driver and its source
  (`pool_source`) as Incus has them. *`HostProvisionerTest.aZfsPoolOnAWholeDiskIsIncussOwnAndTheProfilePutsContainersOnIt`,
  `HostProvisionerTest.aProfileThatNamesAPoolIncusLacksIsRefused`, `HostProvisionerIT`.*
- **Z2. Sail erases nothing.** No command of Sail wipes, repartitions or overwrites a signature:
  it never passes `source.wipe`, so Incus never runs `zpool create -f` (Incus runs `-f`, after
  zeroing the device's headers, only for `source.wipe`: `driver_zfs.go` `Create`, v6.0.0 and
  v7.6.0 alike). Incus's own `zpool create` is then a last guard, not the decision: Sail's own checks decide.
  Without `-f`, zpool refuses a device on which libblkid sees a filesystem, swap, a member's or a
  pool's label at the whole-device level (OpenZFS 2.2.2 `check_disk`, `check_slice`); it does not
  look inside an MBR disk's partitions, and it takes an empty partition table and content libblkid
  cannot read unambiguously. The window it guards is the moment between Sail's re-check and
  Incus's create. `PoolDevices.cleared`
  resolves the named device (a `/dev/mapper` name by its kernel name) and reads the whole device
  tree (`lsblk --json --tree --bytes`; a flat list fails closed), then takes the device only when
  all of this holds, each refused by name otherwise: it is a plain disk (at the top of the tree, of
  type disk; not an array, a loop device, a mapping, a ZFS volume, a zram or nbd device, or a disk
  built on another, as bcache is) or a partition of one, not removable and not empty; a
  partition's extent (its START and size from the same lsblk call) lies inside its disk and
  overlaps no other partition, and a disk with an overlapping pair refuses every partition of it,
  since writing one destroys the other, and the disk itself names the pair; a partition with no
  type udev can read that ends at its disk's last byte is one the kernel cut there, and is refused
  as reaching past the disk's end; every
  partition's type, on the device or for a disk on any of its partitions, is Linux filesystem data
  (GPT `0fc63daf-…`, MBR `0x83`) or ZFS (`6a898cc3-…`, `0xbf`), compared without case, and any
  other type or none refuses it, since a boot, firmware, reserved or extended partition may carry
  no signature; nothing is mounted on it, used as swap or built on it; neither lsblk nor `wipefs`'s
  listing of it shows a signature of any kind (a filesystem, swap, a partition table, or a member's:
  any `*_member` (a RAID, LVM or zfs member, an exported pool's included), `bcache`,
  `ceph_bluestore`, LUKS), nor for a disk any partition; the exclusive open `wipefs --all --no-act
  --types sail_exclusive_probe` makes finds no other holder (`--no-act` skips the writes, and the
  type filter matches no signature, so wipefs neither reports anything erased nor re-reads a
  partition table, as an unfiltered `--no-act --all` does; udev may re-read one when the device is
  closed, which writes nothing, so a disk's partitions are probed before the disk, Sail waits for
  udev to settle after a disk's probe, at most 10 s, and a partition gone while it is probed or a
  table that does not settle is refused as a partition table that is changing); no process has it,
  any of its partitions, or for a partition its disk, open at
  all, read from every `/proc/<pid>/fd` and matched by device number, so a holder is seen through
  any node or path it opened the device by, in any mount namespace (a process whose open files
  cannot be read refuses it too, and an exclusive holder the walk cannot find is said to be one
  Sail cannot name);
  and neither it nor another partition of its disk is a device of an imported pool,
  whatever class it is listed under (`zpool list -vHP <pool>`), named with the pool and with
  Incus's pool when Incus holds it. Where it sits does not matter: a blank partition on the
  system's disk is taken, and everything the system uses there carries a signature, a mount or a
  boot type. A device whose only obstacle is a filesystem, swap or a bare partition table names
  the `sudo wipefs --all <device>` the operator may run; one in use names the use, and a member
  of anything the command that frees it (a zpool `zpool import` lists: import it or `zpool
  labelclear` it; the labels of a pool it does not list, destroyed or missing its other devices:
  `zpool labelclear`, never import), never wipefs; a pool's whole disk is named by its pool. A refused device that holds a filesystem unmounted names it. What
  neither the open nor the walk sees is a consumer that does not keep the device open while Sail
  looks, a stopped virtual machine's raw disk or a closed dm-crypt volume with no header. A disk
  with no partition table and no signature is blank to Sail, even where a filesystem survives
  further in after its table was erased. The check runs when the flags are read and again just
  before the pool is made, which refuses unless the device is the one first cleared (its or its
  disk's WWN or serial, its major:minor and its size in bytes), and once more before the create
  that follows Incus restarting to see ZFS; each re-check is the last command before its create.
  The walk of `/proc` takes seconds on a busy box, so it runs after lsblk is read and before the
  device is probed: the device's own probes, then its by-id name, are the last look at it before
  Incus is handed it.
  A device whose disk has a hardware identity (a WWN, or an NVMe EUI, NGUID or vendor id) is handed
  to Incus by the path udev names from it (`/dev/disk/by-id/wwn-…`, `nvme-eui.…` or `nvme-nvme.…`,
  with `-partN`), only when that path resolves to the device checked, so no other disk
  can stand under the name the pool is made on; a disk with none, as on GCP, is handed by its
  kernel path. There, a disk physically replaced under the same name between Sail's last check and
  the pool's creation is judged by `zpool create` alone, which refuses a filesystem, swap, a pool
  member or a device in use, but not an empty partition table or filesystems inside MBR
  partitions: do not change disks while `host init` runs. Zpool's offer to force a create it
  refused is never passed on: the refusal says what zpool found and that Sail erases nothing.
  Offered disks (none named) are only whole blank ones.
  A pool on one partition or one disk has no redundancy: losing that device loses every container
  on the pool, however the system's own disks are mirrored.
  *`PoolDevicesTest` (on lsblk, wipefs and zpool output captured from this devbox, a GitHub
  runner and four GCP proofs taken through blank, partitioned, RAID, pool, swap, btrfs,
  swapped-disk, overlapping-partition and pivoted root-on-ZFS and btrfs layouts, plus hand-written
  layouts marked as such),
  `PoolDevicesIT` (the runner's own disks, read-only; the probe on a loop device mounted and
  mapped; a `/dev/mapper` path refused as a dm device; a pool with every vdev class; Incus making
  the pool Sail asks for on a blank loop device, and zpool refusing one that took ext4 after the
  checks, its ext4 kept; a loop device a process holds open plainly, through a device node of its
  own, refused until it lets go, and a partition of one whose whole device a process holds), `HostProvisionerTest.aDeviceMountedSinceItWasNamedIsRefusedAndUntouched`.*
- **Z3. One pool, one owner.** Sail only reads ZFS (`zpool list`); every pool,
  dataset, snapshot and copy is made or removed by Incus. *`HostProvisionerTest.aZfsPoolOnAWholeDiskIsIncussOwnAndTheProfilePutsContainersOnIt`.*
- **Z4. The ZFS cache cap is set before ZFS can take memory.** Step 3 writes
  `/etc/modprobe.d/sail-zfs.conf` (`options zfs zfs_arc_max=`, a tenth of memory, at most
  16 GiB and at least the 64 MiB ZFS honours, with `zfs_arc_min` lowered to half the cap where
  the ceiling falls under ZFS's default floor of a thirty-second of memory) before installing
  zfsutils-linux, and to the loaded module when ZFS is already in, so it holds from the next load
  and across reboots. Where `/` is on ZFS the module loads from the initramfs, which carries a
  copy of `/etc/modprobe.d`, so init runs `update-initramfs -u`. It decides from what the next
  boot applies, the merged configuration `modprobe --showconfig` lists from every file modprobe
  reads and the kernel command line: a cap the operator set there that Sail did not write, or a
  value in Sail's own file other than the one Sail writes for this box (the operator's edit), is
  kept, nothing written, and the step names it; a value only the loaded module holds is gone at
  the next boot, so Sail writes its own;
  the cap Sail wrote before is removed, so the operator's is the one modprobe applies whichever
  file sorts last.
  *`HostProvisionerTest.theZfsCacheIsCappedBeforeTheToolsThatLoadZfsAreInstalled`, `ZfsCacheCapTest`.*
- **Z5. A `dir` box keeps working unchanged.** A re-run of `host init --storage zfs --pool <new>`
  on a box whose profile exists adds the pool beside the old one and touches no container, no
  profile device and `host.yaml`'s storage; `host status` lists it. A project's workspaces and
  its template go on it (**Workspaces**). A pool name Incus already has with another
  driver is refused before anything is installed; a zfs pool Incus already has is kept, and a
  source flag that re-run would ignore is refused by name, while the command that made it, a disk
  or a file of the same size, runs again as a no-op: `--disk` and the pool's source are compared as
  the devices `realpath -e` resolves them to, so any name of the pool's device (its kernel path, the
  by-id path Incus holds, another link of the disk) is the same device, and a source that resolves
  to nothing is refused as the pool's device gone. A zfs pool Incus no longer has when the pool is made is never made from its
  zpool. A dry run reads the profile, pools and packages the box has, so it shows the base
  profile staying where it is and says whether Incus and ZFS would be installed, which bridge, pool
  and profile it would create, whether the image would be cached and the bridge rules added, and
  ends "Dry run done: nothing was changed" rather
  than "Server ready".
  *`HostProvisionerTest.aDirBoxWithContainersAddsAZfsPoolBesideItsOwnAndTouchesNoContainer`,
  `HostProvisionerIT`.*

Incus finds the ZFS tools when a pool is created, not only when it starts (its driver looks
them up on each load until one succeeds; only `incus info`'s list of supported drivers is read
at start), so a box whose Incus started first needs no restart. If Incus still says a tool is
missing, init restarts it only on a box with no container, saying so, and otherwise names
`systemctl restart incus` and stops. On a `zfs` pool a container's disk limit is the root disk's
`size`, which Incus sets as the dataset's `quota` (`zfs.use_refquota` would make it `refquota`;
Sail leaves the Incus default), so a write past it fails inside the container with "Disk quota
exceeded" and the snapshots of that container count against it. A copy of an instance's
snapshot on the pool is a `zfs clone` whose `origin` is the snapshot. `sail host status` lists
every pool with its driver, source and free space (`incus storage list`, `incus query
/1.0/storage-pools/<pool>/resources`). A paused container (`incus pause`) reads as
`ContainerState.Frozen`, `frozen` at the API and counted in `host status`; apply and `project
start` resume it with `incus start`, and stop, restore, rename and resize treat it as live.

**Workspaces** (`BoxInstances`, `WorkspaceTemplate`, `Workspaces`, `ProjectWorkspaceCommand`,
`ProjectTemplateCommand`). A project runs several workspaces on one box, each its own container
with its own tree, services and ports, cloned from the project's template. On a 4-vCPU cloud VM
the clone takes about half a second and a whole create, the clone, the start and making it its
own, about three (2.9 to 4.3 s over 32 creates, median 3.2 s).

- **The template** is the instance `<project>--tmpl`, built by the project's own
  `ProjectProvisioner` run against that name (`ProjectProvisioner.Instance`: the name every step
  addresses, the pool the launch puts it on, and the keys it is born with:
  `user.sail.template.of=<project>`). Its progress is kept in `template-provision-state.yaml`
  beside the project's own. Once built it is stopped, records three keys and is snapshotted as
  `v<n>`, so each version's snapshot carries its own record:
  - `user.sail.template.current=v<n>`;
  - `user.sail.template.inputs=<digest>`, the digest apply already records
    (`ProjectProvisioner.inputs`: the definition and the workstation key);
  - `user.sail.template.last`, the highest version ever given, so a number is never given twice.
- **Commands.**
  - `sudo sail project template build <p>` builds it unless its current version was built from the
    definition as it is.
  - `sudo sail project workspace create <p> <name>` builds it first when there is none or it is
    stale. It then clones it with `incus copy <p>--tmpl/v<n> <p>-<name>`, passing the workspace's
    keys at the copy: `-c user.sail.project=<p> -c user.sail.workspace=<name> -c
    user.sail.template=v<n> -c user.sail.created_ms=<ms> -c user.sail.creating=1`.
  - It starts the clone and makes it its own in one `incus exec` (`Workspaces.PREPARE`, constant
    text with the project, the workspace and each repo's path and base branch as its arguments):
    a new machine-id, the project's name as its hostname (which the in-container helpers read the
    project from), new SSH host keys made under that name, each repo fetched and on the branch
    named after the workspace, sshd restarted onto the new keys once systemd answers (it boots
    while the repos are fetched), and the dev user's bus. A fetch whose host does not resolve yet
    is asked again until 10 s have passed (`Workspaces.FETCH`, a deadline on bash's `SECONDS`, git
    run with `LC_ALL=C` so its words are the ones matched): a clone's address comes up a fraction
    of a second before its resolver, and more when several start at once. The script prints
    `sail-step <step> <epoch ms>` as each step ends, and `sail-count fetch_retries <n>`, how many
    times its fetches asked again.
  - Then come the shared files and the machinery, as `project start` reconciles them, and last one
    write: `user.sail.ready`, clearing `user.sail.creating` and the template's own keys
    (`user.sail.template.of`, `.current`, `.inputs`, `.last` and `user.sail.inputs`), which the
    clone carried from the copy: a workspace is no template.
  - It says `Created <name> from v<n> in 3.2 s (clone 0.5 s, start 0.4 s, ready 2.3 s)`, and
    `--json` adds `timings.ready_steps`, the readiness step by step in milliseconds (`identity`,
    `network`, `repos`, `sshd`, `bus`, `files`, `machinery`, `mark`), and `fetch_retries`, a count.
  - One create of a workspace runs at a time, under `<p>-<name>.workspace.lock`, and the template
    is read inside its own lock, so two creates of one stale or missing template build it once.
  - `list`, `shell`, `start`, `stop` and `delete` act on the workspace the seam finds.
    `delete` asks first, once the workspace is found, naming it and that its uncommitted work is
    lost (`--yes`); a missing workspace or `main` is refused without a question.
    `create`, `delete` and `template build` are root's, as `apply` and `destroy` are.
  - `GET /v1/projects/{p}/workspaces` lists the same rows, read in scope.
- **The seam.** `BoxInstances` reads the box's instances in one `incus list` and says what each one
  is, from its keys and its name:
  - a **workspace**: the one named `<p>-<w>` that carries `user.sail.project=<p>` and
    `user.sail.workspace=<w>`;
  - a **template**: the one named `<p>--tmpl` that carries `user.sail.template.of=<p>`;
  - a **stray**: either set of keys under another name, as a hand copy carries them;
  - **plain**: no key. This is the container of the project it is named after when the box holds
    that project, and otherwise not Sail's.

  Every command that walks the box's instances goes through the seam, and each takes the kinds
  below:

  | Command | Takes |
  |---|---|
  | the sync round's teardown | plain containers of leaving projects; their workspaces and template |
  | `project apply --all` | plain only |
  | `project list`, `GET /v1/projects` | plain only |
  | `agent status` | plain only |
  | `snaps prune` (all projects) | plain only |
  | the boot sweep | plain containers of held projects, and whole workspaces of them |
  | the authorized-keys rewrite | every container on remove; a project's container and whole workspaces on add |
  | `project stop --all`, `host status`, the overcommit hints, `host init`'s restart check | every instance, as before |
  | every verb that addresses a container by a project's name (`project start`, `stop`, `switch`, `shell`, `exec`, `logs`, `connect`, `containers`, `snapshot create`, `list`, `restore`, `delete`, `rename`, `resources set`, `config show`, `install-agent`, `repo add`, `service add`, `service remove`, `demo`, `apply`, `files`, `agent run`, `attach`, `logs`, `report`, `status`, `sweep`, `watch`) | plain only: `ContainerManager.queryState` and `queryInfo`, which each passes through, refuse an instance of the project's name that is a workspace, a template or a copy (`BoxInstances.Taken`), naming what it is and what frees the name; `snapshot prune`, `create` and `destroy`, which read no state, ask `BoxInstances.requireNotTakenFor` |
  | every API door that reaches a container by a project's name: dispatch and every run launch (build, review, fix, ad hoc, room wake, live member), serve and ports, snapshots and restore, skills share, agent status, logs and report, through `ProjectLoader` | plain only: the loader's `queryState` refusal is `409 conflict` with the reason and remedy |
  | the terminal host's open (`PtyAdmission.instance`, `HostLanes.Pty.admitInstance`) | plain only: asked once per open, outside the host's lock, of the one instance by name (`incus list ^<p>$`, `BoxInstances.lookup`) within 3 s, and refused, saying so, when Incus does not answer in that time; an attach and a ticket ask Incus nothing, an attach joining a session an open already admitted |
  | `skills ls`'s read of hand-made skills, `project create`, `POST /v1/projects` | plain only, by the same one-name lookup within 3 s (`BoxInstances.namedIfReadable`); an Incus that does not answer in time holds no instance of the name |
  | the resource reconcile after a sync round | plain only: a project whose name is another's instance has no container here, and is skipped |
  | the sync round's arrival notice | names once, in the round that brings it, a project whose name is another's instance, with what frees the name |
  | `project create` and `POST /v1/projects` | refuse a name this box holds as a workspace's, a template's or a copy's instance |

Each row with the tests that prove it:

- **W1. The project's container is untouched.** No verb writes to, copies from, snapshots or
  restarts the instance named after the project, which is the default workspace `main`. `main` is
  listed, and every other verb refuses it, naming the project verb that does it (`project start`,
  `stop`, `shell`, `destroy`).
  *`WorkspacesTest.noVerbWritesToCopiesFromSnapshotsOrRestartsTheProjectsOwnContainer`,
  `WorkspacesTest.theDefaultWorkspaceIsRefusedByEveryVerbThatWouldTouchItNamingTheProjectVerb`,
  `ProjectProvisionerTest.aTemplateIsProvisionedIntoItsOwnInstanceOnItsPoolAndTheProjectsContainerIsNeverNamed`,
  `WorkspaceIT` (the container's state, snapshots and config unchanged).*
- **W2. A workspace is a clone of a template snapshot.** Its dataset's ZFS `origin` is the
  template's `v<n>`. Sail copies only from a snapshot, never from an instance (whose snapshots
  would be sent whole), and refuses a template that is not on a ZFS pool.
  *`WorkspacesTest.aFirstCreateBuildsTheTemplateAsV1OnTheZfsPoolThenClonesItsSnapshotAndSaysWhatItTook`,
  `WorkspacesTest.aTemplateThatIsNotOnZfsIsNeverCopied`, `WorkspaceIT`.*
- **W3. The instance is the record.** A workspace is the instance the seam says it is, with its
  keys set as the copy makes it. Listing reads only Incus, deleting the instance deletes the
  workspace, and no row is written.
  *`WorkspacesTest.theListShowsTheDefaultWorkspaceThenEveryCloneWithItsStateTemplateTimeAndAddress`,
  `BoxInstancesTest.eachInstanceIsOneKindToldFromItsKeysAndItsName`.*
- **W4. Names are validated once and never parsed.**
  - A workspace name follows the project-name rule, is not `main`, and does not end in `-`.
  - `<project>-<name>` and `<project>--tmpl` are at most 63 characters.
  - A new workspace is no instance Incus has, no project the catalog holds, and no name ending
    `--tmpl`. A copy carrying its keys under another name refuses it too.
  - A template is no project's name.
  - In the other direction, no project takes a template's name (`NameValidator.requireNewProjectName`,
    at create and in `ProjectStore.create`). A project created on this box, by `project create` or
    `POST /v1/projects`, is refused when the box holds an instance of its name that is a workspace,
    a template or a stray. A box that holds none cannot know, so such a project can still reach a
    box that does, by sync or through another box's API. There it never takes the instance: the
    round that brings it says once that project `<p>-<w>` cannot take instance `<p>-<w>` because it
    is workspace `<w>` of `<p>`, naming `workspace delete` or another name, and every door that
    resolves a container from the project's name refuses it (W10). A run of such a project cannot
    exist here, since its launch is refused, so the doors that act on a run's project are never
    reached.
  - A workspace is found only as the one instance whose name and keys both match. Several, or a
    keyed instance under another name, are refused, naming them all.

  *`WorkspacesTest.aNameThatCollidesIsRefusedNamingWhatItCollidesWith`,
  `WorkspacesTest.aWorkspaceIsTheOneInstanceNamedForItAndCopiesCarryingItsKeysAreRefusedByName`,
  `ProjectCreateCommandTest.aProjectTakesNeitherATemplatesNameNorTheInstanceOfAWorkspace`,
  `ProjectDefinitionOperationsTest.aProjectIsNotCreatedUnderTheNameOfAWorkspaceOrTemplateThisBoxHoldsAndIsWhereNoneIsHeld`,
  `ScopeChangeRoundTest.aProjectThatSyncsInUnderAWorkspacesNameLeavesTheWorkspaceAloneAndSaysSoOnce`,
  `BoxInstancesTest.noProjectIsGivenAnInstanceThatIsAWorkspaceATemplateOrACopy`.*
- **W5. A clone shares no machine identity.** The per-workspace step regenerates the machine-id
  and the SSH host keys, which every clone otherwise has from the template, the keys after the
  hostname, so their comment names the project. Its template's keys are cleared with the ready
  mark.
  *`WorkspaceIT`; the script is pinned by `WorkspacesTest.theScriptThatMakesACloneItsOwnIsPinnedWhole`.*
- **W6. Times are measured and said.** `create` and `start` print each phase and return it in
  JSON (`timings.clone_ms`, `start_ms`, `ready_ms`, `total_ms`), measured on `System.nanoTime`;
  a create's `ready_steps` breaks the readiness down, the script's steps as it timed them in the
  container and the host's after them.
  *`WorkspacesTest.startResumesAStoppedWorkspaceAndSaysWhatItsResumeTook`,
  `WorkspacesTest.aFirstCreateBuildsTheTemplateAsV1OnTheZfsPoolThenClonesItsSnapshotAndSaysWhatItTook`,
  `ProjectWorkspaceCommandTest.aCreateSaysEachPhaseItTookInSeconds`.*
- **W7. A template version is current only once it is whole.**
  - The record moves to `v<n>` after the build and the stop, and the snapshot follows it, so the
    snapshot's own keys are true. A version is current only when it is recorded and its snapshot
    exists: one recorded whose snapshot failed is stale, and the next build is the one after it.
  - A failed build leaves the previous version current, makes no snapshot, and stops a template it
    started or launched; one it found running is left running.
  - A record that cannot be written makes no snapshot.
  - A stale template is converged on its disk, as apply converges a container: packages, repos and
    services the definition dropped stay in it. It is not built again from nothing.
  - The template's lock is held while it builds, and the template is read inside it, so a create
    that waited for another's build clones what it built.

  *`WorkspacesTest.aFailedBuildLeavesThePreviousVersionCurrentMakesNoSnapshotAndStopsTheTemplateItStarted`,
  `WorkspacesTest.aFailedBuildLeavesATemplateItFoundRunningRunningAndOneItLaunchedStopped`,
  `WorkspacesTest.aVersionWhoseRecordCannotBeWrittenIsNeverSnapshottedSoThePreviousStaysCurrent`,
  `WorkspacesTest.aVersionRecordedButNeverSnapshottedIsStaleSoTheNextBuildIsTheOneAfter`,
  `WorkspacesTest.theTemplatesLockIsHeldWhileItBuilds`,
  `WorkspacesTest.twoCreatesOfOneStaleTemplateBuildItOnceAndBothCloneThatVersion`,
  `WorkspacesTest.twoCreatesOnABoxWithNoTemplateBuildItOnceIntoAFreshInstanceAndBothClone`,
  `WorkspacesTest.aVersionNumberIsNeverGivenTwiceEvenWhenEverySnapshotIsGone`.*
- **W8. ZFS or nothing.**
  - A new template goes on the base profile's pool when it is ZFS, else on the one ZFS pool beside
    it. Several ZFS pools beside a pool that is not ZFS are refused, naming them.
  - An existing template stays on its pool.
  - On a box with no ZFS pool, every verb that makes or changes a workspace or a template is refused
    before anything changes, naming `sudo sail host init --storage zfs`. Listing answers the default
    workspace alone.

  *`WorkspacesTest.everyVerbThatMakesOrChangesOnABoxWithNoZfsPoolIsRefusedBeforeAnythingChanges`,
  `WorkspacesTest.aBoxWithNoZfsPoolListsItsDefaultWorkspaceAlone`,
  `WorkspacesTest.anExistingTemplateStaysOnItsPoolWhateverPoolsWereAddedSince`.*
- **W9. One decision.** Every verb first reads the project from the catalog as the box's person,
  through `ReadFilter`, which is what `project apply` asks. A catalog this account cannot read is
  said to be one. The read route declares `Gate.READS_IN_SCOPE` and reads through
  `ProjectGate.readable`. No rule was added.
  *`RouteGateSweepTest`, `ScopedReadsTest`, `OneDecisionSweepTest`, `AccessDoorsBaselineTest`.*
- **W10. Every instance on a box is exactly one kind, told from its keys.** No command takes a
  workspace, a template or a stray for a project: each one that walks the box's instances asks
  `BoxInstances`, the only class that lists them.
  *`BoxInstancesSweepTest`, `ProjectVerbsTakeNoWorkspaceTest` (each single-name verb run against
  a workspace, an unfinished workspace and the template, and refused having only read Incus;
  `apply <p>` and `destroy <p>` included; dry runs read Incus for real), `WorkspaceIT` (the same
  verbs on a real workspace and template), `ProjectRenamerTest`, `ProjectLoaderTest`,
  `HostPtyTest.aTerminalNeverOpensInAWorkspaceForAProjectNamedLikeIt`, `SkillOperationsTest`,
  `ContainerManagerTest.aContainerAddressedByAProjectsNameThatIsNoProjectsIsRefusedNamingWhatItIs`,
  `ProjectApplyCommandTest.aBulkApplyNeverTakesAWorkspaceOrATemplateForAProject`,
  `SailOperationsTest.projectsNeverListAWorkspaceOrATemplateAsAProject`,
  `ScopeChangeRoundTest.aScopedRoundLeavesTheWorkspacesAndTemplateOfAProjectInScopeAlone`,
  `ContainerSetupSweepTest`, `ContainerAuthorizedKeysTest`.*
- **W11. A project's instances go with it.** `project destroy <p>` removes the project's
  workspaces and its template with its container, naming each in its confirmation. When a project
  leaves a box's scope, the sync round removes them the same way, and only then. A copy carrying
  the project's keys under another name is not Sail's: it stays, said once, in the round that
  drops the project.
  *`ProjectDestroyCommandTest.destroyTakesTheProjectsWorkspacesAndTemplateAndNothingElse`,
  `ProjectTeardownTest`, `ScopeChangeRoundTest.aProjectThatLeavesTheBoxTakesItsWorkspacesAndTemplateWithIt`.*
- **W12. A workspace is whole or it is not one.** `user.sail.ready` is written last, in the write
  that clears the copy's `user.sail.creating`. A workspace without it:
  - is listed as `incomplete`;
  - is refused by `start` and `shell`;
  - is deleted and made again by `create` when it carries `user.sail.creating`, a create that did
    not finish; one without either had its mark taken off by hand, and `create`, `start` and
    `shell` refuse it alike, naming `workspace delete`;
  - is removed by `delete`;
  - is never swept, and never given a new key.

  One create of a workspace runs at a time: of two of one name, one makes it and the other is
  refused, and the workspace is never deleted under the one that made it.

  *`WorkspacesTest.aWorkspaceIsMarkedWholeLastAfterItsFilesAndMachinery`,
  `WorkspacesTest.anIncompleteWorkspaceIsListedAsSuchNeverStartedOrEnteredAndReplacedByTheNextCreate`,
  `WorkspacesTest.aWholeWorkspaceWhoseMarkWasTakenOffByHandIsNeitherReplacedNorUsedButNamesItsDelete`,
  `WorkspacesTest.twoCreatesOfOneNameMakeOneWorkspaceAndTheOtherIsRefusedLeavingItWhole`.*

**Known limits.**
- **The template is its owner's.** A clone carries the box owner's GitHub token
  (`/home/dev/.git-credentials` and gh's `hosts.yml`), git identity (`~/.gitconfig`) and the
  workstation key in `authorized_keys`, as the project's own container does. On a box of one
  person, that is the same person.
- **Keys and the template.**
  - The workstation key is in the digest the template is built from, so revoking it makes the
    template stale and the next create builds a new version without it. Older versions keep the
    old key, but no create clones them again.
  - A new pairing reaches the project's container and its whole workspaces at once. It replaces
    the workstation key, which is in the digest, so the template takes it only when the next create
    rebuilds it.
  - Key lines added to `authorized_keys` by hand are not tracked at all.
- **Hand copies are not Sail's.** A copy of a workspace made with `incus copy` carries the
  project's keys under another name: every verb refuses it, and it outlives its project, keeping
  the project's tree and the owner's credentials (and, through `deleted/`, the dataset of a
  template version it was cloned from). Its owner deletes it.
- **The resolver wait is exercised by the fleet alone.** A test body reaches no network, so the
  wait for a repo's host to resolve (`Workspaces.FETCH`) is pinned and run against a stand-in git
  in `WorkspacesTest`. The fleet's storage row runs it against a real resolver: it gives acme the
  public `octocat/Hello-World` for the row, makes ten workspaces at once, checks each is whole
  with the repo on its own branch, and writes each one's ready steps and fetch retries into the
  report. Until that row has run green on this release, the wait is not proven against one.
- **Version numbers live with the template instance.** `user.sail.template.last` keeps a number
  from being given twice only while `<p>--tmpl` exists: deleting it by hand starts again at `v1`,
  while older workspaces still record the `v1` they were cloned from.
- **Older dry runs read through the dry shell.** `project destroy`, `stop` and `switch` read Incus
  for real in a dry run; `project start`, `snapshot create`, `delete` and `restore`, and `snapshot
  prune` across every project, still read what a dry run is told (nothing), as on main before this
  work.
- **What the digest does not see:** the base image moving under its alias, package, runtime and
  harness versions drifting with time, the sail binary (machinery is re-stamped per workspace), and
  git tokens.
- **Deleting a template version by hand** (`incus snapshot delete <p>--tmpl v1`) while workspaces
  were cloned from it keeps them running: Incus renames the snapshot under `deleted/` until the last
  clone goes, and frees its space only then.

**The stack** (`ProjectServer`, `StackOperations`): a project's services and its processes,
each process an app the dev user runs, declared with the port it listens on. `sudo sail project
serve`, `POST /v1/projects/{p}/serve` and the container's `portal` run one `StackOperations`
verb; Mast's Portal forwards what it reports. Each row with the tests that prove it:

- **I1. The definition says how the project comes up.** `processes.<name>` holds `command`,
  `workdir`, `port` and `env`, checked when it is read: a port is 1024 to 65535 and claimed by
  one process or service. *`ProjectDefinitionTest.aPortIsClaimedByOneProcessOrServiceOnly`,
  `ProjectDefinitionTest.aProcessThatCannotRunAsDeclaredIsRefusedNamingItsField`.*
- **I2. `serve` brings the stack up and says what listens where.** Services start as apply
  starts them; each process is a script and a `systemd --user` unit `sail-process-<name>`
  whose command is the script's last line, so systemd never expands it; a changed unit restarts,
  an unchanged one only starts, an undeclared one is removed; every port is watched, one
  container command a look, until it listens or its process exits. Only `serve` starts a
  process. *`ProjectServerTest`, `ProjectServeIT`.*
- **I3. Both doors serve the same.** The API and the container's socket answer `serve`,
  `DELETE serve` and `ports` through `StackOperations`, for a project the caller may read;
  serving and stopping are writes, refused to a read-only caller.
  *`StackOperationsTest`, `ApiRouterTest.aViewerReadsThePortsButMayNotServe`,
  `LocalApiStackTest`, `ScopedReadsTest`.*
- **I4. A forward lives in Mast and needs nothing on the Mac.** Mast binds each listening port,
  or the next free one above it, on `127.0.0.1` and pipes each connection over the container's
  session to `localhost:<port>` inside; its backend's retirement closes them. *Mast
  `forward.rs` tests, `PortalPanel.test.tsx`.*
- **I5. The portal is one click and tells the truth.** Mast's Portal brings the stack up, opens
  the browser at the first process that listens, once, looks again while a port is starting or
  has not listened yet, shows an exited port's line with a Copy for it, and shows the box's
  own sentence for a refusal, a stopped container's included. *`PortalPanel.test.tsx`.*
- **I6. A scoped FDE sees their projects' ports and no other**, at both doors.
  *`StackOperationsTest.aProjectOutsideTheCallersScopeIsOneTheCatalogDoesNotHold`,
  `LocalApiStackTest.aRunReachesOnlyItsOwnProjectsStack`.*
- **I7. A project that declares no process port serves its services as before**, and nothing
  starts a process but `serve`. *`ProjectServerTest.aDefinitionWithoutProcessesServesItsServicesAsBefore`.*
- **I8. Any agent can bring the stack up, see it fail and fix it.** Sail's `sail-portal` skill
  (`scripts/portal up|ports|log|stop`) is installed beside `spec-board` for every harness.
  *`PortalSkillTest`, `SailSkillsTest`, `ProjectSkillLaunchTest`.*
- **I9. The socket serves the stack to the project's own agents only**: a run reaches its own
  project, a read-only room member reads the ports and may not serve or stop. *`LocalApiStackTest`.*

**The `sail-api` service** (`SystemdServiceInstaller`) is the per-box control plane: a `sail
server start` systemd unit, in system scope under root (`/etc/systemd/system`, which `sail
init` always installs) or in user scope (`systemctl --user` with linger) for an account that
owns a box in its own home and runs `sail host service install` itself. It opens the control-plane SQLite database,
runs registered migrations, ensures an admin token, and serves the REST API and the SSE
event stream (`/v1/events/stream`) on loopback, the passkey endpoints when configured, and
a Unix-socket listener so project containers publish events and drive `spec` over the
bind-mounted socket with no TCP. Requests on that socket authenticate with the per-run
credential minted at dispatch (`SAIL_RUN_CREDENTIAL`), which resolves to the run's agent
principal; a missing or revoked credential is refused with 401. Every box runs it, and it
is what lets an engineer dispatch agents locally.

What changed is read from the change feed (below). A spec's own event history is
`GET /v1/events?spec=<id>[&since=<eventId>][&limit=]`, served from the `EventStore`:
RECORD-class events only (telemetry is pruned by retention and never served from history),
oldest first, with `since` an exclusive event-id cursor. The record is **node-local** — there is no event replica,
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

**Sail writes no context file.** How a codebase is built, tested and verified is the
repository's own `AGENTS.md` to say, which both harnesses read natively (Claude Code from the
working directory and every directory above it, Codex from the git root down to the cwd); the
loop's rules are in the two prompts sail writes (below, *Prompts, briefs and skills*), which
every harness reads on every run; and what used to be generated from the definition (runtimes,
repos, services) is discoverable in the container. Sail generates no home-level file, no
methodology skill and no language rule. The one skill it ships, `spec-board`, is installed
beside the project's own skills so an interactive session finds the `spec` CLI.

**The harness contract** (`ai.singlr.sail.harness`): sail drives two coding harnesses,
Claude Code and Codex, and will drive more. Everything that differs between them sits behind
one interface, `Harness`, with one package-private adapter per harness (`ClaudeCode`,
`Codex`) and a registry, `Harnesses`, that resolves the name a definition uses (`of`), lists every
harness (`all`) and names the default (`DEFAULT`). No main code outside the adapters names
one, compares a harness with a name or switches on one: a capability is an answer the
adapter gives, not a name a caller recognises. Adding a harness is adding one adapter.

| `Harness` method | What it answers |
|---|---|
| `yamlName()`, `binaryName()`, `displayName()`, `installCommand()` | identity: the name a definition uses (`agent.type`, `agent.install`), the binary on PATH, the name people read, how to install it |
| `skillsDir()`, `skillFolder(name)` | where skills live under `$HOME`, and the folder of one |
| `headless(Launch)` | the command for a build, a reviewer, a fix agent or a full chat turn; fresh when the launch's `resumeSessionId` is null, resumed otherwise |
| `readOnly(Launch)`, `readOnlyRefusal()` | the room lane's harness-restricted command, or why the harness has none |
| `live(LiveLaunch)`, `liveSettings()` | the argv of a live member (one process prompted over stdin, read over stdout) and the settings file it runs under, or empty for a harness with no live mode |
| `interactive(fullPermissions)`, `attach(sessionId)` | the TTY session, fresh or resuming a recorded conversation exactly by id |
| `efforts()`, `requireEffort(effort)` | the reasoning efforts the harness takes, and the one refusal, naming it, of any other (M15) |
| `usage(agentLog, transcript)` | what a run cost, from the harness's own report: tokens, dollars and model, or empty for none |
| `loginTunnelPort()`, `interactiveTip()` | which port the login flow needs forwarded, what to tell an engineer before an interactive session |
| `hooks()` | the harness's `HookFile`: which of its events run which of sail's `SailHook`s |
| `isSafeSessionId(id)`, `requireSafeSessionId(id)` | whether a hook-reported, replicated session id may touch a shell string, and the one check every adapter makes before it does |

A `Launch` carries the task file, whether every action is auto-approved, the model, the
reasoning effort, the session to resume and whether to stream. A malformed session id throws
before it can reach a shell string, in `headless`, `readOnly` and `attach` alike; `readOnly`
ignores permissions, and a harness with a refusal throws rather than
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
belt-and-brace the room lane's top credentials. A live member runs under a second file,
`~/.sail/claude-live.json`, with those two entries and no `hooks` key: sail reads a live
process, so nothing in it reports back.

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
own: `agent.guardrails` bounds a build, a fix run, an ad-hoc run and a chat turn (default
`4h` / `20m` / `stop`), `pipeline.guardrails` a reviewer (default `45m` / `20m`
/ `stop`). Both parse through the one `Guardrails` record, which refuses an invalid
duration, a zero limit or an unknown action where the definition is read, naming the block,
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
  blocking exec, and no lane has a log, unit or pid path of its own shape. The one run that is
  not a unit is a live room member's (below): the same row and the same files, under a unit
  name systemd never knows.
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
  asks by lane: a build, and a fix agent held to the same protocol, for a clean, pushed tree
  behind a pull request whose checks it watches; a reviewer for nothing, since its last
  message is the verdict the pipeline parses. The pipeline's router, not a missing hook, is
  what keeps a reviewer's stop from starting a review.
  *`SailStopGateTest.aFixRunIsHeldToTheBuildsProtocolAndToldToWatchItsChecks`,
  `SailStopGateTest.aReviewerIsNeverBlockedByADirtyTreeNorByTheRoom`, and the launch
  environment asserted in P1's first test.*
- **P5. One guardrails record, per-lane values.** `Guardrails` is the only limits type,
  parsed one way; `ProjectDefinition.guardrailsFor(lane)` is the one place a lane is mapped to
  its block and its defaults, and `lifetimeFor(lane)` beside it bounds the run's credential.
  The watcher receives its run's limits at spawn.
  *`aReviewersLimitsDefaultTo45MinutesAFixRunTakesTheBuildLanesAndAnEditAppliesToTheNextRun`,
  `ProjectDefinitionTest.eachLaneReadsItsOwnGuardrailsAndItsOwnDefaults`,
  `ProjectDefinitionTest.aRunsLifetimeIsItsLanesLimitAndABuildNothingBoundsHasNone`,
  `ProjectDefinitionTest.aReviewLaneGuardrailsValueThatIsNoBlockIsRefusedRatherThanReadAsTheDefaults`,
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

**Live room members.** A human posts in a room and its engaged agent answers. Where the
agent's harness has a live mode (`Harness.live`, Claude Code's streaming mode; Codex has none)
the member is one process that stays up between turns: `LiveMembers` starts it over pipes
(`AgentProcess`, in production `ContainerAgentProcess`: one `incus exec` client whose stdin
and stdout are the agent's), writes each prompt to its stdin as a JSON line the moment a
message lands, and reads every line it writes (`LiveStream`). Sail is the only thing that
speaks for it: the member runs no hook and posts no answer of its own. The words: the
*member* is the agent on the room's roster (`Engagement`); the *live member* is the process
serving it while it is awake; a *turn* is one prompt (or several queued) up to its `result`;
a *wake* is the one-off run of a spec's own agent when nobody is engaged, and of a member
whose harness has no live mode. The contract, each row with the tests that prove it
(`LiveMemberTest`, `LiveMemberLanesTest`, `LiveMemberDisplacementTest` and
`LiveMemberEdgesTest` drive the production `SailOperations`, `RoomWakeReactor`, `RunTracker`
and bus over a fake container shell, a fake process and a fake clock; a test named without a
class is `LiveMemberTest`'s):

- **L1. One live member per room with a member, prompted at once.** `RoomWakeReactor` hands a
  human message in such a room to `LiveMembers.deliver` with no debounce and no cooldown, under
  the same `wakeable()` and `RoomWakePolicy` that decide a wake. A member with no process gets
  one on that message; a message that arrives during a turn is written at once and starts the
  next turn; the member's own reply prompts nothing. What is written is the room's human
  messages the run was not yet handed, so a message that arrived while the member could not
  start is in its next prompt. Messages are written oldest first, as many to a prompt as
  `PromptConversation.MAX_CODE_POINTS` holds and none cut or left out: what does not fit is
  the next prompt, written straight after, and a message longer than a prompt is written
  across several, each part saying that more follows (`LiveMemberPrompt.messages`). A message
  is recorded as delivered to the run only once the prompt that ends it was written, so one a
  process took part of, or never took, is handed whole to the next; a fresh session's room
  follows its opening the same way.
  *`aHumanMessageStartsOneLiveProcessAndIsItsFirstPrompt`,
  `aMessageDuringATurnIsWrittenAtOnceAndStartsTheNextTurn`,
  `aMessageAfterATurnGoesToTheSameProcess`,
  `LiveMemberEdgesTest.aMessageLongerThanAPromptIsWrittenAcrossPromptsAndIsDeliveredOnceAllOfItIs`,
  `LiveMemberEdgesTest.aMessageAProcessTookOnlyPartOfIsHandedWholeToTheNext`,
  `LiveMemberEdgesTest.aFreshSessionIsShownARoomLongerThanAPromptOnePromptAfterAnother`,
  `LiveMemberPromptGoldenTest` (pages),
  `RoomWakeReactorTest.aRoomWhoseMemberIsLiveIsPromptedAtOnceAndNeverWoken`,
  `RoomWakeReactorTest.aLiveMemberIsPromptedOnlyByWhatWouldWakeIt`,
  `LiveStreamTest.aPromptIsOneUserMessageLine`.*
- **L2. Sail reads the process; the agent's reply is its text.** `system/init` records the
  session on the run (`session_source` `stream-json`) and publishes `agent_session_started`;
  the first line of a turn, a thinking one included, publishes `agent_turn_began`, so a room
  can say the member is working before any of its answer is written;
  a `text_delta` becomes `agent_text_delta`, fragments within 50 ms of one another told as
  one; a `tool_use` block becomes `agent_tool_call` `started` with the tool and its first
  string argument clipped to 120 code points, its `tool_result` the `finished`; `result` ends
  the turn: the turn's text is posted through `postRoomMessage` as the run's principal under
  the run's actor — the door and the actor the `spec` CLI gets, so the message rule, the
  announcement and the sync trigger are the same — and `agent_turn_ended` names the message.
  A turn failed when the harness flags its result `is_error`, or when it ended before its
  answer because the process died or sail ended the member (stop, `max_idle`, `max_duration`,
  a membership change): its text is still posted,
  in the agent's own words, marked `error` on the message row (a synced mark beside `question`),
  and the end's `reason` is the result's subtype only when it names a failure, never `success`.
  Nothing reads the text.
  Every live event's `spec` is the room id and its data carries `run_id`, `room_id`,
  `principal` and `turn`. A subagent's lines, a line over 1 MiB and a line that is not one of
  these say nothing. The member's settings file (`~/.sail/claude-live.json`) carries the
  read-deny rules and `includeCoAuthoredBy: false` and names no hook; it is installed and
  fingerprinted beside the hooks file.
  *`sailReadsTheProcessAndPostsTheTurnsTextAsTheMember`,
  `aTurnsBeginningIsToldBeforeAnythingOfItIsAndOnceForEachTurn`, `LiveStreamTest`,
  `LiveMemberEdgesTest.textBlocksAreParagraphsAndLinesThatSayNothingAreIgnored`,
  `LiveMemberEdgesTest.aTurnThatStreamedNothingPostsItsResultAndAnEmptyOnePostsNothing`,
  `LiveMemberEdgesTest.whatTheAgentWritesIsBoundedWhereItEnters`,
  `LiveMemberEdgesTest.aFragmentHeldBackIsToldWhenItsWindowElapses`,
  `LiveMemberEdgesTest.aRoomThatRefusesTheReplyEndsTheTurnInError`,
  `LiveMemberEdgesTest.aTurnItsHarnessSaysFailedIsKeptInItsOwnWordsAndMarkedAnErrorAndAnAnswerIsNot`,
  `LiveStreamTest.aResultTheHarnessFlagsAnErrorFailedWhateverItsSubtypeSays`,
  `MessageSyncTest.theErrorMarkRidesTheSnapshotToEveryBoxAndAPlainRevisionIsWhatItWas`,
  `LiveMemberPromptGoldenTest`, `HarnessGoldenTest.claudeCodeLiveFullMember`,
  `HarnessHooksGoldenTest.claudeCodeLiveSettingsNameNoHook`,
  `ContainerSailSetupTest.fingerprintCoversEveryInstalledFile`.*
- **L3. The room is the record.** `agent_turn_began`, `agent_text_delta`, `agent_tool_call` and
  `agent_turn_ended` are `EPHEMERAL`: on the bus and the stream, never persisted, and no client
  may publish one (they are absent from `EventAuthority`'s list, so an admin's and a run's
  publish are both refused). `GET /v1/events/stream?room=<id>` scopes a stream to one room beside `project` and
  `type`, and its first frame, with no `id`, is `agent_turn_state`: where the room's live member
  stands, read under the member's lock after the stream subscribed (an event published while it is
  read follows it, older than it says; Mast takes nothing of a turn the frame names over) — `{run_id, turn, principal,
  open}` and, when the turn is not open, what its `agent_turn_ended` carried; `{open: false}`
  alone for a room with no live member. A reader who opens the room mid-turn, or reconnects
  after missing a turn's end, is told the truth rather than left to infer it. A stream the bus
  drops an event for is ended, so its client reconnects and is told again.
  *`EventTest.aLiveMembersStreamIsEphemeralAndNothingElseIs`,
  `SpecStoreAuditPersisterTest.aLiveMembersStreamIsNeverPersisted`,
  `EventDoorTest.aTypeTheRuleDoesNotListIsRefusedFromEveryone`,
  `EventDoorTest.aRunPublishesNothingButItsHooksOverTheSocket`,
  `EventAuthorityTest.aTypeTheRuleDoesNotListIsNoClients`,
  `SseHandlerTest.parseFilterScopesByRoomBesideProjectAndType`,
  `SseHandlerTest.aRoomsStreamIsFirstToldWhereItsMemberStandsWithNoIdAndOnlyWhatItsReaderMaySee`,
  `SseHandlerTest.aStreamTheBusDropsAnEventForIsEndedSoItsClientReconnects`,
  `SseHandlerTest.anEventPublishedWhileARoomsStateIsReadFollowsTheStateAndIsNotLost`,
  `LiveMemberTest.aRoomsStreamIsToldWhereItsMemberStandsAsItsLastEventLeftIt`,
  `RoomStreamIT`.*
- **L4. A live member is one run.** One row (`room` or `room-full`), one credential, one
  reservation and one pid file for the life of the process, under `AgentUnit.live(runId)`
  (`live-<runId>`), so no watcher is armed for it and the missed-stop reconciler, which reads
  the pid file first, leaves it alone while it lives. `LiveMembers.sweep` ends it when nothing
  was written or read for the lane's `max_idle` (a turn open that long is a `stall`) or at the
  lane's `max_duration`; a change of the room's seat, a restart or stop of sail and L5 end it
  too. Ending closes its stdin, so it finishes and exits; it is given 60 s to exit when it was
  only idle, 30 s to answer an open turn otherwise, then `kill -INT`, 10 s, `kill -TERM`. A
  turn cut short is posted as far as it got and told with `is_error` and the reason. Every end
  is `running` to `stopped` (or `failed`, for a process that died unasked while it owed an
  answer or before its run recorded its pid, with the end of its log as the reason) as one
  compare-and-set that revokes the credential, then the `agent_session_stopped` its watcher
  would have published, `source` `live`. The next message starts a process that resumes the
  newest session a live run of the same member and lane recorded. A process is prompted only
  for a run that still holds what it reserved and a seat that is still its member's: once it
  is registered, sail waits up to 30 s for the pid its wrapper writes, stamps it on the run
  with the compare a launched run is verified by (`RunStore.updateProcess`, which lands only
  on a `running` row) and reads the room's seat again; a process that wrote no pid, a run that
  was ended or a seat that changed while it started ends the process with nothing written to
  it, and one that wrote no pid has its run ended at once, as a launch that failed. So a live
  run that records no pid was never prompted, and one that records a pid has a
  pid file to be asked through. What a run reserved is freed on those two facts alone, by
  whoever does not hold its process: a run with no pid is ended by a compare the stamp loses
  to (`RunStore.transitionUnstarted`), and a run with one only when its container answers that
  the process is gone. On start, `LiveMembers.reclaim` ends by that rule the live runs reserved
  before this sail started (run ids are time-ordered, so a run this sail reserved as a message
  landed during start is its own, registered or not); a run whose process it cannot prove gone
  stays `running` with its reservation, is named on the server's stderr, and is finished by the
  missed-stop reconciler once its container says the process is gone. A prompt is written
  outside the member's lock, so a process that stopped reading its stdin is still read and still
  ended, its stdin closed on a thread of its own while the signals go on; the reader finishes
  the run only when no prompt is mid-write. A member whose output ended is over and gone from
  its room whatever its last look or its finish met: a guard that cannot look loses that look
  alone, and a run a store refused to finish is left to the claim that displaces it or the
  sweep that finds its process gone, never behind a member that takes no prompt.
  *`anIdleMemberEndsAndTheNextMessageResumesItsConversation`,
  `theGraceEachEndWaitsWithIsPinned`,
  `aProcessThatDiesMidTurnFailsItsRunAndTheNextMessageResumes`,
  `LiveMemberEdgesTest.aProcessThatExitsBeforeItAnswersFailsItsRunAndSaysWhy`,
  `LiveMemberEdgesTest.aPromptNobodyAnswersForTheIdleWindowIsAStall`,
  `LiveMemberDisplacementTest.aMemberPastItsLifetimeIsCutShortAndResumes`,
  `LiveMemberDisplacementTest.aTurnSilentForTheIdleWindowIsAStall`,
  `LiveMemberLanesTest.aRestartOfSailFinishesTheMembersItLeftAndTheyResume`,
  `LiveMemberLanesTest.aRestartKeepsTheRunOfAMemberItCannotProveGone`,
  `LiveMemberLanesTest.aRunLeftWithNoProcessRecordedIsReclaimedWithoutAskingItsContainer`,
  `LiveMemberLanesTest.aRunThisSailReservedIsNotOneItReclaims`,
  `LiveMemberLanesTest.aReadOnlyMemberWhoseGuardCannotLookAsItExitsIsStillFinishedAndGone`,
  `LiveMemberLanesTest.theMissedStopReconcilerLeavesALiveTurnAlone`,
  `LiveMemberLanesTest.aMemberInAnotherLaneOrFromAWakeIsNotResumed`,
  `LiveMemberEdgesTest.aMemberThatWillNotEndTakesNoPromptAndNothingStartsBesideIt`,
  `LiveMemberEdgesTest.aMemberDismissedWhileItStartedIsEndedBeforeItIsPrompted`,
  `LiveMemberDisplacementTest.aMemberWhoseRunAClaimEndedWhileItStartedIsNeverPrompted`,
  `LiveMemberEdgesTest.aMemberIsPromptedOnlyOnceItsRunRecordsThePidItsWrapperWrote`,
  `LiveMemberEdgesTest.aProcessThatNeverWritesItsPidIsEndedUnpromptedAndItsRunIsFreedAtOnce`,
  `LiveMemberEdgesTest.aProcessThatEndsBeforeItWritesItsPidFailsItsRunAndSaysWhy`,
  `LiveMemberEdgesTest.aMemberWhoseStartFailsIsEndedAndNeverPrompted`,
  `LiveMemberDisplacementTest.aMemberThatStoppedReadingItsStdinIsStillReadAndIsStillEnded`,
  `RunStoreTest.aRunThatRecordedNoProcessIsEndedAsUnstartedAndItsStampThenLosesTheCompare`,
  `RunStoreTest.aRunThatRecordedItsProcessIsNotEndedAsUnstarted`,
  `ContainerAgentProcessTest`,
  `ReviewAgentLoopIT.aLiveMembersProcessStartsUnderItsInvocationAndIsPromptedOverItsStdin`.*
- **L5. A claim or an attach displaces a live member; nothing else does.** A build, ad-hoc,
  reviewer or fix claim that a live member's run holds ends that member
  (`RunReservation.Displaceable`, outside the claim lock) and is made again, for up to three
  holders; a chat lane's own claim, a claim another run holds and a member that would not end
  are refused as before. `sail agent attach` on the member's run ends the member and then
  opens the terminal on its session, and while that terminal is live no process is started
  over the same session; the pty host's dispatch authority may list sessions for that
  question, and a host that is there and cannot be asked has not said the session is free: the
  member is not started, any claim it made is given back, and the room's next message asks
  again. The member resumes on the room's next message once its repos, or its session, are
  free. A process is owned by the sail that started it: another process on the box (a CLI
  dispatch, the attach) cannot close its stdin and ends it by signal through its pid file, and
  whichever sees it gone finishes its run. It frees the run by L4's two facts: a member that
  has written no pid yet is ended as never started, and its owner, whose stamp then fails,
  ends the process unprompted; a member that recorded its pid is signalled and its run kept
  until the container answers that it is gone.
  *`LiveMemberDisplacementTest` (an idle member, a turn that answers, one that never does, a
  member that will not end, a build's hold, another room's member, a fix claim, a terminal,
  the race with one, a session host that cannot be asked, another process, one that displaces
  a member before its pid is written, and one as it is first prompted),
  `RunReservationTest.aClaimEndsThreeMembersInTurnAndIsThenRefusedAsAnyHeldClaimIs`,
  `RunReservationTest.aChatLanesClaimAndAClaimAnotherRunHoldsDisplaceNoOne`,
  `AgentAttachCommandTest.aLiveMembersRunIsTakenOverNotRefused`,
  `PtyHostYieldTest.aSessionIsLiveWhoeverOpenedItAndNoHostMeansNoSession`.*
- **L6. The lane's guard is the lane's, per turn.** A read-only member runs under the
  read-only invocation with every prompt denied (`--permission-prompts none`); its commit
  baseline is saved before each prompt, and as a turn opens that no prompt armed, and consumed
  by `guardRoomRun` at each `result` and at the run's end, so a change is caught in the turn
  it happened in. A full member holds its repo reservation.
  *`LiveMemberLanesTest.aReadOnlyMemberIsGuardedTurnByTurn`,
  `LiveMemberLanesTest.aTurnAQueuedMessageStartsIsGuardedFromItsFirstLine`,
  `LiveMemberLanesTest.aReadOnlyMemberThatEndsMidTurnIsStillLookedAt`,
  `HarnessGoldenTest.claudeCodeLiveReadOnlyMember`.*
- **L7. Spec-less rooms are rooms.** A member of a room with no spec has a live member keyed
  by the room id: its run names the room, its session is found through the room's runs, and
  its events and stop carry the room id.
  *`LiveMemberLanesTest.aRoomWithNoSpecHasALiveMemberKeyedByTheRoom`.*
- **L8. The wake lane is untouched.** A spec whose agent is not engaged is woken as before:
  the debounce, `RoomWakePrompt`, the hooks, the relay and the stop gate.
  *`RoomWakeReactorTest`, `RoomWakeLaunchTest`, `RoomWakePromptTest`,
  `aMemberDismissedMidTurnIsCutShortAndTheRoomFallsBackToItsWake`.*
- **L9. A member whose harness has no live mode is woken per turn.** A Codex member keeps the
  whole per-turn engaged path: the short debounce, the engaged prompt, the resumed session,
  the owed-turn refire and the engagement sweep, none of which a live member's room uses.
  *`LiveMemberLanesTest.aCodexMemberKeepsThePerTurnWake`,
  `RoomWakeReactorTest.aMemberWhoseHarnessHasNoLiveModeKeepsThePerTurnWake`,
  `RoomWakeReactorTest.aLiveMembersRoomOwesNoTurnOnAStopOrASweep`,
  `HarnessGoldenTest.codexHasNoLiveMode`.*

**Home rooms.** Every FDE has one standing conversation per project with the project's agent:
the room `<project>--<handle>` (`HomeRoom.idOf`: when a room id cannot carry the pair verbatim,
with upper case, '.', '_', a `--` in the handle (so `acme--ada`/`bob` and `acme`/`ada--bob`
never share an id) or too many characters, it is `home--` and the pair's SHA-256, 64 hex
characters no handle can spell), titled with the project's name, assigned to the FDE, its
roster the *project's member* (`agent.member` in the definition: `agent` defaulting to
`agent.type`, `model` to the agent's default, `mode` to `full`). `sail project apply` makes it
(`HomeRoom.ensure`, the last step of `ProjectApplyCommand.converge`) through the door the API
takes, `POST /v1/rooms` with a `member`, as the box's FDE: the member is admitted as
`addRoomMember` admits one, and the room is written with it seated, in one revision. Each clause with the tests that prove
it (`HomeRoomTest` unless another class is named):

- **H1. A project's home room exists on the box that applies it.** Once the container runs with
  the member's harness installed, the apply leaves the room created and engaged; a second apply
  changes nothing. A box that names no FDE and a definition with no agent have none, and the
  apply says why once; every valid handle has one. A refused member creates no room, so nothing
  syncs and the next apply creates it seated: a room that seats no one is always its owner's
  choice, never a refusal's.
  *`theFirstApplyCreatesTheFdesRoomEngagedWithTheProjectsMember`, `aSecondApplyChangesNothing`,
  `aRefusedMemberCreatesNoRoomAndTheNextApplyCreatesItSeated`,
  `aBoxThatNamesNoFdeHasNoHomeRoom`, `aHandleARoomIdCannotCarryVerbatimStillGetsAHomeRoom`,
  `theLongestProjectAndHandleStillNameARoomOfTheirOwn`,
  `HomeRoomIT.anApplyEngagesOnceAndThenTheRoomIsTheFdes`.*
- **H2. The home room is the FDE's.** Its assignee is the FDE, so their box serves its member,
  and only they or an admin on main change its roster.
  *`eachFdeGetsTheirOwnRoomAndNeitherMayChangeTheOthers`.*
- **H3. A home room is a room.** Nothing in the loop, the harness, the journal, sync or scope
  reads its id; it syncs, is scoped and wakes as any room. Only a room of that project, owned by
  the FDE, under an id no spec holds is a home room: an id a spec holds (its own room included)
  refuses the apply with the API's sentence, a room of another project under the id refuses it
  naming that project, and a room someone else owns refuses it naming the owner.
  *`anIdTakenByASpecRefusesTheApply`, `aSpecCreatedThroughTheApiUnderTheIdRefusesTheApply`,
  `theFdesOwnRoomInAnotherProjectRefusesTheApply`,
  `aRoomSomeoneElseTookUnderTheHomeRoomIdRefusesTheApply`.*
- **H4. The definition decides, once.** `agent.member` is read when the room is created; a later
  definition does not rewrite an engaged roster, and an FDE's disengage wins. A member the
  project does not install, declared or the `agent.type` default, an unknown mode or a model
  that is not one shell-safe token is refused when the definition is read, before anything is
  provisioned.
  *`aLaterDefinitionDoesNotRewriteAnEngagedRoster`, `aRoomTheFdeDisengagedStaysDisengaged`,
  `ProjectDefinitionTest.aMemberTheProjectDoesNotInstallIsRefusedNamingTheField`,
  `ProjectDefinitionTest.anImplicitMemberTheProjectDoesNotInstallIsRefusedNamingTheField`,
  `ProjectDefinitionTest.aMemberWithAnUnknownModeOrAnUnsafeModelIsRefused`.*
- **H5. Mast shows one conversation per project.** `GET /v1/projects/{p}` names the project's
  `member` and the reader's `home_room` id, so the id is derived in one place; Mast places that
  room first under the project's name and presets its agent selects to the member. A reader
  with no FDE, a machine token, reads the project with no `home_room`.
  *`SailOperationsTest.projectReturnsContainerAndAgentStatus`,
  `SailOperationsTest.aMachineTokenReadsTheProjectWithoutAHomeRoom`.*
- **H6. A box applied before this release gets its home room on its next apply, and nothing
  else moves.**

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
  `attach` and `run`. The row is the definition's only copy, with no file beside it, so a
  revision that reaches the catalog by sync or by a command is what the next loop event,
  dispatch and notification use, with nothing else run; a watcher already running keeps the
  notifications it started with. An escalation closes any stage still `running`.
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
  `aDefinitionThatCannotBeReadIsNeverTakenForAPipelineThatChanged`,
  `aBuildThatEndsWhileItsProjectsDefinitionCannotBeReadIsHandedToAPersonNotReplayed`,
  `CatalogRevisionBySyncTest.aRevisionAppliedBySyncIsWhatTheNodesNextReadTheLoopAndTheNextNotificationUse`,
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
- **C11. A definition written then read is the definition it was.** `ProjectDefinitionJson`
  is the one codec: the document `write` gives and each section's JSON value read back to the
  same definition, and a section written alone leaves the rest as they were. A review limit
  left at its default is not written, so rewriting a project's `pipeline` never pins it to
  today's defaults, and an empty list of notification events is no list.
  *`ProjectDefinitionJsonTest.aDocumentIsReadWholeAndWrittenBackAsTheSameDefinition`,
  `ProjectDefinitionJsonTest.eachSectionIsTheJsonValueItHoldsAndAnUnsetOneIsNull`,
  `ProjectDefinitionJsonTest.aSectionWriteSetsOrUnsetsItAndChecksTheDefinitionWhole`,
  `ProjectDefinitionTest.toMapRoundTripsCompleteConfig`,
  `ReviewPipelineConfigTest.aLimitTheProjectLeftAtItsDefaultIsNotWrittenIntoItsBlock`,
  `NotificationsTest.anEmptyListOfEventsIsNoListOfEventsSoItIsWrittenBackAsItWasRead`.*
- **P1. Two prompts, sail's, fixed.** The work prompt and the judge prompt are pinned whole
  by golden tests, and every paragraph of the three prompts and three skills of 0.46.5 is in
  one of them or named as reworded or deleted by the coverage test. Nothing in a project's
  definition or files replaces, prepends or appends to either; `FindingParser` and
  `LoopDecision` are untouched.
  *`WorkPromptGoldenTest.theBuildPromptIsThisTextWhole`,
  `JudgePromptGoldenTest.theDefaultJudgePromptIsThisTextWhole`,
  `PromptCoverageTest.theWorkPromptSaysEverythingTheBuildPromptAndItsSkillSaid`,
  `PromptCoverageTest.theJudgePromptSaysEverythingTheReviewPromptAndItsSkillSaid`,
  `PromptCoverageTest.theWorkPromptSaysEverythingTheFixTaskAndItsSkillSaid`,
  `aProjectThatConfiguresNothingRunsTheLoopUnderTheTwoPromptsAndSailsSkill`.*
- **P2. Build and fix are one agent reading one prompt, held to one protocol.** The fix
  run's prompt is the build's with one section added, the findings (rendered with the carried
  ruling's evidence as a reproduction claim, and the dispute lane: a finding is retired by
  argument in the room, never by omission). Both end the same way — commit, push, a pull
  request open, CI green, checked in short polls because the stall guard counts from the
  agent's last tool call — and `SailStopGate` holds a fix run to the pull-request check and
  to the build's reason. The fix lane runs under `agent.guardrails` and the lifetime that
  follows (`ProjectDefinition.guardrailsFor`, `lifetimeFor`: only `REVIEW` reads
  `pipeline.guardrails`); it keeps its own run row and its own stop.
  *`WorkPromptGoldenTest.theFixPromptIsTheBuildPromptWithTheFindingsSection`,
  `SailStopGateTest.aFixRunIsHeldToTheBuildsProtocolAndToldToWatchItsChecks`,
  `SailStopGateTest.aFixRunIsAskedToOpenAPullRequest`,
  `ProjectDefinitionTest.eachLaneReadsItsOwnGuardrailsAndItsOwnDefaults`,
  `ProjectDefinitionTest.aRunsLifetimeIsItsLanesLimitAndABuildNothingBoundsHasNone`,
  `ReviewLanesTest.aFixAgentPastItsTimeLimitIsKilledNothingOfItsLandsAndTheRoomIsToldWhy`,
  `ReviewLanesTest.aReviewersLimitsDefaultTo45MinutesAFixRunTakesTheBuildLanesAndAnEditAppliesToTheNextRun`.*
- **P3. The brief is the only agent-facing text a project writes,** one per judging stage,
  at most `PromptConversation.MAX_CODE_POINTS`, refused on a human stage. It goes in the
  judge prompt where the stage skill's body went; the verdict contract follows it, and a
  brief that asks for prose is still read by the contract (prose is `review_errored`, as
  ever). A stage with no brief judges under `JudgePrompt.DEFAULT_BRIEF`.
  *`JudgePromptGoldenTest.aStagesBriefGoesWhereTheDefaultWentAndNothingElseDiffers`,
  `aStageWithABriefJudgesUnderItWhereTheDefaultWentAndNothingElseDiffers`,
  `aVerdictUnderABriefThatAsksForProseIsStillReadByTheVerdictContract`,
  `ProjectDefinitionTest.aBriefIsTextOnAnAgentStageWithinThePromptBudget`.*
- **P4. The judge reads the spec.** The judge prompt carries the spec's title and body, cut
  at a markdown section boundary to the prompt budget and saying what it left out — the
  sections omitted whole, and the rest of a section it had to cut inside — so a stage can find
  drift from the spec's stated design, bar and non-goals, and never takes a cut body for whole.
  *`JudgePromptGoldenTest.aBodyLongerThanTheBudgetIsCutAtASectionBoundaryAndSaysSo`,
  `JudgePromptGoldenTest.aSpecOfOneSectionOverTheBudgetIsCutInsideItAndSaysSo`,
  `JudgePromptGoldenTest.aFirstSectionAloneOverTheBudgetIsCutInsideItAndEveryOtherSectionIsCounted`,
  `JudgePromptGoldenTest.aSpecInOneRepoWithNoRoomNoBodyAndNothingCarried`.*
- **P5. Skills are installed, never pasted.** Every valid folder under `.sail/skills/` and
  `spec-board` are installed whole into the skills folder of every harness the project
  installs (`agent.install`, else `agent.type`): at provisioning (`SKILLS_INSTALLED`), at
  `project apply` and before every launch, for the launching harness. Each folder is stamped
  with the fingerprint of its files, so an unchanged skill is not pushed again; a changed one
  is built beside the skills directory and put in place whole under `flock`; a stamped folder
  the project no longer holds is removed and an unstamped one is left alone — and never
  replaced: a held skill whose folder is unstamped fails the install naming the folder, so a
  person's own skill under a name the project later takes is not deleted. The one unstamped
  folder sail replaces is a copy of the skill, known by its content: nothing in it but folders
  and regular files, each at a path the skill holds and byte for byte that file, so sharing a
  skill from the agent's folder it was made in, or copying it into another's, loses nothing;
  for `spec-board` the files releases wrote before it stamped count as its own
  (`SpecSkillGenerator.UNSTAMPED_HASHES`). A file of other content, a path the skill does not
  hold, or a link anywhere in it makes it someone's, and it is refused.
  An interactive `agent run` installs the same way before its
  `ssh`; a preview installs nothing. A folder that is
  no skill — no `SKILL.md`, over its bounds, a reserved name (`spec-board`, `sail-*`) — is
  skipped, the valid ones install, and the room is told once which and why; a file whose
  content changed under the launch or is not on this box fails the launch naming it. No
  prompt names a skill.
  *`aProjectSkillIsInstalledWholeForTheLaunchingHarnessAndNeverNamedInThePrompt`,
  `aSkillLaunchedTwiceIsPushedOnceAChangedOneIsReplacedWholeAndARemovedOneIsRemoved`,
  `aFolderThatIsNoSkillIsSkippedTheOthersInstallAndTheRoomIsToldOnce`,
  `aSkillFileWhoseContentIsNotOnThisBoxFailsTheLaunchNamingIt`,
  `theSkillsAreInstalledAfterSailsOwnHelpersAndBeforeTheRunsFilesAreStaged`,
  `anAdhocRunAndARoomTurnInstallSailsSkillAndNoPromptNamesIt`,
  `ProjectSkillInstallerTest.installAllInstallsEverySkillWholeRemovesAStampedFolderNoLongerHeldAndLeavesTheRest`,
  `ProjectSkillInstallerTest.aSkillIsInstalledWholeWithEachFilesModeAndStampedLast`,
  `ProjectSkillInstallerTest.twoLaunchesReplacingTheFolderAtOnceTakeTurnsAndNeitherBuildLandsInTheOthers`,
  `ProjectSkillInstallerTest.anUnstampedFolderUnderAHeldSkillsNameIsNotReplacedAndTheInstallSaysSo`,
  `ProjectSkillInstallerTest.theSpecBoardSailWroteBeforeItStampedIsReplacedWholeAndStamped`,
  `ProjectSkillInstallerTest.aSpecBoardOfSailsFileNamesWithSomeoneElsesContentIsNotReplaced`,
  `ProjectSkillInstallerTest.aFolderUnderOneOfSailsFileNamesIsNotSailsFileAndWhatItHoldsSurvives`,
  `ProjectSkillInstallerTest.aLinkUnderOneOfSailsFileNamesIsNotSailsFileWhateverItPointsAt`,
  `RunCommandTest.anInteractiveSessionInstallsTheProjectsSkillsForItsHarnessBeforeTheSsh`,
  `ProjectSkillsTest.everyFolderIsReadWithItsFilesHashesSizesAndModesInNameOrder`,
  `ProjectSkillsTest.aFolderUnderAReservedNameIsInvalidSayingWhichNamesAreSails`,
  `ProjectApplierTest.applySkillsInstallsTheProjectsSkillsAndWarnsOfAFolderThatIsNoneAndALongBody`,
  `ProjectApplierTest.applySkillsInstallsForEveryHarnessTheProjectInstalls`,
  `ProjectProvisionerTest.provisioningInstallsSailsSkillAndWritesNoContextFile`,
  `ProjectSkillsCommandTest`, `ReviewAgentLoopIT.theReviewLoopReachesAwaitingMergeWithEveryAgentAsItsOwnUnit`.*
- **P6. Sail writes no context file and generates no rule.** `Harness` names no context
  path and no rule channel; provisioning, apply and a launch push nothing named `CLAUDE.md`
  or `AGENTS.md`, in the home or the workspace. `spec-board`'s front matter is pinned whole:
  a description a model acts on, and nothing keeping the harness from invoking it.
  *`ProjectApplierTest.applySkillsInstallsSailsSkillsForTheProjectsHarnessAndWritesNoContextFile`,
  `ProjectProvisionerTest.provisioningInstallsSailsSkillAndWritesNoContextFile`,
  `HarnessContractTest.everyPathAHarnessNamesUnderTheHomeDirectoryIsRelativeToIt`,
  `SpecSkillGeneratorTest.theFrontMatterSaysWhenToUseTheSkillAndLetsTheHarnessInvokeIt`.*
- **P7. Zero configuration is the whole loop.** A project with an `agent.type` and nothing
  else builds under the work prompt, is judged by one stage named `review` under sail's brief
  by the roster's reviewer, gated `no_critical`, fixed under today's limits, and parks in
  `awaiting_merge`. A `pipeline` section with no stages runs the default's stages under
  the default's limits.
  *`aProjectThatConfiguresNothingRunsTheLoopUnderTheTwoPromptsAndSailsSkill`,
  `ReviewWiringTest.aBlockWithNoStagesRunsTheDefaultStagesUnderTheDefaultsLimits`,
  `ReviewWiringTest.aBlockWithStagesIsThePipelineWholeBriefIncluded`.*
- **P8. A key no section has is refused, naming the section's keys.** A definition holds its
  name and its sections (`ProjectDefinition.Section`) and nothing else, and each block reads
  only its own keys (`Fields.only`): `git`, `ssh`, `agent.review_pipeline` or
  `agent.build_skill` fail `ProjectDefinition.fromMap` with "X is not a key of Y. Its keys are:
  …", and a value of the wrong type names its field. Every door reads through it, so an edit is
  refused before it is written and a row that arrives by sync with such a key is
  `ProjectReader.Unreadable` with that sentence.
  *`ProjectDefinitionTest.aDefinitionHoldsItsNameAndItsSectionsAndNothingElse`,
  `ProjectDefinitionTest.aValueOfTheWrongTypeIsRefusedNamingItsField`,
  `ProjectReaderTest.aRowThatSetsAKeyNoDefinitionHasIsUnreadableNamingTheProjectAndTheKey`,
  `ProjectEditCommandTest.anEditThatIsNoDefinitionIsRefusedNamingWhatIsWrong`,
  `ProjectDefinitionOperationsTest.aSectionThereIsNoneOfOrAValueThatIsNoneIsRefused`.*
- **P9. The forge CLI the prompt relies on is authenticated by sail.** Where provisioning
  or `project apply` writes a token into `~/.git-credentials` for a GitHub host
  (`github.com`, or a repo marked `repos[].forge: github`), `ForgeCliLogin` logs `gh` in for
  that host as the dev user with the token the store resolved
  (`GitCredentials.resolveTokenForHost`): the token is pushed to a `0600` file under the dev
  home and consumed on stdin by one command that removes it, success or not, so it is never
  an argument and never in a process list; a failed login fails the step naming the host.
  Other forges get nothing: the work prompt's last sentence tells a run with no authenticated
  forge CLI to say so in the room instead of claiming CI green. The room lane's read-deny
  rules name `~/.config/gh/**`.
  *`ProjectApplierTest.applyReposRefreshesCredentialStoreInsteadOfInjectingToken`,
  `ProjectApplierTest.applyReposLogsGhInForAGitHubEnterpriseHostMarkedAsGitHubAndNotForGitLab`,
  `ProjectApplierTest.applyReposWithOnlyAGitLabTokenNeverTouchesGh`,
  `ProjectApplierTest.aFailedGhLoginFailsTheStepNamingTheHost`,
  `ProjectProvisionerTest.gitTakesTheBoxsIdentityAndTheTokenSetsCredentialsWithNoSshKeyScan`,
  `ProjectProvisionerTest.aFailedGhLoginFailsTheGitStepNamingTheHost`,
  `GitCredentialsTest.githubHostsAreGithubComAndEveryHostMarkedAsGithub`,
  `ProjectDefinitionTest.aRepoMayBeMarkedAsAGitHubHostAndNothingElse`,
  `HarnessHooksTest.claudeCodeDeniesReadsOfTheContainerCredentialsAndKeys`, `HarnessHooksGoldenTest`.*

**The loop machine.** From a build's stop to `awaiting_merge`, one pure function decides every
step of the loop and one class carries each step out. Each part has one job, all in
`ai.singlr.sail.api`:

| Class | Its one job |
|---|---|
| `ReviewPipelineController` | routes: an authoritative stop, or a post in a spec's room, becomes a trigger for one spec's loop |
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
none is read for one. And once the spec's newest loop run, this box's, has ended, `asked` is
the newest question that run asked in the spec's room (`MessageStore.lastQuestion`, under every
identity the run posted as, whatever it said after), with whether someone who steers the room's
agents (`RoomStore.steering`) has answered it since, as the board's "needs reply" reads it, and
whether such an answer is on the run's delivery ledger: whether the run heard it.
`resumableHere()` says the spec is `in_progress` and assigned to this box's FDE, as a dispatch
from here requires. The pipeline is resolved once per event, at the first read of
one of the project's specs, and serves every decision and step of that event.

A trigger is what happened; it carries no decision:

| Trigger | When |
|---|---|
| `BuildEnded(runId, exitCode)` | an authoritative stop of a build or ad-hoc run, or of a run with no known lane; `runId` only when this box holds the run's row |
| `ReviewerEnded(run, failure)` | an authoritative stop of a reviewer; `failure` is the guardrail it was ended for (`killed: time limit (45m)`) or its non-zero exit (`failed: exit 1`), else empty |
| `FixEnded(run, failure)` | the same, for a fix agent |
| `OperatorStopped(run)` | `agent_cancelled`, or a stop of a run an operator's stop claimed |
| `Freed` | a stop freed whatever its run held; raised, after the stop's own loop is driven, for every spec of the project in `review` or `in_progress` |
| `Answered(messageId)` | a post in a conversation (`spec_message_posted`, local or synced); raised for every spec of that conversation in `in_progress` |
| `GoOn` | a gate just failed: the loop goes on from what the rows owe |
| `ReviewRunning` | a step left the review `running` with its spec in `review` |
| `StageJudged(reviewId, stage, outcome)` | a step judged a stage of that review: `Passed`, `GateFailed`, `Errored(why)` |
| `ReviewerNotStarted(reviewId, stage, why)` / `FixNotStarted(reviewId, why)` | a launch for that review (building its prompt or task included) threw and no live run serves it |
| `FixCommitted(run)` / `FixNotCommitted(run, why)` | a step tried to commit and push what fix agent `run` left |

The first four, `Freed` and `Answered` are raised by the router, the rest only by `LoopSteps`. A step is
what to do, and a stage is named by its place in the pipeline, which is its row's place in the
review:

| Step | What `LoopSteps` does | Follow-up trigger |
|---|---|---|
| `Nothing` | nothing | none |
| `SayBuildFailed(exitCode)` | publishes `agent_failed` with `exit <code>` | none |
| `ParkForAPerson` | moves the spec to `review` | none |
| `AwaitAnswer(lane, question)` | says in the room, unless sail said exactly that among its recent messages, that nothing is reviewed until the question is answered, and publishes `agent_awaiting_answer`, which the missed-stop reconciler counts as the stop acted on; the spec stays `in_progress` | none |
| `ResumeBuild(told)` | `ReviewLanes.resumeBuild`: for the roster-trusted box of the spec's assignee, a build run on the spec's branch (a repo still on it keeps the tree its last build left; one another run moved is checked out with `checkout <branch> --` only when clean, else the resume fails), with nothing claimed, snapshotted or superseded and no `spec_restarted`, its prompt a dispatch's, so the room's question and answer are in it; says so in the room. A refused claim defers it, and the stop that frees it retries (W2). A deferral or failure is said in the room and publishes `agent_held_on_question` when `told` (the post is the newest answer, or it is the run's own stop), and only logs on a quiet retry. A failure after the run was written is final (that run is `failed`, which nothing retries), so it is always said | none |
| `CannotResume(lane)` | says in the room that the run ended before it heard the answer and this box no longer holds the spec; publishes `agent_held_on_question` | none |
| `ResumeFix(review)` | `LaunchFix` with the review's open findings; once the run serves, also says so in the room | as `LaunchFix` |
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
| A4b | | the stop names a run this box holds, and that run's newest question is one no one who steers it has answered | `AwaitAnswer(build, question)` |
| A4c | | that question was answered and the run was not told every answer: `ResumeBuild(told)` when the spec is `resumableHere`, else `CannotResume(build)` | as said |
| A5 | | pipeline `Staged` | `StartReview(1)` |
| A6 | | pipeline `None` | `ParkForAPerson` |
| A7 | | pipeline `Unreadable` | `EscalateNew(1, pipelineUnreadable)` |
| W1 | `Freed` | this box did not execute the spec's newest loop run | `Nothing` |
| W2 | | the rows owe anything but a recorded wait | as `Answered`, untold: a deferred resume retried quietly, else `Nothing` |
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
| G9 | | (**P**) the stage the review is in is an agent stage and no reviewer resolves for it | `ErrorReview(stage, "no reviewer agent resolved; set pipeline.stages[].agent or agent.install in the definition")` |
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
| F2b | | the run's newest question is one no one who steers it has answered | `AwaitAnswer(fix, question)` |
| F2c | | that question was answered and the run was not told every answer: `ResumeFix(review)` (**P**) when the spec is `resumableHere`, else `CannotResume(fix)` | as said |
| F3 | | otherwise | `CommitFixLeftovers` |
| F4 | `FixCommitted(run)` | `run` no longer awaited in `failed`, whether or not anything was committed | `Nothing` |
| F5 | | (**P**) otherwise | `StartReview(iteration + 1)` |
| F6 | `FixNotCommitted(run, why)` | `run` no longer awaited in `failed` | `Nothing` |
| F7 | | otherwise | `FailFix("fix agent's work could not be committed: " + why)` |
| Q1 | `Answered` | the spec is not `resumableHere`, or this box did not execute its newest loop run | `Nothing` |
| Q2 | | that run is live, failed, exited non-zero or was stopped by an operator, or its newest question is not answered, or the run was told every answer | `Nothing` |
| Q3 | | that run is a build: the spec has no review | `ResumeBuild(told)`, told when the post is the newest answer |
| Q3 | | that run is a build: the spec has a review | `Nothing` |
| Q4 | | that run is a fix agent awaited in `failed`, its review not failed by error (**P**) | `ResumeFix(review)` |
| Q5 | | otherwise (a reviewer's stage is judged on what it said) | `Nothing` |
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
`sail agent review <project>` plus `sail agent logs <project> --review` (or `--fix`), then
resolves with `sail spec update <id> --status done` (accept the work as-is) or `--status
pending` (send it back to be re-dispatched). Nothing is deleted along the way.

**Measures of a change.** What a spec's change took is computed on read, by one pure function
(`SpecMeasures.of` over the spec's `MeasureRows`), from rows a box already holds and nothing else:
the spec's own record (`SpecStore.recorded`: its times and the sums this box kept of its finished
runs), its reviews and their findings, its runs as `GET /v1/runs?spec=` lists them, the minutes
this box saw people give its conversation, and its project's `agent.budget`. Each kind is one
read for every spec measured, so a listing costs the same statements for one spec as for a
hundred.

- **What a run cost.** Each harness answers `Harness.usage(agentLog, transcript)` from its own
  report: Claude Code from the last `result` line of the run's stream-json log that reports
  anything (tokens summed over `modelUsage`, so subagents count; dollars from `total_cost_usd`;
  the model that cost most), Codex from its rollout transcript's last `token_count` and
  `turn_context` model (no dollars; its cached input taken out of its input). `RunUsageReader`
  reads the last 4 MiB of the one file the harness reports in, in the run's container, waiting at
  most 10 s, and the finish writes the usage, the reported model and the effort passed in the
  update that makes the row terminal: the tracker, the pipeline, the foreground launcher and the
  reconciler finish that way. An operator's stop never waits on a container: it leaves the
  report unread. A harness session's report is its running total, so a run records its share:
  the report less what the session's other runs were given, kept per session in the box-local
  `session_usage` row (the highest total the session reported and what its runs were given),
  written in the finish's transaction, never touched by retention and erased with the spec or
  room it is of. A resumed run's session is stamped at launch from the session it resumes. A
  report below the session's highest in any figure (an earlier run finishing late, a harness
  that started counting over) tells no share: the run is `usage_source = unattributable`, adds
  nothing, and its spec's tokens and dollars are a lower bound. A live member sets each turn's
  share on its run, and its finish keeps the last in the one write that ends it. A report that
  says nothing, or a report file that is not there, is `usage_source = none` with every count
  null. A report that could not be read (the container stopped, missing or silent; a last report
  line that does not parse; a file longer than the window whose window holds none) leaves the
  usage unrecorded: retention keeps that run's directory, and each reconciler sweep of the box
  that ran it reads it again until it is answered. Usage syncs with the run, its executing box's
  fact.
- **Times.** Ready is the spec's first move into `pending`, written once; awaiting merge its
  latest move into `awaiting_merge`, kept as it moves on to `done` and `archived`; done its
  latest move into `done`, kept as it is archived and cleared on any other move. The status
  triggers stamp each from the moving revision's `updated_at`; the snapshot carries them
  (`_ready_at`, `_awaiting_merge_at`, `_done_at`, metadata outside the comparison, tied to
  `status` like `_status_since`), and every box that applies the revision writes them verbatim,
  so every box holds the author's times. Verified is when the current attempt's latest review
  completed, if it passed.
- **Counts and cost.** Rework is reviews that reached a verdict, reviews that errored (served
  apart as `review_errors`), build runs (a resumed build is one more), fix runs and findings
  carried forward. Runs, their usage and their dollars are kept on the spec: box-local columns of
  `specs` that SQLite triggers on `runs` fold as a run's row becomes finished on this box, and
  take back as it stops being finished; erasing a row folds nothing out. A spec that arrives
  after its runs starts from the finished ones held. Builds and fixes still running are added as
  rework under way. Every box in scope holds the same run rows, so every box keeps the same sums,
  with nothing to merge. A box that may lack a run cannot vouch it counted every run, and there
  the run figures are a lower bound (`at_least`): one that first held the spec after main erased
  a run of its project made after the spec was, and one that adopted main's erasure of a run it
  never saw finish (`uncounted_runs`: that run's spec when the box held its row, every spec of
  its project made before it when not). Tokens and dollars are exact only when every run counted
  reported them: a lower bound when some did not or a run was unattributable, and `unknown`
  (`runs_not_reported`) when none did. A review or run the reader may not see leaves every
  number of its kind `hidden_rows`. Over budget is true once what was reported passes a limit the project sets,
  false only when every run reported the figure that limit is of; display only.
- **Human minutes.** `attention(person, conversation, minute, channel)` is box-local and never
  synced: a person's accepted input in a room-bound terminal (`terminal_input`, told by the pty
  host once a minute per connection), a post (`post`), a review decision or a status move
  (`review_act`), and a client's focus heartbeat (`focus`, `POST /v1/attention`). Only a person on
  a person's own lane is counted (`Access.attendee`: the host CLI or an HTTP credential), never
  the credential in a container or a run's principal. Human minutes are the distinct (person,
  minute) pairs in the spec's own conversation (its id: activity in a room the spec was born in
  that other specs share is not its), by channel and in all, with the count of people and the
  box named; always a lower bound, since reading leaves no row and another box's minutes are not
  here. "No recorded human code act" is a done spec no person decided a human stage of, in any
  attempt, and no person typed in a terminal bound to its own conversation: an upper bound.
- **Where it is read.** `GET /v1/specs/{spec}/measures` serves the record. `GET
  /v1/specs/{spec}?measures=true` carries it as `measures`, with no ETag; `sail spec show` and its
  `--json` ask for it, and no other read of a spec pays for it. Reading measures is the permission
  table's Read measures: a person's as reading the spec; an agent's for its own work alone, in its
  work's project; and none for the credential in a container, which is answered the detail as
  without asking. `GET /v1/specs:measures`, behind `sail spec list --status done --measures
  [--done-since] [--done-until]`, lists it per spec with the nearest-rank median and p90 of every
  number over the values known exactly, counting what each left out as unknown, as a lower bound
  or as an error. Human minutes are summarized as this box counted them: one spec's are served
  as a lower bound, since another box may hold minutes this one never saw, and the listing's
  median is over this box's counts, with the box named.

| | Invariant | Proven by |
|---|---|---|
| M1 | A finished run records its usage once, from its harness's own report, in the update that finishes it or, when it could not be read then, in the sweep that reads it; a harness that reported nothing records `none` with null counts, never zeros; a report that could not be read is never `none` nor an earlier running total. | `HarnessUsageTest` (a recorded stream-json log and a Codex rollout, golden; a last line that does not parse), `RunUsageReaderTest` (a container stopped, missing or silent; a missing file; a full window), `HarnessContractTest`, `RunUsageStoreTest`, `ReviewLanesTest` (Claude Code, Codex and an empty log through the production finish), `LiveMemberTest` (three turns), `MissedStopReconcilerTest` (the sweep's read), `StopOperationsTest` (a stop reads nothing) |
| M2 | A run's usage is its executing box's fact: it syncs with the run and no other box writes it. | `SpecMeasuresSyncTest` (main holds the node's usage after one round), `BoxRunsSyncTest`, `JournalDecidesEveryWriteTest` |
| M3 | Retention never removes the directory of a finished run whose usage was not read, and the read is tried again until it is answered. | `ReviewLanesTest` (past the newest twenty), `MissedStopReconcilerTest` |
| M4 | Ready, awaiting merge and done are the moves' own times, the same on every box. | `SchemaManagerTest` (the triggers), `SpecMeasuresSyncTest` (made ready, restarted, reopened, done on a node, read on both boxes) |
| M5 | An attention minute comes only from a fact the server observed, for a person on a person's own lane, once per tuple. | `AttentionStoreTest`, `AttentionTest`, `PtySessionHostTest` (once a minute, room-bound only) |
| M6 | A heartbeat speaks only for its caller, in the server's minute, about a conversation it may read; one it may not read answers as a missing one. | `AttentionTest`, `ScopedReadsTest`, `RouteGateSweepTest`, `AccessDoorsBaselineTest` (`infra-doors`) |
| M7 | Nothing about a person is stored beyond (person, conversation, minute, channel). | `AttentionStoreTest` (the table's columns), `PtySessionHostTest` (the host tells a name and a session, nothing typed) |
| M8 | A measure is computed only from recorded rows: a number missing an input is `unknown`, with its reason; nothing is estimated or zero-filled; a row the reader may not see is never counted as none; a figure a box cannot vouch for (runs it may lack, runs that did not report it, a run whose share could not be told, human minutes) is a lower bound, and no two boxes serve two different exact numbers. | `SpecMeasuresTest` (a figure some runs or none reported, in the detail and the listing), `SpecMeasuresSyncTest` (a node that joins after an erasure; a run made and erased between a node's rounds, held or not), `SpecMeasuresRouteTest` |
| M9 | No fact is counted twice: a run is folded once as it finishes; a run records its share of its harness session's running total, the report less what the session's other runs were given, kept where retention never reaches, whatever order its runs finish in; a report whose share cannot be told adds nothing; a minute seen two ways once. | `SchemaManagerTest`, `RunUsageStoreTest` (an erased earlier run, a later run finishing first, a resumed row whose hook never named its session, a harness restarting at zero), `HarnessUsageTest`, `SpecMeasuresTest` |
| M10 | One computation: the route, the spec's detail and both CLI verbs serve the one record. | `SpecMeasuresRouteTest`, `SpecMeasuresCommandTest` (the section and the listing pinned whole; each `--json` is the record) |
| M11 | A negative interval is an error naming both instants, never clamped; a listing leaves it out of the median and counts it. | `SpecMeasuresTest` |
| M12 | Served measures name no individual. | `SpecMeasuresTest` |
| M13 | Reading is reading: a measure read writes no row but the door's stamp of the credential's last use, and a spec the caller may not read is one that is not there; reading measures is the table's Read measures, an agent's own work alone. | `SpecMeasuresRouteTest`, `ScopedReadsTest`, `RouteGateSweepTest`, `AccessDoorsBaselineTest` (`infra-doors`), `OwnWorkSocketTest`, `AccessTest`, `PolicyTableTest`, `DoorParityTest` |
| M14 | Erasing a run by retention changes no measure of its spec. | `SchemaManagerTest`, `SpecMeasuresRouteTest`, `SpecMeasuresSyncTest` (three of five runs erased on main, read on both boxes) |
| M15 | An asked effort is never silently dropped: a harness takes it (`--effort` for Claude Code, `model_reasoning_effort` for Codex) or it is refused naming the harness, as `invalid_request`, before any run is reserved: at the spec door when the agent or the effort changes, at the dispatch door, at a room wake and at the launch. | `HarnessUsageTest`, `HarnessGoldenTest`, `AgentSessionTest`, `GlobalSpecOperationsTest` (a stored refused effort still moves and is retitled), `BuildDispatchCoverageTest`, `RoomWakeLaunchTest` |

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
the spec. Each listed type names the one action its sender must be allowed on what it is about
(`EventAuthority.publishable`, decided by `Access`; the Access section above), and the rule keeps
whether its sender speaks for this box, whether it retells, and whether this box executed the run:

| Action | Types | Published by |
|---|---|---|
| Operate a run | `agent_session_stopped`, `agent_cancelled`, `agent_failed` | the watcher (`agent_session_stopped`); the host CLI's stop (`agent_cancelled`); main's sync bridge, retelling what a node's synced transition tells (`SyncTransitionEvents`) |
| Dispatch | `spec_dispatched`, `spec_restarted` | the host CLI's dispatch; main's sync bridge |
| Approve | `review_iteration_started`, `review_stage_started`, `review_stage_passed`, `review_stage_failed`, `review_completed`, `review_errored`, `review_escalated` | main's sync bridge |
| Narrate | `agent_session_started`, `agent_stop_nudged`, `agent_tool_started`, `agent_tool_finished` | the in-container hooks over the socket; the host CLI's dispatch and `sail run` (`agent_session_started`) |
| Post | `spec_message_posted` | a node's sync, for each message it pulled |
| Operate the box | `snapshot_created`, `board_updated` | the host CLI's dispatch (`snapshot_created`); a node's sync, for a round that changed the board |

Those the machinery acts on are taken first-hand only from whoever speaks for this box (its FDE,
or an admin), and retold only by one who may take the action on the work, about a run this box
did not execute. A run's narration is a person's only on the box that executed it; a run's own
principal narrates its own run alone, and publishes nothing else that is taken at its word. An
announcement is retold by any voice that acts for an FDE, about a conversation.

The host token acts as the box's FDE: an admin on main or a standalone box, the synced
roster role on a node. The socket's event route takes a run credential only; the box
credential has no run to speak for. A member's own session on main's gateway is not the
box's FDE, so lifecycle events from a `sail` command run through it are refused and the
missed-stop reconciler finishes its run.

Holding a relayed run to the owner of the spec it works, never to the FDE who pushed it,
has one cost: a spec an admin reassigns while another FDE's box is still running it loses
that run's relayed stop on main. The run finishes on its own box and main takes its stop as
that box's; nothing of it is announced there, and since that box's move of the spec to
review is denied on the sync lane, the spec stays `in_progress` on both boxes until an admin
moves it. A run itself proves at its birth that its box's FDE owned the spec it names (the
run rule, below), so no run main holds was ever started against a spec its pusher did not
own, and the next run that box starts on the reassigned spec is denied on its push, where
the node sees it.

What each type drives, which is why its sender is checked: `SpecLifecycleReactor` moves a spec
to `review` on `agent_session_stopped`; `ReviewPipelineController` starts a review on one this
box observed; `RunTracker` finishes the run it names, revoking its credential and releasing
its repos, on `agent_session_stopped` and `agent_session_completed`; `RoomWakeReactor`
prompts a room's live member or launches a wake on `spec_message_posted`, ends the live member
of a room whose seat changed on `spec_engaged` and `spec_disengaged`, and runs the room commit
guard on a room run's stop;
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

### The change feed

A client keeps a copy of the rows it shows and stays current without reading them all again:
it reads a list, which answers a `Seq` header, then asks `GET /v1/changes?since=<position>` (or
holds `GET /v1/events/stream` open with `Last-Event-ID: <position>`) for every row that changed
after. A position is one opaque string, `<catalog>.<seq>.<fingerprint>` (`FeedPositions`): the
database's catalog id, a `change_log.seq`, and the first 6 bytes of a SHA-256 of the type, id
and rev of the entry at that seq, as the log holds it or compaction kept it (`0` where none ever
stood). The `Seq` header, `since`, `next`, the SSE `id:`, `Last-Event-ID` and `after` all carry
one; a change's own `seq` in its envelope stays an integer. A revision's rev is
`<counter>-<content hash>`: a reader who held a row's content can test a guess at a later
revision of it, never enumerate one.

One seam, `ChangeFeed`, serves both. A page reads the heads after the position from
`ChangeLog.changesAfter`, each type's range (and, for a scoped reader, each of its projects'
ranges of it) in seq order through an index and cut at the limit, then each fed type's rows in
one read, in one read snapshot. It serves a reader a row it may read now, or `gone` or `erased`,
without content, for a head filed where it may read now; nothing else, and a change to one row
never re-sends another. A fed row is shaped by its type's list record less the fields that list
derives from other rows (`RoomsListResponse.DERIVED`, `GlobalSpecsListResponse.DERIVED`), which
a client derives from the rows it holds (SailKit's `HeldRows`):

| Field | Derived from |
|---|---|
| room `spec_ids` | the spec rows whose `room_id` is the room |
| room `last_activity_at` | the newest message held of the room |
| room `needs_reply`, `question_message_id` | the room's latest question an agent asked, of the messages held, that no person spoke after |
| spec `wake`, `engagement` | its room row's `wake` and first member |
| spec `needs_reply`, `question_message_id` | its room's open question |
| spec `last_activity_at` | the later of its `updated_at` and the newest message held of the conversation named as the spec is |

A spec whose room its reader may not read is listed with none of the room's fields, as the client,
holding no such room, derives none. Deriving without the box's roster has known limits, where a
client's copy disagrees with the list: the server counts a question answered only by a voice that
steers its room, and a client any person's message, so a reply from someone who does not steer
the room clears the client's `needs_reply` while the list shows it; a client that rebuilt from the
lists holds only the newest page of a room's messages, so a question older than that page, with no
person's message since, is open on the list and not on the client; and a reader that hears only a
room's steering voices is listed with the time of the room's newest message of any voice, which it
is never sent.

A client follows the feed only from the position of the reads it holds, and applies changes in
the order told. A stream reads one page per turn of its loop, as the caller is now: it asks the
door that opened it before a page is read and again before it is written, so a caller refused
since is ended with none of it. It reads on from its own cursor without checking that cursor's
entry again. It reads when this process commits an append to the change log (`ChangeLog.watch`)
or when another process announces one through `POST /v1/changes:wake`, a burst of either waking
it once; never on a timer and on no other event. The wake carries nothing, is delivered to no
client, and any caller who authenticates may send it (gate `WAKES`). A command other than the
server binds a `CommitAnnouncer` to every database it opens (`Sqlite.ANNOUNCING`, bound by
`Main`; the server binds none), which sends the wake after each commit that appended. Bus events
carry no `id:`, so they never move a cursor.

A change of what a reader may read raises a **floor** (`FeedFloors`): an entry of the change
log's own type `feed`, filed under no project, that no sync pages and every other reader of the
log passes over by naming its types (`FeedFloorTest` sweeps the sources for it). Raising one
moves it to a new entry with a fresh random rev, so a position standing on it says nothing of
whose floor rose, and keeps the entry it replaces as a compacted one, so a position standing on
that still checks. The box's floor rises when history is rewritten with no newer entry
(`ChangeLog.purge`, `forget`, on a node); an FDE's when what it reads changes
(`FdeStore.restrict`, `unrestrict`, `grant`, `revoke`, its removal, an erased project's grants
taken back); a project's when a spec, room or run moves into or out of it (both projects), when
main refiles a head on a node (`fileUnder`), and when a message filed apart from its
conversation is deleted or erased: every message said in a room before the room moved stays filed
under the project it left, as does a later reply in one of its threads, so retention or a prune of
a moved room's old history resyncs every scoped reader of the project it left, though none of
them can read any of it, rather than tell them the end of a message that was never theirs. A reader of every project answers to no
project floor. A position that does not parse, names another database, lies past the newest
seq, stands where no entry now stands as it was handed out (a copy restored from an older backup
and grown again; an entry erased since), or below its reader's floor is `409 resync_required` on
the feed and one `event: resync` with an `id:` on the stream, each naming the newest position;
a stream told so after it started is then ended. An older binary run against the database raises
no floor while it runs.

| | Invariant | Proven by |
|---|---|---|
| F1 | A position is opaque and names the database, the `change_log.seq` and the entry standing there: durable across restarts, strictly increasing, assigned where the revision is written, with no second counter. One from another database, from a copy restored from an older backup, standing on an entry no longer there, or a bare number is `resync`, never served. | `ChangeFeedTest` (a restart; a reinstall; a restore, before and after the entry at the seq is erased; the same row at the same seq with another revision; an old client's bare id), `FeedFloorTest` |
| F2 | For any reader and position, replay then live delivery is every readable change after it, each once a page, in order, including a change committed during the replay; a page's `next` passes what it read and told nothing of; a stream's cursor survives the erasure of its entry. | `ChangeFeedTest` (a change committed between two replay pages; a row changed three times; 5,000 changes paged; a skipped tail; an erased cursor) |
| F3 | A change is told only to a reader of its row, by its own read route's rule; a scoped reader's feed and stream are the same changes with and without another project's activity, positions aside; no position names a floor that rose. One erasure is seen by readers who could read nothing of it: the deletion or erasure of a message filed apart from its conversation, which is every pre-move message of a moved room, resyncs the scoped readers of the project it is filed under. | `ChangeFeedTest` (a scoped viewer; a reply in a moved room's old thread; a run's credential; the floor fingerprints), `ScopedReadsTest`, `OneDecisionSweepTest` |
| F4 | A change of what a reader may read raises a floor for the readers it can affect, who are answered `resync` and whose open streams end; a caller refused before a page is written is sent none of it. | `ChangeFeedTest` (a revoked member; a disabled one; a disable, a deleted session and a revoke landing between the door and the page), `FeedFloorTest` |
| F5 | An erased row is told as `erased` with its id and type, never its content, to a reader of where it is filed; a reader that hears only a room's steering voices is never told the erasure of a message, so it keeps one it was told. | `ChangeFeedTest` (a pruned spec; erased messages and a steering-only reader), `FeedFloorTest` |
| F6 | A page holds at most 500 heads (100 by default), reads each range through an index in seq order, and costs the same statements however many it holds; each page is its own read, so a replay never holds a write, and a stream reads one page a turn. | `ChangeFeedTest` (statements; twenty streams over 60,000 hidden messages; a 200,000-change replay), `FeedFloorTest` (query plans at 200,000 heads) |
| F7 | `/v1/changes` and the stream's row events are one read through `ChangeFeed`; a fed row carries what its own journal entry holds, shaped by its list record less the fields derived from other rows, and a change to one row never re-sends another; a client deriving those fields from what it holds agrees with the lists. | `ChangeFeedTest` (scenario 7 on the derived view; a room's wake change), `ClientGoldensTest` (`changes.json`, `board-*.json`), SailKit `ChangeFeedTests` |
| F8 | A client that names no position is served as before, but for the `Seq` header and the ids row changes carry; bus events, turn deltas, pty facts and a room's turn state carry no id, and no client is sent a wake. An idle stream reads nothing, and only commits and wakes make a stream read. | `ChangeFeedTest` (no position; deltas on a resumed stream; an idle stream; a member's wake; wakes in a burst and door events), `SseHandlerTest`, `ClientGoldensTest` (`stream.sse`) |

## Access

One rule, `Access.decide(actor, action, resource)` (`ai.singlr.sail.access`), answers whether a
principal may take an action on a resource, and words every refusal it gives. Its answer is
allowed, not found (the resource is outside the principal's scope, so the door answers as for an
id it does not hold and a list leaves the row out), or refused with a kind, a message and a fix.
It reads nothing but its three arguments and the permission table, so a list decides each row from
the row in hand. It is reached in this order, so one principal hears the same reason at every door:

1. Sail itself, main's decisions as a node adopts them and this box's machinery, is allowed.
2. Audience: a resource filed under a project outside the principal's scope is not found. A box and
   the org's people are filed under none.
3. Role: the role's cell says no: refused, read-only or admin-only; unless the agent's lane grants
   the action as its own, which decides it alone whatever the role, so a room agent, read-only by
   construction, posts in its room and narrates its run.
4. Lane: the lane of an agent or of the in-container credential says no: refused, naming the
   lane; or an agent's lane narrows the action to its own work, and the resource is not: refused,
   naming the work.
5. Ownership: the role's cell says owner and the principal is neither an admin nor acting for an
   owner, or it starts work in the room a spec was born in (creates or claims the spec there)
   without acting for that room's owners: refused, naming the owners.
6. Otherwise allowed.

A write is answered in one order (`WriteAuthority`): what lies outside the actor's audience is
not found, which the scope rule says before any type rule is asked, from the project the journal
files the row under, the one audience a write has; what this box does not hold, a project or a
conversation, is not there; then `Access` decides the actions the revision takes, every step
after the audience (`Access.withinAudience`, which only `WriteAuthority.refused` asks); last come
the rule's own facts. A deletion of an id this box holds no head of is decided as the rule decides
a birth, as on main. A door's placement comes after `Access` too. The table is
`Policy`, and this copy of it is generated (`PolicyTableTest`):

| Action | Covers | Viewer | Member | Admin | Work agent | Read-only room agent | In-container credential |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Read | specs, rooms, messages, runs, files, projects | yes | yes | yes | yes | yes | yes |
| Read a definition | a project's definition | no | yes | yes | own work | no | yes |
| Read measures | a spec's times, rework and cost | yes | yes | yes | own work | its own | no |
| Post | a message in a conversation | no | yes | yes | own work | its own | yes |
| Create | a spec, a room, a project | no | yes | yes | own work | no | yes |
| Change | edit, delete or restore a spec or room; settle a parked conflict | no | owner | yes | own work | no | yes |
| Claim | take an unassigned spec; one born in a room also needs that room's owners | no | yes | yes | no | no | yes |
| Assign | give or move a spec to someone | no | no | yes | no | no | no |
| Approve | move a spec's status (promote, done, archive, cancel) or create it ready; approve or dismiss findings | no | owner | yes | no | no | no |
| Dispatch | dispatch or resume a spec; seat or remove a room agent | no | owner | yes | no | no | no |
| Operate a run | stop it; attach to it; read its log | no | owner | yes | no | no | no |
| Narrate | a run's reports about itself: events, session, inbox acks | no | owner | yes | its own | its own | no |
| Share | add, change or remove project files and skills | no | yes | yes | no | no | no |
| Serve | start and stop the project's processes | no | yes | yes | own work | no | yes |
| Restore | restore or delete a snapshot | no | yes | yes | no | no | no |
| Configure | change a project's definition | no | yes | yes | no | no | no |
| Prune | erase a spec everywhere, once archived, cancelled or deleted | no | owner | yes | no | no | no |
| Purge | erase a project everywhere; a retention sweep | no | no | yes | no | no | no |
| Manage people | add, change role, grant, remove, pair, mint tokens | no | no | yes | no | no | no |
| Operate the box | sync now; read sync health and bus stats; point the box at another main | owner | owner | yes | no | no | no |

A person reads the column of their role on every lane they come in by, a push over sync included.
An agent reads its role's column too (its person's, capped by the lane), and its lane's column
narrows it: a lane cell is yes (it narrows nothing), no, own work (the agent's own work alone, as
its role allows, asked after the role and before the owner), or its own (the lane's own grant on
its own work whatever the role: a run narrating itself, a room agent posting in its room); whose
the work is, is the role column's to say. An agent's work (`Actor.Work`, which only `RoleRule`
builds from the run) is its spec, in that spec's room, for a build, review, fix or a room member
of a spec's room; its room for a spec-less room member; its whole project for an ad-hoc run; and
every spec it made, read from the journal's first revision of the spec (`ChangeLog.bornBy`) with
that spec's own room, all within its work's project. It files a spec only under its project,
born in no room or its own, moves none out of it (a revision that moves a spec's or a room's
project is a Change where it lands as well as where it was), posts in the room of none a person
moved out of it (the room stays filed where it was, so the post is decided where the spec is as
well), and creates no room but a spec's own. A read-only room agent reads its room's project
alone, which `RoleRule` narrows its scope to, so the table's Read stays yes. A run credential readable by
another run of the same person gains only that run's agent column, which is accepted until runs
are isolated. A cell holds today's answer where the
agreed design changes it, and `Policy` names the spec that does. The box credential presented in
a project container is its person, narrowed by its own column (`Actor.Lane.CONTAINER`, the
`In-container credential` column): whatever runs in the container can read it, so it carries no
human gate (assigning, approving, sharing skills, operating the box), whoever presents it, and the
role column still says whose the work is; its refusals name the door outside the container that
takes the action (the host CLI, or Mast). A question asked before there is one resource, a prune by policy or of a whole
project, a conflict's resolution, or again once an erased spec has nothing left to own, is the
role and lane alone (`Access.atAll`), answered as a refusal or none, never not found; a lane's
grant on its own work alone is refused there, since no work is in hand.

**Outside the table**, because they are not permissions:

- A spec runs only on its assignee's box, and a room's agents only on its owner's box: placement
  (`DispatchPolicy`, `LaunchAdmission`), asked after `Access`.
- Opening a terminal needs the project on your own box: its person operates this box (Operate the
  box on it, the question attaching asks of a session's opener's box: the box's own person, or an
  admin) and holds the project, whatever their role (`HostLanes.Pty.admitBox`, then
  `admitProject`, through `ProjectGate.readable`), asked in one place (`PtySessionHost.openRefusal`)
  at the unix socket and for a ticket alike. A viewer on their own box opens one too.
- Admins are never scoped.

**Whose a resource is.** `Resources` builds each resource from the row in hand, with the project
it is filed under and its owners, each owner rule once:

- A spec is its assignee's, or its creator's while it is unassigned (`Ownership.ownerOf`). A spec
  born in another room also names that room: its conversation's owners for a create or a claim,
  which give a voice there. Seating an agent for it writes that room's roster, which the journal
  decides as a change of the room (`MembershipService` asks it before anything starts).
- A room's settings (roster, wake, title, assignee) are its owner's (`RoomStore.ownerOf`): a
  spec's identity room its spec's owner's, as this box last knew the spec, live or deleted; any
  other room its own row's. Its conversation is every owner of the work in it (`RoomStore.owners`):
  the room's owner and the owner of every spec born in it.
- A message is decided as a post in its conversation; a review is filed under its own first
  write's project and owned by its spec's owner.
- A run is the FDE it acts for and its spec's owner (as the reader sees that spec at the doors,
  and unfiltered on the journal and in the event rule), or for a
  spec-less run the box that ran it (`Resources.run`), so a run's own FDE keeps it after its spec
  is reassigned. A run being born answers to whoever's box runs the work it names: its spec's
  assignee, a spec-less room's owner, or else the FDE it acts for (`Resources.launched`).
- A project is its creator's (`projects.created_by`); a box is its FDE's; the org's people are
  no one's, so an admin's alone.

**Type rules name actions.** Each synced type's `WriteAuthority`, declared by its store
(`SyncedStore.authority`), names the actions a revision takes, every one of which `Access` must
allow (`WriteAuthority.refused`, the one seam from a decision to a write's refusal). What this box
does not hold is answered before that, and the rule's own facts after it:

| Rule | Actions | Its own facts |
| --- | --- | --- |
| `SpecAuthority` | a birth is Create, plus Claim or Assign for one born in another room naming an assignee; an edit, tombstone or restore is Change; moving a live spec's assignee is Claim (an unassigned spec, for the FDE the actor acts as) or Assign; a move of its status, and a birth in any status but draft, then Approve, of the role and lane alone (`Access.atAll`), since whose the spec is the other actions asked | not held: a project this box holds, a conversation it holds to be born in (what no one here can own is an admin's, `Access.unowned`); taking a room's reserved id from its owner is Assign of the room; facts: a push names only its pusher; `room_id` never changes; a spec's id is reserved for its own room |
| `RoomAuthority` | a birth is Create; every later revision, tombstone included, is Change of its settings | not held: a project this box holds; facts: attribution |
| `MessageAuthority` | Post in its conversation | not held: a conversation this box holds; facts: a credential acting for no FDE posts nothing; both are of what no one here can own, an admin's (`Access.unowned`); as whom a pusher posts |
| `RunAuthority` | a run's own session report by its own principal is Narrate; every other revision is Change | its principals are its own; attribution; spec and room never change; a run names a spec or a room, never both; a pushed run is its executing box's |
| `ReviewAuthority` | Approve | attribution; a review never moves to another spec, a stage never to another review |
| files, projects (`WriteAuthority.table`) | a file is Share; a project's definition is Configure, and a rename Configure plus Purge of the old name | attribution |
| `EraseAuthority` | a spec named by id is Prune (one this box erased already, Prune at all); a whole project or a policy's sweep is Purge | a prunable status; nothing of the spec's outside the actor's audience; no unfinished run, bar a project purged whole |

Ahead of every rule, `ScopeRule` answers a write that reaches outside the actor's audience in the
words a write has always heard ("already exists" for an id taken outside, "was not found" for a
project it names, "is in no project this FDE is granted" on a push), asking `Access` whether the
actor reads each project. One translator (`Refusals`) turns a refusal into the error clients get,
a kind one code at every door: `READ_ONLY` → `read_only_credential`, `NOT_OWNER` →
`forbidden_not_assignee`, `NO_CONVERSATION` → `room_not_found`, `ADMIN_ONLY` →
`forbidden_admin_only`, `LANE_FORBIDDEN` → `agent_lane_forbidden`, `NOT_AUTHOR` →
`forbidden_not_author`, `FIXED` → `invalid_request`, `NOT_PRUNABLE` → `spec_not_prunable`,
`NOT_PUBLISHABLE` → `forbidden`, `OUT_OF_SCOPE` and `UNHELD_PROJECT` → `not_found`. A not-found
carries no text: the door that asked supplies its own answer for an id it does not hold.

**Events.** Each type a client may publish names one action (`EventAuthority.publishable`): a
run's reports of itself are Narrate, a run's stop or failure Operate a run, a dispatch or restart
Dispatch, the review types Approve, a message's announcement Post, and an event about the box
Operate the box. The rule keeps three facts: whether its sender speaks for this box (Operate the
box) or only retells what another box did, whether this box executed the run (a person's
narration, an admin's included, is believed only of a run this box executed, and a run's own
principal of its own run, whose credential only the executing box holds), and that an agent's
announcement is never taken at its word: it retells nothing, since the socket strips `source`. An
agent is decided like anyone, the run it names as Narrate's resource, and hears its own words: an
agent narrates only its own run, and every other type but an announcement is not for agents.
Work outside the sender's scope is not found.

**Reads and sync.** Read is yes in every column, so a read is the audience alone. One read filter
(`ReadFilter`) decides each row by the project it is filed under and asks that project only of a
reader whose audience is restricted, so a list an unscoped reader reads adds no query per row; a
filter inside one query takes the same scope (`Access.audience`). A node receives a row only when
its person may read it (`SyncRpcServer`'s pages, current rows and handed-back versions), and main
accepts a push only when the pusher may write it, by the type's rule at main's commit. An agent's column hears a conversation only
through the voices that steer it (`Policy.hearsOnlySteering`; the filter is `RoomStore.steering`'s
query): its owners, an active admin of main's roster, sail and the runs working for them.

**Doors** ask `Access` for the side effects no row's rule decides: dispatch and seating
(`LaunchAdmission`: Dispatch on the spec, its assignee's, then placement; an unassigned spec is
asked only of the role and lane, and placement says it runs on no box until it is claimed;
seating an agent for a spec then asks the journal whether it will take the spec room's roster,
while a spec-less room's seat is Dispatch on the room, which its owner alone and an admin take, so
it implies the room rule's Change), stop (Operate a run), posting into a room without a message
(the terminal's room pin and a room session's report ask the message rule's own post,
`MessageAuthority.post`, the one decision of a rule a door asks, since a post with no message to
journal still answers to the rule's fact that a post is someone's), serving a stack, restoring a
snapshot, reading a definition, and sharing or removing a skill before its folder is read
(`ProjectGate`, the one project gate), pruning, resolving a
conflict, reading a run's log and attaching to it (Operate a run, then placement, as a stop), sync
now, sync health,
bus stats and pointing the box at another main (Operate the box), managing people (the passkey
ticket and registration without a ticket, `fde` at the host CLI and at the SSH gateway, asked
before whether the roster lives on this box). Every door reads the row it acts on in its caller's
scope first, the host CLI's operator included, who sees the projects main's roster gives the box's
FDE. The terminal opens a session only on a box its person operates, in a project they hold on it,
whatever their role, as the catalog holds it and their scope admits it.
A terminal session is its shell: the session shim (`_pty-child`) leads it, and when the shell
exits the session ends once what is already in the terminal has been read (`Pty.DRAIN_GRACE_MILLIS`
of quiet); a job the shell left running keeps nothing it says afterwards
(`PtySessionTest.aJobTheShellLeftKeepsWhatItSaidBeforeTheExitAndNothingAfter`). Until 0.67.0 a
Linux session lived until every holder of its terminal let go. Ending a session (a kill, a stop
for good, a dispatch displacing it, the sweep reaping one never attached) hangs it up first, as a
closing terminal does: its shim is sent `SIGHUP`, which it
passes to the command, so an interactive shell ends at once with its history written; one still
running after `PtySession.HANGUP_GRACE` (1 s) is sent `SIGTERM`, and one still running 5 s later
is killed with everything under it. The terminal is read throughout, so what the command says on
its way out is kept, and the jobs an interactive shell started end with it, as it passes the hangup
on (`PtySessionTest`, `PtySessionHostHandoffTest` for a session adopted across a restart).

**One way in.** Six doors reach Sail: the web API, the container socket, the host CLI, the SSH
gateway, the terminal host (its unix socket, and TCP by ticket) and sync. Each door's credential code finds whom its credential names
and the lane it came by, and asks `RoleRule.principal`, which answers the principal (its handle,
role, lane and scope) or refuses it; no door names a role or resolves a scope. A token bound to an
FDE, a passkey session and the gateway's session are that FDE. A credential that names no one is
the box's own person, its role capped by what the credential was minted with: the host CLI's
operator, the host token, a terminal's blank token, a run acting for no FDE, and the box
credential, while the box is still the FDE it was minted for. A machine token acts for no one,
with the box's person's role, capped by what it was minted with, and their scope, so it owns
nothing and operates the box only when that role is admin. A run is its own principal, for the FDE it acts for.
Scope is always the person's the credential resolves to. A disabled or unknown FDE, a session
whose FDE is gone and a node that cannot place its own FDE are refused, and sync serves no session
that no FDE stands behind. A credential is resolved again at every request, command and sync
session, and an open stream asks again each second, so a change to the roster takes effect at
main's doors on the next request and on a node after its next round. The terminal host resolves a
connection at its hello and again at each sweep (every 30 seconds): until the sweep, an open
connection opens sessions and pins rooms as the principal it was admitted as; the sweep severs one
whose credential is refused or now stands for another principal, its role or scope changed, and
severs nothing when the store cannot answer (`RosterChangeTest`). The same person with the same
kind of credential gets the same answer in the same words at every door that offers an action
(`DoorParityTest`).

**Route gates.** Every route of the web API and of the container socket declares one `Gate` in its
router's table (`RouteTable`), and a route cannot be built without one: its operation decides
an action, which the gate names; it reads in scope; the journal decides its write; the terminal
host decides it, as it decides an open or an attach (`Gate.TERMINAL`, the ticket route); it
operates the box; it manages people; or it is served before any credential is asked, for a reason (the
liveness probe, passkey login, the enrollment and sign-in pages, and registration with an
enrollment ticket). One dispatch (`RouteTable.serve`) matches a method and path for every table to
its route, a method the path does not serve, or nothing. Every router that authenticates applies
its matched route's gate before the route's handler runs; only the gates no row decides, Operate
the box and Manage people, refuse there, and both routers decide the box gate on the box's person
the role rule resolves (`RoleRule.boxFde`). The pages authenticate no one and declare only
unauthenticated routes. A deciding gate is a declaration: the router admits every caller and the
operation refuses, as its own tests and the permission snapshot prove. `RouteGateSweepTest` pins
every table the server mounts, and the socket's, whole; fails a context created anywhere but
`SailApiServer.serve`; and drives the API's box routes. What a handler answers beside its table
is proven by that router's own tests, not by the sweep. The socket's table also carries what it
has always answered a method a pattern does not serve, each pattern's last route.

The facts that read a lane or role to know which applies are the allowlist of
`OneDecisionSweepTest`. It sees every such read compiled code makes: of a role, a lane (`peer` and
`owner` included), a scope, an agent's work or whom an actor acts for, a scope or a work widened
(`withScope`, `withWork`), a principal minted with its role
(`agentPrincipal`, `roomPrincipal`, `main`), whoami's view of a role outside the two whoami
handlers, a role constant, resolver (`values` and `valueOf` included) or `switch`, a type check
of the socket's run or box caller, and an audience asked of a project in Java (`Access.audience`
with `ProjectScope.admits` or `unrestricted`), a lambda counted with the member it is written in.
The classes that define what is read are exempt by their exact names. It does not see a read
made by reflection, a door that binds `Actor.system()`, which this box's own machinery binds in
about twenty places, nor a read made as data, in SQL or from a map, which is why a filter inside
a query takes its scope from `Access.audience`. The sync server's tracking of
the content a scoped session was served, by hash, where no row names a project, stays on its
allowlist as the Read rule's own shape there.

## Security model

**One ingress by design.** The only network surface a box exposes is sshd, which the
operator runs anyway. `sail-api` binds `127.0.0.1` and is never network-reachable by
default. There are exactly two identity doors, and both land on the same `fdes` row, the
principal `RoleRule` resolves for it, and `Access`:

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

**The private network.** A client on a private network the box shares reaches it with no SSH,
through two host settings, each an IP address and a port (`ListenConfig`, the `listen` block of
`host.yaml`, set and closed with `sail host config set api-listen|terminal-listen <address>|none`):
`api-listen`, one more address the HTTP API serves besides loopback, with the same routes, gates and
credentials (`SailApiServer.PrivateNetwork`), and `terminal-listen`, where the terminal host takes TCP
connections beside its unix socket (`PtyTcpDoor`). With neither, nothing more is bound and every
answer is as before, on the unix socket too. An address must be private: loopback, RFC 1918,
100.64.0.0/10, or an IPv6 unique-local address (`fc00::/7`), written in full, with a port that has
no leading zero; a wildcard, link-local (with a zone or without: it binds only on one interface),
deprecated site-local (`fec0::/10`), multicast, broadcast, public or unparseable one is refused at
start naming its setting, and so are both settings naming one address and port, when they are set
and at start. A private address that cannot be bound yet (the private network is not up at boot)
takes nothing down: that door binds once it can (`Rebinding`, with backoff, said once per change),
loopback and the unix socket serve meanwhile, and a door whose listener is lost later is not
listening until it is bound again the same way. Each door says what it is bound to in a file beside
its socket (`api.listening`, `pty.listening`) while it is, and `sail host status` reports that, the
running services, never the config: a door listening, with the setting its next restart takes when
that differs, or one set and not listening, or refused at start and why. With neither setting and
no door bound, its `--json` has no `listen` key, as before.

These doors speak plain HTTP and TCP: use them only on an encrypting overlay (Tailscale,
WireGuard). On a LAN, a VPC or a bridge, every host on the segment reads the bearer tokens and the
tickets in the clear. Whoever can reach the address is a peer: every project container on the box
can, since a packet to the box's own address is delivered locally. Nothing is filtered by address;
the doors authenticate every request instead. The web door takes no run credential and no box
credential, and the TCP door nothing but a ticket, so a container gains no identity there it does
not hold (`DirectTerminalTest`).

A terminal connection is admitted by a ticket. The caller asks `POST /v1/terminal/tickets` with
`{"action": "open", "project": ...}` or `{"action": "attach", "session": ...}`, and is answered
`{ticket, address, expires_at}` (`TerminalTickets`). The API asks the terminal host on its unix
socket as the box's own process, carrying the caller's credential in the request
(`PtyMessage.IssueTicket`), which the host answers only on a connection that presented no token, so
a session token's connection keeps main's vocabulary; the host resolves it itself, a login session or an API token bound to an
FDE (`HostAccess.holder`), for that decision alone, and decides it as it decides that open
(`openRefusal`, a project being required: the network opens no shell on the host) or that attach
(`attachRefusal`, the one decision an `Attach` frame asks too), so there is no second rule. Its
refusal carries a kind (`PtyMessage.TicketRefused`): not found is `404`, refused `403`, a cap `429`,
an outage or a door not listening `503` (`terminal_unavailable`). A ticket to attach hears every
refusal as one sentence, "There is no session 'x' on this box that you may attach.", so the network
cannot tell a session that is not the caller's from one that does not exist; the unix socket keeps
its words. A ticket (`PtyTickets`) is a record that the decision was made, never a credential that
widens it: kept only as its hash and found by a constant-time compare, naming its credential only by
that credential's hash (`Secrets.hash`), spent by its first presentation, refused once older than
`PtyMessage.TICKET_LIFE` (30 s), bound to the credential, the action and the target; at most
`Limits.connections()` are held unpresented, and at most 8 for one credential. On TCP the door
serves nothing before a `Hello` whose token is a ticket it issued. Until then it reads at most 256
bytes, closes the connection 5 s after its connect (`PtySessionHost.tick`, each second), holds at
most 8 such connections for one peer (an IPv6 peer counted by its /64, since a host chooses its
addresses from one) and all of them in a pool of a quarter of `Limits.connections()`, whose oldest
(the first to connect: each is taken into the pool on the accept thread) is closed for a newcomer, so a peer with many addresses never fills the door and a ticketed client
always has room; the door serves `Limits.connections()` in all, apart from the unix socket's, and
closes anything but a `Hello` unanswered. Refused connections are counted by why and logged once a
tick, never once each. An accept that fails while the listener stays bound (out of descriptors) is
passed over after 100 ms and never ends the door. A presented ticket's credential is resolved
again against the roster. The connection then keeps `TCP_NODELAY` and TCP keepalive (a vanished peer
found dead within about a minute, its slot, attachment and write token released), speaks the unix
socket's `SAILPTY3` frames for its ticket's one action on its one target alone (an open ticket
creates one session in its project and attaches to it), each frame decided again as on the unix
socket, and is resolved again at every sweep and severed as a unix connection is. No ticket is
issued for no credential, for the host's own token (the box's person exists only on the unix
socket) or for a machine token. `sail _pty-selftest`, the release smoke test, opens a loopback
door, issues a ticket, presents it and hears one echo (`tcp-selftest: ok`), so the native binary
runs the door and its keepalive options.

**The terminal host on a person's own machine.** `sail _pty-host --local` (`LocalPtyHost`) is the
same host wired for the account it runs as, with no server: the native app's launchd agent runs
it, and a client speaks the same frames to it as to a box. `LocalPtyHostTest` holds each rule
below through the real host on a real unix socket and real ptys; `LocalPtyHostProcessTest` holds
them of the process an agent runs.

- **L1. One host, two wirings.** `PtySessionHost`, the frames, the ring, the shim and the spawn
  chain are the server's. The local wiring fills the same seams: `LocalPtyAccess` is its
  `PtyIdentity.Resolver` and `PtyAdmission`, `PtyEvents.NONE` its events, a `Handoff` with no
  descriptor store and the binary's own `_pty-child` its shim, a `Network` with no door its
  listener. Only what a session runs in differs (`PtyAdmission.folder`): the server takes the
  folder as named, with a login `bash`.
- **L2. Only this person.** The socket is `~/.sail/local/pty.sock` in the home the account
  database names, the one source of that home. Before anything is made there, each directory down
  to it is checked, read without following a link, by the rule ssh's StrictModes applies to
  `~/.ssh` and its parents: the home and `~/.sail` must be directories the person owns (the home
  may be root's) that no one else may write, though others may read and search them; `~/.sail/local`,
  created owner-only when absent, must be the person's and open to no one else at all. Otherwise
  the host refuses to start, naming the path, its owner and mode and the fix: `chmod go-w` for a
  home or `~/.sail` others may write, removal for `~/.sail/local`, a link or a file. What the host keeps in
  `~/.sail/local` (`sessions`, `pty.lock`, `pty.sock`) must then be of its own kind and the
  person's, read without following a link, or the start names it and says to remove it. A `chmod`
  the host cannot make is an error. There is no TCP door. A blank token is the account's person, resolved as a box's own
  person is (`RoleRule.Credential.box`, with no roster to consult), and every other token is
  refused. Whoever can open the socket is that person. Attaching, killing and listing ask `Access`
  as the server's host does, and opening asks whether that person operates this machine, so the
  open decision (`openRefusal`) is the server's, unchanged. The one other credential is the
  dispatch token the host mints beside its socket at each start (`pty-dispatch.token`, mode 0600
  in the owner-only directory, so only the person can read it): its holder may list every session
  and end one (yield), and may neither open nor kill one.
- **L3. No store, no Incus, no network.** The local host opens no database, runs no `incus`,
  binds no port and dials nothing. `LocalWiringSweepTest` follows every call, field, lambda and
  method reference from the local wiring and `PtyHostCommand`'s local branch through Sail's own
  classes, and every method a class it makes overrides of a supertype, so a call through a JDK
  interface is followed too, stopping at the shared host and pty layer; it holds that nothing it
  reaches names such a type, but what `RoleRule` names of the roster it is given as none and of a run's
  credential the local door never presents. A running host leaves its data directory empty and
  holds no internet socket.
- **L4. A session is a process in a folder.** An open naming a project or a room is refused,
  saying the local host opens folders. The folder must be an absolute path to an existing
  directory the person may enter, refused before any process starts, and is run in once
  resolved. With no command, a session runs the account's login shell from the account database
  (`Account`, `getpwuid`, never `$SHELL`) as a login shell, whose own start-up builds `PATH`; a
  login shell that cannot be run is refused at start. Its environment holds `HOME`, `USER`,
  `LOGNAME` and `SHELL` from that entry, `TERM`, and a UTF-8 character type: the host's own when
  the first of `LC_ALL`, `LC_CTYPE` and `LANG` it sets names a UTF-8 locale the C library has
  (`Locales`), else `C.UTF-8` where it has that, else `en_US.UTF-8`, as `LANG` and as each of the others the host
  sets. That is what a terminal emulator's login gives, since a service starts with almost none.
  The shell's own start-up may then set its own, as the person's profile does in any terminal.
- **L5. Sessions outlive their clients, not their host.** A stop (`SIGTERM`) hangs every
  session up, reading each terminal to its end, since on macOS a session leader's exit waits for
  that, and then deletes the sessions' transcripts and sidecars. A new host has a new boot id.
  With no descriptor store, a restart ends every session; surviving one is a later spec.
- **L6. The server wiring is untouched.** Without `--local`, the host is wired as before, and
  answers as before but for two changes to what both wirings share: a session ends with a hangup
  first (on a close, a kill, a dispatch displacing it and the sweep reaping it), so the background
  jobs of an ended interactive shell end with it; and a host that cannot make its socket's
  directory owner-only refuses to start.

One host serves one person: it holds `pty.lock` in its directory for as long as it runs, so a
second exits saying where the first serves, and one that finds the lock free, its holder dead,
replaces the socket. The lock is taken before the socket is touched and let go only after the
socket and the sessions' files are gone, so a successor started while its predecessor is still
stopping is refused. Within one process a second claim is refused before the lock file is opened
again, since closing any channel on it would let the first claim's lock go. A socket path longer than 102 bytes (macOS's 104-byte `sun_path`, less the two the JDK
keeps) is refused at start, naming the path and the limit. `sail _pty-selftest` starts a local
host, opens a session in a folder, has a line evaluated, detaches, attaches again and stops
(`local-selftest: ok`), and `ci/check local-host`, beside it in the native smoke test and the
release's, runs the binary's own `_pty-host --local` in the runner's home: it says it serves, a
second exits 1 naming it, and `SIGTERM` stops it with exit 143, leaving only its lock.

**Pairing.** Mast reaches a box as an FDE with one string, the connect code, which `sail fde
pair <handle> --host <ip-or-dns>` mints and `sail fde unpair <handle>` revokes (`FdeCommand`).
It is no third identity door: the code carries an ordinary API token bound to the FDE, so
`RoleRule` decides what it may do as for any token. What it adds is a login. Mast needs a
`direct-tcpip` channel to the API on loopback, a `direct-streamlocal` channel to
`~/.sail/pty.sock` and one `exec`, all three of which the `sail` gateway user's
`command="…",restrict` lines forbid, so a paired Mast logs in as the box's operator: the account
`sail-api` runs as, root on a SYSTEM-mode box. The command runs as that account, and the
operator's login and home are the JVM's `user.name` and `user.home`.

- **C1. One string is the whole setup.** The code is `sail1.` followed by unpadded base64url of
  one JSON object: `v` (1), `handle`, `email`, `host`, `port` (22), `user` (the operator's
  login), `host_key` (the box's `/etc/ssh/ssh_host_ed25519_key.pub`, type and blob, which Mast
  pins), `key` (an OpenSSH private key made for this pairing), `token` and `server` (the API's
  loopback address, which Mast forwards to). Mast asks for nothing else. The prefix names the
  format; `v` is for the next shape.
- **C2. The box mints it once and shows it once.** The key pair is made by `ssh-keygen` in a
  temporary directory that is gone before anything else is written, and the token's plaintext
  exists only in the code. What stays is what verifies: the public key line in the operator's
  `authorized_keys`, the token's hash, and the pairing's record (`fde_pairings`: the public key
  and the token's name, box-local, removed with its FDE).
- **C0. A code is minted only for the box's own FDE.** Its login is the operator, who reads
  the whole database, every container and every secret here, so `pair` refuses any handle but
  the box's sync handle before it writes anything, naming the box that FDE pairs on: its own,
  set up with `sudo sail init --main <main>` (`requireOwnFde`; a box with no sync handle names
  `sudo sail host config set sync-handle`). Main and a node both pair their own FDE.
- **C3. A pairing is revocable in one command, and leaves no trust behind.** One seam
  (`PairingRevocation.revoke`) revokes every pairing, for `sail fde unpair <handle>`, `sail fde rm`,
  a re-pair and the upgrade: it removes every key line the pairing made from the operator's
  login, then the pairing's key from the `authorized_keys` of every account whose home is under
  `/home` in every container of the box, running or stopped (`ContainerAuthorizedKeys`, `incus
  file pull` and `push`; virtual machines are left out: sail provisions none, and a stopped one
  serves no file). The accounts come from each container's `/etc/passwd`, never from the
  catalog; a line goes
  only when its key type and blob are the pairing's, so other keys stay, whatever their comments
  or options mention. Then the workstation key file goes when it holds that key (a file that
  cannot be read fails the revocation; only a missing one holds nothing), and last the token
  its record names is revoked and the record deleted; it is idempotent and says what it
  removed. `sail fde rm <handle>` asks the last-admin refusal first (`FdeStore.requireRemovable`,
  which the removal asks again), so a refused removal leaves the pairing whole; then it revokes,
  and only then removes the FDE, so a revocation that stops leaves the FDE to run `rm` on again.
  The revocation holds no write transaction while it reaches into containers: sail-api writes
  on through it. There is one live code per FDE per box: pairing
  again unpairs first. Revoking the token alone (`sail server token revoke mast-<handle>`)
  leaves the login; `unpair` is the revocation.

  A pairing is a key line in a file and a record in the database, and no transaction spans the
  two, so two rules keep **a login from ever outliving the record that revokes it**:
  - *The record is written before its line and forgotten after it.* `pair` mints the token and
    the record, then appends the line; `unpair` and `rm` remove the lines, then the record. A
    command that stops between the two leaves a record without a login, which the next run
    clears; one that cannot reach a container or the workstation key stops there, with the
    login gone and the record kept, so the next run finishes it. A line that cannot be removed
    fails the command before anything is forgotten
    ("Could not remove ada's key line from root's authorized_keys (AccessDeniedException), so
    nothing of ada was removed and its pairing still works. Check that /root/.ssh is writable
    by root, then run the command again."), and a file that cannot be looked at is that failure,
    never "no such line" (`OperatorAuthorizedKeys.present`).
  - *One pairing command runs at a time.* `pair`, `unpair` and `rm` each hold
    `<database>.pairings.lock` (`FileMutex`, beside the database as the sync rounds' lock is, so
    the gateway's account takes it too) from their first read to their last write. Without it a
    command reading between another's two steps forgets the record of a login just written, or
    writes a login for an FDE just removed. `rm` asks its confirmation before it takes the lock,
    so an unanswered prompt holds nobody. A `project apply` that writes (not `--dry-run`) holds
    the same lock (`PairingTrust`) from reading the workstation key to installing it, so a
    revocation either scans the containers after the key is in them or the apply reads the key
    after it is gone; a revocation waits for an apply in flight (`ProjectApplyCommandTest`,
    `FdeCommandTest`).
- **C4. Containers trust the paired key.** The pairing's public key becomes the box's
  workstation key (above), so every project provisioned or applied afterwards lets it into its
  containers, and once the code is printed the pairing writes it into the container of every
  project the box already runs, running or stopped, a stopped one left stopped
  (`ContainerAuthorizedKeys.add`): the `authorized_keys` of the `/home` account at uid 1000, the
  SSH user step 6 makes, kept at 0600 and owned by it, a line already holding the key left alone
  and every other line kept. A container bearing a project's name without the `sail-api-sock`
  device is not written but named, as `project apply --all` names it. A container
  that cannot be written, or has no SSH user or file yet, stops neither the code nor the other
  containers: the command names it after the code with the `project apply` that makes it trust the
  key (`FdeCommandTest`). Containers that cannot be listed are named once, as the box's, with
  `project apply --all`: the catalog holds projects a box never ran, and applying one of those
  would create it. Step 6 writes the workstation key, the one key a container trusts
  (`ProjectProvisioner.trustedKeys`), and its fingerprint is an input of the run's digest (`ProjectProvisioner.inputs`), so
  registering, replacing or revoking it makes the next apply of every project revisit step 6
  (`ProjectProvisionerTest`). A box trusts one workstation key: a pairing replaces the
  key of the same FDE's earlier pairing, and refuses to replace any other, naming its
  fingerprint and comment, unless `--as-workstation-key` is passed, which takes the replaced key
  out of every `/home` account of every container of the box in the same step, as unpair does,
  including those no project names (`ContainerAuthorizedKeys.removeFromEach`). A container it could
  not edit is named with the replaced key's fingerprint and comment and the line to take out by
  hand, never an apply: apply writes only the SSH user's file, and the workstation key file names
  the new key by then. Revoking the pairing takes
  its key back out of the workstation key and the containers (C3); a key set by `sail host
  config set ssh-public-key` is never touched.
- **C5. The code is a secret, handled as one.** It is printed to the terminal and nowhere else:
  no file, no log line, no exception message. Its private key has no passphrase, so Mast can
  use it unattended. Its lifetime is its token's: 365 days unless `--ttl-days` or `--no-expiry`
  says otherwise. Both halves of it end together: the key line carries the token's deadline as
  sshd's own `expiry-time="YYYYMMDDHHMMSSZ"` (UTC, cut to the second, so never later than the
  token), and sshd refuses the key from then on; `--no-expiry` writes no deadline. The UTC form
  is the one Ubuntu 24.04's OpenSSH 9.6 reads. sshd checks the deadline when a session logs in,
  so one already open ends when it closes.
- **C6. The operator's login stays theirs.** `OperatorAuthorizedKeys` is the only writer of the
  operator's own `authorized_keys`. It appends one line per pairing, with the options
  `no-agent-forwarding,no-X11-forwarding,no-user-rc` (port and unix-socket forwarding stay
  allowed: they are what Mast uses), the deadline (C5) and the comment `sail-mast:<handle>`, and
  it removes only lines carrying that comment, replacing the file in one rename (`0600`, its
  directory `0700` when it creates it). Each edit holds `authorized_keys.sail.lock`, beside the file, from its
  read to its rename (`FileMutex`), so two commands editing together, in one process or two,
  never write back a line the other removed. A paired person can therefore open a shell on the
  box as its operator; the box is theirs.

On main, pairing adds the box's own FDE when the roster holds none and applies `--name`,
`--email` and `--role`. On a node it is no roster edit: the FDE must be in the replicated roster
and active, the roster options are refused with the roster sentence, and only box-local state is
written: the login line, the token, the record and the workstation key. A handle, host, email and name are
validated where they enter (`NameValidator`), and nothing reaches a shell but as an argument.

An admin's `fde` commands also arrive through the gateway, where they run as the `sail` user,
whose `authorized_keys` is the forced-command file and must never take a login, and who cannot
reach the operator's. So that account refuses `fde pair` and `fde unpair`, and refuses `fde rm`
of a paired FDE before removing anything: each names the command to run on the host. An FDE with
no pairing is removed through the gateway as before, and no key file is touched.

- **Roles.** The roles are `admin`, `member` and `viewer`; what each may do is the Access table
  above. An unknown or blank role fails safe to viewer.
- **One identity per FDE, whichever door.** One rule, `RoleRule` (`ai.singlr.sail.identity`),
  decides the role a credential that names an FDE acts with: this box's operator on main or a
  standalone box, the FDE its sync handle names, is admin, because they hold root on it; every
  other FDE acts with the role main's roster gives it; the credential's own role caps both; and
  an FDE the roster marks `disabled`, or one it does not know, is refused. An API token, a
  passkey session, the box credential on the socket, a run's credential, the host CLI
  (`CliOperator`), a sync session, the SSH gateway and the terminal all decide through it, so
  demoting or disabling an FDE on main takes effect at every door on every box once the roster
  syncs. A node mirrors main's roster each round: an FDE main no longer lists is disabled there
  too (`FdeStore.disableUnlisted`), and on a node main scopes it is removed with its box-local
  credentials (`FdeStore.removeUnlisted`), the box's own FDE excepted, so a scoped box keeps no
  name or email outside its projects. Main itself never syncs against another box, whatever it is
  asked (`SyncOperations.resolveMain`): a round adopts the other side's versions and roster.
  - **The host CLI is the box's FDE.** Its API token (`HostToken`, named `admin`) names no FDE
    of its own: when it is used, `TokenAuth` resolves it to the FDE the box's sync handle names
    at that moment, so a host CLI write names that FDE and acts with the role `RoleRule` gives
    it, however the handle or the roster changes after minting. Nothing binds it and nothing
    needs migrating.
  - **A machine token** (any other token that names no FDE) acts as no FDE, so it records no
    author, with the role and scope of the box's FDE, its role capped by its minted role
    (`RoleRule.principal`): on a node it never acts beyond the FDE whose box it lives on. On main or a standalone box
    with no sync handle there is no FDE to be, and a machine token acts with its minted role.
  - **Before the roster arrives.** On a node whose roster does not know its own FDE yet, or
    that names no FDE, a token that acts for the box's FDE is answered `409` naming the fix
    (`sudo sail sync`, or setting the sync handle), and the host CLI, the box credential and
    the terminal are refused: nothing acts as an FDE the box cannot place
    (`CliOperator.unplaced`).
  - **A run's credential** acts as the run's principal, with the role `RoleRule` gives the FDE
    the run acts for, capped by its lane (`member` for an agent run, `viewer` for a room run),
    on the work it was launched for (`Actor.Work`); a room run reads its own project alone. A
    run whose FDE is disabled is refused at the socket.
  - **The box credential in a container** acts as the box's person on the
    `CONTAINER` lane, whose column carries no human gate: whatever runs in the container can
    read the file.
- **Whose a row is** is `Resources`' (the Access section above), each owner rule once. Owning
  a spec born in a room gives a voice in its conversation, never its settings. A spec left
  without an assignee stays unassigned, and so does its identity room; dispatch refuses it until
  someone claims it. An assignee is an FDE handle, never an agent type or a run's principal
  (`PrincipalHandle.matches`). `created_by` is the acting FDE (`Actor.actingFde`: the handle,
  or the FDE a run acts for), written once at create. A spec's creator travels as `_created_by`
  beside `_actor`: main keeps the one it holds, records the pusher when a node-born create names
  none, and fills a creator it never recorded only from that creator's own push; a node adopts
  main's, the pushing node from the creator main names when it accepts the push.
- **Speaking is not steering.** Every writer who acts for an FDE posts in every conversation of
  the projects its scope admits that this box holds, but only the owners and an active admin of
  main's roster reach the agents at work in a conversation (`RoomStore.steering`), with sail's
  own words (`sail` is no FDE's handle) and a run's, weighed as its FDE's through every principal
  it has had. A running build's inbox (`MessageStore.listUndelivered`, which also carries the
  run's own FDE, who keeps the run after a reassignment), a wake (`RoomWakeReactor`), a live
  member's prompt, the room every build, review, fix and room prompt is shown
  (`MessageStore.steering`), and an agent's own read of the room (`MessageStore.list` on the
  agent and room lanes) carry nothing else. So another member's comment never instructs an agent
  that runs on someone else's box with their credentials, and never answers its question
  (`MessageStore.openQuestions`). Main admits sail's words pushed from a node only from one that
  steers the conversation too (`MessageAuthority`), so a box whose work was reassigned narrates
  there no more.
- **Each write is decided once,** by its type's rule (the Access section above). A rule reads
  the type's synced projection — `held`, what this box holds, and `next`, the revision (null for
  a tombstone, or a tombstone's marks) — may read this box's database for owners, never writes,
  and reads whatever it derives from the row it decides from `held`, so a revision never admits
  itself. `held` is the projection at the journal's head (`RevisionJournal.held`: the last live
  state over a tombstone, nothing over a withdrawal or an erasure), never the row, which the
  write being decided has already reached. Sail itself (`Actor.isSail`: `MAIN` and `SYSTEM`) is
  never asked. A write is decided at exactly one of two points:
  - **The journal** decides every write that is not a push, as its revision is recorded
    (`RevisionJournal.recordRevision`, `recordTombstone`, and the chosen side of a resolved
    conflict; `MessageStore.append` for a post), with the bound `Actor` on the `CLI`, `API`,
    `AGENT` and `ROOM` lanes. A refusal throws `WriteRefused` and dooms the transaction
    (`Sqlite.doomed`): everything the operation wrote rolls back, even if a caller in between
    swallowed the exception. No door decides a row write, so none can forget to
    (`OneDecisionPointTest`); a new store method that journals is decided without asking.
    `stampActivity`, `Erasure.discard`, `ChangeLog.purge` and `compact`, and a box re-stamping
    the runs it made (`RunStore.stamp`, as `SYSTEM`) record no decision of an actor's.
  - **The doors** admit side effects only — HTTP, the host CLI, the socket, the terminal —
    asking `Access` before anything starts. Before a side effect the journal would then refuse
    to record, a door asks the journal's own decision (`SpecStore.decide`, `RoomStore.decide`,
    `ProjectStore.decide` and `decideRename`, one method the journal also runs): a stop that
    would cancel a spec, a catalog edit before a command clones or starts anything, a rename
    before its container is stopped, a file before its bytes are ingested. The socket answers a
    thrown refusal in HTTP's envelope (code, message, fix); the terminal keeps its
    message-and-fix text.
  - **An operation that writes several rows commits them once**, so a refusal of any row
    leaves none: a spec's birth with its room, its update with its wake and the findings a
    move into or out of `done` resolves, its delete and restore with its room, a follow-up
    with its body and its links to the findings it was drafted from, a dispatch or restart
    (the run's reservation, the reset, the claim and the superseded reviews), a rename, an
    approval, the loop's pass and escalate, and a resolve (main's side adopted and the chosen
    side written over it).
  - **A resolve is decided like any edit.** Adopting main's side is main's revision and
    decides no row, yet gives up what this box parked, so a read-only credential is refused it
    and any that can write may; keeping this box's side or merging writes over main's version,
    which the type's rule decides with `held` = main's version.
  - **Main's commit** (`RevisionJournal`, `ProjectStore`, `MessageStore`, each handed the type's
    rule by `StoreReplica.commit`) asks it for every pushed revision with the pusher as the
    actor. On `SYNC` it also decides whom a revision names: its `_actor` is the pusher, `sail` or
    a principal of a run the pusher owns (a principal of a run main does not hold yet is refused,
    not denied, until the run lands); a create's creator is the pusher; a message's author is the
    pusher, its runs' principals, or `sail` where a run of its is in the conversation. A run is its
    executing box's: `node` is the pusher, it acts for the pusher, every principal names the run
    itself, and a deleted run is never brought back; so a review run acts for the box's own FDE.
    A run whose spec or room main holds nothing of yet is its box's, and they follow in the same
    round. Blob presence is checked after the rule, not before: a denied offer's content is
    never uploaded, so a read-only session's push is denied, not refused for a missing blob, and
    an accepted one still lands only with its blob. A spec born in a room main has never held is
    refused, not denied, like a message there: specs sync before rooms, so the next round decides
    it. A restore keeps the creator its tombstone recorded, for rooms as for specs. Main's denial
    never removes
    work still under way on the node (`SyncedStore.live`): a run that has not finished, or a
    review the node is still running; once it finishes, the denial settles it.
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
  hands it to the rule it asks; the admissions for side effects — `LaunchAdmission`,
  `RoomWakePolicy` — read it too. It binds with
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
  and `ChangeLog.purge` and `ChangeLog.compact` (history rewritten under erasure and
  retention). There is no default actor, in
  production or in tests: a test binds only the writes it makes itself (`@ActingAs`,
  `Acting`), and a binding an entry point makes is proven by a test that drives that entry
  unbound wherever a test can drive it (the interactive files picker needs a console).
- **Agent principals.** Every run — dispatch, ad-hoc, review — mints an agent principal
  inside its reservation transaction: a handle (`claude/a1b2c3`) plus the FDE it acts for,
  stamped on the run row (they replicate with the run), and an opaque run credential hashed
  at rest in the local-only `run_credentials` table. The credential authenticates the
  in-container lane; every run finisher revokes it and the expired-row sweep collects
  stragglers. What an agent principal may do is its lane's column of the Access table, its
  person's role capped by the lane, on its own work. Agent-ness is a relationship, not a type: the owner link is
  attribution and its column, never a separate authorization system.
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
  plaintext-HTTP warning. The private-network listeners (`api-listen`, `terminal-listen`) are
  plain too, on named private addresses only: use them only on an encrypting overlay, since on a
  LAN every host on the segment reads bearer tokens and tickets in the clear, and every project
  container on the box is a peer of theirs, held off by authentication, not by address.
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

**Accepted risks**, each a decision rather than a gap:

- Agent install commands are enum-controlled shell snippets. npm-based installs and vendor
  install scripts are shell-oriented; the strings are sail's, never user input. User-extensible
  installers would need typed installers first.
- Dispatch gives a coding agent broad power inside its container by design. Dispatch is an
  explicit act of an FDE, the container is the isolation boundary, and the agent's output is
  untrusted.
- A few flags take secrets as process arguments (`--git-token`, `--token`) as explicit
  escape hatches; the environment, a token file or a prompt with echo off are the documented
  ways.

## Continuous integration

One workflow, `.github/workflows/ci.yml`, runs on a pull request, on a push to main and on
`workflow_dispatch`. Every job starts at once except the two native suites, which wait only for
the one native build:

| Check | What it runs | What it needs |
|---|---|---|
| `Unit tests (<module>, <os>)` | the wildcard-import check, then `ci/check unit <module>`: the module's unit tests, its JaCoCo gates and spotless, on Linux; the same tests without gates on macOS | its upstream modules, installed first |
| `Dependency scan` | OWASP dependency-check, offline against a cached NVD database refreshed best-effort | the NVD cache |
| `Native binaries` | the candidate (`-Ob` quick build) and the release `.github/released-tag` pins, each smoke-tested, uploaded as `native-binaries` | nothing else |
| `Native fleet sync tests (part i of 3)` | `ci/check methods NativeFleetIT i 3`: every third test of `NativeFleetIT`, sync end to end on native binaries in Podman boxes | `native-binaries` |
| `Multi-node control-plane integration tests` | every `*IT` that runs without incus | the runner's systemd user manager |
| `Native upgrade tests` | `UpgradeE2EIT`: the pinned release upgraded to this commit's binary on a real incus | incus and `native-binaries` |
| `Incus-backed integration tests (shard i of 4)` | `ci/check its i 4 NativeFleetIT UpgradeE2EIT`: that shard's `*IT` classes on a real incus | incus |
| `CI passed` | nothing: it fails unless every job above succeeded | every job |

- **One entry point.** `ci/check` is what CI runs and what a contributor runs, so the two cannot
  drift; see CONTRIBUTING.
- **The unit verdict is one job per module.** The reactor's modules ran in series; as a matrix,
  the slowest module bounds the verdict, not their sum. Surefire runs one fork per core
  (`forkCount=1C` in the root pom), so a test that cannot share a runner with another class
  belongs to a fork of its own, not to a slower suite. Every failsafe job passes
  `-Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
  -Dspotless.check.skip=true`, and every other build that is not a unit job (the native builds,
  the dependency scan, a unit job's install of its upstream modules) skips the two gates too: the
  unit suite, the coverage gates and the format check are the unit jobs'.
- **macOS runs the unit suite on every pull request.** A release ships a macOS binary, and only
  its own tests see Apple's libraries; a test of Linux-only behaviour says so with
  `@EnabledOnOs(OS.LINUX)`.
- **One native build per commit.** The `Native binaries` job builds the candidate once and every
  native suite downloads it. The build is cached under its inputs (the poms, every module's
  `src/main`, the `native-binaries` action, the optimization level and the GraalVM build), so a
  commit that changes only tests or CI takes the image its inputs already built, smoke-tested
  again. CI tests the quick build (`-Ob`, about half the build time);
  `release.yml` builds the optimized one (`-O2`), which only its smoke test (`-V`,
  `_pty-selftest`, `ci/check local-host`) runs. The two compile the same program; what only `-O2` could expose — a race
  faster code opens, a miscompile — is the accepted gap.
- **The incus suites run in balanced shards.** `ci/check shard` places every concrete `*IT`
  class, one with no recorded time included, in exactly one shard, longest first onto the
  lightest, from the per-class seconds in `ci/it-seconds`. A class's line there is only a weight:
  refresh it from a run's failsafe times when the shards drift apart.
- **Incus is set up by one action.** `.github/actions/incus` installs it, opens its containers'
  egress, fetches the base image and restores the suites' prepared image (the base plus every
  package they install, `AbstractIncusIT`'s bake) from a cache kept per week and per source that
  defines it; a shard that baked it on a miss keeps it with `ci/check prepared-image export`.
- **Dependencies come from a cache.** Every job restores `~/.m2` through the `maven-cache`
  action, one cache per kind of job keyed on the poms, so each kind keeps what it alone resolves;
  this repository's own modules are never cached.
- **One check proves a commit.** `CI passed` fails unless every other job succeeded: a job that
  needs a failed one reads as skipped, and a skipped check reads as passing, so a branch rule
  requires this check, not the jobs one by one. `release.yml` waits for the whole run.
- **Every fleet runs one build.** The fleet suites run boxes of the candidate only: a box of
  another release is refused at the sync hello, so there is no hop to rehearse.
- **A job leaves its evidence.** Each job that runs tests tees its maven log and ends with the
  `test-evidence` action. The job summary holds how many test classes finished, the `Tests run:`
  totals of each module and every `<<< FAILURE!` and `<<< ERROR!` line, also for a maven that was
  killed before it printed a summary; the same lines are the last of the job's log, since a job
  summary has no API. A job that did not pass, cancelled at its time limit included, uploads its
  surefire and failsafe reports as the artifact `<job>[-<label>]-test-reports`, the label naming a
  matrix leg (`unit-sail-infra-macos-latest-test-reports`, `incus-integration-shard-2-test-reports`):
  `gh run download <runId> -n <name>`.
- **`gh run view --log` truncates.** It shows about the last 5,000 lines of a job, which for
  these jobs can leave out the failure. The complete log is `gh api
  repos/<owner>/<repo>/actions/jobs/<jobId>/logs`, with the job ids from `gh run view <runId>
  --json jobs`.

## Open edges

Deliberate limits of the current design, each with its consequence:

1. **Project lifecycle is host-privileged, not API-backed.** `project apply`, `destroy` and
   `rename` drive `incus` directly, so they cannot ride the FDE gateway and need admin SSH to
   the box. A member-role FDE cannot create containers without host privileges; the control
   plane, already root on the box, would have to own provisioning for the CLI to become a pure
   client.
2. **Two remote-config models.** `ClientConfig` (SSH-forward through `host` and `user`) and
   `ServerConnectionConfig` (HTTP API through `server` and `token`) both read
   `~/.sail/config.yaml` with different keys. SSH forwarding covers every command today; a
   direct-API CLI would need them reconciled. `sail login` and `sail enroll` run their passkey
   ceremonies from a forwarding client over a supervised SSH tunnel at the canonical origin
   `http://localhost:7070`, and the stored session token has no forwarded-command consumer.
3. **FDE removal propagates as `disabled`, not a tombstone.** Revoking an FDE on main locks
   them out everywhere, since every door refuses a disabled FDE and a node disables an FDE
   main no longer lists, but the row lingers on nodes as disabled. Delete propagation is a
   roster protocol change.
4. **One platform per OS.** Linux amd64 hosts; macOS arm64 as a thin client. The terminal host's
   pty layer runs on macOS through the same spawn chain (the `macos-latest` unit lanes and the
   darwin release's `_pty-selftest` prove it), and `_pty-host --local` serves a Mac's own person
   there. The app's launchd agent that runs it is the app's to package, and with no descriptor
   store its sessions do not survive a restart.
5. **Sail never talks to the forge.** A passed review parks in `awaiting_merge`; a person
   merges the pull request and marks the spec done. A merge or release stage behind a forge
   adapter is the next step of the loop (`sail-check-stage` on the board).

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
the gateway decision), `pty()` (what the pty host asks: who a session is, which project it may
open in and which room it may join), and `schema()` (version, migration, readiness to sync). The routers never see a facet,
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
Failure and recovery emit one record event per transition. Slack is main-only: a disconnected
node's events reach main only through sync.

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

- One binary, zero runtime dependencies, fully declarative. A project's definition is the
  source of truth, and the container is derived state that can be destroyed and recreated.
- The database is the replicated source of truth for specs, projects, and shared files, and a
  project's catalog row is its definition's only copy: the loop, the API lanes, notifications
  and the agent commands read it through `ProjectReader`, and every write goes to it a section
  at a time (`ProjectStore.writeSection`), over the tag of the section its writer read, so an
  edit never lands unseen over another's and is never lost on the next sync.
- Every write names who is acting: one bound `Actor`, read by the journal and by every policy,
  never a string or an argument a caller threads through. A write with nothing bound fails.
- One owner rule (`Ownership.ownerOf`) and one role rule (`RoleRule`), each implemented once
  and read by every door: a second derivation of either is a bug.
- One write rule per synced type (`ai.singlr.sail.authority`), implemented once in sail-core,
  and each write decided once: at main's commit when pushed, at the journal otherwise. A door
  admits side effects only; a door that decides a row write, or a write that reaches a row
  around the journal, is a bug.
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
