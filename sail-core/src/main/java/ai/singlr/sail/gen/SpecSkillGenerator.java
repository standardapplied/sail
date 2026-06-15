/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import ai.singlr.sail.engine.AgentCli;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the spec-board skill for AI coding agents. Specs live in the Sail database — the
 * shared, synced source of truth — so the skill teaches the agent to manage them through the {@code
 * spec} command ({@code create}/{@code list}/{@code board}/{@code show}/{@code update}), never by
 * editing YAML files on disk. {@code spec} is a tiny in-container helper that reaches the host API
 * over the bind-mounted Unix socket; the agent never needs the {@code sail} binary or a token, and
 * what it creates syncs to every other box.
 *
 * <ul>
 *   <li>Claude Code: {@code .claude/skills/spec-board/SKILL.md} + a spec body template
 *   <li>Codex: instructions embedded in {@code AGENTS.md} via {@link #codexInstructions}
 * </ul>
 */
public final class SpecSkillGenerator {

  private SpecSkillGenerator() {}

  /**
   * Generates spec skill files for the given agent when specs are enabled for the project.
   *
   * @param agent the target agent type
   * @param specsDir the configured specs directory name; when {@code null}, specs are disabled and
   *     no skill is generated. (Retained as the project's "uses specs" switch even though specs are
   *     now stored in the database rather than this directory.)
   * @param basePath the workspace base path (e.g., {@code /home/dev/workspace/})
   * @return generated files, empty if specs are disabled or the agent is Codex (inline
   *     instructions)
   */
  public static List<GeneratedFile> generateFiles(
      AgentCli agent, String specsDir, String basePath) {
    if (specsDir == null) {
      return List.of();
    }
    return switch (agent) {
      case CLAUDE_CODE -> claudeSkillFiles(basePath);
      case CODEX -> List.of();
    };
  }

  /**
   * Returns spec management instructions for Codex, which has no skill system. These are appended
   * to the generated AGENTS.md content.
   *
   * @param specsDir the configured specs directory name; {@code null} disables specs (empty string)
   * @return markdown instructions, or empty string when specs are disabled
   */
  public static String codexInstructions(String specsDir) {
    if (specsDir == null) {
      return "";
    }
    return """

        ## Spec Management

        You are the spec manager for this project. Specs live in the Sail database, not in files. \
        When the engineer asks you to create, list, update, or show specs, use the `spec` CLI \
        exactly as described below.

        """
        + coreInstructions()
        + specTemplate();
  }

  private static List<GeneratedFile> claudeSkillFiles(String basePath) {
    var files = new ArrayList<GeneratedFile>();
    var skillDir = basePath + ".claude/skills/spec-board/";
    files.add(new GeneratedFile(skillDir + "SKILL.md", claudeSkillMd(), false));
    files.add(new GeneratedFile(skillDir + "spec-template.md", specTemplateMd(), false));
    return List.copyOf(files);
  }

  private static String claudeSkillMd() {
    return """
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

        ```
        ┌─────────────┬─────────────────┬──────────────┬──────────────┐
        │ Pending (3)  │ In Progress (1) │ Review (0)   │ Done (2)     │
        ├─────────────┼─────────────────┼──────────────┼──────────────┤
        │ search-api   │ oauth-flow      │              │ data-model   │
        │  └─ depends: │                 │              │ auth-setup   │
        │     oauth    │                 │              │              │
        │ payments     │                 │              │              │
        │ notifications│                 │              │              │
        └─────────────┴─────────────────┴──────────────┴──────────────┘
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
        Valid statuses: `pending`, `in_progress`, `review`, `done`.

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
        `pending` → `in_progress` → `review` → `done`
        - **pending**: ready to be picked up
        - **in_progress**: an agent is actively working on it (set by `sail spec dispatch`)
        - **review**: PR created, waiting for human review (set by `sail`)
        - **done**: PR merged, work complete (set by the engineer via `sail`)

        During autonomous execution Sail manages status itself — do not change it. The engineer's
        `/spec-board update` is the exception, when they explicitly ask to move a spec.

        ### Fields (set at create or via `spec update`)
        - **id** (required): stable identifier, lowercase with hyphens
        - **title** (required): short human-readable description
        - **status**: one of pending, in_progress, review, done
        - **assignee**: agent type or engineer name
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

  private static String specTemplate() {
    return "When writing a spec body, use this structure:\n\n```markdown\n"
        + specTemplateMd()
        + "```\n";
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

  private static String coreInstructions() {
    return listInstructions()
        + createInstructions()
        + showInstructions()
        + updateInstructions()
        + bulkCreateInstructions()
        + coreReference();
  }
}
