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

  /** Requires the container to be running; throws otherwise. */
  public static void requireRunning(ContainerState state, String name) {
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' is stopped. Start it with: sail project start " + name);
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' does not exist. Run 'sail project create' first.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }
  }

  /** Requires the container to exist (running or stopped); throws otherwise. */
  public static void requireCreated(ContainerState state, String name) {
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored -> {}
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' does not exist. Run 'sail project create' first.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }
  }
}
