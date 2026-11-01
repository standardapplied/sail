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
 * inside the definition's {@code agent}. At least one destination — a webhook {@code url} or a
 * {@code slack} block — must be configured.
 *
 * @param url the webhook endpoint URL (must be https:// or http:// with SSRF checks); may be null
 *     when a slack block is configured
 * @param events which events to notify on via the webhook (null or empty means all events)
 * @param slack Slack channel configuration for per-spec threaded notifications; may be null
 */
public record Notifications(String url, List<String> events, SlackNotifications slack) {

  /** No events and an empty list of them are one thing — every event — and are held as one. */
  public Notifications {
    events = events == null || events.isEmpty() ? null : List.copyOf(events);
  }

  public Notifications(String url, List<String> events) {
    this(url, events, null);
  }

  /** Known event types that can trigger notifications. */
  public static final Set<String> VALID_EVENTS =
      Set.of(
          "guardrail_triggered",
          "spec_dispatched",
          "spec_restarted",
          "agent_session_started",
          "agent_session_stopped",
          "agent_session_completed",
          "agent_stop_nudged",
          "snapshot_created");

  static Notifications fromMap(Map<String, Object> map) {
    Fields.only(map, "agent.notifications", List.of("url", "events", "slack"));
    var url = Fields.text(map, "url", "agent.notifications.url");
    var slackRaw = Fields.map(map, "slack", "agent.notifications.slack");
    var slack = slackRaw != null ? SlackNotifications.fromMap(slackRaw) : null;
    if (Strings.isBlank(url) && slack == null) {
      throw new IllegalArgumentException(
          "agent.notifications requires a webhook url or a slack block.");
    }
    if (Strings.isNotBlank(url)) {
      WebhookUrlSafety.requireSafe(url);
    } else {
      url = null;
    }
    var events = Fields.textList(map, "events", "agent.notifications.events");
    if (events != null) {
      for (var event : events) {
        if (!VALID_EVENTS.contains(event)) {
          throw new IllegalArgumentException(
              "Unknown notification event: '"
                  + event
                  + "'. Valid events: "
                  + String.join(", ", VALID_EVENTS));
        }
      }
    }
    return new Notifications(url, events, slack);
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

  /** Returns true if the given event should trigger a notification. */
  public boolean shouldNotify(String event) {
    return events == null || events.contains(event);
  }
}
