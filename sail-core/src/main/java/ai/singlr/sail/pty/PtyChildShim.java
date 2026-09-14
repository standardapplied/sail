/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
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
 * The session leader between the pty and the command: runs {@code argv} on the inherited terminal,
 * waits, records the exit status to a file and exits with the same status. Only a parent can
 * collect a child's status, and a host that inherited a session after a restart is not the parent —
 * the file is how the status crosses that gap. The shim is the session leader, so a hangup on the
 * master reaches it and it passes {@code SIGHUP} down as the kernel would have done to the command
 * in its place; {@code SIGTERM} is forwarded the same way; {@code SIGINT} is left to the terminal,
 * which already delivers it to the foreground group.
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

  private PtyChildShim() {}

  /** {@code <exit file> <argv...>}: the shim's own entry point, for hosts run from a class path. */
  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("usage: PtyChildShim <exit-file> <command> [args...]");
      System.exit(2);
    }
    System.exit(run(Path.of(args[0]), List.of(args).subList(1, args.length)));
  }

  public static int run(Path exitFile, List<String> argv) throws IOException {
    var child = new CompletableFuture<Process>();
    restoreDefault(SIGHUP, SIGINT, SIGTERM);
    Signal.handle(new Signal("HUP"), signal -> child.thenAccept(p -> forward(p, SIGHUP)));
    Signal.handle(new Signal("TERM"), signal -> child.thenAccept(p -> forward(p, SIGTERM)));
    Signal.handle(new Signal("INT"), signal -> {});
    try {
      child.complete(new ProcessBuilder(argv).inheritIO().start());
    } catch (IOException | RuntimeException failed) {
      child.completeExceptionally(failed);
      throw failed;
    }
    var status = await(child.join());
    Files.writeString(exitFile, Integer.toString(status));
    return status;
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
