/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable event traveling through the sail {@link EventBus}. Events are emitted by the
 * orchestrator and by agent hooks inside project containers; they flow to in-process subscribers
 * (audit persister, webhook reactor) and out to live consumers over SSE.
 *
 * <p>The {@link #type} field is free-form for what the server emits: {@link WellKnownTypes}
 * documents the names sail itself uses, and subscribers ignore types they do not recognize. What a
 * client may publish is not free-form: the doors accept only the types {@code EventAuthority}
 * lists, from a sender who may drive what the event names ({@link EventDoor}).
 *
 * @param v schema version (always {@code 1} until a breaking change forces a bump)
 * @param id monotonic identifier stamped by the bus on publish; {@code 0} on freshly-built events
 * @param ts when the event happened (UTC; required)
 * @param project project / container name this event relates to (required)
 * @param spec spec id when the event is spec-scoped, otherwise {@code null}
 * @param type lifecycle marker — see {@link WellKnownTypes} (required)
 * @param agent the agent the event concerns ({@code sail} for orchestrator events; agent type or
 *     principal otherwise). A label its sender chose, never proof of who published it: that is
 *     {@code publisher}
 * @param host machine that produced the event (bare-metal host or container hostname)
 * @param data type-specific payload; never {@code null}, but may be empty
 * @param publisher who published the event through a door, stamped there from the authenticated
 *     actor and never read from a client; {@code null} for an event this box's own machinery
 *     emitted
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
    Map<String, Object> data,
    Publisher publisher) {

  /**
   * Who published an event through a door: the acting {@code handle} (null for a machine credential
   * that names no FDE), with the {@code role} and {@code lane} it acted on.
   */
  public record Publisher(String handle, String role, String lane) {

    public Publisher {
      Strings.requireNonBlank(role, "role");
      Strings.requireNonBlank(lane, "lane");
    }

    /** {@code actor} as the publisher of what it sends. */
    public static Publisher of(Actor actor) {
      return new Publisher(
          actor.handle(),
          actor.role().name().toLowerCase(Locale.ROOT),
          actor.lane().name().toLowerCase(Locale.ROOT));
    }

    /** Returns a map view suitable for JSON dumping. */
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (handle != null) {
        map.put("handle", handle);
      }
      map.put("role", role);
      map.put("lane", lane);
      return map;
    }

    /** Builds a publisher from a parsed map. */
    public static Publisher fromMap(Map<String, Object> map) {
      return new Publisher(
          optionalString(map.get("handle")), stringField(map, "role"), stringField(map, "lane"));
    }
  }

  public enum RetentionClass {
    RECORD,
    TELEMETRY,
    EPHEMERAL
  }

  /** Current schema version. */
  public static final int CURRENT_VERSION = 1;

  /** Sail-orchestrator agent name. */
  public static final String SAIL_AGENT = "sail";

  /**
   * Well-known event types emitted by sail itself. New types may be added without coordinated
   * releases; subscribers ignore types they do not recognize.
   */
  public static final class WellKnownTypes {
    public static final String SYNC_DEGRADED = "sync_degraded";
    public static final String SYNC_RECOVERED = "sync_recovered";
    public static final String SPEC_DISPATCHED = "spec_dispatched";
    public static final String SPEC_RESTARTED = "spec_restarted";
    public static final String SPEC_STATUS_CHANGED = "spec_status_changed";
    public static final String AGENT_SESSION_STARTED = "agent_session_started";
    public static final String AGENT_SESSION_STOPPED = "agent_session_stopped";
    public static final String AGENT_SESSION_COMPLETED = "agent_session_completed";

    /**
     * An interactive agent conversation began inside a room-bound terminal session — reported by
     * the SessionStart hook over the box lane, since such a session has no run row. Scoped to the
     * room; carries {@code room_id}, {@code session_id}, and {@code agent} in {@code data}. The
     * seam for reopening one conversation through either door; nothing consumes it yet.
     */
    public static final String AGENT_CONVERSATION_STARTED = "agent_conversation_started";

    /**
     * An operator deliberately cancelled a running spec: the terminal intent was recorded (spec
     * {@code cancelled}, run {@code stopped}) before the agent process was halted. Carries {@code
     * data.source=operator} and the run id; the event's {@code agent} field names the acting FDE.
     * Distinct from {@link #AGENT_SESSION_STOPPED} so a kill is never mistaken for a finish.
     */
    public static final String AGENT_CANCELLED = "agent_cancelled";

    /**
     * The stop-hook readiness gate blocked a premature turn-end (uncommitted or unpushed work) and
     * nudged the agent to finish the protocol. Carries the nudge text in {@code data.reason}. A
     * blocked stop publishes this instead of {@link #AGENT_SESSION_STOPPED} — the stop never
     * happened.
     */
    public static final String AGENT_STOP_NUDGED = "agent_stop_nudged";

    public static final String AGENT_TOOL_STARTED = "agent_tool_started";
    public static final String AGENT_TOOL_FINISHED = "agent_tool_finished";
    public static final String AGENT_LOG_CHUNK = "agent_log_chunk";
    public static final String SNAPSHOT_CREATED = "snapshot_created";
    public static final String SNAPSHOT_RESTORED = "snapshot_restored";
    public static final String SNAPSHOT_DELETED = "snapshot_deleted";
    public static final String GUARDRAIL_TRIGGERED = "guardrail_triggered";
    public static final String BOARD_UPDATED = "board_updated";
    public static final String SPEC_MESSAGE_POSTED = "spec_message_posted";

    /** A human put an agent in the spec's room; it answers every human message until dismissed. */
    public static final String SPEC_ENGAGED = "spec_engaged";

    /** The room's engaged agent was dismissed (or its engagement expired). */
    public static final String SPEC_DISENGAGED = "spec_disengaged";

    /** An engagement did not take effect — the full mode's snapshot payment failed. */
    public static final String SPEC_ENGAGE_FAILED = "spec_engage_failed";

    public static final String AGENT_PRESENCE = "agent_presence";

    /** A host-owned terminal session started; emitted by the pty host, room-scoped when bound. */
    public static final String PTY_SESSION_STARTED = "pty_session_started";

    /** A client attached to a host-owned terminal session. */
    public static final String PTY_SESSION_ATTACHED = "pty_session_attached";

    /** A host-owned terminal session ended; {@code data.reason} says how. */
    public static final String PTY_SESSION_ENDED = "pty_session_ended";

    /**
     * Whether {@code type} is one of the pty session facts. They are persisted at source — the pty
     * host writes its own event rows straight into the events table from its own process — so on
     * the bus they are always the {@link PtyEventBridge}'s republication for live consumers, never
     * something to persist again.
     */
    public static boolean ptySessionFact(String type) {
      return PTY_SESSION_STARTED.equals(type)
          || PTY_SESSION_ATTACHED.equals(type)
          || PTY_SESSION_ENDED.equals(type);
    }

    /** A spec left in_progress/review past the reconciler threshold — surfaced for triage. */
    public static final String SPEC_STRANDED = "spec_stranded";

    /**
     * The dispatched agent exited non-zero — its work is not auto-advanced to review. Carries the
     * exit code in {@code data.exit_code} so the failure can be triaged.
     */
    public static final String AGENT_FAILED = "agent_failed";

    /**
     * The {@link RetentionClass#TELEMETRY} types: high-volume liveness signals the retention
     * sweeper prunes and history reads exclude, so "the record" always means RECORD-class events.
     */
    public static final Set<String> TELEMETRY_TYPES =
        Set.of(AGENT_TOOL_STARTED, AGENT_TOOL_FINISHED, AGENT_LOG_CHUNK);

    public static RetentionClass retentionClass(String type) {
      if (TELEMETRY_TYPES.contains(type)) {
        return RetentionClass.TELEMETRY;
      }
      return switch (type) {
        case AGENT_PRESENCE -> RetentionClass.EPHEMERAL;
        default -> RetentionClass.RECORD;
      };
    }

    /**
     * Whether {@code type} is evidence the agent is actively working — the one definition of
     * "progress", shared by the guardrail watcher's stall timer and the {@link RunActivityStamper}
     * so the two can never disagree about what counts as liveness.
     */
    public static boolean progress(String type) {
      return AGENT_TOOL_STARTED.equals(type)
          || AGENT_TOOL_FINISHED.equals(type)
          || AGENT_LOG_CHUNK.equals(type);
    }

    private WellKnownTypes() {}
  }

  /**
   * Well-known keys and values in an event's {@link #data} payload. A stop is
   * <em>authoritative</em> — the real termination, not a mid-run turn-end — only when it carries a
   * {@link #SOURCE}; the in-container agent hook never sets one, so its turn-end stop is
   * distinguishable from the watcher's poll-derived stop that knows the process {@link #EXIT_CODE}.
   */
  public static final class WellKnownData {
    /** Who emitted an authoritative stop; absent on a raw agent-hook stop. */
    public static final String SOURCE = "source";

    /** {@link #SOURCE} value: the guardrail watcher, which observed the process exit code. */
    public static final String SOURCE_WATCHER = "watcher";

    /** {@link #SOURCE} value: a deliberate operator cancel through the clean-stop lane. */
    public static final String SOURCE_OPERATOR = "operator";

    /**
     * {@link #SOURCE} value: a reconciler replay of a stop the control plane missed — at daemon
     * start or from the periodic missed-stop sweep. Marks the stop as reconstructed, not observed.
     */
    public static final String SOURCE_RECONCILE = "reconcile";

    /**
     * {@link #SOURCE} value: an event main derived from a node's synced state transition. Carried
     * on every sync-derived event so main-side narration (Slack) treats it as authoritative while
     * the execution-side reactors — the review pipeline, webhooks the origin node already sent —
     * leave it alone; the work it describes lives on another box.
     */
    public static final String SOURCE_SYNC = "sync";

    /** The agent process's exit code, carried on an authoritative stop. */
    public static final String EXIT_CODE = "exit_code";

    /**
     * The {@link #EXIT_CODE} a stop's {@code data} carries, or null when it names none it can read.
     */
    public static Integer exitCode(Map<String, Object> data) {
      return switch (data.get(EXIT_CODE)) {
        case Number number -> number.intValue();
        case String text -> {
          try {
            yield Integer.parseInt(text);
          } catch (NumberFormatException e) {
            yield null;
          }
        }
        case null, default -> null;
      };
    }

    /**
     * Why the watcher ended a run it did not see exit on its own: the guardrail it enforced, as a
     * person reads it ({@code time limit (45m)}, {@code stall (20m)}). A stop that carries one
     * carries no {@link #EXIT_CODE}: the run did not end itself, so it has none of its own.
     */
    public static final String REASON = "reason";

    /**
     * Carried, as {@code true}, on an authoritative stop the missed-stop reconciler published for a
     * run whose row had already ended: the stop was said before, or the run was finished without
     * one, and it is published again only so the loop goes on from it. The review pipeline routes
     * it like any stop; whoever narrates stops to people says nothing for it.
     */
    public static final String REPLAY = "replay";

    /** Whether a stop's {@code data} marks it a {@link #REPLAY}. */
    public static boolean replay(Map<String, Object> data) {
      return Boolean.parseBoolean(Objects.toString(data.get(REPLAY), null));
    }

    /** Host pid of the guardrail watcher covering a dispatched session, carried on its start. */
    public static final String WATCHER_PID = "watcher_pid";

    /**
     * The run id this lifecycle event belongs to, carried on launch and terminal events so a
     * completion addresses the exact execution rather than "the newest running run of the project".
     * Absent on an ad-hoc (non-dispatch) session that minted no run.
     */
    public static final String RUN_ID = "run_id";

    /** The id of the message a {@code spec_message_posted} event announces. */
    public static final String MESSAGE_ID = "message_id";

    /**
     * The stopped run's lane ({@code build}, {@code adhoc}, {@code review}, {@code fix}, {@code
     * room}), carried on stop signals so lane-aware reactors decide without a store lookup — above
     * all the review pipeline, which routes a stop by it: a build's starts a review, a reviewer's
     * or a fix agent's advances the review it serves, a {@link #RUN_ROLE_ROOM} stop is ignored.
     */
    public static final String RUN_ROLE = "run_role";

    /** {@link #RUN_ROLE} value: a room wake — a chat that must never trigger a review. */
    public static final String RUN_ROLE_ROOM = Lane.ROOM.wire();

    /**
     * {@link #RUN_ROLE} value: an engaged agent's full-access chat turn. A conversation, not a
     * task: never a review trigger, and its clean stop is turn plumbing, not news.
     */
    public static final String RUN_ROLE_ROOM_FULL = Lane.ROOM_FULL.wire();

    /** {@link #RUN_ROLE} value: a reviewer run — its stop resolves the stage it ran. */
    public static final String RUN_ROLE_REVIEW = Lane.REVIEW.wire();

    /** {@link #RUN_ROLE} value: a fix run — its stop starts the re-review. */
    public static final String RUN_ROLE_FIX = Lane.FIX.wire();

    /**
     * Whether a run role names a lane whose stop does not hand its spec to review — every lane
     * except a dispatch build and an ad-hoc run. Reactors on {@code agent_session_stopped} that
     * move a spec or narrate a build drop such stops by role. Null (a role-less stop), like an
     * unrecognized role, is treated as a normal triggering stop; a retired invite role never
     * triggers. Delegates to {@link Lane} so this classification and {@code RunRow}'s share one
     * source.
     */
    public static boolean nonTriggeringLane(String role) {
      return !Lane.triggersReview(role);
    }

    private WellKnownData() {}
  }

  /** An event no door published: one this box's own machinery emits, or a client offers. */
  public Event(
      int v,
      long id,
      Instant ts,
      String project,
      String spec,
      String type,
      String agent,
      String host,
      Map<String, Object> data) {
    this(v, id, ts, project, spec, type, agent, host, data, null);
  }

  public Event {
    if (v <= 0) {
      throw new IllegalArgumentException("v must be positive, got " + v);
    }
    if (id < 0) {
      throw new IllegalArgumentException("id must be non-negative, got " + id);
    }
    Objects.requireNonNull(ts, "ts is required");
    Strings.requireNonBlank(project, "project");
    Strings.requireNonBlank(type, "type");
    Strings.requireNonBlank(agent, "agent");
    Strings.requireNonBlank(host, "host");
    data = data == null ? Map.of() : Map.copyOf(data);
  }

  /**
   * Construct a freshly-built event ({@code id = 0}) for the current schema version with an empty
   * data payload. Convenient for the common case.
   */
  public static Event of(String project, String spec, String type, String agent, String host) {
    return of(project, spec, type, agent, host, Map.of());
  }

  /** As {@link #of(String, String, String, String, String)} but with a data payload. */
  public static Event of(
      String project,
      String spec,
      String type,
      String agent,
      String host,
      Map<String, Object> data) {
    return new Event(
        CURRENT_VERSION, 0L, DateTimeUtils.now(), project, spec, type, agent, host, data);
  }

  /** Returns a copy of this event with the given bus-assigned id. */
  public Event withId(long stampedId) {
    if (stampedId <= 0) {
      throw new IllegalArgumentException("stampedId must be positive, got " + stampedId);
    }
    return new Event(v, stampedId, ts, project, spec, type, agent, host, data, publisher);
  }

  /**
   * This event as a door admits it: about {@code project} and {@code spec} as the server knows
   * them, published by {@code publisher} at {@code ts} on the server's clock.
   */
  public Event admitted(String project, String spec, Publisher publisher, Instant ts) {
    return new Event(v, 0L, ts, project, spec, type, agent, host, data, publisher);
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
    if (Strings.isNotBlank(spec)) {
      map.put("spec", spec);
    }
    map.put("type", type);
    map.put("agent", agent);
    map.put("host", host);
    if (!data.isEmpty()) {
      map.put("data", data);
    }
    if (publisher != null) {
      map.put("publisher", publisher.toMap());
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
    var publisher =
        map.get("publisher") instanceof Map<?, ?> p
            ? Publisher.fromMap((Map<String, Object>) p)
            : null;
    return new Event(version, id, ts, project, spec, type, agent, host, data, publisher);
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
}
