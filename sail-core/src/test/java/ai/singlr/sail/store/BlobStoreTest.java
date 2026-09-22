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
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void aManifestCannotBeCheaperThanItsChunksAllow() {
    var hash = "0".repeat(64);
    var one = List.of(hash);
    var two = List.of(hash, hash);

    assertThrows(IllegalArgumentException.class, () -> new BlobStore.Manifest(hash, 1, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new BlobStore.Manifest(hash, 0, one));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlobStore.Manifest(hash, (long) FastCdc.MAX + 1, one),
        "one chunk cannot hold more than MAX");
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlobStore.Manifest(hash, FastCdc.MIN, two),
        "every chunk but the last is at least MIN");
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlobStore.Manifest(hash, BlobStore.MAX_SIZE + 1, two));
    assertEquals(2, new BlobStore.Manifest(hash, FastCdc.MIN + 1, two).chunkHashes().size());
    assertEquals(1, new BlobStore.Manifest(hash, FastCdc.MAX, one).chunkHashes().size());
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
  void sharedLeasesAcrossProcessesAllowIngestAndExcludeGc(@TempDir Path dir) throws Exception {
    var path = dir.resolve("shared.db");
    try (var db = Sqlite.open(path);
        var gcDb = Sqlite.open(path);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      new SchemaManager(db).migrate();
      var blobs = new BlobStore(db);
      Process child = null;
      try {
        try (var retained = blobs.retain()) {
          child = retentionProcess(path, "retain");
          var output = child.inputReader();
          assertEquals("retained", executor.submit(output::readLine).get(10, TimeUnit.SECONDS));
          assertEquals("local", blobs.text(blobs.putText("local")));
        }
        var collecting = executor.submit(() -> new BlobStore(gcDb).gc(Set.of()));
        assertThrows(TimeoutException.class, () -> collecting.get(100, TimeUnit.MILLISECONDS));
        child.getOutputStream().write(1);
        child.getOutputStream().flush();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        assertEquals(5, collecting.get(5, TimeUnit.SECONDS));
      } finally {
        if (child != null) {
          child.destroyForcibly();
          child.waitFor();
        }
      }
    }
  }

  @Test
  void collectionInAnotherProcessWaitsForEveryLocalLease(@TempDir Path dir) throws Exception {
    var path = dir.resolve("shared.db");
    try (var first = Sqlite.open(path);
        var second = Sqlite.open(path);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      new SchemaManager(first).migrate();
      var blobs = new BlobStore(first);
      var orphan = blobs.putText("orphan");
      Process child = null;
      try {
        java.util.concurrent.Future<String> collected;
        try (var outer = blobs.retain()) {
          try (var inner = new BlobStore(second).retain()) {
            child = retentionProcess(path, "gc");
            var output = child.inputReader();
            assertEquals("collecting", executor.submit(output::readLine).get(10, TimeUnit.SECONDS));
            collected = executor.submit(output::readLine);
            assertThrows(TimeoutException.class, () -> collected.get(100, TimeUnit.MILLISECONDS));
          }
          assertThrows(TimeoutException.class, () -> collected.get(100, TimeUnit.MILLISECONDS));
          assertTrue(blobs.has(orphan));
        }
        assertEquals("6", collected.get(5, TimeUnit.SECONDS));
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        assertFalse(blobs.has(orphan));
      } finally {
        if (child != null) {
          child.destroyForcibly();
          child.waitFor();
        }
      }
    }
  }

  private static Process retentionProcess(Path path, String operation) throws java.io.IOException {
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            RetentionProcess.class.getName(),
            path.toString(),
            operation)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
  }

  public static final class RetentionProcess {
    public static void main(String[] args) throws Exception {
      try (var db = Sqlite.open(Path.of(args[0]))) {
        var blobs = new BlobStore(db);
        if (args[1].equals("gc")) {
          System.out.println("collecting");
          System.out.println(blobs.gc(Set.of()));
        } else {
          try (var scope = blobs.retain()) {
            System.out.println("retained");
            System.in.read();
          }
        }
      }
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

  @Test
  void aPutLeasesRetentionOutsideATransactionButNotUnderAWriteOne() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var store = new BlobStore(db);
      assertEquals(1, leasesWhilePutting(db, store));
      assertEquals(0, db.transaction(() -> leasesWhilePutting(db, store)));
      assertEquals(0, db.contentRetention.leases());
    }
  }

  @Test
  void aLeaseTakenBeforeAReadTransactionCoversPutsInsideItWhileACollectorWaits() throws Exception {
    try (var db = Sqlite.openMemory();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      new SchemaManager(db).migrate();
      var store = new BlobStore(db);
      Future<Long> collecting;
      try (var lease = store.retain()) {
        collecting =
            db.read(
                () -> {
                  var collector = executor.submit(() -> store.gc(Set.of()));
                  assertThrows(
                      TimeoutException.class, () -> collector.get(100, TimeUnit.MILLISECONDS));
                  assertEquals(2, leasesWhilePutting(db, store));
                  assertEquals("under read", store.text(store.putText("under read")));
                  return collector;
                });
        assertThrows(TimeoutException.class, () -> collecting.get(100, TimeUnit.MILLISECONDS));
      }
      assertEquals(10, collecting.get(5, TimeUnit.SECONDS));
      assertEquals(0, db.contentRetention.leases());
    }
  }

  @Test
  void aPutUnderAReadTransactionWithoutALeaseIsRefusedAndAConcurrentCollectorCompletes()
      throws Exception {
    try (var db = Sqlite.openMemory();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      new SchemaManager(db).migrate();
      var store = new BlobStore(db);
      var orphan = store.putText("orphan");
      var reading = new CountDownLatch(1);
      var collectorStarted = new CountDownLatch(1);
      var reader =
          executor.submit(
              () ->
                  db.read(
                      () -> {
                        reading.countDown();
                        await(collectorStarted);
                        return assertThrows(
                            IllegalStateException.class, () -> store.putText("data"));
                      }));
      assertTrue(reading.await(5, TimeUnit.SECONDS));
      var collecting = executor.submit(() -> store.gc(Set.of()));
      collectorStarted.countDown();
      assertEquals(
          "Take blob retention before opening a transaction, not inside one",
          reader.get(5, TimeUnit.SECONDS).getMessage());
      assertEquals(6, collecting.get(5, TimeUnit.SECONDS));
      assertFalse(store.has(orphan));
      assertEquals(0, db.contentRetention.leases());
    }
  }

  @Test
  void gcIsRefusedInsideAnyTransactionScope() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var store = new BlobStore(db);
      assertThrows(IllegalStateException.class, () -> db.read(() -> store.gc(Set.of())));
      assertThrows(IllegalStateException.class, () -> db.transaction(() -> store.gc(Set.of())));
      assertEquals(0, store.gc(Set.of()));
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static int leasesWhilePutting(Sqlite db, BlobStore store) {
    var observed = new AtomicInteger(-1);
    store.put(
        new InputStream() {
          @Override
          public int read() {
            observed.set(db.contentRetention.leases());
            return -1;
          }
        });
    return observed.get();
  }
}
