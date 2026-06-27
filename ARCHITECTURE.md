# Sail Architecture

> Status: living document. Captures the positioning and design decisions behind `sail`.
> Last substantive update: 2026-06-21 (reconciled against the code at v0.13.93).

## What Sail is

**Sail is to coding agents what Kubernetes is to containers** — the orchestration
layer for agentic software development. It does **not** compete with Claude Code,
Codex, or any other coding agent; those are the agents. Sail is everything around
them: isolated environments, spec-driven task management, cross-agent review, and
multi-engineer coordination.

The guiding question for any feature is *"does this help coordinate agents?"* — never
*"does this help write code?"*. Sail should never try to be a coding agent.

### Core value proposition

- **Isolated dev environments** — one Incus system container per project on bare metal,
  each a full Ubuntu 24.04 userspace with its own filesystem, network, and rootless
  Podman runtime. Hard isolation: a runaway agent in one project cannot touch another.
- **Spec-driven task management** — agent-native specs (like Linear, not JIRA) held in
  a SQLite control plane, with a status lifecycle, assignees, and dependencies.
- **One shared source of truth across a team** — one **main** devbox holds the org's
  specs *and* project definitions; every other engineer's box **syncs** them down and
  pushes their own work back, over pure SSH keys with conflict resolution. No GitHub in
  this loop, no central scheduler.
- **Cross-agent review pipelines** — if Claude writes the code, Codex reviews it.
- **Remote operation** — via the CLI (on the box or from a Mac thin client), Mast (the
  IntelliJ Platform IDE), and future mobile clients.

### Glossary

- **FDE** — Forward Deployed Engineer: the human operating coding agents at a customer.
  Each FDE has their own bare-metal box.
- **Box / host** — a bare-metal server running Incus, Podman, and the Sail control
  plane. Every FDE's box is a full working environment.
- **Main** — the one box designated the org's source of truth for specs and project
  definitions. **Node** — a box pointed at a main that syncs to it. **Standalone** — a
  box with no sync peer (the default; sync is opt-in).
- **`sail-api`** — the per-box control-plane service: a loopback HTTP API + event stream
  that the CLI, in-container agents, and Mast talk to. Runs as a systemd unit.
- **Client** — a thin machine (typically a Mac) that drives a box remotely over SSH and
  runs no control plane of its own.
- **Mast** — IntelliJ Platform-based desktop IDE for FDEs who prefer a GUI. Connects to
  a box's API for specs and review, and to its workspaces for execution.

## Topology — one main, many nodes

```
   Node box (FDE: mady)                        Main box (FDE: uday — org source of truth)
   ┌────────────────────────────┐             ┌──────────────────────────────────────────┐
   │ sail-api (127.0.0.1:7070)  │  sail sync  │ sail-api (127.0.0.1:7070)                  │
   │  SQLite control plane       │  over SSH   │  SQLite control plane (authoritative for   │
   │   specs / projects / files ◀┼─────────────┼▶  specs, projects, files; roster source)   │
   │   (local replica)           │ sail@main   │                                            │
   │  Incus containers (per      │  _sync lane │  Incus containers (per project)            │
   │   project) + Podman + agents│             │  + Podman + agents                         │
   └────────────────────────────┘             └──────────────────────────────────────────┘
         ▲                                                ▲
         │ SSH (gateway: sail@box → _gateway --fde)       │
   ┌─────┴───────────┐                              Mast / browser ── HTTP (via TLS reverse
   │ Mac thin client │                              proxy, passkeys) ──▶ sail-api
   │ sail (darwin)   │
   └─────────────────┘

  Specs, project definitions, and shared workspace files are reconciled main↔node.
  Compute is NEVER scheduled across boxes — each FDE runs agents only in their own
  containers. The star coordinates *state*, not *execution*.
```

A box's role is plain declared state in `host.yaml` (`SyncConfig`): `role: main`,
`role: node` + `main: <ssh-target>`, or unset (standalone). `sail host sync --as-main` /
`--main <target>` sets it. Main never initiates; nodes pull from and push to main.
Main failover is manual and out of scope for v1.

## Module layout

Three Maven modules, Java 25, one native binary.

| Module | Package roots | Responsibility |
|---|---|---|
| `sail-core` | `auth`, `common`, `config`, `engine`, `gen`, `ssh`, `store`, `sync`, `webauthn` | Domain model + config records, the Panama-SQLite store, the DB-sync engine and replicas, passkey/WebAuthn primitives, SSH gateway tokenizer, pure file generators, shared id/time/string seams. **No CLI, no HTTP, no native binary.** Sole external dep: SnakeYAML Engine. |
| `sail-harness` | `engine` | The in-container agent harness — runs *inside* project containers: agent sessions, guardrail checker, reporting, the in-container `spec` CLI helper, webhook notifier, hook config writers. |
| `sail-infra` | `api`, `commands`, `engine` (+ `Main`/`Sail`/`SailVersion`) | The picocli CLI, the loopback HTTP control-plane API + reactors, and host-side provisioning engines (Incus/Podman/ZFS/systemd drivers). Builds the GraalVM native binary `sail`. |

**Coverage discipline:** `ai.singlr.sail.api.*` is held to **100% line + method** JaCoCo
(minus a documented exclude list of streaming/socket I/O classes that need fault
injection); the rest of the bundle has ratcheted floors set just under the actuals.

## Control plane — SQLite over Panama FFM

`sail server start` runs the control plane on the same bare metal that hosts the Incus
containers — server and node are one process; there is no separate node binary. It is
normally run as the `sail-api` systemd service rather than by hand.

- **SQLite is the single source of truth**, not git. Agents interact with specs through
  the CLI / API, never the filesystem.
- **Panama FFM directly over `libsqlite3.so.0`.** JDK 25's finalized Foreign Function &
  Memory API calls the system SQLite directly — opened WAL, `foreign_keys=ON`, 5 s busy
  timeout, full-mutex serialized mode. **No JDBC anywhere.** `sqlite4j` was rejected
  (single-threaded, no WAL). The wrapper (`store/Sqlite`) exposes only
  execute/query/transaction — it is deliberately not a general JDBC driver.
- **Specs are global entities**, filterable by project / assignee / status — like
  Linear, not nested under projects like JIRA.

### The store layer (`ai.singlr.sail.store`)

Thirteen `*Store` classes plus the sync/journal machinery, all over `Sqlite`:

| Store | Owns | Synced across boxes? |
|---|---|---|
| `SpecStore` | `specs` (+ dependencies, repos, content, attachments) | **Yes** — entity `spec` |
| `ProjectStore` | `projects` (the `sail.yaml` descriptor blob + attribution) | **Yes** — entity `project` |
| `FileStore` | `project_files` (shared workspace files, keyed by project+path) | **Yes** — entity `file` |
| `FdeStore` | `fdes` (the human principals + roles) | one-way (main → node roster pull) |
| `FdeSshKeyStore` | `fde_ssh_keys` (SSH fingerprint → FDE) | local |
| `EventStore` | `events` (the audit stream, persisted from the bus) | local |
| `ReviewStore` | `reviews` / `review_stages` / `review_findings` | local |
| `SessionStore` | `agent_sessions` (agent lifecycle) | local |
| `AuthSessionStore` | `sessions` (login/gateway sessions) | local |
| `TokenStore` | `api_tokens` (SHA-256 hashed, optional expiry) | local |
| `WebauthnCredentialStore` | `webauthn_credentials` (passkeys) | local |
| `PendingChallengeStore` | `webauthn_challenges` (single-use ceremony challenges) | local |
| `EnrollmentTicketStore` | `enrollment_tickets` (one-time passkey enrollment) | local |

Sync/journal support classes in the same package: `ChangeLog` (the append-only revision
journal — the durability spine of sync), `Revisions` (content-addressed rev ids),
`ConflictDetector` (pure three-way merge), `ConflictResolver` (implemented by the three
synced stores), `SyncConflicts` (parked conflicts), `SyncState` (per-peer checkpoints),
`ExpiredRowSweeper` (hourly housekeeping), `PushOutcome` (CAS result).

**Migrations are two-layer and idempotent.** `SchemaManager.MIGRATIONS` is an append-only,
version-tracked list of SQL statements (never reorder or remove an entry); a separate
`DataMigration` framework runs one-shot content fix-ups exactly once, tracked by name.
`MigrationRunner.applyAll` runs schema then data migrations, and `MigrateCommand`
additionally runs the idempotent **data backfills** (`applyDataBackfills`) — these make
pre-journal specs/projects syncable, import on-disk descriptors, scrub per-developer
identity to placeholders, import shared files, and seed the bundled demo. The backfills
run on `sail migrate`, on `sail upgrade` (which spawns the *new* binary's `migrate`, so a
release's new migrations actually execute), **and on every daemon start** — so a failed
upgrade-time migration self-heals on the next service start.

## The sync layer — one main, many nodes

This is the heart of multi-FDE coordination and the part that grew most since this doc
last described it. Sync is **opt-in**: a standalone box does nothing here.

### What syncs, and how

`sail sync` runs three **bidirectional** entity reconciliations plus one **one-way**
roster pull:

| Entity | Direction | Replica | Notes |
|---|---|---|---|
| **specs** | bidirectional, field-level 3-way merge | `SpecReplica` | the team board |
| **project definitions** | bidirectional | `ProjectReplica` | the `sail.yaml` catalog |
| **shared workspace files** | bidirectional | `FileReplica` | the `files/` bundle; content is opaque |
| **FDE roster** | one-way, main-authoritative pull | `FdeStore.replicate` | handle/name/email/role/status — never keys or tokens |

The three replicas are pure delegation adapters that implement *both* `LocalReplica`
and `MainReplica`, so the same box is the node when it syncs up and the authority when
another node syncs to it.

### The engine

`SyncEngine.reconcile(local, main)` is **entity-agnostic, order-independent, idempotent,
and stateless** — all state lives in the replicas. For each entity it feeds three
snapshots — the merge **base** (the rev the local row descended from on main), the
**local** current, and the **remote** (main) current — into the pure `ConflictDetector`,
which classifies at *field* granularity:

- **Converged** — both sides already agree (even if they reached it independently); link
  the shared revision and move on.
- **TakeRemote / KeepLocal** — only one side moved; fast-forward (including deletes).
- **Merged** — disjoint fields changed on each side; auto-merge.
- **Conflict** — the *same* field changed to different values (or a delete races an
  edit); the local row is **left untouched** and the conflict is parked.

Pushes are **compare-and-set**: main mints the revision and rejects a stale expected rev,
so the engine re-reconciles against main's fresh state (bounded retries) rather than ever
silently overwriting. Revisions are **content-addressed** (`<counter>-<shortHash>`): two
boxes that independently reach the same content mint the same rev and converge with no
conflict — which is what makes the migration backfills and identity scrub safe across a
mixed-version fleet. Reserved `_`-prefixed snapshot keys (e.g. `_actor`, carrying author
attribution) ride along but are excluded from conflict detection, so attribution
propagates without ever causing a false conflict.

Parked conflicts live in the `sync_conflicts` table (one open conflict per entity) and
are resolved with **`sail conflicts`** (`--mine` / `--theirs`, or `--merge` for specs —
a file's content and a project's definition are single opaque blobs, so they take
mine/theirs only). Resolve rebases the row onto main's version and writes the choice, so
a follow-up sync converges and the conflict can't re-raise; every version stays in the
change log, so no choice loses work.

### The transport — pure SSH keys, no network enroll

A node reaches main over a single SSH subprocess — `ssh sail@main sail _sync` — using
that process's stdio as a newline-framed JSON RPC pipe. Auth is **pure SSH keys**:
`PasswordAuthentication=no`, `IdentitiesOnly=yes` pinning the sail-managed key that
`sail join` generated, so a missing key fails fast. On main, that key sits on the locked
`sail` user's `authorized_keys` as a forced command
(`command="…/sail _gateway --fde <handle>",restrict …`); the gateway resolves the FDE,
admits the `_sync` session, and the write gate lives next to the write — the `_sync`
server refuses pushes from read-only (viewer) roles while still letting them pull.

### Identity isolation in synced projects

A project definition mixes shared infrastructure with two inherently **per-developer**
fields: the git identity commits are authored with, and the SSH key authorized into that
box's containers. These must never ride onto a teammate's box.

The synced definition therefore carries **placeholders**, not concrete values:
`PersonalFields.redact` rewrites `git.name`/`git.email` → `${GIT_NAME}`/`${GIT_EMAIL}`
and `ssh.authorized_keys` → `[${SSH_PUBLIC_KEY}]` at the single catalog-write seam
(`ProjectStore.upsert`); redaction is pure, idempotent, and deterministic, so every box
agrees on an identity-free definition and the engine converges. Each box resolves the
placeholders **locally, once, at provision time** (`ProjectDefinitions.resolveForProvisioning`
→ `LocalIdentity`): git fields from local `git config`, `${SSH_PUBLIC_KEY}` from the box's
own sail key. A missing value fails loud with the fixing command rather than silently
provisioning someone else's identity. So each engineer's containers commit as them and
trust only their own key.

### Live resource resync

A project's `resources` (cpu / memory / disk) sync like any other field — bump them on
any box and they propagate. After a sync that pulled or merged a project,
`ProjectResourceReconciler` resizes that project's **running container in place** to the
new limits — no recreate, no restart, because a background sync must never disrupt a live
container. It is best-effort and never fatal: an unprivileged sync with no incus access
is a quiet no-op, and a disk shrink the backend refuses (ZFS below used space; advisory
on the `dir` backend) is reported and skipped.

### No GitHub in the project loop

Project descriptors are distributed entirely by DB sync — there is no `project pull` /
`project push`, and the demo project is bundled in the binary and seeded into the catalog
(idempotent, never resurrected once purged). The remaining git-token plumbing is only for
cloning a project's *source repos* into its container at provision time, not for moving
descriptors.

## Runtime modes — host vs client

`Main` chooses a mode at startup via `RuntimeMode.detect()`:

- **Host mode** — `~/.sail/host.yaml` exists (the default). Commands execute locally:
  lifecycle commands drive `incus`/`podman`; API commands hit the local `sail-api`.
- **Client mode** — only `~/.sail/config.yaml` exists. Commands are forwarded to a box
  over SSH by `RemoteCommandRunner`, in lanes:
  - **Local** (`--version`, `upgrade`, `init`, `client`, `login`) run on the client.
  - **Host-only** (`host …`) error with guidance to SSH in.
  - **FDE-gateway commands** (`spec`, `agent`, `events`, `fde`) target `sail@host`. The
    engineer's SSH key hits the forced command `sail _gateway --fde <handle>`, which
    resolves their FDE, mints a short-lived session token, and re-execs the command —
    so the loopback API sees *who* is acting and `Authorizer` enforces their role.
  - **Everything else** (project lifecycle, interactive `shell`/`exec`) is forwarded as
    the plain SSH host, because it needs host privileges the `sail` user must never have.

This is why the loopback-only API is not a limitation for a Mac client: gateway commands
run *on the host*, where the API is reachable at `127.0.0.1:7070`.

## Onboarding

One convergent, idempotent, root-aware command per box — `sail init`:

- `sudo sail init --as-main` — make this box the org's source of truth.
- `sudo sail init --main <target>` — join an existing main as a node.

It figures out what's missing and does only that (decided by a pure, unit-tested
`InitPlan`): provision the host if needed → install and start `sail-api` (a **system**
service under root, or a **per-user** service for the `sudo` user otherwise) → take on
identity (main: publish the SSH identity + declare `--as-main`; node: `join`, which
generates the box's sync key and prints the `sail fde add … --key …` line for the
operator to run on main). The granular commands it orchestrates (`host init`, `host
service install`, `host ssh-identity`, `host sync`, `join`) all remain usable on their
own.

A thin client (a Mac that drives a box but runs no control plane) is set up separately
with **`sail client <host>`**, which writes `~/.sail/config.yaml` pointing at the box (an
IP, hostname, or `~/.ssh/config` alias). The release pipeline builds `sail-darwin-arm64`
alongside `sail-linux-amd64`; `install.sh` detects the platform, verifies magic bytes +
SHA-256, and strips the Gatekeeper quarantine flag.

## Provisioning + execution engine

`sail` is a thin orchestrator — it drives `incus` / `podman` / `systemd` / `ssh` through
`ShellExecutor`; every command maps to calls the operator could run by hand.

**Container provisioning** (`ProjectProvisioner`) is a ~20-step idempotent, resumable
pipeline (a `ProvisionTracker` lets a failed run pick up where it stopped): launch the
Incus container from the configured image; bind-mount the host `sail-api` Unix socket in
and install the in-container `spec` CLI + event hooks; apply the disk quota (`dir`
advisory / `zfs` hard via `refquota`) and cpu/memory limits (with `security.nesting` +
unconfined AppArmor so rootless Podman runs inside); install packages, the SSH user +
authorized key, Podman (with linger + a restart service), Testcontainers wiring, the
JDK / Node / Maven runtimes; configure git identity and clone source repos; push the
shared `files/` bundle; start Podman services (`--restart=always`); install the agent
CLIs and generate agent context. `ProjectApplier` is the live-delta path (`sail project
apply`) for already-running containers; CPU/memory changes that need a restart are
applied by the dedicated resources path, not silently here.

**The `sail-api` service** (`SystemdServiceInstaller`) is the per-box control plane: a
`sail server start` systemd unit, **SYSTEM** scope under root
(`/etc/systemd/system`) or **USER** scope per-user (`systemctl --user` + linger). It
opens the control-plane SQLite DB, runs migrations + backfills, ensures an admin token,
and serves: the REST API and the **SSE event stream** (`/v1/events/stream`) on loopback;
the passkey endpoints (when configured); and a **Unix-socket listener** so project
containers publish events and drive `spec` over the bind-mounted socket with no TCP or
token. Every box runs it — it's what lets an engineer dispatch agents locally.

**Dispatch + agent execution** (`DispatchCommand` + `sail-harness`): autonomous dispatch
reads the next ready spec from the DB (honoring `depends_on` + assignee), marks it
`in_progress`, snapshots the container for rollback, creates the work branch, and launches
the agent headless under `systemd-run --user` with `SAIL_SPEC_ID`/`SAIL_AGENT` in its
environment so in-container hooks correlate events back to the spec. Agents inside the
container manage specs through a dependency-free `spec` shell script that talks to
`sail-api` over the bind-mounted Unix socket — no `sail` binary, token, or files needed.

**Agent context: sail owns the home layer, the engineer owns the workspace**
(`AgentContextGenerator`): both Claude Code and Codex natively merge a home-level context
file with project-level files, so sail writes its layer to the home namespace
(`~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`) and overwrites it every run, while the
engineer owns `~/workspace/CLAUDE.md`/`AGENTS.md` outright — sail never creates or touches
it, and being "closer" it overrides sail's layer on conflict. No `@import` pointer and no
`--force`; sail only ever overwrites its own home namespace. The body encodes project
orientation (tech stack, conventions, runtimes, services), language-agnostic engineering
principles, a short security stance (the full OWASP rubric lives in the review/audit
prompt), the spec-driven workflow (DB-authoritative — no `specs/` directory to edit), and
the autonomous-operation protocol. Methodology/spec skills land under the home skills
namespace; sail ships no hardcoded language standards. A project may supply its own via
`agent_context.rules` (name → `{paths, body}`): sail materializes each into the agent's native
"load only when relevant" channel — a path-scoped Claude rule (`~/.claude/rules/<name>.md` with a
`paths:` glob) and a description-loaded Codex skill — so Java standards reach the agent only while
it edits Java, never bloating the always-loaded context. The bodies are project-supplied; sail
ships none.

**Guardrails + rollback:** `agent.guardrails` sets a `max_duration` and an action
(`snapshot-and-stop` / `stop` / `notify`). An event-driven watcher (`sail agent watch`,
auto-started by dispatch) merges the deadline with agent-exit events off the SSE stream;
on trip it snapshots and/or kills the agent and fires notifications. Rollback is Incus
snapshots (instant on `zfs`, full copies on `dir`); the pre-dispatch snapshot is the
restore point.

**Multi-agent review:** agent-agnostic (claude-code, codex). When enabled, a *secondary*
agent runs the security audit and/or code review at spec completion (falling back to
self-audit when only one agent is installed); a per-spec `agent` overrides the project
default. These stages are baked into the generated completion protocol and post-task
hooks.

**Events:** an in-process `EventBus` (lock-light, bounded per-subscriber queues, lossy by
design so publishers never block) fans out to the SSE stream and to startup reactors —
audit persistence, the webhook reactor, and the spec lifecycle reactor (which advances a
spec `in_progress → review` when its agent session ends). A `board_updated` event after a
sync that changed the board surfaces an "updates available" banner in the CLI and Mast.

## Security model

**One ingress by design.** The only network surface a box exposes is sshd, which the
operator runs anyway; `sail-api` binds `127.0.0.1` and is never network-reachable by
default. There are exactly two identity doors, both landing on the same `fdes` row, role,
and `Authorizer`:

1. **Terminal: SSH key → FDE.** Each registered key is pinned in the `sail` user's
   `authorized_keys` to `command="…/sail _gateway --fde <handle>",restrict` — no shell,
   no forwarding, a **default-deny** command classification (only `spec`/`agent`/`events`
   on the loopback API, `fde` admin-gated at the gateway, and `_sync`; everything else
   refused), and a short-lived session minted per invocation. `sail fde add <handle>
   --key "<pubkey>"` is the whole enrollment; removing the key revokes SSH and API access
   in one step.
2. **Web (opt-in, off by default): passkeys / WebAuthn.** For Mast/browser clients only.
   Until the operator configures the `webauthn` block (RP id, origins) the endpoints
   answer `503`. Login start/finish is unauthenticated by design; registration needs an
   enrollment ticket or an authenticated admin.

- **Roles + Authorizer.** `admin` / `member` / `viewer`, enforced at the API boundary:
  GET ⇒ READ, mutating ⇒ WRITE, sensitive routes ⇒ ADMIN; an unknown/blank role fails
  safe to `viewer`. Attribution (`created_by`/`updated_by`/`decided_by`) is stamped
  **server-side** from the validated token's FDE — never from client input.
- **Tokens.** Host-local bearer tokens are SHA-256-hashed at rest, returned in plaintext
  once, and stored `0600` under a `0700` `~/.sail`. They carry a **90-day default expiry**
  (a null TTL = never-expires, kept only for break-glass/bootstrap), and an hourly
  `ExpiredRowSweeper` prunes expired tokens, sessions, challenges, and tickets. Token
  resolution order (`--token` → `--token-file` → `SAIL_TOKEN` → `SAIL_TOKEN_FILE` →
  config) lets a token stay off the process list.
- **Rate limiting.** A per-credential token-bucket limiter (default 600/min) sits after
  auth in the API router and returns `429` when exceeded.
- **Transport / TLS is out of scope by design.** `sail-api` serves plain HTTP on loopback
  and never terminates TLS or issues certificates. For network or browser access (Mast,
  passkeys) the operator fronts it with a TLS-terminating reverse proxy (Caddy / Traefik /
  nginx) that owns the public hostname and cert lifecycle; for passkeys the secure context
  and RP id are the browser's view of the proxy's `https://` origin. Binding off loopback
  is an explicit `sail server start --host 0.0.0.0` and prints a plaintext-HTTP warning.
- **Input hardening.** Repo/git URLs are validated and rejected if they start with `-`
  (so `git clone` can't read them as options), with `--` guards on git invocations;
  paths and refs reject `..` traversal; FDE handles are a fail-closed boundary for the
  `authorized_keys` forced command. Webhook URLs are SSRF-checked at config-parse time
  **and re-resolved at send time** (DNS-rebinding defense), range-checking every resolved
  A/AAAA record including obfuscated and IPv4-mapped-IPv6 forms.
- **Untrusted by design.** Spec markdown and agent output are untrusted prompt input;
  reviewer / audit / handoff flows never treat them as authoritative instructions.
- **Releases are signed.** The release workflow builds the GraalVM native images, runs
  the test suite, and publishes `sail-linux-amd64` and `sail-darwin-arm64` (plus a `sail`
  alias), each with a `.sha256` **and a keyless-cosign `.cosign.bundle`** (Sigstore via
  GitHub OIDC). Every GitHub Action is pinned to a full commit SHA.

See `SECURITY_AUDIT.md` for the full checklist and accepted risks.

## Known gaps / evolution

The deliberate edges between today's tool and the multi-FDE platform that must support
Mast and direct-API clients:

1. **Project lifecycle is host-privileged, not API-backed.** `project up`/`create` drive
   `incus` directly, so they can't ride the FDE gateway — provisioning needs admin SSH.
   The k8s-shaped fix is for the control plane (already root on the box) to own
   provisioning and the CLI to become a pure client. This is the largest gap before "a
   member-role FDE creates containers without host privileges."
2. **Host admin token rides along for back-compat** (a missing role ⇒ ADMIN). Once
   SSH-key + passkey identity is field-proven, scope it down to break-glass.
3. **Two remote-config models.** `ClientConfig` (SSH-forward via `host`/`user`) and
   `ServerConnectionConfig` (HTTP API via `server` + `token`) both read
   `~/.sail/config.yaml` with different keys; SSH-forwarding papers over this today, but
   a direct-API client (Mast, or a future direct-mode CLI) needs them reconciled. A
   passkey `sail login` session has no CLI consumer on a forwarding client yet.
4. **Sync identity is the box hostname.** Replicas key off the hostname rather than a
   stable per-box id; renames or collisions could confuse sync identity. A stable id is
   the robust fix — low risk for a known small fleet, worth doing before larger ones.
5. **Attribution gaps in synced files.** Per-actor attribution rides via `_actor` for
   specs and projects; shared *files* have no author column at all (a schema change for
   low value), and the change-log's internal author column stays null for projects/files.
6. **FDE removal propagates as `disabled`, not a tombstone.** Revoking an FDE on main
   locks them out everywhere (the gateway refuses a disabled role), but the row lingers
   on nodes as disabled rather than disappearing; true delete-propagation is a roster
   protocol change.
7. **Single platform per OS** — Mac arm64 and Linux amd64 only.

## Design invariants to preserve

- One binary, zero runtime dependencies, fully declarative (`sail.yaml` is the source of
  truth; the container is derived state that can be destroyed and recreated).
- The database is the replicated source of truth for specs, projects, and shared files;
  on-disk descriptors are a materialized view. Reads are catalog-first; writes go through
  the catalog so an edit can never diverge or be lost on the next sync.
- Sync is CAS-safe, idempotent, order-independent, and conflict-parking — no lost local
  work, ever. The `SyncEngine` is entity-agnostic; a new synced entity adds a replica,
  not engine logic.
- Per-developer identity (git identity, SSH keys) never rides the synced definition;
  it is placeholdered in the catalog and resolved locally, per box, at provision time.
- Every state-mutating command is idempotent and supports `--dry-run` and `--json`.
- No magic: every command maps to a small number of `incus` / `podman` / `systemd` /
  `ssh` calls the operator could run by hand.
- No tmux; infrastructure services use Podman `--restart=always` + linger, interactive
  dev happens over SSH remote editing.
- Single-box users need zero new commands; sync stays opt-in.
- Sail orchestrates; it never reimplements virtualization or container runtimes.
