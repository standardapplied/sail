/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;

/**
 * Resource-scoped authorization for the run aggregate: reading a run's log (buffered tail or SSE
 * stream) and stopping it. Pure and I/O-free so REST and SSE evaluate the identical verdict for the
 * identical caller from one place. A run's owner is its spec's assignee — an FDE must not read the
 * logs of a spec that is not assigned to them — with the admin retaining override.
 *
 * <p>Fails closed: a spec with no assignee (or a deleted spec) yields no owner, so only an admin
 * may reach it; a machine token's null handle matches no assignee.
 */
public final class RunPolicy {

  private RunPolicy() {}

  /**
   * Decides whether {@code actor} may access run {@code runId}, whose spec {@code specId} is
   * assigned to {@code specAssignee}. The provenance guard (does this run belong to this box) is a
   * separate, earlier check; this governs identity.
   */
  public static AccessDecision access(
      Actor actor, String runId, String specId, String specAssignee) {
    if (actor.isAdmin()) {
      return AccessDecision.allowed();
    }
    if (Strings.isNotBlank(specAssignee) && specAssignee.equals(actor.handle())) {
      return AccessDecision.allowed();
    }
    return AccessDecision.refused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        "Run "
            + runId
            + " belongs to spec '"
            + specId
            + "'"
            + (Strings.isNotBlank(specAssignee)
                ? ", assigned to '" + specAssignee + "', not you."
                : ", which is unassigned."),
        "Only "
            + (Strings.isNotBlank(specAssignee) ? specAssignee : "the spec's assignee")
            + " or an admin may access this run.");
  }
}
