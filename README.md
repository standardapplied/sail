# sail

A single native binary that provisions bare-metal servers and manages isolated dev environments for AI-assisted engineering. One binary, zero dependencies, fully declarative.

Built with Java 25 + picocli + GraalVM native-image. <1ms startup.

## Why

You have a bare-metal server. You work on multiple projects simultaneously, each needing its own JDK, Postgres, Meilisearch, Redpanda, and AI coding agents — fully isolated so a runaway agent in one project can't affect another.

`sail` provisions the server, creates project environments as Incus system containers, manages their lifecycle, and orchestrates AI agents inside them with spec-driven workflows, guardrails, and rollback safety. Each project gets a complete Ubuntu 24.04 userspace with its own filesystem, network stack, and rootless Podman runtime.

## Day 0: Server + Project Setup

```bash
# Install sail (single binary, no dependencies)
curl -fsSL https://raw.githubusercontent.com/singlr-ai/sing/main/install.sh | bash

# Initialize server (one-time, needs sudo)
sudo sail host init
```

`host init` installs Incus, creates a storage pool (dir by default, ZFS with `--storage zfs --disk /dev/sdX`), configures networking, and caches the base Ubuntu 24.04 image.

```bash
# Generate a new sail.yaml interactively
sail project init

# Create the environment
sail project create acme-health
```

Team projects are coordinated through the control-plane database and replicated to each
engineer's box with `sail sync` (see the db-sync model) — not pulled from a git repo.

`project create` provisions an Incus container with everything declared in `sail.yaml` — installs runtimes, starts Podman services, clones repos, configures git identity, generates agent context files, and sets up the harness.

```bash
# Print SSH config for your editor
sail project connect acme-health
```

Add the output to `~/.ssh/config`, then connect in Zed: `Cmd+Shift+P` → "Connect to SSH Host" → `acme-health`.

## Day 2: The Two Modes

Engineers work in two modes. `sail` supports both from the same project.

### Interactive Mode (Daytime)

Open Zed, connect via SSH remote dev, start the agent from Zed's Agent Panel. You're in the loop — brainstorming, exploring code, writing specs, reviewing output. `sail` is invisible here; the generated context files (CLAUDE.md, SECURITY.md, `.context/`) guide the agent.

```bash
sail project restart acme-health     # start container, show connection info
```

Developer processes (`java -jar`, `npm run dev`) run interactively in Zed terminal tabs. Infrastructure services (Postgres, Meilisearch, etc.) are managed by Podman with `--restart=always` and survive container reboots automatically.

### Autonomous Mode (Overnight)

Write specs during the day. Walk away. The agent works through them overnight.

```bash
sail spec dispatch acme-health   # pick next ready spec, launch agent
```

`dispatch` scans `specs/*/spec.yaml` from the container, finds the next pending spec (respecting dependencies and assignee), reads the detailed `spec.md`, and launches the agent with full context. Guardrails enforce time limits. Auto-snapshot provides rollback safety.

## Spec-Driven Workflow

Specs are the unit of work. Each spec lives in its own directory inside `specs/`, checked into a shared repo so the team can see and assign work.

### Structure

```
specs/
├── oauth-flow/
│   ├── spec.yaml             # Metadata: id, title, status, assignee, depends_on
│   ├── spec.md               # What to build and why (brainstormed with agent in Zed)
│   └── plan.md               # How to build it (optional, for complex specs)
├── payment-integration/
│   ├── spec.yaml
│   ├── spec.md
│   └── plan.md
└── fix-footer-typo/
    ├── spec.yaml
    └── spec.md               # Simple spec, no plan needed
```

### spec.yaml

```yaml
id: payment-integration
title: "Stripe payment webhook"
status: pending
assignee: bob
depends_on:
  - oauth-flow
repo: webapp
agent: codex
model: gpt-5.5
reasoning_effort: high
branch: feat/payment-integration
```

`repo`/`repos` routes work to the right repository in multi-repo projects. `agent` routes a spec
to a specific installed agent (`claude-code` or `codex`); if omitted, dispatch uses
`agent.type` from `sail.yaml`. `model` and `reasoning_effort` tune agents that support those
controls; unsupported combinations fail fast.

### Lifecycle

```
pending → in_progress → review → done → archive
```

**pending**: Ready for an agent to pick up. **in_progress**: Agent is working. **review**: Work complete, security review and code review hooks run automatically. **done**: All reviews pass. **archive**: Cleaned up (`sail task archive`).

### The Full Loop

```
Morning (Zed, interactive):
  Engineer + agent brainstorm → write specs/oauth-flow/spec.md
  Optionally plan → write specs/oauth-flow/plan.md
  Push specs to shared repo

Evening:
  sail spec dispatch acme-health

Overnight:
  Agent reads spec.md, works, commits, pushes branch
  Reviews run automatically at spec completion
  Guardrails enforce time limits

Next morning:
  sail agent status              # all projects at a glance
  sail agent report acme-health  # detailed: commits, spec progress, review results
  Review PRs, merge or address findings
```

### Team Coordination

Specs live in a shared private repo (e.g., `your-org/projects/acme-health/specs/`). Multiple engineers, each with their own container:

- Alice specs out OAuth, assigns to herself
- Bob specs out payments, depends on Alice's OAuth work
- `sail spec dispatch` respects `assignee` — each engineer's agent only picks up their specs (or unassigned ones)
- Dependencies prevent premature work — payments won't start until OAuth is done

No project board needed. The spec directory *is* the board. Git history is the audit trail.

### Fleet Migration

After upgrading `sail`, bring existing project containers up to the current spec layout and agent
instructions with one command:

```bash
sail project migrate --all --pull-specs
```

For a single project:

```bash
sail project migrate acme-health --pull-specs
```

The migration starts stopped projects, regenerates agent context files, converts legacy
`specs/index.yaml` entries into `specs/<id>/spec.yaml`, removes the legacy index after a safe
conversion, and reports spec Git sync state. Use `--json` for automation or `--keep-index` if you
want to retain the old index file during manual review.

## Context Generation

`sail agent run` (or `sail agent context regen`) generates a complete agent environment from `sail.yaml`. Context files are agent-agnostic — Claude Code gets `CLAUDE.md`, Codex gets `AGENTS.md`. Same content, different format.

| Generated | Purpose |
|-----------|---------|
| `CLAUDE.md` / `AGENTS.md` | Tech stack, conventions, runtimes, services, autonomous work protocol |
| `SECURITY.md` | Zero-trust principles, OWASP Top 10, input validation, secrets management |
| `.context/` repository | Persistent knowledge across sessions (system overview, patterns, failure log) |
| Methodology skills | `/spec` (write spec first), `/verify` (run tests and block on failures) |
| Post-task hooks | Auto-trigger security audit and code review at spec completion |

The autonomous work protocol in the context file tells the agent how to read specs, update status, and hand off — this works identically across Claude Code and Codex.

### `.context/` — Institutional Memory

```
.context/
  system/       # always loaded — project overview, key decisions
  patterns/     # discovered architectural patterns and conventions
  failures/     # what went wrong and how it was fixed
```

Agents read `system/README.md` at session start and update these files as they learn. Committed alongside code so knowledge persists across sessions and engineers.

## Methodology

```yaml
agent:
  methodology:
    approach: spec-driven    # spec-driven | tdd | free-form
    verify: "mvn clean test"
    lint: "mvn spotless:check"
```

- **`spec-driven`**: Agent writes a spec before coding. Generated as a `/spec` skill.
- **`tdd`**: Agent writes failing tests first, then implements until green.
- **`free-form`**: No methodology constraints.
- **`verify`** / **`lint`**: Commands injected as skills the agent runs after implementation.

## Guardrails

```yaml
agent:
  guardrails:
    max_duration: 4h
    action: snapshot-and-stop
```

When the time limit triggers, the agent is stopped and rolled back to the pre-launch snapshot. The watcher starts automatically with `sail spec dispatch` and `sail agent run --background`.

Actions: `snapshot-and-stop` (rollback), `stop` (keep changes), `notify` (webhook, agent continues).

## Agent Commands

```bash
# Dispatch
sail spec dispatch acme-health              # next ready spec, background launch
sail spec dispatch acme-health --spec auth  # specific spec by ID

# Run (context regen + launch)
sail agent run acme-health                   # interactive mode (Zed)
sail agent run acme-health --task "..."      # headless with explicit task
sail agent run acme-health --background      # background, picks next spec

# Monitor
sail agent status                      # all projects at a glance
sail agent status acme-health          # single project detail
sail agent log acme-health -f          # stream output live
sail agent report acme-health          # morning-after summary

# Control
sail agent stop acme-health            # SIGTERM → SIGKILL after 3s
sail agent watch acme-health           # enforce guardrails (auto-started by dispatch)

# Quality
sail agent audit acme-health           # cross-agent security audit
sail agent review acme-health          # cross-agent code review
sail agent sweep acme-health           # codebase cleanup pass
```

## Multi-Agent Support

`sail` is agent-agnostic. Configure the primary agent and optionally install others for cross-agent review:

```yaml
agent:
  type: claude-code
  install:
    - claude-code
    - codex
  security_audit:
    enabled: true
    # auditor: codex      # defaults to a different agent than primary
  code_review:
    enabled: true
```

Claude or Codex can execute a spec. Set `agent: codex` in `spec.yaml` to override the
project default for that unit of work. For Codex, `model` and `reasoning_effort` map to
`codex exec --model ... --config model_reasoning_effort=...`. The hooks fire at spec completion,
not session stop — so reviews always see complete, coherent work.

## Example `sail.yaml`

```yaml
name: acme-health
description: "Acme Health Platform"

resources:
  cpu: 4
  memory: 12GB
  disk: 150GB

image: ubuntu/24.04

runtimes:
  jdk: 25
  node: 22
  maven: "3.9.9"

git:
  name: "Acme Engineering"
  email: "eng@acme.com"
  auth: token

repos:
  - url: "https://github.com/acme/backend.git"
    path: "backend"
    branch: "main"
  - url: "https://github.com/acme/webapp.git"
    path: "webapp"

services:
  postgres:
    image: postgres:16
    ports: [5432]
    environment:
      POSTGRES_DB: acme
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: dev
    volumes:
      - pgdata:/var/lib/postgresql/data

  meilisearch:
    image: getmeili/meilisearch:latest
    ports: [7700]
    environment:
      MEILI_ENV: development

agent:
  type: claude-code
  auto_branch: true
  auto_snapshot: true
  specs_dir: specs
  methodology:
    approach: spec-driven
    verify: "mvn clean test"
    lint: "mvn spotless:check"
  guardrails:
    max_duration: 4h
    action: snapshot-and-stop

ssh:
  user: dev
  authorized_keys:
    - "ssh-ed25519 AAAA... you@laptop"
```

## Project Lifecycle

```bash
sail project create acme-health   # provision container from sail.yaml
sail project start acme-health               # start stopped container
sail project stop acme-health             # stop container (preserves state)
sail project restart acme-health           # start + show connection info
sail project containers                           # list all projects with status
sail project snapshot create acme-health             # create snapshot
sail project snapshot restore acme-health snap-01  # rollback to snapshot

# Modify running projects
sail project add service acme-health
sail project add repo acme-health
sudo sail project resources set acme-health --memory 16GB
sail project destroy acme-health  # delete container and state
```

## Building from Source

Requires JDK 25+ and Maven 3.9+.

```bash
mvn clean test                    # run tests (588 tests)
mvn clean package                 # build JAR

# Native image (requires GraalVM JDK 25)
JAVA_HOME=/usr/lib/jvm/graalvm-jdk-25 mvn clean package -Pnative -DskipTests
```

Every command supports `--help`. State-modifying commands support `--dry-run`. All commands support `--json` for machine-parseable output.

## License

[MIT](LICENSE)
