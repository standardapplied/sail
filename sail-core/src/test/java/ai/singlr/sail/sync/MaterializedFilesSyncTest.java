/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a box wrote of a shared file to its own disk is that box's alone: over a real session it
 * reaches neither the change log nor any other box, while the file itself converges as before.
 */
@ActingAs
class MaterializedFilesSyncTest {

  @TempDir Path dir;

  @Test
  void whatABoxWroteNeverReachesANodeOrTheChangeLog() {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      Acting.as("root", () -> new FdeStore(main.db).add("node", null, null, "member"));
      var mainFiles = new FileStore(main.db);
      var nodeFiles = new FileStore(node.db);
      ContentFixtures.put(mainFiles, "acme", "app.conf", "v1");
      var hash = mainFiles.find("acme", "app.conf").orElseThrow().contentHash();
      mainFiles.recordMaterialized("acme/app.conf", hash, 0644);

      SyncBox.assertConvergedWithin(2, main, node);
      nodeFiles.recordMaterialized("acme/app.conf", hash, 0600);
      SyncBox.assertConvergedWithin(2, main, node);

      assertEquals("v1", ContentFixtures.text(nodeFiles, "acme", "app.conf"));
      assertTrue(mainFiles.copyOf("acme/app.conf", hash, 0644).ours(), "main's record stands");
      assertFalse(mainFiles.copyOf("acme/app.conf", hash, 0600).ours());
      assertTrue(nodeFiles.copyOf("acme/app.conf", hash, 0600).ours(), "the node's stands");
      assertFalse(nodeFiles.copyOf("acme/app.conf", hash, 0644).ours());
      for (var db : List.of(main.db, node.db)) {
        assertEquals(1L, records(db), "one box, one record of its own");
        assertTrue(
            db.query("SELECT snapshot FROM change_log", row -> row.text(0)).stream()
                .noneMatch(snapshot -> snapshot.contains("materialized")),
            "the record is not journaled");
      }
    }
  }

  private static long records(Sqlite db) {
    return db.queryOne("SELECT count(*) FROM materialized_files", row -> row.integer(0))
        .orElseThrow();
  }
}
