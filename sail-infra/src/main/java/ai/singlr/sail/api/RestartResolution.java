/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import java.util.List;
import java.util.Optional;

/**
 * The single domain home for dispatch restart resolution, shared by both lanes (the in-process
 * {@code sail spec dispatch --restart} CLI and the HTTP API's {@code restart} field). Pure and
 * I/O-free — it never touches stores, git, or shells — so the full decision matrix is exhaustively
 * table-testable, exactly like {@link DispatchPolicy}.
 *
 * <p>{@link #decide} answers one question: given the requested spec (or its absence) and the {@code
 * restart} flag, how must dispatch treat the spec's lifecycle status? Either a {@link Refused} with
 * the structured code both lanes surface verbatim, a {@link NotRestarted} (dispatch proceeds as a
 * normal pending dispatch), or a {@link Restarted} instructing the executor to reset the spec to
 * pending, publish {@code spec_restarted} with the status it held before the reset, and land on the
 * prior branch. A refusal's fix names the command that re-dispatches the spec ({@link #redispatch})
 * as a {@link Remedy}, so each door renders it for its reader: the CLI as the command to type, the
 * API as its verb and arguments with the command as data.
 *
 * <p>{@link #branchCheckoutArgs} is the branch half of the same decision: a restart force-checks
 * out the prior branch when it exists ({@code checkout -f}, so a dirty tree left by the previous
 * run cannot abort the re-dispatch) and creates it fresh otherwise, while a non-restart dispatch
 * fails loud on a collision, pointing the caller at the re-dispatch. {@link #freshBranchArgs} cuts
 * a brand-new work branch off the latest fetched base so an agent never inherits a stale local
 * checkout of {@code main}/{@code master}.
 */
public sealed interface RestartResolution
    permits RestartResolution.Refused, RestartResolution.NotRestarted, RestartResolution.Restarted {

  record Refused(ErrorCode code, String message, Remedy fix) implements RestartResolution {}

  record NotRestarted() implements RestartResolution {}

  record Restarted(String previousStatus) implements RestartResolution {}

  static RestartResolution decide(String specId, Spec spec, boolean restart) {
    if (Strings.isBlank(specId)) {
      if (!restart) {
        return new NotRestarted();
      }
      return new Refused(
          ErrorCode.INVALID_REQUEST,
          "The restart option requires an explicit spec id to identify which spec to restart.",
          Remedy.of("Set the spec id of the non-pending spec to re-dispatch."));
    }
    if (spec == null) {
      return new Refused(
          ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found.", Remedy.NONE);
    }
    if (spec.status() == SpecStatus.PENDING) {
      return new NotRestarted();
    }
    if (!restart) {
      return new Refused(
          ErrorCode.SPEC_NOT_READY,
          "Spec '"
              + specId
              + "' is not pending (current status: "
              + spec.status().wire()
              + "). A spec is dispatched only when pending.",
          Remedy.of(
              "To dispatch it again, run ",
              redispatch(spec.project(), specId),
              " (this resets its status to pending and records the restart as a lifecycle"
                  + " event)."));
    }
    return new Restarted(spec.status().wire());
  }

  /** The command that dispatches {@code specId} of {@code project} again, whatever its status. */
  static Remedy.Command redispatch(String project, String specId) {
    return Remedy.sail("spec dispatch", "-p", project, "--spec", specId, "--restart");
  }

  /**
   * The checkout onto {@code branchName}, refused on a collision when not restarting; the refusal
   * names {@code redispatch} when the run has a spec to re-dispatch onto the branch.
   */
  static List<String> branchCheckoutArgs(
      String repoDir,
      String branchName,
      boolean branchExists,
      boolean restart,
      Optional<Remedy.Command> redispatch) {
    if (branchExists && !restart) {
      throw new ApiException(
          ErrorCode.BRANCH_CREATE_FAILED,
          "Branch '" + branchName + "' already exists.",
          redispatch
              .map(
                  command ->
                      Remedy.of("To dispatch onto it, run ", command, ", or delete it first."))
              .orElse(Remedy.of("Delete it first.")));
    }
    return branchExists
        ? List.of("git", "-C", repoDir, "checkout", "-f", branchName)
        : List.of("git", "-C", repoDir, "checkout", "-b", branchName);
  }

  /**
   * The git command to cut a fresh work branch. When {@code originBaseAvailable}, it forks from
   * {@code origin/<base>} — the just-fetched upstream tip — so an agent never inherits a stale
   * local checkout of the base branch; otherwise (no remote, offline, or a detached base) it forks
   * from the current {@code HEAD}, the prior behaviour, so a dispatch on a box that cannot reach
   * origin still proceeds rather than failing.
   */
  static List<String> freshBranchArgs(
      String repoDir, String branchName, String base, boolean originBaseAvailable) {
    return originBaseAvailable
        ? List.of("git", "-C", repoDir, "checkout", "-b", branchName, "origin/" + base)
        : List.of("git", "-C", repoDir, "checkout", "-b", branchName);
  }
}
