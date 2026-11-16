/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Files a person pasted or dropped into a terminal, on their way into that session's own workspace
 * and nowhere else (U1-U8). Each connection holds at most one upload: its bytes are appended to a
 * private staging file on the connection's own thread as they arrive, and only a whole file whose
 * SHA-256 matches is pushed, on a thread of its own, to a path the host makes from {@link #ROOT},
 * the session's instance id, a number and the name reduced by {@link #named}, which needs no
 * quoting. Every bound is checked when an upload begins, and the session's again against what its
 * directory holds before the push, so a host that took the session over counts what is there. A
 * refusal names its kind and the bound it met, and the client words it. Nothing of a file's name or
 * content reaches a log. A session's uploads go when it ends ({@link #ended}), or at the next
 * session made in the same container ({@link #sweep}).
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

  /** Where a session's uploads are kept in its container, under its instance id. */
  static final String ROOT = "/tmp/sail-paste/";

  /**
   * Makes the directory {@code $1} the dev user's alone and prints the first number from {@code $2}
   * that no {@code <number>-$3} there has, then how many entries and bytes the directory holds.
   */
  static final String PLACE =
      "mkdir -p -m 0700 \"$1\" && cd \"$1\" && n=$2"
          + " && while [ -e \"$n-$3\" ]; do n=$((n + 1)); done"
          + " && echo \"$n $(ls -A | wc -l) $(du -sb . | cut -f1)\"";

  /** Removes every directory under {@code $1} not named among the rest of the arguments. */
  static final String SWEEP =
      "cd \"$1\" 2>/dev/null || exit 0; shift; for d in *; do [ -e \"$d\" ] || continue; keep=;"
          + " for k in \"$@\"; do [ \"$d\" = \"$k\" ] && keep=1; done;"
          + " [ -n \"$keep\" ] || rm -rf -- \"$d\"; done";

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

  /** Where staged bytes go: the box's private staging directory ({@link ContainerFilePush}). */
  @FunctionalInterface
  interface StagingDir {
    Path get() throws IOException;
  }

  /**
   * What a session holds of uploads in this host's life: its stored and staged files, their bytes,
   * and how many it has begun, which numbers the next.
   */
  private record Tally(int files, long bytes, int begun) {}

  private final ShellExec shell;
  private final StagingDir staging;
  private final Function<PtySession.Origin, Target> targets;
  private final Map<String, Tally> tallies = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();

  PtyUploads(ShellExec shell, StagingDir staging, Function<PtySession.Origin, Target> targets) {
    this.shell = shell;
    this.staging = staging;
    this.targets = targets;
  }

  /** Uploads pushed by {@code incus} into the containers {@link #targetOf} names. */
  static PtyUploads intoContainers() {
    return new PtyUploads(
        new ShellExecutor(false), ContainerFilePush::staging, PtyUploads::targetOf);
  }

  /** Where a file pasted into {@code origin}'s session goes. */
  Target target(PtySession.Origin origin) {
    return targets.apply(origin);
  }

  /**
   * Where a file pasted into {@code origin}'s session goes, decided here alone: a session on the
   * box itself has no container, and one in a project's container shares it with every member's
   * sessions, so no one person's file goes there (U1).
   */
  static Target targetOf(PtySession.Origin origin) {
    return Strings.isBlank(origin.project()) ? Target.NONE : Target.SHARED;
  }

  /** The upload one connection has in progress. Used on that connection's thread alone. */
  static final class Upload {
    private final int id;
    private final String instanceId;
    private final String container;
    private final String name;
    private final int number;
    private final Path staged;
    private final FileChannel out;
    private final MessageDigest sha = sha256();
    private final long size;
    private long received;

    private Upload(
        PtyMessage.PutBegin begin, String instanceId, String container, int number, Path staged)
        throws IOException {
      this.id = begin.id();
      this.instanceId = instanceId;
      this.container = container;
      this.name = named(begin.name());
      this.number = number;
      this.staged = staged;
      this.size = begin.size();
      this.out = FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    int id() {
      return id;
    }
  }

  /**
   * Begins upload {@code begin} into {@code session} for the connection {@code subscriberId}: the
   * upload, acknowledged, or null with its refusal sent to {@code reply}. Its file and bytes are
   * taken from the session's allowance at once, so two connections to one session cannot both pass
   * the last of it.
   */
  Upload begin(
      PtyMessage.PutBegin begin,
      PtySession session,
      long subscriberId,
      Consumer<PtyMessage> reply) {
    var target = target(session.origin());
    var instanceId = session.origin().instanceId();
    var number = new int[1];
    var refusal = refusal(session, subscriberId, target, begin.size());
    if (refusal == null) {
      refusal = take(session, begin.size(), number);
    }
    if (refusal != null) {
      reply.accept(new PtyMessage.PutRefused(begin.id(), refusal, limit(refusal)));
      return null;
    }
    Path staged = null;
    try {
      staged = Files.createTempFile(staging.get(), "paste-", ".tmp");
      var upload = new Upload(begin, instanceId, target.container(), number[0], staged);
      reply.accept(new PtyMessage.PutAck(begin.id(), 0));
      return upload;
    } catch (IOException e) {
      delete(staged);
      give(instanceId, begin.size());
      PtySessionHost.log("upload-failed", "-", instanceId, "staging");
      reply.accept(new PtyMessage.PutRefused(begin.id(), PutRefusal.FAILED, 0));
      return null;
    }
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
   * The refusal when {@code session} has ended or has no room for {@code size} more bytes, else
   * null, having taken them and set {@code number} to the upload's place among the session's. The
   * session is asked whether it is live inside the tally's own update, which {@link #ended} follows
   * by removing the tally: an upload counted before the end is refused at its push.
   */
  private PutRefusal take(PtySession session, long size, int[] number) {
    var refused = new PutRefusal[1];
    tallies.compute(
        session.origin().instanceId(),
        (id, held) -> {
          var now = held == null ? new Tally(0, 0, 0) : held;
          refused[0] =
              !session.live()
                  ? PutRefusal.SESSION_ENDED
                  : now.files() >= FILES
                      ? PutRefusal.TOO_MANY
                      : now.bytes() + size > SESSION ? PutRefusal.SESSION_FULL : null;
          if (refused[0] != null) {
            return held;
          }
          number[0] = now.begun() + 1;
          return new Tally(now.files() + 1, now.bytes() + size, number[0]);
        });
    return refused[0];
  }

  private void give(String instanceId, long bytes) {
    tallies.computeIfPresent(
        instanceId, (id, held) -> new Tally(held.files() - 1, held.bytes() - bytes, held.begun()));
  }

  /**
   * Appends {@code chunk} to {@code upload}: whether the upload goes on. A chunk out of order, too
   * large, or past the declared size refuses the upload as {@link PutRefusal#FAILED} and discards
   * it; one for an upload this connection is not making (refused or cancelled since) is ignored.
   */
  boolean chunk(Upload upload, PtyMessage.PutChunk chunk, Consumer<PtyMessage> reply) {
    if (upload == null || upload.id != chunk.id()) {
      return upload != null;
    }
    var bytes = chunk.bytes();
    if (chunk.offset() != upload.received
        || bytes.length > CHUNK
        || upload.received + bytes.length > upload.size) {
      fail(upload, PutRefusal.FAILED, reply);
      return false;
    }
    try {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        upload.out.write(buffer);
      }
    } catch (IOException e) {
      fail(upload, PutRefusal.NO_SPACE, reply);
      return false;
    }
    upload.sha.update(bytes);
    upload.received += bytes.length;
    reply.accept(new PtyMessage.PutAck(upload.id, upload.received));
    return true;
  }

  /**
   * Ends {@code upload}: when what arrived is all that was declared and its SHA-256 the client's,
   * it is pushed into the session's container on a thread of its own, which answers {@code reply};
   * else it is refused as {@link PutRefusal#FAILED}. The staged file goes either way.
   */
  void end(Upload upload, PtyMessage.PutEnd end, Consumer<PtyMessage> reply) {
    if (upload == null || upload.id != end.id()) {
      return;
    }
    close(upload);
    if (upload.received != upload.size
        || !MessageDigest.isEqual(upload.sha.digest(), end.sha256())) {
      fail(upload, PutRefusal.FAILED, reply);
      return;
    }
    Thread.ofVirtual().name("pty-upload").start(() -> push(upload, reply));
  }

  /**
   * Pushes {@code upload} under its session's lock, which {@link #ended} takes to remove the
   * session's uploads: a push lands before that removal, which takes it too, or finds the session
   * ended and pushes nothing.
   */
  private void push(Upload upload, Consumer<PtyMessage> reply) {
    var answer = (PtyMessage) new PtyMessage.PutRefused(upload.id, PutRefusal.FAILED, 0);
    try {
      synchronized (lock(upload.instanceId)) {
        answer = tallies.containsKey(upload.instanceId) ? place(upload) : ended(upload);
      }
    } catch (IOException | TimeoutException e) {
      var full = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("no space");
      PtySessionHost.log("upload-failed", "-", upload.instanceId, full ? "no space" : "push");
      answer =
          new PtyMessage.PutRefused(upload.id, full ? PutRefusal.NO_SPACE : PutRefusal.FAILED, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      delete(upload.staged);
    }
    if (!(answer instanceof PtyMessage.PutStored)) {
      give(upload.instanceId, upload.size);
    }
    reply.accept(answer);
  }

  private PtyMessage ended(Upload upload) {
    return new PtyMessage.PutRefused(upload.id, PutRefusal.SESSION_ENDED, 0);
  }

  /**
   * Places {@code upload} in its session's directory under the first free number from its own, once
   * the directory, as it is, has room for it.
   */
  private PtyMessage place(Upload upload)
      throws IOException, InterruptedException, TimeoutException {
    var dir = ROOT + upload.instanceId;
    var placed =
        shell.exec(
            ContainerExec.asDevUser(
                upload.container,
                List.of(
                    "sh",
                    "-c",
                    PLACE,
                    "sail-place",
                    dir,
                    Integer.toString(upload.number),
                    upload.name)));
    if (!placed.ok()) {
      throw new IOException("Could not make the session's upload directory: " + placed.reason());
    }
    var held = placed.stdout().strip().split(" ");
    if (Integer.parseInt(held[1]) >= FILES) {
      return new PtyMessage.PutRefused(upload.id, PutRefusal.TOO_MANY, FILES);
    }
    if (Long.parseLong(held[2]) + upload.size > SESSION) {
      return new PtyMessage.PutRefused(upload.id, PutRefusal.SESSION_FULL, SESSION);
    }
    var path = dir + "/" + held[0] + "-" + upload.name;
    ContainerFilePush.push(
        shell,
        upload.container,
        path,
        upload.staged,
        List.of("--uid", ContainerExec.DEV_UID, "--gid", ContainerExec.DEV_GID, "--mode", "0600"));
    return new PtyMessage.PutStored(upload.id, path);
  }

  /** Discards {@code upload}, cancelled or cut off: nothing of it is kept or pushed. */
  void discard(Upload upload) {
    if (upload != null) {
      close(upload);
      delete(upload.staged);
      give(upload.instanceId, upload.size);
    }
  }

  private void fail(Upload upload, PutRefusal kind, Consumer<PtyMessage> reply) {
    discard(upload);
    reply.accept(new PtyMessage.PutRefused(upload.id, kind, 0));
  }

  /**
   * The session of {@code origin} ended: its uploads leave its container, removed as the dev user,
   * whether this host or the one before it stored them. A container not running then is swept at
   * its next session ({@link #sweep}).
   */
  void ended(PtySession.Origin origin) {
    var instanceId = origin.instanceId();
    var container = target(origin).container();
    synchronized (lock(instanceId)) {
      tallies.remove(instanceId);
      locks.remove(instanceId);
      if (container != null) {
        remove(instanceId, container);
      }
    }
  }

  private Object lock(String instanceId) {
    return locks.computeIfAbsent(instanceId, id -> new Object());
  }

  private void remove(String instanceId, String container) {
    try {
      var removed =
          shell.exec(ContainerExec.asDevUser(container, List.of("rm", "-rf", ROOT + instanceId)));
      if (!removed.ok()) {
        PtySessionHost.log("upload-cleanup-failed", "-", instanceId, "swept at the next session");
      }
    } catch (IOException | TimeoutException e) {
      PtySessionHost.log("upload-cleanup-failed", "-", instanceId, "swept at the next session");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Removes from the container {@code origin}'s session runs in the uploads of every session not in
   * {@code live}, the instance ids of the sessions this host runs: what a session that ended while
   * its container was stopped left.
   */
  void sweep(PtySession.Origin origin, Collection<String> live) {
    var container = target(origin).container();
    if (container == null) {
      return;
    }
    var argv = new ArrayList<>(List.of("sh", "-c", SWEEP, "sail-sweep", ROOT));
    argv.addAll(live);
    try {
      if (!shell.exec(ContainerExec.asDevUser(container, argv)).ok()) {
        PtySessionHost.log("upload-sweep-failed", "-", container, "kept until the next session");
      }
    } catch (IOException | TimeoutException e) {
      PtySessionHost.log("upload-sweep-failed", "-", container, "kept until the next session");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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

  private static void close(Upload upload) {
    try {
      upload.out.close();
    } catch (IOException e) {
      PtySessionHost.log("upload-failed", "-", upload.instanceId, "staged file not closed");
    }
  }

  private static void delete(Path staged) {
    if (staged == null) {
      return;
    }
    try {
      Files.deleteIfExists(staged);
    } catch (IOException e) {
      PtySessionHost.log("upload-failed", "-", "-", "staged file left for the staging tidy");
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("This JVM has no SHA-256.", e);
    }
  }
}
