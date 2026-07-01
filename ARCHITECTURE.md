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
  Mast (a desktop GUI client, in development), and through future mobile clients.

### Glossary

- **FDE.** Forward Deployed Engineer: the human operating coding agents at a customer. Each
  FDE has their own bare-metal box.
- **Box or host.** A bare-metal server running Incus, Podman, and the Sail control plane.
  Every FDE's box is a full working environment.
- **Main.** The one box designated the org's source of truth for specs and project
  definitions. A **node** is a box pointed at a main that syncs to it. A **standalone** box
  has no sync peer. Standalone is the default, and sync is opt-in.
- **`sail-api`.** The per-box control-plane service: a loopback HTTP API and event stream
  that the CLI, in-container agents, and Mast talk to. It runs as a systemd unit.
- **Client.** A thin machine, typically a Mac, that drives a box remotely over SSH and runs
  no control plane of its own.
- **Mast.** A desktop GUI client for FDEs who prefer a GUI, currently in development. It
  connects to a box's API for specs and review, and to its workspaces for execution.

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
   ┌─────┴───────────┐                              Mast / browser, HTTP via a TLS reverse
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

Thirteen `*Store` classes plus the sync and journal machinery, all over `Sqlite`:

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

Migrations are two-layer and idempotent. `SchemaManager.MIGRATIONS` is an append-only,
version-tracked list of SQL statements, and entries are never reordered or removed. A
separate `DataMigration` framework runs one-shot content fix-ups exactly once, tracked by
name. `MigrationRunner.applyAll` runs schema migrations then data migrations, and
`MigrateCommand` additionally runs the idempotent data backfills (`applyDataBackfills`).
Those backfills make pre-journal specs and projects syncable, import on-disk descriptors,
scrub per-developer identity to placeholders, import shared files, and seed the bundled
demo. They run on `sail migrate`, on `sail upgrade` (which spawns the new binary's `migrate`
so a release's new migrations actually execute), and on every daemon start, so a failed
upgrade-time migration self-heals on the next service start.

## The sync layer: one main, many nodes

This is the heart of multi-FDE coordination. Sync is opt-in, and a standalone box does
nothing here.

### What syncs, and how

`sail sync` runs three bidirectional entity reconciliations plus one one-way roster pull:

| Entity | Direction | Replica | Notes |
|---|---|---|---|
| specs | Bidirectional, field-level three-way merge | `SpecReplica` | The team board |
| project definitions | Bidirectional | `ProjectReplica` | The `sail.yaml` catalog |
| shared workspace files | Bidirectional | `FileReplica` | The `files/` bundle, opaque content |
| FDE roster | One-way, main-authoritative pull | `FdeStore.replicate` | Handle, name, email, role, status, and never keys or tokens |

The three replicas are pure delegation adapters that implement both `LocalReplica` and
`MainReplica`, so the same box acts as the node when it syncs up and as the authority when
another node syncs to it.

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
conflict. That property is what makes the migration backfills and the identity scrub safe
across a mixed-version fleet. Reserved snapshot keys prefixed with `_` (such as `_actor`,
which carries author attribution) ride along but are excluded from conflict detection, so
attribution propagates without ever causing a false conflict.

Parked conflicts live in the `sync_conflicts` table, one open conflict per entity, and are
resolved with `sail conflicts`. It takes `--mine` or `--theirs`, and `--merge` for specs. A
file's content and a project's definition are single opaque blobs, so they take mine or
theirs only. Resolving rebases the row onto main's version and writes the choice, so a
follow-up sync converges and the conflict cannot re-raise. Every version stays in the change
log, so no choice loses work.

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
runs migrations and backfills, ensures an admin token, and serves the REST API and the SSE
event stream (`/v1/events/stream`) on loopback, the passkey endpoints when configured, and a
Unix-socket listener so project containers publish events and drive `spec` over the
bind-mounted socket with no TCP and no token. Every box runs it, and it is what lets an
engineer dispatch agents locally.

**Dispatch and agent execution** (`DispatchCommand` plus `sail-harness`): autonomous
dispatch reads the next ready spec from the database, honoring `depends_on` and assignee,
marks it `in_progress`, snapshots the container for rollback, creates the work branch, and
launches the agent headless under `systemd-run --user` with `SAIL_SPEC_ID` and `SAIL_AGENT`
in its environment so in-container hooks correlate events back to the spec. The agent's
output streams to the log live (Claude Code via `--output-format stream-json`), which also
feeds the watcher's liveness signal. Agents inside the container manage specs through a
dependency-free `spec` shell script that talks to `sail-api` over the bind-mounted Unix
socket, needing no `sail` binary, token, or files. `sail spec dispatch --restart` re-runs a
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

**Multi-agent review loop:** review is agent-agnostic across claude-code and codex. When the
coder's dispatch stops cleanly, the spec moves to `review` and a secondary reviewer runs at
spec completion (falling back to self-review when only one agent is installed; a per-spec
`agent` overrides the project default). The reviewer is read-only and emits findings; if a
stage's gate fails, a fix agent addresses the open findings on the same branch and the
reviewer runs again, bounded by `max_iterations`, after which the spec `escalates` and parks
in `review` for a human.

Reviewer and fix agents run on the same agent command and log handling as dispatch, but not
its process wrapper. Dispatch is fire-and-forget (it launches a detached `systemd-run --user`
unit and an external `sail agent watch` monitors it); a review blocks the pipeline until it
has findings, so `ContainerReviewAgentRunner` runs it as a bounded foreground `shell.exec`
(30-minute per-invocation timeout) that needs no systemd user manager or D-Bus session, so it
works in any container. The output streams to `review.log` (appended, so an attempt's whole
reviewer-and-fix negotiation lands in one live-followable log, reset per dispatch attempt),
findings are parsed from the bytes that run appended (read by offset so a plain agent's
re-review is never fed a prior iteration's findings) via `StreamJsonResult`. The reviewer runs
clean (empty `SAIL_SPEC_ID`, no hooks) so its own completion never re-enters the pipeline.
Follow it live with `sail agent log <project> --review`.

**Recovery without losing work.** The git branch is the durable record: every coding agent
(build and fix) commits before it stops, and neither a guardrail stop nor an escalation ever
discards it. So an FDE always recovers by returning to the branch. When a spec is stuck: a
guardrail-killed or failed dispatch leaves the work committed, so `sail spec dispatch
--restart` resumes on the branch; an escalated review parks in `review` with its findings (in
the review store), its negotiation (`review.log`), and every fix commit intact, so the FDE
reads it with `sail agent review <project>` plus `sail agent log <project> --review`, then
resolves with `sail spec edit <id> --status done` (accept the work as-is) or `--status
pending` (send it back to be re-dispatched). Nothing is deleted along the way.

**Events:** an in-process `EventBus` (lock-light, with bounded per-subscriber queues that
are lossy by design so publishers never block) fans out to the SSE stream and to startup
reactors: audit persistence, the webhook reactor, and the spec lifecycle reactor, which
advances a spec from `in_progress` to `review` when its agent session ends. A
`board_updated` event after a sync that changed the board surfaces an updates-available
banner in the CLI and in Mast.

## Security model

**One ingress by design.** The only network surface a box exposes is sshd, which the
operator runs anyway. `sail-api` binds `127.0.0.1` and is never network-reachable by
default. There are exactly two identity doors, and both land on the same `fdes` row, role,
and `Authorizer`:

1. **Terminal: SSH key to FDE.** Each registered key is pinned in the `sail` user's
   `authorized_keys` to `command="…/sail _gateway --fde <handle>",restrict`, with no shell
   and no forwarding. Command classification is default-deny: only `spec`, `agent`, and
   `events` reach the loopback API, `fde` is admin-gated at the gateway, `_sync` is admitted,
   and everything else is refused. A short-lived session is minted per invocation. `sail fde
   add <handle> --key "<pubkey>"` is the whole enrollment, and removing the key revokes SSH
   and API access in one step.
2. **Web, opt-in and off by default: passkeys and WebAuthn.** For Mast and browser clients
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
- **Tokens.** Host-local bearer tokens are SHA-256 hashed at rest, returned in plaintext
  once, and stored `0600` under a `0700` `~/.sail`. They carry a 90-day default expiry, where
  a null TTL means never-expires and is kept only for break-glass and bootstrap, and an
  hourly `ExpiredRowSweeper` prunes expired tokens, sessions, challenges, and tickets. The
  token resolution order (`--token`, then `--token-file`, then `SAIL_TOKEN`, then
  `SAIL_TOKEN_FILE`, then config) lets a token stay off the process list.
- **Rate limiting.** A per-credential token-bucket limiter, defaulting to 600 per minute,
  sits after auth in the API router and returns `429` when exceeded.
- **Transport and TLS are out of scope by design.** `sail-api` serves plain HTTP on loopback
  and never terminates TLS or issues certificates. For network or browser access (Mast,
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
support Mast and direct-API clients:

1. **Project lifecycle is host-privileged, not API-backed.** `project up` and `project
   create` drive `incus` directly, so they cannot ride the FDE gateway, and provisioning
   needs admin SSH. The fix is for the control plane, which is already root on the box, to
   own provisioning and for the CLI to become a pure client. This is the largest gap before a
   member-role FDE can create containers without host privileges.
2. **The host admin token rides along for back-compat**, where a missing role maps to ADMIN.
   Once SSH-key and passkey identity are field-proven, it should be scoped down to
   break-glass.
3. **Two remote-config models.** `ClientConfig` (SSH-forward through `host` and `user`) and
   `ServerConnectionConfig` (HTTP API through `server` and `token`) both read
   `~/.sail/config.yaml` with different keys. SSH-forwarding papers over this today, but a
   direct-API client (Mast, or a future direct-mode CLI) needs them reconciled. A passkey
   `sail login` session has no CLI consumer on a forwarding client yet.
4. **Sync identity is the box hostname.** Replicas key off the hostname rather than a stable
   per-box id, so renames or collisions could confuse sync identity. A stable id is the
   robust fix. The risk is low for a known small fleet and worth doing before larger ones.
5. **Attribution gaps in synced files.** Per-actor attribution rides via `_actor` for specs
   and projects, but shared files have no author column at all, which is a schema change for
   low value, and the change-log's internal author column stays null for projects and files.
6. **FDE removal propagates as `disabled`, not a tombstone.** Revoking an FDE on main locks
   them out everywhere, since the gateway refuses a disabled role, but the row lingers on
   nodes as disabled rather than disappearing. True delete-propagation is a roster protocol
   change.
7. **One platform per OS.** Mac arm64 and Linux amd64 only.

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
