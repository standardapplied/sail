/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process pub/sub bus for {@link Event}s. Lock-light and lossy-by-design: publishers never
 * block, subscribers have bounded queues, overflow is counted and dropped.
 *
 * <p>One drain virtual thread per subscriber pulls from that subscriber's queue and invokes {@link
 * EventSubscriber#onEvent(Event)}. Subscribers are independent — a slow subscriber affects only its
 * own queue. The bus also assigns a monotonic {@link Event#id()} so consumers can detect gaps and
 * reconnecting SSE clients can resume.
 */
public final class EventBus implements AutoCloseable {

  /** Default per-subscriber queue capacity. */
  public static final int DEFAULT_CAPACITY = 1024;

  private final CopyOnWriteArrayList<SubscriptionImpl> subscriptions = new CopyOnWriteArrayList<>();
  private final AtomicLong sequence = new AtomicLong();
  private final LongAdder published = new LongAdder();
  private final BoundedVirtualExecutor drainExecutor;
  private final java.util.concurrent.ExecutorService dedicatedDrainPool;
  private volatile boolean closed;

  /** Constructs a bus with up to 256 concurrent subscriber drain threads. */
  public EventBus() {
    this(256);
  }

  /**
   * @param maxSubscribers cap on simultaneously-active drain threads (= cap on subscribers).
   *     Exceeding this on {@link #subscribe} returns null.
   */
  public EventBus(int maxSubscribers) {
    this.drainExecutor = new BoundedVirtualExecutor(maxSubscribers);
    this.dedicatedDrainPool = null;
  }

  /** Publishes an event to all matching subscribers. Stamps an {@link Event#id()}. */
  public Event publish(Event event) {
    Objects.requireNonNull(event, "event");
    if (closed) {
      return event;
    }
    var stamped = event.withId(sequence.incrementAndGet());
    published.increment();
    for (var sub : subscriptions) {
      if (!sub.subscriber.filter().test(stamped)) {
        continue;
      }
      if (!sub.queue.offer(stamped)) {
        sub.dropped.increment();
      }
    }
    return stamped;
  }

  /**
   * Registers a subscriber with the default queue capacity. Returns {@code null} if the drain
   * executor cap is reached (treat as 503-equivalent at the caller).
   */
  public Subscription subscribe(EventSubscriber subscriber) {
    return subscribe(subscriber, DEFAULT_CAPACITY);
  }

  /** Registers a subscriber with the given queue capacity. */
  public Subscription subscribe(EventSubscriber subscriber, int queueCapacity) {
    Objects.requireNonNull(subscriber, "subscriber");
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("queueCapacity must be positive, got " + queueCapacity);
    }
    if (closed) {
      throw new IllegalStateException("EventBus is closed");
    }
    var sub = new SubscriptionImpl(subscriber, queueCapacity);
    var accepted = drainExecutor.tryRun(sub::drainLoop);
    if (!accepted) {
      return null;
    }
    subscriptions.add(sub);
    return sub;
  }

  /** Total number of events published. */
  public long publishedCount() {
    return published.sum();
  }

  /** Current per-subscriber stats. */
  public Stats stats() {
    var subs = new ArrayList<SubscriberStats>(subscriptions.size());
    for (var sub : subscriptions) {
      subs.add(
          new SubscriberStats(
              sub.subscriber.name(), sub.queueCapacity, sub.queue.size(), sub.dropped.sum()));
    }
    return new Stats(publishedCount(), drainExecutor.rejectedCount(), subs);
  }

  @Override
  public void close() {
    closed = true;
    for (var sub : subscriptions) {
      sub.close();
    }
    subscriptions.clear();
    drainExecutor.close();
    if (dedicatedDrainPool != null) {
      dedicatedDrainPool.close();
    }
  }

  /** Handle returned by {@link #subscribe}. Close to unregister and stop the drain thread. */
  public interface Subscription extends AutoCloseable {
    /** Subscriber name. */
    String name();

    /** Current queue depth (events waiting to be drained). */
    int queueDepth();

    /** Total events dropped for this subscriber. */
    long droppedCount();

    @Override
    void close();
  }

  /** Per-subscriber snapshot for the {@code /v1/events/stats} endpoint. */
  public record SubscriberStats(String name, int capacity, int depth, long dropped) {}

  /** Bus-level snapshot. */
  public record Stats(
      long published, long rejectedSubscribers, List<SubscriberStats> subscribers) {}

  private final class SubscriptionImpl implements Subscription {
    final EventSubscriber subscriber;
    final ArrayBlockingQueue<Event> queue;
    final LongAdder dropped = new LongAdder();
    final int queueCapacity;
    volatile boolean active = true;

    SubscriptionImpl(EventSubscriber subscriber, int queueCapacity) {
      this.subscriber = subscriber;
      this.queueCapacity = queueCapacity;
      this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    void drainLoop() {
      while (active) {
        Event event;
        try {
          event = queue.poll(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (event == null) {
          continue;
        }
        try {
          subscriber.onEvent(event);
        } catch (Exception e) {
          System.err.println(
              "sail event bus: subscriber '" + subscriber.name() + "' threw: " + e.getMessage());
        }
      }
    }

    @Override
    public String name() {
      return subscriber.name();
    }

    @Override
    public int queueDepth() {
      return queue.size();
    }

    @Override
    public long droppedCount() {
      return dropped.sum();
    }

    @Override
    public void close() {
      active = false;
      subscriptions.remove(this);
    }
  }
}
