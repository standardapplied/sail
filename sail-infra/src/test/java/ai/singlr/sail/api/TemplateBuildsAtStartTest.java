/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.TemplateBuild;
import ai.singlr.sail.engine.TemplateRecord;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sail-api, as it starts, ends every template build a stop of it cut short: one whose template is
 * gone is ended at once, and one whose template still holds a token is left, said in the log, for
 * the next read.
 */
class TemplateBuildsAtStartTest {

  private static final Instant AT = Instant.parse("2026-11-17T09:00:00Z");

  @TempDir Path dir;

  @Test
  void sailApiStartingEndsEveryCutBuildItCanAndLogsTheOneItCannot() throws Exception {
    try (var db = Sqlite.open(dir.resolve("box.db"));
        var bus = new EventBus()) {
      new SchemaManager(db).migrate();
      var catalog = new ProjectStore(db);
      Acting.system(
          () -> {
            catalog.upsert(TestDefinitions.named("lamp"));
            catalog.upsert(TestDefinitions.named("dock"));
          });
      var incus =
          new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
              .onOk(
                  "incus storage list --format json", "[{\"name\": \"tank\", \"driver\": \"zfs\"}]")
              .onOk("incus list --format json", "[" + TestWorkspaces.template("dock", "x") + "]")
              .on("sail-held", new ShellExec.Result(1, "a git token", ""));
      var operations =
          OperationsFactory.create(db, incus, bus, SyncScheduler.disabled(), SessionYield.NONE)
              .useControlPlane(
                  db,
                  dir,
                  new SyncOperations(
                      db,
                      "main",
                      dir,
                      () -> new SyncConfig(null, null, "ada"),
                      target -> {
                        throw new IOException("no main to reach");
                      }));
      for (var project : new String[] {"lamp", "dock"}) {
        TemplateRecord.started("ada", AT, TemplateBuild.STEPS)
            .at(9, AT)
            .write(TemplateRecord.fileOf(dir, project));
      }

      var said = new ByteArrayOutputStream();
      var err = System.err;
      System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
      try {
        operations.endCutShortTemplateBuilds();
      } finally {
        System.setErr(err);
      }

      assertEquals(
          TemplateRecord.Outcome.INTERRUPTED,
          TemplateRecord.read(TemplateRecord.fileOf(dir, "lamp")).orElseThrow().outcome());
      assertTrue(
          TemplateRecord.read(TemplateRecord.fileOf(dir, "dock")).orElseThrow().running(),
          "dock's template still holds a token, so its record waits for a read that can end it");
      assertTrue(incus.invocations().contains("incus stop dock--tmpl"), "never left running");
      assertTrue(
          said.toString(StandardCharsets.UTF_8).contains("a template build"),
          said.toString(StandardCharsets.UTF_8));
    }
  }
}
