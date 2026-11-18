/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A clock that never moves on its own: what is due runs when a test says the time has come ({@link
 * #fire}), so a deadline is tested without waiting for it.
 */
final class ManualTimer implements PtyUploads.Timer {

  private record Due(Duration delay, Runnable task, boolean[] cancelled) {}

  private final List<Due> due = new ArrayList<>();

  @Override
  public synchronized Runnable after(Duration delay, Runnable task) {
    var cancelled = new boolean[1];
    due.add(new Due(delay, task, cancelled));
    notifyAll();
    return () -> {
      synchronized (this) {
        cancelled[0] = true;
      }
    };
  }

  /** Waits until a task {@code delay} long is due and not cancelled. */
  synchronized void awaitDue(Duration delay) throws InterruptedException {
    while (pending(delay).isEmpty()) {
      wait();
    }
  }

  /** Runs every task {@code delay} long that is due and not cancelled, as that time passes. */
  void fire(Duration delay) {
    List<Due> now;
    synchronized (this) {
      now = pending(delay);
      now.forEach(task -> task.cancelled()[0] = true);
    }
    now.forEach(task -> task.task().run());
  }

  private List<Due> pending(Duration delay) {
    return due.stream().filter(d -> d.delay().equals(delay) && !d.cancelled()[0]).toList();
  }
}
