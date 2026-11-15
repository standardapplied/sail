/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SyncConfig;
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
import picocli.CommandLine;

/**
 * Medium 7: one selector for the verbs that read, attach to or stop a run, and the one form every
 * printed command for a run takes, so it reaches that run however many are live.
 */
class RunSelectorTest {

  private static final String BILLING_RUN = "019fee00-0000-7000-8000-000000000001";
  private static final String AUTH_RUN = "019fee00-0000-7000-8000-000000000002";
  private static final String ROOM_RUN = "019fee00-0000-7000-8000-000000000003";
  private static final String ADHOC_RUN = "019fee00-0000-7000-8000-000000000004";
  private static final String BETA_RUN = "019fee00-0000-7000-8000-000000000005";

  private static RunStore.RunRow run(String id, String specId) {
    return new RunStore.RunRow(
        id,
        "acme",
        specId,
        "eng",
        specId == null ? "adhoc" : "build",
        "claude-code",
        null,
        "t",
        null,
        null,
        "running",
        null,
        "l",
        "u",
        Instant.EPOCH,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** A verb that takes the selector, as every run verb does. */
  @CommandLine.Command(name = "verb")
  static final class Verb {
    @CommandLine.Parameters(index = "0")
    String project;

    @CommandLine.Mixin RunSelector selector = new RunSelector();
  }

  private static RunSelector parsed(String... args) {
    var verb = new Verb();
    new CommandLine(verb).parseArgs(args);
    return verb.selector;
  }

  /**
   * One resolution for log, report, status and attach: a spec names its newest run, live or ended,
   * a run names itself and must be of the project, neither names the project's newest; a spec with
   * a room turn live beside its build names no one of them, and the refusal names each by its run.
   */
  @Test
  void aSelectorResolvesOneRunAndASpecWithTwoLiveRunsIsRefusedNamingEach(@TempDir Path dir)
      throws Exception {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme", "beta");
      new FdeStore(db).add("mady", "Mady", null, "member");
      var runs = new RunStore(db);
      seed(runs, BILLING_RUN, "acme", "billing", "build");
      Acting.system(() -> runs.finish(BILLING_RUN, "running", "stopped", 0, null));
      seed(runs, AUTH_RUN, "acme", "auth", "build");
      seed(runs, ROOM_RUN, "acme", "auth", "room");
      seed(runs, ADHOC_RUN, "acme", null, "adhoc");
      seed(runs, BETA_RUN, "beta", null, "adhoc");
      var operations = operations(db, dir);

      var several =
          assertThrows(ApiException.class, () -> resolved(operations, "acme", "--spec", "auth"));

      assertEquals(ErrorCode.CONFLICT, several.failure().errorCode());
      assertEquals(
          "Name the one: sail agent log acme --run "
              + AUTH_RUN
              + ", sail agent log acme --run "
              + ROOM_RUN
              + ".",
          several.failure().remedy().plain());
      assertEquals(Optional.of(BILLING_RUN), resolved(operations, "acme", "--spec", "billing"));
      assertEquals(Optional.of(ADHOC_RUN), resolved(operations, "acme", "--run", ADHOC_RUN));
      assertEquals(Optional.empty(), resolved(operations, "acme", "--run", BETA_RUN));
      assertEquals(Optional.of(ADHOC_RUN), resolved(operations, "acme"));
      assertThrows(
          CliCommand.Refused.class,
          () -> resolved(operations, "acme", "--spec", "auth", "--run", AUTH_RUN));
    }
  }

  private static Optional<String> resolved(HostOperations operations, String... args) {
    return Actor.readingAs(
        () -> operations.identity().operator(),
        () ->
            parsed(args)
                .resolve(operations.dispatching(), "agent log", "acme", "mady", run -> true)
                .map(RunStore.RunRow::id));
  }

  private static void seed(RunStore runs, String id, String project, String specId, String role) {
    Acting.system(
        () ->
            runs.create(
                id, project, specId, "mady", role, "claude-code", null, "t", 1, null, null, "u"));
  }

  private static HostOperations operations(Sqlite db, Path dir) {
    return OperationsFactory.create(db, SHELL, null, SyncScheduler.disabled(), SessionYield.NONE)
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
  void aPrintedCommandNamesASpecsRunByItsSpecAndAnyOtherByItsId() {
    assertEquals(" --spec auth", RunSelector.of(run(AUTH_RUN, "auth")));
    assertEquals(" --run " + ADHOC_RUN, RunSelector.of(run(ADHOC_RUN, null)));
  }
}
