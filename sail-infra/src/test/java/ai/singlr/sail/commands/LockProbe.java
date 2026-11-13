/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Another process's attempt at a lock file: {@code HELD} when some process holds its lock, {@code
 * ACQUIRED} when this one could take it. A lock a process holds is invisible to itself, so only
 * another one can tell whether a host still holds its own.
 */
final class LockProbe {

  private LockProbe() {}

  /**
   * {@code <lock>}: says whether it could take the lock. {@code hold <lock>}: takes it, says {@code
   * HOLDING}, and keeps it until its standard input ends.
   */
  public static void main(String[] args) throws IOException {
    var hold = args[0].equals("hold");
    try (var channel =
        FileChannel.open(
            Path.of(args[hold ? 1 : 0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      if (!hold) {
        System.out.println(channel.tryLock() == null ? "HELD" : "ACQUIRED");
        return;
      }
      try (var held = channel.lock()) {
        System.out.println("HOLDING");
        System.in.transferTo(OutputStream.nullOutputStream());
      }
    }
  }

  /** What another JVM finds of {@code lock}. */
  static String from(Path lock) throws Exception {
    var probe = start(lock.toString());
    assertTrue(
        probe.waitFor(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS), "the probe ran");
    assertEquals(0, probe.exitValue());
    return new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
  }

  /** Another JVM holding {@code lock} until the returned holder is closed. */
  static AutoCloseable holding(Path lock) throws Exception {
    var holder = start("hold", lock.toString());
    var said = new LinkedBlockingQueue<String>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var lines = holder.inputReader(StandardCharsets.UTF_8)) {
                lines.lines().forEach(said::add);
              } catch (IOException gone) {
                var unused = gone;
              }
            });
    assertEquals(
        "HOLDING",
        said.poll(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS),
        "the holder took the lock");
    return () -> {
      holder.getOutputStream().close();
      if (!holder.waitFor(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS)) {
        holder.destroyForcibly();
      }
    };
  }

  private static Process start(String... args) throws IOException {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                LockProbe.class.getName()));
    command.addAll(List.of(args));
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }
}
