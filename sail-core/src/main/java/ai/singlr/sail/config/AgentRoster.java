/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.List;

/**
 * Selects which installed agent should independently check another's work — the shared rule behind
 * both the security audit and the review pipeline. Independence is preferred but not required: when
 * a second agent is installed it does the checking (cross-agent review, e.g. Codex reviews Claude
 * Code), and when only the primary is installed it reviews its own work in a fresh session, which
 * is weaker but still catches mistakes a continued context would miss.
 */
public final class AgentRoster {

  private AgentRoster() {}

  /**
   * Returns the agent that should review the primary agent's output: the first installed agent that
   * is not the primary, or the primary itself when it is the only one installed, or {@code null}
   * when no agent is installed.
   *
   * @param primaryType the agent that did the work (e.g. {@code "claude-code"})
   * @param installList the agents installed on the project (may include the primary)
   */
  public static String reviewer(String primaryType, List<String> installList) {
    if (installList == null || installList.isEmpty()) {
      return null;
    }
    for (var agent : installList) {
      if (!agent.equals(primaryType)) {
        return agent;
      }
    }
    return primaryType;
  }
}
