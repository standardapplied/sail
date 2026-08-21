/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.FdeStore;
import java.util.List;

/**
 * The pre-launch admission checks every run lane runs before it reserves a container or takes a
 * snapshot: is the actor allowed to act on this spec from this box, is the box's FDE in the synced
 * roster, is the chosen agent a known name actually installed in the container, and is the model a
 * shell-safe token. Each refusal is an {@link ApiException} thrown before any side effect, so a
 * rejected launch never leaves a half-provisioned run. Shared by the dispatch, ad-hoc, room,
 * invite, and engagement lanes so admission is decided in exactly one place.
 */
public final class LaunchAdmission {

  private final ShellExec shell;
  private final FdeStore fdeStore;

  public LaunchAdmission(ShellExec shell, FdeStore fdeStore) {
    this.shell = shell;
    this.fdeStore = fdeStore;
  }

  /**
   * Refuses when the actor may not act on {@code spec} from the box identified by {@code
   * localHandle}.
   */
  public static void requireAllowed(Actor actor, Spec spec, String localHandle) {
    if (DispatchPolicy.check(actor, spec, localHandle)
        instanceof DispatchDecision.Refused refused) {
      throw new ApiException(refused.code(), refused.message(), refused.fix());
    }
  }

  /**
   * Refuses dispatch when this box's FDE handle is missing from the synced roster: an unauthorized
   * handle means the specs assigned to it cannot be trusted. A box that keeps no roster ({@code
   * fdeStore == null}) skips the check.
   */
  public void requireTrustedRoster(String localHandle) {
    if (fdeStore == null || fdeStore.byHandle(localHandle).isPresent()) {
      return;
    }
    throw new ApiException(
        ErrorCode.FDE_NOT_IN_ROSTER,
        "FDE '"
            + localHandle
            + "' is not in this box's roster, so its assigned specs cannot be trusted.",
        "Run 'sail sync' to pull the roster from main, or get authorized there first.");
  }

  /**
   * Refuses the launch before any reservation or snapshot when the chosen agent's binary is not on
   * the container's PATH — sail.yaml's agent block declares what a project apply installed, but the
   * container is the authority on what can actually launch.
   */
  public void requireInstalled(AgentCli agentCli, String project) {
    var found =
        exec(
            ContainerExec.asDevUser(
                project,
                List.of("bash", "-lc", "command -v -- \"$1\"", "bash", agentCli.binaryName())));
    if (!found.ok()) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED,
          "Agent '" + agentCli.yamlName() + "' is not installed in project '" + project + "'.",
          "Add "
              + agentCli.yamlName()
              + " to sail.yaml's agent.install list and run 'sail project apply'.");
    }
  }

  /** Resolves the agent to launch, refusing an unknown or missing name as a client error. */
  public static AgentCli resolveAgent(String agentYamlName) {
    if (Strings.isBlank(agentYamlName)) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "An invite must name the agent to launch.",
          "Pass agent: claude-code or codex.");
    }
    try {
      return AgentCli.fromYamlName(agentYamlName);
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
