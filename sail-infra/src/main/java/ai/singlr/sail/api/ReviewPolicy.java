/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;

/**
 * Resource-scoped authorization for the review aggregate: approving a review and dismissing a
 * finding. Pure and I/O-free. The FDE who owns the work — the assignee of the review's spec — may
 * accept their own review gate; an admin retains override. Reads are open to any READ credential
 * and do not travel this policy.
 *
 * <p>Fails closed: a review whose spec has no assignee (or was deleted) yields no owner, so only an
 * admin may act; a machine token's null handle matches no assignee.
 */
public final class ReviewPolicy {

  private ReviewPolicy() {}

  /**
   * Decides whether {@code actor} may approve review {@code reviewId} or dismiss one of its
   * findings, given its spec {@code specId} is assigned to {@code specAssignee}.
   */
  public static AccessDecision decide(
      Actor actor, String reviewId, String specId, String specAssignee) {
    if (actor.isAdmin()) {
      return AccessDecision.allowed();
    }
    if (Strings.isNotBlank(specAssignee) && specAssignee.equals(actor.handle())) {
      return AccessDecision.allowed();
    }
    return AccessDecision.refused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        "Review "
            + reviewId
            + " is for spec '"
            + specId
            + "'"
            + (Strings.isNotBlank(specAssignee)
                ? ", assigned to '" + specAssignee + "', not you."
                : ", which is unassigned."),
        "Only "
            + (Strings.isNotBlank(specAssignee) ? specAssignee : "the spec's assignee")
            + " or an admin may approve or dismiss it.");
  }
}
