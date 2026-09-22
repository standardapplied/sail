/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FastCdc;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncBlobTest {

  @Test
  void idleViewerAllowsOtherSessionsAndIngestsWhileGcWaits() throws Exception {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node");
        var ingestDb = Sqlite.open(dir.resolve("main.db"));
        var gcDb = Sqlite.open(dir.resolve("main.db"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var reading = new CountDownLatch(1);
      var disconnect = new CountDownLatch(1);
      var input =
          new InputStream() {
            @Override
            public int read() throws IOException {
              reading.countDown();
              try {
                disconnect.await();
                return -1;
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
              }
            }
          };
      var blobs = new BlobStore(main.db);
      var orphan = blobs.putText("unfinished upload");
      var viewer =
          executor.submit(
              () -> {
                main.server(new SyncPrincipal("viewer", false))
                    .serve(input, OutputStream.nullOutputStream());
                return null;
              });
      try {
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        var collecting = executor.submit(() -> new BlobStore(gcDb).gc(Set.of()));
        assertThrows(TimeoutException.class, () -> collecting.get(100, TimeUnit.MILLISECONDS));
        var upload =
            executor.submit(
                () -> {
                  new FileStore(ingestDb)
                      .put("project", "file", new ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
                  return null;
                });
        upload.get(5, TimeUnit.SECONDS);
        var syncing = executor.submit(() -> SyncBox.round(ingestDb, node.db, "file"));
        assertEquals(1, syncing.get(5, TimeUnit.SECONDS).pulled());
        assertTrue(new FileStore(node.db).find("project", "file").isPresent());
        assertThrows(TimeoutException.class, () -> collecting.get(100, TimeUnit.MILLISECONDS));
        assertTrue(blobs.has(orphan));
        disconnect.countDown();
        viewer.get(5, TimeUnit.SECONDS);
        assertEquals(17, collecting.get(5, TimeUnit.SECONDS));
        assertFalse(blobs.has(orphan));
      } finally {
        disconnect.countDown();
      }
    }
  }

  @Test
  void convergingDeletionsCollectOrphanedChunksAfterAdoptingTheTombstone() {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      var mainFiles = new FileStore(main.db);
      mainFiles.put("project", "file", new ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      SyncBox.round(main.db, node.db, "file");
      mainFiles.delete("project", "file");
      new FileStore(node.db).delete("project", "file");
      var blobs = new BlobStore(node.db);
      var orphan = blobs.putText("unfinished upload");

      var report = SyncBox.round(main.db, node.db, "file");

      assertEquals(0, report.pulled());
      assertFalse(blobs.has(orphan));
      assertEquals(
          mainFiles.latestRev("project/file"), new FileStore(node.db).latestRev("project/file"));
    }
  }

  @TempDir Path dir;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void combinedChunkInventoriesStayWithinTheFrameInBothDirections(boolean upload) throws Exception {
    var frame = 900;
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      var files = new FileStore((upload ? node : main).db);
      for (var file = 0; file < 2; file++) {
        var bytes = new byte[7 * FastCdc.MIN];
        new Random(file).nextBytes(bytes);
        var chunks = new ArrayList<String>();
        for (var offset = 0; offset < bytes.length; offset += FastCdc.MIN) {
          var chunk = Arrays.copyOfRange(bytes, offset, offset + FastCdc.MIN);
          var hash = BlobStore.hash(chunk);
          files.blobs().putChunk(hash, chunk);
          chunks.add(hash);
        }
        var hash = BlobStore.hash(bytes);
        files.blobs().assemble(new BlobStore.Manifest(hash, bytes.length, chunks));
        files.put(
            new FileStore.FileRow("project", "file-" + file, hash, bytes.length, 0750, "binary"));
      }
      try (var link =
          SyncBox.connect(
              main.server(new SyncPrincipal("node", true)),
              node,
              frame,
              output -> boundedLines(output, frame))) {
        var session = ((PagedSyncSession) link.session()).frame(frame);
        var report =
            session.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(14L * FastCdc.MIN, upload ? report.sentBytes() : report.fetchedBytes());
        assertTrue(link.count(upload ? "announce" : "fetch_chunks") >= 2);
        if (upload) assertEquals(link.count("push"), link.count("announce"));
        assertTrue(
            link.log()
                .toString()
                .lines()
                .allMatch(
                    line ->
                        line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= frame));
      }
      var destination = new FileStore((upload ? main : node).db);
      assertEquals(files.list("project"), destination.list("project"));
    }
  }

  private static OutputStream boundedLines(OutputStream output, int frame) {
    return new FilterOutputStream(output) {
      private int raw;

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        if (raw > 0) raw -= length;
        else {
          if (length > frame) throw new IOException("Announcing line exceeded frame: " + length);
          var message =
              YamlUtil.parseMap(
                  new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8));
          if ("chunk".equals(message.get("op"))) raw = ((Number) message.get("size")).intValue();
        }
        out.write(bytes, offset, length);
      }
    };
  }

  @Test
  void fortyMegabytesCrossOnceAndAnEditTransfersOnlyOneChunk() throws Exception {
    var path = randomFile(40 * 1024 * 1024);
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      var files = new FileStore(main.db);
      try (var input = Files.newInputStream(path)) {
        files.put("one", "data.bin", input, 0750);
      }
      var first = files.find("one", "data.bin").orElseThrow();
      files.put(
          new FileStore.FileRow(
              "two", "data.bin", first.contentHash(), first.size(), first.mode(), first.kind()));
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        var report =
            link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(Files.size(path), report.fetchedBytes());
        assertEquals(1, link.count("fetch"));
      }
      var localFiles = new FileStore(node.db);
      assertEquals(0750, localFiles.find("one", "data.bin").orElseThrow().mode());
      assertEquals(
          1, node.db.queryOne("SELECT count(*) FROM blobs", r -> r.integer(0)).orElseThrow());
      try (var input = localFiles.open(localFiles.find("one", "data.bin").orElseThrow())) {
        assertEquals(first.contentHash(), BlobStore.hash(input));
      }
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        assertEquals(
            0,
            link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"))
                .fetchedBytes());
        assertEquals(0, link.count("fetch_chunks"));
      }
      try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
        file.seek(20 * 1024 * 1024);
        file.write(new byte[1024]);
      }
      try (var input = Files.newInputStream(path)) {
        files.put("one", "data.bin", input, 0750);
      }
      var next = files.find("one", "data.bin").orElseThrow();
      var changed = new HashSet<>(files.blobs().manifest(next.contentHash()).chunkHashes());
      changed.removeAll(files.blobs().manifest(first.contentHash()).chunkHashes());
      assertEquals(1, changed.size());
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        var report =
            link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(files.blobs().chunk(changed.iterator().next()).length, report.fetchedBytes());
        assertEquals(1, requestedChunks(link));
      }
    }
  }

  @Test
  void aCutInAChunkStoresOnlyVerifiedChunksAndResumesAtTheMissingOnes() throws Exception {
    var path = randomFile(5 * 1024 * 1024);
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      var files = new FileStore(main.db);
      try (var input = Files.newInputStream(path)) {
        files.put("proj", "binary", input, 0640);
      }
      var hash = files.find("proj", "binary").orElseThrow().contentHash();
      var all = new HashSet<>(files.blobs().manifest(hash).chunkHashes());
      var cut = cutDuringChunk(3);
      var failure =
          assertThrows(
              SyncTransportException.class,
              () -> {
                try (var link =
                    SyncBox.connect(
                        main.server(new SyncPrincipal("node", true)),
                        node,
                        SyncWire.MAX_FRAME,
                        cut)) {
                  link.reconcile(
                      "file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
                }
              });
      assertEquals("unreachable", failure.kind());
      var held =
          node.db.query(
              "SELECT hash, bytes FROM chunks",
              r -> {
                assertEquals(r.text(0), BlobStore.hash(r.bytes(1)));
                return r.text(0);
              });
      assertEquals(2, held.size());
      assertEquals(0, node.syncState.checkpoint("main", "file"));
      assertFalse(new BlobStore(node.db).has(hash));
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(all.size() - held.size(), requestedChunks(link));
      }
      assertEquals(hash, new FileStore(node.db).find("proj", "binary").orElseThrow().contentHash());
    }
  }

  @Test
  void uploadsAnnounceContentBeforeCommittingAndSkipBlobsAlreadyOnMain() throws Exception {
    var path = randomFile(40 * 1024 * 1024);
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      var files = new FileStore(node.db);
      try (var input = Files.newInputStream(path)) {
        files.put("proj", "binary", input, 0750);
      }
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        var report =
            link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(Files.size(path), report.sentBytes());
        assertEquals(1, report.report().pushed());
        assertTrue(link.ops().indexOf("announce") < link.ops().indexOf("push"));
        assertTrue(link.count("chunk") > 1);
      }
      var file = files.find("proj", "binary").orElseThrow();
      files.put(
          new FileStore.FileRow(
              "other", "copy", file.contentHash(), file.size(), 0640, file.kind()));
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        var report =
            link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(0, report.sentBytes());
        assertEquals(0, link.count("chunk"));
      }
      assertEquals(
          1, main.db.queryOne("SELECT COUNT(*) FROM blobs", row -> row.integer(0)).orElseThrow());
      assertEquals(0640, new FileStore(main.db).find("other", "copy").orElseThrow().mode());
      try (var disk = new java.io.RandomAccessFile(path.toFile(), "rw")) {
        disk.seek(20 * 1024 * 1024);
        disk.write(new byte[1024]);
      }
      try (var input = Files.newInputStream(path)) {
        files.put("proj", "binary", input, 0750);
      }
      var next = files.find("proj", "binary").orElseThrow();
      var changed = new HashSet<>(files.blobs().manifest(next.contentHash()).chunkHashes());
      changed.removeAll(files.blobs().manifest(file.contentHash()).chunkHashes());
      assertEquals(1, changed.size());
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        var report =
            link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(1, link.count("chunk"));
        assertEquals(files.blobs().chunk(changed.iterator().next()).length, report.sentBytes());
      }
    }
  }

  @Test
  void aCutUploadRetriesOnlyChunksMainDidNotVerify() throws Exception {
    var path = randomFile(40 * 1024 * 1024);
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      var files = new FileStore(node.db);
      try (var input = Files.newInputStream(path)) {
        files.put("proj", "binary", input, 0750);
      }
      var hash = files.find("proj", "binary").orElseThrow().contentHash();
      var chunks = new HashSet<>(files.blobs().manifest(hash).chunkHashes());
      var failure =
          assertThrows(
              SyncTransportException.class,
              () -> {
                try (var link =
                    SyncBox.connect(
                        main.server(new SyncPrincipal("node", true)),
                        node,
                        SyncWire.MAX_FRAME,
                        output -> output,
                        cutDuringChunk(3))) {
                  link.reconcile(
                      "file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
                }
              });
      assertEquals("unreachable", failure.kind());
      var held =
          main.db.query(
              "SELECT hash, bytes FROM chunks",
              row -> {
                assertEquals(row.text(0), BlobStore.hash(row.bytes(1)));
                return row.text(0);
              });
      assertEquals(2, held.size());
      assertFalse(new BlobStore(main.db).has(hash));
      assertTrue(new FileStore(main.db).list("proj").isEmpty());
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
        assertEquals(chunks.size() - held.size(), link.count("chunk"));
      }
      assertTrue(new BlobStore(main.db).has(hash));
    }
  }

  @Test
  void aViewerIsRefusedAtAnnounceBeforeMainStoresAnyContent() throws Exception {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      new FileStore(node.db)
          .put("proj", "binary", new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      var link = SyncBox.connect(main.server(SyncPrincipal.readOnly()), node);
      var failure =
          assertThrows(
              SyncTransportException.class,
              () -> {
                try (link) {
                  link.reconcile(
                      "file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
                }
              });
      assertEquals("refused", failure.kind());
      assertEquals(1, link.count("announce"));
      assertEquals(0, link.count("chunk"));
      assertEquals(
          0, main.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
      assertEquals(
          0, main.db.queryOne("SELECT COUNT(*) FROM blobs", row -> row.integer(0)).orElseThrow());
    }
  }

  @Test
  void mainRefusesAnUnsolicitedChunkWithoutStoringIt() throws Exception {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      new FileStore(node.db)
          .put("proj", "binary", new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      var unsolicited = new byte[] {7, 8, 9};
      var hash = BlobStore.hash(unsolicited);
      var replies = new ByteStreams.Output();
      UnaryOperator<OutputStream> record =
          output ->
              new FilterOutputStream(output) {
                @Override
                public void write(int value) throws IOException {
                  out.write(value);
                  replies.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                  out.write(bytes, offset, length);
                  replies.write(bytes, offset, length);
                }
              };
      UnaryOperator<OutputStream> inject =
          output ->
              new FilterOutputStream(output) {
                private boolean raw;

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                  if (raw) {
                    out.write(unsolicited);
                    raw = false;
                    return;
                  }
                  var line =
                      new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8);
                  if (line.contains("\"op\": \"chunk\"")) {
                    out.write(
                        (SyncWire.encode(new SyncWire.Chunk(hash, unsolicited.length)) + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    raw = true;
                  } else out.write(bytes, offset, length);
                }
              };
      assertThrows(
          RuntimeException.class,
          () -> {
            try (var link =
                SyncBox.connect(
                    main.server(new SyncPrincipal("node", true)),
                    node,
                    SyncWire.MAX_FRAME,
                    record,
                    inject)) {
              link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
            }
          });
      var failed =
          (SyncWire.Failed) SyncWire.decodeResponse(replies.toString().lines().toList().getLast());
      assertEquals("protocol", failed.kind());
      assertTrue(failed.message().contains(hash));
      assertEquals(
          0, main.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
    }
  }

  @Test
  void mainRefusesAnOversizedManifestBeforeAcceptingAnyChunk() throws Exception {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      var files = new FileStore(node.db);
      files.put("proj", "binary", new java.io.ByteArrayInputStream(new byte[1024]), 0644);
      var hash = files.find("proj", "binary").orElseThrow().contentHash();
      var server =
          main.server(new SyncPrincipal("node", true))
              .content(main.db, new ai.singlr.sail.config.FileLimits(512));
      var failure =
          assertThrows(
              SyncTransportException.class,
              () -> {
                try (var link = SyncBox.connect(server, node)) {
                  link.reconcile(
                      "file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
                }
              });
      assertTrue(failure.getMessage().contains(hash), failure.getMessage());
      assertTrue(failure.getMessage().contains("file_max"), failure.getMessage());
      assertTrue(failure.getMessage().contains("proj/binary"), failure.getMessage());
      assertEquals("refused", failure.kind());
      assertEquals(
          0, main.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
      assertTrue(new FileStore(main.db).list("proj").isEmpty());
    }
  }

  @Test
  void contentInANeedAnswerIsFetchedBeforeTheConflictIsParked() throws Exception {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      main.specs.create(SyncBox.spec("a", "A", "pending"));
      main.specs.setContent("a", "base", "");
      SyncBox.round(main.db, node.db, "spec");
      node.specs.setContent("a", "mine", "");
      main.specs.setContent("a", "theirs from need", "");
      var remoteHash = main.specs.comparableSnapshot("a").get("body_hash").toString();
      node.syncState.advance("main", "spec", main.replica.maxSeq());
      try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
        var report =
            link.reconcile("spec", SyncedEntities.replicas(node.db, "node", "node").get("spec"));
        assertEquals(0, link.count("pull"));
        assertEquals(1, link.count("need"));
        assertEquals(1, report.report().conflicts());
        assertEquals("theirs from need", new BlobStore(node.db).text(remoteHash));
      }
      assertEquals("mine", node.specs.getContent("a").orElseThrow().body());
    }
  }

  @Test
  void aRejectedPushFetchesTheNewMainContentBeforeReconcilingAgain() throws Exception {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node")) {
      main.specs.create(SyncBox.spec("a", "A", "pending"));
      main.specs.setContent("a", "base", "");
      SyncBox.round(main.db, node.db, "spec");
      node.specs.update(SyncBox.spec("a", "local title", "pending"));
      main.specs.update(SyncBox.spec("a", "A", "pending"));
      var changed = new java.util.concurrent.atomic.AtomicBoolean();
      UnaryOperator<OutputStream> race =
          output ->
              new FilterOutputStream(output) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                  var line =
                      new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8);
                  if (line.contains("\"op\": \"page\"") && changed.compareAndSet(false, true))
                    main.specs.setContent("a", "body landed after page", "");
                  out.write(bytes, offset, length);
                }
              };
      try (var link =
          SyncBox.connect(
              main.server(new SyncPrincipal("node", true)), node, SyncWire.MAX_FRAME, race)) {
        var report =
            link.reconcile("spec", SyncedEntities.replicas(node.db, "node", "node").get("spec"));
        assertEquals(2, link.count("push"));
        assertEquals(1, link.count("need"));
        assertEquals(1, report.report().merged());
        assertEquals("body landed after page", node.specs.getContent("a").orElseThrow().body());
        assertEquals("local title", main.specs.findById("a").orElseThrow().title());
        assertTrue(
            new BlobStore(node.db)
                .has(main.specs.comparableSnapshot("a").get("body_hash").toString()));
      }
    }
  }

  private static UnaryOperator<OutputStream> cutDuringChunk(int number) {
    var chunks = new AtomicInteger();
    return output ->
        new FilterOutputStream(output) {
          private int raw;

          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            if (raw > 0) {
              if (chunks.get() == number) {
                out.write(bytes, offset, length / 2);
                out.close();
                throw new IOException("cut during a chunk");
              }
              raw -= length;
            } else if (length > 1 && bytes[offset] == '{') {
              var message =
                  YamlUtil.parseMap(
                      new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8));
              if ("chunk".equals(message.get("op"))) {
                raw = ((Number) message.get("size")).intValue();
                chunks.incrementAndGet();
              }
            }
            out.write(bytes, offset, length);
          }
        };
  }

  private static int requestedChunks(SyncBox.Link link) {
    return link.log()
        .toString()
        .lines()
        .map(YamlUtil::parseMap)
        .filter(m -> "fetch_chunks".equals(m.get("op")))
        .mapToInt(m -> ((java.util.List<?>) m.get("hashes")).size())
        .sum();
  }

  private Path randomFile(int size) throws IOException {
    var file = dir.resolve("random.bin");
    var random = new Random(4321);
    var buffer = new byte[64 * 1024];
    try (var output = Files.newOutputStream(file)) {
      for (var remaining = size; remaining > 0; remaining -= buffer.length) {
        random.nextBytes(buffer);
        output.write(buffer, 0, Math.min(remaining, buffer.length));
      }
    }
    return file;
  }
}
