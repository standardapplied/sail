/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class AgentReportCommandTest {

  @TempDir Path tempDir;

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
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    var exitCode = cmd.execute();

    assertNotEquals(0, exitCode);
  }

  @Test
  void projectSpecsReadsFromTheInjectedDatabase() throws Exception {
    var dbPath = tempDir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      new SpecStore(db).create(specRow("auth", "acme", "Add auth"));
      new SpecStore(db).create(specRow("other", "elsewhere", "Unrelated"));
    }
    var command = new AgentReportCommand(() -> Sqlite.open(dbPath));

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
