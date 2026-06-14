/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookNotifierTest {

  @Test
  void detectsNtfyFromUrl() {
    assertEquals(
        WebhookNotifier.Provider.NTFY,
        WebhookNotifier.detectProvider("https://ntfy.sh/singlr-acme"));
  }

  @Test
  void detectsSelfHostedNtfy() {
    assertEquals(
        WebhookNotifier.Provider.NTFY,
        WebhookNotifier.detectProvider("https://ntfy.example.com/alerts"));
  }

  @Test
  void detectsSlackFromUrl() {
    assertEquals(
        WebhookNotifier.Provider.SLACK,
        WebhookNotifier.detectProvider("https://hooks.slack.com/services/T123/B456/abc"));
  }

  @Test
  void detectsDiscordFromUrl() {
    assertEquals(
        WebhookNotifier.Provider.DISCORD,
        WebhookNotifier.detectProvider("https://discord.com/api/webhooks/123456/abcdef"));
  }

  @Test
  void detectsGenericFallback() {
    assertEquals(
        WebhookNotifier.Provider.GENERIC,
        WebhookNotifier.detectProvider("https://example.com/webhook"));
  }

  @Test
  void buildPayloadNtfy() {
    var payload =
        WebhookNotifier.buildPayload(
            WebhookNotifier.Provider.NTFY,
            "guardrail_triggered",
            "acme-health",
            "Guardrail: max_duration",
            "Agent exceeded 4h limit.");

    assertTrue(payload.contains("\"topic\":\"acme-health\""));
    assertTrue(payload.contains("\"title\":\"Guardrail: max_duration\""));
    assertTrue(payload.contains("\"message\":\"Agent exceeded 4h limit.\""));
    assertTrue(payload.contains("\"priority\":4"));
    assertTrue(payload.contains("\"tags\":[\"warning\"]"));
  }

  @Test
  void buildPayloadSlack() {
    var payload =
        WebhookNotifier.buildPayload(
            WebhookNotifier.Provider.SLACK,
            "agent_exited",
            "acme-health",
            "Agent exited",
            "Process is no longer running.");

    assertTrue(payload.contains("\"text\":\"Agent exited\\nProcess is no longer running.\""));
    assertFalse(payload.contains("\"event\""));
  }

  @Test
  void buildPayloadDiscord() {
    var payload =
        WebhookNotifier.buildPayload(
            WebhookNotifier.Provider.DISCORD,
            "session_done",
            "acme-health",
            "Watch complete",
            "Session ended.");

    assertTrue(payload.contains("\"content\":\"Watch complete\\nSession ended.\""));
    assertFalse(payload.contains("\"text\""));
  }

  @Test
  void buildPayloadGeneric() {
    var payload =
        WebhookNotifier.buildPayload(
            WebhookNotifier.Provider.GENERIC,
            "guardrail_triggered",
            "acme-health",
            "Guardrail: max_duration",
            "Agent running for 5h (limit: 4h).");

    assertTrue(payload.contains("\"event\":\"guardrail_triggered\""));
    assertTrue(payload.contains("\"project\":\"acme-health\""));
    assertTrue(payload.contains("\"title\":\"Guardrail: max_duration\""));
    assertTrue(payload.contains("\"message\":\"Agent running for 5h (limit: 4h).\""));
    assertTrue(payload.contains("\"timestamp\":"));
  }

  @Test
  void payloadEscapesTabsAndCarriageReturns() {
    var payload =
        WebhookNotifier.buildPayload(
            WebhookNotifier.Provider.GENERIC, "test", "proj", "Title", "col1\tcol2\r\nline2");

    assertTrue(payload.contains("\\t"));
    assertTrue(payload.contains("\\r"));
    assertFalse(payload.contains("\t"));
    assertFalse(payload.contains("\r"));
  }

  @Test
  void constructorRejectsFileScheme() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new WebhookNotifier("file:///etc/passwd"));
    assertTrue(ex.getMessage().contains("http"));
  }

  @Test
  void constructorRejectsFtpScheme() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new WebhookNotifier("ftp://evil.com/data"));
    assertTrue(ex.getMessage().contains("http"));
  }

  @Test
  void constructorAcceptsHttpsUrl() {
    assertDoesNotThrow(() -> new WebhookNotifier("https://hooks.slack.com/services/T/B/x"));
  }

  @Test
  void redactedUrlKeepsSecretPathOutOfLogs() {
    var redacted =
        WebhookNotifier.redactedUrl("https://hooks.slack.com/services/T123/B456/secret-token");

    assertEquals("https://hooks.slack.com/...", redacted);
    assertFalse(redacted.contains("secret-token"));
    assertFalse(redacted.contains("T123"));
  }

  @Test
  void payloadEscapesSpecialCharacters() {
    var payload =
        WebhookNotifier.buildPayload(
            WebhookNotifier.Provider.SLACK,
            "test",
            "proj",
            "Title with \"quotes\"",
            "Line1\nLine2");

    assertTrue(payload.contains("\\\"quotes\\\""));
    assertTrue(payload.contains("Line1\\nLine2"));
  }

  @Test
  void notifyRefusesToSendWhenHostResolvesPrivateAtSendTime() {
    var notifier = new WebhookNotifier("http://127.0.0.1:9/hook");
    var captured = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      notifier.notify("guardrail_triggered", "acme", "Title", "Message");
    } finally {
      System.setErr(originalErr);
    }
    var logged = captured.toString(StandardCharsets.UTF_8);
    assertTrue(logged.contains("refusing to send"), logged);
    assertFalse(logged.contains("127.0.0.1:9/hook"), "Must not log the unredacted URL");
  }
}
