/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A person's doors to a workspace beside a terminal, at the CLI and the API alike: make their own,
 * start, stop or delete one. Each is decided as Enter a workspace on the workspace's owner (R10),
 * the bound actor being the caller, after the project's audience. A delete first reads what the
 * workspace holds, with the idle sweep's own reads, and is refused when it holds anything, or
 * cannot be read, unless forced.
 */
public final class WorkspaceDoors {

  private final Workspaces workspaces;
  private final WorkspaceGit git;
  private final Function<String, WorkspaceOwner> owners;

  /**
   * @param workspaces the box's workspaces
   * @param shell runs the delete's read in a workspace
   * @param owners the person, by handle, a workspace is given the credentials of
   */
  public WorkspaceDoors(
      Workspaces workspaces, ShellExec shell, Function<String, WorkspaceOwner> owners) {
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.git = new WorkspaceGit(shell);
    this.owners = Objects.requireNonNull(owners, "owners");
  }

  /**
   * Why {@code actor} may not enter workspace {@code name} of {@code project}, owned by {@code
   * owner} (none for no one's): empty when it may. A project outside the actor's audience is
   * refused as one this box does not hold.
   */
  public static Optional<Refusal> refusal(Actor actor, String project, String name, String owner) {
    return switch (Access.decide(
        actor, Action.ENTER_WORKSPACE, Resources.workspace(name, project, owner))) {
      case Decision.Allowed allowed -> Optional.empty();
      case Decision.NotFound unread -> throw ProjectReader.absent(project);
      case Decision.Refused refused -> Optional.of(refused.refusal());
    };
  }

  /**
   * {@code door}'s answer, its refusals as the API says them: a busy runner, a refusal of what the
   * workspace or the request is, and Incus failing, each with its own code.
   */
  static <T> T answered(Callable<T> door) {
    try {
      return door.call();
    } catch (ApiException | IllegalArgumentException refused) {
      throw refused;
    } catch (Workspaces.Busy busy) {
      throw new ApiException(ErrorCode.RUNNER_BUSY, busy.getMessage());
    } catch (IllegalStateException refused) {
      throw ApiException.of(ErrorCode.CONFLICT, refused);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ApiException(ErrorCode.INTERNAL, "Interrupted.", interrupted);
    } catch (Exception failed) {
      throw ApiException.of(ErrorCode.CONTAINER_ERROR, failed);
    }
  }

  /** Throws the refusal of {@link #refusal}, with its code, when there is one. */
  public static void requireEnter(Actor actor, String project, String name, String owner) {
    refusal(actor, project, name, owner).ifPresent(WorkspaceDoors::refuse);
  }

  /** Makes workspace {@code name} of {@code config}'s project, the bound actor's own. */
  public Workspaces.Created create(
      ProjectDefinition config, String name, Path files, Predicate<String> held) throws Exception {
    var actor = Actor.current();
    refusal(actor, config.name(), name, actor.handle()).ifPresent(WorkspaceDoors::refuse);
    return workspaces.createFromCurrent(config, name, files, actor.handle(), owners, held);
  }

  /** Starts workspace {@code name}, given its owner's credentials again. */
  public Workspaces.Changed start(ProjectDefinition config, String name) throws Exception {
    entering(config.name(), name);
    return workspaces.start(config, name, owners);
  }

  /** Stops workspace {@code name}, unless a run of its spec ({@code live}) lives in it. */
  public Workspaces.Changed stop(String project, String name, Predicate<String> live)
      throws Exception {
    entering(project, name);
    return workspaces.stop(project, name, live);
  }

  /**
   * Deletes workspace {@code name} once {@code confirm} returns, unless a run of its spec ({@code
   * live}) lives in it, whatever {@code force}: without it, what the workspace holds is read first
   * (a stopped one started for the read, and stopped again when the delete is refused), and the
   * delete is refused naming it, or naming why it could not be read, with {@code forced} as the
   * remedy.
   */
  public Workspaces.Changed delete(
      ProjectDefinition config,
      String name,
      Predicate<String> live,
      boolean force,
      Remedy forced,
      Consumer<String> confirm)
      throws Exception {
    var project = config.name();
    var found = entering(project, name);
    if (!force && found.key(BoxInstances.SPEC_KEY).filter(live).isEmpty()) {
      requireNothingHeld(config, name, found, live, forced);
    }
    return workspaces.delete(project, name, live, confirm);
  }

  /** Workspace {@code name}'s instance, when the bound actor may enter it; refused otherwise. */
  private BoxInstances.Instance entering(String project, String name) throws Exception {
    var found =
        workspaces
            .find(project, name)
            .orElseThrow(
                () -> ApiException.of(ErrorCode.NOT_FOUND, Workspaces.absent(project, name)));
    refusal(Actor.current(), project, name, Workspaces.ownerOf(found, owners).orElse(null))
        .ifPresent(WorkspaceDoors::refuse);
    return found;
  }

  private void requireNothingHeld(
      ProjectDefinition config,
      String name,
      BoxInstances.Instance found,
      Predicate<String> live,
      Remedy forced)
      throws Exception {
    var stopped = !(found.state() instanceof ContainerState.Running);
    if (stopped) {
      workspaces.start(config, name, owners);
    }
    String said;
    try {
      var held = git.left(found.name(), repos(config), "");
      if (held.isEmpty()) {
        return;
      }
      said =
          "W" + whose(config.name(), name, found).substring(1) + " holds work: " + held.get() + ".";
    } catch (IOException | TimeoutException unread) {
      said =
          "Whether "
              + whose(config.name(), name, found)
              + " holds work cannot be told: "
              + unread.getMessage()
              + ".";
    }
    if (stopped) {
      workspaces.stop(config.name(), name, live);
    }
    throw new ApiException(
        ErrorCode.WORKSPACE_HOLDS_WORK,
        said,
        Remedy.of("Take what it holds first, or delete it anyway: ", forced));
  }

  /** "workspace try of acme", and the owner's name beside it when the caller is not the owner. */
  private String whose(String project, String name, BoxInstances.Instance found) {
    var owner = Workspaces.ownerOf(found, owners);
    var theirs =
        owner.isPresent() && !owner.get().equals(Actor.current().handle())
            ? ", " + owner.get() + "'s,"
            : "";
    return "workspace " + name + " of " + project + theirs;
  }

  private static List<String> repos(ProjectDefinition config) {
    return Objects.requireNonNullElse(config.repos(), List.<ProjectDefinition.Repo>of()).stream()
        .map(ProjectDefinition.Repo::path)
        .toList();
  }

  private static void refuse(Refusal refusal) {
    throw Refusals.exception(refusal);
  }
}
