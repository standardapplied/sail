/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
import ai.singlr.sail.store.FdeStore;
import java.util.List;

/**
 * The pre-launch admission checks every run lane runs before it reserves a container or takes a
 * snapshot: may the actor dispatch this spec, does it run on this box, is the box's FDE in the
 * synced roster, is the chosen agent a known name actually installed in the container, and is the
 * model a shell-safe token. Each refusal is an {@link ApiException} thrown before any side effect,
 * so a rejected launch never leaves a half-provisioned run. Shared by the lanes an operator starts
 * — dispatch, build and membership — so admission is decided in exactly one place.
 */
public final class LaunchAdmission {

  private final ShellExec shell;
  private final FdeStore fdeStore;

  public LaunchAdmission(ShellExec shell, FdeStore fdeStore) {
    this.shell = shell;
    this.fdeStore = fdeStore;
  }

  /**
   * Refuses when the bound actor may not seat or remove the agents of spec-less room {@code roomId}
   * of {@code project}, which {@code owner} owns ({@link Action#DISPATCH}), or when they are not
   * this box's to serve: the node handle must be set, and this box must own the room. A room its
   * caller read was read in its scope; one being created is in a project the caller names, and one
   * the caller may not read is answered as a project this box does not hold. Whether the roster may
   * change is the room rule's too, decided where it is written.
   */
  public static void requireAllowedForRoom(
      String roomId, String project, String owner, String localHandle) {
    Refusals.require(
        Access.decide(Actor.current(), Action.DISPATCH, Resources.room(roomId, project, owner)),
        () -> ProjectReader.absent(project));
    if (Strings.isBlank(localHandle)) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box has no FDE handle, so room ownership cannot be established.",
          HANDLE_HINT);
    }
    if (!Ownership.owns(localHandle, owner)) {
      throw new ApiException(
          ErrorCode.NOT_YOUR_SPEC,
          "Room '" + roomId + "' belongs to '" + owner + "', whose box serves its agents.",
          "Manage the room from that box, or have an admin reassign it.");
    }
  }

  /**
   * Refuses when the bound actor may not dispatch {@code spec} ({@link Action#DISPATCH}), its
   * assignee's, and then when it does not run on the box identified by {@code localHandle}:
   * placement, not permission. The spec is one its caller read in the actor's scope, so one outside
   * it is not found. An unassigned spec has no owner to ask, so only the role and lane are ({@link
   * Access#atAll}), and placement says it runs on no box until it is claimed, whoever asks, as main
   * said.
   *
   * <p>Placement enforces one invariant: <em>a spec executes only on the node whose handle equals
   * the spec's assignee</em>. Node identity is {@code SyncConfig.handle} — one FDE, one devbox — so
   * an FDE dispatching on their own box always matches; anyone, admin included, asking a box to run
   * another FDE's spec is refused with the owning node named. Moving work is explicit: reassign the
   * spec, then dispatch. A box with no handle can run nothing.
   */
  public static void requireAllowed(Spec spec, String localHandle) {
    var actor = Actor.current();
    if (Strings.isBlank(spec.assignee())) {
      Refusals.enforce(Access.atAll(actor, Action.DISPATCH, Resource.Kind.SPEC));
    } else {
      Refusals.require(
          Access.decide(
              actor,
              Action.DISPATCH,
              Resources.spec(spec.id(), spec.project(), spec.assignee(), spec.createdBy())),
          () -> Refusals.specNotFound(spec.id()));
    }
    if (Strings.isBlank(localHandle)) {
      throw nodeHandleUnset();
    }
    if (!localHandle.equals(spec.assignee())) {
      throw runsOnOtherNode(spec, localHandle);
    }
  }

  /** The refusal of a box with no sync-handle, which can be no spec's execution node. */
  public static ApiException nodeHandleUnset() {
    return new ApiException(
        ErrorCode.NODE_HANDLE_UNSET,
        "This node has no sync-handle, so it cannot be any spec's execution node.",
        Remedy.of(
            "Set it once: ",
            Remedy.sail("host config set sync-handle", "<handle>"),
            " (a node gets it from '",
            Remedy.sail("join"),
            "')."));
  }

  static ApiException runsOnOtherNode(Spec spec, String localHandle) {
    var claim = Remedy.sail("spec update", spec.id(), "--assignee", localHandle);
    if (Strings.isBlank(spec.assignee())) {
      return new ApiException(
          ErrorCode.RUNS_ON_OTHER_NODE,
          "Spec '" + spec.id() + "' is unassigned, so no node may dispatch it.",
          Remedy.of("Claim it first: ", claim));
    }
    return new ApiException(
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

  /**
   * Refuses dispatch when this box's FDE is missing from the synced roster or disabled there: an
   * unauthorized handle means the specs assigned to it cannot be trusted, and a run launched for a
   * disabled FDE would be refused at its every call. A box that keeps no roster ({@code fdeStore ==
   * null}) skips the check.
   */
  public void requireTrustedRoster(String localHandle) {
    if (fdeStore == null
        || fdeStore.byHandle(localHandle).filter(FdeStore.Fde::active).isPresent()) {
      return;
    }
    throw new ApiException(
        ErrorCode.FDE_NOT_IN_ROSTER,
        "FDE '"
            + localHandle
            + "' is not an active member of this box's roster, so its assigned specs cannot be"
            + " trusted.",
        Remedy.of(
            "Run '",
            Remedy.sail("sync"),
            "' to pull the roster from main, or get authorized there first."));
  }

  /**
   * Refuses the launch before any reservation or snapshot when the chosen agent's binary is not on
   * the container's PATH — the definition's agent section declares what a project apply installed,
   * but the container is the authority on what can actually launch.
   */
  public void requireInstalled(Harness harness, String project) {
    var found =
        exec(
            ContainerExec.asDevUser(
                project,
                List.of("bash", "-lc", "command -v -- \"$1\"", "bash", harness.binaryName())));
    if (!found.ok()) {
      throw notInstalled(harness, project);
    }
  }

  static final Remedy HANDLE_HINT =
      Remedy.of("Set it with: ", Remedy.sail("host config set sync-handle", "<handle>"));

  static ApiException notInstalled(Harness harness, String project) {
    return new ApiException(
        ErrorCode.AGENT_NOT_CONFIGURED,
        "Agent '" + harness.yamlName() + "' is not installed in project '" + project + "'.",
        Remedy.of(
            "Add " + harness.yamlName() + " to the agent's install list with '",
            Remedy.sail("project edit", project, "--section", "agent"),
            "', then run '",
            Remedy.sail("project apply", project),
            "'."));
  }

  /** Resolves the agent to launch, refusing an unknown or missing name as a client error. */
  public static Harness resolveAgent(String agentYamlName) {
    if (Strings.isBlank(agentYamlName)) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "Name the agent to seat.",
          "Pass agent: " + String.join(" or ", Harnesses.names()) + ".");
    }
    try {
      return Harnesses.of(agentYamlName);
    } catch (IllegalArgumentException e) {
      throw ApiException.of(ErrorCode.BAD_REQUEST, e);
    }
  }

  /**
   * {@code effort} as {@code harness} takes it: an effort it does not take is refused as an invalid
   * request naming it, as the spec door refuses it, before any reservation, never dropped (M15).
   */
  public static String requireEffort(Harness harness, String effort) {
    try {
      return harness.requireEffort(effort);
    } catch (IllegalArgumentException e) {
      throw ApiException.of(ErrorCode.INVALID_REQUEST, e);
    }
  }

  /**
   * Validates the model exactly like a spec write, refusing shell-unsafe values as a client error
   * before any reservation or snapshot — the model rides the agent command through {@code bash -l
   * -c}, so only a single safe token may reach it.
   */
  public static String validateModel(String model) {
    try {
      return Spec.validatedModel(model);
    } catch (IllegalArgumentException e) {
      throw ApiException.of(ErrorCode.INVALID_REQUEST, e);
    }
  }

  private ShellExec.Result exec(List<String> command) {
    try {
      return shell.exec(command);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed.", e);
    }
  }
}
