/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.RunStatus;

/**
 * Moves a run's row through the store's production writes, leaving its report unread: the
 * bookkeeping a test seeds beside the state it checks.
 */
public final class TestRuns {

  private TestRuns() {}

  /** Finishes run {@code id} as {@code status} with {@code exitCode}, from whatever it held. */
  public static void complete(RunStore runs, String id, String status, Integer exitCode) {
    var held = runs.findById(id).orElseThrow().status();
    if (!runs.finish(id, held, status, exitCode, null)) {
      throw new IllegalStateException("Run " + id + " did not finish as " + status + ".");
    }
  }

  /**
   * Moves run {@code id} from {@code expected} to {@code status}, as a finish or, to {@code
   * stopping}, as an operator's stop claim; whether it moved.
   */
  public static boolean transition(RunStore runs, String id, String expected, String status) {
    if (RunStatus.isTerminal(status)) {
      return runs.finish(id, expected, status, null, null);
    }
    if (!"running".equals(expected) || !"stopping".equals(status)) {
      throw new IllegalArgumentException("No production write moves " + expected + " → " + status);
    }
    return runs.claimStop(id, status, () -> {});
  }
}
