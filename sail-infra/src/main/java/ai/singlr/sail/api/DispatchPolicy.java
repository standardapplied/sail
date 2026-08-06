/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import java.util.Objects;

/**
 * The single domain home for dispatch authorization, shared by both lanes (the in-process {@code
 * sail spec dispatch} CLI and the HTTP API). Pure and I/O-free so it is exhaustively
 * table-testable.
 *
 * <p>It enforces one invariant above all: <em>a spec executes only on the node whose handle equals
 * the spec's assignee</em>. Node identity is {@code SyncConfig.handle} — one FDE, one devbox — so
 * an FDE dispatching on their own box (over the CLI or over a GUI client's passkey session to that
 * box) always matches; anyone, admin included, asking a box to run another FDE's spec is refused
 * with the owning node named. Moving work is explicit: reassign the spec, then dispatch.
 *
 * <p>Fails closed at every step: a box with no handle can run nothing, a read-only credential is
 * refused, and a caller whose handle does not match the spec's assignee cannot dispatch unless they
 * hold admin authority.
 */
public final class DispatchPolicy {

  private DispatchPolicy() {}

  /**
   * Decides whether {@code actor} may dispatch {@code spec} on the node identified by {@code
   * localHandle}. Rules are checked in order — node identity, execution locality, caller
   * capability, then ownership — so the most fundamental precondition names the refusal.
   */
  public static DispatchDecision check(Actor actor, Spec spec, String localHandle) {
    if (actor.agentLane()) {
      return agentLaneForbidden("dispatch specs");
    }
    if (Strings.isBlank(localHandle)) {
      return nodeHandleUnset();
    }
    if (!localHandle.equals(spec.assignee())) {
      return runsOnOtherNode(spec, localHandle);
    }
    if (!actor.canWrite()) {
      return new DispatchDecision.Refused(
          ErrorCode.READ_ONLY_CREDENTIAL,
          "Your credential is read-only and cannot dispatch specs.",
          "Ask an admin for a member or admin credential.");
    }
    if (!actor.isAdmin() && !spec.assignee().equals(actor.handle())) {
      return new DispatchDecision.Refused(
          ErrorCode.NOT_YOUR_SPEC,
          "Spec '"
              + spec.id()
              + "' is assigned to '"
              + spec.assignee()
              + "', not you ('"
              + Objects.toString(actor.handle(), "")
              + "').",
          "Ask "
              + spec.assignee()
              + " to dispatch it, or have an admin reassign it before dispatching.");
    }
    return new DispatchDecision.Allowed();
  }

  /**
   * The agent-lane refusal: a run's principal is confined to the spec/event surface, so the
   * dispatch and stop routes refuse it outright — server-side policy, rendered verbatim by clients.
   */
  static DispatchDecision.Refused agentLaneForbidden(String action) {
    return new DispatchDecision.Refused(
        ErrorCode.AGENT_LANE_FORBIDDEN,
        "Agent principals cannot " + action + ".",
        "Only an operator lane (CLI or API token) may do this.");
  }

  /** The rule-1 refusal: this box carries no sync-handle, so no spec has an execution node here. */
  public static DispatchDecision.Refused nodeHandleUnset() {
    return new DispatchDecision.Refused(
        ErrorCode.NODE_HANDLE_UNSET,
        "This node has no sync-handle, so it cannot be any spec's execution node.",
        "Set it once: sudo sail host config set sync-handle <handle> (a node gets it from"
            + " 'sail join').");
  }

  private static DispatchDecision.Refused runsOnOtherNode(Spec spec, String localHandle) {
    if (Strings.isBlank(spec.assignee())) {
      return new DispatchDecision.Refused(
          ErrorCode.RUNS_ON_OTHER_NODE,
          "Spec '" + spec.id() + "' is unassigned, so no node may dispatch it.",
          "Assign it first: sail spec update " + spec.id() + " --assignee " + localHandle);
    }
    return new DispatchDecision.Refused(
        ErrorCode.RUNS_ON_OTHER_NODE,
        "Spec '"
            + spec.id()
            + "' is assigned to '"
            + spec.assignee()
            + "', which runs on that FDE's devbox, not this node ('"
            + localHandle
            + "').",
        "Reassign it to dispatch here: sail spec update "
            + spec.id()
            + " --assignee "
            + localHandle);
  }
}
