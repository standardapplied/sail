/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
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
 * <p>Exactly one subscriber holds the write token; input from anyone else is refused. Input never
 * blocks the connection that sends it: the write-token holder's keystrokes are offered onto a queue
 * bounded in frames and bytes, drained by a dedicated platform writer thread (the mirror of the
 * gather thread), so a child that has stopped reading (Ctrl-S, a stopped job) backs up the queue —
 * answered {@code BACKLOG} — rather than pinning the caller's virtual thread inside a blocking
 * foreign write or growing the host heap. Attach answers with the pty's live geometry ({@code
 * Resized}), the journal tail bracketed by {@code ReplayBegin}/{@code ReplayEnd}, then who holds
 * the write token ({@code WriterChanged}) — so the replay is parsed in the geometry that produced
 * it and an observer knows it is one — and streams from there. When the child exits — or a pty or
 * journal failure ends it — every subscriber hears {@code SessionEnded} and the corpse stays
 * readable until the host reaps it.
 *
 * <p>A session outlives its host. At creation the master is pushed into systemd's descriptor store
 * ({@link SdNotify}) and a {@link SessionMeta} sidecar is kept current beside the ring; a host that
 * is being replaced {@link #handoff() hands off} — stops reading without closing anything — and its
 * successor {@link #resume resumes} from the inherited master, the ring and the sidecar. The child
 * never notices: bytes it writes meanwhile wait in the kernel. Its exit status crosses the gap
 * through the exit file its {@link PtyChildShim} records, since only a parent can collect it.
 */
public final class PtySession implements AutoCloseable {

  /** A connected client; {@code deliver} is called from this subscriber's own sender thread. */
  public interface Client {
    void deliver(PtyMessage message);
  }

  /**
   * The outcome of offering input: taken onto the writer queue, refused, or refused for backlog.
   */
  public enum WriteOutcome {
    ACCEPTED,
    NOT_WRITER,
    BACKLOG
  }

  private record Subscriber(long id, Client client, SubscriberQueue queue) {}

  private record Keystroke(long seq, byte[] bytes) {}

  /** What was lost when no exit file survives a handoff: the reason names it, never a silent 0. */
  public static final String STATUS_LOST = "exited (status lost across a host handoff)";

  /**
   * The child as this host can reach it: the {@link Process} it spawned, or — after a handoff — an
   * orphan it only knows by pid, whose status is read from the exit file its shim wrote.
   */
  private sealed interface Child {
    long pid();

    void destroy();

    void destroyForcibly();

    /** Waits for the child to be gone and names how it ended. */
    String exitReason();
  }

  private record Spawned(Process process) implements Child {
    @Override
    public long pid() {
      return process.pid();
    }

    @Override
    public void destroy() {
      process.destroy();
    }

    @Override
    public void destroyForcibly() {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }

    @Override
    public String exitReason() {
      return "exited(" + process.onExit().join().exitValue() + ")";
    }
  }

  private record Adopted(long pid, Optional<ProcessHandle> handle, Path exitFile) implements Child {
    @Override
    public void destroy() {
      handle.ifPresent(ProcessHandle::destroy);
    }

    @Override
    public void destroyForcibly() {
      handle.ifPresent(
          h -> {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
          });
    }

    @Override
    public String exitReason() {
      handle.ifPresent(h -> h.onExit().join());
      try {
        return "exited(" + Integer.parseInt(Files.readString(exitFile).strip()) + ")";
      } catch (IOException | NumberFormatException lost) {
        return STATUS_LOST;
      }
    }
  }

  /**
   * What a session was born as: its name, the id of this incarnation of that name, the FDE who
   * created it, the project whose container it runs in (blank for the node itself), the room it is
   * pinned to (blank for none), and the child as requested — the facts every listing and every
   * emitted event carry. Names are reusable (a corpse's name may be created again); {@code
   * instanceId} is minted once per create and never reused, so a client that only ever saw two
   * corpses of one name can still tell which life each belonged to.
   */
  public record Origin(
      String name,
      String instanceId,
      String ownerFde,
      String project,
      String room,
      List<String> command) {
    public Origin {
      if (instanceId == null || instanceId.isBlank()) {
        throw new IllegalArgumentException("A session incarnation needs a non-blank instance id.");
      }
      command = List.copyOf(command);
    }

    public boolean roomBound() {
      return room != null && !room.isBlank();
    }
  }

  private final Origin origin;
  private final PtyEvents events;
  private final Pty pty;
  private final Child child;
  private final Journal journal;
  private final SessionFiles files;
  private final SdNotify notify;
  private final String cwd;
  private final String hostVersion;
  private final long createdAtMillis;
  private final TermBoundary boundary = new TermBoundary();

  /**
   * Whether {@link #boundary} reflects the stream exactly. Always for a session born here; for a
   * resumed one only if its state could be rebuilt from retained history that starts at a recorded
   * safe boundary (or at the stream's start). Otherwise the ring may end inside an escape sequence
   * a fresh parser would mistake for text, so no new safe checkpoints are recorded — every later
   * replay is best-effort, never falsely safe.
   */
  private boolean boundaryKnown = true;

  private final int queueCapacity;

  /** A replayed journal crosses the wire in frames this large — well under the 1 MiB frame cap. */
  static final int REPLAY_CHUNK = 256 * 1024;

  /**
   * A resync replays only this recent a tail — the link just proved too slow for the whole ring.
   */
  static final int RESYNC_TAIL = 256 * 1024;

  /** The writer queue backs up to this many keystrokes before input is refused as backlog. */
  static final int MAX_INPUT_BACKLOG = 512;

  /**
   * Queued plus in-flight input is held to this many bytes: a writer whose child stopped reading
   * hears {@code BACKLOG} before it can grow the host heap, however large its legal frames are.
   */
  static final int MAX_INPUT_BACKLOG_BYTES = 1 << 20;

  /** The attach replay window: everything the journal holds. */
  private final int replayMax;

  private final Map<Long, Subscriber> subscribers = new ConcurrentHashMap<>();
  private final Set<Thread> senders = ConcurrentHashMap.newKeySet();
  private final BlockingQueue<Keystroke> inputQueue = new ArrayBlockingQueue<>(MAX_INPUT_BACKLOG);
  private final Semaphore inputBudget = new Semaphore(MAX_INPUT_BACKLOG_BYTES);
  private final Object fanout = new Object();
  private final AtomicLong subscriberIds = new AtomicLong();
  private final AtomicLong lastInputSeq = new AtomicLong(-1);
  private final CountDownLatch gatherDone = new CountDownLatch(1);
  private volatile Thread writerThread;
  private final long createdAt = System.nanoTime();
  private volatile long writerId = -1;
  private volatile String writerFde = "";
  private int cols;
  private int rows;
  private volatile String endedReason;
  private volatile String yieldedReason;
  private volatile long endedAtNanos;
  private volatile boolean everAttached;
  private volatile boolean handedOff;

  private PtySession(
      Origin origin,
      PtyEvents events,
      Pty pty,
      Child child,
      Journal journal,
      SessionFiles files,
      SdNotify notify,
      String cwd,
      String hostVersion,
      long createdAtMillis,
      int queueCapacity,
      int cols,
      int rows) {
    this.origin = origin;
    this.events = events;
    this.pty = pty;
    this.child = child;
    this.journal = journal;
    this.files = files;
    this.notify = notify;
    this.cwd = cwd;
    this.hostVersion = hostVersion;
    this.createdAtMillis = createdAtMillis;
    this.queueCapacity = queueCapacity;
    this.cols = cols;
    this.rows = rows;
    this.replayMax = (int) Math.min(journal.capacity(), Integer.MAX_VALUE);
  }

  /**
   * Spawns {@code argv} — the process actually executed, which may wrap {@code origin.command()} in
   * a container exec lane — and starts gathering its output. The master goes into the descriptor
   * store at once, so a host crash from here on preserves the session as well as a restart does.
   */
  public static PtySession start(
      Origin origin,
      PtyEvents events,
      List<String> argv,
      Map<String, String> env,
      Path cwd,
      SessionFiles files,
      long journalCapacity,
      int cols,
      int rows,
      String hostVersion,
      SdNotify notify)
      throws IOException {
    return start(
        origin,
        events,
        argv,
        env,
        cwd,
        RingJournal.open(files.ring(), journalCapacity),
        files,
        cols,
        rows,
        hostVersion,
        notify,
        4096);
  }

  /** A session beside {@code journalPath}, outside any host: no version, no store. */
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
    return start(origin, events, argv, env, cwd, journalPath, journalCapacity, cols, rows, 4096);
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
      int queueCapacity)
      throws IOException {
    return start(
        origin,
        events,
        argv,
        env,
        cwd,
        RingJournal.open(journalPath, journalCapacity),
        SessionFiles.beside(journalPath),
        cols,
        rows,
        "",
        SdNotify.NONE,
        queueCapacity);
  }

  static PtySession start(
      Origin origin,
      PtyEvents events,
      List<String> argv,
      Map<String, String> env,
      Path cwd,
      Journal journal,
      SessionFiles files,
      int cols,
      int rows,
      int queueCapacity)
      throws IOException {
    return start(
        origin,
        events,
        argv,
        env,
        cwd,
        journal,
        files,
        cols,
        rows,
        "",
        SdNotify.NONE,
        queueCapacity);
  }

  private static PtySession start(
      Origin origin,
      PtyEvents events,
      List<String> argv,
      Map<String, String> env,
      Path cwd,
      Journal journal,
      SessionFiles files,
      int cols,
      int rows,
      String hostVersion,
      SdNotify notify,
      int queueCapacity)
      throws IOException {
    Pty pty;
    try {
      pty = Pty.open(cols, rows);
    } catch (IOException e) {
      journal.close();
      throw e;
    }
    Process child;
    try {
      child = pty.spawn(argv, env, cwd);
    } catch (IOException e) {
      pty.close();
      journal.close();
      throw e;
    }
    notify.storeFd(origin.name(), pty.fd());
    var session =
        new PtySession(
            origin,
            events,
            pty,
            new Spawned(child),
            journal,
            files,
            notify,
            cwd.toString(),
            hostVersion,
            System.currentTimeMillis(),
            queueCapacity,
            cols,
            rows);
    session.persistMeta();
    session.startThreads();
    emitQuietly(() -> events.sessionStarted(origin));
    return session;
  }

  /**
   * Continues a session a previous host handed off: the inherited master, the ring it was writing,
   * and the sidecar that says what it was. The child is an orphan of the old host, reached by pid;
   * the keyboard's last holder is restored (under an id no new subscriber can be given) so the same
   * FDE reclaims it on reconnect exactly as from a ghost attachment; the never-attached grace
   * starts over, since whoever was attached lost the transport with the old host. No start event:
   * the session is not new.
   */
  public static PtySession resume(
      SessionMeta meta,
      PtyEvents events,
      Pty pty,
      Journal journal,
      SessionFiles files,
      SdNotify notify) {
    return resume(meta, events, pty, journal, files, notify, 4096);
  }

  static PtySession resume(
      SessionMeta meta,
      PtyEvents events,
      Pty pty,
      Journal journal,
      SessionFiles files,
      SdNotify notify,
      int queueCapacity) {
    var child = new Adopted(meta.childPid(), ProcessHandle.of(meta.childPid()), files.exit());
    var session =
        new PtySession(
            meta.origin(),
            events,
            pty,
            child,
            journal,
            files,
            notify,
            meta.cwd(),
            meta.hostVersion(),
            meta.createdAt(),
            queueCapacity,
            meta.cols(),
            meta.rows());
    session.writerId = meta.writerId();
    session.writerFde = meta.writerFde();
    session.subscriberIds.set(Math.max(0, meta.writerId()));
    session.everAttached = meta.everAttached();
    session.rebuildBoundary();
    session.startThreads();
    return session;
  }

  private void rebuildBoundary() {
    try {
      var retained = journal.tail((int) Math.min(Integer.MAX_VALUE, journal.capacity()));
      boundaryKnown = retained.safe() || retained.startOffset() == 0;
      if (boundaryKnown) {
        boundary.feed(retained.bytes(), retained.bytes().length);
      }
    } catch (IOException unreadable) {
      boundaryKnown = false;
    }
  }

  private void startThreads() {
    Thread.ofPlatform().name("pty-gather-" + origin.name()).start(this::gather);
    writerThread = Thread.ofPlatform().name("pty-write-" + origin.name()).start(this::drainInput);
  }

  /** The sidecar as of now — geometry, keyboard and attachment included. */
  public SessionMeta meta() {
    return new SessionMeta(
        origin,
        cwd,
        cols,
        rows,
        writerFde,
        writerId,
        child.pid(),
        createdAtMillis,
        hostVersion,
        everAttached);
  }

  /**
   * Rewrites the sidecar. A failure is logged and swallowed: a sidecar the successor cannot read
   * costs the handoff of this one session, which is the loud {@code lost in handoff} at the next
   * start, never a refused resize or attach now. After a handoff the sidecar belongs to the
   * successor; a connection of this host dropping late (its detach releasing the keyboard) must not
   * overwrite what the successor is about to read. Serialized on the session: a resize and a
   * keyboard transfer persisting at once would race on the same temporary file and could leave the
   * older snapshot as the one on disk, restoring a stale holder or geometry after a restart.
   */
  private synchronized void persistMeta() {
    if (handedOff) {
      return;
    }
    try {
      meta().write(files.meta());
    } catch (IOException | RuntimeException e) {
      System.err.println(
          "pty-host: sidecar write failed session=" + origin.name() + " reason=" + e);
    }
  }

  /**
   * Stops serving without ending anything: the read loop lets go of the master, the keyboard queue
   * is dropped, subscribers are released (their transport dies with this host anyway) and the ring
   * is closed for the successor to reopen. Returns the master descriptor, which stays open — the
   * store already holds its duplicate, and closing it here would hang the child up. A session that
   * already ended still hands its descriptor back; the successor reads the ending from the ring's
   * end and the exit file.
   */
  public int handoff() {
    handedOff = true;
    pty.release();
    var writer = writerThread;
    if (writer != null) {
      writer.interrupt();
    }
    try {
      var unused = gatherDone.await(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    synchronized (this) {
      subscribers.values().forEach(subscriber -> subscriber.queue().clearAnd(new PtyMessage.Ok()));
      subscribers.clear();
    }
    try {
      journal.close();
    } catch (IOException e) {
      System.err.println("pty-host: ring close failed session=" + origin.name() + " reason=" + e);
    }
    return pty.fd();
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
          if (boundaryKnown && boundary.atSafeLineStart()) {
            journal.markSafe();
          }
          var chunk = new byte[n];
          System.arraycopy(buf, 0, chunk, 0, n);
          var output = new PtyMessage.Output(lastInputSeq.get(), chunk);
          subscribers.values().forEach(subscriber -> subscriber.queue().enqueue(output));
        }
      }
    } catch (IOException e) {
      failure = handedOff ? null : "io-error: " + e.getMessage();
    } finally {
      try {
        if (!handedOff) {
          publishEnding(failure);
        }
      } finally {
        gatherDone.countDown();
      }
    }
  }

  private void publishEnding(String failure) {
    var reason = endReason(failure);
    notify.removeFd(origin.name());
    synchronized (fanout) {
      endedAtNanos = System.nanoTime();
      endedReason = reason;
      var ended = new PtyMessage.SessionEnded(reason);
      subscribers.values().forEach(subscriber -> subscriber.queue().force(ended));
    }
    emitQuietly(() -> events.sessionEnded(origin, reason));
  }

  /**
   * The reason the session ends, computed with the pty already released so nothing can wedge. On a
   * pty or journal failure the child may still be alive — close the pty, kill it outright and reap
   * it before the ending is published, because {@link #close()} skips its escalation once the
   * gather is done and a child that shrugs off SIGHUP and SIGTERM would otherwise outlive its
   * session untracked; the reason is the failure. Otherwise the child has exited (or a yield
   * displaced it): close the pty, then read its exit status, which returns at once because the
   * child is already gone.
   */
  private String endReason(String failure) {
    if (failure != null) {
      pty.close();
      child.destroyForcibly();
      var unused = child.exitReason();
      return failure;
    }
    pty.close();
    return Objects.requireNonNullElse(yieldedReason, child.exitReason());
  }

  private void drainInput() {
    try {
      while (true) {
        var keystroke = inputQueue.take();
        try {
          lastInputSeq.set(keystroke.seq());
          pty.write(keystroke.bytes());
        } finally {
          inputBudget.release(keystroke.bytes().length);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException failed) {
      var unused = failed;
    }
  }

  /**
   * Re-baselines a subscriber whose pause dropped part of the stream: under the fanout lock, the
   * journal tail replaces whatever accumulated in its queue (those bytes are inside the snapshot),
   * so the client hears {@code Continued}, a bracketed replay, then live traffic — exactly-once
   * against the journal, never a screen with its middle missing. The overflow that paused it
   * cleared every pending frame, geometry and writer answers included, so the snapshot is framed
   * the way an attach is — {@code Resized}, the replay, {@code WriterChanged} — and built under the
   * session monitor, so a token transfer racing it is heard once, after it, never lost behind it.
   */
  private synchronized void resync(Subscriber subscriber) {
    synchronized (fanout) {
      try {
        var snapshot = new java.util.ArrayList<PtyMessage>();
        snapshot.add(new PtyMessage.Resized(cols, rows));
        snapshot.addAll(replayMessages(RESYNC_TAIL));
        snapshot.add(new PtyMessage.WriterChanged(writerFde));
        subscriber.queue().replaceWith(snapshot);
      } catch (IOException e) {
        subscriber.queue().force(new PtyMessage.SessionEnded("resync failed: " + e.getMessage()));
      }
    }
  }

  /**
   * At most {@code maxBytes} of history as a bracketed replay: {@code ReplayBegin}, then the tail
   * in {@code Output} frames of at most {@link #REPLAY_CHUNK} bytes each, then {@code ReplayEnd}.
   * The wire refuses frames over 1 MiB, so a wide replay must cross as a run of frames; a client
   * concatenates {@code Output} frames regardless, so the bracket is all it sees. Attach replays
   * the whole ring ({@link #replayMax}); a resync replays only a recent {@link #RESYNC_TAIL} tail.
   */
  private List<PtyMessage> replayMessages(int maxBytes) throws IOException {
    var tail = journal.tail(maxBytes);
    var messages = new java.util.ArrayList<PtyMessage>();
    messages.add(new PtyMessage.ReplayBegin(tail.safe()));
    var bytes = tail.bytes();
    for (var offset = 0; offset < bytes.length; offset += REPLAY_CHUNK) {
      var chunk =
          java.util.Arrays.copyOfRange(
              bytes, offset, Math.min(bytes.length, offset + REPLAY_CHUNK));
      messages.add(new PtyMessage.Output(lastInputSeq.get(), chunk));
    }
    messages.add(new PtyMessage.ReplayEnd());
    return List.copyOf(messages);
  }

  /**
   * Attaches a client. Its queue is seeded, in order, with the pty's live geometry, the replay, and
   * the write token's holder, then the live stream follows. A write request takes the token when it
   * is free — or when its holder is this same FDE on another connection: a laptop that slept left a
   * ghost attachment parked on the box holding the keyboard, and the same person coming back is the
   * one who should have it. The displaced connection hears {@code WriterChanged} and its next input
   * is refused. A different FDE's token is never taken here — that is {@link #takeWrite}, an
   * explicit act — so the newcomer learns it is an observer instead.
   */
  public long attach(Client client, boolean wantsWrite, String fde) throws IOException {
    if (endedReason != null) {
      throw new IOException("Session '" + name() + "' has ended: " + endedReason + ".");
    }
    var firstAttach = !everAttached;
    everAttached = true;
    var subscriber =
        new Subscriber(subscriberIds.incrementAndGet(), client, new SubscriberQueue(queueCapacity));
    synchronized (fanout) {
      subscriber.queue().force(new PtyMessage.Resized(cols, rows));
      replayMessages(replayMax).forEach(subscriber.queue()::force);
      subscribers.put(subscriber.id, subscriber);
      if (endedReason != null) {
        subscriber.queue().force(new PtyMessage.SessionEnded(endedReason));
      }
    }
    synchronized (this) {
      if (wantsWrite && (writerId < 0 || writerFde.equals(fde))) {
        grant(subscriber.id, fde);
      } else {
        subscriber.queue().force(new PtyMessage.WriterChanged(writerFde));
        if (firstAttach) {
          persistMeta();
        }
      }
    }
    emitQuietly(() -> events.sessionAttached(origin, fde));
    var sender =
        Thread.ofVirtual()
            .name("pty-send-" + name() + "-" + subscriber.id())
            .unstarted(
                () -> {
                  try {
                    send(subscriber);
                  } finally {
                    senders.remove(Thread.currentThread());
                  }
                });
    senders.add(sender);
    sender.start();
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
   * Offers input on behalf of {@code subscriberId}; only the write-token holder may. Never blocks
   * and never throws: a non-writer is {@code NOT_WRITER}, and a writer whose keystrokes have backed
   * up behind a child that stopped reading is {@code BACKLOG} — both refusable outcomes, not a
   * fatal error that tears down the connection or pins its thread in a blocking foreign write.
   * Backlog is bounded in bytes as well as frames, and the bytes are reserved before the payload is
   * copied — so the budget, not the heap, is what a stalled child exhausts. Accepted keystrokes are
   * drained by the writer thread; the sequence rides future output.
   */
  public WriteOutcome input(long subscriberId, long seq, byte[] bytes) {
    if (subscriberId != writerId) {
      return WriteOutcome.NOT_WRITER;
    }
    if (!inputBudget.tryAcquire(bytes.length)) {
      return WriteOutcome.BACKLOG;
    }
    if (inputQueue.offer(new Keystroke(seq, bytes.clone()))) {
      return WriteOutcome.ACCEPTED;
    }
    inputBudget.release(bytes.length);
    return WriteOutcome.BACKLOG;
  }

  /** Transfers the write token to {@code subscriberId}; everyone hears about it. */
  public synchronized void takeWrite(long subscriberId, String fde) {
    if (!subscribers.containsKey(subscriberId)) {
      throw new IllegalArgumentException("No attached subscriber " + subscriberId + ".");
    }
    grant(subscriberId, fde);
  }

  /**
   * Hands the token to a subscriber the caller vouches for and tells every subscriber. An attach
   * grants to the subscriber it just made without looking it up: a session ending in that same
   * instant has already cleared the roster and queued the ending, and the grant must not throw
   * after the host said {@code Ok} — the ending is what the newcomer hears.
   */
  private void grant(long subscriberId, String fde) {
    writerId = subscriberId;
    writerFde = fde;
    var changed = new PtyMessage.WriterChanged(fde);
    subscribers.values().forEach(subscriber -> subscriber.queue().force(changed));
    persistMeta();
  }

  /**
   * Resizes on behalf of {@code subscriberId}; only the write-token holder may, and observers hear
   * the new geometry. Returns {@code false} — never throws — for a non-writer, so an observer's own
   * window change is a silent no-op rather than a fatal error: the writer's window wins. The
   * geometry changes under the fanout lock, so a concurrent attach either seeds its queue with the
   * new size or is already subscribed when the broadcast goes out — never neither.
   */
  public boolean resize(long subscriberId, int cols, int rows) throws IOException {
    if (subscriberId != writerId) {
      return false;
    }
    synchronized (fanout) {
      pty.resize(cols, rows);
      this.cols = cols;
      this.rows = rows;
      var resized = new PtyMessage.Resized(cols, rows);
      subscribers.values().stream()
          .filter(subscriber -> subscriber.id() != subscriberId)
          .forEach(subscriber -> subscriber.queue().force(resized));
    }
    persistMeta();
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
      persistMeta();
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
   * ending, and a detach's queue-clearing poison would race that delivery and lose it. Those
   * senders are given a moment to deliver it before the caller moves on — a host stopping for good
   * exits right after, and the ending must be on the wire by then, not in a queue.
   */
  @Override
  public void close() {
    child.destroy();
    try {
      if (!gatherDone.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
        child.destroyForcibly();
        pty.close();
        gatherDone.await();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    var writer = writerThread;
    if (writer != null) {
      writer.interrupt();
    }
    awaitSenders();
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

  private void awaitSenders() {
    var deadline = System.nanoTime() + 1_000_000_000L;
    for (var sender : senders) {
      var remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return;
      }
      try {
        sender.join(java.time.Duration.ofNanos(remaining));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
