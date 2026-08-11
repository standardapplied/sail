/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.sync.SyncTransition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure mapping from a node's synced state transition to the lifecycle events main narrates from —
 * the state-derived replacement for the events the node's own bus fired. Each mapped event carries
 * {@code data.source = "sync"} so consumers can tell narration input from local execution: the
 * Slack reactor treats a sync-sourced stop as authoritative, while the review pipeline and webhook
 * reactors skip it (the executing node owns that work). No I/O; the two lookups resolve a review's
 * spec to its project and a spec's latest review status from main's already-synced stores.
 *
 * <p>The message vocabulary is exactly today's: a dispatch roots the thread, a restart posts the
 * same re-dispatch pair the node used to, review stages carry their finding counts, and a non-zero
 * exit follows the stop with the same {@code agent_failed} detail.
 */
public final class SyncTransitionEvents {

  private static final String IN_PROGRESS = "in_progress";
  private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("completed", "stopped", "failed");
  private static final List<String> SEVERITY_ORDER = List.of("critical", "high", "medium", "low");

  private SyncTransitionEvents() {}

  public static List<Event> eventsFor(
      SyncTransition transition,
      Function<String, String> projectOfSpec,
      Function<String, String> latestReviewStatusOfSpec,
      String host) {
    return switch (transition.entityType()) {
      case "spec" -> specEvents(transition, latestReviewStatusOfSpec, host);
      case "run" -> runEvents(transition, host);
      case "review" -> reviewEvents(transition, projectOfSpec, host);
      case "review_stage" -> stageEvents(transition, projectOfSpec, host);
      case "message" -> messageEvents(transition, projectOfSpec, host);
      default -> List.of();
    };
  }

  /**
   * Only the move into {@code in_progress} narrates from the spec itself: from {@code pending} it
   * is the dispatch that roots the thread; from {@code review} with a failed (non-escalated) latest
   * review it is the pipeline's fix iteration; from anywhere else it is a restart, which posts the
   * same re-dispatch + dispatch pair the node published before Slack went main-only. Every other
   * spec move is narrated by the run or review transition that caused it.
   */
  private static List<Event> specEvents(
      SyncTransition transition, Function<String, String> latestReviewStatusOfSpec, String host) {
    var project = text(transition.snapshot(), "project");
    if (!IN_PROGRESS.equals(transition.to()) || project == null) {
      return List.of();
    }
    var from = transition.from();
    if (from == null || "pending".equals(from)) {
      return List.of(dispatched(transition, project, host));
    }
    if ("review".equals(from)
        && "failed".equals(latestReviewStatusOfSpec.apply(transition.entityId()))) {
      return List.of(
          event(
              project,
              transition.entityId(),
              "review_iteration_started",
              Event.SAIL_AGENT,
              host,
              Map.of()));
    }
    var restarted =
        event(
            project,
            transition.entityId(),
            Event.WellKnownTypes.SPEC_RESTARTED,
            Event.SAIL_AGENT,
            host,
            Map.of("note", "restarted from " + from));
    return List.of(restarted, dispatched(transition, project, host));
  }

  private static Event dispatched(SyncTransition transition, String project, String host) {
    var data = new LinkedHashMap<String, Object>();
    var branch = text(transition.snapshot(), "branch");
    if (branch != null) {
      data.put("branch", branch);
    }
    return event(
        project,
        transition.entityId(),
        Event.WellKnownTypes.SPEC_DISPATCHED,
        Event.SAIL_AGENT,
        host,
        data);
  }

  /**
   * A run reaching a terminal status is the agent's authoritative stop; a non-zero exit code also
   * carries today's {@code agent_failed} follow-up. The run id rides along so run-addressed
   * consumers can correlate — main's own run tracker ignores it, since the run belongs to the
   * pushing node.
   */
  private static List<Event> runEvents(SyncTransition transition, String host) {
    var project = text(transition.snapshot(), "project");
    if (project == null || !isTerminal(transition.to()) || isTerminal(transition.from())) {
      return List.of();
    }
    var specId = text(transition.snapshot(), "spec_id");
    var agent = Objects.requireNonNullElse(text(transition.snapshot(), "agent"), Event.SAIL_AGENT);
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.RUN_ID, transition.entityId());
    if (Event.WellKnownData.RUN_ROLE_ROOM.equals(text(transition.snapshot(), "role"))) {
      data.put(Event.WellKnownData.RUN_ROLE, Event.WellKnownData.RUN_ROLE_ROOM);
    }
    var exitCode = transition.snapshot().get("exit_code") instanceof Number n ? n.intValue() : null;
    if (exitCode != null) {
      data.put(Event.WellKnownData.EXIT_CODE, exitCode);
    }
    var stopped =
        event(project, specId, Event.WellKnownTypes.AGENT_SESSION_STOPPED, agent, host, data);
    if (exitCode == null || exitCode == 0) {
      return List.of(stopped);
    }
    var failed =
        event(
            project,
            specId,
            Event.WellKnownTypes.AGENT_FAILED,
            Event.SAIL_AGENT,
            host,
            Map.of("detail", "exit " + exitCode));
    return List.of(stopped, failed);
  }

  private static boolean isTerminal(String status) {
    return status != null && TERMINAL_RUN_STATUSES.contains(status);
  }

  private static List<Event> reviewEvents(
      SyncTransition transition, Function<String, String> projectOfSpec, String host) {
    var located = locate(transition, projectOfSpec);
    if (located == null) {
      return List.of();
    }
    return switch (transition.to()) {
      case "passed" -> List.of(located.event("review_completed", Map.of(), host));
      case "escalated" -> List.of(located.event("review_escalated", Map.of(), host));
      case "failed" -> reviewErrored(transition, located, host);
      default -> List.of();
    };
  }

  /**
   * A failed review narrates only when it failed by infrastructure error — a gate failure is
   * already told by its failing stage.
   */
  private static List<Event> reviewErrored(
      SyncTransition transition, Located located, String host) {
    var error = text(transition.snapshot(), "error");
    if (error == null) {
      return List.of();
    }
    return List.of(located.event("review_errored", Map.of("detail", error), host));
  }

  private static List<Event> stageEvents(
      SyncTransition transition, Function<String, String> projectOfSpec, String host) {
    var located = locate(transition, projectOfSpec);
    if (located == null) {
      return List.of();
    }
    var data = new LinkedHashMap<String, Object>();
    var name = text(transition.snapshot(), "name");
    if (name != null) {
      data.put("detail", name);
    }
    return switch (transition.to()) {
      case "running" -> List.of(located.event("review_stage_started", data, host));
      case "passed", "failed" -> {
        var findings = findingsData(transition.snapshot());
        if (!findings.isEmpty()) {
          data.put("findings", findings);
        }
        var type = "passed".equals(transition.to()) ? "review_stage_passed" : "review_stage_failed";
        yield List.of(located.event(type, data, host));
      }
      default -> List.of();
    };
  }

  private static List<Event> messageEvents(
      SyncTransition transition, Function<String, String> projectOfSpec, String host) {
    var located = locate(transition, projectOfSpec);
    var author = text(transition.snapshot(), "author");
    var body = text(transition.snapshot(), "body");
    if (located == null || author == null || body == null || !"posted".equals(transition.to())) {
      return List.of();
    }
    return List.of(
        messagePosted(
            located.project(), located.specId(), transition.entityId(), author, body, host));
  }

  /**
   * The sync-sourced {@code spec_message_posted} event for one synced message, shaped exactly like
   * the local post's event plus {@code data.source = "sync"}. Shared by main's push-side narration
   * and a node's pull side, so a message arriving over sync wakes the same consumers — the room
   * wake reactor above all — as one posted locally.
   */
  public static Event messagePosted(
      String project, String specId, String messageId, String author, String body, String host) {
    return event(
        project,
        specId,
        Event.WellKnownTypes.SPEC_MESSAGE_POSTED,
        author,
        host,
        Map.of("message_id", messageId, "preview", preview(body)));
  }

  /** A review-side transition addressed to its spec and project, or null when unresolvable. */
  private record Located(String project, String specId) {
    Event event(String type, Map<String, Object> data, String host) {
      return SyncTransitionEvents.event(project, specId, type, Event.SAIL_AGENT, host, data);
    }
  }

  private static Located locate(SyncTransition transition, Function<String, String> projectOfSpec) {
    var specId = text(transition.snapshot(), "spec_id");
    if (specId == null) {
      return null;
    }
    var project = projectOfSpec.apply(specId);
    return project == null ? null : new Located(project, specId);
  }

  /**
   * The synced counts keyed by severity name, re-shaped to today's event payload: lowercase keys in
   * severity order, zero and unknown severities omitted.
   */
  private static Map<String, Object> findingsData(Map<String, Object> stage) {
    if (!(stage.get("finding_counts") instanceof Map<?, ?> counts)) {
      return Map.of();
    }
    var data = new LinkedHashMap<String, Object>();
    for (var severity : SEVERITY_ORDER) {
      if (counts.get(severity.toUpperCase(Locale.ROOT)) instanceof Number n && n.intValue() > 0) {
        data.put(severity, n.intValue());
      }
    }
    return data;
  }

  private static Event event(
      String project,
      String spec,
      String type,
      String agent,
      String host,
      Map<String, Object> data) {
    var payload = new LinkedHashMap<>(data);
    payload.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_SYNC);
    return Event.of(project, spec, type, agent, host, payload);
  }

  private static String text(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static String preview(String body) {
    var normalized = body.replaceAll("\\s+", " ").strip();
    return normalized.codePointCount(0, normalized.length()) <= 160
        ? normalized
        : normalized.substring(0, normalized.offsetByCodePoints(0, 160));
  }
}
