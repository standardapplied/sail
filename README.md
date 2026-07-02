# sail

Sail turns bare-metal servers into isolated, spec-driven dev environments where AI coding
agents do the work. Each project gets a hard-isolated container. The team's specs and
project definitions live in a synced database. Agents are dispatched against those specs
with rollback safety and cross-agent review.

Sail is not a coding agent. It is the layer around them: the environments they run in, the
specs they pick up, the reviews they pass, and the shared state a team works from. It does
for coding agents roughly what Kubernetes does for containers, orchestrating the work
rather than performing it.

Built with Java 25, picocli, and GraalVM native-image. One binary, no runtime
dependencies, sub-millisecond startup.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/standardapplied/sail/main/install.sh | bash
```

`sail upgrade` replaces the binary in place and converges the database. Linux (amd64) runs
a full host. macOS (arm64) runs as a thin client that drives a remote host over SSH.

## The model: one main, many nodes

An org runs one main box. It holds the team's specs and project definitions in a SQLite
control plane and is the source of truth. Every engineer also has their own box. A box
pointed at main is a node: it pulls specs, project definitions, and shared files from main,
and pushes its own work back.

There is no GitHub in this loop and no separate board. The database is the board, and
`sail sync` moves it over a locked-down SSH gateway using public-key auth, with three-way
conflict resolution so no one's work is overwritten. Compute is never scheduled across
boxes. The star coordinates state, not execution.

## Quick start

Stand up the main. One idempotent command provisions the box, installs the control plane,
and declares it the source of truth:

```bash
sudo sail init --as-main
```

Authorize each engineer with the public key their `sail init --main` printed:

```bash
sail fde add mady --role member --key "ssh-ed25519 AAAA... sail-sync@madybox"
```

Join a node:

```bash
sudo sail init --main <main-ip>   # provision, install sail-api, generate this box's sync
                                  # key, and print the fde add line to run on main
sail sync                         # pull specs, projects, and shared files from main
```

On a Mac or other thin client, run `sail client <host>` to point your local CLI at a box.
It forwards commands over SSH, and no control plane runs locally.

## Projects

A project is one `sail.yaml`: runtimes, services, repos, and agent config. The database is
the source of truth, and the on-disk descriptor is a materialized view. `sail project
create` turns the definition into an isolated Incus container with runtimes installed,
Podman services running under `--restart=always`, repos cloned, and agent context
generated.

```bash
sail project init        # author a sail.yaml
sail project create web  # provision the container
sail project edit web    # change the definition (saved to the catalog, synced to peers)
sail project connect web # print SSH config for your editor
```

The synced definition is identity-free. Per-engineer fields are placeholders
(`${GIT_NAME}`, `${GIT_EMAIL}`, `${SSH_PUBLIC_KEY}`) resolved from your own box at provision
time. A teammate's project commits as you and trusts only your key. No identity or keys
ride the sync onto another box. Resource limits sync both ways and resize the running
container in place.

```yaml
# sail.yaml (name, resources, and image are the only required fields)
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
  guardrails:  { max_duration: 4h, action: snapshot-and-stop }
ssh:
  authorized_keys: [ ${SSH_PUBLIC_KEY} ]            # per-developer, never synced
```

Run `sudo sail project demo` to spin up a bundled zero-config demo (an Outline wiki) end to
end.

## Specs and agents

Specs are the unit of work. They live in the database with a status, an assignee, and
dependencies, filterable across the whole team. You manage them with `sail spec`. Agents
inside a container use an in-container `spec` CLI over a bound socket, so there is one
source of truth and no sync glue.

```bash
sail spec create --project web --title "Stripe webhook" --assignee mady --depends-on oauth
sail spec board --project web    # who is on what, and what is ready
sail spec dispatch --project web # pick the next ready spec, launch the agent, watch it
```

`dispatch` honors dependencies and assignee, snapshots the container for rollback, creates
the work branch, and launches the agent with full generated context. Its log streams live.
Sail is agent-agnostic across claude-code and codex, so one agent can implement and another
can review: configure `agent.review_pipeline` in `sail.yaml` and when the coder stops, a
reviewer checks the branch, a fix agent addresses its findings, and the loop repeats up to
`max_iterations` before escalating to a human. `sail agent review <project>` shows every
attempt's iterations and findings; `sail agent log <project> --review` follows the
negotiation live. Guardrails combine a `max_duration` and an action, so a runaway agent is
stopped and rolled back to the pre-launch snapshot.

Findings the gate let ship don't die in the review store: `sail spec create --from-review
<spec-id>` drafts a follow-up spec from the latest review's open findings — one actionable
section per finding, priority derived from the highest severity, repos copied from the
original. The draft stays in `draft` until you promote it, and when it reaches `done` the
findings it was created from are marked resolved. `sail spec show` and the board flag done
specs that still carry open findings (`· N open findings`), so completion-with-residue is
distinguishable from clean completion.

Specs and projects edited on two boxes merge automatically when the changes touch different
fields. A true same-field clash parks for `sail conflicts` instead of overwriting either
side.

## Going deeper

- [ARCHITECTURE.md](ARCHITECTURE.md) covers the full design: the sync engine, the security
  model, the control plane, and the provisioning pipeline.
- Every command has `--help`. State-mutating commands support `--dry-run`, and all support
  `--json`.

## Build from source

Requires JDK 25 or newer and Maven 3.9 or newer.

```bash
mvn clean verify                  # build and run the full test suite with coverage gates
JAVA_HOME=/path/to/graalvm-jdk-25 mvn clean package -Pnative -DskipTests   # native binary
```

## License

[MIT](LICENSE)
