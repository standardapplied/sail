/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;

/**
 * Resource-scoped authorization for the review aggregate: approving a review and dismissing a
 * finding. Pure and I/O-free. The FDE who owns the work — the owner of the review's spec, its
 * assignee or, while it is unassigned, its creator — may accept their own review gate; an admin
 * retains override. Reads are open to any READ credential and do not travel this policy.
 *
 * <p>Fails closed: a review whose spec has no owner (or was deleted) may be acted on only by an
 * admin; a machine token's null handle matches no owner.
 */
public final class ReviewPolicy {

  private ReviewPolicy() {}

  /**
   * Decides whether the bound actor may approve review {@code reviewId} or dismiss one of its
   * findings, given its spec {@code specId} is owned by {@code specOwner}.
   */
  public static AccessDecision decide(String reviewId, String specId, String specOwner) {
    var actor = Actor.current();
    if (actor.isAdmin()) {
      return AccessDecision.allowed();
    }
    if (Ownership.owns(actor.handle(), specOwner)) {
      return AccessDecision.allowed();
    }
    return AccessDecision.refused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        "Review "
            + reviewId
            + " is for spec '"
            + specId
            + "'"
            + (Strings.isNotBlank(specOwner)
                ? ", owned by '" + specOwner + "', not you."
                : ", which has no owner."),
        "Only "
            + (Strings.isNotBlank(specOwner) ? specOwner : "the spec's owner")
            + " or an admin may approve or dismiss it.");
  }
}
