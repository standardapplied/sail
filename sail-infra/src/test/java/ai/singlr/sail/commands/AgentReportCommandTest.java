/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class AgentReportCommandTest {

  @TempDir Path tempDir;

  @Test
  void theDefinitionAReportIsBuiltFromIsTheCatalogRowAndAProjectWithNoneFailsSayingSo() {
    var dbPath = tempDir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(
          () -> new ProjectStore(db).upsert("acme", "name: acme\nagent:\n  type: codex\n"));
    }
    var acme = new AgentReportCommand(() -> OperationsFactory.open(dbPath));
    new CommandLine(acme).parseArgs("acme", "-f", "/abs/sail.yaml");
    var absent = new AgentReportCommand(() -> OperationsFactory.open(dbPath));
    new CommandLine(absent).parseArgs("absent");

    assertEquals("codex", acme.definition().agent().type());
    var refused = assertThrows(ApiException.class, absent::definition);
    assertEquals("Project 'absent' is not in the catalog.", refused.getMessage());
  }

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new AgentReportCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("report"));
    assertTrue(usage.contains("morning-after"));
    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--file"));
  }

  @Test
  void requiresProjectName() {
    var cmd = new CommandLine(new AgentReportCommand());
    cmd.setErr(new PrintWriter(new StringWriter()));
    var exitCode = cmd.execute();

    assertNotEquals(0, exitCode);
  }

  @Test
  void projectSpecsReadsFromTheInjectedDatabase() throws Exception {
    var dbPath = tempDir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new SpecStore(db).create(specRow("auth", "acme", "Add auth")));
      Acting.system(() -> new SpecStore(db).create(specRow("other", "elsewhere", "Unrelated")));
    }
    var command = new AgentReportCommand(() -> OperationsFactory.open(dbPath));

    var specs = command.projectSpecs("acme");

    assertEquals(1, specs.size());
    assertEquals("auth", specs.getFirst().id());
  }

  @Test
  void projectSpecsReturnsEmptyWhenTheDatabaseIsUnavailable() {
    var command =
        new AgentReportCommand(
            () -> {
              throw new IllegalStateException("no control-plane database");
            });

    assertTrue(command.projectSpecs("acme").isEmpty());
  }

  private static SpecStore.SpecRow specRow(String id, String project, String title) {
    return new SpecStore.SpecRow(
        id,
        project,
        title,
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "me",
        null,
        null,
        "me",
        List.of(),
        List.of());
  }
}
