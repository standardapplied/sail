/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlobStoreTest {
  @Test
  void roundTripDeduplicatesAndStreams() throws Exception {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var store = new BlobStore(db);
      var bytes = new byte[4 * 1024 * 1024];
      new Random(1).nextBytes(bytes);
      var hash = store.put(new ByteArrayInputStream(bytes));
      assertEquals(hash, store.put(new ByteArrayInputStream(bytes)));
      assertEquals(bytes.length, store.manifest(hash).size());
      assertEquals(Set.of(), store.missing(List.of(hash)));
      try (var stream = store.open(hash)) {
        assertArrayEquals(bytes, stream.readAllBytes());
      }
      assertEquals(1, db.queryOne("SELECT COUNT(*) FROM blobs", r -> r.integer(0)).orElseThrow());
      var empty = store.put(new ByteArrayInputStream(new byte[0]));
      assertEquals(0, store.manifest(empty).size());
      assertEquals(-1, store.open(empty).read());
    }
  }

  @Test
  void verifiesChunksAndWholeContentBeforePublishing() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var store = new BlobStore(db);
      var hash = BlobStore.hash(new byte[] {1, 2});
      assertThrows(IllegalArgumentException.class, () -> store.putChunk(hash, new byte[] {2, 1}));
      assertEquals(Set.of(hash), store.missingChunks(List.of(hash)));
      var manifest = new BlobStore.Manifest(hash, 2, List.of(hash));
      assertThrows(IllegalStateException.class, () -> store.assemble(manifest));
      assertFalse(store.has(hash));
      store.putChunk(hash, new byte[] {1, 2});
      var wrong = BlobStore.hash(new byte[] {3, 4});
      assertThrows(
          IllegalArgumentException.class,
          () -> store.assemble(new BlobStore.Manifest(wrong, 2, List.of(hash))));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.assemble(new BlobStore.Manifest(hash, 1, List.of(hash))));
      assertFalse(store.has(wrong));
      store.assemble(manifest);
      assertTrue(store.has(hash));
    }
  }

  @Test
  void gcKeepsSharedChunksAndDeletesOnlyUnreferencedContent() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var store = new BlobStore(db);
      var kept = store.put(new ByteArrayInputStream(new byte[] {1, 2}));
      var removed = store.put(new ByteArrayInputStream(new byte[] {3, 4, 5}));
      assertEquals(3, store.gc(Set.of(kept)));
      assertTrue(store.has(kept));
      assertFalse(store.has(removed));
      assertEquals(0, store.gc(Set.of(kept)));
    }
  }

  @Test
  void gcKeepsLiveRowsEvenBeforeTheyHaveBeenJournaled() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var specs = new SpecStore(db);
      specs.create(ai.singlr.sail.sync.SyncBox.spec("live", "Live", "pending"));
      specs.setContent("live", "body", "plan");
      var files = new FileStore(db);
      files.put("project", "file", new ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      db.execute("DELETE FROM change_log");
      var blobs = new BlobStore(db);
      assertEquals(0, blobs.gc(Set.of()));
      assertEquals(
          "body", blobs.text(specs.comparableSnapshot("live").get("body_hash").toString()));
      assertTrue(blobs.has(files.find("project", "file").orElseThrow().contentHash()));
    }
  }

  @Test
  void gcKeepsEverySideOfAnOpenConflict() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var blobs = new BlobStore(db);
      var sides = List.of(blobs.putText("base"), blobs.putText("mine"), blobs.putText("theirs"));
      var orphan = blobs.putText("orphan");
      new SyncConflicts(db)
          .record(
              "spec",
              "conflicted",
              ai.singlr.sail.config.YamlUtil.dumpJson(java.util.Map.of("body_hash", sides.get(0))),
              ai.singlr.sail.config.YamlUtil.dumpJson(java.util.Map.of("body_hash", sides.get(1))),
              ai.singlr.sail.config.YamlUtil.dumpJson(java.util.Map.of("body_hash", sides.get(2))),
              List.of("body_hash"));
      assertEquals(6, blobs.gc(Set.of()));
      sides.forEach(hash -> assertTrue(blobs.has(hash)));
      assertFalse(blobs.has(orphan));
    }
  }

  @Test
  void gcWaitsUntilAFetchingRoundReleasesUnpublishedChunks(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var path = dir.resolve("shared.db");
    try (var first = Sqlite.open(path);
        var second = Sqlite.open(path);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      new SchemaManager(first).migrate();
      var blobs = new BlobStore(first);
      java.util.concurrent.Future<Long> collecting;
      try (var round = blobs.retain()) {
        var bytes = new byte[] {1, 2, 3};
        blobs.putChunk(BlobStore.hash(bytes), bytes);
        collecting = executor.submit(() -> new BlobStore(second).gc(Set.of()));
        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> collecting.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertTrue(blobs.missingChunks(List.of(BlobStore.hash(bytes))).isEmpty());
      }
      assertEquals(3, collecting.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }
}
