/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * Another process's attempt at a lock file: {@code HELD} when some process holds its lock, {@code
 * ACQUIRED} when this one could take it. A lock a process holds is invisible to itself, so only
 * another one can tell whether a host still holds its own.
 */
final class LockProbe {

  private LockProbe() {}

  public static void main(String[] args) throws IOException {
    try (var channel =
        FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      System.out.println(channel.tryLock() == null ? "HELD" : "ACQUIRED");
    }
  }

  /** What another JVM finds of {@code lock}. */
  static String from(Path lock) throws Exception {
    var probe =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                LockProbe.class.getName(),
                lock.toString())
            .redirectErrorStream(true)
            .start();
    assertTrue(
        probe.waitFor(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS), "the probe ran");
    assertEquals(0, probe.exitValue());
    return new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
  }
}
