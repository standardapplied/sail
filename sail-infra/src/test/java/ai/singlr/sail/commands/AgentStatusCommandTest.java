/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class AgentStatusCommandTest {

  @TempDir Path dir;

  @Test
  void theListingCountsCommitsInTheRowsReposAndInTheWorkspaceForAProjectWithNoRowOrOneUnreadable() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var store = new ProjectStore(db);
      Acting.system(
          () ->
              store.upsert(
                  "acme", "name: acme\nrepos:\n  - url: https://x/api.git\n    path: api\n"));
      Acting.system(() -> store.upsert("oops", "name: oops\n"));
      db.execute("UPDATE projects SET definition = ? WHERE name = ?", "agent: [", "oops");
      var definitions = ProjectReader.ofCatalog(store);

      assertEquals(
          List.of(ContainerExec.DEV_WORKSPACE + "/api"),
          AgentStatusCommand.repoPathsOrDefault(definitions, "acme"));
      assertEquals(
          List.of(ContainerExec.DEV_WORKSPACE),
          AgentStatusCommand.repoPathsOrDefault(definitions, "absent"));
      assertEquals(
          List.of(ContainerExec.DEV_WORKSPACE),
          AgentStatusCommand.repoPathsOrDefault(definitions, "oops"));
    }
  }

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new AgentStatusCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("status"));
    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--file"));
  }

  @Test
  void helpMentionsOptionalProjectName() {
    var cmd = new CommandLine(new AgentStatusCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("Omit project name"));
  }

  @Test
  void acceptsNoArguments() {
    var cmd = new CommandLine(new AgentStatusCommand());
    var parseResult = cmd.parseArgs();

    assertNotNull(parseResult);
  }
}
