# Sail Architecture

> Status: living document. Captures the positioning and design decisions behind `sail`.
> Last substantive update: 2026-05-29.

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
- **Spec-driven task management** — agent-native specs (like Linear, not JIRA), with
  status lifecycle, assignees, and dependencies.
- **Cross-agent review pipelines** — if Claude writes the code, Codex reviews it.
- **Multi-FDE collaboration** — many Forward Deployed Engineers coordinate on shared
  specs while each runs agents on their own server.
- **Remote operation** — via the CLI (host or Mac thin client), Mast (the IntelliJ
  Platform IDE), and future mobile clients.

### Glossary

- **FDE** — Forward Deployed Engineer: the human operating coding agents at a customer.
  Each FDE has their own bare-metal server.
- **Mast** — IntelliJ Platform-based desktop IDE for FDEs who prefer a GUI. Connects to
  the Sail server for specs and review, and to remote workspaces for execution.
- **Host** — the bare-metal server running Incus, Podman, and the Sail control plane.
- **Client** — a machine (typically a Mac) that drives a host remotely.

## Topology

```
  FDE MacBook (client)                 Bare-metal server (host)
  ┌───────────────────┐                ┌──────────────────────────────────────┐
  │ sail (darwin-arm64)│   SSH          │ sail server  (control plane + node)   │
  │  ~/.sail/config.yaml├───────────────▶│  ├─ HTTP API  (127.0.0.1:7070)        │
  │  RuntimeMode.Client │                │  ├─ SQLite (Panama FFM) — specs,      │
  └───────────────────┘                │  │    events, reviews, sessions,      │
                                         │  │    tokens                          │
  Mast (desktop IDE) ───── HTTP API ────▶│  └─ EventBus → SSE stream             │
                                         │                                        │
                                         │  Incus containers (one per project)    │
                                         │   └─ rootless Podman services          │
                                         │   └─ coding agent + harness            │
                                         └──────────────────────────────────────┘

  Spec coordination across FDEs is centralized; compute is never scheduled across
  hosts — each FDE manages only their own containers.
```

## Control plane + execution engine

`sail server start` runs the control plane **on the same bare metal that hosts the
Incus containers** — server and node are one process, there is no separate node binary.

- **Specs live in SQLite, not git.** The database is the single source of truth.
  Agents interact with specs through the CLI / skills, never the filesystem.
- **Panama FFI to `libsqlite3`** — JDK 25's finalized Foreign Function & Memory API
  plus GraalVM Panama downcalls call `libsqlite3.so.0` directly. Zero new dependencies.
  `sqlite4j` was rejected (single-threaded, no WAL, in-memory VFS).
- **Specs are global entities**, filterable by project / assignee / status — like
  Linear, not nested under projects like JIRA.
- **Each FDE gets their own server.** Spec management is centralized across FDEs;
  multi-host exists for spec coordination only, not cross-host compute scheduling.

The store layer (`ai.singlr.sail.store`) is `SpecStore`, `EventStore`, `ReviewStore`,
`SessionStore`, `TokenStore`, all over `Sqlite` (the Panama wrapper), with
`SchemaManager` and versioned migrations.

## Module layout

| Module | Package roots | Responsibility |
|---|---|---|
| `sail-core` | `config`, `engine`, `gen`, `store` | Domain model + config records, path/shell primitives, file generation, the Panama-SQLite store. No CLI, no HTTP. |
| `sail-harness` | `engine` | In-container agent harness: agent sessions, guardrails, reporting, webhook notifier, GitHub push. Runs *inside* project containers. |
| `sail-infra` | `api`, `commands`, `engine` + `Main`/`Sail` | The CLI (picocli), the HTTP control-plane API + reactors, and host-side provisioning engines. Builds the native binary. |

Coverage discipline: `ai.singlr.sail.api.*` is held to **100% line/method** JaCoCo
(minus a documented exclude list); the rest of the bundle has lower floors.

## Runtime modes — host vs client

`Main` chooses a mode at startup via `RuntimeMode.detect()`:

- **Host mode** — `~/.sail/host.yaml` exists. Commands execute locally: lifecycle
  commands drive `incus`/`podman`; API commands hit the local server.
- **Client mode** — `~/.sail/config.yaml` exists (and no host config). Commands are
  forwarded to the host over SSH by `RemoteCommandRunner`:
  - **Local** (`--version`, `upgrade`, `init`) run on the client.
  - **Host-only** (`host …`) error with guidance to SSH in.
  - **Everything else** is forwarded over SSH; the host renders output, the client
    displays it.

This is why the loopback-only API is not a limitation for the Mac client: commands
run *on the host*, where the API is reachable at `127.0.0.1:7070`.

### The macOS thin client

The release pipeline builds `sail-darwin-arm64` (on `macos-latest`) alongside
`sail-linux-amd64`. `install.sh` detects the platform, verifies the binary's magic
bytes and SHA-256 checksum, and strips the Gatekeeper quarantine flag. An FDE runs:

```
curl -fsSL .../install.sh | bash      # downloads darwin-arm64
sail init my-server                   # writes ~/.sail/config.yaml { host: my-server }
sail project up acme                  # forwarded over SSH, runs on my-server
```

`my-server` may be an IP, hostname, or `~/.ssh/config` alias; SSH supplies user, key,
and proxy settings.

## Security model

The deployment is **single-trust**: one operator, one bare-metal server.

- **Auth** — bearer token, stored `0600` under `~/.sail/` (`0700`). No RBAC or
  multi-user identity yet (see *Known gaps*).
- **API transport — sail serves plain HTTP on `127.0.0.1`; it does not do TLS at all.**
  The loopback API is reached by the CLI via SSH tunnel / SSH command-forwarding; for
  network or browser access (Mast, passkeys), the operator fronts sail with a
  TLS-terminating reverse proxy (Caddy/Traefik/nginx) that forwards to the loopback
  HTTP port. Remote binding still requires explicit `--allow-remote`.
- **TLS and certificates are entirely out of scope.** sail neither terminates TLS nor
  issues/renews certificates — the reverse proxy owns all of it (TLS termination, the
  public hostname, and cert issuance/renewal including Let's Encrypt via the proxy's
  own ACME). This is the dominant self-hosted pattern and keeps sail's core a plain
  HTTP service. For passkeys, the **secure context and RP ID are the browser's view of
  the proxy's `https://` origin**; sail only needs its configured public origin/rpId to
  validate `clientDataJSON` — it never serves TLS itself.
- **Input hardening** — repo URLs validated, `--` guards on git invocations, webhook
  URLs SSRF-checked at parse time *and re-checked at send time* (DNS-rebinding defense),
  API field validation mirroring the domain records.
- **Untrusted by design** — spec markdown and agent output are untrusted prompt input;
  reviewer/audit/handoff flows must never treat them as authoritative instructions.
- **Releases** — checksum-verified, not yet signed/notarized.

See `SECURITY_AUDIT.md` for the full checklist and accepted risks.

## Known gaps / evolution

These are the deliberate edges between today's single-trust CLI tool and the
multi-FDE platform that must support Mast:

1. **Single-trust → multi-FDE identity + authz.** The largest gap before Mast. One
   shared bearer token does not model many FDEs coordinating on shared specs.
   *(Tracked as a design spec.)*
2. **Two remote-config models.** `ClientConfig` (SSH-forward via `host`) and
   `ServerConnectionConfig` (HTTP API via `server` + `token`) both read
   `~/.sail/config.yaml` with different keys. SSH-forwarding papers over this today;
   direct API access from a client (Mast, or a future direct-mode CLI) needs them
   reconciled and the transport decision made.
3. **No API rate limiting** — relevant once long-lived desktop / Chorus clients connect.
4. **Single platform per OS** — Mac arm64 only, Linux amd64 only.
5. **Release signing/notarization** — checksums protect against corruption, not a
   compromised release channel.

## Design invariants to preserve

- One binary, zero runtime dependencies, fully declarative (`sail.yaml` is the source
  of truth; the container is derived state).
- Every state-mutating command is idempotent and supports `--dry-run` and `--json`.
- No magic: every command maps to a small number of `incus` / `podman` / `systemd` /
  `ssh` calls the operator could run by hand.
- No tmux; infrastructure services use Podman `--restart=always` + linger, interactive
  dev happens over SSH remote editing.
- Sail orchestrates; it never reimplements virtualization or container runtimes.
