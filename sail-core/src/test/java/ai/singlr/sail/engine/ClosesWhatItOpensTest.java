/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The descriptor check names what a stretch of work left open, and not what the JVM opens for a
 * read of its own: its cgroup's files, which a compiler thread reads inside whichever class runs.
 */
@EnabledOnOs(OS.LINUX)
class ClosesWhatItOpensTest {

  @TempDir Path tempDir;

  @Test
  void aFileLeftOpenIsNamed() throws Exception {
    var file = Files.writeString(tempDir.resolve("left.txt"), "open");
    var before = ClosesWhatItOpens.baseline();

    try (var left = Files.newByteChannel(file)) {
      var refused =
          assertThrows(AssertionError.class, () -> ClosesWhatItOpens.assertClosed("owner", before));

      assertEquals(
          "owner left these open: " + descriptorOf(file) + " " + file.toRealPath(),
          refused.getMessage());
    }
    assertDoesNotThrow(() -> ClosesWhatItOpens.assertClosed("owner", before));
  }

  @Test
  void theJvmsReadOfItsCgroupIsNotTheClasss() throws Exception {
    var memory = Path.of("/sys/fs/cgroup/memory.current");
    assumeTrue(Files.isReadable(memory), "a host whose cgroup names its memory");
    var before = ClosesWhatItOpens.baseline();

    try (var read = Files.newByteChannel(memory)) {
      assertDoesNotThrow(() -> ClosesWhatItOpens.assertClosed("owner", before));
    }
  }

  private static String descriptorOf(Path file) throws Exception {
    var real = file.toRealPath().toString();
    try (var fds = Files.list(Path.of("/proc/self/fd"))) {
      return fds.filter(
              fd -> {
                try {
                  return Files.readSymbolicLink(fd).toString().equals(real);
                } catch (IOException closedMeanwhile) {
                  return false;
                }
              })
          .map(fd -> fd.getFileName().toString())
          .findFirst()
          .orElseThrow();
    }
  }
}
