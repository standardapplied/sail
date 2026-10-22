/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.pty.PtySessionHost;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;

/**
 * The per-container session host process: binds the pty socket, sweeps on a timer, and runs until
 * terminated. Installed as a systemd user service; everything interesting lives in {@link
 * PtySessionHost} — this command is only its process shell.
 *
 * <p>On {@code SIGTERM} it asks systemd what is happening to it. A restart (an upgrade) hands every
 * session to the descriptor store for the next host to adopt; a stop for good — or a run outside
 * systemd, where there is no store — ends every session loudly instead.
 */
@Command(name = "_pty-host", description = "Internal pty session host.", hidden = true)
public final class PtyHostCommand implements Callable<Integer> {

  static final long JOURNAL_CAPACITY = 4L * 1024 * 1024;
  static final long SWEEP_INTERVAL_MILLIS = 30_000;
  static final String STOPPED_REASON = "pty host stopped";

  @Override
  public Integer call() throws Exception {
    var handoff = PtySessionHost.Handoff.fromEnvironment(PtyHostCommand::shimCommand);
    var host =
        startHost(
            SailPaths.ptySocketPath(),
            SailPaths.sessionsDir(),
            new PtyHostIdentity(),
            new PtyHostRooms(),
            new PtyHostEvents(),
            handoff);
    var done = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    if (handoff.store().enabled()
                        && !SystemdStop.stoppingForGood(new ShellExecutor(false))) {
                      var handed = host.handoff();
                      System.err.println(
                          "pty-host: handed off " + handed.size() + " session(s) to the store");
                    } else {
                      host.stop(STOPPED_REASON);
                    }
                  } finally {
                    done.countDown();
                  }
                }));
    done.await();
    return 0;
  }

  /**
   * How a session's child is wrapped: the installed binary's {@code _pty-child} in the native
   * image, or the same shim run from this class path when the host is a plain JVM (development, the
   * integration lanes) — {@code /proc/self/exe} is {@code java} there, not {@code sail}.
   */
  static List<String> shimCommand(Path exitFile) {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
      return List.of(
          SailPaths.binaryPath().toString(), "_pty-child", "--exit", exitFile.toString(), "--");
    }
    return List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        System.getProperty("java.class.path"),
        "ai.singlr.sail.pty.PtyChildShim",
        exitFile.toString());
  }

  /** Starts a host with no store — the seam the CLI verb tests use. */
  static PtySessionHost startHost(
      java.nio.file.Path socket,
      java.nio.file.Path sessions,
      ai.singlr.sail.pty.PtyIdentity.Resolver identity,
      ai.singlr.sail.pty.PtyRooms rooms,
      ai.singlr.sail.pty.PtyEvents events)
      throws java.io.IOException {
    return startHost(socket, sessions, identity, rooms, events, PtySessionHost.Handoff.NONE);
  }

  /** Starts the host and its sweep timer — the process shell around it is {@link #call()}. */
  static PtySessionHost startHost(
      java.nio.file.Path socket,
      java.nio.file.Path sessions,
      ai.singlr.sail.pty.PtyIdentity.Resolver identity,
      ai.singlr.sail.pty.PtyRooms rooms,
      ai.singlr.sail.pty.PtyEvents events,
      PtySessionHost.Handoff handoff)
      throws java.io.IOException {
    var host =
        new PtySessionHost(
            socket,
            sessions,
            JOURNAL_CAPACITY,
            identity,
            rooms,
            events,
            SailVersion.version(),
            handoff);
    host.start();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (true) {
                  Thread.sleep(SWEEP_INTERVAL_MILLIS);
                  host.sweep(System.nanoTime());
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    return host;
  }
}
