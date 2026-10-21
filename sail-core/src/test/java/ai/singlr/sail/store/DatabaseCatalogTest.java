/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which database this is: minted with it, kept by upgrades and copies, never by another one. */
class DatabaseCatalogTest {

  @TempDir Path dir;

  private static Sqlite migrated(Path file) {
    var db = Sqlite.open(file);
    new SchemaManager(db).migrate();
    return db;
  }

  @Test
  void everyDatabaseIsMintedItsOwnRandomCatalogIdThatUpgradesAndCopiesKeep() {
    try (var db = migrated(dir.resolve("a.db"));
        var other = migrated(dir.resolve("b.db"))) {
      var id = new DatabaseCatalog(db).id();
      assertTrue(id.matches("[0-9a-f]{32}"), id);
      assertNotEquals(id, new DatabaseCatalog(other).id());

      new SchemaManager(db).migrate();
      db.execute("VACUUM INTO ?", dir.resolve("copy.db").toString());

      assertEquals(id, new DatabaseCatalog(db).id());
      try (var copy = migrated(dir.resolve("copy.db"))) {
        assertEquals(id, new DatabaseCatalog(copy).id());
      }
    }
  }

  @Test
  void aNodeRecordsTheFirstMainCatalogItIsOfferedAndKeepsItWhateverItIsOfferedNext() {
    try (var db = migrated(dir.resolve("node.db"))) {
      var catalog = new DatabaseCatalog(db);
      assertEquals(Optional.empty(), catalog.main());

      assertEquals("aaaa", catalog.recordMain("aaaa"));
      assertEquals("aaaa", catalog.recordMain("bbbb"));

      assertEquals(Optional.of("aaaa"), catalog.main());
      assertEquals(List.of(1L), db.query("SELECT count(*) FROM catalog", row -> row.integer(0)));
    }
  }

  @Test
  void aBoxThatIsNoLongerANodeForgetsMainsCatalogAndRecordsTheNextItIsOffered() {
    try (var db = migrated(dir.resolve("node.db"))) {
      var catalog = new DatabaseCatalog(db);
      catalog.recordMain("aaaa");

      catalog.forgetMain();

      assertEquals(Optional.empty(), catalog.main());
      assertEquals("bbbb", catalog.recordMain("bbbb"));
    }
  }

  @Test
  void aBlankCatalogIsNeverRecorded() {
    try (var db = migrated(dir.resolve("node.db"))) {
      var catalog = new DatabaseCatalog(db);

      assertThrows(IllegalArgumentException.class, () -> catalog.recordMain(" "));

      assertEquals(Optional.empty(), catalog.main());
    }
  }
}
