/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.harness.LoginOutcome;
import ai.singlr.sail.harness.RunReport;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one place an outcome of a run's login is worded and said ({@link RunReports}): what it names
 * of the login and how to replace it, and what it says nothing of.
 */
class RunReportsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runs;
  private final List<String> told = new ArrayList<>();
  private final List<Event> published = new ArrayList<>();
  private RunReports reports;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runs = new RunStore(db);
    reports =
        new RunReports(
            run -> RunReport.NONE, (spec, body) -> told.add(spec + ": " + body), published::add);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private RunStore.RunRow run(String specId, String branch, RunStore.RunLogin login) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () -> {
          runs.create(
              id,
              "acme",
              specId,
              "eng",
              "build",
              "claude-code",
              branch,
              "t",
              null,
              null,
              "/l",
              "u");
          if (login != null) {
            runs.recordLaunch(id, new RunStore.Launched(null, null, null, login, null));
          }
        });
    return runs.findById(id).orElseThrow();
  }

  private static RunReport said(LoginOutcome outcome) {
    return new RunReport(RunUsage.NONE, outcome);
  }

  @Test
  void aNamedKeyIsReplacedUnderItsOwnNameAndKindAndALimitWithNoTimeNorBranchSaysNeither() {
    var key = run("auth", null, new RunStore.RunLogin("work", "api-key"));

    reports.recorded(key, said(new LoginOutcome.Refused()));
    reports.recorded(key, said(new LoginOutcome.Limited(null)));

    assertEquals(
        List.of(
            "auth: Claude Code refused eng's login 'work' (API key), so its run stopped. Create an"
                + " API key in the Claude Console, then sail agent login claude-code --kind api-key"
                + " --name work.",
            "auth: Claude Code says the account behind eng's login 'work' (API key) is out of"
                + " quota, so its run stopped."),
        told);
    assertEquals(
        List.of("refused", "limited"),
        published.stream().map(event -> event.data().get("outcome")).toList());
  }

  /** A run given no stored login, a report of nothing, or none read, says nothing. */
  @Test
  void aRunOnItsContainersLoginOrAReportSayingNothingOfItSaysNothing() {
    var container = run("auth", "b", RunStore.RunLogin.CONTAINER);
    var unlaunched = run("auth", "b", null);
    var specless = run(null, "b", new RunStore.RunLogin("default", "token"));
    var stored = run("auth", "b", new RunStore.RunLogin("default", "token"));

    reports.recorded(container, said(new LoginOutcome.Refused()));
    reports.recorded(unlaunched, said(new LoginOutcome.Refused()));
    reports.recorded(specless, said(new LoginOutcome.Refused()));
    reports.recorded(stored, RunReport.NONE);
    reports.recorded(stored, null);

    assertEquals(List.of(), told);
    assertEquals(List.of(), published);
  }

  /** A room that cannot be told never undoes the write that ended the run. */
  @Test
  void aRoomThatCannotBeToldIsSaidOnTheServersLogAndNothingThrows() {
    var refusing =
        new RunReports(
            run -> RunReport.NONE,
            (spec, body) -> {
              throw new IllegalStateException("database is locked");
            },
            published::add);

    refusing.recorded(
        run("auth", "b", new RunStore.RunLogin("default", "token")),
        said(new LoginOutcome.Overridden("apiKeyHelper")));

    assertEquals(List.of(), published);
  }
}
