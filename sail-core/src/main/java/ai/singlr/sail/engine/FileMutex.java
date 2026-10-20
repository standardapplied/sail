/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An exclusive {@link java.nio.channels.FileLock} on one file, so the API server and the CLI —
 * separate processes on the same host — serialize on the kernel's word: a dispatch claim and a
 * resume-session open on {@code <locks>/<project>.lock}, a sync round and a handle change on the
 * box's round lock ({@link #holdRounds}). A file lock is per JVM, not per thread, so an in-process
 * mutex per lock file fronts it: two threads taking the same file queue on the mutex instead of
 * tripping over an overlapping lock. A lock file it creates is readable and writable by its group,
 * as the database beside it is, so whichever of the API server and a root CLI makes it first, the
 * other — sharing the data directory's group — can still take it. Released by closing: closing the
 * channel drops the file lock even when the close itself reports an error, so the release path
 * swallows that error rather than leaking the mutex.
 */
public final class FileMutex implements AutoCloseable {

  private static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();
  private static final Set<PosixFilePermission> GROUP_SHARED =
      PosixFilePermissions.fromString("rw-rw----");

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
      var channel = open(normalized);
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

  /**
   * Holds off every other sync round of the box whose database is {@code database}, and every other
   * write of its identity, until closed. Each round runs under it, from reading the box's identity
   * to its last answer; a handle change holds it from asking main which runs it took until they are
   * re-stamped, so no run can be offered, or its answer lost, between the two; and a migration or
   * {@code host init} holds it from reading the identity to the write that follows, so none acts on
   * an identity half written. It is a lock file beside the database, so a round in the API server
   * and a command in the CLI exclude each other. Null for an in-memory database ({@code null}),
   * which has no other process to exclude.
   */
  public static FileMutex holdRounds(Path database) throws IOException {
    return database == null
        ? null
        : acquire(database.resolveSibling(database.getFileName() + ".rounds.lock"));
  }

  private static FileChannel open(Path file) throws IOException {
    try {
      var created = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      try {
        shareWithGroup(file);
        return created;
      } catch (IOException | RuntimeException e) {
        created.close();
        throw e;
      }
    } catch (FileAlreadyExistsException e) {
      return FileChannel.open(file, StandardOpenOption.WRITE);
    }
  }

  private static void shareWithGroup(Path file) throws IOException {
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, GROUP_SHARED);
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
