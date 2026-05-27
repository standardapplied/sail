/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SessionStore;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * EventBus subscriber that tracks agent sessions in the database. Creates a session record on
 * {@code agent_session_started} and completes it on {@code agent_session_stopped} or {@code
 * agent_session_completed}.
 */
public final class SessionTracker implements EventSubscriber {

  private static final Set<String> HANDLED_TYPES =
      Set.of(
          Event.WellKnownTypes.AGENT_SESSION_STARTED,
          Event.WellKnownTypes.AGENT_SESSION_STOPPED,
          Event.WellKnownTypes.AGENT_SESSION_COMPLETED);

  private final SessionStore sessionStore;
  private final ConcurrentHashMap<String, String> activeSessionsByProject =
      new ConcurrentHashMap<>();

  public SessionTracker(SessionStore sessionStore) {
    this.sessionStore = sessionStore;
  }

  @Override
  public String name() {
    return "session-tracker";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> HANDLED_TYPES.contains(e.type());
  }

  @Override
  public void onEvent(Event event) {
    try {
      switch (event.type()) {
        case Event.WellKnownTypes.AGENT_SESSION_STARTED -> handleStarted(event);
        case Event.WellKnownTypes.AGENT_SESSION_STOPPED -> handleStopped(event, "stopped");
        case Event.WellKnownTypes.AGENT_SESSION_COMPLETED -> handleStopped(event, "completed");
        default -> {}
      }
    } catch (Exception e) {
      System.err.println(
          "session-tracker: failed to process "
              + event.type()
              + " for project "
              + event.project()
              + ": "
              + e.getMessage());
    }
  }

  private void handleStarted(Event event) {
    var data = event.data();
    var pid = extractInt(data, "pid");
    var task = (String) data.get("task");
    var branch = (String) data.get("branch");
    var sessionId =
        sessionStore.create(event.project(), event.spec(), event.agent(), branch, task, pid);
    activeSessionsByProject.put(event.project(), sessionId);
  }

  private void handleStopped(Event event, String status) {
    var sessionId = activeSessionsByProject.remove(event.project());
    if (sessionId != null) {
      sessionStore.complete(sessionId, status);
    } else {
      sessionStore
          .runningForProject(event.project())
          .ifPresent(session -> sessionStore.complete(session.id(), status));
    }
  }

  private static Integer extractInt(Map<String, Object> data, String key) {
    var value = data.get(key);
    if (value instanceof Number n) return n.intValue();
    if (value instanceof String s) {
      try {
        return Integer.parseInt(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
