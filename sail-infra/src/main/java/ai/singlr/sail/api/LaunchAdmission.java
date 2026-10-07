/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

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
 * snapshot: is the actor allowed to act on this spec from this box, is the box's FDE in the synced
 * roster, is the chosen agent a known name actually installed in the container, and is the model a
 * shell-safe token. Each refusal is an {@link ApiException} thrown before any side effect, so a
 * rejected launch never leaves a half-provisioned run. Shared by the lanes an operator starts —
 * dispatch, build and membership — so admission is decided in exactly one place.
 */
public final class LaunchAdmission {

  private final ShellExec shell;
  private final FdeStore fdeStore;

  public LaunchAdmission(ShellExec shell, FdeStore fdeStore) {
    this.shell = shell;
    this.fdeStore = fdeStore;
  }

  /**
   * Refuses when a spec-less room's agents are not this box's to manage: no agent lane, node handle
   * set, and this box owns the room (assignee, else creator). Whether the actor may change the room
   * is the room rule's, decided where its roster is written.
   */
  public static void requireAllowedForRoom(String roomId, String owner, String localHandle) {
    var actor = Actor.current();
    if (actor.agentLane()) {
      throw new ApiException(
          ErrorCode.FORBIDDEN,
          "Agent credentials cannot manage room membership.",
          "Ask the engineer in the room to do it.");
    }
    if (Strings.isBlank(localHandle)) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box has no FDE handle, so room ownership cannot be established.",
          "Set it with: sail host config set sync-handle <handle>");
    }
    if (!Ownership.owns(localHandle, owner)) {
      throw new ApiException(
          ErrorCode.NOT_YOUR_SPEC,
          "Room '" + roomId + "' belongs to '" + owner + "', whose box serves its agents.",
          "Manage the room from that box, or have an admin reassign it.");
    }
  }

  /**
   * Refuses when the actor may not act on {@code spec} from the box identified by {@code
   * localHandle}.
   */
  public static void requireAllowed(Spec spec, String localHandle) {
    if (DispatchPolicy.check(spec, localHandle) instanceof DispatchDecision.Refused refused) {
      throw new ApiException(refused.code(), refused.message(), refused.fix());
    }
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
        "Run 'sudo sail sync' to pull the roster from main, or get authorized there first.");
  }

  /**
   * Refuses the launch before any reservation or snapshot when the chosen agent's binary is not on
   * the container's PATH — sail.yaml's agent block declares what a project apply installed, but the
   * container is the authority on what can actually launch.
   */
  public void requireInstalled(Harness harness, String project) {
    var found =
        exec(
            ContainerExec.asDevUser(
                project,
                List.of("bash", "-lc", "command -v -- \"$1\"", "bash", harness.binaryName())));
    if (!found.ok()) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED,
          "Agent '" + harness.yamlName() + "' is not installed in project '" + project + "'.",
          "Add "
              + harness.yamlName()
              + " to sail.yaml's agent.install list and run 'sail project apply'.");
    }
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
      throw new ApiException(ErrorCode.BAD_REQUEST, e.getMessage());
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
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
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
