/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.RetentionConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The one way anything is pruned — erased everywhere, for good, with everything that belongs to it
 * ({@link Erasure#closure}). The CLI, the API, Mast and {@code project destroy --purge} come
 * through {@link #prune}; main's retention through {@link #retain}; both erase through the same
 * loop.
 *
 * <p>A dry run is the erasure itself, rehearsed in a transaction that is rolled back, so it reports
 * what the real one does on this box's copy. On main, or a standalone box, the real one erases in
 * bounded transactions — each selects its targets, checks who may erase them and reads what belongs
 * to them inside its own write transaction, so a child written meanwhile goes with its parent and
 * an owner changed meanwhile is the one checked — and then collects the content it left
 * unreferenced. A node never authors an erasure: it asks main for what main acknowledged, which
 * main decides on its own copy, and discards on the spot what it alone ever held.
 *
 * <p>Authority: write capability, never an agent; for a spec named by id, its owner ({@link
 * SpecStore#ownerOf}) or an admin, and only once it is archived, cancelled or deleted with no run
 * of it still going; a policy, a project or retention, an admin only.
 */
final class SpecPruner {

  /** Who retention's erasures are attributed to in the erasure rows every box keeps. */
  static final String RETENTION_ACTOR = "sail-retention";

  private static final int BATCH = 200;

  private final Sqlite db;
  private final SpecStore specs;
  private final MessageStore messages;
  private final RunStore runs;
  private final Erasure erasure;
  private final BlobStore blobs;
  private final EventBus eventBus;
  private final BooleanSupplier authoritative;
  private final Supplier<Instant> clock;

  SpecPruner(Sqlite db, EventBus eventBus, BooleanSupplier authoritative, Supplier<Instant> clock) {
    this.db = Objects.requireNonNull(db, "db");
    this.specs = new SpecStore(db);
    this.messages = new MessageStore(db);
    this.runs = new RunStore(db);
    this.erasure = new Erasure(db);
    this.blobs = new BlobStore(db);
    this.eventBus = eventBus;
    this.authoritative = Objects.requireNonNull(authoritative, "authoritative");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Erases what {@code request} names, or, as a dry run, reports what that would erase. */
  PruneReport prune(PruneRequest request, Actor actor) {
    Objects.requireNonNull(request, "prune request");
    authorize(request, actor);
    var node = !authoritative.getAsBoolean();
    if (node && request.policy() != null) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "A policy prune runs on main, which decides it on its own copy; a node prunes specs by"
              + " id and projects.",
          "Run it on main, or name the specs: sail spec prune <id...>.");
    }
    var handle = principal(actor.handle());
    IntFunction<List<Erasure.Target>> select = limit -> roots(request, actor, limit);
    var idle = request.project() == null;
    if (request.dryRun()) {
      return rehearse(select, handle, idle);
    }
    return node ? ask(request, actor, handle, select, idle) : apply(select, handle, idle);
  }

  /**
   * Main's retention: turns {@code policy} into erasures, as {@link #RETENTION_ACTOR} — archived
   * specs past their age with no run still going, messages past theirs that no thread still needs,
   * runs finished before theirs. Returns what went; collecting the content is the sweeper's.
   */
  PruneReport retain(RetentionConfig policy) {
    if (!authoritative.getAsBoolean()) {
      throw new IllegalStateException("Retention runs on main; a node applies main's erasures.");
    }
    var now = clock.get();
    var projects = new LinkedHashSet<String>();
    var result = Erasure.Result.NONE;
    if (policy.pruneArchivedAfter() != null) {
      var cutoff = now.minus(policy.pruneArchivedAfter());
      result =
          result.plus(
              eraseAll(
                  limit ->
                      targets(
                          Erasure.SPEC,
                          specs.prunableSince(List.of(SpecStatus.ARCHIVED), null, cutoff, limit)),
                  RETENTION_ACTOR,
                  true,
                  projects));
    }
    if (policy.messages() != null) {
      var cutoff = now.minus(policy.messages());
      result =
          result.plus(
              eraseAll(
                  limit -> targets(Erasure.MESSAGE, messages.retiredBefore(cutoff, limit)),
                  RETENTION_ACTOR,
                  false,
                  projects));
    }
    if (policy.runsAfterFinished() != null) {
      var cutoff = now.minus(policy.runsAfterFinished());
      result =
          result.plus(
              eraseAll(
                  limit -> targets(Erasure.RUN, runs.finishedBefore(cutoff, limit)),
                  RETENTION_ACTOR,
                  false,
                  projects));
    }
    projects.forEach(project -> publishBoardUpdated(project, RETENTION_ACTOR));
    return PruneReport.of(result, 0, false, false);
  }

  private PruneReport rehearse(
      IntFunction<List<Erasure.Target>> select, String handle, boolean idle) {
    return db.rehearse(
        () -> {
          var before = blobs.collectable();
          var result = eraseAll(select, handle, idle, new LinkedHashSet<>());
          return PruneReport.of(result, blobs.collectable() - before, true, false);
        });
  }

  private PruneReport apply(IntFunction<List<Erasure.Target>> select, String handle, boolean idle) {
    var projects = new LinkedHashSet<String>();
    var result = eraseAll(select, handle, idle, projects);
    var freed = result.entities().isEmpty() ? 0L : collect();
    projects.forEach(project -> publishBoardUpdated(project, handle));
    return PruneReport.of(result, freed, false, false);
  }

  /**
   * A node's prune: rehearsed here for the report, then what main acknowledged is asked of main,
   * offered at the start of its type's next round, and a root that nothing main ever acknowledged
   * belongs to is discarded now — main has nothing of it to erase, and asking would publish it
   * instead. What was discarded leaves the board and its content is collected at once.
   */
  private PruneReport ask(
      PruneRequest request,
      Actor actor,
      String handle,
      IntFunction<List<Erasure.Target>> select,
      boolean idle) {
    var rehearsed = rehearse(select, handle, idle);
    var requests = new EraseRequests(db);
    var projects = new LinkedHashSet<String>();
    var discarded = new ArrayList<Erasure.Target>();
    var asked =
        db.transaction(
            () -> {
              var roots = roots(request, actor, Integer.MAX_VALUE);
              var local =
                  roots.stream()
                      .filter(
                          root ->
                              erasure.closure(List.of(root)).stream()
                                  .allMatch(erasure::unacknowledged))
                      .toList();
              projects.addAll(projectsOf(erasure.closure(local)));
              discarded.addAll(erasure.discard(local).entities());
              roots.stream()
                  .filter(root -> !local.contains(root))
                  .forEach(root -> requests.request(root.type(), root.id(), handle));
              return roots.size() > local.size();
            });
    if (!discarded.isEmpty()) {
      collect();
      projects.forEach(project -> publishBoardUpdated(project, handle));
    }
    return rehearsed.applied(asked);
  }

  /**
   * Erases in bounded transactions until {@code select} finds nothing left: each batch is selected,
   * checked and erased in one write transaction, so the lock is held for a batch, never for a whole
   * sweep. Every batch erases something new, so the loop ends.
   */
  private Erasure.Result eraseAll(
      IntFunction<List<Erasure.Target>> select, String handle, boolean idle, Set<String> projects) {
    var result = Erasure.Result.NONE;
    while (true) {
      var batch =
          db.transaction(
              () -> {
                var plan = erasure.closure(select.apply(BATCH));
                requireIdle(plan, idle);
                projects.addAll(projectsOf(plan));
                return erasure.erase(plan, handle, "local");
              });
      if (batch.entities().isEmpty()) {
        return result;
      }
      result = result.plus(batch);
    }
  }

  private long collect() {
    try {
      return blobs.gc(BlobStore.Compaction.NONE, true).freed();
    } catch (RuntimeException e) {
      System.err.println(
          "  [prune] erased, but could not collect its content; 'sail sync gc' frees it: "
              + e.getMessage());
      return 0;
    }
  }

  private static void authorize(PruneRequest request, Actor actor) {
    Objects.requireNonNull(actor, "a prune needs the authenticated actor");
    if (actor.agentLane()) {
      throw new ApiException(
          ErrorCode.AGENT_LANE_FORBIDDEN,
          "An agent cannot prune: erasing is irreversible and belongs to the FDE.",
          "Ask the FDE who owns the work to run 'sail spec prune'.");
    }
    if (!actor.canWrite()) {
      throw new ApiException(
          ErrorCode.READ_ONLY_CREDENTIAL,
          "Your role is read-only: it cannot prune.",
          "Ask an admin to prune, or for a member role.");
    }
    if (request.ids().isEmpty() && !actor.isAdmin()) {
      throw new ApiException(
          ErrorCode.FORBIDDEN_ADMIN_ONLY,
          "Pruning by policy or a whole project is admin-only.",
          "Name the specs you own by id: sail spec prune <id...>.");
    }
  }

  /**
   * Up to {@code limit} entities the request still has to erase. A spec named by id must be the
   * actor's to prune and prunable; one already pruned is nothing to do, one never recorded is not
   * found. A project is a root only when this box holds something of it, so a name nobody used is
   * never spent.
   */
  private List<Erasure.Target> roots(PruneRequest request, Actor actor, int limit) {
    var roots = new ArrayList<Erasure.Target>();
    for (var id : request.ids()) {
      var target = new Erasure.Target(Erasure.SPEC, id);
      if (erasure.isErased(target)) {
        continue;
      }
      var spec =
          specs
              .lastKnown(id)
              .orElseThrow(
                  () ->
                      new ApiException(
                          ErrorCode.SPEC_NOT_FOUND, "Spec '" + id + "' was not found."));
      SpecPolicy.mutate(actor, id, spec.assignee(), spec.createdBy()).enforce();
      if (!spec.prunable()) {
        throw new ApiException(
            ErrorCode.SPEC_NOT_PRUNABLE,
            "Spec '"
                + id
                + "' is "
                + spec.status().wire()
                + ": only archived, cancelled or deleted"
                + " specs are pruned.",
            "Archive it first: sail spec update " + id + " --status archived.");
      }
      roots.add(target);
    }
    if (request.policy() != null) {
      var policy = request.policy();
      specs
          .prunableSince(
              policy.statuses(), policy.project(), clock.get().minus(policy.olderThan()), limit)
          .forEach(id -> roots.add(new Erasure.Target(Erasure.SPEC, id)));
    }
    if (request.project() != null) {
      var project = new Erasure.Target(Erasure.PROJECT, request.project());
      if (!erasure.isErased(project) && erasure.holds(project)) {
        roots.add(project);
      }
    }
    return roots;
  }

  private void requireIdle(List<Erasure.Target> plan, boolean idle) {
    if (!idle) {
      return;
    }
    var unfinished =
        runs.unfinished(
            plan.stream()
                .filter(target -> Erasure.RUN.equals(target.type()))
                .map(Erasure.Target::id)
                .toList());
    if (!unfinished.isEmpty()) {
      throw new ApiException(
          ErrorCode.SPEC_NOT_PRUNABLE,
          "Run '"
              + unfinished.getFirst()
              + "' has not finished; a prune never erases work going on.",
          "Stop it first: sail agent stop, then prune.");
    }
  }

  private static List<Erasure.Target> targets(String type, List<String> ids) {
    return ids.stream().map(id -> new Erasure.Target(type, id)).toList();
  }

  private Set<String> projectsOf(List<Erasure.Target> plan) {
    var projects = new LinkedHashSet<String>();
    for (var target : plan) {
      if (Erasure.SPEC.equals(target.type())) {
        specs.findById(target.id()).map(SpecStore.SpecRow::project).ifPresent(projects::add);
      } else if (Erasure.PROJECT.equals(target.type())) {
        projects.add(target.id());
      }
    }
    return projects;
  }

  private void publishBoardUpdated(String project, String principal) {
    if (eventBus == null) {
      return;
    }
    eventBus.publish(
        Event.of(
            project, null, Event.WellKnownTypes.BOARD_UPDATED, principal, HostInfo.hostname()));
  }

  private static String principal(String actor) {
    return Strings.isNotBlank(actor) ? actor : Event.SAIL_AGENT;
  }
}
