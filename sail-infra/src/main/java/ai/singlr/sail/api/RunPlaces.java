/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ResourceChecker;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Where a run goes, made ready around its reservation: every lane asks {@link #check} before it
 * reserves and {@link #enter} after, so a run of a spec is refused before anything is written when
 * its spec's workspace cannot be had (R3), and lives in that workspace once it is reserved (R1). A
 * run that names no spec lives in the project's own container, which must be running, as it always
 * had to be. A refusal is said in the spec's room too, in the words its caller is given, with the
 * one remedy that fits its lane ({@link #again}).
 */
final class RunPlaces {

  /** The share of a pool a box keeps free: below it, no new workspace is made (D8). */
  static final double FREE_SHARE = 0.10;

  /** What a run's place was found to be before its reservation, for a run of {@code lane}. */
  sealed interface Checked {
    RunPlace place();
  }

  /** The project's own container, running. */
  record InContainer(RunPlace.ProjectContainer place) implements Checked {}

  /**
   * A spec's workspace, as {@link Workspaces#checkSpec} found it for a run of {@code lane} on the
   * box {@code boxHandle}, for {@code owner}, the box's person, whom every run of it acts for.
   */
  record InWorkspace(Workspaces.SpecCheck check, Lane lane, String boxHandle, WorkspaceOwner owner)
      implements Checked {
    @Override
    public RunPlace.SpecWorkspace place() {
      return check.place();
    }
  }

  private final ProjectLoader projects;
  private final Workspaces workspaces;
  private final Function<String, Set<String>> liveSpecs;
  private final RunLauncher.Room room;
  private final Consumer<RunPlace> prune;
  private final Function<String, WorkspaceOwner> owners;

  /**
   * {@code liveSpecs} answers the specs a box's live runs are of, by its handle ({@link
   * RunStore#liveSpecs}): what its cap counts. {@code owners} answers a person, by handle, as the
   * owner of a workspace ({@link WorkspaceOwner#of}).
   */
  RunPlaces(
      ProjectLoader projects,
      Workspaces workspaces,
      Function<String, Set<String>> liveSpecs,
      RunLauncher.Room room,
      Consumer<RunPlace> prune,
      Function<String, WorkspaceOwner> owners) {
    this.projects = Objects.requireNonNull(projects, "projects");
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.liveSpecs = Objects.requireNonNull(liveSpecs, "liveSpecs");
    this.room = Objects.requireNonNull(room, "room");
    this.prune = Objects.requireNonNull(prune, "prune");
    this.owners = Objects.requireNonNull(owners, "owners");
  }

  /**
   * Where a run of {@code lane} of {@code project}, whose definition is {@code config}, naming
   * {@code specId} (none for a spec-less run) would go, refused before anything is reserved when it
   * cannot: the project's container when it is not running; for a spec, every refusal {@link
   * Workspaces#checkSpec} makes, and, when the spec's workspace is not running yet, a box with no
   * room for one more ({@link ErrorCode#RUNNER_BUSY}). {@code boxHandle} is this box's, whose live
   * runs count against its cap.
   */
  Checked check(
      String project, ProjectDefinition config, String specId, Lane lane, String boxHandle) {
    if (Strings.isBlank(specId)) {
      projects.running(project, config);
      return new InContainer(new RunPlace.ProjectContainer(project));
    }
    return checkSpec(project, config, specId, lane, boxHandle);
  }

  /**
   * As {@link #check}, for a run that names spec {@code specId}: its workspace, refused too when it
   * will be made or re-owned for a person it cannot be given to ({@link WorkspaceOwner#require}).
   */
  InWorkspace checkSpec(
      String project, ProjectDefinition config, String specId, Lane lane, String boxHandle) {
    try {
      var check = checked(workspaceOf(project, specId), config);
      if (!check.running()) {
        requireRoom(check, config, boxHandle);
      }
      return new InWorkspace(check, lane, boxHandle, owner(check, config, boxHandle));
    } catch (ApiException refused) {
      throw told(specId, lane, refused);
    }
  }

  /**
   * {@code handle} as the owner of {@code check}'s workspace, refused as {@link ErrorCode#CONFLICT}
   * when the workspace will be made, or is another's, and could not be theirs.
   */
  private WorkspaceOwner owner(
      Workspaces.SpecCheck check, ProjectDefinition config, String handle) {
    try {
      var owner = owners.apply(handle);
      var theirs =
          check.whole()
              && check
                  .found()
                  .flatMap(i -> i.key(BoxInstances.OWNER_KEY))
                  .orElse("")
                  .equals(handle);
      if (!theirs) {
        workspaces.requireOwner(owner, config);
      }
      return owner;
    } catch (IllegalStateException refused) {
      throw ApiException.of(ErrorCode.CONFLICT, refused);
    }
  }

  /**
   * Puts a reserved run into the place {@code checked} found, for its owner, the person it acts
   * for: a spec's workspace is reused, started when stopped, or made, and is the owner's, holding
   * their credentials alone, before anything of the run is staged; what it entered, none for the
   * project's container. A workspace a dispatch made from a template older than the definition, or
   * on a degraded mirror, says so once in the spec's room. A box still making another workspace
   * past {@link Workspaces#CREATE_WAIT} is {@link ErrorCode#RUNNER_BUSY}; a create that fails
   * answers its own reason, and the caller releases the run.
   */
  DispatchOperations.WorkspaceUse enter(Checked checked, ProjectDefinition config) {
    if (!(checked instanceof InWorkspace(var check, var lane, var boxHandle, var owner))) {
      return null;
    }
    var specId = check.place().specId();
    Workspaces.Entered entered;
    try {
      entered = entered(check, config, owner, boxHandle);
    } catch (ApiException refused) {
      throw told(specId, lane, refused);
    }
    var made = entered instanceof Workspaces.Made;
    var stale = made && check.stale();
    if (stale) {
      var notice = new WorkspaceTemplate.Stale(check.place().project());
      room.tell(specId, Remedied.message(notice.reason(), notice.remedy()));
    }
    var degraded = lane == Lane.BUILD ? check.degraded().orElse(null) : null;
    if (degraded != null) {
      room.tell(specId, degraded);
    }
    prune.accept(check.place());
    return new DispatchOperations.WorkspaceUse(
        entered.instance(),
        check.place().workspace(),
        !made,
        entered instanceof Workspaces.Made fresh ? fresh.created().timings() : null,
        stale,
        degraded);
  }

  /**
   * {@code refused} with the remedy that fits {@code lane}, said in spec {@code specId}'s room in
   * the words its caller is given ({@link RunLauncher.Room#tell}, once).
   */
  private ApiException told(String specId, Lane lane, ApiException refused) {
    var remedy = refused.failure().remedy();
    var fitted =
        new ApiException(
            refused.failure().errorCode(),
            refused.getMessage(),
            remedy == null || remedy.isEmpty()
                ? Remedy.of(again(lane))
                : Remedy.of(remedy, " ", again(lane)),
            refused.getCause());
    room.tell(specId, Remedied.message(fitted.getMessage(), fitted.failure().remedy()));
    return fitted;
  }

  /**
   * How a run of {@code lane} goes on once its refusal is mended: a person dispatches a build
   * again; a reviewer or fix is tried again by the loop itself, which hands the review to a person
   * if the refusal lasts; a room's member is engaged, or its turn taken, by the person again.
   */
  static String again(Lane lane) {
    return switch (lane) {
      case BUILD -> "Then dispatch again.";
      case REVIEW, FIX ->
          "The loop then tries again by itself, and hands the review to a person if it lasts.";
      case ROOM, ROOM_FULL, ADHOC -> "Then engage or post in the room again.";
    };
  }

  private static RunPlace.SpecWorkspace workspaceOf(String project, String specId) {
    try {
      return (RunPlace.SpecWorkspace) RunPlace.of(project, specId);
    } catch (IllegalArgumentException tooLong) {
      throw new ApiException(ErrorCode.BAD_REQUEST, tooLong.getMessage(), tooLong);
    }
  }

  private Workspaces.SpecCheck checked(RunPlace.SpecWorkspace place, ProjectDefinition config) {
    try {
      return workspaces.checkSpec(
          place, ProjectProvisioner.inputs(config, Optional.empty()), projects::held);
    } catch (Workspaces.Busy busy) {
      throw busy(busy);
    } catch (IllegalStateException | IllegalArgumentException refused) {
      throw ApiException.of(ErrorCode.CONFLICT, refused);
    } catch (IOException | TimeoutException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ApiException(
          ErrorCode.COMMAND_FAILED, "Could not read this box's Incus: " + e.getMessage(), e);
    }
  }

  private Workspaces.Entered entered(
      Workspaces.SpecCheck check,
      ProjectDefinition config,
      WorkspaceOwner owner,
      String boxHandle) {
    try {
      return workspaces.enterSpec(
          check,
          config,
          owner,
          SailPaths.projectFilesDir(check.place().project()),
          whole -> requireCapUnderLock(config, boxHandle, whole));
    } catch (Workspaces.Busy busy) {
      throw busy(busy);
    } catch (ApiException refused) {
      throw refused;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
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
   * #FREE_SHARE} free, or {@link #requireCap its memory full}. A pool whose space Incus does not
   * say sets no bound.
   */
  private void requireRoom(Workspaces.SpecCheck check, ProjectDefinition config, String boxHandle) {
    var pool = check.template().pool();
    var space = check.space().orElse(null);
    if (space != null && space.total() - space.used() < space.total() * FREE_SHARE) {
      throw busy(
          "Pool "
              + pool
              + " has "
              + Strings.gibibytes(space.total() - space.used())
              + " free of "
              + Strings.gibibytes(space.total())
              + ", under the 10% this box keeps free, so no new workspace is made on it.",
          Remedy.of("Free space on it, or see sudo zpool status " + pool + "."));
    }
    requireCap(config, () -> liveSpecs.apply(boxHandle));
  }

  /**
   * {@link #requireCap} under the create lock, counting the spec workspaces the box holds whole
   * ({@code whole}) with a live run, so two creates that each found room before their runs were
   * reserved never both make one past the cap.
   */
  private void requireCapUnderLock(ProjectDefinition config, String boxHandle, Set<String> whole) {
    requireCap(
        config,
        () -> {
          var live = new HashSet<>(liveSpecs.apply(boxHandle));
          live.retainAll(whole);
          return live;
        });
  }

  /**
   * Refuses one more workspace when the spec workspaces with a live run ({@code specs}) are as many
   * as the box's memory holds of the project's limit, and at least one. A project with no memory
   * limit sets no bound; a box whose memory reads as none holds one.
   */
  private void requireCap(ProjectDefinition config, Supplier<Set<String>> specs) {
    var limit =
        config.resources() == null ? 0 : ResourceChecker.parseMemoryMb(config.resources().memory());
    if (limit <= 0) {
      return;
    }
    var cap = Math.max(1, memoryMb() / limit);
    var live = specs.get().size();
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
          Remedy.of("Wait for one of their runs to end."));
    }
  }

  private long memoryMb() {
    try {
      return workspaces.memoryMb();
    } catch (UncheckedIOException unread) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, unread.getMessage(), unread);
    }
  }

  /**
   * {@link ErrorCode#RUNNER_BUSY} while another create holds the box's create lock, or a build the
   * template's, in the words {@code busy} says.
   */
  private static ApiException busy(Workspaces.Busy busy) {
    return new ApiException(ErrorCode.RUNNER_BUSY, busy.getMessage());
  }

  private static ApiException busy(String message, Remedy remedy) {
    return new ApiException(ErrorCode.RUNNER_BUSY, message, remedy);
  }
}
