/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLogCommandTest {

  @TempDir Path tempDir;

  private static final String RUN = "0195e0a0-1111-7abc-8def-0123456789ab";

  private static RunStore.RunRow run(String role) {
    return run(role, "/a/path/sync/could/have/written");
  }

  private static RunStore.RunRow run(String role, String logPath) {
    return new RunStore.RunRow(
        RUN,
        "acme",
        "auth",
        "node-a",
        role,
        "claude-code",
        "feat/x",
        "do it",
        1,
        null,
        "running",
        null,
        logPath,
        null,
        Instant.EPOCH,
        null,
        List.of(),
        null,
        null,
        null);
  }

  private static final String STREAM_EVENT =
      "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Reading.\"}]}}";

  @Test
  void jsonOutputStreamsTheRawStructuredLineForMachineConsumers() {
    assertEquals(
        STREAM_EVENT,
        AgentLogCommand.renderForLog(STREAM_EVENT, true),
        "--json (incl. --follow --json) must stream raw NDJSON events, not human-rendered text");
  }

  @Test
  void humanOutputRendersStreamJsonToReadableText() {
    assertEquals("Reading.", AgentLogCommand.renderForLog(STREAM_EVENT, false));
  }

  @Test
  void aRunsLogIsItsOwnRunScopedFileWhicheverLaneItRanIn() {
    for (var role : List.of("build", "review", "fix", "room")) {
      assertEquals(
          "/home/dev/.sail/runs/" + RUN + "/agent.log",
          AgentLogCommand.logPathFrom(Optional.of(run(role))),
          "a " + role + " run's log is derived from its id: one file shape for every lane");
    }
  }

  @Test
  void aReviewRunRecordedBeforeTheUpgradeIsStillReadFromItsReviewLog() {
    var legacy = "/home/dev/.sail/runs/" + RUN + "/review.log";

    assertEquals(legacy, AgentLogCommand.logPathFrom(Optional.of(run("review", legacy))));
  }

  /**
   * {@code sail agent log} reads as this box's operator, as the log route reads as its caller: a
   * project outside the operator's scope is not in the catalog, and a run the operator may not
   * operate is refused as a stop refuses it, so a viewer does not read its own run's log.
   */
  @Test
  void aLogIsReadAsTheBoxsOperatorTheCommandLineBindsAndDecidedAsTheLogRouteDecidesIt()
      throws Exception {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme", "beta");
      var roster = new FdeStore(db);
      roster.add("mady", "Mady", null, "viewer");
      roster.restrict("mady");
      roster.grant("mady", "acme");
      Acting.system(
          () ->
              new RunStore(db)
                  .create(
                      RUN,
                      "acme",
                      null,
                      "mady",
                      "adhoc",
                      "claude-code",
                      null,
                      "do it",
                      1,
                      null,
                      null,
                      "unit"));
      var operations = operations(db, tempDir);

      var readOnly =
          assertThrows(
              ApiException.class, () -> asTheOperator(operations, "acme", Optional.empty()));
      var outside =
          assertThrows(
              ApiException.class, () -> asTheOperator(operations, "beta", Optional.empty()));

      assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, readOnly.failure().errorCode());
      assertEquals(
          "Your credential is read-only and cannot stop runs or read their logs.",
          readOnly.getMessage());
      assertEquals(
          "Ask an admin for a member or admin credential.", readOnly.failure().remedy().plain());
      assertEquals(ErrorCode.PROJECT_NOT_IN_CATALOG, outside.failure().errorCode());
      assertEquals("Project 'beta' is not in the catalog.", outside.getMessage());

      db.execute("UPDATE fdes SET role = 'member' WHERE handle = 'mady'");
      assertEquals(
          AgentUnit.forRun(RUN).logPath(), asTheOperator(operations, "acme", Optional.empty()));
      assertNull(asTheOperator(operations, "acme", Optional.of(Lane.REVIEW)));
    }
  }

  /** The log path the command line finds, reading as the box's operator as it binds one. */
  private static String asTheOperator(
      HostOperations operations, String project, Optional<Lane> lane) {
    return Actor.readingAs(
        () -> operations.identity().operator(),
        () -> AgentLogCommand.logPath(operations, project, "mady", lane));
  }

  private static HostOperations operations(Sqlite db, Path dir) {
    return OperationsFactory.create(
            db, SHELL, null, null, SyncScheduler.disabled(), SessionYield.NONE)
        .useControlPlane(
            db,
            dir,
            new SyncOperations(
                db,
                "box",
                dir,
                () -> new SyncConfig("node", "sail@main", "mady", "mady-box"),
                target -> {
                  throw new IOException("main unavailable");
                }));
  }

  private static final ShellExec SHELL =
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

  @Test
  void noRunMeansNoLog() {
    assertNull(
        AgentLogCommand.logPathFrom(Optional.empty()),
        "every agent session is a run, so a lane with no run has no log to show");
  }
}
