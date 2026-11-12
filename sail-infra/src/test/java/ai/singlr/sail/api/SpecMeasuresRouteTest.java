/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
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
import java.util.Optional;
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
  private static final String BOX = "box-7";

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
        TestControlPlane.on(
            OperationsFactory.create(
                db, NO_CONTAINERS, bus, null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            new SyncConfig(null, null, null, BOX));
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
   * Every row of every table, every column but a credential's {@code last_used_at}, so a read can
   * be shown to leave the database as it found it: the door stamps a credential's last use on every
   * request it authenticates, whatever the request then reads, and that stamp is the door's.
   */
  private List<String> everyRow() {
    var dump = new ArrayList<String>();
    for (var table :
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
                + " ORDER BY name",
            row -> row.text(0))) {
      var columns =
          db.query("SELECT name FROM pragma_table_info(?)", row -> row.text(0), table).stream()
              .filter(
                  column ->
                      !(column.equals("last_used_at")
                          && List.of("api_tokens", "sessions").contains(table)))
              .map(column -> "\"" + column + "\"")
              .toList();
      dump.addAll(
          db.query(
              "SELECT " + String.join(", ", columns) + " FROM \"" + table + "\"",
              row ->
                  table
                      + ": "
                      + IntStream.range(0, row.columnCount()).mapToObj(row::text).toList()));
    }
    return dump;
  }

  private static Map<String, Object> count(int count) {
    return Map.of("count", count);
  }

  private static Map<String, Object> rework(
      Object rounds, Object errors, Object builds, Object fixes, Object carried) {
    return Map.of(
        "review_rounds",
        rounds,
        "review_errors",
        errors,
        "build_runs",
        builds,
        "fix_runs",
        fixes,
        "carried_findings",
        carried);
  }

  private static final Map<String, Object> HIDDEN = Map.of("unknown", "hidden_rows");

  /** Scenario 1, and the same record wherever it is read (M10). */
  @Test
  void aSpecReviewedThreeTimesAcrossTwoDispatchesAnswersItsRoundsBuildRunsAndFixRuns()
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

    assertEquals(rework(count(3), count(0), count(2), count(1), count(1)), measures.get("rework"));
    assertEquals(
        Map.of("unknown", "not_recorded", "runs_without_usage", count(6)), measures.get("cost"));
    assertEquals(
        Map.of("sail_id", BOX), ((Map<?, ?>) measures.get("times")).get("box"), "its Sail id");
    var measured = get(ada, "/v1/specs/auth?measures=true");
    assertEquals(measures, YamlUtil.parseMap(measured.body()).get("measures"), "one computation");
    assertEquals(
        Optional.empty(),
        measured.headers().firstValue("ETag"),
        "its measures change with rows its updated_at does not follow");
    assertEquals(true, get(ada, "/v1/specs/auth").headers().firstValue("ETag").isPresent());
    assertEquals(null, read(ada, "/v1/specs/auth").get("measures"), "asked for, never paid for");
    assertEquals(
        "{\"schema_version\": 1, \"error\": {\"code\": \"invalid_request\", \"message\":"
            + " \"measures must be true or false, not 'yes'.\", \"action\": \"Send measures=true to"
            + " read the spec with its measures, or leave it out.\", \"field_errors\": []}}",
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
        Map.of("unknown", "not_recorded", "runs_without_usage", count(1)),
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
    assertEquals(1, specs.compacted(List.of("auth")).size(), "the move to pending, and only it");
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
    Acting.system(() -> new Erasure(db).adopt(Erasure.RUN, "elsewhere", "1-erased", "bravo"));

    assertEquals(
        Map.of("unknown", "history_incomplete"),
        ((Map<?, ?>) read(sam, "/v1/specs/moved/measures").get("times")).get("ready"));
    assertEquals(
        count(0),
        ((Map<?, ?>) read(sam, "/v1/specs/moved/measures").get("rework")).get("build_runs"),
        "an erasure in a project sam does not read says nothing to him");
    assertEquals(
        Map.of("count", 0, "at_least", true),
        ((Map<?, ?>) read(ada, "/v1/specs/moved/measures").get("rework")).get("build_runs"),
        "a project the spec was filed under, which ada reads");
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

  /** A build that asked a question and resumed is a second build run, and is served as one. */
  @Test
  void aResumedBuildIsAnotherBuildRunAndNotAnotherDispatch() throws Exception {
    spec("held", "alpha");
    move("held", SpecStatus.DRAFT, SpecStatus.PENDING);
    build("h1", "held", "alpha");
    build("h2", "held", "alpha");
    review("held", 1, true);

    var rework = (Map<?, ?>) read(ada, "/v1/specs/held/measures").get("rework");

    assertEquals(count(2), rework.get("build_runs"));
    assertEquals(false, rework.containsKey("dispatch_attempts"));
  }

  /**
   * Majors 2 and 3: a spec built and reviewed in bravo and moved into alpha. To sam, who reads
   * alpha alone, every number its reviews and runs give is unknown, never none, and his listing
   * leaves it out; ada, who reads both, is told the numbers.
   */
  @Test
  void aReviewOrRunFiledWhereTheReaderDoesNotReadLeavesItsNumbersUnknownNeverZero()
      throws Exception {
    spec("moved", "bravo");
    move("moved", SpecStatus.DRAFT, SpecStatus.PENDING);
    build("bm1", "moved", "bravo");
    review("moved", 1, false, finding("A"));
    build("bm2", "moved", "bravo");
    review("moved", 2, true);
    project("moved", "alpha");
    move("moved", SpecStatus.PENDING, SpecStatus.AWAITING_MERGE);
    move("moved", SpecStatus.AWAITING_MERGE, SpecStatus.DONE);

    var forSam = read(sam, "/v1/specs/moved/measures");
    var forAda = read(ada, "/v1/specs/moved/measures");
    var samsListing = (Map<?, ?>) read(sam, "/v1/specs:measures?status=done").get("summary");

    assertEquals(rework(HIDDEN, HIDDEN, HIDDEN, HIDDEN, HIDDEN), forSam.get("rework"));
    assertEquals(HIDDEN, ((Map<?, ?>) forSam.get("cost")).get("runs_without_usage"));
    assertEquals(HIDDEN, ((Map<?, ?>) forSam.get("times")).get("verified"));
    assertEquals(HIDDEN, forSam.get("no_recorded_human_code_act"));
    assertEquals(rework(count(2), count(0), count(2), count(0), count(0)), forAda.get("rework"));
    assertEquals(
        Map.of("counted", true, "bound", "upper"), forAda.get("no_recorded_human_code_act"));
    assertEquals(0, ((Map<?, ?>) samsListing.get("review_rounds")).get("n"));
    assertEquals(1, ((Map<?, ?>) samsListing.get("review_rounds")).get("left_out_unknown"));
  }

  /**
   * A run retention erased after the spec arrived, of its own project, may have been one of its
   * own, so its run counts are a lower bound; one erased before it arrived could not have been, and
   * neither could one of another project, nor the runs of a spec pruned there.
   */
  @Test
  void aRunOfItsProjectErasedSinceTheSpecArrivedMakesItsRunCountsALowerBound() throws Exception {
    spec("old", "alpha");
    build("o1", "old", "alpha");
    erase("o1");
    spec("erased", "alpha");
    move("erased", SpecStatus.DRAFT, SpecStatus.PENDING);
    build("e1", "erased", "alpha");
    build("e2", "erased", "alpha");
    spec("bystander", "bravo");
    build("by1", "bystander", "bravo");
    spec("gone", "bravo");
    build("g1", "gone", "bravo");
    move("gone", SpecStatus.DRAFT, SpecStatus.ARCHIVED);
    var before = (Map<?, ?>) read(ada, "/v1/specs/erased/measures").get("rework");
    Acting.system(
        () ->
            new Erasure(db)
                .erase(
                    new Erasure(db).closure(List.of(new Erasure.Target(Erasure.SPEC, "gone"))),
                    SpecPruner.RETENTION));
    var afterAPrune = (Map<?, ?>) read(ada, "/v1/specs/erased/measures").get("rework");

    erase("e1");

    var after = read(ada, "/v1/specs/erased/measures");
    assertEquals(count(2), before.get("build_runs"), "erased before the spec arrived");
    assertEquals(count(2), afterAPrune.get("build_runs"), "a pruned spec of another project");
    assertEquals(
        Map.of("count", 1, "at_least", true), ((Map<?, ?>) after.get("rework")).get("build_runs"));
    assertEquals(
        Map.of("count", 1, "at_least", true),
        ((Map<?, ?>) after.get("cost")).get("runs_without_usage"));
    assertEquals(
        Map.of("count", 1, "at_least", true),
        ((Map<?, ?>) read(ada, "/v1/specs/bystander/measures").get("rework")).get("build_runs"),
        "the pruned spec's own project: its run, erased with it, names no spec");
  }

  /**
   * Minor 3 of review 2: a node records main's erasure of a run, held or not, as main's row heard
   * by sync, filed under the run's project, so it bounds a spec's run counts as main's own does.
   */
  @Test
  void anErasureHeardFromMainBoundsTheRunCountsOfASpecOfItsProjectAsMainsOwnDoes()
      throws Exception {
    spec("here", "alpha");
    build("h1", "here", "alpha");
    spec("there", "bravo");
    build("t1", "there", "bravo");

    Acting.system(() -> new Erasure(db).adopt(Erasure.RUN, "never-held", "1-erased", "alpha"));

    assertEquals(
        Map.of("count", 1, "at_least", true),
        ((Map<?, ?>) read(ada, "/v1/specs/here/measures").get("rework")).get("build_runs"));
    assertEquals(
        count(1),
        ((Map<?, ?>) read(ada, "/v1/specs/there/measures").get("rework")).get("build_runs"));
  }

  private void erase(String run) {
    Acting.system(
        () ->
            new Erasure(db)
                .erase(List.of(new Erasure.Target(Erasure.RUN, run)), SpecPruner.RETENTION));
  }

  /** The listing reads in as many statements for a hundred specs as for one. */
  @Test
  void theListingsStatementsDoNotGrowWithItsSpecs() throws Exception {
    var listing = "/v1/specs:measures?status=done";
    shipMany(0, 1);
    var one = List.of(statementsOf(ada, listing), statementsOf(sam, listing));
    shipMany(1, 100);
    var hundred = List.of(statementsOf(ada, listing), statementsOf(sam, listing));

    assertEquals(100, ((List<?>) read(sam, listing).get("specs")).size());
    assertEquals(one, hundred, "an admin's, then a scoped reader's");
  }

  private void shipMany(int from, int to) {
    for (var index = from; index < to; index++) {
      var id = "d" + index;
      spec(id, "alpha");
      move(id, SpecStatus.DRAFT, SpecStatus.PENDING);
      build("bd" + index, id, "alpha");
      var passed = review(id, 1, true, finding("F" + index));
      serve("vd" + index, passed, id, Lane.REVIEW);
      move(id, SpecStatus.PENDING, SpecStatus.DONE);
    }
  }

  private long statementsOf(String token, String path) throws Exception {
    var before = db.statements();
    assertEquals(200, get(token, path).statusCode());
    return db.statements() - before;
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

    var minute = 100;
    for (var token : List.of(ada, sam)) {
      for (var path :
          List.of(
              "/v1/specs/auth/measures",
              "/v1/specs/auth?measures=true",
              "/v1/specs:measures?status=awaiting_merge&done_since=2026-11-01")) {
        at(minute++);
        read(token, path);
      }
    }

    assertEquals(before, everyRow());
  }
}
