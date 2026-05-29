/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import ai.singlr.sail.config.SailYaml;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Assembles the agent guardrail/audit files (security-audit prompts, code-review config, post-task
 * hooks) for a project. Shared by every code path that materializes agent context — provisioning,
 * delta-apply, run, and context regen — so they generate an identical set.
 */
public final class AgentAuditFiles {

  private AgentAuditFiles() {}

  /** Returns the audit/guardrail files for {@code config}; empty when no agent is configured. */
  public static List<GeneratedFile> assemble(SailYaml config) {
    var excludeAgents = resolveSecurityAuditorExclude(config);
    var files = new ArrayList<GeneratedFile>();
    files.addAll(SecurityAuditGenerator.generateFiles(config));
    files.addAll(CodeReviewGenerator.generateFiles(config, excludeAgents));
    files.addAll(PostTaskHooksGenerator.generateFiles(config, excludeAgents));
    return files;
  }

  /**
   * The agents to exclude from code review — the configured security auditor reviews via its own
   * stage, so it should not also be a code reviewer.
   */
  private static Set<String> resolveSecurityAuditorExclude(SailYaml config) {
    if (config.agent() != null
        && config.agent().securityAudit() != null
        && config.agent().securityAudit().enabled()) {
      var resolved =
          config
              .agent()
              .securityAudit()
              .resolveAuditor(config.agent().type(), config.agent().install());
      if (resolved != null) {
        return Set.of(resolved);
      }
    }
    return Set.of();
  }
}
