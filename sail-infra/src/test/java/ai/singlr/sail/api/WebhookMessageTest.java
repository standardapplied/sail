/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookMessageTest {

  @Test
  void recordRejectsNulls() {
    assertThrows(NullPointerException.class, () -> new WebhookMessage(null, "m"));
    assertThrows(NullPointerException.class, () -> new WebhookMessage("t", null));
  }

  @Test
  void dispatchedIncludesProjectAndAgent() {
    var event = Event.of("light-grid", "oauth-flow", "spec_dispatched", "claude-code", "host-01");
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.title().contains("light-grid"));
    assertTrue(message.message().contains("oauth-flow"));
    assertTrue(message.message().contains("claude-code"));
  }

  @Test
  void restartedIncludesNoteWhenPresent() {
    var event =
        Event.of(
            "light-grid",
            "oauth-flow",
            "spec_restarted",
            "sail",
            "host-01",
            Map.of("note", "restarted from in_progress"));
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.message().contains("restarted from in_progress"));
  }

  @Test
  void agentSessionStartedIncludesPid() {
    var event =
        Event.of(
            "light-grid",
            "oauth-flow",
            "agent_session_started",
            "claude-code",
            "host-01",
            Map.of("pid", 42));
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.message().contains("pid 42"));
  }

  @Test
  void agentSessionStartedHandlesMissingPid() {
    var event = Event.of("p", null, "agent_session_started", "claude-code", "host-01");
    var message = WebhookMessage.forEvent(event);
    assertFalse(message.message().contains("pid"));
  }

  @Test
  void agentSessionStoppedIncludesNote() {
    var event =
        Event.of("p", "s", "agent_session_stopped", "claude-code", "h", Map.of("note", "exit 0"));
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.message().contains("exit 0"));
  }

  @Test
  void agentSessionCompletedRecognizedAsCompletion() {
    var event = Event.of("p", "s", "agent_session_completed", "sail", "h");
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.title().contains("completed"));
  }

  @Test
  void snapshotCreatedIncludesLabel() {
    var event =
        Event.of("p", null, "snapshot_created", "sail", "h", Map.of("label", "snap-20260521"));
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.message().contains("snap-20260521"));
  }

  @Test
  void snapshotCreatedHandlesMissingLabel() {
    var event = Event.of("p", null, "snapshot_created", "sail", "h");
    var message = WebhookMessage.forEvent(event);
    assertEquals("Snapshot created.", message.message());
  }

  @Test
  void guardrailTriggeredIncludesReasonAndAction() {
    var event =
        Event.of(
            "p",
            null,
            "guardrail_triggered",
            "sail",
            "h",
            Map.of("reason", "max_duration", "action", "stop"));
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.message().contains("max_duration"));
    assertTrue(message.message().contains("stop"));
  }

  @Test
  void genericTypeFallsBackGracefully() {
    var event = Event.of("p", null, "custom_event", "claude-code", "h");
    var message = WebhookMessage.forEvent(event);
    assertTrue(message.title().startsWith("custom_event"));
    assertTrue(message.message().contains("custom_event"));
  }

  @Test
  void withoutSpecOmitsSpecPrefix() {
    var event = Event.of("p", null, "spec_dispatched", "sail", "h");
    var message = WebhookMessage.forEvent(event);
    assertFalse(message.message().startsWith("Spec "));
  }

  @Test
  void forEventRejectsNull() {
    assertThrows(NullPointerException.class, () -> WebhookMessage.forEvent(null));
  }
}
