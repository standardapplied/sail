/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ReviewRuns;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The measures routes through the real web router, over the production operations and stores: what
 * {@code GET /v1/specs/{spec}/measures} answers is what {@code GET /v1/specs/{spec}?measures=true}
 * carries as {@code measures} and what {@code GET /v1/specs:measures} lists (M10), computed from
 * the rows the stores hold (M8, M9), refused to a caller who may not read the spec as a spec that
 * is not there (M13), and read without writing a row.
 */
class SpecMeasuresRouteTest {

  private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
  private static final String NODE = "uday";

  @TempDir Path tempDir;
  private final HttpClient client = HttpClient.newHttpClient();
  private Clock clock;
  private Sqlite db;
  private EventBus bus;
  private HttpServer server;
  private SpecStore specs;
  private RunStore runs;
  private ReviewStore reviews;
  private String ada;
  private String sam;

  @BeforeEach
  void aBoxWithTwoProjects() throws Exception {
    clock = DateTimeUtils.clock;
    at(0);
    db = Sqlite.open(tempDir.resolve("main.db"));
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
    specs = new SpecStore(db);
    runs = new RunStore(db);
    reviews = new ReviewStore(db);
    bus = new EventBus();
    var operations =
        TestControlPlane.standalone(
            OperationsFactory.create(
                db, NO_CONTAINERS, bus, null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir);
    var auth = TestAuth.tokens(db);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/", new ApiRouter(operations, auth, new RateLimitGate(), new AgentLogStreamer(auth)));
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

  private static void at(int minutes) {
    DateTimeUtils.clock = Clock.fixed(T0.plusSeconds(60L * minutes), ZoneOffset.UTC);
  }

  private void spec(String id, String project) {
    Acting.system(
        () ->
            specs.create(
                new SpecStore.SpecRow(
                    id,
                    project,
                    "Spec " + id,
                    SpecStatus.DRAFT,
                    NODE,
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

  private void move(String id, SpecStatus from, SpecStatus to) {
    Acting.system(() -> specs.compareAndSetStatus(id, from, to));
  }

  private void build(String run, String spec, String project) {
    Acting.system(
        () -> {
          runs.reserveDispatch(
              run,
              project,
              spec,
              null,
              NODE,
              "build",
              List.of(),
              "claude-code",
              "agent/" + spec,
              "build",
              "/var/log/" + run,
              "sail-agent-" + run,
              null);
          runs.complete(run, "completed", 0);
        });
  }

  private void serve(String run, String review, String spec, Lane lane) {
    Acting.system(
        () -> {
          ReviewRuns.reserve(runs, run, review, "alpha", spec, NODE, lane, "codex", List.of());
          runs.complete(run, "completed", 0);
        });
  }

  /** A review of {@code spec} at {@code iteration} that ends {@code passed} or failed. */
  private String review(String spec, int iteration, boolean passed, Finding... findings) {
    return Acting.system(
        () -> {
          var id = reviews.createReview(spec, iteration);
          var stage = reviews.createStage(id, "logic", "agent");
          for (var finding : findings) {
            reviews.addFinding(stage, finding);
          }
          if (passed) {
            reviews.pass(id, () -> {});
          } else {
            reviews.updateReviewStatus(id, "failed");
          }
          return id;
        });
  }

  private static Finding finding(String title) {
    return Finding.create(
        Finding.Severity.HIGH,
        Finding.Category.LOGIC,
        "Auth.java",
        3,
        3,
        title,
        "It leaks.",
        "Auth.java:3",
        null,
        0.9);
  }

  private HttpResponse<String> get(String token, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> read(String token, String path) throws Exception {
    var response = get(token, path);
    assertEquals(200, response.statusCode(), response.body());
    var body = YamlUtil.parseMap(response.body());
    body.remove("schema_version");
    return body;
  }

  /**
   * Every row of every table but the credentials', so a read can be shown to leave the database as
   * it found it: the door stamps a credential's last use on every request it authenticates,
   * whatever the request then reads, and that stamp is the door's, not the read's.
   */
  private List<String> everyRow() {
    var dump = new ArrayList<String>();
    for (var table :
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
                + " AND name NOT IN ('api_tokens', 'sessions') ORDER BY name",
            row -> row.text(0))) {
      dump.addAll(
          db.query(
              "SELECT * FROM \"" + table + "\"",
              row ->
                  table
                      + ": "
                      + IntStream.range(0, row.columnCount()).mapToObj(row::text).toList()));
    }
    return dump;
  }

  /** Scenario 1, and the same record wherever it is read (M10). */
  @Test
  void aSpecReviewedThreeTimesAcrossTwoDispatchesAnswersThreeRoundsTwoAttemptsAndItsFixRuns()
      throws Exception {
    spec("auth", "alpha");
    move("auth", SpecStatus.DRAFT, SpecStatus.PENDING);
    build("b1", "auth", "alpha");
    var first = review("auth", 1, false, finding("Token leak"));
    serve("v1", first, "auth", Lane.REVIEW);
    Acting.system(() -> reviews.supersedeForSpec("auth"));
    build("b2", "auth", "alpha");
    var second =
        Acting.system(
            () -> {
              var id = reviews.createReview("auth", 1);
              reviews.carryForward(
                  reviews.createStage(id, "logic", "agent"),
                  reviews.findingsForReview(first).getFirst(),
                  "It still leaks.");
              reviews.updateReviewStatus(id, "failed");
              return id;
            });
    serve("v2", second, "auth", Lane.REVIEW);
    serve("x1", second, "auth", Lane.FIX);
    var third = review("auth", 2, true);
    serve("v3", third, "auth", Lane.REVIEW);

    var measures = read(ada, "/v1/specs/auth/measures");

    assertEquals(
        Map.of("review_rounds", 3, "dispatch_attempts", 2, "fix_runs", 1, "carried_findings", 1),
        measures.get("rework"));
    assertEquals(Map.of("unknown", "not_recorded", "runs_without_usage", 6), measures.get("cost"));
    assertEquals(HostInfo.hostname(), ((Map<?, ?>) measures.get("times")).get("box"));
    assertEquals(
        measures, read(ada, "/v1/specs/auth?measures=true").get("measures"), "one computation");
    assertEquals(null, read(ada, "/v1/specs/auth").get("measures"), "asked for, never paid for");
    assertEquals(
        "{\"schema_version\": 1, \"error\": {\"code\": \"invalid_request\", \"message\":"
            + " \"measures must be true or false, not 'yes'.\", \"field_errors\": []}}",
        get(ada, "/v1/specs/auth?measures=yes").body());
    assertEquals(
        List.of(measures),
        read(ada, "/v1/specs:measures?project=alpha").get("specs"),
        "one computation");
  }

  /** Scenario 7. */
  @Test
  void aRunOfTheSpecInItsOwnRoomIsCountedOnce() throws Exception {
    spec("auth", "alpha");
    Acting.system(
        () -> {
          runs.reserveDispatch(
              "w1",
              "alpha",
              "auth",
              "auth",
              NODE,
              "room",
              List.of(),
              "claude-code",
              null,
              "wake",
              "/var/log/w1",
              "sail-agent-w1",
              null);
          runs.complete("w1", "completed", 0);
        });

    assertEquals(
        Map.of("unknown", "not_recorded", "runs_without_usage", 1),
        read(ada, "/v1/specs/auth/measures").get("cost"));
  }

  /**
   * Scenario 4, through the stores' own compaction: the birth is kept as the synced base, but the
   * revision that made the spec ready was dropped, so the first revision held in {@code pending}, a
   * later edit, is not taken for it.
   */
  @Test
  void aSpecWhosePendingRevisionWasCompactedAwaySaysItsReadyTimeIsUnknown() throws Exception {
    spec("auth", "alpha");
    var birth = specs.revOf("auth");
    at(10);
    move("auth", SpecStatus.DRAFT, SpecStatus.PENDING);
    for (var edit = 1; edit <= ChangeLog.HISTORY_REVISIONS; edit++) {
      at(10 + edit);
      var body = "body " + edit;
      Acting.system(() -> specs.setContent("auth", body, ""));
    }
    var before = (Map<?, ?>) read(ada, "/v1/specs/auth/measures").get("times");
    Acting.system(() -> new ChangeLog(db).compact("spec", List.of("auth"), id -> birth));

    var times = (Map<?, ?>) read(ada, "/v1/specs/auth/measures").get("times");

    assertEquals(
        Map.of("at", "2026-11-01T09:10:00.000Z", "source", "change_log"), before.get("ready"));
    assertEquals(1, specs.compacted("auth").size(), "the move to pending, and only it");
    assertEquals(Map.of("unknown", "history_incomplete"), times.get("ready"));
  }

  /**
   * A spec born in alpha, moved into bravo, made ready there and moved back: its revisions filed
   * under bravo are a gap to a reader of alpha alone, so the ready time it holds after them is not
   * taken for the first, and ada, who reads both, is told it.
   */
  @Test
  void aRevisionFiledWhereTheReaderDoesNotReadIsAGapNeverSkippedOver() throws Exception {
    spec("moved", "alpha");
    at(10);
    project("moved", "bravo");
    at(20);
    move("moved", SpecStatus.DRAFT, SpecStatus.PENDING);
    at(30);
    project("moved", "alpha");

    assertEquals(
        Map.of("unknown", "history_incomplete"),
        ((Map<?, ?>) read(sam, "/v1/specs/moved/measures").get("times")).get("ready"));
    assertEquals(
        Map.of("at", "2026-11-01T09:20:00.000Z", "source", "change_log"),
        ((Map<?, ?>) read(ada, "/v1/specs/moved/measures").get("times")).get("ready"));
  }

  private void project(String id, String project) {
    var row = specs.findById(id).orElseThrow();
    Acting.system(
        () ->
            specs.update(
                new SpecStore.SpecRow(
                    row.id(),
                    project,
                    row.title(),
                    row.status(),
                    row.assignee(),
                    row.agent(),
                    row.model(),
                    row.reasoningEffort(),
                    row.branch(),
                    row.priority(),
                    row.createdBy(),
                    row.createdAt(),
                    row.updatedAt(),
                    null,
                    row.dependsOn(),
                    row.repos(),
                    row.roomIdOrIdentity())));
  }

  /** Scenario 9, and a window that is not one. */
  @Test
  void aWindowListsOnlyTheSpecsDoneInItAndAWindowThatIsNoDateIsRefused() throws Exception {
    for (var day = 0; day < 3; day++) {
      var id = "s" + day;
      at(day * 24 * 60);
      spec(id, "alpha");
      move(id, SpecStatus.DRAFT, SpecStatus.DONE);
    }
    spec("open", "alpha");

    var listed =
        read(ada, "/v1/specs:measures?status=done&done_since=2026-11-02&done_until=2026-11-02");
    var refused = get(ada, "/v1/specs:measures?done_since=yesterday");

    assertEquals(
        List.of("s1"),
        ((List<?>) listed.get("specs"))
            .stream().map(spec -> ((Map<?, ?>) spec).get("spec_id")).toList());
    assertEquals(
        Map.of("done_since", "2026-11-02", "done_until", "2026-11-02", "left_out_undated", 0),
        listed.get("window"));
    assertEquals(422, refused.statusCode());
    assertEquals(
        "{\"schema_version\": 1, \"error\": {\"code\": \"invalid_request\", \"message\":"
            + " \"done_since must be a date, YYYY-MM-DD, not 'yesterday'.\", \"field_errors\":"
            + " []}}",
        refused.body());
  }

  /** Scenario 10. */
  @Test
  void aCallerWhoMayNotReadTheSpecIsAnsweredAsIfItWereMissing() throws Exception {
    spec("mine", "alpha");
    var missing = get(sam, "/v1/specs/hers/measures");
    spec("hers", "bravo");
    build("b1", "hers", "bravo");

    var hidden = get(sam, "/v1/specs/hers/measures");

    assertEquals(404, hidden.statusCode());
    assertEquals(missing.body(), hidden.body());
    assertEquals(
        "{\"schema_version\": 1, \"error\": {\"code\": \"spec_not_found\", \"message\": \"Spec"
            + " 'hers' was not found.\", \"field_errors\": []}}",
        hidden.body());
    assertEquals(
        List.of("mine"),
        ((List<?>) read(sam, "/v1/specs:measures").get("specs"))
            .stream().map(spec -> ((Map<?, ?>) spec).get("spec_id")).toList());
    assertEquals(200, get(ada, "/v1/specs/hers/measures").statusCode(), "control");
  }

  /** Scenario 12 (M13). */
  @Test
  void aMeasureReadLeavesEveryRowAsItFoundIt() throws Exception {
    spec("auth", "alpha");
    move("auth", SpecStatus.DRAFT, SpecStatus.PENDING);
    build("b1", "auth", "alpha");
    review("auth", 1, true);
    move("auth", SpecStatus.PENDING, SpecStatus.IN_PROGRESS);
    move("auth", SpecStatus.IN_PROGRESS, SpecStatus.AWAITING_MERGE);
    var before = everyRow();

    for (var token : List.of(ada, sam)) {
      read(token, "/v1/specs/auth/measures");
      read(token, "/v1/specs/auth?measures=true");
      read(token, "/v1/specs:measures?status=awaiting_merge&done_since=2026-11-01");
    }

    assertEquals(before, everyRow());
  }
}
