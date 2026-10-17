/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/**
 * Lifecycle preconditions shared by the commands that operate on a project container. Each guard
 * inspects a live {@link ContainerState} and throws {@link IllegalStateException} with an
 * actionable message when the container is not in an acceptable state.
 */
public final class ContainerStateGuard {

  private ContainerStateGuard() {}

  public static void requireRunning(ContainerState state, String name, OperatorCommand operator) {
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw new IllegalStateException(
              "Project '"
                  + name
                  + "' is stopped. Start it with: "
                  + operator.sail("project start " + name));
      default -> requireCreated(state, name, operator);
    }
  }

  public static void requireCreated(ContainerState state, String name, OperatorCommand operator) {
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored -> {}
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(notCreated(name, operator));
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }
  }

  /** What a command told of a project with no container says. */
  public static String notCreated(String name, OperatorCommand operator) {
    return "Project '"
        + name
        + "' does not exist. Run '"
        + operator.sail("project apply <name>")
        + "' first.";
  }
}
