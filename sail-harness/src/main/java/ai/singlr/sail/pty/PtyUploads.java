/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Files a person pasted or dropped into a terminal, on their way into that session's own workspace
 * and nowhere else (U1-U8): the upload frames of a connection, answered. Where a session's files go
 * is {@link #targetOf}'s alone and their names are {@link #named}'s. Each upload takes its share of
 * the session's {@link UploadAllowance} as it begins and hands it back unless it is stored; its
 * bytes stream into the workspace through an {@link UploadCopy}, which keeps nothing of them on the
 * host and gives the file its name only when the bytes are whole and their SHA-256 is the client's;
 * a session's uploads leave its container through {@link UploadCleanup}. The connection's own
 * thread never waits on a container: an upload's chunks are handed to a thread of its own, at most
 * {@link #QUEUE} at a time, and each is written within {@link #WRITE_DEADLINE} or the upload is
 * given up; an upload that waits for the client past {@link #IDLE} is given up too, and a session's
 * end ends every copy of its uploads at once. Nothing of a file's name or content reaches a log.
 */
final class PtyUploads {

  /** The longest name a stored file keeps, in bytes. */
  static final int NAME = 96;

  /**
   * How long the workspace may take to write one chunk before the upload is given up as stalled.
   */
  static final Duration WRITE_DEADLINE = Duration.ofSeconds(30);

  /** How long an upload may wait for the client's next chunk or its end before it is given up. */
  static final Duration IDLE = Duration.ofMinutes(2);

  /**
   * The chunks an upload holds on the host while its copy writes them: the client's window of two
   * unacknowledged chunks, the one it is sending, and its end. A client that sends more is refused.
   */
  static final int QUEUE = 4;

  /** The most uploads open at once on this host, all sessions together. */
  static final int OPEN_ON_HOST = 16;

  /** Runs {@code task} once {@code delay} has passed: the way to cancel it. */
  @FunctionalInterface
  interface Timer {
    Runnable after(Duration delay, Runnable task);
  }

  /** The system's clock. */
  static final Timer SYSTEM_TIMER =
      (delay, task) -> {
        var due =
            CompletableFuture.runAsync(
                task, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
        return () -> due.cancel(false);
      };

  /**
   * Where an upload into a session goes: a container that is one person's own, or the refusal when
   * there is none ({@link PutRefusal#NO_CONTAINER}) or it is shared by a project's members ({@link
   * PutRefusal#SHARED_CONTAINER}).
   */
  record Target(String container, PutRefusal refused) {
    static final Target NONE = new Target(null, PutRefusal.NO_CONTAINER);
    static final Target SHARED = new Target(null, PutRefusal.SHARED_CONTAINER);

    static Target own(String container) {
      return new Target(container, null);
    }
  }

  /** What became of an upload, and whose it was. */
  private record Answered(String person, PtyMessage answer) {}

  /**
   * One session's uploads: its allowance, whether it ended, its uploads under way, those whose end
   * came and whose commit has not answered, and the answers of its recent uploads by id, for a
   * client that lost its connection after it sent an end; all of these under the record's monitor,
   * held only to read or change them. Its lock, held across a commit and across the removal of the
   * session's directory, puts the two in order.
   */
  private static final class Session {
    final ReentrantLock lock = new ReentrantLock();
    final UploadAllowance allowance = new UploadAllowance();
    boolean ended;
    final Set<Upload> open = new HashSet<>();
    final Map<Integer, Upload> ending = new LinkedHashMap<>();
    final Map<Integer, Answered> outcomes =
        new LinkedHashMap<>() {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Integer, Answered> eldest) {
            return size() > 64;
          }
        };
  }

  private static final byte[] END = new byte[0];
  private static final byte[] STOP = new byte[0];

  private final UploadCopy copies;
  private final UploadCleanup cleanup;
  private final Function<PtySession.Origin, Target> targets;
  private final Timer timer;
  private final Semaphore openOnHost = new Semaphore(OPEN_ON_HOST);
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  PtyUploads(UploadCopy copies, Function<PtySession.Origin, Target> targets, Timer timer) {
    this.copies = copies;
    this.cleanup = new UploadCleanup(copies);
    this.targets = targets;
    this.timer = timer;
  }

  /** Uploads into the containers {@link #targetOf} names, by {@code incus exec}. */
  static PtyUploads intoContainers() {
    return new PtyUploads(UploadCopy.intoContainers(), PtyUploads::targetOf, SYSTEM_TIMER);
  }

  /** Where a file pasted into {@code origin}'s session goes. */
  Target target(PtySession.Origin origin) {
    return targets.apply(origin);
  }

  /**
   * Where a file pasted into {@code origin}'s session goes, decided here alone: a session in a
   * workspace puts it in that workspace's own instance, which is one person's; a session on the box
   * itself has no container, and one in a project's container shares it with every member's
   * sessions, so no one person's file goes there (U1).
   */
  static Target targetOf(PtySession.Origin origin) {
    if (Strings.isBlank(origin.project())) {
      return Target.NONE;
    }
    return Strings.isBlank(origin.workspace()) ? Target.SHARED : Target.own(origin.instance());
  }

  /** The upload one connection has in progress. */
  static final class Upload {
    private final int id;
    private final PtySession session;
    private final long subscriberId;
    private final String person;
    private final Session record;
    private final String container;
    private final String name;
    private final long size;
    private final MessageDigest sha = sha256();
    private final ArrayBlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(QUEUE);
    private final AtomicBoolean settled = new AtomicBoolean();
    private final CountDownLatch answered = new CountDownLatch(1);
    private long queued;
    private long written;
    private volatile Process process;
    private volatile UploadCopy.Copy copy;
    private volatile Runnable idle = () -> {};
    private volatile boolean ready;
    private volatile boolean cancelled;
    private volatile boolean stalled;
    private volatile boolean over;

    private Upload(
        PtyMessage.PutBegin begin,
        PtySession session,
        long subscriberId,
        String person,
        Session record,
        String container) {
      this.id = begin.id();
      this.session = session;
      this.subscriberId = subscriberId;
      this.person = person;
      this.record = record;
      this.container = container;
      this.name = named(begin.name());
      this.size = begin.size();
    }

    int id() {
      return id;
    }

    /** Whether this upload was answered, refused or discarded: its connection may begin another. */
    boolean over() {
      return over;
    }
  }

  /**
   * Begins upload {@code begin} into {@code session} for the connection {@code subscriberId}, whose
   * person is {@code person}: the upload, acknowledged on its own thread once the container holds
   * its temporary, or null with its refusal sent to {@code reply}. Its file and bytes are taken
   * from the session's allowance at once, so two connections to one session cannot both pass the
   * last of it; so is its place among the uploads open in the session and on the host.
   */
  Upload begin(
      PtyMessage.PutBegin begin,
      PtySession session,
      long subscriberId,
      String person,
      Consumer<PtyMessage> reply) {
    var target = target(session.origin());
    var refusal = refusal(session, subscriberId, target, begin.size());
    if (refusal != null) {
      reply.accept(new PtyMessage.PutRefused(begin.id(), refusal, UploadAllowance.limit(refusal)));
      return null;
    }
    var record = sessions.computeIfAbsent(session.origin().instanceId(), id -> new Session());
    var upload = new Upload(begin, session, subscriberId, person, record, target.container());
    refusal = take(record, upload);
    if (refusal != null) {
      reply.accept(new PtyMessage.PutRefused(begin.id(), refusal, UploadAllowance.limit(refusal)));
      return null;
    }
    if (!openOnHost.tryAcquire()) {
      synchronized (record) {
        record.open.remove(upload);
      }
      record.allowance.done(upload.size, false);
      reply.accept(new PtyMessage.PutRefused(begin.id(), PutRefusal.BUSY, OPEN_ON_HOST));
      return null;
    }
    Thread.ofVirtual().name("pty-upload-open").start(() -> open(upload, reply));
    return upload;
  }

  /** Why {@code session} takes no upload of {@code size} from {@code subscriberId}, else null. */
  private static PutRefusal refusal(
      PtySession session, long subscriberId, Target target, long size) {
    if (!session.live()) {
      return PutRefusal.SESSION_ENDED;
    }
    if (!session.isWriter(subscriberId)) {
      return PutRefusal.NOT_WRITER;
    }
    if (target.refused() != null) {
      return target.refused();
    }
    return UploadAllowance.sizeRefusal(size);
  }

  /**
   * Takes {@code upload}'s share of {@code record}'s allowance and counts it among the session's
   * uploads under way, or the refusal when it has no room or has ended. Whether the session is live
   * is asked under the record's monitor, which {@link #ended} takes to mark it ended.
   */
  private PutRefusal take(Session record, Upload upload) {
    synchronized (record) {
      if (record.ended || !upload.session.live()) {
        if (record.allowance.empty()) {
          sessions.remove(upload.session.origin().instanceId(), record);
        }
        return PutRefusal.SESSION_ENDED;
      }
      var refused = record.allowance.take(upload.size);
      if (refused == null) {
        record.open.add(upload);
      }
      return refused;
    }
  }

  /**
   * {@code upload} is done, once: its place among the uploads under way given back, and its file
   * and bytes too unless it was {@code stored}.
   */
  private void settle(Upload upload, boolean stored) {
    if (!upload.settled.compareAndSet(false, true)) {
      return;
    }
    upload.idle.run();
    synchronized (upload.record) {
      upload.record.open.remove(upload);
    }
    upload.record.allowance.done(upload.size, stored);
    openOnHost.release();
  }

  /**
   * Opens {@code upload}'s copy in its container, under the container's lock, which the sweep takes
   * too: a session's first upload makes its directory either before the sweep looks or after it is
   * done. Answers the first acknowledgement, or the container's refusal, whatever fails.
   */
  private void open(Upload upload, Consumer<PtyMessage> reply) {
    var lock = cleanup.lockOf(upload.container);
    lock.lock();
    upload.record.lock.lock();
    try {
      if (ended(upload.record)) {
        refuse(upload, PutRefusal.SESSION_ENDED, reply);
        return;
      }
      var copy =
          copies.open(
              upload.container,
              upload.session.origin().instanceId(),
              upload.name,
              upload.size,
              process -> started(upload, process));
      upload.copy = copy;
      if (ended(upload.record) || !copy.opened()) {
        var kind = ended(upload.record) ? PutRefusal.SESSION_ENDED : copy.refusal();
        if (copy.opened()) {
          copy.close();
          copy.abort();
        }
        refuse(upload, kind, reply);
        return;
      }
      boolean cancelled;
      synchronized (upload) {
        cancelled = upload.cancelled;
        upload.ready = !cancelled;
      }
      if (cancelled) {
        copy.close();
        copy.abort();
        settle(upload, false);
        return;
      }
      Thread.ofVirtual().name("pty-upload-write").start(() -> write(upload, reply));
      waitForClient(upload);
      reply.accept(new PtyMessage.PutAck(upload.id, 0));
    } catch (IOException | RuntimeException e) {
      PtySessionHost.log(
          "upload-failed", "-", upload.session.origin().instanceId(), e.getClass().getSimpleName());
      refuse(upload, ended(upload.record) ? PutRefusal.SESSION_ENDED : PutRefusal.FAILED, reply);
    } finally {
      upload.record.lock.unlock();
      lock.unlock();
    }
  }

  /**
   * {@code upload}'s copy started as {@code process}: ended at once when the session ended or the
   * upload was discarded while it started, since {@link #end} found no process to end then.
   */
  private static void started(Upload upload, Process process) {
    upload.process = process;
    if (upload.over || ended(upload.record)) {
      process.destroyForcibly();
    }
  }

  private static boolean ended(Session record) {
    synchronized (record) {
      return record.ended;
    }
  }

  /** {@code upload} refused before it opened: its allowance handed back, the refusal sent. */
  private void refuse(Upload upload, PutRefusal kind, Consumer<PtyMessage> reply) {
    upload.over = true;
    settle(upload, false);
    if (!upload.cancelled) {
      reply.accept(new PtyMessage.PutRefused(upload.id, kind, UploadAllowance.limit(kind)));
    }
  }

  /** Gives {@code upload} up when the client sends nothing more within {@link #IDLE}. */
  private void waitForClient(Upload upload) {
    upload.idle.run();
    upload.idle = timer.after(IDLE, () -> stop(upload, false));
  }

  /**
   * Writes {@code upload}'s chunks to its copy as the connection hands them over, each within
   * {@link #WRITE_DEADLINE}, acknowledging each once written, then commits it at its end. A copy
   * that stops, is stopped, or is given up ends the upload: its allowance handed back, its
   * temporary removed, and, unless it was cancelled, its refusal answered.
   */
  private void write(Upload upload, Consumer<PtyMessage> reply) {
    try {
      while (true) {
        var next = upload.chunks.take();
        if (next == END) {
          commit(upload, reply);
          return;
        }
        if (next == STOP) {
          throw new IOException("stopped");
        }
        var deadline = timer.after(WRITE_DEADLINE, () -> stop(upload, true));
        try {
          upload.copy.write(next);
        } finally {
          deadline.run();
        }
        upload.written += next.length;
        reply.accept(new PtyMessage.PutAck(upload.id, upload.written));
      }
    } catch (IOException | InterruptedException e) {
      var refusal =
          ended(upload.record)
              ? PutRefusal.SESSION_ENDED
              : upload.stalled ? PutRefusal.STALLED : upload.copy.refusal();
      upload.over = true;
      settle(upload, false);
      upload.copy.close();
      upload.copy.abort();
      if (!upload.cancelled) {
        reply.accept(new PtyMessage.PutRefused(upload.id, refusal, UploadAllowance.limit(refusal)));
      }
    }
  }

  /**
   * Ends {@code upload}'s copy now, whatever it is doing: given up as {@code stalled} by its
   * workspace, or by its client's silence. Its writer then ends it ({@link #write}).
   */
  private static void stop(Upload upload, boolean stalled) {
    upload.stalled = stalled;
    end(upload);
  }

  /**
   * Ends {@code upload}'s process, if it has one yet, and wakes its writer: one waiting for a chunk
   * takes the stop, one writing finds the copy gone. The writer is never interrupted, since a
   * thread interrupted as it answers on the connection would close the connection.
   */
  private static void end(Upload upload) {
    var process = upload.process;
    if (process != null) {
      process.destroyForcibly();
    }
    var unused = upload.chunks.offer(STOP);
  }

  /**
   * Hands {@code chunk} to {@code upload}'s writer: whether the upload goes on. A chunk from a
   * connection that no longer writes, out of order, too large, past the declared size, before the
   * copy is open, or past the chunks the host holds ({@link #QUEUE}) refuses the upload and
   * discards it; one for an upload this connection is not making, or one already answered, is
   * ignored. It is acknowledged once written.
   */
  boolean chunk(Upload upload, PtyMessage.PutChunk chunk, Consumer<PtyMessage> reply) {
    if (upload == null || upload.id != chunk.id()) {
      return upload != null;
    }
    if (upload.over) {
      return false;
    }
    if (!upload.session.isWriter(upload.subscriberId)) {
      fail(upload, PutRefusal.NOT_WRITER, reply);
      return false;
    }
    var bytes = chunk.bytes();
    if (!upload.ready
        || chunk.offset() != upload.queued
        || bytes.length > UploadAllowance.CHUNK
        || upload.queued + bytes.length > upload.size
        || !upload.chunks.offer(bytes)) {
      fail(upload, PutRefusal.FAILED, reply);
      return false;
    }
    upload.sha.update(bytes);
    upload.queued += bytes.length;
    waitForClient(upload);
    return true;
  }

  /**
   * Ends {@code upload}: from a connection that still writes, when what arrived is all that was
   * declared and its SHA-256 the client's, its writer commits it once every chunk is written, and
   * answers {@code reply}; else it is refused and its temporary removed. Until it is answered, its
   * person may cancel it ({@link #cancel}) or ask after it from a new connection ({@link #query}).
   */
  void end(Upload upload, PtyMessage.PutEnd end, Consumer<PtyMessage> reply) {
    if (upload == null || upload.id != end.id() || upload.over) {
      return;
    }
    if (!upload.session.isWriter(upload.subscriberId)) {
      fail(upload, PutRefusal.NOT_WRITER, reply);
      return;
    }
    if (!upload.ready
        || upload.queued != upload.size
        || !MessageDigest.isEqual(upload.sha.digest(), end.sha256())) {
      fail(upload, PutRefusal.FAILED, reply);
      return;
    }
    synchronized (upload.record) {
      upload.record.ending.put(upload.id, upload);
    }
    upload.idle.run();
    if (!upload.chunks.offer(END)) {
      synchronized (upload.record) {
        upload.record.ending.remove(upload.id);
      }
      fail(upload, PutRefusal.FAILED, reply);
    }
  }

  /**
   * Commits {@code upload}'s copy under its session's lock, which {@link #ended} has the session's
   * removal take, so the two are in order: a commit the session's end came before, or came during,
   * keeps nothing and says so, and a removal after it removes the file. A cancel that came before
   * the commit began keeps nothing; one that came later is too late, and the answer says the file
   * is stored.
   */
  private void commit(Upload upload, Consumer<PtyMessage> reply) {
    var copied = upload.copy.close();
    PtyMessage answer;
    upload.record.lock.lock();
    try {
      answer = committed(upload, copied);
      var stored = answer instanceof PtyMessage.PutStored;
      if (!stored) {
        upload.copy.abort();
      }
      settle(upload, stored);
      synchronized (upload.record) {
        upload.record.ending.remove(upload.id);
        upload.record.outcomes.put(upload.id, new Answered(upload.person, answer));
      }
    } finally {
      upload.record.lock.unlock();
    }
    upload.over = true;
    upload.answered.countDown();
    reply.accept(answer);
  }

  /** What {@code upload}'s commit comes to, its copy having exited with {@code copied}. */
  private PtyMessage committed(Upload upload, int copied) {
    if (ended(upload.record)) {
      return new PtyMessage.PutRefused(upload.id, PutRefusal.SESSION_ENDED, 0);
    }
    if (upload.cancelled) {
      return new PtyMessage.PutRefused(upload.id, PutRefusal.CANCELLED, 0);
    }
    if (copied != 0) {
      var refusal = UploadCopy.refusalOf(copied);
      return new PtyMessage.PutRefused(upload.id, refusal, UploadAllowance.limit(refusal));
    }
    var status = upload.copy.commit();
    if (ended(upload.record)) {
      return new PtyMessage.PutRefused(upload.id, PutRefusal.SESSION_ENDED, 0);
    }
    return status == 0
        ? new PtyMessage.PutStored(upload.id, upload.copy.path())
        : new PtyMessage.PutRefused(upload.id, PutRefusal.FAILED, 0);
  }

  /**
   * Cancels upload {@code id} of {@code session}, asked by the connection {@code subscriberId} of
   * {@code person}, sent before or after its end: before, {@code upload} is discarded; after, a
   * commit not yet made keeps nothing and answers {@link PutRefusal#CANCELLED}, and a commit made
   * stands. Only the upload's own person, holding the write token, cancels it.
   */
  void cancel(PtySession session, long subscriberId, String person, int id, Upload upload) {
    if (upload != null && upload.id == id) {
      discard(upload);
      return;
    }
    var record = sessions.get(session.origin().instanceId());
    if (record == null || !session.isWriter(subscriberId)) {
      return;
    }
    synchronized (record) {
      var ending = record.ending.get(id);
      if (ending != null && ending.person.equals(person)) {
        ending.cancelled = true;
      }
    }
  }

  /**
   * Answers what became of upload {@code id} of {@code session}, for a client whose connection
   * dropped after it sent the end: its outcome once its commit is done, or {@link
   * PutRefusal#NOT_KNOWN} when this host has no record of it, or the asking connection {@code
   * subscriberId} is not the writer's, or {@code person} is not the upload's.
   */
  void query(
      PtySession session, long subscriberId, String person, int id, Consumer<PtyMessage> reply) {
    var unknown = new PtyMessage.PutRefused(id, PutRefusal.NOT_KNOWN, 0);
    var record = sessions.get(session.origin().instanceId());
    if (record == null || !session.isWriter(subscriberId)) {
      reply.accept(unknown);
      return;
    }
    Answered known;
    Upload pending;
    synchronized (record) {
      known = record.outcomes.get(id);
      pending = record.ending.get(id);
    }
    if (known != null) {
      reply.accept(known.person().equals(person) ? known.answer() : unknown);
      return;
    }
    if (pending == null || !pending.person.equals(person)) {
      reply.accept(unknown);
      return;
    }
    Thread.ofVirtual()
        .name("pty-upload-query")
        .start(
            () -> {
              try {
                pending.answered.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              Answered done;
              synchronized (record) {
                done = record.outcomes.get(id);
              }
              reply.accept(done != null ? done.answer() : unknown);
            });
  }

  /**
   * Discards {@code upload}, cancelled or cut off before its end: its copy is ended at once, and
   * its temporary removed by its writer once it is open, else by its opening as it finds it
   * cancelled.
   */
  void discard(Upload upload) {
    if (upload == null) {
      return;
    }
    synchronized (upload) {
      upload.cancelled = true;
      upload.over = true;
    }
    upload.idle.run();
    end(upload);
  }

  private void fail(Upload upload, PutRefusal kind, Consumer<PtyMessage> reply) {
    discard(upload);
    reply.accept(new PtyMessage.PutRefused(upload.id, kind, UploadAllowance.limit(kind)));
  }

  /** How many sessions this host keeps a record of: none once every session that uploaded ended. */
  int records() {
    return sessions.size();
  }

  /**
   * The session of {@code origin} ended: no upload begins in it from now on, every copy of its
   * uploads not yet at their end is ended at once and the upload refused {@link
   * PutRefusal#SESSION_ENDED}, and its directory is removed after any commit in flight, whether
   * this host or the one before it stored the files. A container not running then is swept at its
   * next session ({@link #sweep}).
   */
  void ended(PtySession.Origin origin) {
    var record = sessions.remove(origin.instanceId());
    var container = target(origin).container();
    if (record != null) {
      Collection<Upload> underWay;
      synchronized (record) {
        record.ended = true;
        underWay = new ArrayList<>(record.open);
        underWay.removeAll(record.ending.values());
      }
      underWay.forEach(PtyUploads::end);
    }
    if (container != null) {
      cleanup.remove(container, origin.instanceId(), record == null ? null : record.lock);
    }
  }

  /**
   * Sweeps the container of {@code origin}'s session of the uploads of every session not among
   * {@code live} ({@link UploadCleanup#sweep}).
   */
  void sweep(PtySession.Origin origin, Supplier<Collection<String>> live) {
    var container = target(origin).container();
    if (container != null) {
      cleanup.sweep(container, live);
    }
  }

  /**
   * {@code name} as a stored file is named: every byte outside {@code [A-Za-z0-9._-]} becomes
   * {@code -}, at most {@link #NAME} bytes, and {@code file} when nothing, {@code .} or {@code ..}
   * is left. No path made from it needs quoting or leaves its directory.
   */
  static String named(String name) {
    var reduced = new StringBuilder();
    for (var b : name.getBytes(StandardCharsets.UTF_8)) {
      if (reduced.length() == NAME) {
        break;
      }
      var c = (char) (b & 0xff);
      reduced.append(
          (c >= 'a' && c <= 'z')
                  || (c >= 'A' && c <= 'Z')
                  || (c >= '0' && c <= '9')
                  || c == '.'
                  || c == '_'
                  || c == '-'
              ? c
              : '-');
    }
    var named = reduced.toString();
    return named.isEmpty() || named.equals(".") || named.equals("..") ? "file" : named;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("This JVM has no SHA-256.", e);
    }
  }
}
