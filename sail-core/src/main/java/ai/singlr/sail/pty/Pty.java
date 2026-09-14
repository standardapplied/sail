/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pseudo-terminal pair owned by this process, with the child spawned as a session leader whose
 * controlling terminal is the slave side. The JVM cannot {@code fork()}, so the classic {@code
 * forkpty} is replaced by first principles: the master is allocated here via {@code posix_openpt}/
 * {@code grantpt}/{@code unlockpt} (libc through the same Panama discipline as the SQLite layer),
 * and the child is spawned with {@link ProcessBuilder} as {@code setsid sh -c 'exec 0<>$slave 1>&0
 * 2>&0 && exec "$@"'}: {@code setsid} makes it a session leader, and a session leader that opens a
 * terminal it has no controlling terminal for acquires it, which is exactly what {@code forkpty}
 * would have done. The slave is opened by the child alone, never here: this process is itself a
 * session leader under systemd, and a {@link ProcessBuilder} redirect to the slave would make it
 * this process's controlling terminal instead of the child's.
 *
 * <p>Reads block the calling thread (the session host dedicates a platform gather thread per
 * session — the ghostty drain discipline); a read against a dead child reports end of stream
 * ({@code EIO} on Linux) as {@code -1}, never an exception, so the reaper owns the child's ending.
 * A blocked read polls rather than sleeping in {@code read(2)} forever, so {@link #release()} —
 * which forgets the master without closing it, for a host handing its sessions to its successor —
 * is observed within a poll interval as end of stream. Read and write each allocate their own
 * per-call confined arena, so the two halves — which run on different threads (gather vs. the
 * writer's connection) — share no native buffer and cannot race.
 *
 * <p>A master may also be {@link #adopt(int) adopted}: a descriptor inherited from a previous host
 * (systemd's file descriptor store hands it back as {@code LISTEN_FDS}) becomes a {@code Pty} over
 * the same kernel terminal, whose child never noticed the host change.
 */
public final class Pty implements AutoCloseable {

  private static final int O_RDWR = 2;
  private static final int O_NOCTTY = 0x100;
  private static final long TIOCSWINSZ = 0x5414;
  private static final int EINTR = 4;
  private static final int EIO = 5;
  private static final int EBADF = 9;
  private static final short POLLIN = 1;
  private static final int READ_POLL_MILLIS = 250;

  private static final StructLayout POLLFD =
      java.lang.foreign.MemoryLayout.structLayout(
          ValueLayout.JAVA_INT.withName("fd"),
          ValueLayout.JAVA_SHORT.withName("events"),
          ValueLayout.JAVA_SHORT.withName("revents"));

  private static final StructLayout WINSIZE =
      java.lang.foreign.MemoryLayout.structLayout(
          ValueLayout.JAVA_SHORT.withName("ws_row"),
          ValueLayout.JAVA_SHORT.withName("ws_col"),
          ValueLayout.JAVA_SHORT.withName("ws_xpixel"),
          ValueLayout.JAVA_SHORT.withName("ws_ypixel"));
  private static final VarHandle WS_ROW =
      WINSIZE.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("ws_row"));
  private static final VarHandle WS_COL =
      WINSIZE.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("ws_col"));

  private record Libc(
      MethodHandle posixOpenpt,
      MethodHandle grantpt,
      MethodHandle unlockpt,
      MethodHandle ptsname,
      MethodHandle ioctl,
      MethodHandle read,
      MethodHandle write,
      MethodHandle close,
      MethodHandle poll) {}

  private static final Libc LIBC = loadLibc();

  private static Libc loadLibc() {
    var fdCall = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    var transfer =
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG);
    return new Libc(
        Native.downcall("posix_openpt", fdCall),
        Native.downcall("grantpt", fdCall),
        Native.downcall("unlockpt", fdCall),
        Native.downcall(
            "ptsname", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
        Native.downcall(
            "ioctl",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS)),
        Native.downcallCapturingErrno("read", transfer),
        Native.downcallCapturingErrno("write", transfer),
        Native.downcall("close", fdCall),
        Native.downcallCapturingErrno(
            "poll",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT)));
  }

  private static final int MAX_CHUNK = 64 * 1024;

  private final int masterFd;
  private final String slavePath;
  private volatile boolean closed;

  private Pty(int masterFd, String slavePath) {
    this.masterFd = masterFd;
    this.slavePath = slavePath;
  }

  /** Allocates a master/slave pair and stamps the initial window size on it. */
  public static Pty open(int cols, int rows) throws IOException {
    try {
      var fd = (int) LIBC.posixOpenpt().invokeExact(O_RDWR | O_NOCTTY);
      if (fd < 0) {
        throw new IOException("posix_openpt failed; no pseudo-terminals available.");
      }
      if ((int) LIBC.grantpt().invokeExact(fd) != 0 || (int) LIBC.unlockpt().invokeExact(fd) != 0) {
        var unused = (int) LIBC.close().invokeExact(fd);
        throw new IOException("grantpt/unlockpt failed for the new pseudo-terminal.");
      }
      Pty pty;
      try {
        pty = adopt(fd);
      } catch (IOException e) {
        var unused = (int) LIBC.close().invokeExact(fd);
        throw e;
      }
      try {
        pty.resize(cols, rows);
      } catch (IOException e) {
        pty.close();
        throw e;
      }
      return pty;
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("pty allocation failed", t);
    }
  }

  /**
   * Wraps an already-open master — one this process inherited rather than allocated. The kernel
   * still knows its slave, its geometry and its child; only the owner changed. A descriptor that is
   * not a pty master is refused by name, and never closed: the caller decides what an unadoptable
   * inheritance means.
   */
  public static Pty adopt(int fd) throws IOException {
    MemorySegment name;
    try {
      name = (MemorySegment) LIBC.ptsname().invokeExact(fd);
    } catch (Throwable t) {
      throw new IOException("ptsname failed for fd " + fd, t);
    }
    if (name.equals(MemorySegment.NULL)) {
      throw new IOException("File descriptor " + fd + " is not a pty master.");
    }
    return new Pty(fd, name.reinterpret(256).getString(0));
  }

  /** Closes a raw descriptor this process holds but never wrapped; errors are the caller's. */
  public static void closeFd(int fd) throws IOException {
    try {
      if ((int) LIBC.close().invokeExact(fd) != 0) {
        throw new IOException("close failed for fd " + fd + ".");
      }
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("close failed for fd " + fd, t);
    }
  }

  /**
   * Spawns {@code command} as a session leader with this pty as its controlling terminal. The
   * caller owns the returned process's ending: waiting, exit codes, and reaping happen there.
   */
  public Process spawn(List<String> command, Map<String, String> env, Path cwd) throws IOException {
    requireOpen();
    var full = new ArrayList<String>(command.size() + 5);
    full.add("setsid");
    full.add("sh");
    full.add("-c");
    full.add("exec 0<>\"$0\" 1>&0 2>&0 && exec \"$@\"");
    full.add(slavePath);
    full.addAll(command);
    var builder = new ProcessBuilder(full);
    builder.environment().putAll(env);
    builder.directory(cwd.toFile());
    var devNull = new File("/dev/null");
    builder.redirectInput(devNull);
    builder.redirectOutput(devNull);
    builder.redirectError(devNull);
    return builder.start();
  }

  /**
   * Reads from the master into {@code buf}, blocking until output arrives. Returns the byte count,
   * or {@code -1} once the child side is gone ({@code EIO}) or this pty is closed.
   */
  public int read(byte[] buf) throws IOException {
    while (true) {
      if (closed) {
        return -1;
      }
      if (!readable()) {
        continue;
      }
      long n;
      int err;
      try (var arena = Arena.ofConfined()) {
        var cap = Math.min(buf.length, MAX_CHUNK);
        var slot = arena.allocate(cap);
        var errno = arena.allocate(Native.ERRNO_STATE);
        try {
          n = (long) LIBC.read().invokeExact(errno, masterFd, slot, (long) cap);
        } catch (Throwable t) {
          throw new IOException("pty read failed", t);
        }
        err = Native.errno(errno);
        if (n > 0) {
          MemorySegment.ofArray(buf).copyFrom(slot.asSlice(0, n));
          return (int) n;
        }
      }
      if (n == 0) {
        return -1;
      }
      if (err == EINTR) {
        continue;
      }
      if (err == EIO || err == EBADF || closed) {
        return -1;
      }
      throw new IOException("pty read failed with errno " + err + ".");
    }
  }

  /**
   * Waits up to a poll interval for the master to have something to say — output, a hangup, or an
   * error; the read that follows tells which. A timeout or a signal is {@code false}: look at
   * {@link #closed} and come back.
   */
  private boolean readable() throws IOException {
    int rc;
    int err;
    try (var arena = Arena.ofConfined()) {
      var pollfd = arena.allocate(POLLFD);
      pollfd.set(ValueLayout.JAVA_INT, 0, masterFd);
      pollfd.set(ValueLayout.JAVA_SHORT, 4, POLLIN);
      var errno = arena.allocate(Native.ERRNO_STATE);
      try {
        rc = (int) LIBC.poll().invokeExact(errno, pollfd, 1L, READ_POLL_MILLIS);
      } catch (Throwable t) {
        throw new IOException("pty poll failed", t);
      }
      err = Native.errno(errno);
    }
    if (rc > 0) {
      return true;
    }
    if (rc == 0 || err == EINTR) {
      return false;
    }
    throw new IOException("pty poll failed with errno " + err + ".");
  }

  /** Writes {@code data} to the master — keystrokes bound for the child. */
  public void write(byte[] data) throws IOException {
    requireOpen();
    var offset = 0;
    while (offset < data.length) {
      var chunk = Math.min(data.length - offset, MAX_CHUNK);
      long n;
      int err;
      try (var arena = Arena.ofConfined()) {
        var slot = arena.allocate(chunk);
        var errno = arena.allocate(Native.ERRNO_STATE);
        slot.copyFrom(MemorySegment.ofArray(data).asSlice(offset, chunk));
        try {
          n = (long) LIBC.write().invokeExact(errno, masterFd, slot, (long) chunk);
        } catch (Throwable t) {
          throw new IOException("pty write failed", t);
        }
        err = Native.errno(errno);
      }
      if (n < 0) {
        if (err == EINTR) {
          continue;
        }
        throw new IOException("pty write failed with errno " + err + ".");
      }
      offset += (int) n;
    }
  }

  /** Sets the window size; the kernel delivers {@code SIGWINCH} to the foreground group. */
  public void resize(int cols, int rows) throws IOException {
    requireOpen();
    try (var local = Arena.ofConfined()) {
      var ws = local.allocate(WINSIZE);
      WS_ROW.set(ws, 0L, (short) rows);
      WS_COL.set(ws, 0L, (short) cols);
      var rc = (int) LIBC.ioctl().invokeExact(masterFd, TIOCSWINSZ, ws);
      if (rc != 0) {
        throw new IOException("TIOCSWINSZ failed on the pty master.");
      }
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("pty resize failed", t);
    }
  }

  public String slavePath() {
    return slavePath;
  }

  /** The master descriptor — what a host pushes to the fd store and a successor adopts. */
  public int fd() {
    return masterFd;
  }

  /**
   * Forgets the master without closing it: every later read reports end of stream and every write
   * refuses, but the kernel terminal — and the child on its slave — live on for whoever holds the
   * descriptor next. Idempotent, and a no-op after {@link #close()}.
   */
  public void release() {
    closed = true;
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("This pty is closed.");
    }
  }

  /**
   * Closes the master fd. Safe under concurrency: read and write hold no shared native state (each
   * allocates a per-call confined arena), so a close racing an in-flight op cannot corrupt memory
   * or throw an arena-in-use error — the fd close simply lands, and a blocked read observes it as
   * end of stream (a closed fd reports {@code EBADF}).
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      var ignored = (int) LIBC.close().invokeExact(masterFd);
    } catch (Throwable t) {
      throw new IllegalStateException("pty close failed", t);
    }
  }
}
