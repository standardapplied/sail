/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * A pseudo-terminal pair owned by this process, with the child spawned as a session leader whose
 * controlling terminal is the slave side. The JVM cannot {@code fork()}, so the classic {@code
 * forkpty} is replaced by first principles: the master is allocated here via {@code posix_openpt}/
 * {@code grantpt}/{@code unlockpt} (libc through the same Panama discipline as the SQLite layer),
 * and the child is spawned with {@link ProcessBuilder} as the session shim ({@link PtyChildShim}),
 * told its terminal in {@link #TERMINAL}; the shim's first act is the half of {@code forkpty} that
 * runs in the child. The slave is opened here too, with {@code O_NOCTTY} so it never becomes this
 * process's controlling terminal (this process is itself a session leader under systemd or
 * launchd), and held until the child holds it: Darwin refuses every terminal {@code ioctl} on a
 * master, its size among them, until the master's slave has been opened (XNU's {@code
 * fix_7070978}), and a slave's last close before the child opened it would end the pty.
 *
 * <p>Reads block the calling thread (the session host dedicates a platform gather thread per
 * session — the ghostty drain discipline); a read against a dead child reports end of stream
 * ({@code EIO} on Linux, a zero-length read on macOS) as {@code -1}, never an exception, so the
 * reaper owns the child's ending, and so does one whose silent job still holds the terminal once
 * the terminal has drained. A blocked read polls rather than sleeping in {@code read(2)} forever,
 * so {@link #release()} — which forgets the master without closing it, for a host handing its
 * sessions to its successor — is observed within a poll interval as end of stream. Read and write
 * each allocate their own per-call confined arena, so the two halves — which run on different
 * threads (gather vs. the writer's connection) — share no native buffer and cannot race.
 *
 * <p>A master may also be {@link #adopt(int) adopted}: a descriptor inherited from a previous host
 * (systemd's file descriptor store hands it back as {@code LISTEN_FDS}) becomes a {@code Pty} over
 * the same kernel terminal, whose child never noticed the host change.
 */
public final class Pty implements AutoCloseable {

  /** The variable {@link #spawn} names the child's terminal in, for the shim to claim. */
  static final String TERMINAL = "SAIL_PTY_TERMINAL";

  /**
   * How long {@link #spawn} waits for the shim to claim its terminal: a native shim takes
   * milliseconds and one run from a class path well under a second, so a shim this late is not
   * coming, and is stopped.
   */
  static final Duration CLAIM_WAIT = Duration.ofSeconds(10);

  /**
   * How long {@link #spawn} waits for a shim it killed to be gone, so a refused claim holds its
   * caller about {@link #CLAIM_WAIT} and not twice that.
   */
  private static final Duration STOP_WAIT = Duration.ofSeconds(1);

  /**
   * How long a terminal must have been quiet, once its child is gone, for a read to report end of
   * stream; also how often a blocked read looks again at {@link #release()}. A session is its
   * shell: what is already in the terminal when the shell exits is read, and a job it left running
   * keeps nothing it says after this grace.
   */
  static final int DRAIN_GRACE_MILLIS = 250;

  private static final int NO_SLAVE = -1;
  private static final int EINTR = 4;
  private static final int EIO = 5;
  private static final int EBADF = 9;
  private static final short POLLIN = 1;

  private static final StructLayout POLLFD =
      MemoryLayout.structLayout(
          ValueLayout.JAVA_INT.withName("fd"),
          ValueLayout.JAVA_SHORT.withName("events"),
          ValueLayout.JAVA_SHORT.withName("revents"));

  private static final StructLayout WINSIZE =
      MemoryLayout.structLayout(
          ValueLayout.JAVA_SHORT.withName("ws_row"),
          ValueLayout.JAVA_SHORT.withName("ws_col"),
          ValueLayout.JAVA_SHORT.withName("ws_xpixel"),
          ValueLayout.JAVA_SHORT.withName("ws_ypixel"));
  private static final VarHandle WS_ROW =
      WINSIZE.varHandle(MemoryLayout.PathElement.groupElement("ws_row"));
  private static final VarHandle WS_COL =
      WINSIZE.varHandle(MemoryLayout.PathElement.groupElement("ws_col"));

  private record Libc(
      MethodHandle posixOpenpt,
      MethodHandle grantpt,
      MethodHandle unlockpt,
      MethodHandle ptsname,
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
        Native.downcall("read", transfer, Native.CAPTURE_ERRNO),
        Native.downcall("write", transfer, Native.CAPTURE_ERRNO),
        Native.downcall("close", fdCall),
        Native.downcall(
                "poll",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    Native.PLATFORM.nfdsT(),
                    ValueLayout.JAVA_INT),
                Native.CAPTURE_ERRNO)
            .asType(
                MethodType.methodType(
                    int.class, MemorySegment.class, MemorySegment.class, int.class, int.class)));
  }

  private static final int MAX_CHUNK = 64 * 1024;

  private final int masterFd;
  private final String slavePath;
  private int slaveFd;
  private volatile boolean closed;
  private volatile boolean childGone;

  private enum Poll {
    READY,
    QUIET,
    INTERRUPTED
  }

  private Pty(int masterFd, String slavePath, int slaveFd) {
    this.masterFd = masterFd;
    this.slavePath = slavePath;
    this.slaveFd = slaveFd;
  }

  /** Allocates a master/slave pair, opens its slave, and stamps the initial window size on it. */
  public static Pty open(int cols, int rows) throws IOException {
    try {
      var fd = (int) LIBC.posixOpenpt().invokeExact(Native.O_RDWR | Native.PLATFORM.oNoctty());
      if (fd < 0) {
        throw new IOException("posix_openpt failed; no pseudo-terminals available.");
      }
      if ((int) LIBC.grantpt().invokeExact(fd) != 0 || (int) LIBC.unlockpt().invokeExact(fd) != 0) {
        var unused = (int) LIBC.close().invokeExact(fd);
        throw new IOException("grantpt/unlockpt failed for the new pseudo-terminal.");
      }
      Pty pty;
      try {
        var slave = slaveOf(fd);
        pty = new Pty(fd, slave, Native.openTerminal(slave));
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
    return new Pty(fd, slaveOf(fd), NO_SLAVE);
  }

  private static String slaveOf(int fd) throws IOException {
    MemorySegment name;
    try {
      name = (MemorySegment) LIBC.ptsname().invokeExact(fd);
    } catch (Throwable t) {
      throw new IOException("ptsname failed for fd " + fd, t);
    }
    if (name.equals(MemorySegment.NULL)) {
      throw new IOException("File descriptor " + fd + " is not a pty master.");
    }
    return name.reinterpret(256).getString(0);
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
   * Spawns {@code shim}, the session shim's command line ({@link PtyChildShim} and the command it
   * runs), with this pty named as its terminal: the shim claims it and becomes the session leader
   * whose controlling terminal it is. What the shim says before it has a terminal goes to this
   * process's standard error. Returns once the shim holds the terminal, or is gone: its standard
   * output, a pipe from here, closes as the claim puts the terminal in its place or as the shim
   * exits, and only then does this process let go of the slave. A shim that has done neither within
   * {@link #CLAIM_WAIT} is stopped, and refused by the terminal it was given. Whatever fails,
   * nothing spawn opened outlives it. Once the child is gone, reads end after the drain grace. The
   * caller owns the returned process's ending: waiting and exit codes happen there, and it reads
   * the terminal meanwhile, since on macOS a session leader's exit waits until its terminal's
   * output has been read.
   */
  public Process spawn(List<String> shim, Map<String, String> env, Path cwd) throws IOException {
    requireOpen();
    var builder = new ProcessBuilder(shim);
    builder.environment().putAll(env);
    builder.environment().put(TERMINAL, slavePath);
    builder.directory(cwd.toFile());
    builder.redirectInput(new File("/dev/null"));
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    Process child = null;
    try {
      child = builder.start();
      awaitClaim(child);
      child.onExit().thenRun(this::endOnceDrained);
      return child;
    } catch (IOException | RuntimeException failed) {
      if (child != null) {
        stop(child);
      }
      throw failed;
    } finally {
      closeSlave();
    }
  }

  /** Waits, up to {@link #CLAIM_WAIT}, for the shim's standard output to close. */
  private void awaitClaim(Process shim) throws IOException {
    var said = shim.getInputStream();
    var drain =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (said) {
                    said.transferTo(OutputStream.nullOutputStream());
                  } catch (IOException gone) {
                    var unused = gone;
                  }
                });
    try {
      if (!drain.join(CLAIM_WAIT)) {
        throw new IOException(
            "The session shim did not claim "
                + slavePath
                + " within "
                + CLAIM_WAIT.toSeconds()
                + " s, so it was stopped.");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted waiting for the shim to claim " + slavePath);
    }
  }

  /**
   * Kills a shim that failed to claim, and everything it started, waits up to {@link #STOP_WAIT}
   * for all of them to be gone, and closes its pipe. A killed process the shim started is still
   * there until it is reaped, so waiting for the shim alone would return with one running.
   */
  private static void stop(Process shim) throws IOException {
    var started = Stream.concat(shim.descendants(), Stream.of(shim.toHandle())).toList();
    started.forEach(ProcessHandle::destroyForcibly);
    var gone =
        CompletableFuture.allOf(
            started.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new));
    try {
      gone.get(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException stillThere) {
      var unused = stillThere;
    }
    shim.getInputStream().close();
  }

  /**
   * Reads from the master into {@code buf}, blocking until output arrives. Returns the byte count,
   * or {@code -1} at end of stream: the child gone and the terminal drained, the slave gone, or
   * this pty closed.
   */
  public int read(byte[] buf) throws IOException {
    while (true) {
      if (closed) {
        return -1;
      }
      var poll = poll();
      if (poll == Poll.QUIET && childGone) {
        return -1;
      }
      if (poll != Poll.READY) {
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
   * error; the read that follows tells which. A timeout is {@link Poll#QUIET} and a signal {@link
   * Poll#INTERRUPTED}: look at {@link #closed} and come back.
   */
  private Poll poll() throws IOException {
    int rc;
    int err;
    try (var arena = Arena.ofConfined()) {
      var pollfd = arena.allocate(POLLFD);
      pollfd.set(ValueLayout.JAVA_INT, 0, masterFd);
      pollfd.set(ValueLayout.JAVA_SHORT, 4, POLLIN);
      var errno = arena.allocate(Native.ERRNO_STATE);
      try {
        rc = (int) LIBC.poll().invokeExact(errno, pollfd, 1, DRAIN_GRACE_MILLIS);
      } catch (Throwable t) {
        throw new IOException("pty poll failed", t);
      }
      err = Native.errno(errno);
    }
    if (rc > 0) {
      return Poll.READY;
    }
    if (rc == 0) {
      return Poll.QUIET;
    }
    if (err == EINTR) {
      return Poll.INTERRUPTED;
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
      Native.ioctl(
          masterFd, Native.PLATFORM.tiocswinsz(), ws, "TIOCSWINSZ failed on the pty master");
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

  /**
   * Its child is gone: once the master has said nothing for a poll interval, a read reports end of
   * stream. What the child left in the terminal is still read; a terminal it never opened, or one a
   * job of it still holds in silence, ends the read all the same.
   */
  public void endOnceDrained() {
    childGone = true;
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
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      var ignored = (int) LIBC.close().invokeExact(masterFd);
    } catch (Throwable t) {
      throw new IllegalStateException("pty close failed", t);
    }
    closeSlave();
  }

  /** Lets go of the slave this process opened, once: the child holds its own. */
  private synchronized void closeSlave() {
    if (slaveFd == NO_SLAVE) {
      return;
    }
    try {
      var ignored = (int) LIBC.close().invokeExact(slaveFd);
    } catch (Throwable t) {
      throw new IllegalStateException("pty slave close failed", t);
    } finally {
      slaveFd = NO_SLAVE;
    }
  }
}
