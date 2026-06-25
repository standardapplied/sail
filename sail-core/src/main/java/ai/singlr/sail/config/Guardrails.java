/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent guardrail configuration parsed from the {@code guardrails} block inside {@code agent} in
 * sail.yaml. Two time-based guardrails are enforced: a hard wall-clock ceiling ({@code
 * max_duration}) and an idle/stall window ({@code max_idle}). The stall window measures time since
 * the agent's last <em>progress event</em> (a tool call or log chunk) — not git activity. The
 * earlier git-based idle timeout was removed because an agent working without committing looked
 * idle; the agent event stream does not have that blind spot, so it distinguishes a long build from
 * a hung agent. Quality assessment still happens post-task via {@code agent review}.
 *
 * @param maxDuration hard wall-clock stop (e.g. "4h", "90m")
 * @param maxIdle stall window — act when no progress event arrives for this long (e.g. "15m")
 * @param action what to do on trigger: stop, snapshot-and-stop, notify
 */
public record Guardrails(String maxDuration, String maxIdle, String action) {

  private static final Set<String> VALID_ACTIONS = Set.of("stop", "snapshot-and-stop", "notify");
  private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([hms])$");

  /**
   * Parses a Guardrails record from a YAML map. Unknown keys (including legacy {@code idle_timeout}
   * and {@code commit_burst}) are silently ignored for backwards compatibility.
   */
  public static Guardrails fromMap(Map<String, Object> map) {
    var maxDuration = (String) map.get("max_duration");
    var maxIdle = (String) map.get("max_idle");

    var action = Objects.requireNonNullElse((String) map.get("action"), "stop");
    if (!VALID_ACTIONS.contains(action)) {
      throw new IllegalArgumentException(
          "Invalid guardrail action: '"
              + action
              + "'. Valid values: "
              + String.join(", ", VALID_ACTIONS));
    }

    return new Guardrails(maxDuration, maxIdle, action);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    if (maxDuration != null) map.put("max_duration", maxDuration);
    if (maxIdle != null) map.put("max_idle", maxIdle);
    map.put("action", action);
    return map;
  }

  /**
   * Parses a duration string like "4h", "90m", or "30s" into a {@link Duration}. Returns null if
   * the input is null (meaning the guardrail is disabled).
   *
   * @throws IllegalArgumentException if the format is invalid
   */
  public static Duration parseDuration(String value) {
    if (value == null) {
      return null;
    }
    var matcher = DURATION_PATTERN.matcher(value.strip());
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "Invalid duration format: '"
              + value
              + "'. Use a number followed by h (hours), m (minutes), or s (seconds). Examples:"
              + " 4h, 90m, 30s");
    }
    var amount = Long.parseLong(matcher.group(1));
    try {
      return switch (matcher.group(2)) {
        case "h" -> Duration.ofHours(amount);
        case "m" -> Duration.ofMinutes(amount);
        case "s" -> Duration.ofSeconds(amount);
        default -> throw new IllegalArgumentException("Unknown duration unit: " + matcher.group(2));
      };
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Duration value too large: '" + value + "'.");
    }
  }
}
