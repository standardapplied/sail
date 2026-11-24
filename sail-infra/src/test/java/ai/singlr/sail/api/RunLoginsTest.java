/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What mends a refused login: its person's default login for the harness, stored anew. */
class RunLoginsTest {

  private static final Instant STORED = Instant.parse("2026-01-01T09:00:00Z");

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private RunStore runs;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    runs = new RunStore(db);
    var uday = Acting.system(() -> fdes.add("uday", "Uday", null, "member")).id();
    fdes.logins().store(uday, Harnesses.of("claude-code"), null, "api-key", "fake-claude-key-1");
    try (var files = Files.walk(tempDir.resolve("logins"))) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        Files.setLastModifiedTime(file, FileTime.from(STORED));
      }
    }
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  /**
   * Round 4, item 2: a refused login is replaced once its person's default login for the harness is
   * another, or was stored after the run started, and not by anything stored before it, nor by a
   * person with no default login.
   */
  @Test
  void aRefusedLoginIsReplacedByAnotherDefaultOrOneStoredAfterTheRunStarted() throws Exception {
    var refused = refusedRun("uday");
    var stranger = refusedRun("nobody");
    var uday = fdes.byHandle("uday").orElseThrow().id();
    var claude = Harnesses.of("claude-code");

    assertFalse(RunLogins.replaced(fdes, refused), "the one it was refused, stored before");
    assertFalse(RunLogins.replaced(fdes, stranger), "no login to replace it");

    fdes.logins().store(uday, claude, "work", "api-key", "fake-key-key-work");
    fdes.logins().use(uday, claude, "work");
    assertTrue(RunLogins.replaced(fdes, refused), "another login is the default");

    fdes.logins().use(uday, claude, "default");
    assertFalse(RunLogins.replaced(fdes, refused));
    fdes.logins().store(uday, claude, null, "api-key", "fake-claude-key-2");
    Files.setLastModifiedTime(
        tempDir.resolve("logins").resolve(uday).resolve("claude-code").resolve("default"),
        FileTime.from(refused.startedAt().plusSeconds(1)));
    assertTrue(RunLogins.replaced(fdes, refused), "stored again after the run started");
  }

  private RunStore.RunRow refusedRun(String owner) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () -> {
          runs.create(
              id, "acme", "auth", owner, "review", "claude-code", "b", "t", null, null, "/l", "u");
          runs.recordLaunch(
              id,
              new RunStore.Launched(
                  null, null, null, new RunStore.RunLogin("default", "api-key"), null));
        });
    return runs.findById(id).orElseThrow();
  }
}
