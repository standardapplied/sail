/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.function.Predicate;

/**
 * An in-process consumer of {@link Event}s. Subscribers register with the {@link EventBus} and run
 * their {@link #onEvent(Event)} on a dedicated drain virtual thread — sequential within one
 * subscriber, parallel across subscribers.
 *
 * <p>Implementations must not block indefinitely; their queue is bounded and slow drains cause
 * drops (counted in {@link EventBus.Stats}).
 */
public interface EventSubscriber {

  /** Subscriber name for stats / debugging. */
  String name();

  /** Returns the predicate used to decide whether an event is delivered to this subscriber. */
  Predicate<Event> filter();

  /** Called once per delivered event, on the subscriber's drain thread. */
  void onEvent(Event event) throws Exception;

  /** Convenience filter that accepts every event. */
  static Predicate<Event> all() {
    return e -> true;
  }

  /** Filter accepting only events whose type matches one of the given names. */
  static Predicate<Event> byType(String... types) {
    var set = java.util.Set.of(types);
    return e -> set.contains(e.type());
  }

  /** Filter accepting only events for a given project. */
  static Predicate<Event> byProject(String project) {
    return e -> project.equals(e.project());
  }
}
