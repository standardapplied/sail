# Changelog

## 0.46.3

- **A box's runs carry the handle main knows it by, and a live run is never rewritten.** Every run a box reserves — dispatch, build, restart, ad-hoc (`sail run`, `agent sweep`, the API), the review pipeline and its fix lane, and room wakes — carries the box's handle as both `node` and `owner`. Runs used to be stamped with a blank node, a stale handle, or the spec's or room's owner, and main then denied them, sometimes mid-run, and the node deleted them.
  - Main's `welcome` names the handle it authenticated the session as. A node whose configured sync handle is blank or another fails its round before offering or adopting anything, naming both handles and the fix.
  - One box syncs as each FDE. Main records the first box that syncs as an FDE and refuses any other, naming both boxes; an admin runs `sail fde release-box <handle>` on main after retiring the old box. Main's own FDE syncs from no box but main.
  - Every round, before anything else, a node stamps every run it made that main has never acknowledged with its handle. A run synced from another box is never stamped as this box's, even on a box that was main and holds every box's runs. A run main took whose answer was lost is acknowledged instead, keeping the stamp main holds it under. A run of this box's that an older release left acting for no one is stamped too, so main takes its completion.
  - **A lost answer never reverts or loses a change.** Main names, for each row a node asks after, the latest version it took from that box, and the node reconciles against exactly that. A change main made after taking the node's offer — an edit, or a delete — is never undone by the node pushing its offer again, and a field each side changed alone merges instead of parking. Every synced type, not only runs.
  - `sail host config set sync-handle` and `sail join` refuse to change the handle while a run main holds under the old one is live here or has changes main has not taken, naming each; on main, while any run the box executed is live. A node first asks main which runs it took whose answer was lost, and weighs those as held; when main cannot be asked, the change is refused. No sync round runs from that ask until the new stamps are written. Once changed, every run main has not taken is stamped with the new handle. A box becoming main stamps its unstamped runs. Removing an FDE releases the box it synced from.
  - Main takes a run only acting for the pusher. A run acting for no one is denied.
  - Main's version, pulled, denied or merged with the box's own change, never rewrites or removes a run or review still live on the box that executes it. It stays as it is and settles once finished. Another box's run is always adopted.
  - A room wake whose spec or room moved off the box before launch is refused and reserves nothing.
  - The reconciler finishes every dead session this box runs within one pass: superseded build sessions, room and full room runs of specs under way, a dead newest build run of a spec in review, and a newest build run whose recorded stop reached nothing that finishes runs, which it finishes without replaying the stop.
  - The reconciler reads a run as gone only when its container says so: a stopped or destroyed container runs nothing, while one incus cannot report, or a running one that will not answer a command, reads as alive. An `incus exec` that failed (incusd restarting) used to have live runs finished and their reservations and credentials released. A run whose recorded stop reached nothing that finishes runs is finished with that stop's exit code, and only a stop naming the run, or naming none, speaks for it — never another run of the same spec. A run read as not running is read again once its container has answered, so one command lost while incusd restarts never reads a working agent as gone. A spec's dead run is finished with the exit code its recorded stop carried, so a failed agent's work is never rescued into review as a clean finish.
  - The sync handle is stripped of surrounding space where `host.yaml` is read, so a box stamps its runs and recognizes them by one value.
  - A second box syncing as main's own FDE is told that FDE syncs from main alone; `sail fde release-box` cannot move it.
  - The lock files that serialize dispatch claims and sync rounds are created readable and writable by their group, so a root CLI and the API server can each take one whoever made it.
  - **The fleet floor moves to 0.46.3.** An older node pushes runs acting for no one, which main now denies, so main refuses it and tells it to run `sail upgrade`.
- **Main decides every pushed revision by the rule the doors ask.** Each synced type has one write rule in sail-core, and main's commit asks it for every revision a node pushes, after its compare-and-set and before it writes. The same edit gets the same answer through HTTP, the host CLI, the socket, the terminal and a sync. A member with an edited box database, or a crafted client, used to have almost every push committed.
  - A spec is edited, deleted or restored only by its owner or an admin, and its assignee changes only by an admin or a claim of an unassigned spec for oneself. A spec never moves to another room. Main used to commit anyone's edit, including taking someone else's spec, and the prune that followed then erased that FDE's spec, room, messages, runs and reviews on every box.
  - Restoring a deleted spec is its owner's or an admin's before any assignee change is decided as a claim, and a restore keeps the creator its tombstone recorded, whatever the push names.
  - A spec's id is reserved for its own room: a pushed spec is never born over a room, live or deleted, unless that moves no ownership (a node's own room reaching main first) or an admin pushes it. It would have handed that room, and the conversation in it, to the spec's owner. A room's restore keeps the creator its tombstone recorded, as a spec's does, and a node adopts the creator main holds, so both boxes agree on the room's owner.
  - A spec born in a room main has not received yet waits on the node for that room and lands the round after it, where it used to be denied and deleted there.
  - A room's later revisions, and a review and its verdict, are their owner's or an admin's. A review never moves to another spec, and a stage never moves to another review.
  - A run is its executing box's: its `node` is the pusher, it acts for the pusher or no one, a deleted run is never brought back, and every `principal` it carries names the run itself (`<family>/<marker><run id>`). A run naming another FDE or run could post as anyone.
  - A pushed revision names as its author (`_actor`) only the pusher, its box's machinery (`sail`) or a principal of a run the pusher owns, and a create names only the pusher as its creator. Admins are bound by this too. A principal of a run main does not hold yet is refused for that round and decided once the run lands, so an agent's spec lands the round after its run.
  - A post is authored by the pusher, a principal of any run it owns (in any conversation), or `sail` where a run it owns is in the conversation, and only where the pusher may post. An agent's post in another spec's room, where its FDE may post, is accepted at the socket and on main alike. A node holds an agent's post until main holds its run, in whatever conversation it runs.
  - A denied revision is settled as before: the node adopts main's version and keeps its own in history. Adopting main's review never deletes the finding rows the node holds, and the posts of a run main denied leave the node with it.
  - After an admin reassigns a spec away from a box, that box's status changes, reviews and pipeline posts on it are denied, and it adopts main's version. Its run is still its own to finish or stop, and a stop cancels the spec only when the stopper may change it. A review the box is still running keeps its row and findings until it finishes; a finished review main never held then leaves with the denial. A review run acts for the box's own FDE, not the spec's owner, so main takes it and what it writes.
  - A restart fails only the reviews this box executed, where it used to fail every running review, another box's included.
  - Local prune and main's decision on a node's prune ask one erase rule, with one set of texts: main's refusals now read like the CLI's (`Spec 'x' is assigned to 'bob', not you.`).
- **A node's writes speak for its FDE.** On a node, a credential naming an FDE other than the box's own acts with at most `viewer`: another FDE's token, passkey session, gateway key and terminal, admins included, read but write nothing there. Their writes would reach main as the box FDE's, on the box's session, and be denied. The box's FDE, its runs and its FDE-less credentials write as before.
- **A room's settings are its owner's.** A room's roster, wake, title and assignee are changed only by its owner or an admin. Setting a wake, or engaging or dismissing an agent, through a spec born in someone else's room is refused with the room owner's text, before anything is probed or launched; owning a spec born in a room still gives a voice there. Deleting a room refuses with room wording (`Room 'x' belongs to 'bob', not you.`, and `cannot change rooms` for a read-only credential), and so does managing a spec-less room's agents (still `not_your_spec`). Claiming an unassigned spec and setting its wake in one update is accepted.
  - Every door asks the rules it used to skip: drafting a follow-up acts on the source review under the review rule, and a read-only credential on the socket can no longer create a spec (`read_only_credential`, where a room session used to get a plain 403), refused before anything about the spec is checked.
- **New refusal code `forbidden_not_author` (403).** A write that names an author or creator its actor may not write as is refused with it.

## 0.46.2

- **Personal rooms are retired, and every one is erased on upgrade.** Rooms are created on purpose, from Mast: `GET /v1/rooms` no longer mints a room for its reader in every project, and rooms no longer render `personal_of`.
  - Sync every node, then upgrade main first. Its upgrade erases every personal room with its messages and runs, and each node adopts those erasures on its next sync. A standalone box erases its own.
  - Main decides on its own copy, as every erasure does: a run or spec a node started in a personal room and has not synced yet goes with main's erasure. A node's upgrade names each one.
  - Then upgrade every node. A node's upgrade removes the personal rooms main never held, which only that box ever had, and they never reach main.
  - **The fleet floor moves to 0.46.2.** An older node would mint a personal room and push it to a main that has already erased them all, so main refuses it and tells it to run `sail upgrade`.
  - A personal room deleted before the upgrade is erased too, with the messages and runs its deletion kept.
  - A personal room with a run still under way, or with a spec born in it, is left as an ordinary room, and the upgrade names it. The upgrade prints how many rooms it erased.
  - A personal room is recognized by its exact id, derived from its creator and project. A room whose id merely starts with `fde-` is untouched.
- **A spec left without an assignee is unassigned, and any member may claim it.** Creating a spec with no assignee used to assign it to whoever created it, which on the in-container socket was the run's principal (`claude/…`), leaving it undispatchable.
  - This holds through HTTP, the CLI and the socket (a run's credential or the box credential), and its identity room is unassigned too.
  - Its creator, the FDE who created it (a run records the FDE it acts for), may edit it while it is unassigned. Any member claims it with `sail spec update <id> --assignee <you>`; an agent claims it for the FDE it acts for. Dispatch refuses it until then, naming that claim.
  - Its room wakes on its creator's box, and a room-role run woken there may answer in it. Main accepts its creator's posts in its room through sync. Its creator may read the logs of, and stop, a run working it.
  - Once it is claimed, its room is the new owner's at every door: HTTP, the socket, the terminal and main's check of synced posts all decide who owns a conversation by one rule.
  - Owning a spec gives a voice in its conversation, so a spec born in a room is claimed, or created with an assignee, only by one who may already post in that room, and only for themselves; anyone else asks an admin to assign it. A claim never opens someone else's room.
  - A post about a spec born in a room whose row has not synced yet lands in that room.
  - A run's own FDE may still read the logs of, and stop, its run after the spec is reassigned.
  - A spec's creator now travels with it: main records a node-born spec's creator (the pushing FDE, when an older node names none) and keeps it, whatever a later push says, and every other box adopts it, the pushing node included. A spec main holds with no creator, such as one pushed before this release, gains one only from a push by that creator naming themselves.
  - An assignee is an FDE handle. The API refuses one shaped like a run's principal (`<family>/<id>`), and accepts a handle this box's roster does not know yet. The `--assignee` help of `sail spec create`/`update` and of the in-container `spec` CLI, and the spec skill, say so: the agent type goes in `--agent`.
- **The host CLI acts as the box's FDE.** The API token the host CLI uses names no FDE of its own: whenever it is used, it acts as the FDE the box's sync handle names at that moment, so a `sail spec create` or `update` on the box records that FDE and acts with its role. Changing the sync handle, or main's roster, takes effect on the next request; there is nothing to bind or migrate. A box with no sync handle has no FDE to be.
  - A machine token (any other token that names no FDE) acts as no FDE, with the role of the box's FDE capped by its minted role, so on a node it never acts beyond the FDE whose box it lives on. On a box with no sync handle it acts with its minted role. Such a token, the host token among them, used to act as admin on a node whatever the roster said.
  - On a node that has not pulled main's roster yet, or whose FDE main has disabled, a token acting for the box's FDE is answered `409`, naming `sudo sail sync`. A node that names no FDE of its own refuses such tokens and its CLI alike, naming `sudo sail host config set sync-handle`; its host token used to act as admin there.
- **A credential acts with its FDE's roster role.** One rule decides the role of every credential that names an FDE, whichever door it came in by: an API token, a passkey session, the box credential, the host CLI, a sync session, the SSH gateway and the terminal. Main's operator, or a standalone box's, is admin on it; every other FDE acts with the role main's roster gives it, capped by the credential's own role, so an API token minted as admin for an FDE since demoted acts with the demoted role.
  - A disabled FDE's credential is refused at every door; its API token and passkey session used to keep working on the HTTP API, and its session on the terminal and the sync server.
  - A node disables every FDE main's roster no longer lists, so an FDE removed on main is locked out on every box. A node used to keep it active.
  - Main never syncs against another box, even when asked with `sail sync --main` or `POST /v1/sync`: a round adopts the other side's versions and roster, which would overwrite the fleet's.
  - Dispatch, build and engage refuse to launch for a box whose FDE is disabled, rather than launching a run its credential cannot use.
  - A run's credential on the in-container socket acts with the role main's roster gives the FDE the run acts for, capped by its lane (member for an agent run, viewer for a room run), and is refused once that FDE is disabled. It used to act with its lane's role whatever the roster said.
  - Main decides whether a synced post is an admin's by the session's role, not by a second roster lookup.
- **A viewer node's CLI can no longer dispatch or stop.** `sail spec dispatch` and `sail agent stop` decide as the box's FDE with the role main's roster gives it, where they used to decide as a hard-coded admin. A stop needs write access. A preview (`--dry-run`) still describes what it would do.
- **Every box records the same author for a revision.** Main names the author it recorded when it accepts a node's push, and on a tombstone or an erasure it hands a node, including one a denial answers with. A node used to record `main` for these, and its own push without an author as `main` where main recorded the pusher. The new fields are optional on the wire.

- **A push main refuses on authority settles, and the node keeps syncing.** Main answers each such offer `denied`, with its current version. The node adopts that version, keeps its own in history, and carries on. It used to fail the round, and offer the same revision again every round.
  - Main denies a read-only session's offers, a message posted as someone the pusher may not post as, a reply to a message main does not hold, and a run whose provenance is not the pusher's. A denied offer never fails the offers beside it. A viewer's push used to fail whole, a forged author failed the push with every other offer in it, and a foreign run was answered as a race.
  - A message in a room main has never held is refused for that round and lands in the next, after its room. An agent's or the review pipeline's post waits on the node, with the replies under it, until main holds the run (and its spec) that decides it, so it is never denied for a run that simply had not arrived. A spec born in a room counts its runs as that room's, where main used to refuse their posts there.
  - A node-born entity main denies leaves the node: a denied message leaves its room with the replies this box posted under it. Every revision stays in the change log. A run from before the box's sync handle, with no node stamp, is such an entity.
  - A run still under way on the box is never rewritten or removed by a denial: its row, credential and room guard stay, and it settles once it has finished.
  - Adopting main's version counts as a pull, so it announces the board and resizes a project's container like any other pull.
  - A read-only session is asked for no content, so its push reaches main's decision instead of failing at the upload. Every offer is budgeted for main's answer, so a push's results fit the frame it fit.
  - Each denial is printed as main answers it, such as `spec auth: main denied this change — <reason>. Yours is in its history: sail spec history auth.`, by `sail sync` and a node's running server, even when the round later fails. `sail sync --json` and `GET /v1/sync` list them (`type`, `id`, `reason`). `sail sync --json` is now the same object as `POST /v1/sync`, which adds `message`.
  - On the wire a denial is a refusal marked `denied`, so a 0.46 node reads a refusal and fails that type's round naming the reason. For a read-only push and a forged author that is as before; a run main may not take from it, which a 0.46 main answered as stale, now fails its run type until the node upgrades. The sync floor does not change.
- **Every write names who is acting.** One `Actor` (handle, role, lane) is the identity of every write, bound by the entry point that makes it, and the change log reads it for every revision, tombstone and erasure. This changes no authority rule; it is where the next release enforces them.
  - Lanes: `CLI` (the box's operator), `API` (an HTTP token), `AGENT` and `ROOM` (a run's principal on the socket), `SYNC` (an FDE pushing to main), `MAIN` (a node adopting main's revisions) and `SYSTEM` (this box's machinery, recorded as `sail`).
  - The author in `sail spec history` and in `updated_by` is always the one who made that revision. A status-only or content-only change, a delete, a restore and a project move used to keep the previous editor's name; a synced row with no author used to read `sync`, and retention's erasures `sail-retention` (they now say `sail`, with origin `retention`).
  - A write with no actor bound is refused and leaves nothing behind.
  - A run, review or shared file keeps its author on every box. These have no author column, so a push used to record the pushing FDE on main and every box that pulled it recorded `main`.
  - On a node, the CLI writes as the box's FDE with the role main's roster gives it, and conflict resolution no longer runs as a hard-coded admin. A node whose roster has not synced yet refuses the write before touching anything and asks for `sail sync`; a preview (`run`, `agent sweep`, `agent stop --dry-run`) needs no identity. The CLI now prints a refusal's fix with its message.
  - Resolving a conflict keeps main's author on main's version. `--theirs`, or a merge identical to it, records the author main recorded; keeping mine or a different merge records the resolver.
  - A spec or room created through a machine token (one with no FDE) records no author and is left unassigned, so any member may claim it. It used to be assigned to the token's name.
  - Resolving the findings a follow-up spec addressed is journaled in one transaction, like every other review mutation.
- **A merge applies only to the conflict it was made from.** A merged record is a full record. A merge started before a round re-recorded the conflict with main's news used to revert that news: every field main had moved since read as a local edit back to its old value, and won main's compare-and-set.
  - `sail conflicts show <id> --template` prints the record to merge. Its `_conflict` key names the version of the conflict it was made from. `--merge` opens the same template in `$EDITOR`.
  - A merge without `_conflict` is refused (`400`). One made from an earlier version is refused (`409`) with nothing written. A round that brings no news keeps a template valid.
  - `--merge` refuses a conflict this box has written over before the editor opens.
  - A refused `--merge` keeps the edited file and prints its path, as reference for the redo.
  - `--merge-file` takes a template from `show --template`, so an agent or a script can merge without a terminal.
  - API clients get the same template from `GET /v1/conflicts/<id>?template=true`, on the web API and the in-container socket. A path suffix would collide with a file conflict's `project/path` id.
  - A merged record that is not valid YAML, or repeats a key, is refused as a bad request.
  - A clash on a field with several lines, such as a body, no longer breaks the template: main's value is commented line by line in its header, with any character YAML cannot carry, such as a terminal colour code, written as `\uXXXX`.
  - A template for a conflict that is not open is `404` on both APIs, as is a resolve of one; a stale merge is told to start again, not to resolve.
  - `--mine` and `--theirs` are unchanged: the side they choose and the merge base come from the same recorded conflict.
- **`$EDITOR` runs as git runs it.** `sail conflicts resolve --merge` and `sail project edit` run `$EDITOR` as a shell command with the file as its argument, so `EDITOR="code --wait"` works and an editor path with spaces is quoted, as for git. An unset or blank `EDITOR` means `vi` in both, and a failed editor names its exit status.

## 0.46.1

- **`sail upgrade --binary <file>` installs a local build**, such as an unreleased build or one for a box without internet access. It is the same install as a release download.
  - The file must be an executable for this platform.
  - It is staged beside the installed sail, and that staged copy answers `-V`, so the bytes checked are the bytes installed.
  - A build older than the installed sail is refused, as is combining `--binary` with `--check` or `--target`. Installing the bytes already installed changes nothing.
  - The installed sail knows `--binary` from this release on. To move a box on an older release to a local build, run the build's own upgrade: `sudo ./sail upgrade --binary ./sail`.
- **`sail upgrade` upgrades `/usr/local/bin/sail`, starting from that binary's version.**
  - It installs where install.sh puts sail, and compares the release with that binary's `-V`, not with the sail that is running.
  - An installed sail that cannot report its version is replaced, with a warning.
  - A `--target` older than the installed sail is refused, because migrations never run backwards.
  - With nothing installed there, `sail upgrade` and `--check` say to run install.sh.
  - Under a `SAIL_DATA_DIR` override it refuses, because an upgrade replaces this box's binary and restarts its services.
- **Host state names the installed binary.**
  - The `sail` user's forced commands and the `sail-api` and `sail-pty-host` units run `/usr/local/bin/sail`, whichever binary writes them.
  - `sail host service install` needs sail installed there.
  - Start, stop, status, logs and uninstall never need the binary, so a box whose binary is gone can still be cleaned up.
- **`sail migrate` limits what it changes to what the binary that runs it may change.**
  - Run by the installed sail, it converges the database and the host.
  - Under a `SAIL_DATA_DIR` override it is a rehearsal. It migrates and imports into that copy and touches no keys, units or services.
  - Any other binary is refused before it opens the database. The refusal names `sudo /usr/local/bin/sail upgrade --binary <it>` and the rehearsal.
  - The migrate that an upgrade starts never auto-upgrades, and neither does a rehearsal.
- **Legacy shared files sync without a mode conflict.**
  - Some files have no mode in their history; the content migration left them that way. Such a file keeps the mode its row records when the copy on disk lacks only bits the umask removed.
  - A copy someone made executable is imported as a new revision. So is a chmod back to a mode the history already records.
- `--dry-run` on `sail host service install` and `uninstall` no longer writes or removes unit files.

## 0.46.0

- **Archive keeps, delete restores, prune erases everywhere.**
  - `sail spec prune <id...>` erases specs with everything that belongs to them: the identity room (a shared room outlives the spec), the room's messages, the spec's runs and reviews, its events, every history entry, and the content only they referenced.
  - `--status archived,cancelled --older-than 90d [-p project]` is the admin's policy form.
  - A spec is pruned from archived, cancelled or deleted, and never while a run of it is unfinished.
  - A prune always reports first. The report comes from rehearsing the erasure in a transaction that is then rolled back, so it is what the real run does on this box's copy. Only `--apply` erases.
  - An erasure is terminal: a pruned spec id or project name is never used again. Creating one, or provisioning a project under a pruned name, is refused before anything is written.
  - The same method serves `POST /v1/specs:prune`, Mast's **Prune…** on an archived spec, and `sail project destroy --purge`, which now erases the whole project instead of tombstoning one row. It prints what it will erase before it confirms.
  - Authority: an owner (the assignee, or the creator when unassigned) or an admin. A policy or project prune is admin-only. Agents and read-only roles are refused.
- **Erasure is a kind of change-log entry.**
  - `change_log.kind` is `revision`, `tombstone` or `erasure`, backfilled from `deleted`. Every page entry carries it.
  - Main is the only author of erasures. A node's prune is an `erase` offer, and main decides it against its own copy; one round later every type on every box has converged. What main never saw is discarded on the node, never published. A policy prune runs on main only.
  - The journal refuses any write that would belong to an erased entity, on every box and every path.
  - A node applying an erasure removes only what main never acknowledged; what main held follows with its own erasure row.
  - Every box applies an erasure the moment a page, a need answer or a stale-push refresh brings it. It never reaches the conflict engine, so the erasure wins over an unsynced local edit and a stale push cannot resurrect the entity.
  - One erasure row per entity names who pruned and when. Erasure rows are never compacted, and a restore of a pruned spec is refused with that reason.
  - A room other specs were born into outlives the spec that minted it until the last of them goes.
  - The links between entities are declared once, in `Erasure`, not as foreign keys. Deleting a spec still keeps its runs, reviews and room so a restore brings them back, and sync never depends on the order entities arrive in.
  - The upgrade erases, on main only, what earlier releases orphaned (runs and reviews whose spec, and messages whose room, left no trace). It works one entity per transaction and resumes if interrupted.
- **History is bounded, content follows.**
  - Every box keeps each entity's newest 20 revisions, its synced base, and every tombstone and erasure. This is a compiled constant.
  - A node compacts what each round touched. Main compacts daily. `sail sync gc` compacts and then collects under the exclusive content lease, refusing inside a transaction.
  - `sail sync status` reports bytes freed.
  - A deleted spec can now be restored from any retained revision, including its tombstone, and brings back the identity room it minted.
  - `archived_at` and `cancelled_at` record when a spec entered those statuses.
- **Retention is opt-in.** A `retention` block in main's `host.yaml` (`prune_archived_after`, `messages`, `runs_after_finished`) makes main's daily sweeper prune by that policy, in bounded transactions, never taking an open agent question or a message a younger reply needs. Without the block nothing is erased that a person did not ask for, and nodes never evaluate retention.
- A deletion the old server writes while `sail upgrade` migrates is still recorded as a tombstone.
- **The fleet floor moves to 0.46.0.** A 0.45 box would read an erasure as a revision with an empty snapshot, so it is refused and told to run `sail upgrade`. Upgrade main first, then nodes.

## 0.45.0

- **Content syncs by hash, in chunks, never as base64.** Spec bodies and plans and shared files
  live once in a content-addressed blob store (SHA-256, content-defined chunks of 64 KiB–1 MiB,
  every chunk verified before it is written); rows and history carry hashes, so an edit to a
  title no longer re-journals the body and history grows by a hash per revision. The sync wire
  is a byte stream — a JSON line announces, raw bytes follow only a `chunk` — and content is
  deduplicated and resumable in both directions: a 1 KiB edit moves one chunk, an interrupted
  transfer resumes at the chunk it lost, and a node pulls a page's content one blob at a time so
  a page of many large files costs one manifest of memory. Shared files keep their permission
  bits end to end (a symlink's target, a local `chmod` the sync never recorded, an HTTP update of
  an existing file) and stream through ingest, sync, materialization and download; downloads
  send `Content-Length` and an `ETag` (the content hash) and answer `If-None-Match` with `304`.
  One cap replaces the 5 MiB limit: `limits.file_max` in `host.yaml`, 1 GiB by default, at most
  8 GiB, enforced from the declared length before a byte is read and by main on every upload.
  `sail sync status` reports bytes fetched and sent; `sail sync gc` frees content no live row,
  retained history or open conflict references. Upgrading migrates existing content one entity
  per transaction — resumable if interrupted, safe when two processes run it — and the fleet
  floor moves to 0.45.0: a 0.44 box cannot read a hash-only snapshot, and is told so.
- **Blobs hardening: one chunk loop, streams that outlive a stopwatch, a lease that means what it
  says.** The chunk and manifest runs over the sync wire have one definition, `ContentReceiver`,
  that both main's upload and the node's pull drive, with one vocabulary of failures (`protocol`,
  `unreachable`, `store`); main's replies to a bad upload are pinned byte for byte by a test, and a
  chunk the store cannot write is now reported as `store`, not `protocol`. Streaming a file out of
  a container is bounded by silence, not by the clock: `sail project files add` of a file that
  keeps delivering is never killed at two minutes, and one that stalls fails naming the command,
  the idle period and the bytes received. `host.yaml` is read once per command or request rather
  than once per file, so a corrupt file fails a bulk share or an upgrade's import before any file
  is opened, and every "too large" refusal is the same sentence, naming the cap in bytes and where
  to raise it (`limits.file_max` in `host.yaml`). A blob ingest inside a read transaction no
  longer silently skips the shared retention lease: only a write transaction, which holds the lock
  that makes skipping safe, goes without one. Retention is taken before the connection lock
  everywhere, so a read transaction runs its ingests under a lease taken before it opened, and an
  ingest or a collection that would take retention from inside a transaction is refused with a
  clear error rather than left to deadlock with a collector waiting on the connection.
- **The sync protocol-3 fallback is gone.** 0.44.0 let a node upgraded ahead of its main keep
  syncing over the whole-table protocol-3 wire for one release; that release is over. A node that
  meets a main still on 0.43 or older now fails the round saying main is on a sync protocol it
  cannot speak and to upgrade main — recognised by main answering `hello` with a message that
  names no `op`; noise on the channel is reported as noise, never as an old main. A `refuse` no
  longer carries the `error`/`error_kind` keys a protocol-3 node read, so a 0.43 node refused by
  this main sees the remedy inside an "Unrecognized sync response" line rather than on its own.
  The fleet floor stays `0.44.0`: this release is wire-compatible with every 0.44 box, so nothing
  already syncing is refused.
- **A conflict is decided on what the box holds now, and addressed by type and id.** `sail
  conflicts resolve` wrote the snapshot recorded at detection over whatever the box had written
  since; it now refuses (`409`) when the row has moved, ignoring latest-wins fields, until `sail
  sync` re-records the conflict. A spec and its room share an id, and the old lookup reported
  both as "No open conflict": `--type` on the CLI and `?type=` over the API say which, and an id
  parked under several types is refused naming them (`400`).

## 0.44.3

- **A parked conflict keeps its entity in the round.** A round examines what main changed since
  the checkpoint and what this box journaled; an entity parked over an unjournaled field was
  neither, so no round looked at it again and a conflict that had stopped being one could never
  close. Every open conflict is now re-reconciled each round: it merges or adopts and closes, or
  is re-recorded with fresh snapshots — and the round reports it instead of "Already in sync".

## 0.44.2

- **A run's heartbeat never parks a conflict.** `last_activity_at` is stamped without a revision;
  when main also held a stamp the node never recorded as its base, both sides had moved one field
  and the round parked it. Stores declare latest-wins fields (instants that only move forward):
  when both sides moved one, the later wins. A real conflict beside it still surfaces. Adopting
  an entity's settled state closes whatever conflict is still open on it.

## 0.44.1

- **A stream that ends inside a sync message is a lost channel**, reported as `unreachable`, not
  as a malformed message with a parser dump.
- `sail sync status` counts pending conflicts (`pending_conflicts` in `--json`); sync notices
  about upgrade order are warnings, not errors; a box without `libsqlite3` is told which package
  provides it, and every database open failure carries its cause.
- CI runs sync end to end on the native binaries (`NativeFleetIT`): the latest release upgrading
  to the build under test across real sshd and the gateway's forced command.

## 0.44.0

- **Sync ships the change log since a checkpoint, in bounded pages.** Sync protocol 4 replaces
  the whole-table-per-round wire: a round now costs O(what changed), a node can be seeded from
  any history size, and no entity table can grow past what one message may carry. A session opens
  with `hello`, is `welcome`d once (floors compare as versions; a node ahead of main is told the
  order, main first), reads main's high-water per type with `heads`, pulls only the types that
  moved as 16 MiB-bounded pages of the change log since a per-type checkpoint, asks `need` for
  main's rows of what it changed locally, and pushes in batches. Every adoption is one atomic
  store operation, no transaction spans the wire, and the checkpoint advances only after a page
  and only to what the node has seen, so a round that dies mid-page re-pulls it with nothing
  re-adopted. Main keeps `change_heads` (one row per
  entity naming its latest change) so every read the protocol makes is an index range, never a
  scan of history; `sync_state` becomes per peer and per type, carrying the old checkpoint into
  every type so no node re-seeds. Every box now has a stable sync id (`sync.box_id` in
  `host.yaml`; `sail join` and `sail host sync --as-main` mint one, `sail migrate` persists the
  hostname for a box that already has a role); it names the node in main's log, while who the
  node is stays the authenticated SSH principal. `sail sync` prints a line per type that moved or failed, `--json` carries a
  `types` list, and one type's failure no longer skips the others or the post-sync
  materialization. A run's process bookkeeping (`pid`, `watcher_pid`, `pid_ticks`, `log_path`,
  `transcript_path`) stays on the box that executes it. **Upgrade main first:** a 0.44 node still
  syncs with a 0.43 main through a one-release fallback and says so on every round; a 0.43 node
  meeting a 0.44 main is told to upgrade. The fallback is deleted in the next release.

## 0.42.0

- **`sail upgrade` never kills a session.** Every pty master now lives in systemd's file
  descriptor store from the moment a session is created, so a restart — or a crash — of the pty
  host hands the session to the next host intact: same `instanceId`, same boot id (kept in
  `~/.sail/sessions/host.boot`), same keyboard holder, continuous replay from the ring, and the
  child's exit status recorded by a new `sail _pty-child` shim so `exited(N)` still reaches the
  pane afterwards. Mast sees a brief reconnect, not "host restarted". A `systemctl stop` ends every
  session loudly with `pty host stopped`; closing the master is the kill switch, so no `incus exec`
  client outlives its session. The unit gains `NotifyAccess=main`, `FileDescriptorStoreMax`,
  `KillMode=process` and `TimeoutStopSec=15`; a session's sidecar (`<name>.meta`) and exit file
  (`<name>.exit`) sit beside its ring. **The first upgrade to this release still ends sessions** —
  the host being replaced never pushed anything — and says so, naming the live sessions; from the
  next upgrade on, sessions survive. `sail _pty-selftest` now proves the store binding in the
  native binary.

## 0.41.0

- **A reconnect from the same FDE keeps the keyboard — and its screen.** A laptop that slept left
  a ghost attachment on the box holding the write token; the returning pane attached as a silent
  observer for up to two hours. Now (no wire version bump — every frame already existed):
  - **Attach answers with the geometry, the replay, then the writer.** The host sends
    `Resized(cols, rows)` — the pty's live size — before `ReplayBegin`, so the replay is parsed in
    the geometry that produced it, and `WriterChanged(current)` right after `ReplayEnd`, so an
    observer knows it is one. A resync after a flow-control pause is framed the same way, since the
    overflow that paused the subscriber discarded whatever answer was still pending.
  - **Same-FDE reclaim.** `Attach(write)` takes the token when its holder is the same FDE on another
    connection; the ghost hears `WriterChanged` and its next `Input` is refused. A different FDE
    still waits (arbitration unchanged) and is told who holds it.
  - **Revoked credentials are severed.** The host sweep re-resolves every admitted connection's
    token and closes those the resolver now refuses.
  - **sshd notices a vanished peer within a minute.** A managed
    `/etc/ssh/sshd_config.d/10-sail.conf` (`ClientAliveInterval 15`, `ClientAliveCountMax 3`) is
    written on the host by `sail migrate` (so every `sail upgrade` converges it) and installed in
    every project container as part of sail's machinery; migrate now converges every running
    Sail-provisioned container's machinery on upgrade (never a foreign Incus instance).
  - **`sail session attach` narrates the token holder** and a replay that starts mid-sequence
    instead of dropping the frames.

## 0.40.0

- **The pty host ends a session loudly instead of wedging, and never leaks its ring.** A hardening
  pass over the per-container session host (no wire change):
  - **A pty or journal failure ends the session.** A read error, or a journal append that throws
    (a full disk), used to leave the gather thread joining a still-live child that nobody was
    draining — the session wedged live forever, unreapable, until an explicit kill. It now closes
    the pty, kills the child outright and reaps it first (a child that shrugs off SIGHUP and
    SIGTERM does not outlive its session), then every subscriber hears
    `SessionEnded(reason=io-error)`.
  - **Ring files are deleted and never leak.** A session's `~/.sail/sessions/<name>.ring` is removed
    when the session is killed, yielded to a dispatch, swept, or re-created — and when its create fails to spawn, so a
    bad working directory cannot litter rings that no quota counts — and every orphan ring is swept
    at host start (sessions do not survive a restart — there is no rehydration). New rings are
    created owner-only (0600).
  - **Input never pins a connection.** The write-token holder's keystrokes drain through a dedicated
    per-session writer thread over a queue bounded in frames and bytes (1 MiB queued plus in-flight,
    reserved before the payload is copied), so a child that has stopped reading (Ctrl-S, a stopped
    job) backs up to an `Err("input backlog")` instead of blocking the writer's connection or
    growing the host heap — and one stuck writer can no longer freeze the accept lane or every
    other connection.
  - **Unchecked failures answer `Err`, not a silent close.** An invalid session name, project, cwd,
    or terminal size is validated up front and refused with `Err`; any other runtime exception (a
    truncated frame, say) is a logged last resort that still answers `Err` on the open socket and
    then closes it cleanly, so Mast reads a real refusal rather than a transport fault it retries
    forever.
  - **Session names are validated as path segments.** A name like `../foo` is refused before it can
    escape the sessions directory or delete another session's ring.
  - **Backlog is bounded by bytes as well as frames.** A stalled subscriber's queue pauses at 1 MiB
    of live output (not only 4096 frames), and a resync replays a bounded 256 KiB tail rather than
    the whole 4 MB ring on the link that just proved too slow. Writer and geometry notifications
    coalesce to the latest of each, so a client that stops reading and hammers `TakeWrite` or
    `Resize` cannot grow anyone's queue past the caps.
  - **`sail session attach` narrates a refused keystroke.** The host's `Err` for a rejected input
    (no write token, or a full input backlog) is rendered inline as `[sail: …]` instead of being
    dropped, so a paste that outran the session is visibly incomplete rather than silently short.
  - **Resource caps.** Sessions per FDE (32) and subscribers per session (16) are each refused with
    an `Err` naming the cap, and each admission is atomic with its registration, so concurrent
    creates or attaches cannot all take the last slot. Recreating another owner's corpse (an admin
    reusing a name) counts as a new session against the recreator's cap; only replacing your own
    corpse takes no extra slot. A socket over the host's connection cap
    (256) is closed before a byte of it is read: a peer that opens many and never speaks holds no
    handler, descriptor, or frame past the cap.
  - **The host logs one line per attach, refusal, and exception** (principal, session, reason) to its
    journald unit; wire `Err` strings no longer name another FDE's ownership.

## 0.39.2

- **An attach replays everything the journal holds.** The pty host used to replay at most 256 KB of a
  session's 4 MB journal on attach and on a flow-control resync, because the tail crossed as one
  frame and the wire refuses frames over 1 MiB. The tail now crosses as a run of `Output` frames of at
  most 256 KiB inside the same `ReplayBegin`/`ReplayEnd` bracket, so a client that reconnects (a Mast
  relaunch) sees the whole history the host has. No wire change.

- **Pty sessions tell programs what terminal they are in.** The child of every host-owned session
  now inherits `COLORTERM=truecolor`, `TERM_PROGRAM=mast` and `TERM_PROGRAM_VERSION=<sail
  version>` beside `TERM=xterm-256color` (which stays: containers ship no other terminfo), so
  agent TUIs detect truecolor and identify the host without probing.

## 0.39.1

- **Fix: `sail session ls --json` and `sail dispatch --json` died in the native binary** with a
  GraalVM unsupported-feature error (record components unavailable for reflection) — the records
  `CliJson` prints were never registered for reflection, and only the JVM tests exercised them.
  They are registered now, and `_pty-selftest` (the release smoke test) stringifies one of each
  on every platform so a future `--json` record cannot ship unregistered.

- **Breaking (pty wire): SAILPTY3.** The session host answers an admitted `Hello` with `Welcome`
  carrying its boot id — a fresh id per run of the host process, the same on every connection
  until the host restarts — so a terminal client that remembers the id a session was last seen
  under can tell "the host restarted and lost it" from "it ended". The handshake magic moves to
  `SAILPTY3`; a client speaking `SAILPTY2` is refused by name (Mast renders its skew card until
  one side is upgraded). `sail session ls --json` now includes `host_boot_id`. The pty host
  restarts on `sail upgrade`, as before.
- **Pty sessions carry an incarnation id.** Every create mints an `instance_id` for that life of
  the (reusable) session name; it rides `SessionInfo` on the wire (SAILPTY3), `sail session ls
  --json`, and the data of every `pty_session_started/attached/ended` event. A client that only
  ever saw two corpses of one name can now tell which life each belonged to — the fact Mast's
  ended cards settle their reason on, instead of the name's newest event.

- **Breaking (CLI + API):** the one-shot invite lane is gone — one verb per primitive. `sail spec
  invite` no longer exists and `POST /v1/rooms/{id}/invite` answers 404 with a pointer to the two
  verbs that remain: add a member (`sail spec engage`, `POST /v1/rooms/{id}/members`) to converse,
  dispatch a spec to delegate. `Lane` has no `invite`/`invite-full` values; a historical invite run
  row lists as plain history with an unknown lane but keeps the contract it was minted under — a
  plain invite still running across the upgrade stays viewer-tier, neither role's stop enters the
  review pipeline, and stop and the reaper still address the row — and old `invite-<run>`
  snapshots keep their provenance label. CLI copy now says member: `spec engage` adds a member, `spec disengage`
  removes one.

- Personal rooms: every FDE gets one room per project, minted lazily on their first rooms read
  (`GET /v1/rooms`, filtered or not) — id `fde-<handle>-<project>-<fingerprint>`, where the
  fingerprint of the exact handle/project pair keeps the id unique when the readable slug is not
  (case and punctuation fold, a hyphenated handle shifts the boundary, a long pair truncates) —
  titled by the handle, assigned to the FDE, the project's default agent seated as its first
  member. Rooms render `personal_of` (the handle) on a personal room so clients pin it without
  re-deriving the id. Every field derives from the
  FDE and project rows, so two boxes minting the same room converge as a no-op on sync; a deleted
  personal room stays deleted until the FDE recreates it.

- Wake defaults derive from the roster instead of a stored value: a room whose `wake` is unset
  runs `on` with one member or none and `mention` with two or more, so a multi-member room answers
  only when addressed. An explicit `wake` a human set is never overridden — including `off` on a
  room with a seated member, which previously answered regardless. Rooms now render
  `effective_wake` beside the stored `wake`. No data migration: existing explicit values keep
  their meaning; existing nulls take the derived default.

- The pty event lane is measured, and its live half now exists. A `pty_session_*` row the pty
  host cannot write no longer vanishes silently: each drop leaves one structured line in the
  host's journal (`sail-pty-host.service`) and bumps a drop meter file beside the pty socket that
  the new `sail session ls --json` surfaces (`event_drops`: count, last type, cause, timestamp).
  The fail-open contract is pinned on both sides of the seam — `PtySession` now swallows whatever
  a `PtyEvents` implementation throws, so a session can never die or stall because eventing
  failed. And the lane's structural gap is closed: the pty host writes its event rows straight
  into the events table from its own process, so `/v1/events/stream` — the live lane mast treats
  as its accelerator — never carried them at all; the server now bridges new pty rows onto its
  event bus every 2 seconds, and the audit persister skips pty facts (persisted at source) so a
  row is never written twice.

- `sail agent attach` resumes a completed run's conversation inside a host-owned session
  (`resume-<run id>`, pinned to the run's room) instead of a raw `incus exec` tty: `Ctrl-]`
  detaches and the conversation lives on, attaching again joins the live session instead of
  forking a second agent, the run's room lists it like any room session, and a `spec create` from
  the resumed conversation is born in the run's room. Host-owned conversations respect the
  reservation gate: when a dispatch reserves the repos a resumed conversation works in — by exactly
  the dispatch gate's rules, so a read-only room wake displaces nothing — the reservation ends the
  session, attached or detached, with the reason in its stream and on its `pty_session_ended` room
  event, and the dispatch proceeds. New host-inbound pty wire verb `Yield(session, reason)`.

- `agent_session_started` now carries `run_role`, so clients can light presence the moment a run
  launches instead of waiting for its first tool call — the seconds between "message sent" and
  "agent working" shrink to the wake debounce plus launch.

- An agent can be engaged in a spec's room: it joins the conversation and answers every human
  message until dismissed. `sail spec engage <id> --agent <a>` (API `POST /v1/specs/{id}/engage`)
  records the engagement on the spec row as one atomic synced value — agent, mode, model,
  engaged-at as a single JSON column, so field-level sync merging can never stitch two boxes'
  engagements together — and `sail spec disengage` clears it; both transitions publish room-visible
  events (`spec_engaged`, `spec_disengaged`). **Full access is the default mode**: conversations
  produce artifacts (diagrams, drafts, files), so an engaged agent works in the workspace, drafts
  spec bodies, and creates sibling draft specs, guarded by the same one-writer-per-repo
  reservation a build takes per turn. An engage-time rollback snapshot is opt-in
  (`--snapshot` / the dialog checkbox) and off by default — on the `dir` backend a snapshot is a
  slow full filesystem copy, and the choice to skip it belongs to the human.
  `--read-only` is the explicit narrow choice, enforced by the harness and offered only where
  enforcement exists (claude-code today); full mode works on every agent, so codex is a
  first-class conversationalist. An engagement did not take effect until its snapshot succeeded —
  a failed payment publishes `spec_engage_failed` and engages nobody.
- Engaged rooms converse at conversation speed. The wake reactor answers every human message in an
  engaged room regardless of wake mode or dispatch history — a fresh chat room needs no build-run
  ancestry — with a 5-second debounce and **no post-finish cooldown** (the 30s/10min rules remain
  exactly as shipped for non-engaged specs). Turns resume the engagement's own conversation: the
  session selector is now role- and agent-filtered, so a build's session can never reopen under a
  chat turn's tool cut. A read-only chat turn runs alongside its own spec's live build (the gate's
  same-spec rule now applies within working lanes and within chat lanes, never across); a full
  turn defers on the build through its repo claim and fires on the build's stop, which — like a
  message landing in a turn's tail — re-evaluates the room for owed turns (newest human message vs
  newest chat-turn start; derived, never bookkept). A periodic engagement sweep backstops the
  event edges. Chat turns skip the read-only worktree fingerprint in full mode and never snapshot.
- Engaged turn prompts drop "when you have contributed, stop": a turn ends, the engagement
  continues, and the agent is told it will be resumed for the next message — never to say goodbye.
  The wake prompt also stops promising read-only git the allowlist deliberately denies.
- The one-shot read-only invite is retired, superseded by engagement (the API refuses it naming
  the replacement); `sail spec invite` is now the task-shaped lane it always really was — one
  full-access turn, snapshot first. Slack stops narrating conversation plumbing: a chat or invite
  turn's clean exit ("Agent stopped (exit 0)") no longer posts, while failures and build stops
  keep their narration.

- A room message now wakes the agent when no run is live. A new `room-wake` reactor on
  `spec_message_posted` (local and sync-arrived alike) runs on the dispatch-owning box — the one
  whose handle is the spec's assignee, so the fleet has exactly one waker per spec — and launches
  a real run through the same reservation machinery as dispatch: run role `room`, principal
  `<agent>/room-<runId>`, run credential, watcher, and guardrail ceiling. The gate learns the
  chat lane explicitly: a `room` run reserves no repos and conflicts only with runs of its own
  spec, so a wake and a dispatch on one spec serialize while a chat never blocks another spec's
  build. Timing is time-based only — a 30s debounce batches messages into one wake, a 10-minute
  post-finish cooldown kills the thank-you refire and covers the review loop's inter-iteration
  gaps, and any live run suppresses the wake outright (the relay owns delivery then). Wake
  policy is the per-spec synced `wake` field (`on` | `mention` | `off`, default `on` once
  dispatched; `spec update --wake`, shown by `spec show`); only human authors ever wake — agent
  and `sail` posts are structurally excluded, so no storm loops. The wake resumes the spec's
  most recent recorded conversation when one exists (`claude --print --resume` /
  `codex exec resume`, session ids validated before touching an argv) and otherwise primes a
  fresh session with the spec body and room tail; either way the prompt's rendered messages seed
  the delivery ledger. The chat is read-only by contract and structurally excluded from review:
  stop signals now carry `run_role` (env, session file, watcher, reconciler), the pipeline and
  the lifecycle reactor ignore `room` stops even on a spec parked in `review`, the stop gate
  skips the git protocol for the chat while keeping the room last-look, and a wake turn that
  somehow moved a repo's HEAD surfaces as a loud `guardrail_triggered` event with the changed
  files — never a review.

- The room lane's read-only contract is enforced by the harness, not promised by the prompt. A
  `room` run launches Claude Code without `--dangerously-skip-permissions`: the tool set is cut
  to `Bash,Read,Grep,Glob` (no Write, no Edit) and the only auto-approved commands are the
  `spec` CLI — the lane's one write, posting the answer — plus `cd` and read-only git; print
  mode denies everything else, so a prompt-injected instruction to edit the worktree fails at
  the harness. Codex wakes decline loudly instead of launching unenforced: its only sandbox
  (bubblewrap) needs user namespaces, which incus containers block, so no codex mode both runs
  commands and honors a read-only boundary. On the socket, a room credential now resolves to a
  read-and-converse principal (`viewer` role, `room` lane): every spec mutation — status,
  metadata, content, restore, delete, create, other specs' rooms — returns 403 at the API
  boundary, and the one allowed write is posting to its own spec's room. The commit guard's
  baseline moved host-side into the run store (out of the guarded agent's reach, consumed on
  first read) and now records a worktree digest alongside each HEAD, so an uncommitted edit is
  as loud as a commit. Wake session resume is node-local: a session id recorded by another box
  never becomes a `--resume` argv, and the fresh-prompt fallback keeps the spec body.

- Every run now knows its agent session. A new `sail-session-report` SessionStart hook (both
  CLIs; Codex's payload verified to carry the same `session_id`/`transcript_path` fields) posts
  the conversation's identity to the run row over the run-credential lane
  (`POST /v1/run/session`), last write wins — a resume, clear, or compact restart re-reports the
  new conversation, and the fix lane reports without `SAIL_SPEC_ID`. The three fields
  (`session_id`, `session_source`, `transcript_path`) replicate with the run (old-shape
  snapshots derive nulls) and surface on runs listings and `latest_run`. `sail agent attach` now
  has honest semantics per run state: a completed run resumes its recorded conversation exactly
  (`claude --resume <id>` / `codex resume <id>`, never an interactive picker; loud fresh
  fallback when no session was recorded, `--dry-run`/`--json` show the exact argv), and a live
  run is refused with the real lanes named — reply in its spec room to steer it, or stop it
  first; live observe/attach arrives with the PTY session host.

- A `still_open` ruling's evidence now travels with the finding instead of being discarded. The
  carried row stores the ruling's evidence (`carry_evidence`, newest ruling wins; the
  `carried_from` chain keeps the older ones), the next fix task renders it as a reproduction
  claim the agent must answer — with code or a dispute argument, never a bare "already fixed" —
  and the re-review prompt shows each carried finding alongside its own prior scenario. The
  review prompt now asks `still_open` verdicts to describe the exact residual scenario.
- Fix-lane room posts are attributed honestly. Rejoining a review run stamps the invocation's
  own identity on the run row (`<agent>/fix-<reviewId>` for the fix lane,
  `<reviewer>/review-<reviewId>` for a reviewer), journaled so it replicates — the room's audit
  trail names the lane that wrote each post instead of crediting the fix agent's work to the
  reviewer's principal.

- Room messages now reach the live run. A new `sail-room-relay` PostToolUse hook delivers replies
  posted mid-run into the agent's context after its next tool call, on both Claude Code and Codex
  (both honor `additionalContext` injection). Each run keeps a `run_delivered_messages` ledger —
  exact message identities, node-local, never synced — seeded with what the dispatch or fix
  prompt already rendered, so a message that syncs in from another box late is still delivered
  whatever its id; delivery is run-credential-scoped through the local API's new
  `/v1/run/messages` inbox (with `has_more` when a batch is capped) and exact-id acknowledgement,
  so the fix lane gets delivery without `SAIL_SPEC_ID`. The stop gate takes a last look:
  undelivered messages block the stop once (their bodies are the reason, acknowledged before the
  block — a failed acknowledgement fails open and retries) under a marker separate from the
  git-protocol nudge, so each concern blocks at most once. The fix task now renders the room
  conversation, the dispatch prompt teaches `spec comments` as the read verb, and the spec-room
  composer states the delivery contract while an agent is working.

- Review findings now have identity, verdicts, and a dispute lane. The re-review receives the
  previous review's open findings and must rule on every one (`fixed`/`still_open`/`disputed`,
  evidence required to resolve) inside a verdict envelope — the only reviewer output shape the
  parser accepts; a bare findings array errors the stage. Unruled findings carry forward as the
  same finding (a `carried_from` chain), keep failing the gate, and escalate by name once they
  survive `max_finding_age` fix iterations (default 2). The fix agent disputes a wrong finding by
  arguing it in the spec room instead of coding around it; the reviewer rules, disputed findings
  skip the gate but surface in the room verdict for the human. "N open findings" after a pass now
  means exactly N unresolved sub-gate findings.

## 0.17.3

- The SQLite busy timeout is now set before any other pragma. Opening a database while another
  process held a write lock could fail outright with "database is locked" — the CLI, the
  in-container `spec` helper, the sync server, and the reconciler all open through that path.
- One rate limiter now covers every TCP context. The passkey ceremony endpoints (`/v1/auth`),
  the login and enroll pages, and SSE connection establishment sat outside the limiter and were
  unthrottled; callers reaching the server before authentication are throttled by address (IPv6
  grouped by /64), and authenticated callers stay throttled by credential. SSE is charged once at
  connection establishment, never per event, and `/v1/health` stays unmetered.
- Documentation corrections: the upgrade floor is 0.15.0 (README and CHANGELOG said 0.14.0, which
  would strand an operator whose fleet sync then refused them) and the schema floor is v118
  (CONTRIBUTING said v125). Releases 0.15.0 through 0.17.2 now have changelog entries.
- Retired code removed: the `global.yaml` merge and file-era spec scaffolding from the withdrawn
  GitHub project-pull flow, plus internal helpers no longer called by anything shipped.

## 0.17.2

- Spec listings now expose `last_activity_at` so clients can order rooms by recent activity.
- Automatic setup triggers now share one best-effort reconciliation policy.

## 0.17.1

- `sail up` now reconciles the container's Sail-managed surface on every start.

## 0.17.0

- In-container interactive sessions now receive ambient box credentials tied to the FDE identity.

## 0.16.0

- Every run now acts as an attributable agent principal with a run-scoped credential.
- The server resolves agent credentials to principals and records those identities on events,
  specs, change history, and run listings.
- Agent principals are member-tier and cannot use dispatch or stop lanes.
- Every run-finishing path revokes its credential, with an expiry sweep for stragglers.
- Stop verification now kills the whole cgroup and polls for confirmed termination.
- Specs now support conversation messages.

## 0.15.0

Version 0.15.0 is the v1 upgrade floor. Upgrade every box in a fleet to 0.15.0 before
installing a later v1 release.

- The pre-v1 migration chain is collapsed into a guarded schema baseline; sync refuses a peer
  below 0.15.0 before exchanging data.
- Every agent session is now a first-class run with one whole-container reservation model. Stop any
  running agent session before upgrading: the fixed `sail-agent` unit is gone, and a session
  launched by an older binary is invisible to this version's stop, status, and log commands.
- Ad-hoc, dispatch, and review sessions share run-scoped units, logs, process identity, stop,
  status, watcher recovery, and reconciliation behavior.
- Process start-time fingerprints prevent stale runs from signaling a reused PID.
- Review runs record their real execution identity.

## 0.14.0

Version 0.14.0 establishes the schema floor carried forward by the v1 baseline.

- Legacy runs, specs, projects, and reviews are carried to the current data shape by `sail
  migrate`.
- Legacy build runs without a run-scoped unit are stopped; foreground review runs are unchanged.
- Synced entities receive content-addressed baseline revisions once.
- API tokens owned by an FDE now cascade when that FDE is removed.
