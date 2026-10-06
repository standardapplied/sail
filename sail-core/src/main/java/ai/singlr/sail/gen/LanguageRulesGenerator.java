/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.harness.Harness;
import java.util.ArrayList;
import java.util.List;

/**
 * Materializes org-supplied {@link SailYaml.AgentRule language rules} into each harness's native
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
   * @param agent the target harness, which fixes the native channel and layout
   * @param rules the org-supplied rules from {@code agent_context.rules}; may be {@code null}
   * @param basePath the home base path with a trailing slash (e.g. {@code /home/dev/})
   */
  public static List<GeneratedFile> generateFiles(
      Harness agent, List<SailYaml.AgentRule> rules, String basePath) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    var files = new ArrayList<GeneratedFile>();
    for (var rule : rules) {
      var path = basePath + agent.languageRulePath(rule.name());
      var content = agent.languageRule(rule.name(), rule.paths(), body(rule));
      files.add(new GeneratedFile(path, content, false));
    }
    return List.copyOf(files);
  }

  private static String body(SailYaml.AgentRule rule) {
    return rule.body() == null ? "" : rule.body().strip() + "\n";
  }
}
