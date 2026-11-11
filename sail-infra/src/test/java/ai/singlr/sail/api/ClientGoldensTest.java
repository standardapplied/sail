/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.EngagementMode;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server's answers a native client decodes, as the goldens {@code clients/SailKit} tests read:
 * each route the first native surface calls and each refusal it handles, as one request and the
 * response the real router gave; the event stream the real stream handler wrote while those routes
 * ran, with a sample of every well-known event type; every pty frame type as {@link PtyWire} writes
 * it; and the vocabularies the client's enums mirror. Each file must equal what the server writes
 * now, so a change to a shape a client decodes fails here and arrives in its pull request as a
 * changed golden. A change that alters one on purpose re-records them:
 *
 * <pre>
 * mvn -o -pl sail-infra -am test -Dtest=ClientGoldensTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dsail.client.goldens=record
 * </pre>
 *
 * <p>Ids are random and times run on the clock, so the clock is pinned and every UUID is renamed by
 * the order it first appears in; JSON keys are sorted, since a map's iteration order is not part of
 * the wire. Nothing else is rewritten.
 */
class ClientGoldensTest {

  static final String SWITCH = "sail.client.goldens";
  static final String RECORD = "record";

  private static final Path GOLDENS = Path.of("..", "clients", "SailKit", "Tests", "Goldens");
  private static final Instant AT = Instant.parse("2026-11-11T09:00:00Z");
  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final String PROJECT = "harbor";
  private static final String ROOM = "harbor-design";
  private static final String SPEC = "harbor-docks";
  private static final String DATA = "data: ";
  private static final SyncConfig OWNER = new SyncConfig(null, null, "uday");

  @TempDir Path tempDir;
  private Clock clock;

  @BeforeEach
  void pinTheClock() {
    clock = DateTimeUtils.clock;
    DateTimeUtils.clock = Clock.fixed(AT, ZoneOffset.UTC);
  }

  @AfterEach
  void restoreTheClock() {
    DateTimeUtils.clock = clock;
  }

  @Test
  void theRoutesAndTheStreamAClientDecodesAnswerAsTheGoldensSay() throws Exception {
    try (var db = Sqlite.openMemory();
        var bus = new EventBus();
        var box = new Box(db, bus, tempDir)) {
      var uday = box.session("uday", "Uday", "uday@example.com", Role.ADMIN);
      var vik = box.token("vik", Role.VIEWER);
      var sam = box.scopedToken("sam");
      var http = new TreeMap<String, String>();
      box.seed("POST", "/v1/rooms", uday, room());
      var stream = box.stream("/v1/events/stream", uday);
      var roomStream = box.stream("/v1/events/stream?room=" + ROOM, uday);
      var asked = box.record(http, "message-posted", "POST", messages(), uday, question());
      var asking = ((Map<?, ?>) asked.get("message")).get("id").toString();
      box.record(http, "message-reply", "POST", messages(), uday, reply(asking));
      box.seed("POST", "/v1/specs", uday, spec());
      box.seat(Engagement.of("claude-code", "read_only", "opus", DateTimeUtils.format(AT)));
      box.ask("claude/run-1", "uday", "Shall I take the south berth?");

      box.record(http, "whoami", "GET", "/v1/whoami", uday, null);
      box.record(http, "projects", "GET", "/v1/projects", uday, null);
      box.record(http, "rooms", "GET", "/v1/rooms?project=" + PROJECT, uday, null);
      box.record(http, "room", "GET", "/v1/rooms/" + ROOM, uday, null);
      box.record(http, "messages", "GET", messages() + "?limit=50", uday, null);
      box.record(http, "specs", "GET", "/v1/specs?project=" + PROJECT, uday, null);
      box.record(http, "spec", "GET", "/v1/specs/" + SPEC, uday, null);

      box.record(http, "refusal-read-only-credential", "POST", messages(), vik, plain("hello"));
      box.record(http, "refusal-room-not-found", "GET", "/v1/rooms/nowhere", uday, null);
      box.record(http, "refusal-spec-not-found", "GET", "/v1/specs/nothing", uday, null);
      box.record(http, "refusal-missing-bearer-token", "GET", "/v1/whoami", null, null);
      box.record(http, "refusal-invalid-bearer-token", "GET", "/v1/whoami", "not-a-token", null);
      box.record(http, "refusal-bad-request", "POST", messages(), uday, plain(""));
      box.record(http, "refusal-out-of-scope", "POST", "/v1/specs", sam, unroomed());
      box.record(http, "refusal-project-not-created", "POST", members(), uday, member());
      box.record(http, "refusal-not-found", "GET", "/v1/nowhere", uday, null);

      var last = everyOtherWellKnownEvent().stream().map(bus::publish).toList().getLast();
      box.end(uday, last);
      var events = new TreeMap<String, String>();
      events.put("stream.sse", Scrub.stream(stream.join(), box::renamed));
      events.put("room-stream.sse", Scrub.stream(roomStream.join(), box::renamed));

      Goldens.check("http", http);
      Goldens.check("events", events);
      assertEquals(
          wellKnownTypes(),
          typesIn(events.get("stream.sse"), events.get("room-stream.sse")),
          "the streams carry every well-known event type");
    }
  }

  @Test
  void everyPtyFrameIsWhatThePtyWireWrites() throws IOException {
    var frames = new ArrayList<Map<String, Object>>();
    for (var message : everyPtyMessage()) {
      var name = message.getClass().getSimpleName();
      frames.add(Map.of("name", name, "hex", HexFormat.of().formatHex(wire(message))));
    }
    var sampled =
        frames.stream().map(frame -> frame.get("name").toString()).collect(Collectors.toSet());
    var permitted =
        Arrays.stream(PtyMessage.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    assertEquals(permitted, sampled, "a sample of every pty frame type");
    Goldens.check(
        "frames",
        new TreeMap<>(
            Map.of(
                "frames.json",
                Goldens.json(
                    Map.of("magic", HexFormat.of().formatHex(magic()), "frames", frames)))));
  }

  @Test
  void theVocabulariesAClientMirrorsAreTheServers() {
    var vocabulary = new LinkedHashMap<String, Object>();
    vocabulary.put(
        "error_codes",
        Arrays.stream(ErrorCode.values())
            .map(code -> Map.of("code", code.code(), "status", code.httpCode()))
            .toList());
    vocabulary.put("event_types", List.copyOf(wellKnownTypes()));
    vocabulary.put(
        "member_modes", Arrays.stream(EngagementMode.values()).map(EngagementMode::wire).toList());
    vocabulary.put("roles", Arrays.stream(Role.values()).map(Role::attribute).toList());
    vocabulary.put(
        "spec_statuses", Arrays.stream(SpecStatus.values()).map(SpecStatus::wire).toList());
    Goldens.check("vocabulary", new TreeMap<>(Map.of("vocabulary.json", Goldens.json(vocabulary))));
  }

  private static String messages() {
    return "/v1/rooms/" + ROOM + "/messages";
  }

  private static String members() {
    return "/v1/rooms/" + ROOM + "/members";
  }

  private static Map<String, Object> plain(String body) {
    return Map.of("body", body);
  }

  private static Map<String, Object> unroomed() {
    return Map.of("id", "harbor-ferry", "project", PROJECT, "title", "A ferry timetable");
  }

  private static Map<String, Object> room() {
    return Map.of("id", ROOM, "project", PROJECT, "title", "Docks", "wake", "mention");
  }

  private static Map<String, Object> member() {
    return Map.of("agent", "claude-code", "mode", "read_only", "model", "opus");
  }

  private static Map<String, Object> question() {
    return Map.of("body", "Which berth takes the ferry — north or south? ⚓", "question", true);
  }

  private static Map<String, Object> reply(String messageId) {
    return Map.of("body", "South: it has the deeper draft. 🚢", "reply_to", messageId);
  }

  private static Map<String, Object> spec() {
    return Map.ofEntries(
        Map.entry("id", SPEC),
        Map.entry("project", PROJECT),
        Map.entry("title", "Docks take the ferry"),
        Map.entry("status", "pending"),
        Map.entry("assignee", "uday"),
        Map.entry("agent", "claude-code"),
        Map.entry("model", "opus"),
        Map.entry("reasoning_effort", "high"),
        Map.entry("branch", "agent/harbor-docks"),
        Map.entry("priority", 2),
        Map.entry("depends_on", List.of("harbor-berths")),
        Map.entry("repos", List.of("harbor-api")),
        Map.entry("body", "The south berth takes the ferry."),
        Map.entry("room_id", ROOM));
  }

  /**
   * One event of each well-known type the routes above did not publish, with the data keys its
   * emitter writes; the last is the room's, so both streams are sent it.
   */
  private static List<Event> everyOtherWellKnownEvent() {
    var run = Map.<String, Object>of(Event.WellKnownData.RUN_ID, "run-1");
    var stop = Map.<String, Object>of("run_id", "run-1", "source", "watcher", "exit_code", 0);
    var room = Map.<String, Object>of("room_id", ROOM, "principal", "agent:claude", "turn", 1);
    return List.of(
        SyncTransitionEvents.health("main@host", false, "unreachable", "refused", "box"),
        SyncTransitionEvents.health("main@host", true, null, null, "box"),
        event(SPEC, "spec_dispatched", Map.of("run_id", "run-1", "agent", "claude-code")),
        event(SPEC, "spec_restarted", run),
        event(SPEC, "spec_status_changed", Map.of("from", "pending", "to", "in_progress")),
        event(SPEC, "agent_session_started", run),
        event(SPEC, "agent_session_stopped", stop),
        event(SPEC, "agent_session_completed", stop),
        event(ROOM, "agent_conversation_started", Map.of("room_id", ROOM, "session_id", "s-1")),
        event(SPEC, "agent_cancelled", Map.of("run_id", "run-1", "source", "operator")),
        event(SPEC, "agent_stop_nudged", Map.of("reason", "Commit and push before you stop.")),
        event(SPEC, "agent_tool_started", Map.of("tool", "Bash")),
        event(SPEC, "agent_tool_finished", Map.of("tool", "Bash", "batch", true)),
        event(SPEC, "agent_log_chunk", Map.of("run_id", "run-1", "lines", List.of("building"))),
        event(null, "snapshot_created", Map.of("name", "before-docks")),
        event(null, "snapshot_restored", Map.of("name", "before-docks")),
        event(null, "snapshot_deleted", Map.of("name", "before-docks")),
        event(SPEC, "guardrail_triggered", Map.of("run_id", "run-1", "reason", "stall (20m)")),
        event(null, "board_updated", Map.of()),
        event(ROOM, "spec_engaged", Map.of("agent", "claude-code", "mode", "full")),
        event(ROOM, "spec_disengaged", Map.of("agent", "claude-code")),
        event(ROOM, "spec_engage_failed", Map.of("agent", "claude-code", "reason", "no space")),
        event(SPEC, "agent_presence", Map.of("run_id", "run-1", "presence", "working")),
        event(ROOM, "agent_turn_began", room),
        event(ROOM, "agent_text_delta", with(room, "text", "South berth — 12 m draft ✅")),
        event(ROOM, "agent_tool_call", with(room, "status", "started")),
        event(ROOM, "agent_turn_ended", with(room, "message_id", "ended-turn")),
        event(SPEC, "spec_stranded", Map.of("status", "in_progress")),
        event(SPEC, "agent_failed", Map.of("run_id", "run-1", "exit_code", 2)),
        event(ROOM, "pty_session_started", Map.of("session", "lounge", "room_id", ROOM)),
        event(ROOM, "pty_session_attached", Map.of("session", "lounge")),
        event(ROOM, "pty_session_ended", Map.of("session", "lounge", "reason", "exited(0)")));
  }

  private static Map<String, Object> with(Map<String, Object> data, String key, Object value) {
    var more = new LinkedHashMap<>(data);
    more.put(key, value);
    return more;
  }

  private static Event event(String spec, String type, Map<String, Object> data) {
    return Event.of(PROJECT, spec, type, Event.SAIL_AGENT, "box", data);
  }

  private static Set<String> wellKnownTypes() {
    return Arrays.stream(Event.WellKnownTypes.class.getFields())
        .filter(field -> Modifier.isStatic(field.getModifiers()))
        .filter(field -> field.getType() == String.class)
        .map(ClientGoldensTest::constant)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static String constant(Field field) {
    try {
      return (String) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<String> typesIn(String... streams) {
    return Stream.of(streams)
        .flatMap(String::lines)
        .filter(line -> line.startsWith(DATA))
        .map(line -> YamlUtil.parseMap(line.substring(DATA.length())).get("type"))
        .filter(Objects::nonNull)
        .map(String::valueOf)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static List<PtyMessage> everyPtyMessage() {
    var lounge =
        new PtyMessage.SessionInfo(
            "lounge", "inst-1", true, 2, "uday", ROOM, List.of("claude", "--resume"));
    var shell = new PtyMessage.SessionInfo("shell", "inst-2", false, 0, "", "", List.of("bash"));
    return List.of(
        new PtyMessage.Hello("tok-uday"),
        new PtyMessage.Welcome("boot-7"),
        new PtyMessage.Create("lounge", List.of("bash", "-l"), "/home/dev", PROJECT, ROOM, 132, 43),
        new PtyMessage.Attach("lounge", true),
        new PtyMessage.Input(42, "ls -la\r".getBytes(StandardCharsets.UTF_8)),
        new PtyMessage.Resize(100, 30),
        new PtyMessage.TakeWrite(),
        new PtyMessage.Detach(),
        new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT),
        new PtyMessage.Kill("lounge"),
        new PtyMessage.Yield("lounge", "a dispatch took the repos"),
        new PtyMessage.Output(42, "héllo ✅\u001b[0m\r\n".getBytes(StandardCharsets.UTF_8)),
        new PtyMessage.ReplayBegin(true),
        new PtyMessage.ReplayEnd(),
        new PtyMessage.WriterChanged("uday"),
        new PtyMessage.Resized(100, 30),
        new PtyMessage.Paused(),
        new PtyMessage.Continued(),
        new PtyMessage.SessionEnded("exited(0)"),
        lounge,
        new PtyMessage.Sessions(List.of(lounge, shell), "shell"),
        new PtyMessage.Ok(),
        new PtyMessage.Err(
            new Remedy(
                List.of(
                    new Remedy.Words("You do not hold the write token. Run "),
                    new Remedy.Command(Remedy.Runs.SAIL, "session", List.of("take", "lounge")),
                    new Remedy.Words(" to take it.")))));
  }

  private static byte[] wire(PtyMessage message) throws IOException {
    var out = new ByteArrayOutputStream();
    PtyWire.write(Channels.newChannel(out), message);
    return out.toByteArray();
  }

  private static byte[] magic() {
    return "SAILPTY3".getBytes(StandardCharsets.US_ASCII);
  }

  /** A box serving the web API and the event stream over its own database and bus. */
  private static final class Box implements AutoCloseable {
    private final Sqlite db;
    private final EventBus bus;
    private final SailOperations operations;
    private final HttpServer server;
    private final ManualClock streamClock;
    private final List<OpenStream> streams = new ArrayList<>();
    private final Map<String, String> ids = new LinkedHashMap<>();

    Box(Sqlite db, EventBus bus, Path dir) throws IOException {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      this.db = db;
      this.bus = bus;
      this.streamClock = new ManualClock(AT, Duration.ofMinutes(1));
      this.operations =
          OperationsFactory.create(
                  db,
                  new ScriptedShellExecutor(new ShellExec.Result(0, "[]", "")),
                  bus,
                  null,
                  SyncScheduler.disabled(),
                  SessionYield.NONE)
              .useControlPlane(
                  db,
                  dir,
                  new SyncOperations(
                      db,
                      "node",
                      dir,
                      () -> OWNER,
                      target -> {
                        throw new IOException("main unavailable");
                      }));
      var auth = TestAuth.sessions(db, OWNER);
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      PrivateAttributes.context(
          server,
          "/",
          new ApiRouter(operations, auth, new RateLimitGate(), new AgentLogStreamer(auth)));
      server.createContext(
          "/v1/events/stream",
          new SseHandler(
              bus,
              auth,
              operations.liveMembers()::turnState,
              SseHandler.DEFAULT_MAX_CONNECTIONS,
              streamClock));
      server.start();
    }

    String session(String handle, String displayName, String email, Role role) {
      var fde = new FdeStore(db).add(handle, displayName, email, role.attribute());
      return new AuthSessionStore(db).create(fde.id(), Duration.ofMinutes(30)).token();
    }

    /** A member's token whose scope grants no project. */
    String scopedToken(String handle) {
      var token = token(handle, Role.MEMBER);
      new FdeStore(db).restrict(handle);
      return token;
    }

    /**
     * Seats {@code member} in the room as the box's own machinery does: engaging one through the
     * route needs the project's container, which this box has none of.
     */
    void seat(Engagement member) {
      Acting.system(() -> new RoomStore(db).updateRoster(ROOM, Roster.solo(member).toJson()));
    }

    /** Posts {@code body} as a question from the agent principal {@code agent} of {@code owner}. */
    void ask(String agent, String owner, String body) {
      var principal = Actor.agentPrincipal(agent, owner);
      Actor.call(
              principal,
              () ->
                  operations.postRoomMessage(
                      ROOM,
                      new SpecMessageRequest(body, null, MessageStore.Mark.QUESTION),
                      principal.handle()))
          .orThrow();
    }

    String token(String handle, Role role) {
      var fde = new FdeStore(db).add(handle, null, null, role.attribute());
      return new TokenStore(db).create(handle, role.attribute(), fde.id(), null).token();
    }

    /**
     * Records {@code method path}, sent as {@code token} with {@code body}, and the answer the
     * router gave, under {@code name}; answers the response body.
     */
    Map<String, Object> record(
        Map<String, String> goldens,
        String name,
        String method,
        String path,
        String token,
        Map<String, Object> body)
        throws Exception {
      var response = send(method, path, token, body);
      var parsed = YamlUtil.parseMap(response.body());
      var request = new LinkedHashMap<String, Object>();
      request.put("method", method);
      request.put("path", path);
      if (body != null) {
        request.put("body", body);
      }
      var exchange =
          Map.of(
              "request",
              request,
              "response",
              Map.of("status", response.statusCode(), "body", parsed));
      goldens.put(name + ".json", Goldens.json(Scrub.canonical(exchange, this::renamed)));
      return parsed;
    }

    /** Sends what a golden needs written first, which must succeed. */
    void seed(String method, String path, String token, Map<String, Object> body) throws Exception {
      var response = send(method, path, token, body);
      if (response.statusCode() >= 300) {
        fail(method + " " + path + " answered " + response.body());
      }
    }

    HttpResponse<String> send(String method, String path, String token, Map<String, Object> body)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(URI.create(base() + path))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(YamlUtil.dumpJson(body)));
      if (token != null) {
        builder.header("Authorization", "Bearer " + token);
      }
      try (var client = HttpClient.newHttpClient()) {
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500) {
          fail(method + " " + path + " answered " + response.body());
        }
        return response;
      }
    }

    /** Opens {@code path}'s stream as {@code token}, once it has subscribed. */
    OpenStream stream(String path, String token) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base() + path))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      var client = HttpClient.newHttpClient();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      var stream = new OpenStream(client, response.body());
      streams.add(stream);
      stream.awaitLine(": subscribed");
      return stream;
    }

    /**
     * Ends every open stream as a revoked caller's ends, once each has written {@code last}: {@code
     * token} is revoked, the streams' clock passes their next check, and an event arrives for them
     * to check on.
     */
    void end(String token, Event last) throws InterruptedException {
      for (var stream : streams) {
        stream.awaitLine("id: " + last.id());
      }
      new AuthSessionStore(db).revoke(token);
      streamClock.advance(StreamRecheck.INTERVAL);
      bus.publish(event(ROOM, "board_updated", Map.of()));
    }

    /** {@code text} with every UUID renamed by the order this box first saw it. */
    String renamed(String text) {
      return UUID.matcher(text)
          .replaceAll(
              found ->
                  ids.computeIfAbsent(
                      found.group(),
                      id -> String.format("00000000-0000-7000-8000-%012d", ids.size() + 1)));
    }

    private String base() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
      operations.close();
    }
  }

  /** An event stream being read to its end, line by line as it arrives. */
  private static final class OpenStream {
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final CompletableFuture<String> ended;

    OpenStream(HttpClient client, InputStream body) {
      this.ended =
          CompletableFuture.supplyAsync(
              () -> {
                try (client;
                    body) {
                  return readToEnd(body);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              },
              Executors.newVirtualThreadPerTaskExecutor());
    }

    private String readToEnd(InputStream body) throws IOException {
      var line = new ByteArrayOutputStream();
      for (var next = body.read(); next >= 0; next = body.read()) {
        raw.write(next);
        if (next == '\n') {
          lines.add(line.toString(StandardCharsets.UTF_8));
          line.reset();
        } else {
          line.write(next);
        }
      }
      return raw.toString(StandardCharsets.UTF_8);
    }

    void awaitLine(String wanted) throws InterruptedException {
      for (var line = lines.poll(BusTesting.DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          !wanted.equals(line);
          line = lines.poll(BusTesting.DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        if (line == null) {
          fail("the stream never wrote '" + wanted + "'");
        }
      }
    }

    String join() {
      return ended.join();
    }
  }

  /** What makes a recorded answer the same on every run, and nothing more. */
  private static final class Scrub {

    private Scrub() {}

    /** {@code value} with its keys sorted and every text {@code rename}d. */
    @SuppressWarnings("unchecked")
    static Object canonical(Object value, UnaryOperator<String> rename) {
      return switch (value) {
        case String text -> rename.apply(text);
        case Map<?, ?> map -> {
          var sorted = new TreeMap<String, Object>();
          ((Map<String, Object>) map)
              .forEach((key, item) -> sorted.put(key, canonical(item, rename)));
          yield sorted;
        }
        case List<?> list -> list.stream().map(item -> canonical(item, rename)).toList();
        case null, default -> value;
      };
    }

    /**
     * {@code stream} with each data line's JSON made canonical and the name of the machine that
     * wrote an event written as {@code box}, its framing as sent.
     */
    static String stream(String stream, UnaryOperator<String> rename) {
      return stream
          .lines()
          .map(line -> line.startsWith(DATA) ? DATA + data(line, rename) : line)
          .collect(Collectors.joining("\n", "", "\n"));
    }

    private static String data(String line, UnaryOperator<String> rename) {
      var data = new LinkedHashMap<>(YamlUtil.parseMap(line.substring(DATA.length())));
      data.computeIfPresent("host", (key, host) -> HostInfo.hostname().equals(host) ? "box" : host);
      return YamlUtil.dumpJsonValue(canonical(data, rename));
    }
  }

  /** The goldens one directory holds, checked against what is written now or recorded as it. */
  private static final class Goldens {

    private Goldens() {}

    static String json(Object value) {
      return YamlUtil.dumpJsonPretty(Scrub.canonical(value, UnaryOperator.identity())) + "\n";
    }

    static void check(String directory, SortedMap<String, String> now) {
      var dir = GOLDENS.resolve(directory);
      if (RECORD.equals(System.getProperty(SWITCH))) {
        record(dir, now);
        return;
      }
      var recorded = read(dir);
      var names = new TreeSet<>(recorded.keySet());
      names.addAll(now.keySet());
      var problems = new ArrayList<String>();
      for (var name : names) {
        var golden = recorded.get(name);
        var written = now.get(name);
        if (golden == null) {
          problems.add(name + ": written now, but no golden is committed");
        } else if (written == null) {
          problems.add(name + ": committed, but nothing writes it now");
        } else if (!golden.equals(written)) {
          problems.add(name + ": " + firstDifference(golden, written));
        }
      }
      if (!problems.isEmpty()) {
        fail(
            dir
                + " differs from what the server writes now:\n"
                + String.join("\n", problems)
                + "\nA change to a shape a client decodes re-records them with -D"
                + SWITCH
                + "="
                + RECORD
                + "; any other difference is a change of the wire.");
      }
    }

    private static String firstDifference(String golden, String written) {
      var was = golden.lines().toList();
      var is = written.lines().toList();
      var line = 0;
      while (line < was.size() && line < is.size() && was.get(line).equals(is.get(line))) {
        line++;
      }
      return "line "
          + (line + 1)
          + "\n  golden: "
          + (line < was.size() ? was.get(line) : "(end)")
          + "\n  now:    "
          + (line < is.size() ? is.get(line) : "(end)");
    }

    private static SortedMap<String, String> read(Path dir) {
      var files = new TreeMap<String, String>();
      if (!Files.isDirectory(dir)) {
        return files;
      }
      try (var listing = Files.list(dir)) {
        for (var file : listing.toList()) {
          files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return files;
    }

    private static void record(Path dir, SortedMap<String, String> now) {
      try {
        Files.createDirectories(dir);
        for (var stale : read(dir).keySet()) {
          if (!now.containsKey(stale)) {
            Files.delete(dir.resolve(stale));
          }
        }
        for (var file : now.entrySet()) {
          Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
