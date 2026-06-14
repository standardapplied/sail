/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Code review configuration for cross-agent bug detection. When enabled, a secondary agent performs
 * an independent review of all code changes for logic bugs, edge cases, and quality issues.
 *
 * <p>The reviewer is auto-resolved from the agent install list: the first installed agent that
 * isn't the primary agent (and isn't already assigned as the security auditor) becomes the reviewer
 * — falling back to the primary itself for self-audit when no other agent is available. The {@code
 * auditor} field is an optional override.
 */
public record CodeReview(boolean enabled, String auditor) {

  private static final Set<String> KNOWN_AGENTS = Set.of("claude-code", "codex");

  public static CodeReview fromMap(Map<String, Object> map) {
    var enabled = Boolean.TRUE.equals(map.get("enabled"));
    var auditor = (String) map.get("auditor");
    return new CodeReview(enabled, auditor);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("enabled", enabled);
    if (auditor != null) map.put("auditor", auditor);
    return map;
  }

  /**
   * Resolves which agent CLI should perform the code review. Uses the explicit {@code auditor}
   * override if set, otherwise picks the first installed agent that isn't the primary and isn't in
   * the exclude set — falling back to the primary itself for self-audit when no other non-excluded
   * agent is available.
   *
   * <p>When an explicit auditor is configured, validates that it is a known agent and is present in
   * the install list. The auditor may be the same as the primary agent (self-audit is valid because
   * each audit runs as a fresh process with no context from the coding session). Throws {@link
   * IllegalArgumentException} with a clear message if any check fails.
   *
   * @param primaryType the primary agent type (e.g. "claude-code")
   * @param installList the list of installed agent CLIs (may include the primary)
   * @param exclude agents to skip during auto-resolution (e.g. the security auditor)
   * @return the reviewer agent name, or null if no suitable reviewer is available
   * @throws IllegalArgumentException if the explicit auditor is invalid
   */
  public String resolveAuditor(String primaryType, List<String> installList, Set<String> exclude) {
    if (Strings.isNotBlank(auditor)) {
      if (!KNOWN_AGENTS.contains(auditor)) {
        throw new IllegalArgumentException(
            "Unknown code review auditor '"
                + auditor
                + "'. Known agents: "
                + String.join(", ", KNOWN_AGENTS)
                + ".\n  Check the 'code_review.auditor' field in your sail.yaml.");
      }
      if (installList == null || !installList.contains(auditor)) {
        throw new IllegalArgumentException(
            "Code review auditor '"
                + auditor
                + "' is not in the agent install list."
                + "\n  Add '"
                + auditor
                + "' to 'agent.install' in your sail.yaml,"
                + " or omit 'code_review.auditor' for auto-resolution.");
      }
      return auditor;
    }
    if (installList == null || installList.isEmpty()) {
      return null;
    }
    var effectiveExclude = exclude != null ? exclude : Set.<String>of();
    for (var agent : installList) {
      if (!agent.equals(primaryType) && !effectiveExclude.contains(agent)) {
        return agent;
      }
    }
    if (!effectiveExclude.contains(primaryType) && installList.contains(primaryType)) {
      return primaryType;
    }
    return null;
  }
}
