/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;

/**
 * Lifecycle preconditions shared by the commands that operate on a project container. Each guard
 * inspects a live {@link ContainerState} and throws {@link IllegalStateException} with an
 * actionable message when the container is not in an acceptable state.
 */
public final class ContainerStateGuard {

  private ContainerStateGuard() {}

  public static void requireRunning(ContainerState state, String name) {
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw Remedied.state(
              "Project '" + name + "' is stopped.",
              Remedy.of("Start it with: ", Remedy.sail("project start", name)));
      default -> requireCreated(state, name);
    }
  }

  public static void requireCreated(ContainerState state, String name) {
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored -> {}
      case ContainerState.NotCreated ignored -> throw notCreated(name);
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }
  }

  /** What a command told of a project with no container says. */
  public static IllegalStateException notCreated(String name) {
    return Remedied.state(
        "Project '" + name + "' does not exist.",
        Remedy.of("Run '", Remedy.sail("project apply", "<name>"), "' first."));
  }
}
