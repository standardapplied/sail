/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class BlobRetention {
  private final Path database;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private FileChannel channel;
  private int leases;

  BlobRetention(Path database) {
    this.database = database;
  }

  BlobStore.Scope acquireShared() {
    return acquire(lock.readLock(), true);
  }

  BlobStore.Scope acquireExclusive() {
    if (lock.getReadHoldCount() > 0 && !lock.isWriteLockedByCurrentThread()) {
      throw new IllegalStateException("Release blob retention before collecting content");
    }
    return acquire(lock.writeLock(), false);
  }

  private BlobStore.Scope acquire(Lock local, boolean shared) {
    if (!local.tryLock()) local.lock();
    try {
      synchronized (this) {
        if (leases == 0 && database != null) channel = open(shared);
        leases++;
      }
      return () -> release(local);
    } catch (IOException | RuntimeException e) {
      local.unlock();
      throw new IllegalStateException("Cannot lock blob content for " + database, e);
    }
  }

  private FileChannel open(boolean shared) throws IOException {
    var path = database.resolveSibling(database.getFileName() + ".blobs.lock");
    try {
      Files.createFile(path);
      var source = Files.readAttributes(database, PosixFileAttributes.class);
      Files.getFileAttributeView(path, PosixFileAttributeView.class).setGroup(source.group());
      Files.setPosixFilePermissions(path, source.permissions());
    } catch (FileAlreadyExistsException ignored) {
    }
    var opened = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    try {
      opened.lock(0, Long.MAX_VALUE, shared);
      return opened;
    } catch (IOException | RuntimeException e) {
      try {
        opened.close();
      } catch (IOException close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  private void release(Lock local) {
    try {
      synchronized (this) {
        if (--leases == 0 && channel != null) {
          var held = channel;
          channel = null;
          held.close();
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot release blob retention", e);
    } finally {
      local.unlock();
    }
  }
}
