/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.LoginStore;
import java.util.List;
import java.util.Optional;

/**
 * Whether an agent starts in a workspace, and with whose login: the one decision a terminal Create
 * of an agent and the route that says what can start in a workspace both make, each in the same
 * words and codes.
 */
final class AgentStarts {

  private AgentStarts() {}

  /**
   * Why an agent does not start in workspace {@code workspace}, {@code person}'s (null for no
   * one's), for {@code asker}: empty when it is the asker's own.
   */
  static Optional<ApiException> notTheirs(String workspace, String person, String asker) {
    if (person != null && person.equals(asker)) {
      return Optional.empty();
    }
    return Optional.of(
        new ApiException(
            ErrorCode.NOT_YOUR_WORKSPACE,
            person == null
                ? workspace + " is no one's workspace, so no agent starts in it."
                : workspace
                    + " is "
                    + person
                    + "'s workspace, so an agent starts in it only as "
                    + person
                    + ", with "
                    + person
                    + "'s login.",
            Remedy.of("Open a shell in it, or start the agent in a workspace of your own.")));
  }

  /**
   * Why {@code harness} does not start in a workspace of {@code config}'s project: empty when the
   * project's agent installation lists it.
   */
  static Optional<ApiException> notInstalled(ProjectDefinition config, Harness harness) {
    var installs =
        Optional.ofNullable(config.agent())
            .map(ProjectDefinition.Agent::installs)
            .orElse(List.of());
    if (installs.contains(harness.yamlName())) {
      return Optional.empty();
    }
    var project = config.name();
    return Optional.of(
        new ApiException(
            ErrorCode.HARNESS_NOT_INSTALLED,
            harness.displayName()
                + " is not installed in "
                + project
                + "'s workspaces; its agent installation lists "
                + (installs.isEmpty()
                    ? "none"
                    : String.join(
                        ", ",
                        installs.stream().map(Harnesses::of).map(Harness::displayName).toList()))
                + ".",
            Remedy.of(
                "An admin adds "
                    + harness.yamlName()
                    + " to "
                    + project
                    + "'s agent install list and builds its template again.")));
  }

  /**
   * The default login of {@code person}, of FDE id {@code fdeId}, for {@code harness}, which an
   * agent session of it in workspace {@code workspace} starts signed in with.
   *
   * @throws ApiException {@link ErrorCode#LOGIN_NOT_STORED} with the remedy that stores one, or
   *     that makes one of their logins the default
   */
  static LoginStore.Login login(
      FdeStore fdes, String fdeId, String person, Harness harness, String workspace) {
    return RunLogins.stored(
        fdes,
        fdeId,
        person,
        harness,
        "a " + harness.displayName() + " session cannot start in " + workspace,
        "");
  }
}
