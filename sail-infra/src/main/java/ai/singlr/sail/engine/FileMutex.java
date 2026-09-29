/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An exclusive {@link java.nio.channels.FileLock} on one file, so the API server and the CLI —
 * separate processes on the same host — serialize on the kernel's word: a dispatch claim and a
 * resume-session open on {@code <locks>/<project>.lock}, a sync round and a handle change on the
 * box's round lock ({@link SyncOperations#holdRounds}). A file lock is per JVM, not per thread, so
 * an in-process mutex per lock file fronts it: two threads taking the same file queue on the mutex
 * instead of tripping over an overlapping lock. Released by closing: closing the channel drops the
 * file lock even when the close itself reports an error, so the release path swallows that error
 * rather than leaking the mutex.
 */
public final class FileMutex implements AutoCloseable {

  private static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

  private final ReentrantLock inProcess;
  private final FileChannel channel;

  private FileMutex(ReentrantLock inProcess, FileChannel channel) {
    this.inProcess = inProcess;
    this.channel = channel;
  }

  /**
   * Takes the lock on {@code file}, creating it and its directory, waiting while another holds it.
   */
  public static FileMutex acquire(Path file) throws IOException {
    var normalized = file.toAbsolutePath().normalize();
    var inProcess = IN_PROCESS.computeIfAbsent(normalized, f -> new ReentrantLock());
    inProcess.lock();
    try {
      Files.createDirectories(normalized.getParent());
      var channel =
          FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      try {
        channel.lock();
        return new FileMutex(inProcess, channel);
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
