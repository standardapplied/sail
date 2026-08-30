/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One subscriber's delivery queue with the flow-control contract built in: a bounded FIFO whose
 * overflow drops the backlog and enqueues {@code Paused}; while paused, every enqueue is dropped;
 * and the first {@link #next()} after the pause has drained returns {@code Continued} — resume is
 * the consumer's act of catching up, not a race against the producer's next output. Deterministic
 * by construction, so the contract is provable single-threaded.
 */
final class SubscriberQueue {

  private final int capacity;
  private final Deque<PtyMessage> queue = new ArrayDeque<>();
  private boolean paused;

  SubscriberQueue(int capacity) {
    this.capacity = capacity;
  }

  /** Offers a live message; a full queue trips the pause, a paused queue drops silently. */
  synchronized void enqueue(PtyMessage message) {
    if (paused) {
      return;
    }
    if (queue.size() >= capacity) {
      paused = true;
      queue.clear();
      queue.add(new PtyMessage.Paused());
    } else {
      queue.add(message);
    }
    notifyAll();
  }

  /** Enqueues regardless of pause — endings and poison must always arrive. */
  synchronized void force(PtyMessage message) {
    queue.add(message);
    notifyAll();
  }

  /**
   * Replaces the pending backlog with {@code messages} — the resync-after-pause path, where the
   * backlog's bytes are already covered by the journal snapshot being installed. Terminal messages
   * ({@code Ok}, {@code SessionEnded}) survive the swap, re-queued after the replacement: a detach
   * or an ending must never be lost to a resync racing it.
   */
  synchronized void replaceWith(java.util.List<PtyMessage> messages) {
    var terminal =
        queue.stream()
            .filter(m -> m instanceof PtyMessage.Ok || m instanceof PtyMessage.SessionEnded)
            .toList();
    queue.clear();
    queue.addAll(messages);
    queue.addAll(terminal);
    notifyAll();
  }

  /** Empties the queue and delivers only {@code message} next — the detach path. */
  synchronized void clearAnd(PtyMessage message) {
    queue.clear();
    paused = false;
    queue.add(message);
    notifyAll();
  }

  /**
   * The next message to deliver, blocking until one exists. After a drained pause this returns
   * {@code Continued} exactly once, before any further live traffic.
   */
  synchronized PtyMessage next() throws InterruptedException {
    while (queue.isEmpty()) {
      if (paused) {
        paused = false;
        return new PtyMessage.Continued();
      }
      wait();
    }
    return queue.poll();
  }
}
