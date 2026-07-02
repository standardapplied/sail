/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SpecStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure formatting from an {@link Event} into Slack message text (mrkdwn). No I/O; pure functions so
 * every lifecycle message can be unit-tested without a bus or HTTP.
 */
final class SlackMessage {

  private SlackMessage() {}

  /**
   * Builds the message text for the given event. {@code spec} is the spec row when known — it
   * supplies the title and assigned agent for root messages; dispatch events themselves are emitted
   * by the orchestrator, not the coding agent.
   */
  static String forEvent(Event event, SpecStore.SpecRow spec) {
    return switch (event.type()) {
      case Event.WellKnownTypes.SPEC_DISPATCHED -> root(event, spec, "dispatched");
      case Event.WellKnownTypes.SPEC_RESTARTED -> root(event, spec, "re-dispatched");
      case Event.WellKnownTypes.AGENT_SESSION_STOPPED -> stopped(event);
      case Event.WellKnownTypes.AGENT_FAILED ->
          ":x: Agent failed (" + detailOr(event, "no exit code") + ").";
      case Event.WellKnownTypes.GUARDRAIL_TRIGGERED -> guardrail(event);
      case "review_stage_started" -> ":mag: Review started: " + detailOr(event, "review") + ".";
      case "review_stage_passed" ->
          ":white_check_mark: Review stage passed: "
              + detailOr(event, "review")
              + findingsSuffix(event)
              + ".";
      case "review_stage_failed" ->
          ":x: Review stage failed: " + detailOr(event, "review") + findingsSuffix(event) + ".";
      case "review_completed" -> ":tada: Review passed — spec done.";
      case "review_errored" -> ":warning: Review errored: " + detailOr(event, "unknown error");
      case "review_iteration_started" -> ":wrench: Fix iteration started.";
      case "review_escalated" ->
          ":rotating_light: Escalated — iterations exhausted, this spec needs a human.";
      default -> "Event " + event.type() + " from " + event.agent() + ".";
    };
  }

  private static String root(Event event, SpecStore.SpecRow spec, String verb) {
    var sb = new StringBuilder(":rocket: Spec *");
    sb.append(Objects.toString(event.spec(), "unknown")).append("*");
    if (spec != null && spec.title() != null) {
      sb.append(" — ").append(spec.title());
    }
    sb.append(" ").append(verb).append("\nproject `").append(event.project()).append("`");
    var branch = stringFromData(event, "branch");
    if (!branch.isBlank()) {
      sb.append(" · branch `").append(branch).append("`");
    }
    var agent = spec != null && spec.agent() != null ? spec.agent() : event.agent();
    sb.append(" · agent `").append(agent).append("`");
    return sb.toString();
  }

  private static String stopped(Event event) {
    var sb = new StringBuilder("Agent ").append(event.agent()).append(" stopped");
    var exitCode = event.data().get(Event.WellKnownData.EXIT_CODE);
    if (exitCode != null) {
      sb.append(" (exit ").append(exitCode).append(")");
    }
    var note = stringFromData(event, "note");
    if (!note.isBlank()) {
      sb.append(" — ").append(note);
    }
    return sb.append(".").toString();
  }

  private static String guardrail(Event event) {
    var sb = new StringBuilder(":no_entry: Guardrail triggered");
    var reason = stringFromData(event, "reason");
    if (!reason.isBlank()) {
      sb.append(". Reason: ").append(reason);
    }
    var action = stringFromData(event, "action");
    if (!action.isBlank()) {
      sb.append(". Action: ").append(action);
    }
    return sb.append(".").toString();
  }

  private static String findingsSuffix(Event event) {
    if (!(event.data().get("findings") instanceof Map<?, ?> counts)) {
      return " (no findings)";
    }
    var parts = new ArrayList<String>();
    for (var severity : List.of("critical", "high", "medium", "low")) {
      var count = counts.get(severity);
      if (count != null) {
        parts.add(count + " " + severity);
      }
    }
    return parts.isEmpty() ? " (no findings)" : " — " + String.join(", ", parts);
  }

  private static String detailOr(Event event, String fallback) {
    var detail = stringFromData(event, "detail");
    return detail.isBlank() ? fallback : detail;
  }

  private static String stringFromData(Event event, String key) {
    return Objects.toString(event.data().get(key), "");
  }
}
