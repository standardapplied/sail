/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtySessionHost;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;

/**
 * The per-container session host process: binds the pty socket, sweeps on a timer, and runs until
 * terminated. Installed as a systemd user service; everything interesting lives in {@link
 * PtySessionHost} — this command is only its process shell.
 */
@Command(name = "_pty-host", description = "Internal pty session host.", hidden = true)
public final class PtyHostCommand implements Callable<Integer> {

  static final long JOURNAL_CAPACITY = 4L * 1024 * 1024;
  static final long SWEEP_INTERVAL_MILLIS = 30_000;

  @Override
  public Integer call() throws Exception {
    var host =
        startHost(
            SailPaths.ptySocketPath(),
            SailPaths.sessionsDir(),
            new PtyHostIdentity(),
            new PtyHostRooms(),
            new PtyHostEvents());
    var done = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  host.close();
                  done.countDown();
                }));
    done.await();
    return 0;
  }

  /** Starts the host and its sweep timer — the process shell around it is {@link #call()}. */
  static PtySessionHost startHost(
      java.nio.file.Path socket,
      java.nio.file.Path sessions,
      ai.singlr.sail.pty.PtyIdentity.Resolver identity,
      ai.singlr.sail.pty.PtyRooms rooms,
      ai.singlr.sail.pty.PtyEvents events)
      throws java.io.IOException {
    var host = new PtySessionHost(socket, sessions, JOURNAL_CAPACITY, identity, rooms, events);
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
