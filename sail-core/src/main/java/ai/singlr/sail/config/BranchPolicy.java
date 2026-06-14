/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;

/**
 * Pure resolver for the git branch name a dispatched spec should run on. Replaces the inline
 * duplicates that lived in {@code SailApiOperations.branchName} (HTTP dispatch path) and {@code
 * DispatchCommand} (CLI dispatch path); both surfaces now consume the same answer so events
 * announcing the dispatch carry the same {@code branch} value regardless of where the dispatch was
 * kicked off.
 */
public final class BranchPolicy {

  private static final String DEFAULT_PREFIX = "sail/";

  private BranchPolicy() {}

  /**
   * Returns the expected branch name for {@code spec} under the project's {@code agent} config, or
   * {@code null} when auto-branching is disabled. Resolution order:
   *
   * <ol>
   *   <li>{@link Spec#branch()} when the spec explicitly pinned one — the engineer's choice always
   *       wins.
   *   <li>{@code agent.branch_prefix} + {@link Spec#id()} — falling back to {@code "sail/"} when
   *       the project hasn't customised the prefix.
   * </ol>
   *
   * The result is purely derived from configuration and the spec record; no git or container I/O
   * happens here.
   */
  public static String branchName(SailYaml config, Spec spec) {
    if (config == null || config.agent() == null || !config.agent().autoBranch()) {
      return null;
    }
    if (spec.branch() != null && !spec.branch().isBlank()) {
      return spec.branch();
    }
    var prefix = config.agent().branchPrefix();
    if (Strings.isBlank(prefix)) {
      prefix = DEFAULT_PREFIX;
    }
    return prefix + spec.id();
  }
}
