/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a run's request costs the box: a request that decides nothing about the run's work reads
 * none of it, so a build's whoami and its read of its spec cost the statements they cost before the
 * run carried its work, as on main.
 */
class RunRequestCostTest {

  private static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday", "main");

  @TempDir Path dir;
  private Sqlite db;
  private SailOperations operations;
  private String build;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    new FdeStore(db).add("eng", null, null, "member");
    Acting.system(
        () ->
            new ProjectStore(db)
                .upsert(TestDefinitions.yaml("name: acme\nagent:\n  type: claude-code\n")));
    Acting.as(
        "eng",
        () ->
            new SpecStore(db)
                .create(
                    new SpecStore.SpecRow(
                        "e1",
                        "acme",
                        "E1",
                        SpecStatus.DRAFT,
                        "eng",
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of())));
    var reservation =
        Acting.system(
            () ->
                new RunStore(db)
                    .reserveDispatch(
                        "019fee00-0000-7000-8000-0000000000c1",
                        "acme",
                        "e1",
                        null,
                        "eng",
                        "build",
                        List.of(),
                        "claude-code",
                        "feat/e1",
                        "work",
                        "/tmp/e1.log",
                        "sail-agent-e1",
                        Duration.ofHours(1)));
    build = ((RunStore.Reservation.Reserved) reservation).credential();
    operations =
        OperationsFactory.create(
                db,
                new ScriptedShellExecutor(new ShellExec.Result(0, "", "")),
                new EventBus(),
                SyncScheduler.disabled(),
                SessionYield.NONE)
            .useControlPlane(
                db,
                dir,
                new SyncOperations(
                    db,
                    MAIN.boxId(),
                    dir,
                    () -> MAIN,
                    target -> {
                      throw new IOException("main is not asked here");
                    }));
  }

  @AfterEach
  void tearDown() {
    operations.close();
    db.close();
  }

  @Test
  void aBuildsWhoamiAndItsReadOfItsSpecCostWhatTheyDidOnMain() {
    assertEquals(List.of(200L, 200L), List.of(status("/v1/whoami"), status("/v1/specs/e1")));

    assertEquals(List.of(4L, 12L), List.of(cost("/v1/whoami"), cost("/v1/specs/e1")));
  }

  private long status(String path) {
    return new SocketDoor(operations).ask("GET", path, build, "").status();
  }

  /** The statements the box's database prepares as the build asks {@code path}. */
  private long cost(String path) {
    var before = db.statements();
    new SocketDoor(operations).ask("GET", path, build, "");
    return db.statements() - before;
  }
}
