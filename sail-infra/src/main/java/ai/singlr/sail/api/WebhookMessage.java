/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.Objects;

/**
 * Pure formatting from an {@link Event} into a webhook title + message pair. No I/O; pure function
 * so it can be unit-tested without spinning up the bus or HTTP server.
 */
public record WebhookMessage(String title, String message) {

  public WebhookMessage {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(message, "message");
  }

  /** Builds a webhook payload for the given event. Recognizes the well-known sail event types. */
  public static WebhookMessage forEvent(Event event) {
    Objects.requireNonNull(event, "event");
    return switch (event.type()) {
      case Event.WellKnownTypes.SPEC_DISPATCHED -> dispatched(event);
      case Event.WellKnownTypes.SPEC_RESTARTED -> restarted(event);
      case Event.WellKnownTypes.AGENT_SESSION_STARTED -> sessionStarted(event);
      case Event.WellKnownTypes.AGENT_SESSION_STOPPED -> sessionStopped(event);
      case Event.WellKnownTypes.AGENT_SESSION_COMPLETED -> sessionCompleted(event);
      case Event.WellKnownTypes.SNAPSHOT_CREATED -> snapshotCreated(event);
      case Event.WellKnownTypes.GUARDRAIL_TRIGGERED -> guardrailTriggered(event);
      default -> generic(event);
    };
  }

  private static WebhookMessage dispatched(Event event) {
    var detail = withSpec(event, "Dispatched to agent " + event.agent());
    return new WebhookMessage("Spec dispatched: " + event.project(), detail);
  }

  private static WebhookMessage restarted(Event event) {
    var note = stringFromData(event, "note");
    var detail =
        withSpec(event, note.isBlank() ? "Spec restarted." : "Spec restarted (" + note + ").");
    return new WebhookMessage("Spec restarted: " + event.project(), detail);
  }

  private static WebhookMessage sessionStarted(Event event) {
    var pid = stringFromData(event, "pid");
    var detail =
        withSpec(
            event,
            "Agent " + event.agent() + " started" + (pid.isBlank() ? "." : " (pid " + pid + ")."));
    return new WebhookMessage("Agent started: " + event.project(), detail);
  }

  private static WebhookMessage sessionStopped(Event event) {
    var note = stringFromData(event, "note");
    var detail =
        withSpec(
            event,
            "Agent " + event.agent() + " stopped" + (note.isBlank() ? "." : " (" + note + ")."));
    return new WebhookMessage("Agent stopped: " + event.project(), detail);
  }

  private static WebhookMessage sessionCompleted(Event event) {
    var detail = withSpec(event, "Agent " + event.agent() + " completed its session.");
    return new WebhookMessage("Agent completed: " + event.project(), detail);
  }

  private static WebhookMessage snapshotCreated(Event event) {
    var label = stringFromData(event, "label");
    var detail = label.isBlank() ? "Snapshot created." : "Snapshot '" + label + "' created.";
    return new WebhookMessage("Snapshot: " + event.project(), detail);
  }

  private static WebhookMessage guardrailTriggered(Event event) {
    var reason = stringFromData(event, "reason");
    var action = stringFromData(event, "action");
    var sb = new StringBuilder("Guardrail triggered for ").append(event.project());
    if (!reason.isBlank()) {
      sb.append(". Reason: ").append(reason);
    }
    if (!action.isBlank()) {
      sb.append(". Action: ").append(action);
    }
    sb.append('.');
    return new WebhookMessage("Guardrail: " + event.project(), sb.toString());
  }

  private static WebhookMessage generic(Event event) {
    return new WebhookMessage(
        event.type() + ": " + event.project(),
        withSpec(event, "Event " + event.type() + " from " + event.agent() + "."));
  }

  private static String withSpec(Event event, String detail) {
    return event.spec() == null ? detail : "Spec " + event.spec() + ". " + detail;
  }

  private static String stringFromData(Event event, String key) {
    var raw = event.data().get(key);
    return raw == null ? "" : raw.toString();
  }
}
