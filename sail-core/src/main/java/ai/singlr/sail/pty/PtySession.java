/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One live conversation: a {@link Pty} and its child, a gather thread that drains the pty
 * unconditionally into the {@link RingJournal} (the child is never backpressured — the ghostty
 * discipline), and any number of subscribers, each served by its own sender thread over a bounded
 * queue. Attach (journal snapshot + subscription) and the gather fanout (append + delivery)
 * serialize on one lock so no byte falls between a subscriber's replay and its live stream; the
 * blocking pty read stays outside the lock. A subscriber that cannot keep up is paused — its
 * backlog dropped, marked by {@code Paused}/{@code Continued} frames — while the writer and
 * everyone else stay live.
 *
 * <p>Exactly one subscriber holds the write token; input from anyone else is refused. Attach
 * replays the journal tail bracketed by {@code ReplayBegin}/{@code ReplayEnd}, then streams. When
 * the child exits, every subscriber hears {@code SessionEnded} and the corpse (ring included) stays
 * readable until the host reaps it.
 */
public final class PtySession implements AutoCloseable {

  /** A connected client; {@code deliver} is called from this subscriber's own sender thread. */
  public interface Client {
    void deliver(PtyMessage message);
  }

  private record Subscriber(long id, Client client, SubscriberQueue queue) {}

  private final String name;
  private final Pty pty;
  private final Process child;
  private final RingJournal journal;
  private final TermBoundary boundary = new TermBoundary();
  private final int queueCapacity;
  private final int replayMax;
  private final Map<Long, Subscriber> subscribers = new ConcurrentHashMap<>();
  private final Object fanout = new Object();
  private final AtomicLong subscriberIds = new AtomicLong();
  private final AtomicLong lastInputSeq = new AtomicLong(-1);
  private final CountDownLatch gatherDone = new CountDownLatch(1);
  private final long createdAt = System.nanoTime();
  private volatile long writerId = -1;
  private volatile String endedReason;
  private volatile long endedAtNanos;
  private volatile boolean everAttached;

  private PtySession(
      String name, Pty pty, Process child, RingJournal journal, int queueCapacity, int replayMax) {
    this.name = name;
    this.pty = pty;
    this.child = child;
    this.journal = journal;
    this.queueCapacity = queueCapacity;
    this.replayMax = replayMax;
  }

  public static PtySession start(
      String name,
      List<String> command,
      Map<String, String> env,
      Path cwd,
      Path journalPath,
      long journalCapacity,
      int cols,
      int rows)
      throws IOException {
    return start(name, command, env, cwd, journalPath, journalCapacity, cols, rows, 4096, 262_144);
  }

  static PtySession start(
      String name,
      List<String> command,
      Map<String, String> env,
      Path cwd,
      Path journalPath,
      long journalCapacity,
      int cols,
      int rows,
      int queueCapacity,
      int replayMax)
      throws IOException {
    var journal = RingJournal.open(journalPath, journalCapacity);
    var pty = Pty.open(cols, rows);
    Process child;
    try {
      child = pty.spawn(command, env, cwd);
    } catch (IOException e) {
      pty.close();
      journal.close();
      throw e;
    }
    var session = new PtySession(name, pty, child, journal, queueCapacity, replayMax);
    Thread.ofPlatform().name("pty-gather-" + name).start(session::gather);
    return session;
  }

  private void gather() {
    var buf = new byte[65536];
    try {
      while (true) {
        var n = pty.read(buf);
        if (n < 0) {
          break;
        }
        synchronized (fanout) {
          journal.append(buf, n);
          boundary.feed(buf, n);
          if (boundary.atSafeLineStart()) {
            journal.markSafe(Math.max(0, journal.totalWritten() - replayMax));
          }
          var chunk = new byte[n];
          System.arraycopy(buf, 0, chunk, 0, n);
          var output = new PtyMessage.Output(lastInputSeq.get(), chunk);
          subscribers.values().forEach(subscriber -> subscriber.queue().enqueue(output));
        }
      }
    } catch (IOException e) {
      endedReason = "pty failed: " + e.getMessage();
    } finally {
      var exit = child.onExit().join().exitValue();
      if (endedReason == null) {
        endedReason = "exited(" + exit + ")";
      }
      endedAtNanos = System.nanoTime();
      pty.close();
      var ended = new PtyMessage.SessionEnded(endedReason);
      subscribers.values().forEach(subscriber -> subscriber.queue().force(ended));
      gatherDone.countDown();
    }
  }

  /** Attaches a client: replay first, live stream after, write token if free and requested. */
  public long attach(Client client, boolean wantsWrite) throws IOException {
    if (endedReason != null) {
      throw new IOException("Session '" + name + "' has ended: " + endedReason + ".");
    }
    everAttached = true;
    var subscriber =
        new Subscriber(subscriberIds.incrementAndGet(), client, new SubscriberQueue(queueCapacity));
    synchronized (fanout) {
      var tail = journal.tail(replayMax);
      subscriber.queue().force(new PtyMessage.ReplayBegin(tail.safe()));
      if (tail.bytes().length > 0) {
        subscriber.queue().force(new PtyMessage.Output(lastInputSeq.get(), tail.bytes()));
      }
      subscriber.queue().force(new PtyMessage.ReplayEnd());
      subscribers.put(subscriber.id, subscriber);
    }
    synchronized (this) {
      if (wantsWrite && writerId < 0) {
        writerId = subscriber.id;
      }
    }
    Thread.ofVirtual()
        .name("pty-send-" + name + "-" + subscriber.id())
        .start(() -> send(subscriber));
    return subscriber.id();
  }

  private void send(Subscriber subscriber) {
    try {
      while (true) {
        var message = subscriber.queue().next();
        if (message instanceof PtyMessage.Ok) {
          return;
        }
        subscriber.client().deliver(message);
        if (message instanceof PtyMessage.SessionEnded) {
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Writes input; only the write-token holder may, and the sequence rides future output. */
  public void input(long subscriberId, long seq, byte[] bytes) throws IOException {
    if (subscriberId != writerId) {
      throw new IOException("Subscriber " + subscriberId + " does not hold the write token.");
    }
    lastInputSeq.set(seq);
    pty.write(bytes);
  }

  /** Transfers the write token to {@code subscriberId}; everyone hears about it. */
  public synchronized void takeWrite(long subscriberId, String fde) {
    if (!subscribers.containsKey(subscriberId)) {
      throw new IllegalArgumentException("No attached subscriber " + subscriberId + ".");
    }
    writerId = subscriberId;
    var changed = new PtyMessage.WriterChanged(fde);
    subscribers.values().forEach(subscriber -> subscriber.queue().force(changed));
  }

  /** Resizes; only the write-token holder may, and observers hear the new geometry. */
  public void resize(long subscriberId, int cols, int rows) throws IOException {
    if (subscriberId != writerId) {
      throw new IOException("Only the writer resizes; the writer's window wins.");
    }
    pty.resize(cols, rows);
    var resized = new PtyMessage.Resized(cols, rows);
    subscribers.values().stream()
        .filter(subscriber -> subscriber.id() != subscriberId)
        .forEach(subscriber -> subscriber.queue().force(resized));
  }

  public synchronized void detach(long subscriberId) {
    var subscriber = subscribers.remove(subscriberId);
    if (subscriber != null) {
      subscriber.queue().clearAnd(new PtyMessage.Ok());
    }
    if (writerId == subscriberId) {
      writerId = -1;
    }
  }

  /** Bytes gathered from the pty so far — the child's progress, independent of any subscriber. */
  public long journaledBytes() {
    return journal.totalWritten();
  }

  public String name() {
    return name;
  }

  public boolean live() {
    return endedReason == null;
  }

  public String endedReason() {
    return endedReason;
  }

  public int attachedCount() {
    return subscribers.size();
  }

  public long writerId() {
    return writerId;
  }

  public boolean everAttached() {
    return everAttached;
  }

  public long endedAtNanos() {
    return endedAtNanos;
  }

  public long createdAtNanos() {
    return createdAt;
  }

  /** Ends the child (if alive), waits for the gather thread, and releases the journal. */
  @Override
  public void close() {
    child.destroy();
    try {
      if (!gatherDone.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
        child.destroyForcibly();
        gatherDone.await();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    subscribers.keySet().forEach(this::detach);
    try {
      journal.close();
    } catch (IOException e) {
      throw new IllegalStateException("journal close failed for session '" + name + "'", e);
    }
  }
}
