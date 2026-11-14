/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ResourceChecker;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.engine.WorkstationIdentity;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Where a run goes, made ready around its reservation: every lane asks {@link #check} before it
 * reserves and {@link #enter} after, so a run of a spec is refused before anything is written when
 * its spec's workspace cannot be had (R3), and lives in that workspace once it is reserved (R1). A
 * run that names no spec lives in the project's own container, which must be running, as it always
 * had to be.
 */
final class RunPlaces {

  /** The share of a pool a box keeps free: below it, no new workspace is made (D8). */
  static final double FREE_SHARE = 0.10;

  /** What a run's place was found to be before its reservation. */
  sealed interface Checked {
    RunPlace place();
  }

  /** The project's own container, running. */
  record InContainer(RunPlace.ProjectContainer place) implements Checked {}

  /** A spec's workspace, as {@link Workspaces#checkSpec} found it. */
  record InWorkspace(Workspaces.SpecCheck check) implements Checked {
    @Override
    public RunPlace place() {
      return check.place();
    }
  }

  /** Where a reserved run went in, and how: none for the project's container. */
  record Entered(RunPlace place, Optional<Workspaces.Entered> workspace, boolean stale) {
    String instance() {
      return place.instance();
    }
  }

  private final ProjectLoader projects;
  private final Workspaces workspaces;
  private final RunStore runStore;
  private final ShellExec shell;
  private final RunLauncher.Room room;

  RunPlaces(
      ProjectLoader projects,
      Workspaces workspaces,
      RunStore runStore,
      ShellExec shell,
      RunLauncher.Room room) {
    this.projects = projects;
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.runStore = runStore;
    this.shell = shell;
    this.room = room;
  }

  /**
   * Where a run of {@code project}, whose definition is {@code config}, naming {@code specId} (none
   * for a spec-less run) would go, refused before anything is reserved when it cannot: the
   * project's container when it is not running; for a spec, every refusal {@link
   * Workspaces#checkSpec} makes, and, when the spec's workspace is not running yet, a box with no
   * room for one more ({@link ErrorCode#RUNNER_BUSY}). {@code boxHandle} is this box's, whose live
   * runs count against its cap.
   */
  Checked check(String project, ProjectDefinition config, String specId, String boxHandle) {
    var place = place(project, specId);
    return switch (place) {
      case RunPlace.ProjectContainer container -> {
        projects.running(project, config);
        yield new InContainer(container);
      }
      case RunPlace.SpecWorkspace workspace -> {
        var check = checked(workspace, config);
        if (!check.running()) {
          requireRoom(check, config, boxHandle);
        }
        yield new InWorkspace(check);
      }
    };
  }

  /**
   * Puts a reserved run into the place {@code checked} found, for {@code owner}, the person it acts
   * for: a spec's workspace is reused, started when stopped, or made, and is {@code owner}'s before
   * anything of the run is staged. A workspace made from a template older than the definition says
   * so once in the spec's room, naming the build that freshens it. A box still making another
   * workspace past {@link Workspaces#CREATE_WAIT} is {@link ErrorCode#RUNNER_BUSY}; a create that
   * fails answers its own reason, and the caller releases the run.
   */
  Entered enter(Checked checked, ProjectDefinition config, String owner) {
    return switch (checked) {
      case InContainer container -> new Entered(container.place(), Optional.empty(), false);
      case InWorkspace(var check) -> {
        var entered = entered(check, config, owner);
        var stale = entered instanceof Workspaces.Made && check.stale();
        if (stale) {
          var notice = new WorkspaceTemplate.Stale(check.place().project());
          room.tell(check.place().specId(), Remedied.message(notice.reason(), notice.remedy()));
        }
        yield new Entered(check.place(), Optional.of(entered), stale);
      }
    };
  }

  private static RunPlace place(String project, String specId) {
    try {
      return RunPlace.of(project, specId);
    } catch (IllegalArgumentException tooLong) {
      throw new ApiException(ErrorCode.BAD_REQUEST, tooLong.getMessage(), tooLong);
    }
  }

  private Workspaces.SpecCheck checked(RunPlace.SpecWorkspace place, ProjectDefinition config) {
    try {
      return workspaces.checkSpec(
          place,
          ProjectProvisioner.inputs(config, WorkstationIdentity.registered()),
          projects::held);
    } catch (IllegalStateException | IllegalArgumentException refused) {
      throw ApiException.of(ErrorCode.CONFLICT, refused);
    } catch (IOException | TimeoutException e) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED, "Could not read this box's Incus: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Interrupted reading Incus.", e);
    }
  }

  private Workspaces.Entered entered(
      Workspaces.SpecCheck check, ProjectDefinition config, String owner) {
    try {
      return workspaces.enterSpec(
          check, config, owner, SailPaths.projectFilesDir(check.place().project()));
    } catch (Workspaces.Busy busy) {
      throw busy(busy);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Interrupted making a workspace.", e);
    } catch (Exception e) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "Workspace "
              + check.place().workspace()
              + " of "
              + check.place().project()
              + " could not be made ready: "
              + Remedied.reasonOf(e),
          Remedied.remedyOf(e),
          e);
    }
  }

  /**
   * Refuses one more workspace on a box with no room for it (D8): its pool below {@link
   * #FREE_SHARE} free, or as many spec workspaces holding a live run as its memory holds of the
   * project's limit, and at least one. A pool whose space Incus does not say, a project with no
   * memory limit, or a box whose memory Incus does not say, sets no bound.
   */
  private void requireRoom(Workspaces.SpecCheck check, ProjectDefinition config, String boxHandle) {
    var pool = check.template().pool();
    check
        .space()
        .filter(space -> space.total() > 0)
        .filter(space -> space.total() - space.used() < space.total() * FREE_SHARE)
        .ifPresent(
            space -> {
              throw busy(
                  "Pool "
                      + pool
                      + " has "
                      + Strings.gibibytes(space.total() - space.used())
                      + " free of "
                      + Strings.gibibytes(space.total())
                      + ", under the 10% this box keeps free, so no new workspace is made on it.",
                  Remedy.of(
                      "Free space on it, or see sudo zpool status "
                          + Objects.requireNonNullElse(pool, "")
                          + "; then dispatch again."));
            });
    var limit =
        config.resources() == null ? 0 : ResourceChecker.parseMemoryMb(config.resources().memory());
    if (limit <= 0 || runStore == null) {
      return;
    }
    hostMemoryMb()
        .ifPresent(
            memory -> {
              var cap = Math.max(1, memory / limit);
              var live = runStore.liveSpecWorkspaces(boxHandle);
              if (live >= cap) {
                throw busy(
                    "This box has "
                        + live
                        + " spec workspace"
                        + (live == 1 ? "" : "s")
                        + " with a live run, as many as its memory holds at "
                        + config.name()
                        + "'s "
                        + config.resources().memory()
                        + " each (a cap of "
                        + cap
                        + "), so no new workspace is made.",
                    Remedy.of("Dispatch again when one of their runs ends."));
              }
            });
  }

  /** The box's memory, as Incus says it, in megabytes; none when it does not say. */
  private Optional<Long> hostMemoryMb() {
    try {
      var queried = shell.observer().exec(List.of("incus", "query", "/1.0/resources"));
      if (queried.ok()
          && YamlUtil.parseMap(queried.stdout()).get("memory") instanceof Map<?, ?> m
          && m.get("total") instanceof Number total
          && total.longValue() > 0) {
        return Optional.of(total.longValue() / (1024L * 1024L));
      }
      return Optional.empty();
    } catch (IOException | TimeoutException | RuntimeException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /** {@link ErrorCode#RUNNER_BUSY} while another create holds the box's create lock. */
  private static ApiException busy(Workspaces.Busy busy) {
    return new ApiException(
        ErrorCode.RUNNER_BUSY,
        "This runner is making another workspace; dispatch again in a minute.",
        busy);
  }

  private static ApiException busy(String message, Remedy remedy) {
    return new ApiException(ErrorCode.RUNNER_BUSY, message, remedy);
  }
}
