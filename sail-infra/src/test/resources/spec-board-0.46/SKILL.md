---
name: spec-board
description: >
  Manage the project spec board — create specs, list them as a kanban board, update status,
  show spec details. Only invoked explicitly by the engineer with /spec-board.
argument-hint: "[create|list|show|update] [args...]"
disable-model-invocation: true
---

You are the spec manager for this project. Specs live in the Sail database — the shared,
synced source of truth — so you manage them with the `spec` CLI, never by editing
files. Anything you create here syncs to every other devbox on the project.

## Commands

### `/spec-board list` or "show me the board"
Run `spec board` for the kanban summary, or `spec list` for the full set (add
`--status pending` or `--assignee me` to filter). Render the result as status columns:

Use `spec comment <id> --body <text>|-` to post progress, questions, and summaries in the
spec's conversation; add `--question` when you are blocked and need a human reply — it
pages the engineer on the board until someone answers. Use `spec comments <id>` to read
the room.

```
┌─────────────┬─────────────────┬──────────────┬────────────────────┬──────────────┐
│ Pending (3)  │ In Progress (1) │ Review (0)   │ Awaiting Merge (1) │ Done (2)     │
├─────────────┼─────────────────┼──────────────┼────────────────────┼──────────────┤
│ search-api   │ oauth-flow      │              │ billing-hooks      │ data-model   │
│  └─ depends: │                 │              │                    │ auth-setup   │
│     oauth    │                 │              │                    │              │
│ payments     │                 │              │                    │              │
│ notifications│                 │              │                    │              │
└─────────────┴─────────────────┴──────────────┴────────────────────┴──────────────┘
```

Show the title under each id. The board marks the next ready spec; flag specs whose dependencies are not yet done as blocked.

### `/spec-board create <id> <title>` or "create a spec for ..."
1. Derive an id from the title (lowercase, hyphens, e.g., "OAuth Flow" → `oauth-flow`).
2. Write the spec body to a temporary markdown file using the spec template, e.g. `/tmp/<id>.md`.
3. Create the spec in the database:
   ```sh
   spec create --id <id> --title "<title>" --body-file /tmp/<id>.md
   ```
   Add options as the conversation warrants:
   - `--depends-on a,b` — spec ids that must be `done` first
   - `--repos repo-a,repo-b` — target repos (must match `repos[].path` in `sail.yaml`)
   - `--agent codex|claude-code` — overrides the project default
   - `--model <id>` `--reasoning-effort none|low|medium|high|xhigh` — for agents that support them
4. Confirm: "Created spec `<id>`."

If the engineer gave detailed requirements in the conversation, write them into the body file instead of leaving placeholders. Ask which repo, agent, model, and dependencies apply when the project's configuration makes them relevant.

### `/spec-board show <id>` or "show me the auth spec"
Run `spec show <id>` — it prints the metadata, dependencies, and the full body. Use
`--json` if you need to parse fields.

### `/spec-board update <id> <status>` or "move auth to in_progress"
Valid statuses: `pending`, `in_progress`, `review`, `awaiting_merge`, `done`.

```sh
spec update <id> --status <new-status>
```

Confirm: "Updated `<id>` → `<new-status>`". To revise the body, write the new markdown to a
temp file and run `spec content <id> --body-file /tmp/<id>.md`.

### Bulk creation — "turn these into specs" or "create specs for all of these"
When the engineer has brainstormed multiple features or tasks and wants to turn them into specs:

1. Extract each distinct unit of work from the conversation.
2. For each, derive an id and title and write a body file.
3. Infer dependencies from the natural ordering discussed and pass them via `--depends-on`.
4. Run one `spec create` per unit.
5. Show the resulting board with `spec board`.

This is the primary daytime workflow: brainstorm with the engineer, then materialize the plan into specs with one confirmation.

## Reference

### Creating a spec
```sh
spec create --id oauth-flow --title "OAuth 2.0 authorization code flow" \
  --body-file /tmp/oauth-flow.md --depends-on data-model --repos app --agent codex \
  --model gpt-5.5 --reasoning-effort high
```

### Status Lifecycle
`pending` → `in_progress` → `review` → `awaiting_merge` → `done`
- **pending**: ready to be picked up
- **in_progress**: an agent is actively working on it (set by `sail spec dispatch`)
- **review**: PR created, waiting for human review (set by `sail`)
- **awaiting_merge**: review passed; the PR waits for a human to merge it on the forge
  (set by `sail`)
- **done**: PR merged, work complete (set by the engineer via `sail`)

During autonomous execution Sail manages status itself — do not change it. The engineer's
`/spec-board update` is the exception, when they explicitly ask to move a spec.

### Fields (set at create or via `spec update`)
- **id** (required): stable identifier, lowercase with hyphens
- **title** (required): short human-readable description
- **status**: one of pending, in_progress, review, awaiting_merge, done
- **assignee**: an FDE handle, or blank for anyone to claim (never an agent type or a
  run's principal); the agent type goes in `--agent`. An agent claims an unassigned spec
  for the FDE it acts for
- **depends-on**: spec ids that must be done first
- **repos**: target repository paths from `sail.yaml` `repos[].path`
- **agent**: agent CLI for this spec (`claude-code` or `codex`)
- **model** / **reasoning-effort**: for agents that support them
- **branch**: git branch name for this spec's work

In multi-repo projects, always set `--repos` before dispatch so Sail branches the right
repository. In multi-agent projects, set `--agent` when a spec should run on a non-default
agent; otherwise dispatch uses `agent.type` from `sail.yaml`.

### Dependency Rules
A spec cannot be started until every id in its `depends-on` is `done`. When listing specs,
visually indicate which pending specs are blocked.

### Where specs live
Specs are rows in the Sail database, replicated across devboxes by `sail sync`. There is no
`specs/` directory to edit — always go through `spec`.

## Spec Body Template

When writing a spec body, use the template in [spec-template.md](spec-template.md).
