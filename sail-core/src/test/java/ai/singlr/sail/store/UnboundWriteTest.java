/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * A write that names no one never lands: every synced type refuses it at the journal, and the
 * refusal rolls the row back with it, so neither a row nor a revision is left behind.
 */
class UnboundWriteTest {

  @TempDir Path tempDir;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aSpecWrittenByNoOneIsRefused() {
    assertRefused(() -> new SpecStore(db).create(spec("auth")), "specs", "spec");
  }

  @Test
  void aRoomWrittenByNoOneIsRefused() {
    assertRefused(
        () ->
            new RoomStore(db)
                .create(
                    new RoomStore.RoomRow(
                        "lobby", "acme", "Lobby", null, null, null, null, null, null, null)),
        "rooms",
        "room");
  }

  @Test
  void aProjectWrittenByNoOneIsRefused() {
    assertRefused(() -> new ProjectStore(db).upsert("acme", "name: acme\n"), "projects", "project");
  }

  @Test
  void aFileWrittenByNoOneIsRefused() {
    assertRefused(
        () ->
            new FileStore(db)
                .put("acme", "notes.md", new ByteArrayInputStream("hello".getBytes()), 0644),
        "project_files",
        "file");
  }

  @Test
  void aMessageWrittenByNoOneIsRefused() {
    assertRefused(
        () -> new MessageStore(db).append("lobby", "uday", "hello", null),
        "room_messages",
        "message");
  }

  @Test
  void aReviewWrittenByNoOneIsRefused() {
    Acting.system(() -> new SpecStore(db).create(spec("auth")));

    assertRefused(() -> new ReviewStore(db).createReview("auth", 1), "reviews", "review");
  }

  @Test
  void aRunWrittenByNoOneIsRefused() {
    assertRefused(
        () ->
            new RunStore(db)
                .createReview(
                    "review-1",
                    "acme",
                    "auth",
                    "node-a",
                    "uday",
                    "claude-code",
                    "main",
                    "review it",
                    "/tmp/log",
                    "unit"),
        "runs",
        "run");
  }

  @Test
  void anEditOfAnExistingRowByNoOneLeavesItAsItWas() {
    var specs = new SpecStore(db);
    Acting.as("uday", () -> specs.create(spec("auth")));
    var before = specs.findById("auth").orElseThrow();

    assertThrows(
        IllegalStateException.class, () -> specs.updateStatus("auth", SpecStatus.IN_PROGRESS));

    assertEquals(before, specs.findById("auth").orElseThrow());
    assertEquals(1, new ChangeLog(db).history("spec", "auth").size());
  }

  private void assertRefused(Executable write, String table, String entityType) {
    var refusal = assertThrows(IllegalStateException.class, write);

    assertTrue(refusal.getMessage().contains("No actor is bound"), refusal.getMessage());
    assertEquals(0L, count("SELECT count(*) FROM " + table));
    assertEquals(
        0L, count("SELECT count(*) FROM change_log WHERE entity_type = '" + entityType + "'"));
  }

  private long count(String sql) {
    return db.queryOne(sql, row -> row.integer(0)).orElseThrow();
  }

  private static SpecStore.SpecRow spec(String id) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Auth",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }
}
