/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host CLI's reads of one project, made as this box's operator: a project outside the
 * operator's scope is one the catalog does not hold, refused before any container is asked.
 */
class HostReadsAsTheOperatorTest {

  private static final String ABSENT =
      "  ✗ Project 'beta' is not in the catalog.\n  Create it with 'sail project create beta', or"
          + " sync it from main with 'sail sync'.\n";

  @TempDir Path dir;
  private Function<OperatorCommand, HostOperations> operations;

  @BeforeEach
  void aCatalogHoldingAlphaAndBetaReadByAnOperatorGrantedAlpha() {
    var database = dir.resolve("control-plane.db");
    try (var db = Sqlite.open(database)) {
      new SchemaManager(db).migrate();
      Acting.system(
          () -> {
            new ProjectStore(db).upsert(TestDefinitions.named("alpha"));
            new ProjectStore(db).upsert(TestDefinitions.named("beta"));
          });
    }
    var ada =
        new Actor("ada", Role.MEMBER, Actor.Lane.CLI).withScope(ProjectScope.of(List.of("alpha")));
    operations = operator -> OperatorAs.of(OperationsFactory.open(database), ada);
  }

  @Test
  void agentStatusOfAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT), CommandRun.of(new AgentStatusCommand(operations), "beta"));
  }

  @Test
  void agentReportOfAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT), CommandRun.of(new AgentReportCommand(operations), "beta"));
  }

  @Test
  void projectConfigShowOfAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT),
        CommandRun.of(new ProjectConfigShowCommand(operations), "beta"));
  }

  @Test
  void agentAttachToAProjectOutsideTheScopeIsNotInTheCatalog() {
    assertEquals(
        new CommandRun(1, "", ABSENT),
        CommandRun.of(new AgentAttachCommand(operations), "beta", "--dry-run"));
  }
}
