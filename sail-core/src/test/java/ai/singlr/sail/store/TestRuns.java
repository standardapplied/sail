/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

/**
 * Moves a run's row as only a test may: to a status with no usage read, the bookkeeping a test
 * seeds beside the state it checks. Every finisher in Sail records what its harness reported
 * ({@link RunStore#finish}); these are the store's own usage-blind writes, kept to its package.
 */
public final class TestRuns {

  private TestRuns() {}

  /** Finishes run {@code id} as {@code status} with {@code exitCode}, whatever it held. */
  public static void complete(RunStore runs, String id, String status, Integer exitCode) {
    runs.complete(id, status, exitCode);
  }

  /** As {@link #complete(RunStore, String, String, Integer)}, with {@code alongside} in one go. */
  public static void complete(
      RunStore runs, String id, String status, Integer exitCode, Runnable alongside) {
    runs.complete(id, status, exitCode, alongside);
  }

  /** Moves run {@code id} from {@code expected} to {@code status}; whether it moved. */
  public static boolean transition(RunStore runs, String id, String expected, String status) {
    return runs.transition(id, expected, status);
  }
}
