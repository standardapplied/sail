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
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A person's doors to a workspace beside a terminal, at the CLI and the API alike: make their own,
 * start, stop or delete one. Each is decided as Enter a workspace on the workspace's owner (R10),
 * the bound actor being the caller, after the project's audience, on the instance the workspace's
 * locked entry found. On a node, a workspace nobody owns is its keeper's, the node's own person.
 */
public final class WorkspaceDoors {

  private final Workspaces workspaces;
  private final WorkspaceHoldings holdings;
  private final Function<String, WorkspaceOwner> owners;
  private final SessionYield sessions;
  private final String keeper;

  /**
   * @param workspaces the box's workspaces
   * @param shell runs the delete's read in a workspace
   * @param owners the person, by handle, a workspace is given the credentials of
   * @param sessions ends the terminals of a workspace stopped or deleted with force, saying why
   * @param keeper the node's own person on a node; null on main or a standalone box
   */
  public WorkspaceDoors(
      Workspaces workspaces,
      ShellExec shell,
      Function<String, WorkspaceOwner> owners,
      SessionYield sessions,
      String keeper) {
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.holdings = new WorkspaceHoldings(shell);
    this.owners = Objects.requireNonNull(owners, "owners");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.keeper = keeper;
  }

  /**
   * Why {@code actor} may not enter workspace {@code name} of {@code project}, owned by {@code
   * owner} (none for no one's): empty when it may. On a node ({@code keeper} its own person, null
   * elsewhere) a workspace no one owns is the keeper's, and a person the node caps is told their
   * workspaces live on main. A project outside the actor's audience is refused as one this box does
   * not hold.
   */
  public static Optional<Refusal> refusal(
      Actor actor, String project, String name, String owner, String keeper) {
    var decided = owner == null ? keeper : owner;
    return switch (Access.decide(
        actor, Action.ENTER_WORKSPACE, Resources.workspace(name, project, decided))) {
      case Decision.Allowed allowed -> Optional.empty();
      case Decision.NotFound unread -> throw ProjectReader.absent(project);
      case Decision.Refused refused -> Optional.of(onTheNode(refused.refusal(), actor, keeper));
    };
  }

  /** Throws the refusal of {@link #refusal}, with its code, when there is one. */
  public static void requireEnter(
      Actor actor, String project, String name, String owner, String keeper) {
    refusal(actor, project, name, owner, keeper).ifPresent(WorkspaceDoors::refuse);
  }

  /**
   * The refusal a person a node caps to a viewer is given there: where their workspaces live,
   * rather than that their credential reads alone.
   */
  private static Refusal onTheNode(Refusal refusal, Actor actor, String keeper) {
    if (keeper == null
        || refusal.kind() != Refusal.Kind.READ_ONLY
        || keeper.equals(actor.handle())) {
      return refusal;
    }
    return new Refusal(
        Refusal.Kind.READ_ONLY,
        "This box is "
            + keeper
            + "'s node, where only "
            + keeper
            + " makes or enters a workspace; another person's workspaces live on main.",
        "Make yours on main: POST /v1/projects/<project>/workspaces on main's API.");
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
    } catch (Workspaces.Absent absent) {
      throw ApiException.of(ErrorCode.NOT_FOUND, absent);
    } catch (IllegalStateException refused) {
      throw ApiException.of(ErrorCode.CONFLICT, refused);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ApiException(ErrorCode.INTERNAL, "Interrupted.", interrupted);
    } catch (Exception failed) {
      throw ApiException.of(ErrorCode.CONTAINER_ERROR, failed);
    }
  }

  /**
   * Makes workspace {@code name} of {@code config}'s project, the bound actor's own: a credential
   * that names no person makes none.
   */
  public Workspaces.Created create(
      ProjectDefinition config, String name, Path files, Predicate<String> held) throws Exception {
    var actor = Actor.current();
    if (Strings.isBlank(actor.handle())) {
      throw new ApiException(
          ErrorCode.FORBIDDEN,
          "This credential names no person, and a workspace is made for the person who asks.");
    }
    requireEnter(actor, config.name(), name, actor.handle(), keeper);
    return workspaces.createFromCurrent(config, name, files, actor.handle(), owners, held);
  }

  /** Starts workspace {@code name}, given its owner's credentials again. */
  public Workspaces.Changed start(ProjectDefinition config, String name) throws Exception {
    return workspaces.start(config, name, owners, found -> entering(config.name(), name, found));
  }

  /** Starts workspace {@code name} for a shell, and answers its instance. */
  public String startExisting(ProjectDefinition config, String name) throws Exception {
    return workspaces.startExisting(
        config, name, owners, found -> entering(config.name(), name, found));
  }

  /**
   * Stops workspace {@code name}, unless a run of its spec ({@code live}) lives in it; its open
   * terminals are told who stopped it.
   */
  public Workspaces.Changed stop(String project, String name, Predicate<String> live)
      throws Exception {
    return workspaces.stop(
        project,
        name,
        live,
        found -> decide(project, name, found),
        found -> endTerminals(project, name, "stopped"));
  }

  /**
   * Deletes workspace {@code name} once {@code confirm} returns, unless a run of its spec ({@code
   * live}) lives in it, whatever {@code force}, all under the workspace's lock. Without force, what
   * the workspace holds is read first ({@link WorkspaceHoldings}, a stopped one started for the
   * read and stopped again when the delete is refused), and the delete is refused naming it, with
   * {@code forced} as the remedy; with it, its open terminals are told who deleted it.
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
    return workspaces.delete(
        project,
        name,
        live,
        found -> decide(project, name, found),
        found -> {
          if (!force) {
            workspaces.whileRunning(
                found,
                running ->
                    holdings.requireNothingIn(
                        config, running, whose(project, name, found), forced));
          }
          confirm.accept(found.name());
          if (force) {
            endTerminals(project, name, "deleted");
          }
        });
  }

  /** Refuses the bound actor workspace {@code found} unless they may enter it. */
  private void decide(String project, String name, BoxInstances.Instance found) {
    requireEnter(
        Actor.current(), project, name, Workspaces.ownerOf(found, owners).orElse(null), keeper);
  }

  /** As {@link #decide}, and refused when its owner is no longer active here, so none is given. */
  private void entering(String project, String name, BoxInstances.Instance found) {
    decide(project, name, found);
    Workspaces.goneOwner(found, owners)
        .ifPresent(
            gone -> {
              throw goneOwner(project, name, gone);
            });
  }

  /**
   * What a start of workspace {@code name} labelled for {@code gone}, no longer active here, is
   * refused with, and how to be rid of it meanwhile.
   */
  static IllegalStateException goneOwner(String project, String name, String gone) {
    return Remedied.state(
        "Workspace '"
            + name
            + "' of "
            + project
            + " is "
            + gone
            + "'s, who is no longer active here, so it is not started: the next pass makes it"
            + " nobody's.",
        Remedy.of(
            "Delete it meanwhile with: ",
            Remedy.sail("project workspace delete", project, name, "--force"),
            "."));
  }

  /**
   * Ends the open terminals of workspace {@code name}, each told the workspace was {@code done} and
   * by whom. A host that cannot be told leaves them to end with the workspace, and that is said on
   * the server's log.
   */
  private void endTerminals(String project, String name, String done) {
    var who = Objects.requireNonNullElse(Actor.current().handle(), "an admin");
    try {
      sessions.endIn(
          project,
          name,
          "Workspace " + name + " of " + project + " was " + done + " by " + who + ".");
    } catch (IOException untold) {
      System.err.println(
          "  [workspaces] the terminals of workspace "
              + name
              + " of "
              + project
              + " end without being told why: "
              + Remedied.reasonOf(untold));
    }
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

  private static void refuse(Refusal refusal) {
    throw Refusals.exception(refusal);
  }
}
