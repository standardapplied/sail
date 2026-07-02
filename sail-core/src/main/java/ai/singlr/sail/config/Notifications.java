/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notification configuration for agent watch events. Parsed from the {@code notifications} block
 * inside {@code agent} in sail.yaml. At least one destination — a webhook {@code url} or a {@code
 * slack} block — must be configured.
 *
 * @param url the webhook endpoint URL (must be https:// or http:// with SSRF checks); may be null
 *     when a slack block is configured
 * @param events which events to notify on via the webhook (null or empty means all events)
 * @param slack Slack channel configuration for per-spec threaded notifications; may be null
 */
public record Notifications(String url, List<String> events, SlackNotifications slack) {

  public Notifications {
    events = events == null ? null : List.copyOf(events);
  }

  public Notifications(String url, List<String> events) {
    this(url, events, null);
  }

  /**
   * Known event types that can trigger notifications. Includes both legacy names (kept for
   * backwards-compatibility with existing sail.yaml files) and the new bus event types that flow
   * through the EventBus.
   */
  public static final Set<String> VALID_EVENTS =
      Set.of(
          "guardrail_triggered",
          "agent_exited",
          "session_done",
          "spec_dispatched",
          "spec_restarted",
          "agent_session_started",
          "agent_session_stopped",
          "agent_session_completed",
          "snapshot_created");

  /**
   * Legacy alias names that map to current bus event type names. {@link #shouldNotify(String)}
   * accepts either form so users with old configs do not need to migrate.
   */
  private static final Map<String, String> LEGACY_ALIAS_OF =
      Map.of(
          "agent_session_stopped", "agent_exited",
          "agent_session_completed", "session_done");

  @SuppressWarnings("unchecked")
  public static Notifications fromMap(Map<String, Object> map) {
    var url = (String) map.get("url");
    var slackRaw = (Map<String, Object>) map.get("slack");
    var slack = slackRaw != null ? SlackNotifications.fromMap(slackRaw) : null;
    if (Strings.isBlank(url) && slack == null) {
      throw new IllegalArgumentException("notifications requires a webhook url or a slack block.");
    }
    if (Strings.isNotBlank(url)) {
      WebhookUrlSafety.requireSafe(url);
    } else {
      url = null;
    }

    var eventsRaw = (List<String>) map.get("events");
    if (eventsRaw != null) {
      for (var event : eventsRaw) {
        if (!VALID_EVENTS.contains(event)) {
          throw new IllegalArgumentException(
              "Unknown notification event: '"
                  + event
                  + "'. Valid events: "
                  + String.join(", ", VALID_EVENTS));
        }
      }
    }

    return new Notifications(url, eventsRaw, slack);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    if (url != null) {
      map.put("url", url);
    }
    if (events != null && !events.isEmpty()) {
      map.put("events", List.copyOf(events));
    }
    if (slack != null) {
      map.put("slack", slack.toMap());
    }
    return map;
  }

  /**
   * Returns true if the given event should trigger a notification. Accepts either the current bus
   * event-type name or its legacy alias when one exists, so configs declared with the old
   * vocabulary (for example {@code agent_exited}) continue to fire for the new bus events.
   */
  public boolean shouldNotify(String event) {
    if (events == null || events.isEmpty()) {
      return true;
    }
    if (events.contains(event)) {
      return true;
    }
    var legacy = LEGACY_ALIAS_OF.get(event);
    return legacy != null && events.contains(legacy);
  }
}
