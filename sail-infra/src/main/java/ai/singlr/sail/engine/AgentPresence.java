/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Whether a run's agent is there: the reading every caller shares that acts on an agent being
 * absent with nothing else to go on — the stop that records an agent already gone, the sweep that
 * finishes a run nobody reported, the watcher whose unit's manager went silent or that finds no
 * session to watch, the launch that releases a claim. It is the container's own answer ({@link
 * AgentSession#presence}) and, where the container gave none, incus's: a container that is stopped,
 * or does not exist, runs no agent. A container that is running and merely ran no command, or one
 * incus cannot report on, leaves the question open. How a unit that answers ended — its state and
 * exit code — is its manager's to say ({@link AgentSession#answeredExitStatus}), which is what a
 * watcher publishes a run's stop on.
 */
public final class AgentPresence {

  private final AgentSession session;
  private final ContainerManager containers;
  private final BoxInstances instances;

  public AgentPresence(ShellExec shell) {
    this.session = new AgentSession(shell);
    this.containers = new ContainerManager(shell);
    this.instances = new BoxInstances(shell);
  }

  /** Whether the run's agent is known gone: the one answer anything is finished or freed on. */
  public boolean gone(RunPlace place, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    return of(place, unit) instanceof AgentSession.Presence.Gone;
  }

  /**
   * Whether the agent of a run that lives in {@code place} is there: its instance's answer, or,
   * where it gave none, incus's word on the instance, the project's own container through the door
   * that refuses any other instance of its name (W10), a spec's workspace by its own name.
   */
  public AgentSession.Presence of(RunPlace place, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var inside = session.presence(place.instance(), unit);
    if (!(inside instanceof AgentSession.Presence.Unanswered)) {
      return inside;
    }
    return switch (state(place)) {
      case ContainerState.Stopped stopped -> new AgentSession.Presence.Gone();
      case ContainerState.NotCreated absent -> new AgentSession.Presence.Gone();
      case ContainerState.Running running -> inside;
      case ContainerState.Frozen frozen -> inside;
      case ContainerState.Error unknown -> inside;
    };
  }

  private ContainerState state(RunPlace place)
      throws IOException, InterruptedException, TimeoutException {
    return switch (place) {
      case RunPlace.ProjectContainer container -> containers.queryState(container.project());
      case RunPlace.SpecWorkspace workspace -> workspaceState(workspace);
    };
  }

  /** A spec's workspace's state as incus lists it by name, an error where it does not answer. */
  private ContainerState workspaceState(RunPlace.SpecWorkspace workspace)
      throws InterruptedException {
    try {
      return instances
          .lookup(workspace.instance(), Duration.ofSeconds(BoxInstances.LOOKUP_SECONDS))
          .map(BoxInstances.Instance::state)
          .orElse(new ContainerState.NotCreated());
    } catch (IOException | TimeoutException unanswered) {
      return new ContainerState.Error(unanswered.getMessage());
    }
  }
}
