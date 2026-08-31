/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SessionYield;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The per-project lock a dispatch claim and a resume-session open both hold: an exclusive {@link
 * java.nio.channels.FileLock} on {@code <dir>/<project>.lock}, so the API server, a CLI dispatch,
 * and {@code sail agent attach} — separate processes on the same host — serialize on the kernel's
 * word. A file lock is per JVM, not per thread, so an in-process mutex per lock file fronts it: two
 * server threads claiming the same project queue on the mutex instead of tripping over an
 * overlapping lock. Released by closing: closing the channel drops the file lock even when the
 * close itself reports an error, so the release path swallows that error rather than leaking the
 * mutex.
 */
final class SessionDispatchLock implements SessionYield.Hold {

  private static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

  private final ReentrantLock inProcess;
  private final FileChannel channel;

  private SessionDispatchLock(ReentrantLock inProcess, FileChannel channel) {
    this.inProcess = inProcess;
    this.channel = channel;
  }

  static SessionDispatchLock acquire(Path dir, String project) throws IOException {
    var file = dir.resolve(project + ".lock").toAbsolutePath().normalize();
    var inProcess = IN_PROCESS.computeIfAbsent(file, f -> new ReentrantLock());
    inProcess.lock();
    try {
      Files.createDirectories(dir);
      var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      try {
        channel.lock();
        return new SessionDispatchLock(inProcess, channel);
      } catch (IOException | RuntimeException e) {
        channel.close();
        throw e;
      }
    } catch (IOException | RuntimeException e) {
      inProcess.unlock();
      throw e;
    }
  }

  @Override
  public void close() {
    try {
      channel.close();
    } catch (IOException ignored) {
    } finally {
      inProcess.unlock();
    }
  }
}
