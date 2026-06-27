/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentCli;
import java.util.ArrayList;
import java.util.List;

/**
 * Materializes org-supplied {@link SailYaml.AgentRule language rules} into each agent's native
 * "load only when relevant" channel, so a project's coding standards reach the agent only while it
 * touches the matching files — never bloating the always-loaded context. Sail ships no rule content
 * of its own; the body is supplied verbatim by the project's {@code agent_context.rules}.
 *
 * <ul>
 *   <li><b>Claude Code</b> — a path-scoped rule {@code ~/.claude/rules/<name>.md} whose {@code
 *       paths:} frontmatter loads it only when a matching file enters context (or a session-start
 *       rule when the project gives no globs).
 *   <li><b>Codex</b> — a skill {@code ~/.agents/skills/<name>/SKILL.md} Codex loads by its
 *       synthesized {@code description} when the work is relevant (Codex has no path glob).
 * </ul>
 *
 * <p>Every file is sail-owned and overwritten on every run. Pure utility — no I/O, no shell.
 */
public final class LanguageRulesGenerator {

  private LanguageRulesGenerator() {}

  /**
   * Generates the per-agent rule files for {@code rules}, or empty when none are configured.
   *
   * @param agent the target agent, which fixes the native channel and layout
   * @param rules the org-supplied rules from {@code agent_context.rules}; may be {@code null}
   * @param basePath the home base path with a trailing slash (e.g. {@code /home/dev/})
   */
  public static List<GeneratedFile> generateFiles(
      AgentCli agent, List<SailYaml.AgentRule> rules, String basePath) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    var files = new ArrayList<GeneratedFile>();
    for (var rule : rules) {
      var path =
          switch (agent) {
            case CLAUDE_CODE -> basePath + ".claude/rules/" + rule.name() + ".md";
            case CODEX -> basePath + agent.skillsDir() + rule.name() + "/SKILL.md";
          };
      var content =
          switch (agent) {
            case CLAUDE_CODE -> claudeRule(rule);
            case CODEX -> codexSkill(rule);
          };
      files.add(new GeneratedFile(path, content, false));
    }
    return List.copyOf(files);
  }

  /** A Claude rule: {@code paths:} frontmatter over the body, or just the body when unscoped. */
  private static String claudeRule(SailYaml.AgentRule rule) {
    if (rule.paths().isEmpty()) {
      return body(rule);
    }
    var sb = new StringBuilder("---\npaths:\n");
    for (var glob : rule.paths()) {
      sb.append("  - \"").append(glob).append("\"\n");
    }
    return sb.append("---\n\n").append(body(rule)).toString();
  }

  /** A Codex skill: {@code name} + a synthesized {@code description} over the body. */
  private static String codexSkill(SailYaml.AgentRule rule) {
    return "---\nname: "
        + rule.name()
        + "\ndescription: >\n  "
        + description(rule)
        + "\n---\n\n"
        + body(rule);
  }

  private static String description(SailYaml.AgentRule rule) {
    var globs = String.join(", ", rule.paths());
    var scope = globs.isEmpty() ? "" : " (" + globs + ")";
    return capitalize(rule.name())
        + " coding standards for this project. Apply when writing or reviewing "
        + rule.name()
        + scope
        + ".";
  }

  private static String body(SailYaml.AgentRule rule) {
    return rule.body() == null ? "" : rule.body().strip() + "\n";
  }

  private static String capitalize(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }
}
