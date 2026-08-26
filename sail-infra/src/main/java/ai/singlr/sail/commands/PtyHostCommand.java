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
        new PtySessionHost(SailPaths.ptySocketPath(), SailPaths.sessionsDir(), JOURNAL_CAPACITY);
    host.start();
    var done = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  host.close();
                  done.countDown();
                }));
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
    done.await();
    return 0;
  }
}
