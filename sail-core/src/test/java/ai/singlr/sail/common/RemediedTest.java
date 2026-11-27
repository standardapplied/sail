/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The one line a failure and its causes are told in, wherever sail says a failure. */
class RemediedTest {

  private static String told(Throwable failure) {
    return Remedied.told(failure, Remedy::plain);
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aLineWalksEveryWrapperAndSaysNothingTheLineAlreadySaid() {
    assertEquals(
        "Reading host.yaml failed: Permission denied: /etc/sail/host.yaml",
        told(
            new UncheckedIOException(
                "Reading host.yaml failed.",
                new IOException("Permission denied", new IOException("/etc/sail/host.yaml")))));
    assertEquals(
        "Failed to open database /x: disk I/O error",
        told(
            new IllegalStateException(
                "Failed to open database /x: disk I/O error",
                new IllegalStateException("disk I/O error", new IOException("disk I/O error")))),
        "a cause the line already says adds nothing, at any depth");
    assertEquals(
        "A sail system command failed: Cannot run program incus",
        told(
            new IllegalStateException(
                "A sail system command failed.",
                new IOException("A sail system command failed: Cannot run program incus"))),
        "a cause that repeats its wrapper and says more is said once");
    var first = new IllegalStateException("first");
    var second = new IllegalStateException("second");
    first.initCause(second);
    second.initCause(first);
    assertEquals("first: second", told(first), "a cycle of causes is said once");
  }

  @Test
  void aLineEndsAtTheFirstFixAndNothingIsGluedAfterIt() {
    assertEquals(
        "Dispatch failed: The roster is unsynced. Run sail sync.",
        told(
            new IllegalStateException(
                "Dispatch failed",
                Remedied.state(
                    "The roster is unsynced.",
                    Remedy.of("Run ", Remedy.sail("sync"), "."),
                    new IOException("unseen")))));
    assertEquals(
        "The SQLite library x could not be loaded (no such file). On Ubuntu: sudo apt-get install"
            + " -y libsqlite3-0",
        told(
            Remedied.state(
                "The SQLite library x could not be loaded (no such file).",
                Remedy.of("On Ubuntu: sudo apt-get install -y libsqlite3-0"),
                new IllegalArgumentException("no such file"))),
        "a failure that names its own fix ends the line at it");
  }

  @Test
  void aCauseWithNoWordsAddsOnlyATypeTheLineDoesNotAlreadySay() {
    assertEquals(
        "Interrupted while removing ada's key from this box's containers.",
        told(
            new IllegalStateException(
                "Interrupted while removing ada's key from this box's containers.",
                new InterruptedException())));
    assertEquals(
        "Reading the roster failed: InterruptedException",
        told(new IllegalStateException("Reading the roster failed", new InterruptedException())));
    assertEquals(
        "Reading the roster failed: IOException",
        told(new IllegalStateException("Reading the roster failed", new IOException("  "))),
        "blank words are no words");
    assertEquals("IllegalStateException", told(new IllegalStateException()));
  }
}
