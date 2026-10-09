/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.store.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * The one skill sail ships, {@code spec-board}: what an interactive session in a container needs to
 * find the {@code spec} CLI and manage specs through it. Specs live in the Sail database — the
 * shared, synced source of truth — so the skill teaches the agent to create, list, show and update
 * them with {@code spec}, never by editing files on disk. {@code spec} is a tiny in-container
 * helper that reaches the host API over the bind-mounted Unix socket; the agent never needs the
 * {@code sail} binary or a token, and what it creates syncs to every other box.
 *
 * <p>Both harnesses have a skill system with the same {@code SKILL.md} shape, so the skill is
 * installed whole, with its spec body template alongside, into each harness's skills folder by the
 * same installer that installs a project's own skills.
 */
public final class SpecSkillGenerator {

  /** The skill's name, which is its folder's and which no project skill may take. */
  public static final String NAME = "spec-board";

  private static final int MODE = 0644;

  /**
   * The SHA-256 of each file sail wrote into {@code spec-board} before it stamped the folder
   * (0.46.5 and earlier), by path: the one spec template, and the {@code SKILL.md} of every release
   * that changed it. An unstamped folder holding nothing but regular files of these names and
   * contents is one sail wrote, and the only unstamped folder an install replaces.
   */
  public static final Map<String, Set<String>> UNSTAMPED_HASHES =
      Map.of(
          StageSkill.MANIFEST,
          Set.of(
              "6b9c48f495659704e170ee204e4c79534c1f5a44f2004ceb4ce369cd05ba0d95",
              "a3309bcef7f830ac174d5cdc2188c2bd1d716198ac99e25fa31ac6247b171233",
              "093f9ad4cb85d17c93b6053db0b811d29ee164c63488cf72c7fbdfc2569779bd",
              "7183f543e645cd9324cc8b1c8b0b36c4f807ee2efb97bb31a49afff27682af82",
              "7496ac254457448ba0440863d3056b54fb855b720c6493fe69ae96c48f5fc78c"),
          "spec-template.md",
          Set.of("299a557e66625c8e14a9f784393779f7b3540d8de06ed7f4ceac218251c0c89c"));

  private static final Map<String, String> FILES =
      Map.of(StageSkill.MANIFEST, skillMd(), "spec-template.md", specTemplateMd());

  private SpecSkillGenerator() {}

  /** The {@code spec-board} skill: its {@code SKILL.md} and the spec body template beside it. */
  public static StageSkill skill() {
    return StageSkill.of(
        NAME,
        skillMd(),
        FILES.entrySet().stream()
            .map(
                entry -> {
                  var bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                  return new StageSkill.File(
                      entry.getKey(), BlobStore.hash(bytes), bytes.length, MODE);
                })
            .toList());
  }

  /** The bytes of {@code file} of the {@code spec-board} skill. */
  public static InputStream content(StageSkill.File file) {
    var text = FILES.get(file.path());
    if (text == null) {
      throw new IllegalArgumentException(NAME + " has no file " + file.path() + ".");
    }
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  /** The text of the skill's {@code SKILL.md}. */
  public static String skillMd() {
    return """
        ---
        name: spec-board
        description: >
          Use when the engineer asks about specs, the board, what to work on next, or to create or
          update a spec. Specs live in the Sail database and are managed with the `spec` CLI.
        argument-hint: "[create|list|show|update] [args...]"
        ---

        You are the spec manager for this project. Specs live in the Sail database — the shared,
        synced source of truth — so you manage them with the `spec` CLI, never by editing
        files. Anything you create here syncs to every other devbox on the project.

        ## Commands

        ### `/spec-board list` or "show me the board"
        """
        + listInstructions()
        + """

        ### `/spec-board create <id> <title>` or "create a spec for ..."
        """
        + createInstructions()
        + """

        ### `/spec-board show <id>` or "show me the auth spec"
        """
        + showInstructions()
        + """

        ### `/spec-board update <id> <status>` or "move auth to in_progress"
        """
        + updateInstructions()
        + """

        ### Bulk creation — "turn these into specs" or "create specs for all of these"
        """
        + bulkCreateInstructions()
        + """

        ## Reference

        """
        + coreReference()
        + """

        ## Spec Body Template

        When writing a spec body, use the template in [spec-template.md](spec-template.md).
        """;
  }

  private static String listInstructions() {
    return """
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

        Show the title under each id. The board marks the next ready spec; flag specs whose \
        dependencies are not yet done as blocked.
        """;
  }

  private static String createInstructions() {
    return """
        1. Derive an id from the title (lowercase, hyphens, e.g., "OAuth Flow" → `oauth-flow`).
        2. Write the spec body to a temporary markdown file using the spec template, e.g. \
        `/tmp/<id>.md`.
        3. Create the spec in the database:
           ```sh
           spec create --id <id> --title "<title>" --body-file /tmp/<id>.md
           ```
           Add options as the conversation warrants:
           - `--depends-on a,b` — spec ids that must be `done` first
           - `--repos repo-a,repo-b` — target repos (must match `repos[].path` in `sail.yaml`)
           - `--agent codex|claude-code` — overrides the project default
           - `--model <id>` `--reasoning-effort none|low|medium|high|xhigh` — for agents that \
        support them
        4. Confirm: "Created spec `<id>`."

        If the engineer gave detailed requirements in the conversation, write them into the body \
        file instead of leaving placeholders. Ask which repo, agent, model, and dependencies apply \
        when the project's configuration makes them relevant.
        """;
  }

  private static String showInstructions() {
    return """
        Run `spec show <id>` — it prints the metadata, dependencies, and the full body. Use
        `--json` if you need to parse fields.
        """;
  }

  private static String updateInstructions() {
    return """
        Valid statuses: `pending`, `in_progress`, `review`, `awaiting_merge`, `done`.

        ```sh
        spec update <id> --status <new-status>
        ```

        Confirm: "Updated `<id>` → `<new-status>`". To revise the body, write the new markdown to a
        temp file and run `spec content <id> --body-file /tmp/<id>.md`.
        """;
  }

  private static String bulkCreateInstructions() {
    return """
        When the engineer has brainstormed multiple features or tasks and wants to turn them \
        into specs:

        1. Extract each distinct unit of work from the conversation.
        2. For each, derive an id and title and write a body file.
        3. Infer dependencies from the natural ordering discussed and pass them via `--depends-on`.
        4. Run one `spec create` per unit.
        5. Show the resulting board with `spec board`.

        This is the primary daytime workflow: brainstorm with the engineer, then materialize \
        the plan into specs with one confirmation.
        """;
  }

  private static String coreReference() {
    return """
        ### Creating a spec
        ```sh
        spec create --id oauth-flow --title "OAuth 2.0 authorization code flow" \\
          --body-file /tmp/oauth-flow.md --depends-on data-model --repos app --agent codex \\
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
        """;
  }

  private static String specTemplateMd() {
    return """
        # <Title>

        ## Goal
        What this spec achieves in one or two sentences.

        ## Background
        Why this work is needed. Link to prior specs or decisions if relevant.

        ## Requirements
        - Concrete, testable requirements
        - Each item should be independently verifiable

        ## Approach
        High-level design and key decisions. Include:
        - Components affected
        - Data model changes (if any)
        - API contracts (if any)

        ## Edge Cases
        - Known edge cases and how to handle them

        ## Test Strategy
        - What to test and how
        - Key scenarios to cover

        ## Out of Scope
        - What this spec explicitly does NOT cover
        """;
  }
}
