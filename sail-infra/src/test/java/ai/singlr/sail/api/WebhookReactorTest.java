/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Notifications;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebhookReactorTest {

  @Test
  void constructorRejectsNullResolver() {
    assertThrows(NullPointerException.class, () -> new WebhookReactor(null, url -> recorder()));
  }

  @Test
  void constructorRejectsNullSenderFactory() {
    assertThrows(NullPointerException.class, () -> new WebhookReactor(p -> null, null));
  }

  @Test
  void nameIsStable() {
    var reactor = new WebhookReactor(p -> null, url -> recorder());
    assertEquals("webhook-reactor", reactor.name());
  }

  @Test
  void filterAcceptsNotifiableTypes() {
    var reactor = new WebhookReactor(p -> null, url -> recorder());
    var filter = reactor.filter();
    for (var type : WebhookReactor.NOTIFIABLE_TYPES) {
      assertTrue(filter.test(Event.of("p", null, type, "a", "h")), "should accept " + type);
    }
  }

  @Test
  void filterRejectsUnknownTypes() {
    var reactor = new WebhookReactor(p -> null, url -> recorder());
    assertFalse(reactor.filter().test(Event.of("p", null, "agent_tool_started", "a", "h")));
    assertFalse(reactor.filter().test(Event.of("p", null, "custom_thing", "a", "h")));
  }

  @Test
  void onEventSkipsWhenResolverReturnsNull() {
    var calls = new ArrayList<String>();
    var reactor = new WebhookReactor(project -> null, url -> recorder(calls));
    reactor.onEvent(Event.of("light-grid", null, "spec_dispatched", "sail", "h").withId(1L));
    assertTrue(calls.isEmpty());
  }

  @Test
  void onEventSkipsWhenNotificationsHaveBlankUrl() {
    var calls = new ArrayList<String>();
    var notifications = new Notifications(null, List.of());
    var reactor = new WebhookReactor(project -> notifications, url -> recorder(calls));
    reactor.onEvent(Event.of("p", null, "spec_dispatched", "sail", "h").withId(1L));
    assertTrue(calls.isEmpty());
  }

  @Test
  void onEventSkipsWhenTypeNotSubscribed() {
    var calls = new ArrayList<String>();
    var notifications = new Notifications("https://example.com/wh", List.of("guardrail_triggered"));
    var reactor = new WebhookReactor(project -> notifications, url -> recorder(calls));
    reactor.onEvent(Event.of("p", null, "spec_dispatched", "sail", "h").withId(1L));
    assertTrue(calls.isEmpty());
  }

  @Test
  void onEventFiresForSubscribedType() {
    var calls = new ArrayList<String>();
    var notifications = new Notifications("https://example.com/wh", List.of("spec_dispatched"));
    var reactor = new WebhookReactor(project -> notifications, url -> recorder(calls));

    reactor.onEvent(Event.of("light", "oauth", "spec_dispatched", "claude-code", "h").withId(1L));

    assertEquals(1, calls.size());
    assertTrue(calls.getFirst().contains("spec_dispatched"));
    assertTrue(calls.getFirst().contains("light"));
  }

  @Test
  void onEventFiresWhenEventsListIsEmpty() {
    var calls = new ArrayList<String>();
    var notifications = new Notifications("https://example.com/wh", List.of());
    var reactor = new WebhookReactor(project -> notifications, url -> recorder(calls));

    reactor.onEvent(Event.of("p", null, "snapshot_created", "sail", "h").withId(1L));

    assertEquals(1, calls.size());
  }

  @Test
  void onEventRespectsLegacyEventAlias() {
    var calls = new ArrayList<String>();
    var notifications = new Notifications("https://example.com/wh", List.of("agent_exited"));
    var reactor = new WebhookReactor(project -> notifications, url -> recorder(calls));

    reactor.onEvent(Event.of("p", null, "agent_session_stopped", "claude-code", "h").withId(1L));

    assertEquals(
        1, calls.size(), "legacy 'agent_exited' should fire for new agent_session_stopped");
  }

  @Test
  void senderIsCachedPerUrl() {
    var counter = new AtomicInteger();
    var sender = new RecordingSender();
    var notifications = new Notifications("https://example.com/wh", List.of("spec_dispatched"));
    var reactor =
        new WebhookReactor(
            project -> notifications,
            url -> {
              counter.incrementAndGet();
              return sender;
            });

    reactor.onEvent(Event.of("p", null, "spec_dispatched", "sail", "h").withId(1L));
    reactor.onEvent(Event.of("p", null, "spec_dispatched", "sail", "h").withId(2L));
    reactor.onEvent(Event.of("p", null, "spec_dispatched", "sail", "h").withId(3L));

    assertEquals(1, counter.get(), "sender factory should only build one sender per URL");
    assertEquals(3, sender.calls);
  }

  @Test
  void senderCacheKeyedByUrl() {
    var built = new ConcurrentHashMap<String, Integer>();
    var notificationsFor =
        Map.of(
            "a", new Notifications("https://example.com/a", List.of("spec_dispatched")),
            "b", new Notifications("https://example.com/b", List.of("spec_dispatched")));
    var reactor =
        new WebhookReactor(
            notificationsFor::get,
            url -> {
              built.merge(url, 1, Integer::sum);
              return recorder();
            });

    reactor.onEvent(Event.of("a", null, "spec_dispatched", "sail", "h").withId(1L));
    reactor.onEvent(Event.of("a", null, "spec_dispatched", "sail", "h").withId(2L));
    reactor.onEvent(Event.of("b", null, "spec_dispatched", "sail", "h").withId(3L));

    assertEquals(1, built.get("https://example.com/a"));
    assertEquals(1, built.get("https://example.com/b"));
  }

  @Test
  void withDefaultResolverDoesNotThrow() {
    assertNotNull(WebhookReactor.withDefaultResolver());
  }

  private static WebhookSender recorder() {
    return recorder(new ArrayList<>());
  }

  private static WebhookSender recorder(List<String> log) {
    return (event, project, title, message) -> log.add(event + " " + project + " " + title);
  }

  private static final class RecordingSender implements WebhookSender {
    int calls;

    @Override
    public void send(String event, String project, String title, String message) {
      calls++;
    }
  }
}
