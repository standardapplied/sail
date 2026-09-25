/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StuckSpecReconcilerTest {

  @TempDir Path tempDir;

  private static SpecStore.SpecRow row(String id) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "T",
        SpecStatus.IN_PROGRESS,
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

  @Test
  void sweepSurfacesOnlyTheStrandedSpec() {
    var dbPath = tempDir.resolve("control.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      var store = new SpecStore(db);
      Acting.system(
          () -> {
            store.create(row("stuck"));
            store.create(row("recent"));
          });
      db.execute(
          "UPDATE specs SET updated_at = ? WHERE id = ?",
          Instant.now().minus(Duration.ofHours(7)).toString(),
          "stuck");
    }
    var captured = new AtomicReference<List<SpecStore.SpecRow>>();
    var actor = new AtomicReference<Actor>();

    try (var reconciler =
        new StuckSpecReconciler(
            dbPath,
            Duration.ofHours(6),
            stranded -> {
              captured.set(stranded);
              actor.set(Actor.current());
            })) {
      reconciler.sweep();
    }

    assertEquals(Actor.system(), actor.get(), "a sweep acts as this box's machinery");
    assertNotNull(captured.get());
    assertEquals(List.of("stuck"), captured.get().stream().map(SpecStore.SpecRow::id).toList());
  }

  @Test
  void sweepDoesNotCallBackWhenNothingIsStranded() {
    var dbPath = tempDir.resolve("control.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new SpecStore(db).create(row("recent")));
    }
    var called = new AtomicBoolean(false);

    try (var reconciler =
        new StuckSpecReconciler(dbPath, Duration.ofHours(6), specs -> called.set(true))) {
      reconciler.sweep();
    }

    assertFalse(called.get());
  }
}
