/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  /**
   * What a session was born as: its name, the FDE who created it, the project whose container it
   * runs in (blank for the node itself), the room it is pinned to (blank for none), and the child
   * as requested — the facts every listing and every emitted event carry.
   */
  public record Origin(
      String name, String ownerFde, String project, String room, List<String> command) {
    public Origin {
      command = List.copyOf(command);
    }

    public boolean roomBound() {
      return room != null && !room.isBlank();
    }
  }

  private final Origin origin;
  private final PtyEvents events;
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
  private volatile String writerFde = "";
  private volatile String endedReason;
  private volatile String yieldedReason;
  private volatile long endedAtNanos;
  private volatile boolean everAttached;

  private PtySession(
      Origin origin,
      PtyEvents events,
      Pty pty,
      Process child,
      RingJournal journal,
      int queueCapacity,
      int replayMax) {
    this.origin = origin;
    this.events = events;
    this.pty = pty;
    this.child = child;
    this.journal = journal;
    this.queueCapacity = queueCapacity;
    this.replayMax = replayMax;
  }

  /**
   * Spawns {@code argv} — the process actually executed, which may wrap {@code origin.command()} in
   * a container exec lane — and starts gathering its output.
   */
  public static PtySession start(
      Origin origin,
      PtyEvents events,
      List<String> argv,
      Map<String, String> env,
      Path cwd,
      Path journalPath,
      long journalCapacity,
      int cols,
      int rows)
      throws IOException {
    return start(
        origin, events, argv, env, cwd, journalPath, journalCapacity, cols, rows, 4096, 262_144);
  }

  static PtySession start(
      Origin origin,
      PtyEvents events,
      List<String> argv,
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
      child = pty.spawn(argv, env, cwd);
    } catch (IOException e) {
      pty.close();
      journal.close();
      throw e;
    }
    var session = new PtySession(origin, events, pty, child, journal, queueCapacity, replayMax);
    Thread.ofPlatform().name("pty-gather-" + origin.name()).start(session::gather);
    emitQuietly(() -> events.sessionStarted(origin));
    return session;
  }

  /**
   * Runs one event emission, swallowing anything it throws: the session facts are observational, so
   * no {@link PtyEvents} failure may end, stall, or refuse the session it describes.
   */
  private static void emitQuietly(Runnable emission) {
    try {
      emission.run();
    } catch (RuntimeException ignored) {
      var unused = ignored;
    }
  }

  private void gather() {
    var buf = new byte[65536];
    String failure = null;
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
      failure = "pty failed: " + e.getMessage();
    } finally {
      try {
        var exit = "exited(" + child.onExit().join().exitValue() + ")";
        var reason = failure != null ? failure : Objects.requireNonNullElse(yieldedReason, exit);
        pty.close();
        synchronized (fanout) {
          endedAtNanos = System.nanoTime();
          endedReason = reason;
          var ended = new PtyMessage.SessionEnded(reason);
          subscribers.values().forEach(subscriber -> subscriber.queue().force(ended));
        }
        emitQuietly(() -> events.sessionEnded(origin, reason));
      } finally {
        gatherDone.countDown();
      }
    }
  }

  /**
   * Re-baselines a subscriber whose pause dropped part of the stream: under the fanout lock, the
   * journal tail replaces whatever accumulated in its queue (those bytes are inside the snapshot),
   * so the client hears {@code Continued}, a bracketed replay, then live traffic — exactly-once
   * against the journal, never a screen with its middle missing.
   */
  private void resync(Subscriber subscriber) {
    synchronized (fanout) {
      try {
        var tail = journal.tail(replayMax);
        var messages = new java.util.ArrayList<PtyMessage>(3);
        messages.add(new PtyMessage.ReplayBegin(tail.safe()));
        if (tail.bytes().length > 0) {
          messages.add(new PtyMessage.Output(lastInputSeq.get(), tail.bytes()));
        }
        messages.add(new PtyMessage.ReplayEnd());
        subscriber.queue().replaceWith(List.copyOf(messages));
      } catch (IOException e) {
        subscriber.queue().force(new PtyMessage.SessionEnded("resync failed: " + e.getMessage()));
      }
    }
  }

  /** Attaches a client: replay first, live stream after, write token if free and requested. */
  public long attach(Client client, boolean wantsWrite, String fde) throws IOException {
    if (endedReason != null) {
      throw new IOException("Session '" + name() + "' has ended: " + endedReason + ".");
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
      if (endedReason != null) {
        subscriber.queue().force(new PtyMessage.SessionEnded(endedReason));
      }
    }
    synchronized (this) {
      if (wantsWrite && writerId < 0) {
        writerId = subscriber.id;
        writerFde = fde;
      }
    }
    emitQuietly(() -> events.sessionAttached(origin, fde));
    Thread.ofVirtual()
        .name("pty-send-" + name() + "-" + subscriber.id())
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
        if (message instanceof PtyMessage.Continued) {
          resync(subscriber);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Writes input on behalf of {@code subscriberId}; only the write-token holder may. Returns {@code
   * false} — never throws — when the caller does not hold the token, so a non-writer's stray input
   * is a refusable outcome, not a fatal error that tears down its connection. An {@link
   * IOException} means the pty write itself failed. The sequence rides future output.
   */
  public boolean input(long subscriberId, long seq, byte[] bytes) throws IOException {
    if (subscriberId != writerId) {
      return false;
    }
    lastInputSeq.set(seq);
    pty.write(bytes);
    return true;
  }

  /** Transfers the write token to {@code subscriberId}; everyone hears about it. */
  public synchronized void takeWrite(long subscriberId, String fde) {
    if (!subscribers.containsKey(subscriberId)) {
      throw new IllegalArgumentException("No attached subscriber " + subscriberId + ".");
    }
    writerId = subscriberId;
    writerFde = fde;
    var changed = new PtyMessage.WriterChanged(fde);
    subscribers.values().forEach(subscriber -> subscriber.queue().force(changed));
  }

  /**
   * Resizes on behalf of {@code subscriberId}; only the write-token holder may, and observers hear
   * the new geometry. Returns {@code false} — never throws — for a non-writer, so an observer's own
   * window change is a silent no-op rather than a fatal error: the writer's window wins.
   */
  public boolean resize(long subscriberId, int cols, int rows) throws IOException {
    if (subscriberId != writerId) {
      return false;
    }
    pty.resize(cols, rows);
    var resized = new PtyMessage.Resized(cols, rows);
    subscribers.values().stream()
        .filter(subscriber -> subscriber.id() != subscriberId)
        .forEach(subscriber -> subscriber.queue().force(resized));
    return true;
  }

  public synchronized void detach(long subscriberId) {
    var subscriber = subscribers.remove(subscriberId);
    if (subscriber != null) {
      subscriber.queue().clearAnd(new PtyMessage.Ok());
    }
    if (writerId == subscriberId) {
      writerId = -1;
      writerFde = "";
    }
  }

  /** Bytes gathered from the pty so far — the child's progress, independent of any subscriber. */
  public long journaledBytes() {
    return journal.totalWritten();
  }

  public Origin origin() {
    return origin;
  }

  public String name() {
    return origin.name();
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

  public String ownerFde() {
    return origin.ownerFde();
  }

  public String project() {
    return origin.project();
  }

  public String writerFde() {
    return writerFde;
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

  /**
   * Ends the session because something displaced it: every attached client first sees {@code
   * reason} as a terminal line in the stream, then the session ends and reports that reason — not
   * the child's exit status — to its subscribers and its ended event. A session that already ended
   * keeps its own reason.
   */
  public void end(String reason) {
    synchronized (fanout) {
      if (endedReason == null && yieldedReason == null) {
        yieldedReason = reason;
        var notice =
            ("\r\n[sail: session ended \u2014 " + reason + "]\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var output = new PtyMessage.Output(lastInputSeq.get(), notice);
        subscribers.values().forEach(subscriber -> subscriber.queue().force(output));
      }
    }
    close();
  }

  /**
   * Ends the child (if alive), waits for the gather thread, and releases the journal. By the time
   * the gather thread is done it has forced {@code SessionEnded} into every subscriber's queue, so
   * the subscribers are dropped rather than poisoned: their sender threads end on delivering the
   * ending, and a detach's queue-clearing poison would race that delivery and lose it.
   */
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
    synchronized (this) {
      subscribers.clear();
      writerId = -1;
      writerFde = "";
    }
    try {
      journal.close();
    } catch (IOException e) {
      throw new IllegalStateException("journal close failed for session '" + name() + "'", e);
    }
  }
}
