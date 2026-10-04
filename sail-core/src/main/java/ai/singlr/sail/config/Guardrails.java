/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The limits one agent run is held to: a hard wall-clock ceiling ({@code max_duration}) and an
 * idle/stall window ({@code max_idle}), with what to do when either is crossed. The one limits type
 * every lane reads: {@code agent.guardrails} in sail.yaml bounds a build, an ad-hoc run and a chat
 * turn, {@code agent.review_pipeline.guardrails} a reviewer and a fix agent, and both blocks parse
 * here. The stall window measures time since the agent's last <em>progress event</em> (a tool call
 * or log chunk) — not git activity. The earlier git-based idle timeout was removed because an agent
 * working without committing looked idle; the agent event stream does not have that blind spot, so
 * it distinguishes a long build from a hung agent. Quality assessment still happens post-task via
 * {@code agent review}.
 *
 * @param maxDuration hard wall-clock stop (e.g. "4h", "90m"); null for none
 * @param maxIdle stall window — act when no progress event arrives for this long (e.g. "15m"); null
 *     for none
 * @param action what to do on trigger: stop, snapshot-and-stop, notify
 */
public record Guardrails(String maxDuration, String maxIdle, String action) {

  private static final List<String> VALID_ACTIONS = List.of("stop", "snapshot-and-stop", "notify");
  private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([hms])$");

  /**
   * The supervision every dispatched agent gets when {@code sail.yaml} configures no {@code
   * guardrails} block: a 4h wall-clock ceiling and a 20m no-progress stall window, stopping the
   * agent on either. All three are overridable per project; this is only the default-on baseline.
   */
  public static Guardrails defaults() {
    return new Guardrails("4h", "20m", "stop");
  }

  /**
   * The supervision a reviewer and a fix agent get when {@code sail.yaml} configures no {@code
   * review_pipeline.guardrails} block: 45 minutes of wall clock, enough for a fix that runs the
   * project's full verification, and the build lane's 20m stall window, stopping the agent on
   * either.
   */
  public static Guardrails reviewDefaults() {
    return new Guardrails("45m", "20m", "stop");
  }

  /**
   * The limits named by their three values, each checked: a duration greater than zero in a form
   * {@link #parseDuration} accepts, or null for no limit, and an action sail knows.
   *
   * @throws IllegalArgumentException naming the accepted forms when a value is not one
   */
  public static Guardrails of(String maxDuration, String maxIdle, String action) {
    requireLimit(maxDuration);
    requireLimit(maxIdle);
    var chosen = Objects.requireNonNullElse(action, "stop");
    if (!VALID_ACTIONS.contains(chosen)) {
      throw new IllegalArgumentException(
          "Invalid guardrail action: '"
              + chosen
              + "'. Valid values: "
              + String.join(", ", VALID_ACTIONS));
    }
    return new Guardrails(maxDuration, maxIdle, chosen);
  }

  /**
   * Refuses a limit sail cannot hold a run to: a form {@link #parseDuration} does not read, or a
   * zero, which would end every run the moment it starts.
   */
  private static void requireLimit(String value) {
    var limit = parseDuration(value);
    if (limit != null && limit.isZero()) {
      throw new IllegalArgumentException(
          "Invalid duration: '"
              + value
              + "'. A limit must be greater than zero; leave the key out for no limit.");
    }
  }

  /** The build lane's block, {@code agent.guardrails}, as error messages name it. */
  static final String BUILD_BLOCK = "agent.guardrails";

  /** The review lanes' block, {@code agent.review_pipeline.guardrails}. */
  static final String REVIEW_BLOCK = "agent.review_pipeline.guardrails";

  /** Parses an {@code agent.guardrails} block of {@code sail.yaml}. */
  public static Guardrails fromMap(Map<String, Object> map) {
    return fromMap(map, BUILD_BLOCK, "sail.yaml");
  }

  /**
   * Parses the guardrails block {@code block} of {@code descriptor}, so a refused value names the
   * key to fix and the file it is in.
   */
  static Guardrails fromMap(Map<String, Object> map, String block, String descriptor) {
    rejectRetiredKey(map, "idle_timeout", "max_idle", descriptor);
    rejectRetiredKey(map, "commit_burst", "max_idle", descriptor);
    var maxDuration = duration(map, "max_duration", block, descriptor);
    var maxIdle = duration(map, "max_idle", block, descriptor);
    try {
      return of(maxDuration, maxIdle, Objects.toString(map.get("action"), null));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid `" + block + ".action` in " + descriptor + ": " + e.getMessage(), e);
    }
  }

  /**
   * The guardrails block {@code raw} holds under {@code block}: empty when the descriptor names
   * none, parsed when it is a block, and refused when it is anything else — a scalar or a list
   * there is a mistake, and must not run an agent under limits nobody wrote.
   */
  @SuppressWarnings("unchecked")
  static Optional<Guardrails> fromBlock(Object raw, String block, String descriptor) {
    return switch (raw) {
      case null -> Optional.empty();
      case Map<?, ?> limits ->
          Optional.of(fromMap((Map<String, Object>) limits, block, descriptor));
      default ->
          throw new IllegalArgumentException(
              "Invalid `"
                  + block
                  + "` in "
                  + descriptor
                  + ": expected a block with max_duration, max_idle and action, e.g."
                  + " `guardrails: {max_duration: 45m, max_idle: 20m, action: stop}`.");
    };
  }

  private static String duration(
      Map<String, Object> map, String key, String block, String descriptor) {
    var raw = map.get(key);
    if (raw == null) {
      return null;
    }
    var value = raw.toString().strip();
    try {
      requireLimit(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid `" + block + "." + key + "` in " + descriptor + ": " + e.getMessage(), e);
    }
    return value;
  }

  private static void rejectRetiredKey(
      Map<String, Object> map, String key, String replacement, String descriptor) {
    if (map.containsKey(key)) {
      throw new IllegalArgumentException(
          "Unknown guardrail key `"
              + key
              + "` in "
              + descriptor
              + "; rename `"
              + key
              + "` to `"
              + replacement
              + "` in "
              + descriptor
              + ".");
    }
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
