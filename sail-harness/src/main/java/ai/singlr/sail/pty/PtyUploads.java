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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
 * a session's uploads leave its container through {@link UploadCleanup}. Nothing of a file's name
 * or content reaches a log.
 */
final class PtyUploads {

  /** The longest name a stored file keeps, in bytes. */
  static final int NAME = 96;

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

  /**
   * One session's uploads: its allowance, whether it ended, the uploads whose end came and whose
   * commit has not answered, and the answers of its recent uploads by id, for a client that lost
   * its connection after it sent an end; all of these under the record's monitor, held only to read
   * or change them. Its lock, held across a commit and across the removal of the session's
   * directory, puts the two in order.
   */
  private static final class Session {
    final ReentrantLock lock = new ReentrantLock();
    final UploadAllowance allowance = new UploadAllowance();
    boolean ended;
    final Map<Integer, Upload> ending = new LinkedHashMap<>();
    final Map<Integer, PtyMessage> outcomes =
        new LinkedHashMap<>() {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Integer, PtyMessage> eldest) {
            return size() > 64;
          }
        };
  }

  private final UploadCopy copies;
  private final UploadCleanup cleanup;
  private final Function<PtySession.Origin, Target> targets;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  PtyUploads(UploadCopy copies, Function<PtySession.Origin, Target> targets) {
    this.copies = copies;
    this.cleanup = new UploadCleanup(copies);
    this.targets = targets;
  }

  /** Uploads into the containers {@link #targetOf} names, by {@code incus exec}. */
  static PtyUploads intoContainers() {
    return new PtyUploads(UploadCopy.intoContainers(), PtyUploads::targetOf);
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
    private final Session record;
    private final String container;
    private final String name;
    private final long size;
    private final MessageDigest sha = sha256();
    private long received;
    private final AtomicBoolean given = new AtomicBoolean();
    private final CountDownLatch answered = new CountDownLatch(1);
    private volatile UploadCopy.Copy copy;
    private volatile boolean ready;
    private volatile boolean cancelled;
    private volatile boolean over;

    private Upload(
        PtyMessage.PutBegin begin,
        PtySession session,
        long subscriberId,
        Session record,
        String container) {
      this.id = begin.id();
      this.session = session;
      this.subscriberId = subscriberId;
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
   * Begins upload {@code begin} into {@code session} for the connection {@code subscriberId}: the
   * upload, acknowledged on its own thread once the container holds its temporary, or null with its
   * refusal sent to {@code reply}. Its file and bytes are taken from the session's allowance at
   * once, so two connections to one session cannot both pass the last of it.
   */
  Upload begin(
      PtyMessage.PutBegin begin,
      PtySession session,
      long subscriberId,
      Consumer<PtyMessage> reply) {
    var target = target(session.origin());
    var refusal = refusal(session, subscriberId, target, begin.size());
    var record =
        refusal == null
            ? sessions.computeIfAbsent(session.origin().instanceId(), id -> new Session())
            : null;
    if (record != null) {
      refusal = take(record, session, begin.size());
    }
    if (refusal != null) {
      reply.accept(new PtyMessage.PutRefused(begin.id(), refusal, UploadAllowance.limit(refusal)));
      return null;
    }
    var upload = new Upload(begin, session, subscriberId, record, target.container());
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
   * Takes one file and {@code size} bytes of {@code record}'s allowance, or the refusal when it has
   * no room or has ended. Whether the session is live is asked under the record's monitor, which
   * {@link #ended} takes to mark it ended.
   */
  private PutRefusal take(Session record, PtySession session, long size) {
    synchronized (record) {
      if (record.ended || !session.live()) {
        if (record.allowance.empty()) {
          sessions.remove(session.origin().instanceId(), record);
        }
        return PutRefusal.SESSION_ENDED;
      }
      return record.allowance.take(size);
    }
  }

  /** Hands {@code upload}'s file and bytes back to its session's allowance, once. */
  private static void give(Upload upload) {
    if (upload.given.compareAndSet(false, true)) {
      upload.record.allowance.give(upload.size);
    }
  }

  /**
   * Opens {@code upload}'s copy in its container, under the container's lock, which the sweep takes
   * too: a session's first upload makes its directory either before the sweep looks or after it is
   * done. Answers the first acknowledgement, or the container's refusal.
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
              upload.container, upload.session.origin().instanceId(), upload.name, upload.size);
      upload.copy = copy;
      if (!copy.opened()) {
        refuse(upload, copy.refusal(), reply);
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
        give(upload);
        return;
      }
      reply.accept(new PtyMessage.PutAck(upload.id, 0));
    } catch (IOException e) {
      PtySessionHost.log("upload-failed", "-", upload.session.origin().instanceId(), "open");
      refuse(upload, PutRefusal.FAILED, reply);
    } finally {
      upload.record.lock.unlock();
      lock.unlock();
    }
  }

  private static boolean ended(Session record) {
    synchronized (record) {
      return record.ended;
    }
  }

  /** {@code upload} refused before it opened: its allowance handed back, the refusal sent. */
  private static void refuse(Upload upload, PutRefusal kind, Consumer<PtyMessage> reply) {
    upload.over = true;
    give(upload);
    if (!upload.cancelled) {
      reply.accept(new PtyMessage.PutRefused(upload.id, kind, UploadAllowance.limit(kind)));
    }
  }

  /**
   * Streams {@code chunk} into {@code upload}'s copy: whether the upload goes on. A chunk from a
   * connection that no longer writes, out of order, too large, past the declared size, or before
   * the copy is open refuses the upload and discards it; one for an upload this connection is not
   * making is ignored. A copy the container refused ends the upload with the container's kind.
   */
  boolean chunk(Upload upload, PtyMessage.PutChunk chunk, Consumer<PtyMessage> reply) {
    if (upload == null || upload.id != chunk.id()) {
      return upload != null;
    }
    if (!upload.session.isWriter(upload.subscriberId)) {
      fail(upload, PutRefusal.NOT_WRITER, reply);
      return false;
    }
    var bytes = chunk.bytes();
    if (!upload.ready
        || chunk.offset() != upload.received
        || bytes.length > UploadAllowance.CHUNK
        || upload.received + bytes.length > upload.size) {
      fail(upload, PutRefusal.FAILED, reply);
      return false;
    }
    try {
      upload.copy.write(bytes);
    } catch (IOException e) {
      fail(upload, upload.copy.refusal(), reply);
      return false;
    }
    upload.sha.update(bytes);
    upload.received += bytes.length;
    reply.accept(new PtyMessage.PutAck(upload.id, upload.received));
    return true;
  }

  /**
   * Ends {@code upload}: from a connection that still writes, when what arrived is all that was
   * declared and its SHA-256 the client's, the copy is committed on a thread of its own, which
   * answers {@code reply}; else it is refused and its temporary removed. Until it is answered, a
   * cancel removes it ({@link #cancel}), and a client that lost its connection may ask after it
   * ({@link #query}).
   */
  void end(Upload upload, PtyMessage.PutEnd end, Consumer<PtyMessage> reply) {
    if (upload == null || upload.id != end.id()) {
      return;
    }
    if (!upload.session.isWriter(upload.subscriberId)) {
      fail(upload, PutRefusal.NOT_WRITER, reply);
      return;
    }
    if (!upload.ready
        || upload.received != upload.size
        || !MessageDigest.isEqual(upload.sha.digest(), end.sha256())) {
      fail(upload, PutRefusal.FAILED, reply);
      return;
    }
    upload.record.lock.lock();
    try {
      upload.record.ending.put(upload.id, upload);
    } finally {
      upload.record.lock.unlock();
    }
    Thread.ofVirtual().name("pty-upload-commit").start(() -> commit(upload, reply));
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
      if (!(answer instanceof PtyMessage.PutStored)) {
        upload.copy.abort();
        give(upload);
      }
      synchronized (upload.record) {
        upload.record.ending.remove(upload.id);
        upload.record.outcomes.put(upload.id, answer);
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
   * Cancels upload {@code id} of {@code session}, sent before or after its end: before, {@code
   * upload} is discarded; after, a commit not yet made keeps nothing and answers {@link
   * PutRefusal#CANCELLED}; a commit made stands, and its answer was already sent.
   */
  void cancel(PtySession session, int id, Upload upload) {
    if (upload != null && upload.id == id) {
      discard(upload);
      return;
    }
    var record = sessions.get(session.origin().instanceId());
    if (record == null) {
      return;
    }
    synchronized (record) {
      var ending = record.ending.get(id);
      if (ending != null) {
        ending.cancelled = true;
      }
    }
  }

  /**
   * Answers what became of upload {@code id} of {@code session}, for a client whose connection
   * dropped after it sent the end: its outcome once its commit is done, or {@link
   * PutRefusal#NOT_KNOWN} when this host has no record of it.
   */
  void query(PtySession session, int id, Consumer<PtyMessage> reply) {
    var record = sessions.get(session.origin().instanceId());
    if (record == null) {
      reply.accept(new PtyMessage.PutRefused(id, PutRefusal.NOT_KNOWN, 0));
      return;
    }
    PtyMessage known;
    Upload pending;
    synchronized (record) {
      known = record.outcomes.get(id);
      pending = record.ending.get(id);
    }
    if (known == null && pending != null) {
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
                PtyMessage done;
                synchronized (record) {
                  done = record.outcomes.get(id);
                }
                reply.accept(
                    done != null ? done : new PtyMessage.PutRefused(id, PutRefusal.NOT_KNOWN, 0));
              });
      return;
    }
    reply.accept(known != null ? known : new PtyMessage.PutRefused(id, PutRefusal.NOT_KNOWN, 0));
  }

  /**
   * Discards {@code upload}, cancelled or cut off before its end: its temporary is removed, here
   * once it is open, else by its opening as it finds it cancelled.
   */
  void discard(Upload upload) {
    if (upload == null) {
      return;
    }
    boolean ready;
    synchronized (upload) {
      upload.cancelled = true;
      upload.over = true;
      ready = upload.ready;
    }
    if (!ready) {
      return;
    }
    Thread.ofVirtual()
        .name("pty-upload-abort")
        .start(
            () -> {
              upload.copy.close();
              upload.copy.abort();
              give(upload);
            });
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
   * The session of {@code origin} ended: no upload begins in it from now on, and its directory is
   * removed after any commit in flight, whether this host or the one before it stored the files. A
   * container not running then is swept at its next session ({@link #sweep}).
   */
  void ended(PtySession.Origin origin) {
    var record = sessions.remove(origin.instanceId());
    var container = target(origin).container();
    if (record != null) {
      synchronized (record) {
        record.ended = true;
      }
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
