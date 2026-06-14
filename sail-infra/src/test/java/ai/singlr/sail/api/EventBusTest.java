/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class EventBusTest {

  @Test
  void publishStampsMonotonicId() {
    try (var bus = new EventBus()) {
      var a = bus.publish(Event.of("p", null, "t", "a", "h"));
      var b = bus.publish(Event.of("p", null, "t", "a", "h"));
      assertTrue(a.id() > 0);
      assertEquals(a.id() + 1, b.id());
    }
  }

  @Test
  void publishIncrementsPublishedCount() {
    try (var bus = new EventBus()) {
      bus.publish(Event.of("p", null, "t", "a", "h"));
      bus.publish(Event.of("p", null, "t", "a", "h"));
      assertEquals(2L, bus.publishedCount());
    }
  }

  @Test
  void publishRejectsNull() {
    try (var bus = new EventBus()) {
      assertThrows(NullPointerException.class, () -> bus.publish(null));
    }
  }

  @Test
  void subscriberReceivesMatchingEvents() throws Exception {
    try (var bus = new EventBus()) {
      var seen = new ArrayList<Event>();
      var latch = new CountDownLatch(2);
      bus.subscribe(
          subscriber(
              "test",
              EventSubscriber.all(),
              e -> {
                synchronized (seen) {
                  seen.add(e);
                }
                latch.countDown();
              }));
      bus.publish(Event.of("p", null, "t", "a", "h"));
      bus.publish(Event.of("p", null, "t", "a", "h"));
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals(2, seen.size());
    }
  }

  @Test
  void filterIsolatesSubscribers() throws Exception {
    try (var bus = new EventBus()) {
      var lightSeen = new AtomicInteger();
      var darkSeen = new AtomicInteger();
      var latch = new CountDownLatch(2);
      bus.subscribe(
          subscriber(
              "light",
              EventSubscriber.byProject("light"),
              e -> {
                lightSeen.incrementAndGet();
                latch.countDown();
              }));
      bus.subscribe(
          subscriber(
              "dark",
              EventSubscriber.byProject("dark"),
              e -> {
                darkSeen.incrementAndGet();
                latch.countDown();
              }));
      bus.publish(Event.of("light", null, "t", "a", "h"));
      bus.publish(Event.of("dark", null, "t", "a", "h"));
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals(1, lightSeen.get());
      assertEquals(1, darkSeen.get());
    }
  }

  @Test
  void byTypeFilterMatchesOnlyListedTypes() {
    var filter = EventSubscriber.byType("a", "b");
    assertTrue(filter.test(Event.of("p", null, "a", "x", "h")));
    assertTrue(filter.test(Event.of("p", null, "b", "x", "h")));
    assertFalse(filter.test(Event.of("p", null, "c", "x", "h")));
  }

  @Test
  void overflowDropsAndCountsAtSubscriberLevel() throws Exception {
    try (var bus = new EventBus()) {
      var block = new CountDownLatch(1);
      var started = new CountDownLatch(1);
      var sub =
          bus.subscribe(
              subscriber(
                  "slow",
                  EventSubscriber.all(),
                  e -> {
                    started.countDown();
                    try {
                      block.await();
                    } catch (InterruptedException ex) {
                      Thread.currentThread().interrupt();
                    }
                  }),
              2);
      assertNotNull(sub);

      bus.publish(Event.of("p", null, "t", "a", "h"));
      assertTrue(started.await(5, TimeUnit.SECONDS));

      for (var i = 0; i < 10; i++) {
        bus.publish(Event.of("p", null, "t", "a", "h"));
      }
      assertTrue(sub.droppedCount() > 0, "slow subscriber should have drops");
      block.countDown();
    }
  }

  @Test
  void subscribeRejectsNullSubscriber() {
    try (var bus = new EventBus()) {
      assertThrows(NullPointerException.class, () -> bus.subscribe(null));
    }
  }

  @Test
  void subscribeRejectsBadCapacity() {
    try (var bus = new EventBus()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> bus.subscribe(subscriber("n", EventSubscriber.all(), e -> {}), 0));
    }
  }

  @Test
  void subscribeReturnsNullWhenCapReached() {
    try (var bus = new EventBus(1)) {
      var first = bus.subscribe(subscriber("a", EventSubscriber.all(), e -> {}));
      assertNotNull(first);
      var second = bus.subscribe(subscriber("b", EventSubscriber.all(), e -> {}));
      assertNull(second);
    }
  }

  @Test
  void closedBusRejectsNewSubscriptions() {
    var bus = new EventBus();
    bus.close();
    assertThrows(
        IllegalStateException.class,
        () -> bus.subscribe(subscriber("n", EventSubscriber.all(), e -> {})));
  }

  @Test
  void closedBusSwallowsPublish() {
    var bus = new EventBus();
    bus.close();
    var result = bus.publish(Event.of("p", null, "t", "a", "h"));
    assertNotNull(result);
  }

  @Test
  void statsReflectSubscribers() {
    try (var bus = new EventBus()) {
      bus.subscribe(subscriber("alpha", EventSubscriber.all(), e -> {}), 8);
      bus.subscribe(subscriber("beta", EventSubscriber.all(), e -> {}), 16);
      bus.publish(Event.of("p", null, "t", "a", "h"));
      var stats = bus.stats();
      var names = stats.subscribers().stream().map(EventBus.SubscriberStats::name).toList();
      assertTrue(names.contains("alpha"));
      assertTrue(names.contains("beta"));
      assertEquals(1L, stats.published());
    }
  }

  @Test
  void closingSubscriptionStopsDelivery() throws Exception {
    try (var bus = new EventBus()) {
      var seen = new AtomicInteger();
      var sub =
          bus.subscribe(subscriber("once", EventSubscriber.all(), e -> seen.incrementAndGet()));
      sub.close();
      Thread.sleep(250);
      bus.publish(Event.of("p", null, "t", "a", "h"));
      Thread.sleep(150);
      assertEquals(0, seen.get());
    }
  }

  @Test
  void subscriberExceptionIsIsolated() throws Exception {
    try (var bus = new EventBus()) {
      var seen = new CountDownLatch(2);
      bus.subscribe(
          subscriber(
              "boom",
              EventSubscriber.all(),
              e -> {
                seen.countDown();
                throw new RuntimeException("boom");
              }));
      bus.publish(Event.of("p", null, "t", "a", "h"));
      bus.publish(Event.of("p", null, "t", "a", "h"));
      assertTrue(seen.await(5, TimeUnit.SECONDS));
    }
  }

  private static EventSubscriber subscriber(
      String name, Predicate<Event> filter, Consumer<Event> sink) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Predicate<Event> filter() {
        return filter;
      }

      @Override
      public void onEvent(Event event) {
        sink.accept(event);
      }
    };
  }
}
