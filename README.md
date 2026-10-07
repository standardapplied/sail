# sail

Sail turns bare-metal servers into isolated, spec-driven dev environments where AI coding
agents do the work. Each project gets a hard-isolated container. The team's specs and project
definitions live in a synced database. Agents are dispatched against those specs with rollback
safety and cross-agent review.

Sail is not a coding agent. It is the layer around them: the environments they run in, the
specs they pick up, the reviews they pass, and the shared state a team works from. It does for
coding agents roughly what Kubernetes does for containers, orchestrating the work rather than
performing it.

One static binary (Java 25, GraalVM native-image), no runtime dependencies.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/standardapplied/sail/main/install.sh | bash
```

`sail upgrade` replaces `/usr/local/bin/sail` and converges the database; `sail upgrade
--binary <file>` installs a local build the same way. Linux (amd64) runs a full host. macOS
(arm64) runs as a thin client that drives a remote host over SSH. Upgrade main first, then
nodes: sync refuses a peer behind the fleet floor before any data moves and names the remedy.

## The model: one main, many nodes

An org runs one main box. It holds the team's specs and project definitions in a SQLite
control plane and is the source of truth. Every engineer also has their own box. A box pointed
at main is a node: it pulls specs, project definitions and shared files from main, and pushes
its own work back.

There is no GitHub in this loop and no separate board. The database is the board, and `sail
sync` moves it over a locked-down SSH gateway using public-key auth, with three-way conflict
resolution so no one's work is overwritten. Compute is never scheduled across boxes. The star
coordinates state, not execution.

Spec bodies and shared files are content-addressed blobs, deduplicated and streamed; `sail sync
status` reports health and `sail sync gc` compacts history. Three verbs remove work: **archive**
keeps everything, **delete** keeps history so `sail spec restore` brings it back, and **prune**
erases a spec everywhere, leaving one audit row. Retention is opt-in through a `retention` block
in main's `host.yaml`.

Mast, the desktop client, is a thin client of a box: specs, rooms and reviews over its API and
event stream, terminals and files over its pty host.

## Quick start

Stand up the main. One idempotent command provisions the box, installs the control plane and
declares it the source of truth:

```bash
sudo sail init --as-main
```

Authorize each engineer with the public key their `sail init --main` printed:

```bash
sail fde add mady --role member --key "ssh-ed25519 AAAA... sail-sync@madybox"
```

Join a node:

```bash
sudo sail init --main <main-ip>   # provision, install sail-api, generate this box's sync key,
                                  # and print the fde add line to run on main
sail sync                         # pull specs, projects and shared files from main
sail sync status --json           # stored health, last success, and any failure reason
```

On a Mac or other thin client, `sail client <host>` points the local CLI at a box. Commands are
forwarded over SSH; no control plane runs locally.

## Projects

A project is one definition: runtimes, services, repos and agent config. The database is the
source of truth and the on-disk `sail.yaml` is a copy. `sail project apply` makes the container
match the definition from any starting state: an absent container is provisioned, a stopped one
started, a running one converged in place. Run it again any time.

```bash
sail project init        # author a definition
sail project apply web   # make it real: create, start or converge, one verb
sail project edit web    # change the definition, saved to the catalog and synced
sail project connect web # print SSH config for your editor
```

The synced definition is identity-free. Per-engineer fields are placeholders (`${GIT_NAME}`,
`${GIT_EMAIL}`, `${SSH_PUBLIC_KEY}`) resolved from your own box at provision time, so a
teammate's project commits as you and trusts only your key.

```yaml
# sail.yaml (name, resources and image are the only required fields)
name: web
resources: { cpu: 4, memory: 12GB, disk: 150GB }
image: ubuntu/24.04
runtimes: { jdk: 25, node: 22 }
git: { name: ${GIT_NAME}, email: ${GIT_EMAIL} }     # per-developer, never synced
repos:
  - { url: "https://github.com/acme/web.git", path: web }
services:
  postgres: { image: postgres:16, ports: [5432] }
agent:
  type: claude-code
  methodology: { approach: spec-driven, verify: "mvn clean test" }
  guardrails:  { max_duration: 4h, max_idle: 20m, action: snapshot-and-stop }   # builds
  review_pipeline:
    guardrails: { max_duration: 45m, max_idle: 20m, action: stop }              # reviewers, fix agents
ssh:
  authorized_keys: [ ${SSH_PUBLIC_KEY} ]            # per-developer, never synced
```

`sudo sail project demo` spins up a bundled zero-config demo (an Outline wiki) end to end.

## Specs and agents

Specs are the unit of work: a status, an assignee and dependencies, filterable across the whole
team. `sail spec` manages them on a box; agents inside a container use an in-container `spec`
CLI over a bound socket, so there is one source of truth and no sync glue.

```bash
sail spec create --project web --title "Stripe webhook" --assignee mady --depends-on oauth
sail spec board --project web    # who is on what, and what is ready
sail spec dispatch --project web # pick the next ready spec, launch the agent, watch it
```

`dispatch` honors dependencies and assignee, snapshots the container for rollback, creates the
work branch and launches the agent with its generated context. When the build stops, the review
loop runs: each stage of `agent.review_pipeline` has a reviewer that checks the branch, a fix
agent addresses its findings, and the loop repeats up to `max_iterations` before escalating to
a person. Sail is agent-agnostic across claude-code and codex, so one agent can implement and
another review. Every run is held to its lane's guardrails: a wall-clock `max_duration`, a
`max_idle` stall window and an action; an agent past a limit is killed in its container and
the spec's room says why. A passing review parks the spec in `awaiting_merge`. Sail never talks
to the forge: merge the pull request there and close the loop with
`sail spec update <id> --status done`.

`sail agent review <project>` shows every attempt's iterations and findings; `sail agent logs
<project> --review` (or `--fix`) follows the latest reviewer's or fix agent's run live. `sail
spec create --from-review <spec-id>` drafts a follow-up spec from a review's open findings, and
marking that follow-up done resolves them.

Every spec has a room. Agents post progress there, read it before deciding, and ask with
`spec comment <id> --question --body <text>`, which pages the engineer on the board.

### Giving a stage its own skill

The loop is sail's: a build, then review stages, then a fix when a stage fails. How each of
those agents goes about its work is a skill, a folder with a `SKILL.md` (front matter, then
instructions) and optional scripts and reference files. Sail ships `sail-build`, `sail-review`
and `sail-fix`; a project can replace any of them:

```bash
sail project skills --project web                     # the skill each stage runs under
sail project skills show sail-review > SKILL.md       # start from sail's default
$EDITOR SKILL.md
sail project files add SKILL.md --project web --as .sail/skills/web-review/SKILL.md
```

Then name it in the definition (`sail project edit web`): `agent.build_skill`,
`agent.review_pipeline.fix_skill`, or `skill` on a review stage. Sail puts the skill's body in
the stage's prompt on every launch and installs its folder where the harness looks for skills.
What the loop parses or enforces follows the skill and is not replaceable: the reviewer still
answers with the verdict envelope, the build still pushes its branch and opens a pull request,
the fix agent still argues a finding in the room rather than skipping it.

## Going deeper

- [ARCHITECTURE.md](ARCHITECTURE.md): the design and its contracts, each row naming the tests
  that prove it.
- [CONTRIBUTING.md](CONTRIBUTING.md): building, the quality gates, migrations, releases.
- [AGENTS.md](AGENTS.md): the working conventions for people and agents in this repository.
- Every command has `--help`; state-mutating commands take `--dry-run`, and all take `--json`.

## License

[MIT](LICENSE)
