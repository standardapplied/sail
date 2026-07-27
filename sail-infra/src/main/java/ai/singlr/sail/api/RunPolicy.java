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
 * logs of a spec that is not assigned to them — or, for an ad-hoc session that works no spec, the
 * handle of the box that launched it; the admin retains override either way.
 *
 * <p>Fails closed: no owner (an unassigned or deleted spec, an ad-hoc run from a handle-less box)
 * means only an admin may reach it; a machine token's null handle matches no owner.
 */
public final class RunPolicy {

  private RunPolicy() {}

  /**
   * Decides whether {@code actor} may access run {@code runId}: {@code specId} names its spec (null
   * for an ad-hoc session) and {@code owner} the identity that owns it — the spec's assignee or the
   * ad-hoc session's launching handle. The provenance guard (does this run belong to this box) is a
   * separate, earlier check; this governs identity.
   */
  public static AccessDecision access(Actor actor, String runId, String specId, String owner) {
    if (actor.isAdmin()) {
      return AccessDecision.allowed();
    }
    if (Strings.isNotBlank(owner) && owner.equals(actor.handle())) {
      return AccessDecision.allowed();
    }
    return AccessDecision.refused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        describeRun(runId, specId, owner),
        "Only "
            + (Strings.isNotBlank(owner) ? owner : "its owner")
            + " or an admin may access this run.");
  }

  private static String describeRun(String runId, String specId, String owner) {
    if (Strings.isBlank(specId)) {
      return "Run "
          + runId
          + " is an ad-hoc session"
          + (Strings.isNotBlank(owner) ? " launched by '" + owner + "', not you." : ".");
    }
    return "Run "
        + runId
        + " belongs to spec '"
        + specId
        + "'"
        + (Strings.isNotBlank(owner)
            ? ", assigned to '" + owner + "', not you."
            : ", which is unassigned.");
  }
}
