/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentCli;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generates the sail-owned agent context from {@link SailYaml} configuration. Each configured agent
 * gets one home-level file it loads natively alongside any project-level file the engineer keeps in
 * the workspace ({@code ~/.claude/CLAUDE.md} for Claude Code, {@code ~/.codex/AGENTS.md} for
 * Codex), plus the sail-owned methodology and spec-board skills. Every file is sail-owned and
 * overwritten on every run; sail never writes into the engineer's workspace. Pure utility — no I/O,
 * no shell.
 */
public final class AgentContextGenerator {

  private AgentContextGenerator() {}

  /**
   * Generates the sail-owned file set for the configured agents: the home-level context file per
   * agent ({@code ~/.claude/CLAUDE.md} / {@code ~/.codex/AGENTS.md}) and the methodology +
   * spec-board skills under the agent's home skills directory. Empty when no agent is configured.
   */
  public static List<GeneratedFile> generateFiles(SailYaml config) {
    var targetAgents = resolveTargetAgents(config);
    if (targetAgents.isEmpty()) {
      return List.of();
    }

    var home = "/home/" + config.sshUser() + "/";
    var body = generateContextBody(config);
    var methodology = config.agent() != null ? config.agent().methodology() : null;
    var specsDir = config.agent() != null ? config.agent().specsDir() : null;
    var rules = config.agentContext() != null ? config.agentContext().rules() : null;

    var files = new ArrayList<GeneratedFile>();
    for (var agent : targetAgents) {
      files.add(new GeneratedFile(home + agent.homeContextPath(), body, false));
      files.addAll(MethodologyGenerator.generateFiles(agent, methodology, home));
      files.addAll(SpecSkillGenerator.generateFiles(agent, specsDir, home));
      files.addAll(LanguageRulesGenerator.generateFiles(agent, rules, home));
    }
    return List.copyOf(files);
  }

  /**
   * Determines which agents need context files. Uses the {@code install} list from the agent
   * config, falling back to just the primary agent type.
   */
  static List<AgentCli> resolveTargetAgents(SailYaml config) {
    if (config.agent() == null) {
      return List.of();
    }
    var installList = config.agent().install();
    if (installList != null && !installList.isEmpty()) {
      return installList.stream().distinct().map(AgentCli::fromYamlName).toList();
    }
    var primaryType = config.agent().type();
    if (primaryType != null) {
      return List.of(AgentCli.fromYamlName(primaryType));
    }
    return List.of();
  }

  /** Generates the markdown body of the sail-owned home context file. */
  static String generateContextBody(SailYaml config) {
    var sb = new StringBuilder();

    sb.append("\n## Project\n");
    sb.append(Objects.requireNonNullElse(config.description(), config.name()));
    sb.append("\n");

    if (config.agentContext() != null && config.agentContext().techStack() != null) {
      sb.append("\n## Tech Stack\n");
      sb.append(config.agentContext().techStack().strip());
      sb.append("\n");
    }

    sb.append("\n## Engineering Principles\n");
    sb.append(universalPrinciples());

    if (config.agentContext() != null && config.agentContext().conventions() != null) {
      sb.append("\n## Project Conventions\n");
      sb.append(config.agentContext().conventions().strip());
      sb.append("\n");
    }

    if (config.agentContext() != null && config.agentContext().buildCommands() != null) {
      sb.append("\n## Build & Run\n");
      sb.append(config.agentContext().buildCommands().strip());
      sb.append("\n");
    }

    if (config.runtimes() != null
        && (config.runtimes().jdk() > 0
            || config.runtimes().node() != null
            || config.runtimes().maven() != null)) {
      sb.append("\n## Runtimes\n");
      if (config.runtimes().jdk() > 0) {
        sb.append("- JDK ").append(config.runtimes().jdk()).append("\n");
      }
      if (config.runtimes().node() != null) {
        sb.append("- Node.js ").append(config.runtimes().node()).append("\n");
      }
      if (config.runtimes().maven() != null) {
        sb.append("- Maven ").append(config.runtimes().maven()).append("\n");
      }
    }

    if (config.repos() != null && !config.repos().isEmpty()) {
      sb.append("\n## Repositories\n");
      for (var repo : config.repos()) {
        sb.append("- `~/workspace/").append(repo.path()).append("` \u2190 ").append(repo.url());
        if (repo.branch() != null) {
          sb.append(" (branch: ").append(repo.branch()).append(")");
        }
        sb.append("\n");
      }
    }

    if (config.services() != null && !config.services().isEmpty()) {
      sb.append("\n## Services (Podman containers, auto-started on boot)\n");
      sb.append("| Service | Port | Notes |\n");
      sb.append("|---------|------|-------|\n");
      for (var entry : config.services().entrySet()) {
        var svcName = entry.getKey();
        var svc = entry.getValue();
        var displayName = serviceDisplayName(svcName, svc);
        var ports =
            svc.ports() != null
                ? svc.ports().stream()
                    .map(String::valueOf)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("-")
                : "-";
        var notes = serviceNotes(svc);
        sb.append("| ")
            .append(displayName)
            .append(" | ")
            .append(ports)
            .append(" | ")
            .append(notes)
            .append(" |\n");
      }
    }

    if (config.processes() != null && !config.processes().isEmpty()) {
      sb.append("\n## Developer Commands\n");
      sb.append("| Task | Command |\n");
      sb.append("|------|---------|\n");
      for (var entry : config.processes().entrySet()) {
        var taskName = entry.getKey();
        var proc = entry.getValue();
        var cmd = proc.command();
        if (proc.workdir() != null && !".".equals(proc.workdir())) {
          cmd = cmd + " (in " + proc.workdir() + ")";
        }
        sb.append("| ").append(taskName).append(" | ").append(cmd).append(" |\n");
      }
    }

    sb.append("\n## Environment\n");
    sb.append("This workspace runs inside an Incus container managed by `sail`.\n");
    sb.append("Podman (rootless) is the container runtime. Services run as Podman containers.\n");
    if (config.git() != null) {
      sb.append("Git identity: ")
          .append(config.git().name())
          .append(" <")
          .append(config.git().email())
          .append(">.\n");
    }
    sb.append("Create feature branches for autonomous work.\n");

    sb.append("\n## Security\n");
    sb.append(
        "Zero-trust posture: validate every input, authenticate every request, authorize every"
            + " action. Never hardcode secrets, tokens, or credentials. Never concatenate untrusted"
            + " input into queries or commands — use parameterized queries and safe APIs.\n");
    if (config.agentContext() != null
        && config.agentContext().security() != null
        && !config.agentContext().security().isBlank()) {
      sb.append("\n### Project-Specific Security Requirements\n");
      sb.append(config.agentContext().security().strip());
      sb.append("\n");
    }

    if (config.agentContext() != null
        && config.agentContext().projectSpecific() != null
        && !config.agentContext().projectSpecific().isBlank()) {
      sb.append("\n## Project-Specific Notes\n");
      sb.append(config.agentContext().projectSpecific().strip());
      sb.append("\n");
    }

    if (config.agent() != null) {
      sb.append(MethodologyGenerator.generateMethodologyInstructions(config.agent().methodology()));
    }

    appendSpecSection(sb, config);

    return sb.toString();
  }

  /** The consolidated engineering principles included in every agent context file. */
  static String universalPrinciples() {
    return """
        You are an efficient senior engineer. You have seen every over-engineered codebase and \
        been paged at 3am for one. The best code is the code never written.

        - SRP, DRY, KISS, YAGNI. Prefer composition over inheritance.
        - Deletion over addition. Boring over clever — clever is what someone decodes at 3 AM.
        - Fewest files possible. The shortest working diff wins, but only once you understand the \
        problem; the smallest change in the wrong place isn't lazy, it's a second bug.
        - Simple and elegant: if a solution feels complex, step back and find a simpler one.
        - No inline comments, ever. Code is self-documenting through clear names and small, \
        single-purpose functions; the only comments explain non-obvious "why".
        - Handle every edge case, but keep it simple — robustness and simplicity reinforce each other.
        - Fail fast and loud: validate preconditions early; throw clear, actionable errors.
        - Test behavior, not lines: ship a test that asserts the behavior you changed. A green \
        coverage gate is necessary, not sufficient.
        """;
  }

  /** Appends the spec-driven development section when specs are enabled for the project. */
  private static void appendSpecSection(StringBuilder sb, SailYaml config) {
    if (config.agent() == null || config.agent().specsDir() == null) {
      return;
    }
    sb.append("\n## Spec-Driven Development\n\n");
    sb.append(
        """
        This project uses spec-driven development. Specs live in the **Sail database** — the shared,
        synced source of truth — and you manage them with the `spec` CLI, never by editing
        files. What you create syncs to every devbox on the project.

        ### Working with specs
        - `spec board` — kanban summary; `spec list [--status pending] [--assignee me]`
        - `spec show <id>` — metadata, dependencies, and the full body
        - `spec create --id <id> --title "<title>" --body-file <file>` — create one; add
          `--depends-on a,b`, `--repos api,web`, `--agent codex|claude-code`, `--model <id>`,
          `--reasoning-effort none|low|medium|high|xhigh` as the work warrants
        - `spec update <id> --status <status>` — change metadata;
          `spec content <id> --body-file <file>` — revise the body

        Repo, agent, and model values must match `sail.yaml` (`repos[].path`, installed agents). In
        a multi-repo project, set `--repos` before dispatch or `sail spec dispatch` will not
        auto-create a branch.

        ### Status Lifecycle
        `pending` → `in_progress` → `review` → `done`
        Status is managed by `sail`, not by you. Do not change a spec's status during autonomous
        execution.

        ### Dependencies
        `--depends-on` lists spec ids that must be `done` before a spec can start. Never start a
        spec whose dependencies are not all `done`; `sail spec dispatch` enforces this when it picks
        the next ready spec.

        ### Interactive Mode
        When the engineer asks you to brainstorm or write specs, draft each body to a temporary
        markdown file and run `spec create ...`. There is no `specs/` directory to edit.
        """);
  }

  /** Builds a display name from the service name and image (e.g., "Postgres 16"). */
  private static String serviceDisplayName(String name, SailYaml.Service service) {
    var image = service.image();
    if (image == null) {
      return capitalize(name);
    }
    var colonIdx = image.lastIndexOf(':');
    var tag = colonIdx >= 0 ? image.substring(colonIdx + 1) : "";
    var displayTag = "latest".equals(tag) ? "" : " " + tag;
    return capitalize(name) + displayTag;
  }

  /** Extracts useful notes from service environment variables. */
  private static String serviceNotes(SailYaml.Service service) {
    if (service.environment() == null || service.environment().isEmpty()) {
      return "-";
    }
    var env = service.environment();
    var sb = new StringBuilder();

    if (env.containsKey("POSTGRES_USER")) {
      sb.append("user: ").append(env.get("POSTGRES_USER"));
    }
    if (env.containsKey("POSTGRES_DB")) {
      if (!sb.isEmpty()) sb.append(", ");
      sb.append("db: ").append(env.get("POSTGRES_DB"));
    }
    if (env.containsKey("MEILI_ENV")) {
      if (!sb.isEmpty()) sb.append(", ");
      sb.append(env.get("MEILI_ENV")).append(" mode");
    }

    return sb.isEmpty() ? "-" : sb.toString();
  }

  private static String capitalize(String s) {
    if (Strings.isEmpty(s)) return s;
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
