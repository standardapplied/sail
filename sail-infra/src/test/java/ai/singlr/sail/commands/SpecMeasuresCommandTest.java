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
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
        TestControlPlane.standalone(
            OperationsFactory.create(
                db,
                new ShellExecutor(true),
                bus,
                null,
                SyncScheduler.disabled(),
                SessionYield.NONE),
            db,
            tempDir);
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
          runs.complete("b1", "completed", 0);
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

          Measures (this box's record, %s):
            Ready: 2026-11-01T09:10:00.000Z (change log)
            Verified: 2026-11-01T10:10:00.000Z (review)
            Awaiting merge: 2026-11-01T10:15:00.000Z (change log)
            Done: 2026-11-01T11:10:00.000Z (change log)
            Ready → verified: 1h 0m
            Verified → done: 1h 0m
            Ready → done: 2h 0m
            In awaiting merge: 55m
            Rework: 1 review round, 1 dispatch attempt, 0 fix runs, 0 carried findings
            No recorded human code act: yes (an upper bound)
            Cost: unknown: not recorded (1 run without usage)
            Human minutes: unknown: not recorded
        """
            .formatted(HostInfo.hostname()),
        run("show", "auth"));
  }

  @Test
  void specListMeasuresPrintsARowPerSpecThenTheMedianAndP90OfEachNumber() {
    assertEquals(
        """
          Measures of 2 specs (this box's record, %s):
            • auth  1 review round, 1 dispatch attempt, 0 fix runs, 0 carried findings · Ready → verified 1h 0m · Verified → done 1h 0m · Ready → done 2h 0m · In awaiting merge 55m · no recorded human code act: yes (an upper bound)
            • quick  0 review rounds, 0 dispatch attempts, 0 fix runs, 0 carried findings · Ready → verified unknown (2 inputs missing) · Verified → done unknown (1 input missing) · Ready → done unknown (1 input missing) · In awaiting merge unknown (1 input missing) · no recorded human code act: yes (an upper bound)
          Median and p90, nearest rank, over the specs listed:
            Ready → verified: median 1h 0m, p90 1h 0m, over 1; left out 1 unknown, 0 errors
            Verified → done: median 1h 0m, p90 1h 0m, over 1; left out 1 unknown, 0 errors
            Ready → done: median 2h 0m, p90 2h 0m, over 1; left out 1 unknown, 0 errors
            In awaiting merge: median 55m, p90 55m, over 1; left out 1 unknown, 0 errors
            Review rounds: median 0, p90 1, over 2; left out 0 unknown, 0 errors
            Dispatch attempts: median 0, p90 1, over 2; left out 0 unknown, 0 errors
            Fix runs: median 0, p90 0, over 2; left out 0 unknown, 0 errors
            Carried findings: median 0, p90 0, over 2; left out 0 unknown, 0 errors
            No recorded human code act: 2 of 2 done (an upper bound); 0 not done
        """
            .formatted(HostInfo.hostname()),
        run("list", "--project", "alpha", "--status", "done", "--measures"));
    assertEquals(
        """
          Measures of 2 specs (this box's record, %s), done any time to 2026-11-01; 0 specs with no known move to done left out:
            • auth  1 review round, 1 dispatch attempt, 0 fix runs, 0 carried findings · Ready → verified 1h 0m · Verified → done 1h 0m · Ready → done 2h 0m · In awaiting merge 55m · no recorded human code act: yes (an upper bound)
        """
            .formatted(HostInfo.hostname()),
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
            .limit(2)
            .map(line -> line + "\n")
            .reduce("", String::concat));
  }

  @Test
  void aListingOfNoSpecsSaysSoAndADurationReadsInItsLargestUnits() {
    assertEquals(
        """
          Measures of 0 specs (this box's record):
          Median and p90, nearest rank, over the specs listed:
            No recorded human code act: 0 of 0 done (an upper bound); 0 not done
        """,
        run("list", "--project", "alpha", "--status", "cancelled", "--measures"));
    assertEquals(
        List.of("42s", "5m", "2h 0m", "3d 4h 5m"),
        List.of(
            MeasuresText.duration(42),
            MeasuresText.duration(300),
            MeasuresText.duration(7200),
            MeasuresText.duration(3 * 86400 + 4 * 3600 + 300)));
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
        Map.of("review_rounds", 1, "dispatch_attempts", 1, "fix_runs", 0, "carried_findings", 0),
        measures.get("rework"));
    assertEquals(measures, ((List<Object>) listed.get("specs")).getFirst());
  }

  @Test
  void aWindowWithoutMeasuresIsRefusedNamingTheFlagItNeeds() {
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      System.setProperty("SAIL_SERVER", "http://127.0.0.1:" + server.port());
      System.setProperty("SAIL_TOKEN", token);
      var command = new CommandLine(new SpecCommand());
      command.setExecutionExceptionHandler((failure, line, parsed) -> 1);
      var code = command.execute("list", "--project", "alpha", "--done-since", "2026-11-01");
      assertEquals(1, code);
    } finally {
      System.setErr(originalErr);
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
    assertEquals(
        "✗ --done-since and --done-until choose which measures to list; add --measures.",
        err.toString(StandardCharsets.UTF_8).strip());
  }
}
