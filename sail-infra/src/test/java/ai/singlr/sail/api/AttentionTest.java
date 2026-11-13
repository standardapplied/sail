/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AttentionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The minutes a box records of people, through the production operations, stores and web router: a
 * person's typing in a room-bound terminal, post, status move and focus heartbeat each record a
 * (person, conversation, minute, channel) row and nothing else (M5, M7); the container's credential
 * and a run's principal record none; a heartbeat speaks only for its caller, in the server's
 * minute, about a conversation the caller may read (M6); and the measures count distinct (person,
 * minute) pairs.
 */
class AttentionTest {

  private static final Instant T0 = Instant.parse("2026-11-13T09:00:00Z");
  private static final ShellExec NO_CONTAINERS =
      new ShellExec() {
        public Result exec(List<String> command) {
          return new Result(0, "[]", "");
        }

        public Result exec(List<String> command, Path workDir, Duration timeout) {
          return exec(command);
        }

        public boolean isDryRun() {
          return false;
        }
      };

  @TempDir Path tempDir;
  private final HttpClient client = HttpClient.newHttpClient();
  private Clock clock;
  private Sqlite db;
  private EventBus bus;
  private HttpServer server;
  private SailOperations operations;
  private AttentionStore attention;
  private String ada;
  private String sam;

  @BeforeEach
  void aBoxWithTwoProjects() throws Exception {
    clock = DateTimeUtils.clock;
    at(0, 0);
    db = Sqlite.open(tempDir.resolve("box.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "alpha", "bravo");
    Acting.system(
        () -> {
          var fdes = new FdeStore(db);
          var tokens = new TokenStore(db);
          ada =
              tokens
                  .create("ada", "admin", fdes.add("ada", "Ada", "ada@x", "admin").id(), null)
                  .token();
          sam =
              tokens
                  .create("sam", "member", fdes.add("sam", "Sam", "sam@x", "member").id(), null)
                  .token();
          fdes.restrict("sam");
          fdes.grant("sam", "alpha");
        });
    spec("auth", "alpha");
    spec("hers", "bravo");
    bus = new EventBus();
    operations =
        TestControlPlane.on(
            OperationsFactory.create(
                db, NO_CONTAINERS, bus, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            new SyncConfig(null, null, null, "box-7"));
    attention = new AttentionStore(db);
    var auth = TestAuth.tokens(db);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        new ApiRouter(
            operations, auth, new RateLimitGate(), new AgentLogStreamer(auth), Optional.empty()));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    bus.close();
    db.close();
    DateTimeUtils.clock = clock;
  }

  private static void at(int minutes, int seconds) {
    DateTimeUtils.clock = Clock.fixed(T0.plusSeconds(60L * minutes + seconds), ZoneOffset.UTC);
  }

  private static Instant minute(int minutes) {
    return T0.plusSeconds(60L * minutes);
  }

  private void spec(String id, String project) {
    Acting.system(
        () ->
            new SpecStore(db)
                .create(
                    new SpecStore.SpecRow(
                        id,
                        project,
                        "Spec " + id,
                        SpecStatus.DRAFT,
                        "ada",
                        "claude-code",
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of())));
  }

  private HttpResponse<String> send(String token, String method, String path, String body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private List<AttentionStore.Minute> rows() {
    return attention.of(List.of("auth", "hers"));
  }

  /** Scenario 10. */
  @Test
  void threeMinutesTypedAndAPostInTheThirdAreThreeHumanMinutes() throws Exception {
    for (var minute = 0; minute < 3; minute++) {
      at(minute, 30);
      operations.pty().recordTyping("ada", "auth");
    }
    operations.pty().recordTyping("ada", "auth");

    var posted = send(ada, "POST", "/v1/rooms/auth/messages", "{\"body\": \"looks right\"}");

    assertEquals(201, posted.statusCode(), posted.body());
    assertEquals(
        List.of(
            new AttentionStore.Minute(
                "ada", "auth", minute(0), AttentionStore.Channel.TERMINAL_INPUT),
            new AttentionStore.Minute(
                "ada", "auth", minute(1), AttentionStore.Channel.TERMINAL_INPUT),
            new AttentionStore.Minute("ada", "auth", minute(2), AttentionStore.Channel.POST),
            new AttentionStore.Minute(
                "ada", "auth", minute(2), AttentionStore.Channel.TERMINAL_INPUT)),
        rows());
    var measures = YamlUtil.parseMap(send(ada, "GET", "/v1/specs/auth/measures", null).body());
    assertEquals(
        Map.of(
            "minutes",
            Map.of("count", 3),
            "by_channel",
            Map.of(
                "terminal_input", Map.of("count", 3),
                "post", Map.of("count", 1),
                "review_act", Map.of("count", 0),
                "focus", Map.of("count", 0)),
            "people",
            Map.of("count", 1),
            "bound",
            "lower"),
        measures.get("human_minutes"));
    assertEquals(Map.of("sail_id", "box-7"), measures.get("box"));
  }

  /**
   * Scenario 11: an interactive agent posting with the box credential records no minute; an agent
   * at a terminal is held by the pty host ({@code PtySessionHostTest}).
   */
  @Test
  void anAgentPostingWithTheBoxCredentialRecordsNoMinute() {
    var landed =
        Actor.call(
            new Actor("ada", Role.MEMBER, Actor.Lane.CONTAINER),
            () ->
                operations.postRoomMessage(
                    "auth", SpecMessageRequest.of("Done in-session.", null, "false"), "ada"));

    assertInstanceOf(Result.Success.class, landed, "the message lands");
    assertEquals(1, new MessageStore(db).list("auth", null, 10).size());
    assertEquals(List.of(), rows());
  }

  /** A person's status move is a review act in the spec's conversation. */
  @Test
  void aPersonsStatusMoveIsAReviewActMinute() throws Exception {
    at(5, 0);

    var moved = send(ada, "PUT", "/v1/specs/auth", "{\"status\": \"pending\"}");
    send(ada, "PUT", "/v1/specs/auth", "{\"title\": \"Renamed\"}");

    assertEquals(200, moved.statusCode(), moved.body());
    assertEquals(
        List.of(
            new AttentionStore.Minute("ada", "auth", minute(5), AttentionStore.Channel.REVIEW_ACT)),
        rows(),
        "an edit that moves no status is no act");
  }

  /** Scenario 12 (M6). */
  @Test
  void aHeartbeatSpeaksOnlyForItsCallerInTheServersMinuteAboutWhatItMayRead() throws Exception {
    at(7, 42);
    var missing = send(sam, "POST", "/v1/attention", "{\"conversation\": \"nobody\"}");
    var hidden = send(sam, "POST", "/v1/attention", "{\"conversation\": \"hers\"}");
    var named =
        send(
            sam,
            "POST",
            "/v1/attention",
            "{\"conversation\": \"auth\", \"person\": \"ada\", \"minute\": \"2020-01-01T00:00:00Z\"}");
    var again = send(sam, "POST", "/v1/attention", "{\"conversation\": \"auth\"}");

    assertEquals(404, hidden.statusCode());
    assertEquals(
        missing.body().replace("nobody", "hers"),
        hidden.body(),
        "a hidden room answers as a missing one");
    assertEquals(200, named.statusCode(), named.body());
    assertEquals(
        Map.of("conversation", "auth", "minute", "2026-11-13T09:07:00.000Z", "recorded", true),
        YamlUtil.parseMap(named.body()).entrySet().stream()
            .filter(entry -> !entry.getKey().equals("schema_version"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertEquals(false, YamlUtil.parseMap(again.body()).get("recorded"), "one row a minute");
    assertEquals(
        List.of(new AttentionStore.Minute("sam", "auth", minute(7), AttentionStore.Channel.FOCUS)),
        rows(),
        "the caller's person, the server's minute");
  }

  @Test
  void aHeartbeatThatNamesNoConversationIsRefusedAndOtherMethodsAreNotServed() throws Exception {
    var empty = send(ada, "POST", "/v1/attention", "{}");
    var read = send(ada, "GET", "/v1/attention", null);

    assertEquals(400, empty.statusCode());
    assertEquals(
        "{\"schema_version\": 1, \"error\": {\"code\": \"bad_request\", \"message\": \"Name the"
            + " conversation the view is focused on.\", \"action\": \"Send {\\\"conversation\\\":"
            + " \\\"<room or spec id>\\\"}.\", \"field_errors\": []}}",
        empty.body());
    assertEquals(404, read.statusCode());
    assertEquals(List.of(), rows());
  }
}
