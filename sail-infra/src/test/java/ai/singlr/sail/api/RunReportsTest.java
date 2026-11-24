/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.harness.LoginOutcome;
import ai.singlr.sail.harness.RunReport;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one place an outcome of a run's login is read, worded and said ({@link RunReports}): what it
 * names of the login and how to replace it, what it says nothing of, and that the write and the
 * room's line commit together.
 */
class RunReportsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runs;
  private final List<String> told = new ArrayList<>();
  private final List<Event> published = new ArrayList<>();

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runs = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private RunStore.RunRow run(String specId, String branch, RunStore.RunLogin login) {
    return run(specId, branch, login, "build");
  }

  private RunStore.RunRow run(String specId, String branch, RunStore.RunLogin login, String role) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () -> {
          runs.create(
              id, "acme", specId, "eng", role, "claude-code", branch, "t", null, null, "/l", "u");
          if (login != null) {
            runs.recordLaunch(id, new RunStore.Launched(null, null, null, login, null));
          }
        });
    return runs.findById(id).orElseThrow();
  }

  private RunReports reporting(LoginOutcome outcome) {
    return new RunReports(
        run -> new RunReport(RunUsage.NONE, outcome),
        (spec, body) -> told.add(spec + ": " + body),
        published::add);
  }

  private boolean finished(RunReports reports, RunStore.RunRow run) {
    return Acting.system(
        () ->
            reports.finish(
                runs, run, report -> runs.finish(run.id(), "running", "stopped", 1, report)));
  }

  @Test
  void aNamedKeyIsReplacedUnderItsOwnNameAndKindAndALimitWithNoTimeNorBranchSaysNeither() {
    var key = new RunStore.RunLogin("work", "api-key");

    assertTrue(finished(reporting(new LoginOutcome.Refused()), run("auth", null, key)));
    assertTrue(finished(reporting(new LoginOutcome.Limited(null)), run("auth", null, key)));

    assertEquals(
        List.of(
            "auth: Claude Code refused eng's login 'work' (API key), so its run"
                + " stopped. Create an API key in the Claude Console, then sail agent login"
                + " claude-code --name work. Then build it again: sail spec dispatch -p"
                + " acme --spec auth --restart.",
            "auth: Claude Code says the account behind eng's login 'work' (API key) is out of"
                + " quota, so its run stopped."),
        told);
    assertEquals(
        List.of("refused", "limited"),
        published.stream().map(event -> event.data().get("outcome")).toList());
  }

  /**
   * Round 4, item 1: a limit holds nothing, and its sentence is true for its lane: a build or a fix
   * keeps what it committed on its branch, and a reviewer, which commits nothing, says no branch.
   */
  /**
   * Minor F-m4 of the final look: a report the idle sweep reads late, for a done spec, says to
   * reopen it with the restart that does, never to build it again.
   */
  @Test
  void aDoneSpecsLateReportSaysToReopenItNotToBuildItAgain() {
    var run = run("auth", null, new RunStore.RunLogin("work", "api-key"));

    assertTrue(
        Acting.system(
            () ->
                reporting(new LoginOutcome.Refused())
                    .finishDone(
                        runs,
                        run,
                        report -> runs.finish(run.id(), "running", "stopped", 1, report))));

    assertEquals(
        List.of(
            "auth: Claude Code refused eng's login 'work' (API key), so its run"
                + " stopped. Create an API key in the Claude Console, then sail agent login"
                + " claude-code --name work. Its spec is done; to work on it again, reopen"
                + " it with sail spec dispatch -p acme --spec auth --restart."),
        told);
  }

  @Test
  void aLimitSaysTheBranchOnlyForALaneThatCommitsAndHoldsNoLane() {
    var token = new RunStore.RunLogin("default", "api-key");
    var limited = reporting(new LoginOutcome.Limited("15:04"));
    var runs =
        List.of(
            run("auth", "feat/auth", token, "build"),
            run("auth", "feat/auth", token, "fix"),
            run("auth", "feat/auth", token, "review"));

    runs.forEach(run -> finished(limited, run));

    var quota =
        "auth: Claude Code says the account behind eng's login 'default' (API key) is out"
            + " of quota until 15:04, so its run stopped";
    assertEquals(
        List.of(
            quota + "; what it committed stays on branch feat/auth.",
            quota + "; what it committed stays on branch feat/auth.",
            quota + "."),
        told);
    runs.forEach(
        run ->
            assertFalse(
                this.runs.findById(run.id()).orElseThrow().stoppedForLogin(),
                "a limit resets by itself: " + run.role()));
  }

  /**
   * Round 4, item 4: the event names each outcome by a constant written out, never by its class;
   * one its harness never saw has a name too, though it is never published.
   */
  @Test
  void eachOutcomeHasTheNameItsEventCarries() {
    assertEquals(
        List.of("refused", "limited", "undelivered"),
        List.of(
            RunReports.outcome(new LoginOutcome.Refused()),
            RunReports.outcome(new LoginOutcome.Limited(null)),
            RunReports.outcome(new LoginOutcome.Undelivered())));
  }

  /** A run given no stored login, or a report of nothing, or none read, says nothing. */
  @Test
  void aRunOnItsContainersLoginOrAReportSayingNothingOfItSaysNothing() {
    var refused = reporting(new LoginOutcome.Refused());

    finished(refused, run("auth", "b", RunStore.RunLogin.CONTAINER));
    finished(refused, run("auth", "b", null));
    finished(refused, run(null, "b", new RunStore.RunLogin("default", "api-key")));
    finished(
        new RunReports(run -> RunReport.NONE, (spec, body) -> told.add(body), published::add),
        run("auth", "b", new RunStore.RunLogin("default", "api-key")));
    finished(
        new RunReports(run -> null, (spec, body) -> told.add(body), published::add),
        run("auth", "b", new RunStore.RunLogin("default", "api-key")));

    assertEquals(List.of(), told);
    assertEquals(List.of(), published);
  }

  /** A write that does not record the report says nothing: another finisher's write did. */
  @Test
  void aFinishWhoseWriteRecordsNothingSaysNothing() {
    var run = run("auth", "b", new RunStore.RunLogin("default", "api-key"));

    assertFalse(
        Acting.system(
            () -> reporting(new LoginOutcome.Refused()).finish(runs, run, report -> false)));

    assertEquals(List.of(), told);
    assertEquals(List.of(), published);
  }

  /**
   * Ruling 6: the room's line is written in the finish's own transaction: while the room is told,
   * another connection to the database still sees the run running, so a crash between the two loses
   * neither, and a write that fails after the line leaves neither; a room that cannot be told still
   * lets the event out.
   */
  @Test
  void theRoomsLineCommitsWithTheFinishAndARoomThatThrowsStillLetsTheEventOut() {
    var messages = new MessageStore(db);
    var run = run("auth", "b", new RunStore.RunLogin("default", "api-key"));
    var seenElsewhere = new ArrayList<String>();
    var first = run("auth", "b", new RunStore.RunLogin("default", "api-key"));
    try (var other = Sqlite.open(tempDir.resolve("test.db"))) {
      var asTold =
          new RunReports(
              read -> new RunReport(RunUsage.NONE, new LoginOutcome.Refused()),
              (spec, body) ->
                  seenElsewhere.add(
                      new RunStore(other).findById(first.id()).orElseThrow().status()),
              published::add);
      assertTrue(finished(asTold, first));
    }
    assertEquals(List.of("running"), seenElsewhere, "told before the write committed, with it");
    published.clear();
    var intoTheRoom =
        new RunReports(
            read -> new RunReport(RunUsage.NONE, new LoginOutcome.Refused()),
            (spec, body) -> messages.append(spec, MessageStore.SAIL_AUTHOR, body, null),
            published::add);
    assertThrows(
        IllegalStateException.class,
        () ->
            Acting.system(
                () ->
                    intoTheRoom.finish(
                        runs,
                        run,
                        report -> {
                          runs.finish(run.id(), "running", "stopped", 1, report);
                          throw new IllegalStateException("the disk filled");
                        })));
    assertEquals("running", runs.findById(run.id()).orElseThrow().status(), "rolled back");
    assertEquals(List.of(), messages.list("auth", null, 10), "and its line with it");
    assertTrue(finished(intoTheRoom, run), "the next finisher says it");
    assertEquals(1, messages.list("auth", null, 10).size());
    published.clear();

    var throwing =
        new RunReports(
            read -> new RunReport(RunUsage.NONE, new LoginOutcome.Limited(null)),
            (spec, body) -> {
              throw new IllegalStateException("database is locked");
            },
            published::add);
    assertTrue(finished(throwing, run("auth", "b", new RunStore.RunLogin("default", "api-key"))));
    assertEquals(List.of("limited"), published.stream().map(e -> e.data().get("outcome")).toList());
  }

  /** M3: a login its harness never saw is the operator's to hear: no room line, no event. */
  @Test
  void aLoginItsHarnessNeverSawIsSaidToTheOperatorAlone() {
    var original = System.err;
    var log = new ByteArrayOutputStream();
    System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
    RunStore.RunRow run;
    try {
      run = run("auth", "b", new RunStore.RunLogin("default", "api-key"));
      finished(reporting(new LoginOutcome.Undelivered()), run);
    } finally {
      System.setErr(original);
    }

    assertEquals(List.of(), told);
    assertEquals(List.of(), published);
    assertEquals(
        "  [api] run "
            + run.id()
            + ": Claude Code saw no login, though Sail gave it eng's login 'default' (API"
            + " key): Sail's delivery of it failed, and the login itself was never tried.\n",
        log.toString(StandardCharsets.UTF_8));
    assertFalse(runs.findById(run.id()).orElseThrow().stoppedForLogin(), "an ordinary failure");
  }
}
