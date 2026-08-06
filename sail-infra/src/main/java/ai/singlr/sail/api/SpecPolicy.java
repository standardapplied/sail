/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;

/**
 * Resource-scoped authorization for the spec aggregate, shared by every lane (the HTTP API a member
 * reaches over {@code sail spec edit} or a GUI client, and the in-container socket an agent uses).
 * Pure and I/O-free so the full {role × ownership × verb} matrix is table-testable.
 *
 * <p>Two verbs. {@link #mutate} governs edit / content-write / restore / delete / status change:
 * the spec's assignee or an admin, and for an unassigned spec its creator or an admin. {@link
 * #reassign} governs changing the assignee: an admin only, the explicit "move work between boxes"
 * act — except a member may self-serve grab an <em>unassigned</em> spec for themselves.
 *
 * <p>Fails closed: a read-only credential is refused before ownership is consulted, and a machine
 * token (null handle) matches no owner, so it travels only the admin paths its role grants.
 */
public final class SpecPolicy {

  private SpecPolicy() {}

  /**
   * Decides whether {@code actor} may mutate spec {@code specId}. Ownership is the assignee, or the
   * creator when the spec is unassigned; an admin always passes. Order — write capability, then
   * admin, then ownership — so the most fundamental precondition names the refusal.
   */
  public static AccessDecision mutate(
      Actor actor, String specId, String assignee, String createdBy) {
    if (!actor.canWrite()) {
      return readOnly();
    }
    if (actor.isAdmin()) {
      return AccessDecision.allowed();
    }
    var owner = Strings.isNotBlank(assignee) ? assignee : createdBy;
    if (Strings.isNotBlank(owner) && actor.actsFor(owner)) {
      return AccessDecision.allowed();
    }
    return notAssignee(specId, assignee, createdBy);
  }

  /**
   * Decides whether {@code actor} may set spec {@code specId}'s assignee to {@code
   * requestedAssignee}. Reassignment is an admin act; the one member-allowed case is claiming a
   * spec that is currently unassigned for oneself. An agent principal claims for the FDE it acts
   * for, never for its ephemeral run-scoped handle — dispatch locality matches the assignee against
   * the node's FDE handle, so a run-principal assignee would leave the spec undispatchable.
   */
  public static AccessDecision reassign(
      Actor actor, String specId, String currentAssignee, String requestedAssignee) {
    if (!actor.canWrite()) {
      return readOnly();
    }
    if (actor.isAdmin()) {
      return AccessDecision.allowed();
    }
    var claimant = actor.agentLane() ? actor.owner() : actor.handle();
    if (Strings.isBlank(currentAssignee)
        && Strings.isNotBlank(claimant)
        && claimant.equals(requestedAssignee)) {
      return AccessDecision.allowed();
    }
    return AccessDecision.refused(
        ErrorCode.FORBIDDEN_ADMIN_ONLY,
        "Reassigning spec '"
            + specId
            + "' moves work between FDEs and is an admin-only action"
            + (Strings.isNotBlank(currentAssignee) ? " (currently '" + currentAssignee + "')" : "")
            + ".",
        "Ask an admin to reassign it. You may grab a spec only while it is unassigned.");
  }

  private static AccessDecision readOnly() {
    return AccessDecision.refused(
        ErrorCode.READ_ONLY_CREDENTIAL,
        "Your credential is read-only and cannot change specs.",
        "Ask an admin for a member or admin credential.");
  }

  private static AccessDecision notAssignee(String specId, String assignee, String createdBy) {
    if (Strings.isNotBlank(assignee)) {
      return AccessDecision.refused(
          ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
          "Spec '" + specId + "' is assigned to '" + assignee + "', not you.",
          "Ask " + assignee + " to make this change, or have an admin do it.");
    }
    var creator = Strings.isNotBlank(createdBy) ? createdBy : "its creator";
    return AccessDecision.refused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        "Spec '" + specId + "' is unassigned; only " + creator + " or an admin may change it.",
        "Have an admin change it, or claim it first with --assignee <you>.");
  }
}
