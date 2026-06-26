/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Test support for the event bus. Delivery happens on a per-subscriber drain thread, so a test must
 * wait for processing rather than sleep. {@link #latching} wraps the real subscriber and counts a
 * latch down after each {@code onEvent} — even on failure — so a test can block until the
 * subscriber-under-test has actually handled the event, without a fixed sleep.
 */
final class BusTesting {

  /**
   * How long to wait for a subscriber to drain a published event. Generous because delivery rides a
   * virtual thread that can be starved under a loaded CI runner; the normal path returns in
   * milliseconds, so this ceiling is only reached when delivery is genuinely broken.
   */
  static final long DELIVERY_TIMEOUT_SECONDS = 30;

  private BusTesting() {}

  /** Blocks until the latch fires, failing if the bus does not deliver within the timeout. */
  static void awaitDelivery(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError("bus did not deliver within " + DELIVERY_TIMEOUT_SECONDS + "s");
    }
  }

  static EventSubscriber latching(EventSubscriber delegate, CountDownLatch latch) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return delegate.name();
      }

      @Override
      public Predicate<Event> filter() {
        return delegate.filter();
      }

      @Override
      public void onEvent(Event event) throws Exception {
        try {
          delegate.onEvent(event);
        } finally {
          latch.countDown();
        }
      }
    };
  }
}
