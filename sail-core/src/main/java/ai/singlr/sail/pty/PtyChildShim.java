/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import sun.misc.Signal;

/**
 * The session leader between the pty and the command: claims the terminal {@link Pty#spawn} named,
 * runs {@code argv} on it, waits, records the exit status to a file and exits with the same status.
 * Only a parent can collect a child's status, and a host that inherited a session after a restart
 * is not the parent — the file is how the status crosses that gap. The shim is the session leader,
 * so a hangup on the master reaches it and it passes {@code SIGHUP} down as the kernel would have
 * done to the command in its place; {@code SIGTERM} is forwarded the same way; {@code SIGINT} is
 * left to the terminal, which already delivers it to the foreground group.
 */
public final class PtyChildShim {

  private static final int SIGHUP = 1;
  private static final int SIGINT = 2;
  private static final int SIGTERM = 15;

  private static final MethodHandle KILL =
      Native.downcall(
          "kill",
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle SIGNAL =
      Native.downcall(
          "signal",
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle SETSID =
      Native.downcall("setsid", FunctionDescriptor.of(ValueLayout.JAVA_INT), Native.CAPTURE_ERRNO);
  private static final MethodHandle DUP2 =
      Native.downcall(
          "dup2",
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
          Native.CAPTURE_ERRNO);
  private static final MethodHandle CLOSE =
      Native.downcall("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle ISATTY =
      Native.downcall("isatty", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

  private PtyChildShim() {}

  /**
   * {@code java -cp <this class path> PtyChildShim <exit file>}, the command {@code argv} follows:
   * the shim a host run from a class path spawns, as the native image's is its {@code _pty-child}.
   */
  public static List<String> onClassPath(Path exitFile) {
    return List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        System.getProperty("java.class.path"),
        PtyChildShim.class.getName(),
        exitFile.toString());
  }

  /** {@code <exit file> <argv...>}: the shim's own entry point, for hosts run from a class path. */
  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("usage: PtyChildShim <exit-file> <command> [args...]");
      System.exit(2);
    }
    System.exit(run(Path.of(args[0]), List.of(args).subList(1, args.length)));
  }

  /**
   * Claims the terminal the host named, or, when it named none, runs on the terminal already on
   * standard input: hosts started before 0.67.0 hand the shim its terminal already claimed.
   */
  public static int run(Path exitFile, List<String> argv) throws IOException {
    var terminal = System.getenv(Pty.TERMINAL);
    if (terminal != null) {
      claim(terminal);
    } else if (!onATerminal()) {
      throw new IllegalStateException(
          "No terminal is named in "
              + Pty.TERMINAL
              + ": the shim runs only as the pty host spawns it.");
    }
    var child = new CompletableFuture<Process>();
    restoreDefault(SIGHUP, SIGINT, SIGTERM);
    Signal.handle(new Signal("HUP"), signal -> child.thenAccept(p -> forward(p, SIGHUP)));
    Signal.handle(new Signal("TERM"), signal -> child.thenAccept(p -> forward(p, SIGTERM)));
    Signal.handle(new Signal("INT"), signal -> {});
    try {
      var command = new ProcessBuilder(argv).inheritIO();
      command.environment().remove(Pty.TERMINAL);
      child.complete(command.start());
    } catch (IOException | RuntimeException failed) {
      child.completeExceptionally(failed);
      throw failed;
    }
    var status = await(child.join());
    Files.writeString(exitFile, Integer.toString(status));
    return status;
  }

  /**
   * The child's half of {@link Pty#spawn}: makes this process the leader of a new session, claims
   * {@code terminal} as the session's controlling terminal, and puts it on standard input, output
   * and error for the command to inherit. The terminal is opened with {@code O_NOCTTY} and claimed
   * with {@code TIOCSCTTY}, the same two steps on every system, rather than left to whether an open
   * by a session leader claims it. The claim makes this process's group the terminal's foreground
   * group, which the command joins. Putting the terminal on standard output closes the pipe {@code
   * spawn} waits on.
   */
  static void claim(String terminal) throws IOException {
    try (var arena = Arena.ofConfined()) {
      var errno = arena.allocate(Native.ERRNO_STATE);
      if ((int) SETSID.invokeExact(errno) < 0) {
        throw new IOException(
            "setsid failed: the shim already leads a process group (errno "
                + Native.errno(errno)
                + ").");
      }
      var fd = Native.openTerminal(terminal);
      Native.ioctl(
          fd,
          Native.PLATFORM.tiocsctty(),
          MemorySegment.NULL,
          "TIOCSCTTY failed: " + terminal + " is not this session's to claim");
      for (var stdio = 0; stdio <= 2; stdio++) {
        if ((int) DUP2.invokeExact(errno, fd, stdio) < 0) {
          throw new IOException(
              "Could not put "
                  + terminal
                  + " on descriptor "
                  + stdio
                  + " (errno "
                  + Native.errno(errno)
                  + ").");
        }
      }
      if (fd > 2) {
        var unused = (int) CLOSE.invokeExact(fd);
      }
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("claiming the terminal " + terminal + " failed", t);
    }
  }

  private static boolean onATerminal() {
    try {
      return (int) ISATTY.invokeExact(0) == 1;
    } catch (Throwable t) {
      throw new IllegalStateException("isatty failed", t);
    }
  }

  private static int await(Process child) {
    while (true) {
      try {
        return child.waitFor();
      } catch (InterruptedException interrupted) {
        var unused = interrupted;
      }
    }
  }

  /**
   * A parent that ignored these (a {@code nohup}, a test runner) would pass the ignoring on: the
   * JVM declines to handle a signal that was ignored at start, and the command would inherit the
   * same indifference. Default dispositions first, then handlers.
   */
  private static void restoreDefault(int... signals) {
    try {
      for (var signal : signals) {
        var unused = (MemorySegment) SIGNAL.invokeExact(signal, MemorySegment.NULL);
      }
    } catch (Throwable t) {
      throw new IllegalStateException("signal reset failed", t);
    }
  }

  /**
   * Passes {@code signal} to the child. The handler is bound through a future because the child is
   * running before {@code start()} has returned its {@link Process}: a signal that lands in that
   * gap waits for the handle rather than being dropped on the floor.
   */
  private static void forward(Process child, int signal) {
    try {
      var unused = (int) KILL.invokeExact((int) child.pid(), signal);
    } catch (Throwable t) {
      throw new IllegalStateException("kill failed", t);
    }
  }
}
