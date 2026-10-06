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
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
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
        authorized_keys:
          - ${SSH_PUBLIC_KEY}
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
      assertEquals(
          List.of("${SSH_PUBLIC_KEY}"),
          config.ssh().authorizedKeys(),
          "the context names the git identity only; every other placeholder is left as it is");
    }
  }

  @Test
  void anApostropheInTheBoxsGitNameSurvivesTheRowsQuotedPlaceholder() {
    var dbPath = seeded("acme", "name: acme\ngit:\n  name: Alex Morgan\n  email: a@x.io\n");
    var identity = identity(Map.of("user.name", "Mady O'Neil", "user.email", "m@x.io"));

    try (var operations = OperationsFactory.open(dbPath)) {
      var config =
          AgentContextRegenCommand.definitionWithBoxIdentity(
              operations.catalog(), "acme", identity);

      assertEquals("Mady O'Neil", config.git().name());
      assertEquals("m@x.io", config.git().email());
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
    var git = new ScriptedShellExecutor().onOk("user.", "Mady");
    var identity = new LocalIdentity(git, dir.resolve("none.pub"));

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
      assertTrue(
          git.invocations().isEmpty(),
          "the box's identity is never asked for a project it cannot read");
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
    var git = new ScriptedShellExecutor();
    gitConfig.forEach((key, value) -> git.onOk(key, value + "\n"));
    return new LocalIdentity(git, dir.resolve("none.pub"));
  }
}
