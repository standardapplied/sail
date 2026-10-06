/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class AgentContextRegenCommandTest {

  private static final String ACME =
      """
      name: acme
      git:
        name: ${GIT_NAME}
        email: ${GIT_EMAIL}
      ssh:
        user: dev
      agent:
        type: claude-code
      """;

  @TempDir Path dir;

  @Test
  void helpSaysTheFileOptionIsIgnored() {
    var usage = new CommandLine(new AgentContextRegenCommand()).getUsageMessage();

    assertTrue(usage.contains("Ignored: the project is read from the catalog."), usage);
  }

  @Test
  void theContextNamesTheBoxsGitIdentityWhereTheRowHoldsThePlaceholder() {
    var dbPath = seeded("acme", ACME);
    var identity = identity(Map.of("user.name", "Mady Lane", "user.email", "mady@example.dev"));

    try (var operations = OperationsFactory.open(dbPath)) {
      var config =
          AgentContextRegenCommand.definitionWithBoxIdentity(
              operations.catalog(), "acme", identity);

      assertEquals("Mady Lane", config.git().name());
      assertEquals("mady@example.dev", config.git().email());
      assertEquals("dev", config.ssh().user());
    }
  }

  @Test
  void aBoxWithNoGitIdentityLeavesThePlaceholderAndSucceeds() {
    var dbPath = seeded("acme", ACME);

    try (var operations = OperationsFactory.open(dbPath)) {
      var config =
          AgentContextRegenCommand.definitionWithBoxIdentity(
              operations.catalog(), "acme", identity(Map.of()));

      assertEquals("${GIT_NAME}", config.git().name());
      assertEquals("${GIT_EMAIL}", config.git().email());
    }
  }

  @Test
  void aProjectNotInTheCatalogFailsBeforeAnythingIsReplacedAndSoDoesARowThatCannotBeRead() {
    var dbPath = seeded("acme", ACME);
    var asked = new java.util.concurrent.atomic.AtomicInteger();
    var identity =
        new LocalIdentity(
            new ShellExec() {
              public Result exec(List<String> command) {
                asked.incrementAndGet();
                return new Result(0, "Mady", "");
              }

              public Result exec(List<String> command, Path workDir, Duration timeout) {
                return exec(command);
              }

              public boolean isDryRun() {
                return false;
              }
            },
            dir.resolve("none.pub"));

    try (var operations = OperationsFactory.open(dbPath)) {
      var refused =
          assertThrows(
              ApiException.class,
              () ->
                  AgentContextRegenCommand.definitionWithBoxIdentity(
                      operations.catalog(), "other", identity));
      assertEquals("Project 'other' is not in the catalog.", refused.getMessage());

      try (var db = Sqlite.open(dbPath)) {
        db.execute(
            "UPDATE projects SET definition = ? WHERE name = ?", "git: [${GIT_NAME}", "acme");
      }
      var unreadable =
          assertThrows(
              ProjectReader.Unreadable.class,
              () ->
                  AgentContextRegenCommand.definitionWithBoxIdentity(
                      operations.catalog(), "acme", identity));
      assertTrue(unreadable.getMessage().contains("'acme'"), unreadable.getMessage());
      assertEquals(
          0, asked.get(), "the box's identity is never asked for a project it cannot read");
    }
  }

  private Path seeded(String project, String definition) {
    var dbPath = dir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert(project, definition));
    }
    return dbPath;
  }

  private LocalIdentity identity(Map<String, String> gitConfig) {
    return new LocalIdentity(
        new ShellExec() {
          public Result exec(List<String> command) {
            var value = gitConfig.get(command.getLast());
            return value == null ? new Result(1, "", "") : new Result(0, value + "\n", "");
          }

          public Result exec(List<String> command, Path workDir, Duration timeout) {
            return exec(command);
          }

          public boolean isDryRun() {
            return false;
          }
        },
        dir.resolve("none.pub"));
  }
}
