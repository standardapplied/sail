/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.TemplateBuild;
import ai.singlr.sail.engine.TemplateRecord;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sail-api's template operations over a real database: the builds cut short it ends as it starts,
 * and whose tokens a build through the route clones with.
 */
class TemplateOperationsTest {

  private static final Instant AT = Instant.parse("2026-11-17T09:00:00Z");

  @TempDir Path dir;

  private SailOperations operations(Sqlite db, EventBus bus, ShellExec incus, String boxPerson) {
    return OperationsFactory.create(db, incus, bus, SyncScheduler.disabled(), SessionYield.NONE)
        .useControlPlane(
            db,
            dir,
            new SyncOperations(
                db,
                "main",
                dir,
                () -> new SyncConfig(null, null, boxPerson),
                target -> {
                  throw new IOException("no main to reach");
                }));
  }

  /**
   * A build through the route clones with its caller's own stored tokens, never the box's person's:
   * the caller who keeps none is refused for want of one though the box's person keeps one, and
   * once the caller stores theirs the build goes on to the runner's own checks.
   */
  @Test
  void aBuildThroughTheRouteIsGivenItsCallersTokensNeverTheBoxPersons() throws Exception {
    var hostYaml = SailPaths.hostConfigPath();
    try (var db = Sqlite.open(dir.resolve("route.db"));
        var bus = new EventBus()) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      var uday = fdes.add("uday", null, "uday@x", "admin");
      var dana = fdes.add("dana", null, "dana@x", "admin");
      Acting.system(
          () ->
              new ProjectStore(db)
                  .upsert(
                      new ProjectDefinition(
                          "lamp",
                          "",
                          new ProjectDefinition.Resources(2, "4GB", "20GB"),
                          null,
                          null,
                          null,
                          List.of(
                              new ProjectDefinition.Repo(
                                  "https://github.com/acme/lamp.git", "lamp", null)),
                          null,
                          null,
                          null,
                          null)));
      fdes.logins().storeGit(uday.id(), "github.com", "ghp_the_box_persons_own_token");
      Files.createDirectories(hostYaml.getParent());
      YamlUtil.dumpToFile(
          new HostYaml("dir", "default", null, "incusbr0", "b", "ubuntu/24.04", "6.0", null, null)
              .toMap(),
          hostYaml);
      var noZfs = new ScriptedShellExecutor(new ShellExec.Result(0, "[]", ""));
      var operations = operations(db, bus, noZfs, "uday").useCredentialProbe(url -> true);
      var asDana = new Actor("dana", Role.ADMIN, Actor.Lane.API);

      var without = Actor.call(asDana, () -> operations.buildTemplate("lamp"));
      fdes.logins().storeGit(dana.id(), "github.com", "ghp_danas_own_stored_token");
      var with = Actor.call(asDana, () -> operations.buildTemplate("lamp"));

      var refused = assertInstanceOf(Result.Failure.class, without);
      assertEquals(ErrorCode.LOGIN_NOT_STORED, refused.errorCode());
      assertTrue(refused.errorMessage().contains("dana keeps no token"), refused.errorMessage());
      var past = assertInstanceOf(Result.Failure.class, with);
      assertNotEquals(ErrorCode.LOGIN_NOT_STORED, past.errorCode(), past.errorMessage());
      assertTrue(past.errorMessage().contains("no ZFS pool"), past.errorMessage());
    } finally {
      Files.deleteIfExists(hostYaml);
    }
  }

  /**
   * sail-api ends the builds cut short as it starts, then once each interval, and no more once its
   * passes are closed, as it stops: a command-line build killed while it runs is ended by the next
   * pass, never left until a restart.
   */
  @Test
  void sailApiEndsCutBuildsAsItStartsThenEachIntervalUntilItStops() throws Exception {
    try (var db = Sqlite.open(dir.resolve("passes.db"));
        var bus = new EventBus()) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.named("lamp")));
      var scripted =
          new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
              .onOk("incus list --format json", "[]");
      var looked = new Semaphore(0);
      var incus =
          new ShellExec() {
            @Override
            public ShellExec.Result exec(List<String> command) throws IOException {
              var result = scripted.exec(command);
              if (String.join(" ", command).startsWith("incus list")) {
                looked.release();
              }
              return result;
            }

            @Override
            public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
                throws IOException {
              return exec(command);
            }

            @Override
            public boolean isDryRun() {
              return false;
            }
          };
      var operations = operations(db, bus, incus, "ada");
      var file = TemplateRecord.fileOf(dir, "lamp");
      cut(file);

      var passes = operations.cleanCutTemplateBuilds(Duration.ofMillis(50));
      var atStart = TemplateRecord.read(file).orElseThrow();
      looked.drainPermits();
      cut(file);
      var aTickLooked = looked.tryAcquire(10, TimeUnit.SECONDS);
      passes.close();
      var afterATick = TemplateRecord.read(file).orElseThrow();
      cut(file);
      var lookedOnceClosed = looked.tryAcquire(300, TimeUnit.MILLISECONDS);

      assertEquals(TemplateRecord.Outcome.INTERRUPTED, atStart.outcome(), "ended as it starts");
      assertTrue(aTickLooked, "a later pass looks again");
      assertEquals(TemplateRecord.Outcome.INTERRUPTED, afterATick.outcome(), "and ends it");
      assertFalse(lookedOnceClosed, "no pass runs once sail-api stops");
      assertTrue(TemplateRecord.read(file).orElseThrow().running());
    }
  }

  private static void cut(Path file) {
    TemplateRecord.started("ada", AT, TemplateBuild.STEPS).at(9, AT).write(file);
  }

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
        operations.cleanCutTemplateBuilds().close();
      } finally {
        System.setErr(err);
      }

      assertEquals(
          TemplateRecord.Outcome.INTERRUPTED,
          TemplateRecord.read(TemplateRecord.fileOf(dir, "lamp")).orElseThrow().outcome());
      assertEquals(
          TemplateBuild.UNPROVEN,
          TemplateRecord.read(TemplateRecord.fileOf(dir, "dock")).orElseThrow().reason(),
          "dock's template still holds a token, and its record says so");
      assertTrue(incus.invocations().contains("incus stop dock--tmpl"), "never left running");
      assertTrue(
          said.toString(StandardCharsets.UTF_8).contains("a template build"),
          said.toString(StandardCharsets.UTF_8));
    }
  }
}
