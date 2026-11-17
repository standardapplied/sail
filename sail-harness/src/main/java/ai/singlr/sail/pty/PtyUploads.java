/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Files a person pasted or dropped into a terminal, on their way into that session's own workspace
 * and nowhere else (U1-U8). Nothing of a file is kept on the host: from the moment the host accepts
 * an upload its bytes stream, as the dev user, into a temporary file the upload opened in the
 * session's directory ({@link #OPEN}), and the file takes its name, which the host chose, only when
 * the bytes are whole and their SHA-256 is the client's ({@link #COMMIT}); anything else removes
 * the temporary ({@link #ABORT}). The session's directory is the root's, under a root-owned {@link
 * #ROOT}, so nothing in the container can put a link where a deleter will look. Every bound is
 * checked on the host when an upload begins and again by the container against what the directory
 * holds and the disk has free; a refusal names its kind, decided by an exit status, and the bound
 * it met. Nothing of a file's name or content reaches a log. A session's uploads go when it ends
 * ({@link #ended}), or at the next session made in the same container ({@link #sweep}); neither
 * runs under the host's lock.
 */
final class PtyUploads {

  /** The largest file one upload carries. */
  static final long ITEM = 64L << 20;

  /** The most files one session keeps. */
  static final int FILES = 256;

  /** The most bytes one session keeps. */
  static final long SESSION = 1L << 30;

  /** The most bytes one chunk carries. */
  static final int CHUNK = 64 << 10;

  /** The longest name a stored file keeps, in bytes. */
  static final int NAME = 96;

  /**
   * Where sessions' uploads are kept in a container, one directory each, under their instance id.
   */
  static final String ROOT = "/var/lib/sail-paste";

  /**
   * How long one script in a container may take, and an upload's copy may wait to open, before it
   * is given up as failed: a container that never answers holds no lock past it.
   */
  static final Duration SCRIPT = Duration.ofMinutes(2);

  /** Exit statuses of the scripts that refuse: what is unsafe, too many files, too many bytes. */
  static final int UNSAFE = 10;

  static final int TOO_MANY = 11;
  static final int FULL = 12;

  /** The status a script exits with when the workspace's disk has no room for the file. */
  static final int NO_SPACE = 28;

  /**
   * Run as root: checks the disk has room for {@code $4} bytes, before anything is made on it,
   * makes {@code $1} root's own and {@code $1/$2} the dev user's alone, refusing a link at either,
   * then, as the dev user ({@code shift 9; "$@"}), counts what the directory holds against {@code
   * $5} files and {@code $6} bytes, creates the temporary for the first number no file takes with
   * {@code O_EXCL} (a link at the name is refused, never written through), prints that number, and
   * copies its input into it through the descriptor it opened. A copy that fails is {@code
   * no_space} when it left the disk under 64 KiB free; the host removes its temporary.
   */
  static final String OPEN =
      """
      root=$1 id=$2 name=$3 size=$4 files=$5 bytes=$6 rootuid=$7 devuid=$8 devgid=$9
      shift 9
      free=$(df -Pk "${root%/*}" | awk "NR == 2 { print \\$4 * 1024 }")
      [ "$free" -ge "$size" ] || exit 28
      [ -d "$root" ] || mkdir -m 0755 "$root" || exit 10
      [ -d "$root" ] && [ ! -L "$root" ] && [ "$(stat -c %u "$root")" = "$rootuid" ] || exit 10
      chmod 0755 "$root"
      dir=$root/$id
      [ -d "$dir" ] || mkdir -m 0700 "$dir" || exit 10
      [ -d "$dir" ] && [ ! -L "$dir" ] || exit 10
      chown "$devuid:$devgid" "$dir" && chmod 0700 "$dir" || exit 10
      exec "$@" sh -c '
      cd "$1" || exit 1
      held=$(find . -mindepth 1 -maxdepth 1 ! -name ".*.part" | wc -l)
      [ "$held" -lt "$4" ] || exit 11
      used=$(find . -mindepth 1 -maxdepth 1 -type f -printf "%s\\n" | awk "{ s += \\$1 } END { print s + 0 }")
      [ $((used + $3)) -le "$5" ] || exit 12
      n=1
      while [ -e "$n-$2" ] || [ -L "$n-$2" ] || [ -e ".$n-$2.part" ] || [ -L ".$n-$2.part" ]; do
        n=$((n + 1))
      done
      umask 077
      set -C
      exec 3>".$n-$2.part"
      set +C
      echo "$n"
      if ! cat >&3; then
        free=$(df -Pk . | awk "NR == 2 { print \\$4 * 1024 }")
        [ "$free" -lt 65536 ] && exit 28
        exit 1
      fi
      ' sail-put "$dir" "$name" "$size" "$files" "$bytes"
      """;

  /**
   * Run as the dev user in the session's directory {@code $1}: gives the temporary of number {@code
   * $2} and name {@code $3} its name when it is a file of {@code $4} bytes, by a hard link that
   * refuses an existing name, and removes the temporary either way.
   */
  static final String COMMIT =
      """
      cd "$1" || exit 1
      part=".$2-$3.part"
      [ -f "$part" ] && [ ! -L "$part" ] && [ "$(stat -c %s "$part")" = "$4" ] && ln "$part" "$2-$3"
      done=$?
      rm -f "$part"
      exit $done
      """;

  /**
   * Run as the dev user: removes the temporary of number {@code $2} and name {@code $3} in {@code
   * $1}.
   */
  static final String ABORT = "cd \"$1\" && rm -f \".$2-$3.part\"";

  /**
   * Run as root: with {@code $1} a directory, no link, owned by {@code $3}, removes {@code $2}
   * under it, a link there or inside it removed as the link, never followed: {@code rm -r} walks by
   * descriptor without following links. A root that is anything else is refused, not touched.
   */
  static final String REMOVE =
      """
      [ -e "$1" ] || [ -L "$1" ] || exit 0
      [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = "$3" ] || exit 10
      rm -rf --one-file-system -- "$1/$2"
      """;

  /**
   * Run as root: with {@code $1} root's own and no link, removes every directory under it named as
   * an instance id that is not among the ids after {@code $2}, the root's owner.
   */
  static final String SWEEP =
      """
      root=$1 rootuid=$2
      shift 2
      [ -e "$root" ] || [ -L "$root" ] || exit 0
      [ -d "$root" ] && [ ! -L "$root" ] && [ "$(stat -c %u "$root")" = "$rootuid" ] || exit 10
      for d in "$root"/*; do
        [ -d "$d" ] || continue
        id=${d##*/}
        case $id in *[!0-9a-f-]*) continue ;; esac
        keep=
        for live in "$@"; do [ "$id" = "$live" ] && keep=1; done
        [ -n "$keep" ] || rm -rf --one-file-system -- "$d"
      done
      """;

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
   * How the scripts reach a container, and whose ids they use: production's runs them by {@code
   * incus exec} as root ({@code asRoot}) or the dev user ({@code asDev}), in {@link #ROOT}, root's
   * and the dev user's ids, dropping to the dev user with {@code setpriv}, each script given up
   * after {@link #SCRIPT} ({@code patience}). A test runs them here.
   */
  record Place(
      Function<String, List<String>> asRoot,
      Function<String, List<String>> asDev,
      String root,
      String rootUid,
      String devUid,
      String devGid,
      List<String> drop,
      Duration patience) {

    static final Place CONTAINERS =
        new Place(
            container -> ContainerExec.asRoot(container, List.of()),
            container -> ContainerExec.asDevUser(container, "/", Map.of(), List.of()),
            ROOT,
            "0",
            ContainerExec.DEV_UID,
            ContainerExec.DEV_GID,
            List.of(
                "setpriv",
                "--reuid=" + ContainerExec.DEV_UID,
                "--regid=" + ContainerExec.DEV_GID,
                "--clear-groups"),
            SCRIPT);
  }

  /** Starts a command, its input to write and its output to read. */
  @FunctionalInterface
  interface Processes {
    Process start(List<String> command) throws IOException;
  }

  /**
   * One session's uploads: its stored and pending files and their bytes, whether it ended, the
   * uploads whose end came and whose commit has not answered, and the answers of its recent uploads
   * by id, for a client that lost its connection after it sent an end; all of these under the
   * record's monitor, held only to read or change them. Its lock, held across a commit and across
   * the removal of the session's directory, puts the two in order.
   */
  private static final class Session {
    final ReentrantLock lock = new ReentrantLock();
    int files;
    long bytes;
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

  private final Processes processes;
  private final Place place;
  private final Function<PtySession.Origin, Target> targets;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> containers = new ConcurrentHashMap<>();

  PtyUploads(Processes processes, Place place, Function<PtySession.Origin, Target> targets) {
    this.processes = processes;
    this.place = place;
    this.targets = targets;
  }

  /** Uploads into the containers {@link #targetOf} names, by {@code incus exec}. */
  static PtyUploads intoContainers() {
    return new PtyUploads(
        command ->
            new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(),
        Place.CONTAINERS,
        PtyUploads::targetOf);
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
    private final String directory;
    private final String name;
    private final long size;
    private final MessageDigest sha = sha256();
    private long received;
    private final AtomicBoolean given = new AtomicBoolean();
    private final CountDownLatch answered = new CountDownLatch(1);
    private volatile Process stream;
    private volatile int number;
    private volatile boolean ready;
    private volatile boolean cancelled;
    private volatile boolean over;

    private Upload(
        PtyMessage.PutBegin begin,
        PtySession session,
        long subscriberId,
        Session record,
        String container,
        String directory) {
      this.id = begin.id();
      this.session = session;
      this.subscriberId = subscriberId;
      this.record = record;
      this.container = container;
      this.directory = directory;
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

    private String path() {
      return directory + "/" + number + "-" + name;
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
      reply.accept(new PtyMessage.PutRefused(begin.id(), refusal, limit(refusal)));
      return null;
    }
    var upload =
        new Upload(
            begin,
            session,
            subscriberId,
            record,
            target.container(),
            place.root() + "/" + session.origin().instanceId());
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
    return size < 0 || size > ITEM ? PutRefusal.TOO_LARGE : null;
  }

  private static long limit(PutRefusal refusal) {
    return switch (refusal) {
      case TOO_LARGE -> ITEM;
      case TOO_MANY -> FILES;
      case SESSION_FULL -> SESSION;
      default -> 0;
    };
  }

  /**
   * Takes one file and {@code size} bytes of {@code record}'s allowance, or the refusal when it has
   * no room or has ended. Whether the session is live is asked under the record's monitor, which
   * {@link #ended} takes to mark it ended.
   */
  private PutRefusal take(Session record, PtySession session, long size) {
    synchronized (record) {
      if (record.ended || !session.live()) {
        if (record.files == 0) {
          sessions.remove(session.origin().instanceId(), record);
        }
        return PutRefusal.SESSION_ENDED;
      }
      if (record.files >= FILES) {
        return PutRefusal.TOO_MANY;
      }
      if (record.bytes + size > SESSION) {
        return PutRefusal.SESSION_FULL;
      }
      record.files++;
      record.bytes += size;
      return null;
    }
  }

  /** Hands {@code upload}'s file and bytes back to its session's allowance, once. */
  private static void give(Upload upload) {
    if (!upload.given.compareAndSet(false, true)) {
      return;
    }
    synchronized (upload.record) {
      upload.record.files--;
      upload.record.bytes -= upload.size;
    }
  }

  /**
   * Opens {@code upload}'s temporary in its container, under the container's lock, which the sweep
   * takes too: a session's first upload makes its directory either before the sweep looks or after
   * it is done. Answers the first acknowledgement, or the container's refusal.
   */
  private void open(Upload upload, Consumer<PtyMessage> reply) {
    var lock = containers.computeIfAbsent(upload.container, name -> new ReentrantLock());
    lock.lock();
    upload.record.lock.lock();
    try {
      if (ended(upload.record)) {
        refuse(upload, PutRefusal.SESSION_ENDED, reply);
        return;
      }
      var command = new ArrayList<>(place.asRoot().apply(upload.container));
      command.addAll(
          List.of(
              "sh",
              "-c",
              OPEN,
              "sail-open",
              place.root(),
              upload.session.origin().instanceId(),
              upload.name,
              Long.toString(upload.size),
              Integer.toString(FILES),
              Long.toString(SESSION),
              place.rootUid(),
              place.devUid(),
              place.devGid()));
      command.addAll(place.drop());
      var process = processes.start(command);
      upload.stream = process;
      var unanswered =
          CompletableFuture.runAsync(
              process::destroyForcibly,
              CompletableFuture.delayedExecutor(
                  place.patience().toMillis(), TimeUnit.MILLISECONDS));
      var line =
          new BufferedReader(
                  new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
              .readLine();
      unanswered.cancel(false);
      if (line != null && line.matches("[0-9]{1,9}")) {
        upload.number = Integer.parseInt(line);
      }
      if (upload.number == 0) {
        refuse(upload, refusalOf(finish(process)), reply);
        return;
      }
      boolean cancelled;
      synchronized (upload) {
        cancelled = upload.cancelled;
        upload.ready = !cancelled;
      }
      if (cancelled) {
        close(upload);
        abort(upload);
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
      reply.accept(new PtyMessage.PutRefused(upload.id, kind, limit(kind)));
    }
  }

  private static PutRefusal refusalOf(int status) {
    return switch (status) {
      case TOO_MANY -> PutRefusal.TOO_MANY;
      case FULL -> PutRefusal.SESSION_FULL;
      case NO_SPACE -> PutRefusal.NO_SPACE;
      default -> PutRefusal.FAILED;
    };
  }

  /**
   * Streams {@code chunk} into {@code upload}'s temporary: whether the upload goes on. A chunk from
   * a connection that no longer writes, out of order, too large, past the declared size, or before
   * the temporary is open refuses the upload and discards it; one for an upload this connection is
   * not making is ignored. A copy the container refused ends the upload with the container's kind.
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
        || bytes.length > CHUNK
        || upload.received + bytes.length > upload.size) {
      fail(upload, PutRefusal.FAILED, reply);
      return false;
    }
    try {
      OutputStream in = upload.stream.getOutputStream();
      in.write(bytes);
      in.flush();
    } catch (IOException e) {
      var refusal = refusalOf(finish(upload.stream));
      fail(upload, refusal, reply);
      return false;
    }
    upload.sha.update(bytes);
    upload.received += bytes.length;
    reply.accept(new PtyMessage.PutAck(upload.id, upload.received));
    return true;
  }

  /**
   * Ends {@code upload}: from a connection that still writes, when what arrived is all that was
   * declared and its SHA-256 the client's, the temporary takes its name on a thread of its own,
   * which answers {@code reply}; else it is refused and its temporary removed. Until it is
   * answered, a cancel removes it ({@link #cancel}), and a client that lost its connection may ask
   * after it ({@link #query}).
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
   * Gives {@code upload}'s temporary its name under its session's lock, which {@link #ended} takes
   * to remove the session's directory, so the two are in order: a commit the session's end came
   * before, or came during, keeps nothing and says so, and a removal after it removes the file. A
   * cancel that came before the commit began keeps nothing; one that came later is too late, and
   * the answer says the file is stored.
   */
  private void commit(Upload upload, Consumer<PtyMessage> reply) {
    var copied = close(upload);
    PtyMessage answer;
    upload.record.lock.lock();
    try {
      answer = committed(upload, copied);
      if (!(answer instanceof PtyMessage.PutStored)) {
        abort(upload);
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
      var refusal = refusalOf(copied);
      return new PtyMessage.PutRefused(upload.id, refusal, limit(refusal));
    }
    var status =
        run(
            upload.container,
            place.asDev(),
            COMMIT,
            upload.directory,
            Integer.toString(upload.number),
            upload.name,
            Long.toString(upload.size));
    if (ended(upload.record)) {
      return new PtyMessage.PutRefused(upload.id, PutRefusal.SESSION_ENDED, 0);
    }
    return status == 0
        ? new PtyMessage.PutStored(upload.id, upload.path())
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
   * PutRefusal#UNKNOWN} when this host has no record of it.
   */
  void query(PtySession session, int id, Consumer<PtyMessage> reply) {
    var record = sessions.get(session.origin().instanceId());
    if (record == null) {
      reply.accept(new PtyMessage.PutRefused(id, PutRefusal.UNKNOWN, 0));
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
                    done != null ? done : new PtyMessage.PutRefused(id, PutRefusal.UNKNOWN, 0));
              });
      return;
    }
    reply.accept(known != null ? known : new PtyMessage.PutRefused(id, PutRefusal.UNKNOWN, 0));
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
              close(upload);
              abort(upload);
              give(upload);
            });
  }

  private void fail(Upload upload, PutRefusal kind, Consumer<PtyMessage> reply) {
    discard(upload);
    reply.accept(new PtyMessage.PutRefused(upload.id, kind, limit(kind)));
  }

  /** Ends {@code upload}'s copy: its input closed, the copy's status once it exits. */
  private int close(Upload upload) {
    var process = upload.stream;
    if (process == null) {
      return 1;
    }
    try {
      process.getOutputStream().close();
    } catch (IOException e) {
      var unused = e;
    }
    return finish(process);
  }

  /** {@code process}'s exit status, waiting at most the place's patience; 1 when it hangs. */
  private int finish(Process process) {
    try {
      if (process.waitFor(place.patience().toMillis(), TimeUnit.MILLISECONDS)) {
        return process.exitValue();
      }
      process.destroyForcibly();
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return 1;
    }
  }

  private void abort(Upload upload) {
    if (upload.number > 0) {
      run(
          upload.container,
          place.asDev(),
          ABORT,
          upload.directory,
          Integer.toString(upload.number),
          upload.name);
    }
  }

  /** How many sessions this host keeps a record of: none once every session that uploaded ended. */
  int records() {
    return sessions.size();
  }

  /**
   * The session of {@code origin} ended: no upload begins in it from now on, and its directory is
   * removed on a thread of its own, after any commit in flight, whether this host or the one before
   * it stored the files. A container not running then is swept at its next session ({@link
   * #sweep}).
   */
  void ended(PtySession.Origin origin) {
    var record = sessions.remove(origin.instanceId());
    var container = target(origin).container();
    if (record != null) {
      synchronized (record) {
        record.ended = true;
      }
    }
    if (container == null) {
      return;
    }
    Thread.ofVirtual()
        .name("pty-upload-remove")
        .start(
            () -> {
              if (record != null) {
                record.lock.lock();
              }
              try {
                if (run(
                        container,
                        place.asRoot(),
                        REMOVE,
                        place.root(),
                        origin.instanceId(),
                        place.rootUid())
                    != 0) {
                  PtySessionHost.log(
                      "upload-cleanup-failed",
                      "-",
                      origin.instanceId(),
                      "swept at the next session");
                }
              } finally {
                if (record != null) {
                  record.lock.unlock();
                }
              }
            });
  }

  /**
   * Removes, on a thread of its own and under the container's lock, the uploads in the container of
   * {@code origin}'s session of every session not among those live as it runs ({@code live}, asked
   * under the lock): what a session that ended while its container was stopped left. A session's
   * directory is made only by its first upload, which needs the session live and takes the same
   * lock, so the sweep never removes what a live session made or is making.
   */
  void sweep(PtySession.Origin origin, Supplier<Collection<String>> live) {
    var container = target(origin).container();
    if (container == null) {
      return;
    }
    Thread.ofVirtual()
        .name("pty-upload-sweep")
        .start(
            () -> {
              var lock = containers.computeIfAbsent(container, name -> new ReentrantLock());
              lock.lock();
              try {
                var args = new ArrayList<>(List.of(place.root(), place.rootUid()));
                args.addAll(live.get());
                if (run(container, place.asRoot(), SWEEP, args.toArray(String[]::new)) != 0) {
                  PtySessionHost.log(
                      "upload-sweep-failed", "-", container, "kept until the next session");
                }
              } finally {
                lock.unlock();
              }
            });
  }

  /**
   * Runs {@code script} in {@code container} as {@code as} gives, its exit status; 1 if it failed.
   */
  private int run(
      String container, Function<String, List<String>> as, String script, String... args) {
    var command = new ArrayList<>(as.apply(container));
    command.addAll(List.of("sh", "-c", script, "sail-upload"));
    command.addAll(List.of(args));
    try {
      var process = processes.start(command);
      process.getOutputStream().close();
      process.getInputStream().transferTo(OutputStream.nullOutputStream());
      return finish(process);
    } catch (IOException e) {
      return 1;
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
