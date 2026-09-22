/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FastCdc;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobStreamingTest {
  @TempDir Path dir;

  @Test
  void aFileLargerThanTheHeapIsIngestedSyncedAndMaterialized() throws Exception {
    probe(Probe.class);
  }

  @Test
  void aPageOfManyFilesWithLongManifestsPullsWithinOneManifestOfHeap() throws Exception {
    probe(ManifestsProbe.class);
  }

  private void probe(Class<?> probe) throws Exception {
    var log = dir.resolve("probe.log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx48m",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                probe.getName(),
                dir.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(2, TimeUnit.MINUTES), "streaming probe timed out");
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(log);
            } catch (java.io.IOException e) {
              return e.toString();
            }
          });
    } finally {
      process.destroyForcibly();
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      var directory = Path.of(args[0]);
      var source = directory.resolve("source.bin");
      var random = new Random(615);
      var buffer = new byte[64 * 1024];
      try (var output = Files.newOutputStream(source)) {
        for (var i = 0; i < 1024; i++) {
          random.nextBytes(buffer);
          output.write(buffer);
        }
      }
      try (var main = new SyncBox(directory, "main");
          var node = new SyncBox(directory, "node")) {
        var files = new FileStore(main.db);
        try (var input = Files.newInputStream(source)) {
          files.put("project", "data.bin", input, 0750);
        }
        SyncBox.round(main.db, node.db, "file");
        new FileMaterializer(new FileStore(node.db), directory.resolve("projects"))
            .materialize("project");
        var destination = directory.resolve("projects/project/files/data.bin");
        if (Files.size(destination) != Files.size(source))
          throw new AssertionError("length changed");
        try (var expected = Files.newInputStream(source);
            var actual = Files.newInputStream(destination)) {
          if (!BlobStore.hash(expected).equals(BlobStore.hash(actual)))
            throw new AssertionError("content changed");
        }
        if (WorkspaceFiles.mode(destination) != 0750) throw new AssertionError("mode changed");
      }
    }
  }

  /**
   * 256 files on main, each a manifest of ~1,615 chunks over one shared 64 KiB chunk: ~100 MiB
   * apiece, of which 64 KiB ever crosses. A node that keeps a page's manifests before fetching runs
   * out of a 48 MiB heap after about 225 of them; one that brings each blob home in turn never
   * holds more than one. Main's rows are written directly: the blob store verifies what it
   * assembles, and here only the node assembles.
   */
  public static final class ManifestsProbe {
    private static final int FILES = 256;
    private static final int CHUNKS_PER_FILE = 1615;

    public static void main(String[] args) throws Exception {
      var directory = Path.of(args[0]);
      try (var main = new SyncBox(directory, "main");
          var node = new SyncBox(directory, "node")) {
        var files = new FileStore(main.db);
        var chunk = new byte[FastCdc.MIN];
        new Random(1615).nextBytes(chunk);
        var chunkHash = BlobStore.hash(chunk);
        new BlobStore(main.db).putChunk(chunkHash, chunk);
        for (var i = 0; i < FILES; i++) {
          var count = CHUNKS_PER_FILE + i;
          var digest = MessageDigest.getInstance("SHA-256");
          for (var c = 0; c < count; c++) digest.update(chunk);
          var manifest =
              new BlobStore.Manifest(
                  HexFormat.of().formatHex(digest.digest()),
                  (long) count * chunk.length,
                  Collections.nCopies(count, chunkHash));
          main.db.execute(
              "INSERT INTO blobs (hash, size, chunks, created_at) VALUES (?, ?, ?, 'now')",
              manifest.hash(),
              manifest.size(),
              YamlUtil.dumpJson(manifest.chunkHashes()));
          files.put(
              new FileStore.FileRow(
                  "project",
                  "data-" + i + ".bin",
                  manifest.hash(),
                  manifest.size(),
                  0644,
                  "binary"));
        }
        var report = SyncBox.round(main.db, node.db, "file");
        if (report.pulled() != FILES) throw new AssertionError("pulled " + report.pulled());
        var nodeFiles = new FileStore(node.db);
        for (var i = 0; i < FILES; i++) {
          if (nodeFiles.find("project", "data-" + i + ".bin").isEmpty())
            throw new AssertionError("data-" + i + ".bin missing on the node");
        }
      }
    }
  }
}
