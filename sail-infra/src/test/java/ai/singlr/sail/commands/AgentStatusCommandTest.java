/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.api.TestWorkspaces;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
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
  void theListingReadsReposFromTheRowAndTakesTheWorkspaceForAProjectWithNoRowOrOneUnreadable() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var store = new ProjectStore(db);
      Acting.system(
          () ->
              store.upsert(
                  TestDefinitions.yaml(
                      "name: acme\nrepos:\n  - url: https://x/api.git\n    path: api\n")));
      Acting.system(() -> store.upsert(TestDefinitions.named("oops")));
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

  /**
   * m10: status reads the one row the selector resolves, its place and its session alike: a spec's
   * run that has ended is read in its spec's workspace, never the project's container.
   */
  @Test
  void aSpecsEndedRunIsReadInItsOwnWorkspace() {
    var dbPath = dir.resolve("control-plane.db");
    var runId = DateTimeUtils.newId().toString();
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.named("acme")));
      var runs = new RunStore(db);
      Acting.system(
          () ->
              runs.create(
                  runId,
                  "acme",
                  "billing",
                  NodeIdentity.handle(),
                  "build",
                  "claude-code",
                  null,
                  "bill",
                  41,
                  null,
                  null,
                  "unit"));
      Acting.system(() -> runs.finish(runId, "running", "stopped", 0, null));
    }
    var incus =
        new ScriptedShellExecutor()
            .onOk(
                "incus list ^acme-billing$",
                "[" + TestWorkspaces.specWorkspace("acme", "billing", "Running", "eng") + "]")
            .onOk("agent.pid", "41")
            .onFail("kill -0 41", "no such process")
            .onOk("agent-session.json", "{\"task\": \"bill\"}");

    var shown =
        CommandRun.as(
            Actor.system(),
            new AgentStatusCommand(operator -> OperationsFactory.open(dbPath), incus),
            "acme",
            "--spec",
            "billing",
            "--json");

    assertEquals(0, shown.exit(), shown.err());
    var status = YamlUtil.parseMap(shown.out());
    assertEquals(false, status.get("agent_running"));
    assertEquals("bill", status.get("task"));
    assertTrue(
        incus.invocations().stream()
            .filter(command -> command.startsWith("incus exec"))
            .allMatch(command -> command.startsWith("incus exec acme-billing ")),
        incus.invocations().toString());
  }

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new AgentStatusCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("status"));
    assertTrue(usage.contains("--json"));
    assertFalse(usage.contains("--file"));
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
