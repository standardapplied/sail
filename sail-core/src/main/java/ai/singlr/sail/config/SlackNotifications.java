/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slack notification configuration, parsed from the {@code slack} block inside {@code
 * agent.notifications}. Only the channel lives in config; the bot token is resolved from the {@code
 * SAIL_SLACK_TOKEN} environment variable or the file named by {@code SAIL_SLACK_TOKEN_FILE} — never
 * from the definition.
 *
 * @param channel the Slack channel to post to (required, e.g. {@code #sail-activity})
 */
public record SlackNotifications(String channel) {

  static SlackNotifications fromMap(Map<String, Object> map) {
    Fields.only(map, "agent.notifications.slack", List.of("channel"));
    var channel = Fields.text(map, "channel", "agent.notifications.slack.channel");
    if (Strings.isBlank(channel)) {
      throw new IllegalArgumentException(
          "notifications.slack.channel is required (e.g. \"#sail-activity\").");
    }
    return new SlackNotifications(channel);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("channel", channel);
    return map;
  }
}
