/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;

/**
 * Where a spec may run, decided once the bound actor may dispatch it ({@code Action#DISPATCH}, in
 * {@link LaunchAdmission#requireAllowed}): placement, not permission. Pure and I/O-free so it is
 * exhaustively table-testable.
 *
 * <p>It enforces one invariant: <em>a spec executes only on the node whose handle equals the spec's
 * assignee</em>. Node identity is {@code SyncConfig.handle} — one FDE, one devbox — so an FDE
 * dispatching on their own box (over the CLI or over a GUI client's passkey session to that box)
 * always matches; anyone, admin included, asking a box to run another FDE's spec is refused with
 * the owning node named. Moving work is explicit: reassign the spec, then dispatch. A box with no
 * handle can run nothing.
 */
public final class DispatchPolicy {

  private DispatchPolicy() {}

  /** Whether {@code spec} runs on the node identified by {@code localHandle}, and if not, why. */
  public static DispatchDecision check(Spec spec, String localHandle) {
    if (Strings.isBlank(localHandle)) {
      return nodeHandleUnset();
    }
    if (!localHandle.equals(spec.assignee())) {
      return runsOnOtherNode(spec, localHandle);
    }
    return new DispatchDecision.Allowed();
  }

  /** The rule-1 refusal: this box carries no sync-handle, so no spec has an execution node here. */
  public static DispatchDecision.Refused nodeHandleUnset() {
    return new DispatchDecision.Refused(
        ErrorCode.NODE_HANDLE_UNSET,
        "This node has no sync-handle, so it cannot be any spec's execution node.",
        Remedy.of(
            "Set it once: ",
            Remedy.sail("host config set sync-handle", "<handle>"),
            " (a node gets it from '",
            Remedy.sail("join"),
            "')."));
  }

  static DispatchDecision.Refused runsOnOtherNode(Spec spec, String localHandle) {
    var claim = Remedy.sail("spec update", spec.id(), "--assignee", localHandle);
    if (Strings.isBlank(spec.assignee())) {
      return new DispatchDecision.Refused(
          ErrorCode.RUNS_ON_OTHER_NODE,
          "Spec '" + spec.id() + "' is unassigned, so no node may dispatch it.",
          Remedy.of("Claim it first: ", claim));
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
        Remedy.of("Reassign it to dispatch here: ", claim));
  }
}
