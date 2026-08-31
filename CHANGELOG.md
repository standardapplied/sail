# Changelog

## Unreleased

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
