/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationsTest {

  @Test
  void fromMapParsesUrlAndEvents() {
    var n =
        Notifications.fromMap(
            Map.of("url", "https://ntfy.sh/test", "events", List.of("agent_exited")));

    assertEquals("https://ntfy.sh/test", n.url());
    assertEquals(List.of("agent_exited"), n.events());
  }

  @Test
  void eventsListIsImmutable() {
    var mutable = new java.util.ArrayList<String>(List.of("agent_exited"));
    var n = Notifications.fromMap(Map.of("url", "https://ntfy.sh/test", "events", mutable));

    assertThrows(UnsupportedOperationException.class, () -> n.events().add("snapshot_created"));
    mutable.add("snapshot_created");
    assertEquals(List.of("agent_exited"), n.events());
  }

  @Test
  void fromMapRejectsUnknownEvent() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Notifications.fromMap(
                    Map.of("url", "https://ntfy.sh/test", "events", List.of("bad_event"))));

    assertTrue(ex.getMessage().contains("Unknown notification event"));
    assertTrue(ex.getMessage().contains("bad_event"));
  }

  @Test
  void fromMapRequiresUrl() {
    assertThrows(
        IllegalArgumentException.class, () -> Notifications.fromMap(Map.of("events", List.of())));
  }

  @Test
  void fromMapRejectsBlankUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Notifications.fromMap(Map.of("url", "  ", "events", List.of())));
  }

  @Test
  void toMapRoundTrips() {
    var original =
        new Notifications(
            "https://hooks.slack.com/services/T/B/x",
            List.of("guardrail_triggered", "session_done"));
    var map = original.toMap();
    var restored = Notifications.fromMap(map);

    assertEquals(original.url(), restored.url());
    assertEquals(original.events(), restored.events());
  }

  @Test
  void nullEventsAllowsAllEvents() {
    var n = new Notifications("https://ntfy.sh/test", null);

    assertTrue(n.shouldNotify("guardrail_triggered"));
    assertTrue(n.shouldNotify("agent_exited"));
    assertTrue(n.shouldNotify("session_done"));
  }

  @Test
  void emptyEventsAllowsAllEvents() {
    var n = new Notifications("https://ntfy.sh/test", List.of());

    assertTrue(n.shouldNotify("guardrail_triggered"));
    assertTrue(n.shouldNotify("agent_exited"));
  }

  @Test
  void configuredEventsFilters() {
    var n = new Notifications("https://ntfy.sh/test", List.of("guardrail_triggered"));

    assertTrue(n.shouldNotify("guardrail_triggered"));
    assertFalse(n.shouldNotify("agent_exited"));
    assertFalse(n.shouldNotify("session_done"));
  }

  @Test
  void legacyAgentExitedAliasMatchesNewAgentSessionStopped() {
    var n = new Notifications("https://ntfy.sh/test", List.of("agent_exited"));

    assertTrue(n.shouldNotify("agent_session_stopped"));
    assertTrue(n.shouldNotify("agent_exited"));
  }

  @Test
  void legacySessionDoneAliasMatchesNewAgentSessionCompleted() {
    var n = new Notifications("https://ntfy.sh/test", List.of("session_done"));

    assertTrue(n.shouldNotify("agent_session_completed"));
    assertTrue(n.shouldNotify("session_done"));
  }

  @Test
  void newBusTypesValidateAndFilter() {
    var n =
        new Notifications("https://ntfy.sh/test", List.of("spec_dispatched", "snapshot_created"));

    assertTrue(n.shouldNotify("spec_dispatched"));
    assertTrue(n.shouldNotify("snapshot_created"));
    assertFalse(n.shouldNotify("spec_restarted"));
  }

  @Test
  void fromMapAcceptsNewBusEventNames() {
    assertDoesNotThrow(
        () ->
            Notifications.fromMap(
                Map.of(
                    "url",
                    "https://ntfy.sh/test",
                    "events",
                    List.of("spec_dispatched", "agent_session_started"))));
  }

  @Test
  void fromMapRejectsFileScheme() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "file:///etc/passwd")));
    assertTrue(ex.getMessage().contains("scheme"));
  }

  @Test
  void fromMapRejectsFtpScheme() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "ftp://evil.com/data")));
    assertTrue(ex.getMessage().contains("scheme"));
  }

  @Test
  void fromMapRejectsLocalhostUrl() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://localhost:8080/admin")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsLoopbackIp() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://127.0.0.1:9090/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsPrivate10Network() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://10.0.0.1/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsPrivate172Network() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://172.16.0.1/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsPrivate192Network() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://192.168.1.1/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsMetadataEndpoint() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://169.254.169.254/latest/meta-data/")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapAcceptsPublicHttpsUrl() {
    assertDoesNotThrow(
        () -> Notifications.fromMap(Map.of("url", "https://hooks.slack.com/services/T/B/x")));
  }

  @Test
  void fromMapAcceptsPublicNtfyUrl() {
    assertDoesNotThrow(() -> Notifications.fromMap(Map.of("url", "https://ntfy.sh/my-topic")));
  }

  @Test
  void fromMapAcceptsPublicDiscordWebhook() {
    assertDoesNotThrow(
        () ->
            Notifications.fromMap(Map.of("url", "https://discord.com/api/webhooks/12345/abcdef")));
  }

  @Test
  void fromMapRejectsIpv6UniqueLocal_fd() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://[fd00::1]/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsIpv6UniqueLocal_fc() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://[fc00::1]/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsIpv6LinkLocal() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Notifications.fromMap(Map.of("url", "http://[fe80::1]/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void fromMapRejectsUnresolvableHostname() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Notifications.fromMap(
                    Map.of("url", "https://this-host-definitely-does-not-exist-xyz.invalid/hook")));
    assertTrue(ex.getMessage().contains("Private"));
  }
}
