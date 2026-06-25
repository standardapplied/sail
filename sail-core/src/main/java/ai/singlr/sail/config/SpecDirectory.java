/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec selection and board-summary logic over a list of specs read from the control-plane database:
 * which spec is ready to dispatch next, which are blocked by unmet dependencies, and the kanban
 * counts. Pure functions over the given list — no file or database I/O.
 */
public final class SpecDirectory {

  /** Statuses an engineer may assign by hand via {@code sail spec status}. */
  public static final Set<SpecStatus> CLI_SETTABLE =
      Set.of(SpecStatus.PENDING, SpecStatus.IN_PROGRESS, SpecStatus.REVIEW, SpecStatus.DONE);

  public record Summary(
      Map<String, Integer> counts, int readyCount, int blockedCount, String nextReadyId) {

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("counts", counts);
      map.put("ready_count", readyCount);
      map.put("blocked_count", blockedCount);
      if (nextReadyId != null) {
        map.put("next_ready_id", nextReadyId);
      }
      return map;
    }
  }

  private SpecDirectory() {}

  /**
   * Returns the first pending spec whose dependencies are all done and whose assignee matches the
   * given identity (or is unassigned). Returns {@code null} if no spec is ready.
   *
   * @param specs ordered list of specs
   * @param assignee the engineer's identity to match (nullable for any assignee)
   */
  public static Spec nextReady(List<Spec> specs, String assignee) {
    var doneIds = doneIds(specs);

    for (var spec : specs) {
      if (spec.status() != SpecStatus.PENDING) {
        continue;
      }
      if (!dependenciesMet(spec, doneIds)) {
        continue;
      }
      if (assignee != null && spec.assignee() != null && !assignee.equals(spec.assignee())) {
        continue;
      }
      return spec;
    }
    return null;
  }

  /**
   * Returns the first pending spec whose dependencies are all done, regardless of assignee. Returns
   * {@code null} if no spec is ready.
   */
  public static Spec nextReady(List<Spec> specs) {
    return nextReady(specs, null);
  }

  /**
   * Strict FDE-aware selection: the first ready spec (dependencies all done) whose {@code assignee}
   * equals {@code assignee} exactly. Unassigned specs and specs owned by another FDE are skipped,
   * so dispatch runs only this box's own work — never unscoped or someone else's. Returns {@code
   * null} when {@code assignee} is null (no FDE bound) or nothing matches.
   */
  public static Spec nextReadyAssignedTo(List<Spec> specs, String assignee) {
    if (assignee == null) {
      return null;
    }
    var doneIds = doneIds(specs);
    for (var spec : specs) {
      if (spec.status() != SpecStatus.PENDING) {
        continue;
      }
      if (!dependenciesMet(spec, doneIds)) {
        continue;
      }
      if (!assignee.equals(spec.assignee())) {
        continue;
      }
      return spec;
    }
    return null;
  }

  /** Returns the spec with the given id, or null if not found. */
  public static Spec findById(List<Spec> specs, String specId) {
    return specs.stream().filter(spec -> spec.id().equals(specId)).findFirst().orElse(null);
  }

  /** Returns a copy of the specs list with the given spec's status updated. */
  public static List<Spec> updateStatus(List<Spec> specs, String specId, SpecStatus newStatus) {
    requireSettableStatus(newStatus);
    if (findById(specs, specId) == null) {
      throw new IllegalArgumentException("Spec '" + specId + "' not found");
    }
    return specs.stream()
        .map(
            spec ->
                spec.id().equals(specId)
                    ? new Spec(
                        spec.id(),
                        spec.project(),
                        spec.title(),
                        newStatus,
                        spec.assignee(),
                        spec.dependsOn(),
                        spec.repos(),
                        spec.agent(),
                        spec.model(),
                        spec.reasoningEffort(),
                        spec.branch())
                    : spec)
        .toList();
  }

  /** Returns true if the spec is pending and all dependencies are done. */
  public static boolean isReady(List<Spec> specs, Spec spec) {
    if (spec.status() != SpecStatus.PENDING) {
      return false;
    }
    var doneIds = doneIds(specs);
    return dependenciesMet(spec, doneIds);
  }

  /** Returns true if the spec is pending and is blocked by unmet dependencies. */
  public static boolean isBlocked(List<Spec> specs, Spec spec) {
    if (spec.status() != SpecStatus.PENDING || spec.dependsOn().isEmpty()) {
      return false;
    }
    var doneIds = doneIds(specs);
    return !dependenciesMet(spec, doneIds);
  }

  /** Returns the list of unmet dependency ids for the given spec. */
  public static List<String> unmetDependencies(List<Spec> specs, Spec spec) {
    var doneIds = doneIds(specs);
    return spec.dependsOn().stream().filter(dep -> !doneIds.contains(dep)).toList();
  }

  /** Returns readiness and blocked-state summary information for a board. */
  public static Summary summarize(List<Spec> specs) {
    var readyCount = 0;
    var blockedCount = 0;
    for (var spec : specs) {
      if (isReady(specs, spec)) {
        readyCount++;
      } else if (isBlocked(specs, spec)) {
        blockedCount++;
      }
    }
    var nextReady = nextReady(specs);
    return new Summary(
        statusCounts(specs), readyCount, blockedCount, nextReady != null ? nextReady.id() : null);
  }

  /** Returns a map of status counts: {pending: N, in_progress: N, review: N, done: N}. */
  public static Map<String, Integer> statusCounts(List<Spec> specs) {
    var counts = new LinkedHashMap<String, Integer>();
    counts.put(SpecStatus.PENDING.wire(), 0);
    counts.put(SpecStatus.IN_PROGRESS.wire(), 0);
    counts.put(SpecStatus.REVIEW.wire(), 0);
    counts.put(SpecStatus.DONE.wire(), 0);
    for (var spec : specs) {
      counts.merge(spec.status().wire(), 1, Integer::sum);
    }
    return counts;
  }

  /** Validates that a status may be assigned by hand via CLI. */
  public static void requireSettableStatus(SpecStatus status) {
    if (!CLI_SETTABLE.contains(status)) {
      throw new IllegalArgumentException(
          "Invalid spec status: '"
              + status.wire()
              + "'. Must be one of: "
              + CLI_SETTABLE.stream()
                  .map(SpecStatus::wire)
                  .sorted()
                  .collect(Collectors.joining(", ")));
    }
  }

  private static Set<String> doneIds(List<Spec> specs) {
    return specs.stream()
        .filter(s -> s.status() == SpecStatus.DONE)
        .map(Spec::id)
        .collect(Collectors.toSet());
  }

  private static boolean dependenciesMet(Spec spec, Set<String> doneIds) {
    return spec.dependsOn().stream().allMatch(doneIds::contains);
  }
}
