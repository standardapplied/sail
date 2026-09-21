/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.*;

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
}
