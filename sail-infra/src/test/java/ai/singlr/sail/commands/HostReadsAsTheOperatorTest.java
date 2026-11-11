/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host CLI's reads of one project, made as the box's person the command line binds: a project
 * outside that person's scope is one the catalog does not hold, refused before any container is
 * asked.
 */
class HostReadsAsTheOperatorTest {

  private static final String ABSENT =
      "  ✗ Project 'beta' is not in the catalog.\n  Create it with 'sail project create beta', or"
          + " sync it from main with 'sail sync'.\n";

  private static final Actor ADA =
      new Actor("ada", Role.MEMBER, Actor.Lane.CLI).withScope(ProjectScope.of(List.of("alpha")));

  private static final ShellExec NO_INCUS = new ScriptedShellExecutor();

  @TempDir Path dir;
  private Path database;
  private Function<OperatorCommand, HostOperations> operations;

  @BeforeEach
  void aCatalogHoldingAlphaAndBetaReadByAnOperatorGrantedAlpha() {
    database = dir.resolve("control-plane.db");
    try (var db = Sqlite.open(database)) {
      new SchemaManager(db).migrate();
      Acting.system(
          () -> {
            new ProjectStore(db).upsert(TestDefinitions.named("alpha"));
            new ProjectStore(db).upsert(TestDefinitions.named("beta"));
            new RunStore(db)
                .create(
                    "019fee00-0000-7000-8000-0000000a1fa1",
                    "alpha",
                    null,
                    NodeIdentity.handle(),
                    "adhoc",
                    "claude-code",
                    null,
                    "do it",
                    1,
                    null,
                    null,
                    "unit");
          });
    }
    operations = operator -> OperationsFactory.open(database);
  }

  @Test
  void agentStatusOfAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT),
        CommandRun.as(ADA, new AgentStatusCommand(operations, NO_INCUS), "beta"));
  }

  @Test
  void agentStatusOfEveryRunningProjectShowsOnlyThoseInTheScope() {
    var incus =
        new ScriptedShellExecutor()
            .onOk(
                "incus list",
                "[{\"name\": \"alpha\", \"status\": \"Running\", \"state\": {}},"
                    + " {\"name\": \"beta\", \"status\": \"Running\", \"state\": {}}]");

    var shown =
        CommandRun.as(
            ADA,
            new AgentStatusCommand(
                operator ->
                    OperationsFactory.create(
                        Sqlite.open(database),
                        incus,
                        null,
                        null,
                        SyncScheduler.disabled(),
                        SessionYield.NONE),
                incus),
            "--json");

    assertEquals(0, shown.exit(), shown.err());
    assertEquals(
        List.of("alpha"),
        ((List<?>) YamlUtil.parseMap(shown.out()).get("agents"))
            .stream().map(agent -> ((Map<?, ?>) agent).get("name")).toList());
  }

  @Test
  void agentReportOfAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT),
        CommandRun.as(ADA, new AgentReportCommand(operations), "beta"));
  }

  @Test
  void agentLogOfAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT), CommandRun.as(ADA, new AgentLogCommand(operations), "beta"));
  }

  @Test
  void agentLogOfARunAViewerMayNotOperateIsRefusedAsAStopIs() {
    var viewer = new Actor("vic", Role.VIEWER, Actor.Lane.CLI);

    assertEquals(
        new CommandRun(
            1,
            "",
            "  ✗ Your credential is read-only and cannot stop or attach to runs or read their logs.\n"
                + "  Ask an admin for a member or admin credential.\n"),
        CommandRun.as(viewer, new AgentLogCommand(operations), "alpha"));
  }

  @Test
  void agentAttachToAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT),
        CommandRun.as(ADA, new AgentAttachCommand(operations), "beta", "--dry-run"));
  }
}
