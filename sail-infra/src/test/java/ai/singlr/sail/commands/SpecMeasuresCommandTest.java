/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.api.TestControlPlane;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestRuns;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code sail spec show} and {@code sail spec list --measures} print the measures the server
 * answers, a section and a listing pinned whole, and their {@code --json} is that answer (M10). The
 * server is a real one over the production operations and stores.
 */
class SpecMeasuresCommandTest {

  private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");

  @TempDir Path tempDir;
  private Clock clock;
  private Sqlite db;
  private EventBus bus;
  private SailApiServer server;
  private String token;

  @BeforeEach
  void aServerHoldingTwoDoneSpecs() throws Exception {
    clock = DateTimeUtils.clock;
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "alpha");
    token =
        Acting.system(
            () ->
                new TokenStore(db)
                    .create(
                        "test",
                        "admin",
                        new FdeStore(db).add("test", "Test", "test@example.com", "admin").id(),
                        null)
                    .token());
    bus = new EventBus();
    var operations =
        TestControlPlane.on(
            OperationsFactory.create(
                db, new ShellExecutor(true), bus, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            new SyncConfig(null, null, null, "box-7"));
    server =
        new SailApiServer("127.0.0.1", 0, operations, TestAuth.tokens(db), bus, null, null, null);
    server.start();
    shipAuth();
    shipQuick();
  }

  @AfterEach
  void tearDown() {
    DateTimeUtils.clock = clock;
    server.close();
    bus.close();
    db.close();
  }

  private static void at(int minutes) {
    DateTimeUtils.clock = Clock.fixed(T0.plusSeconds(60L * minutes), ZoneOffset.UTC);
  }

  private void create(String id, String title) {
    var specs = new SpecStore(db);
    Acting.system(
        () ->
            specs.create(
                new SpecStore.SpecRow(
                    id,
                    "alpha",
                    title,
                    SpecStatus.DRAFT,
                    null,
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
    var specs = new SpecStore(db);
    Acting.system(() -> specs.compareAndSetStatus(id, from, to));
  }

  /** Ready at 09:10, built, passed at 10:10, awaiting merge at 10:15, done at 11:10. */
  private void shipAuth() {
    at(0);
    create("auth", "OAuth flow");
    at(10);
    move("auth", SpecStatus.DRAFT, SpecStatus.PENDING);
    at(20);
    move("auth", SpecStatus.PENDING, SpecStatus.IN_PROGRESS);
    var runs = new RunStore(db);
    Acting.system(
        () -> {
          runs.reserveDispatch(
              "b1",
              "alpha",
              "auth",
              null,
              "test",
              "build",
              List.of(),
              "claude-code",
              "agent/auth",
              "build",
              "/var/log/b1",
              "sail-agent-b1",
              null);
          TestRuns.complete(runs, "b1", "completed", 0);
        });
    at(70);
    var reviews = new ReviewStore(db);
    Acting.system(() -> reviews.pass(reviews.createReview("auth", 1), () -> {}));
    at(75);
    move("auth", SpecStatus.IN_PROGRESS, SpecStatus.AWAITING_MERGE);
    at(130);
    move("auth", SpecStatus.AWAITING_MERGE, SpecStatus.DONE);
  }

  /** Done straight from its draft at 09:05, never ready, built or reviewed. */
  private void shipQuick() {
    at(0);
    create("quick", "A typo");
    at(5);
    move("quick", SpecStatus.DRAFT, SpecStatus.DONE);
  }

  private String run(String... args) {
    var originalOut = System.out;
    var out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      System.setProperty("SAIL_SERVER", "http://127.0.0.1:" + server.port());
      System.setProperty("SAIL_TOKEN", token);
      var command = new CommandLine(new SpecCommand());
      command.setExecutionExceptionHandler((failure, line, parsed) -> 1);
      assertEquals(0, command.execute(args), String.join(" ", args));
      return out.toString(StandardCharsets.UTF_8);
    } finally {
      System.setOut(originalOut);
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
  }

  @Test
  void specShowPrintsTheMeasuresSectionOfWhatTheServerAnswered() {
    assertEquals(
        """
          Spec: auth
          Project: alpha
          Title: OAuth flow
          Status: done

          Measures (counted on box-7):
            Ready: 2026-11-01T09:10:00.000Z (spec)
            Verified: 2026-11-01T10:10:00.000Z (review)
            Awaiting merge: 2026-11-01T10:15:00.000Z (spec)
            Done: 2026-11-01T11:10:00.000Z (spec)
            Ready → verified: 1h 0m 0s
            Verified → done: 1h 0m 0s
            Ready → done: 2h 0m 0s
            In awaiting merge: 55m 0s
            Rework: 1 review round, 0 review errors, 1 build run, 0 fix runs, 0 carried findings
            No recorded human code act: yes (an upper bound)
            Cost: unknown (runs not reported) over 0 of 1 run, tokens unknown (runs not reported)
            Budget: none set
            Human minutes: 0 from 0 people (terminal input 0, post 0, review act 0, focus 0), a lower bound
        """,
        run("show", "auth"));
  }

  private static final String KNOWN_ROWS =
      """
            • auth  1 review round, 0 review errors, 1 build run, 0 fix runs, 0 carried findings · Ready → verified 1h 0m 0s · Verified → done 1h 0m 0s · Ready → done 2h 0m 0s · In awaiting merge 55m 0s · no recorded human code act: yes (an upper bound) · cost unknown (runs not reported) over 0 of 1 run, tokens unknown (runs not reported) · human minutes 0 from 0 people (terminal input 0, post 0, review act 0, focus 0), a lower bound
            • quick  0 review rounds, 0 review errors, 0 build runs, 0 fix runs, 0 carried findings · Ready → verified unknown (2 inputs missing) · Verified → done unknown (1 input missing) · Ready → done unknown (1 input missing) · In awaiting merge unknown (1 input missing) · no recorded human code act: yes (an upper bound) · cost $0.00 over 0 of 0 runs, 0 tokens over 0 (in 0, out 0, cache read 0, cache write 0) · human minutes 0 from 0 people (terminal input 0, post 0, review act 0, focus 0), a lower bound
        """;

  @Test
  void specListMeasuresPrintsARowPerSpecThenTheMedianAndP90OfEachNumber() {
    assertEquals(
        "  Measures of 2 specs (counted on box-7):\n"
            + KNOWN_ROWS
            + """
              Median and p90, nearest rank, over the numbers known exactly:
                Ready → verified: median 1h 0m 0s, p90 1h 0m 0s, over 1; left out 1 unknown, 0 lower bounds, 0 errors
                Verified → done: median 1h 0m 0s, p90 1h 0m 0s, over 1; left out 1 unknown, 0 lower bounds, 0 errors
                Ready → done: median 2h 0m 0s, p90 2h 0m 0s, over 1; left out 1 unknown, 0 lower bounds, 0 errors
                In awaiting merge: median 55m 0s, p90 55m 0s, over 1; left out 1 unknown, 0 lower bounds, 0 errors
                Review rounds: median 0, p90 1, over 2; left out 0 unknown, 0 lower bounds, 0 errors
                Review errors: median 0, p90 0, over 2; left out 0 unknown, 0 lower bounds, 0 errors
                Build runs: median 0, p90 1, over 2; left out 0 unknown, 0 lower bounds, 0 errors
                Fix runs: median 0, p90 0, over 2; left out 0 unknown, 0 lower bounds, 0 errors
                Carried findings: median 0, p90 0, over 2; left out 0 unknown, 0 lower bounds, 0 errors
                Tokens: median 0, p90 0, over 1; left out 1 unknown, 0 lower bounds, 0 errors
                Dollars: median $0.00, p90 $0.00, over 1; left out 1 unknown, 0 lower bounds, 0 errors
                Human minutes: median 0, p90 0, over 2; left out 0 unknown, 0 lower bounds, 0 errors
                No recorded human code act: 2 of 2 done (an upper bound); left out 0 unknown
            """,
        run("list", "--project", "alpha", "--status", "done", "--measures"));
    assertEquals(
        "  Measures of 2 specs (counted on box-7), done any time to 2026-11-01; 0 specs"
            + " with no known move to done left out:\n"
            + KNOWN_ROWS,
        run(
                "list",
                "--project",
                "alpha",
                "--status",
                "done",
                "--measures",
                "--done-until",
                "2026-11-01")
            .lines()
            .limit(3)
            .map(line -> line + "\n")
            .reduce("", String::concat));
  }

  @Test
  void aListingOfNoSpecsSaysWhatItWouldHaveSummarizedAndADurationReadsToTheSecond() {
    var nothing =
        "median unknown, p90 unknown, over 0; left out 0 unknown, 0 lower bounds, 0 errors";
    assertEquals(
        "  Measures of 0 specs (counted on this box), done any time to 2026-10-01; 0 specs with no"
            + " known move to done left out:\n"
            + "  Median and p90, nearest rank, over the numbers known exactly:\n"
            + String.join(
                "",
                List.of(
                        "Ready → verified",
                        "Verified → done",
                        "Ready → done",
                        "In awaiting merge",
                        "Review rounds",
                        "Review errors",
                        "Build runs",
                        "Fix runs",
                        "Carried findings",
                        "Tokens",
                        "Dollars",
                        "Human minutes")
                    .stream()
                    .map(name -> "    " + name + ": " + nothing + "\n")
                    .toList())
            + "    No recorded human code act: 0 of 0 done (an upper bound); left out 0 unknown\n",
        run(
            "list",
            "--project",
            "alpha",
            "--status",
            "done",
            "--measures",
            "--done-until",
            "2026-10-01"));
    assertEquals(
        List.of("42s", "5m 0s", "59m 59s", "2h 0m 0s", "3d 4h 5m 0s"),
        List.of(
            MeasuresText.duration(42),
            MeasuresText.duration(300),
            MeasuresText.duration(3599),
            MeasuresText.duration(7200),
            MeasuresText.duration(3 * 86400 + 4 * 3600 + 300)));
  }

  /**
   * What a box with no Sail id, a lower bound, a count the reader may not see, an interval no clock
   * agrees with and a cost over its budget read as, from an answer in the shape the server serves.
   */
  @Test
  void aLowerBoundReadsAtLeastAndAnUnknownOrAnErrorSaysWhy() {
    var at = Map.of("at", "2026-11-01T09:00:00.000Z", "source", "spec");
    var times = new LinkedHashMap<String, Object>();
    times.put("ready", at);
    times.put("verified", Map.of("unknown", "hidden_rows"));
    times.put("awaiting_merge", Map.of("unknown", "not_reached"));
    times.put("done", at);
    var intervals = new LinkedHashMap<String, Object>();
    intervals.put("ready_to_done", Map.of("error", "done at x is before ready at y."));
    var rework = new LinkedHashMap<String, Object>();
    rework.put("review_rounds", Map.of("unknown", "hidden_rows"));
    rework.put("review_errors", Map.of("unknown", "hidden_rows"));
    rework.put("build_runs", Map.of("count", 3, "at_least", true));
    rework.put("fix_runs", Map.of("count", 1, "at_least", true));
    rework.put("carried_findings", Map.of("unknown", "hidden_rows"));
    var cost = new LinkedHashMap<String, Object>();
    cost.put("runs", Map.of("count", 4, "at_least", true));
    cost.put("runs_without_usage", Map.of("count", 1, "at_least", true));
    cost.put("runs_without_cost", Map.of("count", 2, "at_least", true));
    cost.put("tokens", Map.of("count", 1234567, "at_least", true));
    cost.put("tokens_in", Map.of("count", 1000, "at_least", true));
    cost.put("tokens_out", Map.of("count", 567, "at_least", true));
    cost.put("tokens_cache_read", Map.of("count", 1233000, "at_least", true));
    cost.put("tokens_cache_write", Map.of("count", 0, "at_least", true));
    cost.put("usd", Map.of("usd", 7.204, "at_least", true));
    cost.put("budget", Map.of("tokens", 1000000, "usd", 5.0));
    cost.put("over_budget", true);
    var channels = new LinkedHashMap<String, Object>();
    channels.put("terminal_input", Map.of("count", 3));
    channels.put("post", Map.of("count", 1));
    channels.put("review_act", Map.of("count", 0));
    channels.put("focus", Map.of("count", 2));
    var minutes = new LinkedHashMap<String, Object>();
    minutes.put("minutes", Map.of("count", 5));
    minutes.put("by_channel", channels);
    minutes.put("people", Map.of("count", 1));
    minutes.put("bound", "lower");
    var answer = new LinkedHashMap<String, Object>();
    answer.put("box", Map.of("unknown", "no_box_id"));
    answer.put("times", times);
    answer.put("intervals", intervals);
    answer.put("rework", rework);
    answer.put("no_recorded_human_code_act", Map.of("unknown", "hidden_rows"));
    answer.put("cost", cost);
    answer.put("human_minutes", minutes);
    var notHeld = new LinkedHashMap<>(cost);
    notHeld.put("budget", Map.of("unknown", "not_set"));
    var unknownOver = new LinkedHashMap<>(cost);
    unknownOver.put("over_budget", Map.of("unknown", "runs_not_held"));
    var hidden = new LinkedHashMap<>(cost);
    hidden.put("runs", Map.of("unknown", "hidden_rows"));

    assertEquals(
        List.of(
            "  @|bold Measures|@ (counted on this box, unknown: no box id):",
            "    Ready: 2026-11-01T09:00:00.000Z (spec)",
            "    Verified: unknown: hidden rows",
            "    Awaiting merge: unknown: not reached",
            "    Done: 2026-11-01T09:00:00.000Z (spec)",
            "    Ready → done: error: done at x is before ready at y.",
            "    Rework: review rounds unknown (hidden rows), review errors unknown (hidden rows),"
                + " at least 3 build runs, at least 1 fix run, carried findings unknown (hidden"
                + " rows)",
            "    No recorded human code act: unknown: hidden rows",
            "    Cost: at least $7.20 over 2 of at least 4 runs, at least 1,234,567 tokens over 3 (in"
                + " 1,000, out 567, cache read 1,233,000, cache write 0)",
            "    Budget: $5.00, 1,000,000 tokens: over",
            "    Human minutes: 5 from 1 person (terminal input 3, post 1, review act 0, focus 2), a"
                + " lower bound"),
        MeasuresText.section(answer));
    answer.put("cost", notHeld);
    assertEquals("    Budget: none set", MeasuresText.section(answer).get(9));
    answer.put("cost", unknownOver);
    assertEquals(
        "    Budget: $5.00, 1,000,000 tokens: unknown (runs not held)",
        MeasuresText.section(answer).get(9));
    cost.put("over_budget", false);
    answer.put("cost", cost);
    assertEquals(
        "    Budget: $5.00, 1,000,000 tokens: within", MeasuresText.section(answer).get(9));
    answer.put("cost", hidden);
    assertEquals("    Cost: unknown (hidden rows)", MeasuresText.section(answer).get(8));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theJsonOfBothVerbsIsTheOneRecordTheServerComputed() {
    var shown = YamlUtil.parseMap(run("show", "auth", "--json"));
    var listed =
        YamlUtil.parseMap(
            run("list", "--project", "alpha", "--status", "done", "--measures", "--json"));

    var measures = (Map<String, Object>) shown.get("measures");
    assertEquals(
        Map.of(
            "review_rounds",
            Map.of("count", 1),
            "review_errors",
            Map.of("count", 0),
            "build_runs",
            Map.of("count", 1),
            "fix_runs",
            Map.of("count", 0),
            "carried_findings",
            Map.of("count", 0)),
        measures.get("rework"));
    assertEquals(measures, ((List<Object>) listed.get("specs")).getFirst());
  }

  @Test
  void aWindowWithoutMeasuresAndMeasuresWithoutDoneAreRefusedNamingTheFlagEachNeeds() {
    assertEquals(
        "✗ --done-since and --done-until choose which measures to list; add --measures.",
        refused("list", "--project", "alpha", "--status", "done", "--done-since", "2026-11-01"));
    assertEquals(
        "✗ --measures measures shipped changes; add --status done.",
        refused("list", "--project", "alpha", "--measures"));
    assertEquals(
        "✗ --measures measures shipped changes; add --status done.",
        refused("list", "--project", "alpha", "--all", "--measures"));
  }

  private String refused(String... args) {
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      System.setProperty("SAIL_SERVER", "http://127.0.0.1:" + server.port());
      System.setProperty("SAIL_TOKEN", token);
      var command = new CommandLine(new SpecCommand());
      command.setExecutionExceptionHandler((failure, line, parsed) -> 1);
      assertEquals(1, command.execute(args));
    } finally {
      System.setErr(originalErr);
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
    return err.toString(StandardCharsets.UTF_8).strip();
  }
}
