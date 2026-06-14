/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable line in a spec's audit log. Audit lines are persisted as one JSON object per line
 * (JSONL) at {@code <specsDir>/<id>/audit.jsonl} inside the project container.
 *
 * <p>The {@link #event} field is intentionally a free-form string so new lifecycle events (for
 * example agent-emitted hooks) can be added without coordinated releases. {@link
 * #WELL_KNOWN_EVENTS} documents the names sail itself writes.
 *
 * @param ts when the event happened (UTC instant; required)
 * @param event lifecycle marker — see {@link #WELL_KNOWN_EVENTS} for sail-written names (required)
 * @param agent who emitted it — agent type ({@code claude-code} or {@code codex}) or {@code sail}
 *     for orchestrator-emitted events (required)
 * @param pid OS process id of the agent run, if known
 * @param host hostname of the machine running sail when the event was recorded (required)
 * @param note short free-form context (e.g. {@code restarted from done}); may be null
 */
public record SpecAuditEvent(
    Instant ts, String event, String agent, Integer pid, String host, String note) {

  /** Names emitted by sail itself. Agents may write any string. */
  public static final Set<String> WELL_KNOWN_EVENTS =
      Set.of("dispatched", "restarted", "started", "stopped", "completed");

  /** sail-orchestrator agent name (used when the event source is sail itself, not an AI CLI). */
  public static final String SAIL_AGENT = "sail";

  public SpecAuditEvent {
    Objects.requireNonNull(ts, "ts is required");
    requireNonBlank(event, "event");
    requireNonBlank(agent, "agent");
    requireNonBlank(host, "host");
    if (pid != null && pid <= 0) {
      throw new IllegalArgumentException("pid must be positive when set, got " + pid);
    }
  }

  /** Convenience factory for {@code dispatched}. */
  public static SpecAuditEvent dispatched(String agent, String host, String note) {
    return new SpecAuditEvent(DateTimeUtils.now(), "dispatched", agent, null, host, note);
  }

  /** Convenience factory for {@code restarted}. */
  public static SpecAuditEvent restarted(String agent, String host, String note) {
    return new SpecAuditEvent(DateTimeUtils.now(), "restarted", agent, null, host, note);
  }

  /** Convenience factory for {@code started}, carrying the agent PID. */
  public static SpecAuditEvent started(String agent, int pid, String host) {
    return new SpecAuditEvent(DateTimeUtils.now(), "started", agent, pid, host, null);
  }

  /** Convenience factory for {@code stopped}, carrying the agent PID. */
  public static SpecAuditEvent stopped(String agent, int pid, String host, String note) {
    return new SpecAuditEvent(DateTimeUtils.now(), "stopped", agent, pid, host, note);
  }

  /** Convenience factory for {@code completed}. */
  public static SpecAuditEvent completed(String agent, String host, String note) {
    return new SpecAuditEvent(DateTimeUtils.now(), "completed", agent, null, host, note);
  }

  /** Serializes this event as a single-line JSON object. Field order is deterministic. */
  public String toJsonLine() {
    return YamlUtil.dumpJson(toMap());
  }

  /** Returns a map view suitable for JSON dumping. Field order is deterministic. */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("ts", ts.toString());
    map.put("event", event);
    map.put("agent", agent);
    if (pid != null) {
      map.put("pid", pid);
    }
    map.put("host", host);
    if (Strings.isNotBlank(note)) {
      map.put("note", note);
    }
    return map;
  }

  /**
   * Parses a single JSONL line into an event. Throws {@link IllegalArgumentException} for any
   * missing required field or malformed timestamp — callers that want to ignore malformed lines
   * should catch and skip.
   */
  public static SpecAuditEvent fromJsonLine(String line) {
    var trimmed = Objects.requireNonNull(line, "line").strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("audit line is blank");
    }
    return fromMap(YamlUtil.parseMap(trimmed));
  }

  /** Builds an event from a parsed map. See {@link #fromJsonLine(String)} for error semantics. */
  public static SpecAuditEvent fromMap(Map<String, Object> map) {
    Objects.requireNonNull(map, "map");
    var tsRaw = stringField(map, "ts");
    Instant ts;
    try {
      ts = Instant.parse(tsRaw);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("invalid ts '" + tsRaw + "': " + e.getMessage(), e);
    }
    var event = stringField(map, "event");
    var agent = stringField(map, "agent");
    var host = stringField(map, "host");
    var pid = intField(map.get("pid"));
    var noteRaw = map.get("note");
    var note = noteRaw == null ? null : noteRaw.toString();
    return new SpecAuditEvent(ts, event, agent, pid, host, note);
  }

  private static String stringField(Map<String, Object> map, String key) {
    var raw = map.get(key);
    if (raw == null) {
      throw new IllegalArgumentException("missing required field '" + key + "'");
    }
    var str = raw.toString();
    if (str.isBlank()) {
      throw new IllegalArgumentException("required field '" + key + "' is blank");
    }
    return str;
  }

  private static Integer intField(Object raw) {
    return switch (raw) {
      case null -> null;
      case Integer i -> i;
      case Long l -> Math.toIntExact(l);
      case Number n -> n.intValue();
      case String s when !s.isBlank() -> Integer.parseInt(s.strip());
      default -> null;
    };
  }

  private static void requireNonBlank(String value, String name) {
    if (Strings.isBlank(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
