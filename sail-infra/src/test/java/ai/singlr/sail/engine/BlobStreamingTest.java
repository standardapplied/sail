/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobStreamingTest {
  @TempDir Path dir;

  @Test
  void aFileLargerThanTheHeapIsIngestedSyncedAndMaterialized() throws Exception {
    var log = dir.resolve("probe.log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx48m",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(),
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
}
