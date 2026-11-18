/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A clock that never moves on its own: it moves when a test says so ({@link #fire}), and what is
 * due by then runs, so a deadline is tested without waiting for it.
 */
final class ManualTimer implements PtyUploads.Timer {

  private record Due(Duration delay, Duration at, Runnable task, boolean[] cancelled) {}

  private final List<Due> due = new ArrayList<>();
  private Duration now = Duration.ZERO;

  @Override
  public synchronized Runnable after(Duration delay, Runnable task) {
    var cancelled = new boolean[1];
    due.add(new Due(delay, now.plus(delay), task, cancelled));
    notifyAll();
    return () -> {
      synchronized (this) {
        cancelled[0] = true;
      }
    };
  }

  /** Waits until a task {@code delay} long is set and not cancelled. */
  synchronized void awaitDue(Duration delay) throws InterruptedException {
    while (due.stream().noneMatch(d -> d.delay().equals(delay) && !d.cancelled()[0])) {
      wait();
    }
  }

  /** Moves the clock on by {@code by}, and runs every task due by then and not cancelled. */
  void fire(Duration by) {
    List<Due> ripe;
    synchronized (this) {
      now = now.plus(by);
      ripe = due.stream().filter(d -> !d.cancelled()[0] && d.at().compareTo(now) <= 0).toList();
      ripe.forEach(task -> task.cancelled()[0] = true);
    }
    ripe.forEach(task -> task.task().run());
  }
}
