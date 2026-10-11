/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Counts what a bus offers its subscribers against what they have handled, so a test waits for the
 * bus to go quiet instead of sleeping: {@link #settle} returns once every {@link #counted}
 * subscriber has handled every event offered to it so far.
 */
final class Settling {

  private final AtomicLong offered = new AtomicLong();
  private final AtomicLong handled = new AtomicLong();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition idle = lock.newCondition();

  /** Blocks until every counted subscriber has handled every event published so far. */
  void settle() {
    lock.lock();
    try {
      var remaining = TimeUnit.SECONDS.toNanos(BusTesting.DELIVERY_TIMEOUT_SECONDS);
      while (offered.get() != handled.get()) {
        if (remaining <= 0) {
          throw new AssertionError(
              "the loop did not settle: " + offered.get() + " offered, " + handled.get());
        }
        remaining = idle.awaitNanos(remaining);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while the loop settled", e);
    } finally {
      lock.unlock();
    }
  }

  /** {@code delegate}, with every event it is offered and every one it handles counted. */
  EventSubscriber counted(EventSubscriber delegate) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return delegate.name();
      }

      @Override
      public Predicate<Event> filter() {
        return event -> {
          var matches = delegate.filter().test(event);
          if (matches) {
            offered.incrementAndGet();
          }
          return matches;
        };
      }

      @Override
      public void onEvent(Event event) throws Exception {
        try {
          delegate.onEvent(event);
        } finally {
          lock.lock();
          try {
            handled.incrementAndGet();
            idle.signalAll();
          } finally {
            lock.unlock();
          }
        }
      }
    };
  }
}
