/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Every read Sail makes of zpool, each given {@link #BOUND} to answer: zpool can block on a pool
 * whose devices stopped answering, as a suspended pool's do, and no read of it waits longer.
 */
final class Zpool {

  /** How long any one read of zpool is given to answer. */
  static final Duration BOUND = Duration.ofSeconds(10);

  /** What is said of a zpool that did not answer within {@link #BOUND}. */
  static final String NO_ANSWER = "did not answer within " + BOUND.toSeconds() + " s";

  private Zpool() {}

  /** What zpool answers to {@code command} within {@link #BOUND}. */
  static ShellExec.Result read(ShellExec shell, List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(command, null, BOUND);
  }
}
