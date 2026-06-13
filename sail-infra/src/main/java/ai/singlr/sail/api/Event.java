/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable event traveling through the sail {@link EventBus}. Events are emitted by the
 * orchestrator and by agent hooks inside project containers; they flow to in-process subscribers
 * (audit persister, webhook reactor) and out to live consumers over SSE.
 *
 * <p>The {@link #type} field is intentionally free-form. {@link WellKnownTypes} documents the names
 * sail itself emits; agents and future reactors may add new ones without coordinated releases.
 *
 * @param v schema version (always {@code 1} until a breaking change forces a bump)
 * @param id monotonic identifier stamped by the bus on publish; {@code 0} on freshly-built events
 * @param ts when the event happened (UTC; required)
 * @param project project / container name this event relates to (required)
 * @param spec spec id when the event is spec-scoped, otherwise {@code null}
 * @param type lifecycle marker — see {@link WellKnownTypes} (required)
 * @param agent who emitted the event ({@code sail} for orchestrator events; agent type otherwise)
 * @param host machine that produced the event (bare-metal host or container hostname)
 * @param data type-specific payload; never {@code null}, but may be empty
 */
public record Event(
    int v,
    long id,
    Instant ts,
    String project,
    String spec,
    String type,
    String agent,
    String host,
    Map<String, Object> data) {

  /** Current schema version. */
  public static final int CURRENT_VERSION = 1;

  /** Sail-orchestrator agent name. */
  public static final String SAIL_AGENT = "sail";

  /**
   * Well-known event types emitted by sail itself. New types may be added without coordinated
   * releases; subscribers ignore types they do not recognize.
   */
  public static final class WellKnownTypes {
    public static final String SPEC_DISPATCHED = "spec_dispatched";
    public static final String SPEC_RESTARTED = "spec_restarted";
    public static final String SPEC_STATUS_CHANGED = "spec_status_changed";
    public static final String AGENT_SESSION_STARTED = "agent_session_started";
    public static final String AGENT_SESSION_STOPPED = "agent_session_stopped";
    public static final String AGENT_SESSION_COMPLETED = "agent_session_completed";
    public static final String AGENT_TOOL_STARTED = "agent_tool_started";
    public static final String AGENT_TOOL_FINISHED = "agent_tool_finished";
    public static final String AGENT_LOG_CHUNK = "agent_log_chunk";
    public static final String SNAPSHOT_CREATED = "snapshot_created";
    public static final String SNAPSHOT_RESTORED = "snapshot_restored";
    public static final String SNAPSHOT_DELETED = "snapshot_deleted";
    public static final String GUARDRAIL_TRIGGERED = "guardrail_triggered";
    public static final String PROJECT_STARTED = "project_started";
    public static final String PROJECT_STOPPED = "project_stopped";
    public static final String BOARD_UPDATED = "board_updated";

    private WellKnownTypes() {}
  }

  public Event {
    if (v <= 0) {
      throw new IllegalArgumentException("v must be positive, got " + v);
    }
    if (id < 0) {
      throw new IllegalArgumentException("id must be non-negative, got " + id);
    }
    Objects.requireNonNull(ts, "ts is required");
    requireNonBlank(project, "project");
    requireNonBlank(type, "type");
    requireNonBlank(agent, "agent");
    requireNonBlank(host, "host");
    data = data == null ? Map.of() : Map.copyOf(data);
  }

  /**
   * Construct a freshly-built event ({@code id = 0}) for the current schema version with an empty
   * data payload. Convenient for the common case.
   */
  public static Event of(String project, String spec, String type, String agent, String host) {
    return new Event(
        CURRENT_VERSION, 0L, Instant.now(), project, spec, type, agent, host, Map.of());
  }

  /** As {@link #of(String, String, String, String, String)} but with a data payload. */
  public static Event of(
      String project,
      String spec,
      String type,
      String agent,
      String host,
      Map<String, Object> data) {
    return new Event(CURRENT_VERSION, 0L, Instant.now(), project, spec, type, agent, host, data);
  }

  /** Returns a copy of this event with the given bus-assigned id. */
  public Event withId(long stampedId) {
    if (stampedId <= 0) {
      throw new IllegalArgumentException("stampedId must be positive, got " + stampedId);
    }
    return new Event(v, stampedId, ts, project, spec, type, agent, host, data);
  }

  /** Serializes this event as a single-line JSON object. */
  public String toJsonLine() {
    return YamlUtil.dumpJson(toMap());
  }

  /** Returns a map view suitable for JSON dumping. Field order is deterministic. */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", v);
    if (id > 0) {
      map.put("id", id);
    }
    map.put("ts", ts.toString());
    map.put("project", project);
    if (spec != null && !spec.isBlank()) {
      map.put("spec", spec);
    }
    map.put("type", type);
    map.put("agent", agent);
    map.put("host", host);
    if (!data.isEmpty()) {
      map.put("data", data);
    }
    return map;
  }

  /**
   * Parses a single JSONL line into an event. Throws {@link IllegalArgumentException} on missing
   * required fields or malformed values. Callers that want to skip corrupted lines should catch and
   * continue.
   */
  public static Event fromJsonLine(String line) {
    var trimmed = Objects.requireNonNull(line, "line").strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("event line is blank");
    }
    return fromMap(YamlUtil.parseMap(trimmed));
  }

  /** Builds an event from a parsed map. */
  @SuppressWarnings("unchecked")
  public static Event fromMap(Map<String, Object> map) {
    Objects.requireNonNull(map, "map");
    var version = intField(map.get("v"), CURRENT_VERSION);
    var id = longField(map.get("id"), 0L);
    var ts = parseTs(stringField(map, "ts"));
    var project = stringField(map, "project");
    var spec = optionalString(map.get("spec"));
    var type = stringField(map, "type");
    var agent = stringField(map, "agent");
    var host = stringField(map, "host");
    var dataRaw = map.get("data");
    Map<String, Object> data =
        dataRaw instanceof Map<?, ?> m ? Map.copyOf((Map<String, Object>) m) : Map.of();
    return new Event(version, id, ts, project, spec, type, agent, host, data);
  }

  private static Instant parseTs(String raw) {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("invalid ts '" + raw + "': " + e.getMessage(), e);
    }
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

  private static String optionalString(Object raw) {
    if (raw == null) {
      return null;
    }
    var str = raw.toString();
    return str.isBlank() ? null : str;
  }

  private static int intField(Object raw, int fallback) {
    return switch (raw) {
      case null -> fallback;
      case Integer i -> i;
      case Long l -> Math.toIntExact(l);
      case Number n -> n.intValue();
      case String s when !s.isBlank() -> Integer.parseInt(s.strip());
      default -> fallback;
    };
  }

  private static long longField(Object raw, long fallback) {
    return switch (raw) {
      case null -> fallback;
      case Long l -> l;
      case Integer i -> i.longValue();
      case Number n -> n.longValue();
      case String s when !s.isBlank() -> Long.parseLong(s.strip());
      default -> fallback;
    };
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
