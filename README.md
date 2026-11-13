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
nodes: sync refuses a peer on another sync protocol before any data moves and names the remedy.

Sail installs what it runs on and nothing else: Incus from Zabbly's stable repository, and
zfsutils-linux for a zfs pool. It holds the incus packages, so neither `apt upgrade` nor
unattended-upgrades restarts Incus, which would end every terminal and agent run in every
container. `sudo sail host incus upgrade` moves Incus when you choose: it lists the sessions and
agent runs the restart ends, then asks. The rest of the OS is yours to keep current; sail never
upgrades it. The needrestart policy sail installs (`/etc/needrestart/conf.d/50-sail.conf`) keeps
a library update from restarting Incus.

## Storage

`sudo sail init` puts containers on a `dir` pool on the host's filesystem, where a snapshot or a
copy is a full copy and a container's disk limit is advisory. For a ZFS pool, run `sudo sail
host init --storage zfs` with one source:

```bash
sudo sail host init --storage zfs --disk /dev/nvme1n1        # a whole unused disk
sudo sail host init --storage zfs --disk /dev/nvme0n1p5      # a spare partition
sudo sail host init --storage zfs --disk /dev/nvme0n1p5 --disk /dev/nvme1n1p5   # a mirror of two
sudo sail host init --storage zfs --pool-file-size 200GiB    # a file, to try ZFS without a spare disk
```

Incus creates and owns the pool. Sail erases nothing: it takes a disk or a partition only when it
carries nothing at all, no partition table, no filesystem, no swap, no RAID, LVM, LUKS, bcache,
ceph or ZFS signature, and nothing holds it open, exclusively or not. A partition is taken only
with the Linux filesystem data or ZFS partition type, lying inside its disk and overlapping no
other partition. Anything else is refused naming what it
holds. Where only a filesystem, swap or a bare partition table is in the way, the refusal names
the command that erases it (`sudo wipefs --all <device>`), for you to run if nothing on it is
wanted, and then run `host init` again; a member of a pool, an array, a volume group or a cache
is refused naming the tool that frees it instead (an exported pool: `zpool import`, or `zpool
labelclear -f` yourself). A whole disk that holds only what a destroyed zpool left on it (zpool's
own table: its data partition and the 8 MiB reserved partition 9) is refused naming the commands
that return it to blank, in order (`sudo zpool labelclear <data partition>`, then `sudo wipefs
--all <disk>`). With two `--disk`, one refusal names what is wrong with each device. A blank partition on the disk the system runs from is taken, as on a server
whose two disks both carry the system. Refused whatever it holds: a disk with partitions; a mounted
or swap device, or one with something built on it, or one a process has open (for a partition, its whole disk too); a boot, firmware,
reserved or extended partition, by its type; anything but a plain disk or a partition of one (an
array, a loop device, a ZFS volume, an LVM or LUKS mapping, a partition of any of them); and a
device of an imported zpool, or a partition beside one on the same disk (export or destroy the
pool yourself first). A disk with no partition table and no signature is blank to Sail, even if a
filesystem survives further in after its table was erased. A disk with a hardware identity (a WWN
or an NVMe id) is handed to Incus by its `/dev/disk/by-id` name; on disks with none, as on most
cloud VMs, do not change disks while `host init` runs: one replaced under the same name after
Sail's last check is judged by `zpool create` alone, which does not refuse an empty partition
table or filesystems inside MBR partitions. A pool on one partition or one disk
has no redundancy: if that device dies, every container on the pool is lost, however the system's
own disks are mirrored.

`--disk` given twice makes one pool that is a ZFS mirror of the two devices, as on a server whose
two disks carry the system in RAID1 and a spare partition each. Each device must pass every rule
one device passes; a refusal names which one and why, and nothing is made unless both pass. The
two must be on two physical disks: two partitions of one disk, two paths to one disk (one WWN or
serial) and two namespaces of one NVMe controller are refused, so two NVMe persistent disks of a
cloud VM that shows every disk as a namespace of one controller (as GCP does) cannot be mirrored;
use SCSI-attached disks there. They must be of one size, give or
take the 16 MiB `zpool create` allows without force (Sail never forces): make the spare
partitions the same size; within that, the output says how much of the larger goes unused. Incus
makes the mirror in one `zpool create ... mirror <a> <b>`. Every refusal, by Sail or by zpool's
own checks of both devices, leaves both untouched. A failure while zpool labels them (a device
gone, or held, between its checks and its writes) can leave the first with its start zeroed or a
new empty partition table, and no pool: both were blank, so nothing is lost, and a re-run says
what it finds there. A zpool already imported under the pool's name is refused before Incus is
asked, since zpool would label the devices before finding the name taken.
A mirror keeps every container through the loss of either device: `sudo sail host status` then
says the pool is `DEGRADED` and names the device that is gone, and workspace and template verbs
go on, saying once that the pool is degraded. It does not protect against losing both, against a
deleted file or a bad write by software (ZFS writes it to both), or against the box itself; it is no backup.
Sail replaces and resilvers nothing: put a new device in place of the missing one with `sudo
zpool replace <pool> <missing device> <new device>`, which ZFS resilvers. A pool that is neither
`ONLINE` nor `DEGRADED` (both devices gone, `FAULTED`, `SUSPENDED`, or not imported) refuses
workspace and template verbs, naming its state and `sudo zpool status <pool>`. Re-running the
command that made a mirror, in either order of its two `--disk` flags, is a no-op; naming one of
the two, or another pair, is refused by name. What a pool is on is what ZFS lists for its zpool
now (a whole-disk device as its disk), not the names Incus recorded when it made the pool, which
on disks with no hardware identity are kernel names that can move across a reboot: a re-run
compares the devices it names with those, `host init` and `host status` say those, and a pool
whose zpool is not imported is said as Incus recorded it, saying so. After your own `zpool
replace`, the pool is on its new device. The mirror needs an Incus with the `storage_zfs_vdev` API
extension (6.0.1 and later, as Sail installs it); Ubuntu's own Incus 6.0.0 is refused before
anything is made, in a dry run too, naming what moves it: add Zabbly's apt source, then `sudo sail
host incus upgrade`. A pool Incus did not finish making is never taken for one that exists, and
`host init` stops before the base profile, naming what Incus reports and how to clear it. If
incusd stops between its `zpool create` and marking the pool created, Incus 7.6 reports the pool
`Unavailable` once it is back: its record names no zpool, and `host init` names the commands that
remove the half-made pool (`sudo incus storage delete <pool>`, then `sudo zfs list -r <pool>` and,
if it lists only the pool itself, `sudo zpool destroy <pool>`); the next run names what the zpool
left on each disk and how to return it to blank. An `Unavailable` pool Incus made and cannot start
is refused too, pointing at `sudo incus storage show <pool>` and `sudo zpool status <zpool>` to mend
it. A record Incus left `Pending` or `Errored` is refused with `sudo incus storage delete <pool>`
(for `Pending` that leaves the zpool for you to destroy as above). A file is slower
than a disk and meant for trials. `host init` caps the ZFS cache at a tenth of memory, at most
16 GiB (`/etc/modprobe.d/sail-zfs.conf`), and says so; a cap you set yourself is kept, in your own
file or edited into Sail's, and a cap Sail wrote before yours is removed so yours is the one that
applies. On ZFS a
container's disk limit is enforced: Incus sets the root disk's size as the dataset's ZFS `quota`,
which its snapshots count against, and a snapshot copy is a clone that costs nothing at first.
On a box already on `dir`, the same command with `--pool <name>` adds the ZFS pool beside it:
existing containers and the base profile stay where they are, and `sudo sail host status` lists
every pool with its driver, source and free space, and a ZFS pool with its health as `zpool
status` gives it. A project's workspaces and its template go
on the ZFS pool (see [Workspaces](#workspaces-several-lines-of-work-on-one-box)).

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

Main's database is the org. Back it up on main, and keep the copy off the box:

```bash
sudo apt-get install -y sqlite3
sudo sqlite3 /var/lib/sail/sail.db ".backup /root/sail.db.copy"
```

A main set up again from that copy is the same main to every node. On the new main, run `sudo
sail init --as-main`, then put the copy in place:

```bash
sudo systemctl stop sail-api
sudo install -m 660 -g sail /root/sail.db.copy /var/lib/sail/sail.db
sudo rm -f /var/lib/sail/sail.db-wal /var/lib/sail/sail.db-shm
sudo sail migrate
sudo systemctl start sail-api
```

`sudo sail migrate` says "API token re-issued for this box: the database came from another box."
when the copy came from another box, and `sudo sail spec list --all-projects` then works on the new
main. Each node then re-pins main's new host key (`sudo ssh-keygen -R <main-ip>`, then `sudo sail
init --main <main-ip>`, which keeps the node's handle; compare the fingerprint it prints with `sudo
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on main), and its next `sudo sail sync` pushes
whatever it has not pushed yet. Work main took after the copy is lost with it and is not rebuilt
from the node: that sync drops what the node had pulled of it, says so once per type, and keeps each
row in its history (`sudo sail spec history <id>`). A main restored in place from an older copy is
treated the same way. A main set up empty is a new org: every node refuses to sync with it.

Mast, the desktop client, is a thin client of a box: specs, rooms and reviews over its API and
event stream, terminals and files over its pty host.

## Quick start

Stand up the main. One idempotent command provisions the box, installs the control plane and
declares it the source of truth:

```bash
sudo sail init --as-main --handle ada --name "Ada Lovelace" --email ada@example.com
```

`--handle` makes you main's operator: the FDE dispatch picks this box's specs for, added to the
roster as admin with `--name` and `--email`, or with the git identity the box already has. It is
asked for when left out; a re-run keeps the handle the box already has and refuses another.
`--name` and `--email` also become the box's git identity when it has none (they are asked for
when left out, and init stops before any step without them), so the projects it applies commit as
you. An identity the box already has is kept.

Authorize each engineer with the line their `sudo sail init --main` printed. It carries the
fingerprint of main's host key that their box pinned, and main refuses the line when that is not
its own key:

```bash
sudo sail fde add mady --role member --key 'ssh-ed25519 AAAA... sail-sync@madybox' \
  --main-host-key SHA256:...
```

Join a node:

```bash
sudo sail init --main <main-ip> --name "Mady M" --email mady@example.com
                                  # provision, install sail-api, pin main's SSH host key,
                                  # generate this box's sync key, and print the fde add line
sudo sail sync                    # pull specs, projects and shared files from main
sudo sail sync status --json      # stored health, last success, and any failure reason
```

A box set up by `sudo sail init` is root's: `sail-api` runs as root, whoever invoked `sudo`. Run
sail there with `sudo`; another account's plain `sail` is refused and names the `sudo` command to
run instead.

Hand a person their box in Mast with one string. On the box, as the account `sail-api` runs as
(root, through `sudo`, on a box `sudo sail init` set up), for the box's own FDE (its sync handle):

```bash
sudo sail fde pair ada --host 34.1.2.3 --email ada@example.com   # prints a connect code, once
sudo sail fde unpair ada                                         # revokes it
```

The code is everything Mast needs: where the box is and its host key, a login key made for this
pairing, and an API token for `ada`. It logs in as the account `sail-api` runs as, which sees
every project, container and secret on the box, so a box mints it for its own FDE and no other.
It is a secret that opens a shell on the box, so send it as you would a password. It lasts a year
unless `--ttl-days` or `--no-expiry` says otherwise, and its login and its token end together.
The pairing's key also becomes the workstation key the box's containers trust, from the moment
it is paired, with no second apply; a container it cannot reach is named with the apply that
fixes it. A box that already has a workstation key refuses unless `--as-workstation-key` says to
replace it, which takes the old key out of the containers too. Unpairing takes the key back out of
the login, the workstation key and every container.

A client, or any other engineer, gets a box of their own: set it up with
`sudo sail init --main <main>`, grant it its projects on main (`sudo sail fde grant <handle>
<project>`), and pair on that box. A node pairs its own FDE as main's roster has it, so the
roster options (`--name`, `--email`, `--role`) are main's.

A scope changes in place on main: `sudo sail fde update <handle> --scoped --grant <project>`
narrows an FDE that joined unscoped, keeping its keys, tokens and sessions, and `--unscoped`
lets it see every project again. On its next sync its box holds what the new scope admits and
nothing else: a project it no longer holds leaves with its container, its directory and any
unpushed work, and so does the name and email of every FDE outside its projects.

Each project the box applies opens with a conversation already waiting: `sudo sail project
apply` leaves the box's FDE a home room, `<project>--<handle>`, with the project's agent in it
(`agent.member` in the definition picks another installed agent, model or mode).

On a Mac or other thin client, `sail client <host>` points the local CLI at a box. Commands are
forwarded over SSH; no control plane runs locally.

## Projects

A project is one definition in the catalog: runtimes, services, repos and agent config. The
catalog is the only copy, synced to every box with the project; there is no file to keep beside
it. `sail project apply` makes the container match the definition from any starting state: an
absent container is provisioned, a stopped one started, a running one converged in place. Run
it again any time.

```bash
sail project create web --cpu 4 --memory 12GB --repo https://github.com/acme/web.git
sail project apply web           # make it real: create, start or converge, one verb
sail project edit web            # the definition in $EDITOR, each changed section saved
sail project config get web agent
sail project connect web         # print SSH config for your editor
```

`create` with no flags asks for each field; `--definition -` reads a whole definition from
stdin. A definition is JSON, one section per key, and only `name` is required:

```json
{
  "name": "web",
  "resources": {"cpu": 4, "memory": "12GB", "disk": "150GB"},
  "image": "ubuntu/24.04",
  "runtimes": {"jdk": 25, "node": "22"},
  "repos": [{"url": "https://github.com/acme/web.git", "path": "web"}],
  "services": {"postgres": {"image": "postgres:16", "ports": [5432]}},
  "processes": {"web": {"command": "npm run dev", "workdir": "web", "port": 3000}},
  "agent": {
    "type": "claude-code",
    "guardrails": {"max_duration": "4h", "max_idle": "20m", "action": "snapshot-and-stop"}
  },
  "pipeline": {"guardrails": {"max_duration": "45m", "max_idle": "20m", "action": "stop"}}
}
```

Each section is written on its own, over the version it was read from: a section someone else
changed meanwhile is refused, never overwritten unseen. A wrong value is refused naming the
field.

The definition holds no identity. Every container commits as the git identity of the box that
provisions it (`git config --global user.name` and `user.email`; on a box `sudo sail init` set
up, root's, the one `sudo sail init --name … --email …` gave it) and trusts only that box's
key. Repos clone over https with the box's forge token; an ssh-style URL is refused naming its
https form.

The container's user is always `dev` (uid 1000, `/home/dev`): every agent, shell and hook runs
as it. Apply records each provisioning step for the definition it ran with, so an edit made
after a failed or finished run is provisioned again from the first step, each step installing
only what is missing.

`sudo sail project demo` spins up a bundled zero-config demo (an Outline wiki) end to end.

### The portal: run the app and open it

A process declares the command that starts the project's app and the port it listens on.
`sudo sail project serve web` brings the services and processes up, each process a
`systemd --user` unit in the container, and says what listens where, exiting 0 only when every
port listens; a process that exits says its last line. `--stop` stops the processes,
`--json` prints the ports. Mast's **Portal** button does the same in one click, forwards every
port that listens to the Mac's own `localhost` over the connection it already holds, and opens
the browser: no `ssh -L`, nothing installed on the Mac, nothing listening once Mast quits.
Agents get the same through sail's `sail-portal` skill (`scripts/portal up`, `ports`, `log`,
`stop`), whatever harness they run in.

### Workspaces: several lines of work on one box

A project's container is its default workspace, `main`. On a box with a ZFS pool, a project can
run more, each its own container with its own tree, services and ports, cloned from the project's
template. On a 4-vCPU cloud VM the clone takes about half a second and a whole create, the clone,
the start and making it its own, about three seconds:

```bash
sudo sail project workspace create web fix-login   # builds the template first if it is missing or stale
sail project workspace list web                      # main, then every workspace: state, template, created, IPv4
sail project workspace shell web fix-login
sail project workspace stop web fix-login
sail project workspace start web fix-login           # says how long it took to be ready
sudo sail project workspace delete web fix-login     # asks once it finds it; --yes to skip
sudo sail project template build web                 # build the template now; a no-op when it is current
```

The template is the container `web--tmpl`, provisioned as `project apply` provisions `web`, kept
stopped, with one snapshot per version (`v1`, `v2`, ...). A workspace is a ZFS clone of the
current version. It gets:
- a machine-id of its own;
- `web` as its hostname, so the in-container `spec` and portal work as they do in `main`;
- SSH host keys of its own, made under that name;
- every repo fetched and on the branch named after the workspace (the remote's, when it has one),
  asking again for up to 10 s while the repo's host does not resolve, as a fresh clone's
  resolver can lag its address (`ready_steps.fetch_retries` counts how often);
- none of the template's own keys.

`create` says how long each phase took, for example `Created fix-login from v1 in 3.2 s (clone 0.5 s,
start 0.4 s, ready 2.3 s)`; `--json` breaks the readiness down step by step (`ready_steps`).

A workspace counts as whole only once its create finished. One whose create was interrupted is
listed as `incomplete`, is not started or entered, and is replaced by the next `create` of its
name. One whose ready mark was taken off by hand is not replaced: `create`, `start` and `shell`
name `workspace delete`. Two creates of one name make one workspace and refuse the other; two of
different names build a missing or stale template once and both clone it. `delete` asks, once it
finds the workspace, before it removes it and its uncommitted work.

An edited definition is built as the next version at the next `create`. Workspaces made from an
older version keep running on it. A stale template is converged on its disk, as `apply` converges
a container, so what the definition dropped stays in it.

On a box without ZFS, `list` shows `main` alone, and the verbs that make or change a workspace
name `sudo sail host init --storage zfs` to add a pool. `main` is never touched: `delete main`
names `project destroy`.

`sudo sail project destroy web` removes the template and every workspace with the container,
naming each in its prompt; `--dry-run` names the same. A project that leaves a box's scope takes
them with it the same way. `project list`, `project apply --all`, `snaps prune` and the sync round
never take a workspace or a template for a project, and no project verb acts on one: a project
named like a workspace's instance (`web-fix-login`) is refused everywhere, naming the workspace to
delete or another name to choose.

A copy of a workspace made by hand (`incus copy web-fix-login keep`) is not Sail's. No verb acts on
it, and it outlives its project, keeping the project's tree and your credentials; delete it
yourself.

Know what a clone carries. The template is provisioned like your own container, so every
workspace holds your GitHub token, your git identity and your workstation key. The workstation
key is part of what the template is built from: revoking it makes the template stale, so the next
`create` builds a new version without it. Older versions keep the old key, but nothing is cloned
from them again. A new pairing reaches your workspaces at once and the template when the next
`create` rebuilds it, as the pairing's key is in what the template is built from. Key lines added
by hand are not tracked.

Deleting an old template version by hand (`sudo incus snapshot delete web--tmpl v1`) leaves its
workspaces running. Its space is freed when the last of them goes.

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
loop runs: each stage of the definition's `pipeline` has a reviewer that checks the branch, a fix
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

### A stage's brief

The loop is sail's: a build, then review stages, then a fix when a stage fails. What the
agents are told comes from two prompts sail writes and no project changes: the *work prompt*,
read by the build and by the fix run (the spec, the room, the findings, commit and push, a
pull request, CI green), and the *judge prompt*, read by every review stage (the spec, what to
review, the verdict contract). How *this codebase* is built, tested and verified is the
repository's own `AGENTS.md` to say; sail writes no context file.

The one agent-facing text a project writes is a stage's **brief**: a paragraph saying what
that stage judges, in the definition's `pipeline` section (`sail project edit web --section
pipeline`). A stage with no brief judges
under sail's default (the spec first, then correctness, security and tests). A brief that
orders a skill makes a validation stage without new machinery:

```json
{
  "stages": [
    {"name": "review"},
    {
      "name": "validate",
      "brief": "Run the project's e2e skill against this branch and report every failure as a finding, with the command and its output as evidence. Pass only on a clean run.",
      "gate": "all_clear"
    }
  ]
}
```

### Project skills

A skill is an [Agent Skills](https://agentskills.io/specification) folder: a `SKILL.md` whose
front matter names it and says what it does and when to use it, plus any scripts, references
and assets. Sail installs a project's skills whole, for every agent the project installs, at
provisioning, at `project apply` and before every launch, and syncs them to every box with the
project. A folder that breaks the standard's rules is refused where it is added, naming the
field. Share one from the devbox:

```bash
sail project skills add ./e2e --project web        # .sail/skills/e2e/<every file>
sail project skills ls --project web               # every skill an agent sees: shared, sail's, made by hand
sail project skills show e2e --project web         # its SKILL.md
sail project skills rm e2e --project web           # the folder goes at the next launch or apply
```

Or share a folder that lives in the project's container, read there as its dev user:

```bash
sail project skills add --container /home/dev/.agents/skills/java --project web
```

Inside the container, `skill ls` and `skill show` beside `spec` read the same rows. Sharing or
removing a skill is a person's, from outside the container (the host CLI, or Mast): whatever runs
in the container can read its credential, so the socket refuses both to every credential there and
names both host commands. A
copy of a shared skill made by hand in an agent's folder, holding nothing but the skill's own
files, is taken over by the next install. Sail ships one skill, `spec-board`, so an interactive
session finds the `spec` CLI.

## Going deeper

- [ARCHITECTURE.md](ARCHITECTURE.md): the design and its contracts, each row naming the tests
  that prove it.
- [CONTRIBUTING.md](CONTRIBUTING.md): building, the quality gates, migrations, releases.
- [AGENTS.md](AGENTS.md): the working conventions for people and agents in this repository.
- Every command has `--help`; state-mutating commands take `--dry-run`, and all take `--json`.

## License

[MIT](LICENSE)
