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
 *
 * <p>The backlog is bounded two ways at once, so neither a flood of tiny frames nor a few huge ones
 * can pin heap: by count ({@code capacity} live messages) and by bytes ({@code maxLiveBytes} of
 * live payload). Whichever trips first pauses the subscriber. Only live output counts; a forced
 * ending or an installed resync may exceed either cap without tripping a second pause behind the
 * first.
 */
final class SubscriberQueue {

  static final int DEFAULT_MAX_LIVE_BYTES = 1 << 20;

  /** One queued message; {@code live} marks stream output that counts toward the backlog caps. */
  private record Entry(PtyMessage message, boolean live, int bytes) {}

  private final int capacity;
  private final int maxLiveBytes;
  private final Deque<Entry> queue = new ArrayDeque<>();
  private int live;
  private long liveBytes;
  private boolean paused;

  SubscriberQueue(int capacity) {
    this(capacity, DEFAULT_MAX_LIVE_BYTES);
  }

  SubscriberQueue(int capacity, int maxLiveBytes) {
    this.capacity = capacity;
    this.maxLiveBytes = maxLiveBytes;
  }

  private static int weightOf(PtyMessage message) {
    return message instanceof PtyMessage.Output(var seq, var bytes) ? bytes.length : 0;
  }

  /**
   * Offers a live message; a full backlog of live messages or bytes trips the pause, a paused queue
   * drops silently. Only live messages count: a replay installed with {@link #replaceWith} or a
   * forced message may exceed a cap without tripping a second pause behind the first (which would
   * resync again, and again).
   */
  synchronized void enqueue(PtyMessage message) {
    if (paused) {
      return;
    }
    var weight = weightOf(message);
    if (live >= capacity || liveBytes + weight > maxLiveBytes) {
      paused = true;
      queue.clear();
      live = 0;
      liveBytes = 0;
      queue.add(new Entry(new PtyMessage.Paused(), false, 0));
    } else {
      queue.add(new Entry(message, true, weight));
      live++;
      liveBytes += weight;
    }
    notifyAll();
  }

  /** Enqueues regardless of pause — endings and poison must always arrive. */
  synchronized void force(PtyMessage message) {
    queue.add(new Entry(message, false, 0));
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
            .map(Entry::message)
            .filter(m -> m instanceof PtyMessage.Ok || m instanceof PtyMessage.SessionEnded)
            .toList();
    queue.clear();
    live = 0;
    liveBytes = 0;
    messages.forEach(m -> queue.add(new Entry(m, false, 0)));
    terminal.forEach(m -> queue.add(new Entry(m, false, 0)));
    notifyAll();
  }

  /** Empties the queue and delivers only {@code message} next — the detach path. */
  synchronized void clearAnd(PtyMessage message) {
    queue.clear();
    live = 0;
    liveBytes = 0;
    paused = false;
    queue.add(new Entry(message, false, 0));
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
    var entry = queue.poll();
    if (entry.live()) {
      live--;
      liveBytes -= entry.bytes();
    }
    return entry.message();
  }
}
