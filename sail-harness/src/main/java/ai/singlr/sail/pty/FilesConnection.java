/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.pty.PtyMessage.FilesRefusal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One files connection's requests answered in its workspace's container, by {@link FileScripts}: at
 * most {@link #SCRIPTS} scripts at once, at most {@link #READS} of them reads, so a listing always
 * has one (F6); the rest wait in order, reads and the others each in their own line, and are never
 * refused; past {@link #WAITING} the connection's next request waits to be read, unless a save of
 * the connection waits for its bytes, which come after it ({@link #WAITING_FOR_A_SAVE}). The watch
 * is one loop for the connection, started by its first watch set. A script that fails for no reason
 * of its own has the workspace asked again ({@code ended}): one stopped, gone or no longer the
 * person's ends the connection in those words; else the request is refused as failed. {@link
 * #close} ends every script and the loop at once, however the connection ended (F5), a write by
 * closing its input so it removes its own temporary.
 *
 * <p>A write (a save, a create, a move, a remove) takes the workspace's write lock, which every
 * connection to the workspace shares, and asks the workspace's decision again under it before it
 * changes anything: a save once its bytes are copied, before its {@code go}; the others before
 * their script starts. A save's bytes stream in without the lock.
 */
final class FilesConnection {

  static final int SCRIPTS = 4;
  static final int READS = 2;
  static final int WAITING = 64;

  /**
   * The most requests that wait while a save of the connection waits for its bytes: its chunks come
   * after them on the wire, so the next request is read past {@link #WAITING}, never held there,
   * until this many wait.
   */
  static final int WAITING_FOR_A_SAVE = 2 * WAITING;

  /** The most paths one watch set holds. */
  static final int WATCHED = 1_024;

  /** The most bytes one read asks for: text is shown up to 20 MB. */
  static final long READ_CAP = 20L << 20;

  /** The listing frames' budget of entry bytes, well inside the frame cap. */
  static final int LISTING_BYTES = 512 << 10;

  /**
   * A request naming {@code paths}, the first the one its answers name and the last a taken name's
   * (a move names two), which Sail could not {@code verb} when it fails; {@code body} is a save's.
   */
  private record Request(
      int id,
      List<String> paths,
      boolean reads,
      String verb,
      Body body,
      Answer answer,
      long order) {
    Request {
      paths = List.copyOf(paths);
    }

    String path() {
      return paths.getFirst();
    }
  }

  /**
   * What a connection's writes are given: the workspace's write lock, shared by every connection to
   * it and fair, so writes wait in order; the temporaries of every save the host runs, each as
   * {@link #saving} keys it, from its {@code ready} until its {@code incus exec} ends; the
   * workspace's decision for this person; how long a save may run after its go; and how long its
   * process is waited for once it is ended.
   */
  record Writes(
      WorkspaceLock lock, Set<String> saving, Gate gate, Duration afterGo, Duration holdBound) {}

  /**
   * A workspace's write lock, which also remembers a save whose process outlived its kill: while
   * {@code held} names that save's temporary and a process in the workspace still holds it open, no
   * write in the workspace is made. The whole workspace, not that file alone: another path can name
   * the same file through a link, so no path can be said safe from that process's rename.
   */
  static final class WorkspaceLock extends ReentrantLock {
    private final Object asking = new Object();
    private volatile String held;
    private boolean asked;

    WorkspaceLock() {
      super(true);
    }

    /** Whether a save's process that outlived its kill may still be writing in the workspace. */
    boolean stuck() {
      return held != null;
    }
  }

  /**
   * How long an ended save's process is waited for, asked every {@link #PROBE_EVERY}: Incus ends a
   * workspace's process within about 1 s of its {@code incus exec} going, so only a process the
   * workspace's disk holds outlasts this.
   */
  static final Duration HOLD_BOUND = Duration.ofSeconds(5);

  private static final Duration PROBE_EVERY = Duration.ofMillis(250);

  /**
   * How long a save's script may run after its go: well above hashing and renaming 20 MiB under the
   * lock (about 50 ms on a devbox, 200 ms on a slow box), so only a hung script meets it.
   */
  static final Duration AFTER_GO = Duration.ofSeconds(10);

  /** The exact shape of a save's temporary, which Sail never makes as a person's name. */
  private static final Pattern TEMPORARY =
      Pattern.compile(Pattern.quote(FileScripts.SAVING) + "[A-Za-z0-9]{10}\\.part");

  /** The workspace's decision for the connection's person, asked again for each write. */
  @FunctionalInterface
  interface Gate {
    /**
     * Why the person may no longer change the workspace's files, in the decision's own words and
     * code; null when they may. Throws when it cannot be asked.
     */
    PtyMessage.Err refusal() throws IOException;
  }

  /**
   * A save's bytes as the client sends them, at most {@link PtyUploads#QUEUE} chunks held until its
   * script takes them, then its SHA-256; {@code broken} says how the client's frames broke it.
   */
  private static final class Body {
    final long size;
    final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    long received;
    byte[] sha256;
    String broken;

    Body(long size) {
      this.size = size;
    }

    boolean whole() {
      return received == size && sha256 != null;
    }
  }

  @FunctionalInterface
  private interface Answer {
    void run(Request request, Running running) throws IOException;
  }

  /** A request under way: the script it runs, once it runs one, and whether it was cancelled. */
  private static final class Running {
    final boolean writes;
    FileScripts.Script script;
    boolean cancelled;
    boolean committed;

    Running(boolean writes) {
      this.writes = writes;
    }

    /**
     * Ends its script: a save's by closing its input, so it removes its own temporary; never a save
     * past its go, which runs to its end under the workspace's lock, bounded by {@link #AFTER_GO}.
     */
    void stop() {
      if (script == null || committed) {
        return;
      }
      if (writes) {
        script.cut();
      } else {
        script.end();
      }
    }
  }

  private final FileScripts files;
  private final String container;
  private final String workspace;
  private final Consumer<PtyMessage> reply;
  private final Supplier<PtyMessage.Err> ended;
  private final Consumer<PtyMessage.Err> end;
  private final Writes writes;
  private final WorkspaceChanges changes;
  private final Map<Integer, Changing> changing = new HashMap<>();
  private final List<Changing> answering = new ArrayList<>();
  private WorkspaceChanges.Answer newest;
  private boolean sending;
  private final ArrayDeque<Request> reads = new ArrayDeque<>();
  private final ArrayDeque<Request> others = new ArrayDeque<>();
  private final Map<Integer, Running> underWay = new HashMap<>();
  private final Map<Integer, Body> bodies = new HashMap<>();
  private int scripts;
  private int reading;
  private int waiting;
  private int hungry;
  private long asked;
  private boolean closed;
  private FileScripts.Script watch;

  /**
   * The connection to workspace {@code workspace}, instance {@code container}, answering on {@code
   * reply}: {@code ended} says why the workspace no longer serves it, null while it does, and
   * {@code end} ends the connection with that; its writes take {@code writes}' lock and gate, and
   * its changes are asked of the workspace's one {@code changes}.
   */
  FilesConnection(
      FileScripts files,
      String container,
      String workspace,
      Consumer<PtyMessage> reply,
      Supplier<PtyMessage.Err> ended,
      Consumer<PtyMessage.Err> end,
      Writes writes,
      WorkspaceChanges changes) {
    this.files = files;
    this.container = container;
    this.workspace = workspace;
    this.reply = reply;
    this.ended = ended;
    this.end = end;
    this.writes = writes;
    this.changes = changes;
  }

  void list(PtyMessage.FilesList m) throws InterruptedException {
    submit(m.id(), List.of(m.path()), "read", false, null, this::listed);
  }

  void stat(PtyMessage.FilesStat m) throws InterruptedException {
    submit(m.id(), List.of(m.path()), "read", false, null, this::statted);
  }

  void read(PtyMessage.FilesRead m) throws InterruptedException {
    if (m.offset() < 0 || m.length() < 0) {
      reply.accept(
          new PtyMessage.FilesRefused(
              m.id(),
              FilesRefusal.FAILED,
              "A read starts at 0 or later and takes 0 bytes or more."));
      return;
    }
    var length = Math.min(m.length(), READ_CAP);
    submit(
        m.id(),
        List.of(m.path()),
        "read",
        true,
        null,
        (request, running) -> readOf(request, running, m.offset(), length));
  }

  /**
   * Saves {@code m}'s bytes, which follow it as chunks, over the file while it is at {@code m}'s
   * base, or as a new file while no name is there: refused at once when it is too large, names no
   * base, or would make a name Sail does not make.
   */
  void write(PtyMessage.FilesWrite m) throws InterruptedException {
    var why =
        m.size() < 0
            ? "A save carries 0 bytes or more."
            : !m.base().equals(PtyMessage.ABSENT) && !DIGEST.matcher(m.base()).matches()
                ? "A save names the content it was based on: the SHA-256 of a whole read, in"
                    + " lowercase hex, or "
                    + PtyMessage.ABSENT
                    + "."
                : m.seen().indexOf('\0') >= 0
                    ? "A save names the version it read its base at, which holds no NUL."
                    : null;
    if (why != null) {
      reply.accept(new PtyMessage.FilesRefused(m.id(), FilesRefusal.FAILED, why));
    } else if (m.size() > READ_CAP) {
      refuseAt(m.id(), FilesRefusal.TOO_LARGE, m.path(), "save");
    } else if (m.base().equals(PtyMessage.ABSENT) && !makes(m.path())) {
      refuseAt(m.id(), FilesRefusal.UNSAFE, m.path(), "save");
    } else {
      submit(
          m.id(),
          List.of(m.path()),
          "save",
          false,
          new Body(m.size()),
          (request, running) -> saved(request, running, m.base(), m.seen()));
    }
  }

  /**
   * Asks what changed since {@code HEAD}: answered by the workspace's next changes run, taking no
   * script of the connection's, but one of its {@link #WAITING} until it is answered.
   */
  void changes(PtyMessage.FilesChanges m) throws InterruptedException {
    Changing waiter;
    synchronized (this) {
      while (waiting >= bound() && !closed) {
        wait();
      }
      if (closed) {
        return;
      }
      if (taken(m.id())) {
        reply.accept(underWay(m.id()));
        return;
      }
      waiter = new Changing(m.id());
      changing.put(m.id(), waiter);
      waiting++;
    }
    changes.ask(waiter);
  }

  /** {@code m}'s path's change against {@code HEAD}: one of the connection's scripts, no read. */
  void diff(PtyMessage.FilesDiff m) throws InterruptedException {
    submit(m.id(), List.of(m.path()), "diff", false, null, this::diffed);
  }

  /** Makes {@code m}'s path anew, a folder or an empty file, never over a name there. */
  void create(PtyMessage.FilesCreate m) throws InterruptedException {
    if (!makes(m.path())) {
      refuseAt(m.id(), FilesRefusal.UNSAFE, m.path(), "create");
      return;
    }
    var kind = m.folder() ? "folder" : "file";
    submit(
        m.id(),
        List.of(m.path()),
        "change",
        false,
        null,
        (request, running) ->
            changed(request, running, FileScripts.CREATE, List.of(m.path(), kind)));
  }

  /** Moves {@code m}'s first path to its second, never over a name there. */
  void move(PtyMessage.FilesMove m) throws InterruptedException {
    if (!makes(m.to())) {
      refuseAt(m.id(), FilesRefusal.UNSAFE, m.to(), "move");
      return;
    }
    submit(
        m.id(),
        List.of(m.from(), m.to()),
        "change",
        false,
        null,
        (request, running) -> changed(request, running, FileScripts.MOVE, request.paths()));
  }

  /** Removes {@code m}'s path, a folder with all it holds. */
  void remove(PtyMessage.FilesRemove m) throws InterruptedException {
    submit(
        m.id(),
        List.of(m.path()),
        "change",
        false,
        null,
        (request, running) -> changed(request, running, FileScripts.REMOVE, request.paths()));
  }

  /**
   * Whether the last name of {@code path} is one Sail makes: UTF-8, no control character, and no
   * save's temporary, which no listing shows and a later write would remove.
   */
  static boolean makes(String path) {
    var name = path.substring(path.lastIndexOf('/') + 1);
    return FileNames.shown(name).equals(name)
        && name.chars().noneMatch(Character::isISOControl)
        && !TEMPORARY.matcher(name).matches();
  }

  /**
   * The next bytes of save {@code m.id()}: held for its script, or the save broken when they are
   * not the next ones, more than it said, or more than it may hold. Bytes of a save no longer under
   * way are dropped: it was answered.
   */
  synchronized void chunk(PtyMessage.PutChunk m) {
    var body = bodies.get(m.id());
    if (body == null || body.broken != null) {
      return;
    }
    if (m.offset() != body.received
        || body.sha256 != null
        || body.received + m.bytes().length > body.size
        || body.chunks.size() >= PtyUploads.QUEUE) {
      body.broken = "its bytes did not arrive in order and within its size";
    } else {
      body.chunks.addLast(m.bytes());
      body.received += m.bytes().length;
    }
    notifyAll();
  }

  /** Save {@code m.id()}'s bytes are all sent, and their SHA-256. */
  synchronized void end(PtyMessage.PutEnd m) {
    var body = bodies.get(m.id());
    if (body == null || body.broken != null) {
      return;
    }
    if (body.sha256 != null || body.received != body.size) {
      body.broken = "it ended before its size, or twice";
    } else {
      body.sha256 = m.sha256().clone();
    }
    notifyAll();
  }

  /** Ends request {@code id}: unanswered from now on, its script ended or never run. */
  synchronized void cancel(PtyMessage.FilesCancel m) {
    if (reads.removeIf(request -> request.id() == m.id())
        | others.removeIf(request -> request.id() == m.id())) {
      waiting--;
      bodies.remove(m.id());
    }
    if (changing.remove(m.id()) != null) {
      waiting--;
    }
    var running = underWay.get(m.id());
    if (running != null) {
      running.cancelled = true;
      running.stop();
    }
    notifyAll();
  }

  /**
   * Replaces the watch set with {@code m}'s, starting the loop with the first one. A set over
   * {@link #WATCHED} paths, or naming a path outside the home, leaves the set as it was and says
   * so.
   */
  void watch(PtyMessage.FilesWatch m) throws IOException {
    if (m.paths().size() > WATCHED) {
      reply.accept(
          new PtyMessage.Err(
              "A watch holds at most "
                  + WATCHED
                  + " paths and this one held "
                  + m.paths().size()
                  + ", so the watch is as it was."));
      return;
    }
    var set = new ByteArrayOutputStream();
    for (var watched : m.paths()) {
      if (!files.inHome(watched.path()) || watched.version().indexOf('\0') >= 0) {
        reply.accept(
            new PtyMessage.Err(
                FileNames.shown(watched.path())
                    + " is not in this workspace's home, so the watch is as it was."));
        return;
      }
      set.write(FileScripts.nul(watched.path()));
      set.write(FileScripts.nul(watched.version()));
    }
    set.write(0);
    FileScripts.Script loop;
    synchronized (this) {
      if (closed) {
        return;
      }
      if (watch == null) {
        try {
          watch = files.start(container, FileScripts.WATCH, null);
        } catch (IOException unstarted) {
          var why = ended.get();
          end.accept(
              why != null
                  ? why
                  : new PtyMessage.Err(
                      "The watch of "
                          + workspace
                          + "'s files could not start; connect again to watch them."));
          return;
        }
        var started = watch;
        Thread.ofVirtual().name("pty-files-watch").start(() -> watching(started));
      }
      loop = watch;
    }
    try {
      loop.input().write(set.toByteArray());
      loop.input().flush();
    } catch (IOException gone) {
      var unused = gone;
    }
  }

  /**
   * Says what {@code loop} reports until it ends; an end no close asked for ends the connection.
   */
  private void watching(FileScripts.Script loop) {
    var changed = new ArrayList<String>();
    try {
      for (var field = loop.field(); field != null; field = loop.field()) {
        if (field.startsWith("-")) {
          reply.accept(
              new PtyMessage.Err(
                  FileNames.shown(field.substring(1))
                      + " leads outside this workspace's home, so it is not watched."));
        } else if (!field.isEmpty()) {
          changed.add(field);
        } else if (changed.isEmpty()) {
          reply.accept(new PtyMessage.FilesTick());
        } else {
          reply.accept(new PtyMessage.FilesChanged(changed));
          changed = new ArrayList<>();
        }
      }
    } catch (IOException | RuntimeException failed) {
      var unused = failed;
    }
    if (!isClosed()) {
      var why = ended.get();
      end.accept(
          why != null
              ? why
              : new PtyMessage.Err(
                  "The watch of " + workspace + "'s files stopped; connect again to watch them."));
    }
  }

  private synchronized boolean isClosed() {
    return closed;
  }

  /**
   * Queues a request, refused at once when a path it names is not under the home, waiting while
   * {@link #WAITING} are queued already; a save's {@code body} is held from now. A failure of it
   * says Sail could not {@code verb} its path.
   */
  private void submit(
      int id, List<String> paths, String verb, boolean isRead, Body body, Answer answer)
      throws InterruptedException {
    for (var path : paths) {
      if (!files.inHome(path)) {
        refuseAt(id, FilesRefusal.OUTSIDE_HOME, path, verb);
        return;
      }
    }
    synchronized (this) {
      while (waiting >= bound() && !closed) {
        wait();
      }
      if (closed) {
        return;
      }
      var request = new Request(id, paths, isRead, verb, body, answer, asked++);
      if (taken(id)) {
        reply.accept(underWay(id));
        return;
      }
      (request.reads() ? reads : others).addLast(request);
      if (body != null) {
        bodies.put(id, body);
      }
      waiting++;
      dispatch();
    }
  }

  /** How many requests may wait before the next is read: more while a save waits for its bytes. */
  private int bound() {
    return hungry > 0 ? WAITING_FOR_A_SAVE : WAITING;
  }

  /** Whether request {@code id} is under way or waiting already. */
  private boolean taken(int id) {
    return underWay.containsKey(id)
        || changing.containsKey(id)
        || reads.stream().anyMatch(waits -> waits.id() == id)
        || others.stream().anyMatch(waits -> waits.id() == id);
  }

  private static PtyMessage.FilesRefused underWay(int id) {
    return new PtyMessage.FilesRefused(
        id,
        FilesRefusal.FAILED,
        "Request " + id + " is under way already, so this one was not started.");
  }

  /** A changes request waiting for its workspace's next run. */
  private final class Changing implements WorkspaceChanges.Waiter {
    final int id;

    Changing(int id) {
      this.id = id;
    }

    @Override
    public boolean gone() {
      return !waits(this);
    }

    @Override
    public void answered(WorkspaceChanges.Answer answer) {
      deliver(this, answer);
    }
  }

  private synchronized boolean waits(Changing waiter) {
    return !closed && changing.get(waiter.id) == waiter;
  }

  /**
   * Sends {@code answer} to {@code waiter} on a thread of the connection's own, never the run's:
   * every request of the connection still to be sent takes the newest answer, which a run that
   * started after each of them made.
   */
  private void deliver(Changing waiter, WorkspaceChanges.Answer answer) {
    synchronized (this) {
      if (!waits(waiter)) {
        return;
      }
      answering.add(waiter);
      newest = answer;
      if (sending) {
        return;
      }
      sending = true;
    }
    Thread.ofVirtual().name("pty-files-changes-send").start(this::sendChanges);
  }

  private void sendChanges() {
    while (true) {
      List<Changing> these;
      WorkspaceChanges.Answer answer;
      synchronized (this) {
        if (answering.isEmpty() || closed) {
          answering.clear();
          sending = false;
          return;
        }
        these = List.copyOf(answering);
        answering.clear();
        answer = newest;
      }
      these.forEach(waiter -> send(waiter, answer));
    }
  }

  /** Sends {@code answer} as request {@code waiter}'s frames, unless it went meanwhile. */
  private void send(Changing waiter, WorkspaceChanges.Answer answer) {
    var home = files.home() + "/workspace";
    try {
      if (answer.overdue() != null) {
        unlessGone(
            waiter,
            new PtyMessage.FilesRefused(
                waiter.id,
                FilesRefusal.FAILED,
                "What changed in "
                    + workspace
                    + " was not said: git did not answer within "
                    + seconds(answer.overdue())
                    + " s, so Sail ended it."));
      } else if (answer.status() == 0) {
        var parts = answer.parts();
        for (var i = 0; i < parts.size() && waits(waiter); i++) {
          var part = parts.get(i);
          unlessGone(
              waiter,
              new PtyMessage.FilesChangeList(
                  waiter.id,
                  answer.tookMillis(),
                  part.root(),
                  part.branch(),
                  part.entries(),
                  part.ignored(),
                  part.truncated(),
                  i < parts.size() - 1));
        }
      } else if (FileScripts.refusalOf(answer.status()) instanceof FilesRefusal kind) {
        unlessGone(
            waiter, new PtyMessage.FilesRefused(waiter.id, kind, sentence(kind, home, "changes")));
      } else {
        var why = waits(waiter) ? ended.get() : null;
        if (why != null) {
          end.accept(why);
        } else {
          unlessGone(
              waiter,
              new PtyMessage.FilesRefused(
                  waiter.id,
                  FilesRefusal.FAILED,
                  "Sail could not say what changed in "
                      + workspace
                      + (answer.repository() == null
                          ? ""
                          : ": git failed in " + FileNames.shown(answer.repository()))
                      + "."));
        }
      }
    } finally {
      synchronized (this) {
        if (changing.remove(waiter.id, waiter)) {
          waiting--;
          notifyAll();
        }
      }
    }
  }

  private void unlessGone(Changing waiter, PtyMessage answer) {
    if (waits(waiter)) {
      reply.accept(answer);
    }
  }

  /**
   * Answers a diff: frames of at most {@link PtyMessage#FILE_CHUNK}, its last held until its script
   * says it ended well; a diff past {@link #READ_CAP} is refused once its bytes pass it.
   */
  private void diffed(Request request, Running running) throws IOException {
    var script = start(request, running, FileScripts.DIFF, Long.toString(READ_CAP));
    if (script == null) {
      return;
    }
    var held = script.bytes(PtyMessage.FILE_CHUNK);
    var total = (long) held.length;
    for (var next = script.bytes(PtyMessage.FILE_CHUNK);
        next.length > 0;
        next = script.bytes(PtyMessage.FILE_CHUNK)) {
      total += next.length;
      if (total > READ_CAP) {
        script.end();
        unless(running, refused(request, FilesRefusal.TOO_LARGE));
        return;
      }
      unless(running, new PtyMessage.FilesDiffData(request.id(), held, false));
      held = next;
    }
    var status = script.status();
    if (status == 0) {
      unless(running, new PtyMessage.FilesDiffData(request.id(), held, true));
    } else {
      refuse(request, running, status);
    }
  }

  /** Starts the oldest waiting request that may run, again until none may. */
  private void dispatch() {
    while (!closed && scripts < SCRIPTS) {
      var read = reading < READS ? reads.peekFirst() : null;
      var other = others.peekFirst();
      if (read == null && other == null) {
        return;
      }
      var next =
          other == null || (read != null && read.order() < other.order())
              ? reads.pollFirst()
              : others.pollFirst();
      waiting--;
      notifyAll();
      scripts++;
      if (next.reads()) {
        reading++;
      }
      var running = new Running(next.body() != null);
      underWay.put(next.id(), running);
      Thread.ofVirtual().name("pty-files").start(() -> run(next, running));
    }
  }

  private void run(Request request, Running running) {
    try {
      request.answer().run(request, running);
    } catch (IOException | RuntimeException failed) {
      fail(request, running);
    } finally {
      synchronized (this) {
        underWay.remove(request.id(), running);
        bodies.remove(request.id(), request.body());
        scripts--;
        if (request.reads()) {
          reading--;
        }
        dispatch();
      }
    }
  }

  /**
   * Starts {@code script} for {@code request}, unless it was cancelled or the connection closed.
   */
  private FileScripts.Script start(Request request, Running running, String script, String... args)
      throws IOException {
    return start(running, script, List.of(request.path()), false, args);
  }

  /**
   * Starts {@code script} with {@code fields} on its input, kept open when {@code keepOpen}, unless
   * the request was cancelled or the connection closed.
   */
  private FileScripts.Script start(
      Running running, String script, List<String> fields, boolean keepOpen, String... args)
      throws IOException {
    synchronized (this) {
      if (closed || running.cancelled) {
        return null;
      }
    }
    var started = files.start(container, script, fields, keepOpen, args);
    synchronized (this) {
      running.script = started;
      if (closed || running.cancelled) {
        running.stop();
        return null;
      }
    }
    return started;
  }

  /**
   * Saves the request's body: its script started with the path and {@code base}, its bytes copied
   * in as they come, each acknowledged, then, once its script says they are on disk with the digest
   * the client sent, the lock taken and the decision asked again before {@code go}. Anything else
   * cuts the script, which writes nothing.
   */
  private void saved(Request request, Running running, String base, String seen)
      throws IOException {
    if (!askedOnce(request, running)) {
      return;
    }
    var body = request.body();
    var script =
        start(
            running,
            FileScripts.WRITE,
            List.of(request.path(), base, seen),
            true,
            "" + body.size,
            "" + READ_CAP);
    if (script == null) {
      return;
    }
    var ready = script.field();
    var temporary = "ready".equals(ready) ? script.field() : null;
    if (temporary == null) {
      unwritten(request, running, script, ready);
      return;
    }
    var mine = saving(temporary);
    writes.saving().add(mine);
    script.onEnd(
        () -> {
          writes.saving().remove(mine);
          wake();
        });
    var sent = 0L;
    try {
      for (var chunk = next(body, running, script);
          chunk != null;
          chunk = next(body, running, script)) {
        script.input().write(chunk);
        script.input().flush();
        sent += chunk.length;
        unless(running, new PtyMessage.PutAck(request.id(), sent));
      }
    } catch (IOException ended) {
      var unused = ended;
    }
    String broken;
    synchronized (this) {
      broken = gone(running) ? "" : body.broken;
    }
    if (broken != null) {
      script.cut();
      if (!broken.isEmpty()) {
        unless(running, failed(request, " was not saved: " + broken + "; nothing was changed."));
      }
      return;
    }
    var copied = script.field();
    if (!"copied".equals(copied)) {
      unwritten(request, running, script, copied);
      return;
    }
    var digest = script.field();
    if (!HexFormat.of().formatHex(body.sha256).equals(digest)) {
      script.cut();
      unless(
          running,
          failed(request, " was not saved: the bytes Sail received are not the bytes sent."));
      return;
    }
    writes.lock().lock();
    try {
      if (!allowed(request, running, script)) {
        return;
      }
      synchronized (this) {
        running.committed = true;
      }
      script.input().write(FileScripts.nul("go"));
      script.input().close();
      var answered = new AtomicBoolean();
      var bound =
          CompletableFuture.runAsync(
              () -> {
                if (answered.compareAndSet(false, true)) {
                  script.end();
                }
              },
              CompletableFuture.delayedExecutor(
                  writes.afterGo().toMillis(), TimeUnit.MILLISECONDS));
      var version = script.field();
      var status = script.status();
      bound.cancel(false);
      if (!answered.compareAndSet(false, true)) {
        try {
          script.kill();
          if (!released(temporary, writes.holdBound())) {
            writes.lock().held = temporary;
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          writes.lock().held = temporary;
        }
        unless(running, overdue(request));
        return;
      }
      if (status == 0 && version != null) {
        unless(running, new PtyMessage.FilesWritten(request.id(), digest, version));
      } else if (status == FileScripts.CONFLICT && version != null) {
        unless(running, new PtyMessage.FilesConflict(request.id(), version));
      } else {
        refuse(request, running, status);
      }
    } finally {
      writes.lock().unlock();
    }
  }

  /**
   * The next bytes of {@code body}, waiting at most {@link PtyUploads#IDLE} for them; null once it
   * is whole, broken, cancelled or closed, once its {@code script} has ended (a stop, an exec cut
   * off), whose status then says why, or when the client sent nothing for that long.
   */
  private synchronized byte[] next(Body body, Running running, FileScripts.Script script) {
    var idle = System.nanoTime() + PtyUploads.IDLE.toNanos();
    while (body.chunks.isEmpty()
        && !body.whole()
        && body.broken == null
        && !gone(running)
        && script.alive()) {
      var left = idle - System.nanoTime();
      if (left <= 0) {
        body.broken = "its bytes stopped coming for " + PtyUploads.IDLE.toMinutes() + " minutes";
        return null;
      }
      hungry++;
      notifyAll();
      try {
        TimeUnit.NANOSECONDS.timedWait(this, left);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        body.broken = "the host ended it";
      } finally {
        hungry--;
      }
    }
    return body.broken != null || gone(running) || !script.alive() ? null : body.chunks.pollFirst();
  }

  /** Wakes a save waiting for its bytes, as its script's end does. */
  private synchronized void wake() {
    notifyAll();
  }

  /**
   * A save's script that ended before it wrote, having printed {@code field}: a conflict found
   * before the copy, a full disk with the bytes it has free, or its own refusal.
   */
  private void unwritten(
      Request request, Running running, FileScripts.Script script, String field) {
    var status = script.status();
    if (status == FileScripts.CONFLICT && field != null) {
      unless(running, new PtyMessage.FilesConflict(request.id(), field));
    } else if (status == FileScripts.NO_SPACE && field != null) {
      var free =
          field.chars().allMatch(Character::isDigit) && !field.isEmpty()
              ? Long.parseLong(field)
              : -1;
      unless(
          running,
          new PtyMessage.FilesRefused(
              request.id(),
              FilesRefusal.NO_SPACE,
              FileNames.shown(request.path())
                  + " was not saved: "
                  + workspace
                  + (free >= 0 && free < request.body().size
                      ? "'s disk has "
                          + free
                          + " bytes free and the save needs "
                          + request.body().size
                      : "'s disk or its quota is full")
                  + "; nothing was changed."));
    } else {
      refuse(request, running, status);
    }
  }

  /**
   * Answers a create, a move or a remove: under the workspace's write lock, the decision asked
   * again, then its one script with {@code fields}.
   */
  private void changed(Request request, Running running, String script, List<String> fields)
      throws IOException {
    if (!askedOnce(request, running)) {
      return;
    }
    writes.lock().lock();
    try {
      if (!allowed(request, running, null)) {
        return;
      }
      var started = start(running, script, fields, false);
      if (started == null) {
        return;
      }
      while (started.field() != null) {
        continue;
      }
      var status = started.status();
      if (status == 0) {
        unless(running, new PtyMessage.FilesDone(request.id()));
      } else {
        refuse(request, running, status);
      }
    } finally {
      writes.lock().unlock();
    }
  }

  /**
   * Whether the request may change the workspace now, asked under the write lock: a refusal ends
   * the connection in the decision's words, and a decision that cannot be asked refuses the request
   * as failed; either way {@code script}, when there is one, is cut and writes nothing.
   */
  private boolean allowed(Request request, Running running, FileScripts.Script script) {
    var held = writes.lock().held;
    if (held != null) {
      try {
        if (released(held, Duration.ZERO)) {
          writes.lock().held = null;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (writes.lock().stuck()) {
      if (script != null) {
        script.cut();
      }
      unless(
          running,
          new PtyMessage.FilesRefused(
              request.id(),
              FilesRefusal.FAILED,
              "A save in "
                  + workspace
                  + " has not ended since Sail ended it: a process there still holds "
                  + FileNames.shown(writes.lock().held)
                  + "; when it ends, or once that file is removed, writes resume. Nothing was"
                  + " changed."));
      return false;
    }
    PtyMessage.Err refusal;
    try {
      refusal = writes.gate().refusal();
    } catch (IOException | RuntimeException unasked) {
      if (script != null) {
        script.cut();
      }
      unless(
          running,
          failed(
              request,
              " was not changed: Sail could not ask whether you may still change "
                  + workspace
                  + "'s files; try again."));
      return false;
    }
    if (refusal != null || gone(running)) {
      if (script != null) {
        script.cut();
      }
      if (refusal != null) {
        end.accept(refusal);
      }
      return false;
    }
    return true;
  }

  /**
   * Whether the workspace was asked for a save stuck there ({@link #stuckIn}), once for its lock
   * and before that lock's first write starts its script: every other write waits for the answer,
   * so no save of this lock has a temporary yet, and the saves this host runs on any lock are left
   * out, so what it finds was left by an earlier host or lock. A workspace that cannot be asked
   * refuses the write, and the next write asks again.
   */
  private boolean askedOnce(Request request, Running running) {
    var lock = writes.lock();
    synchronized (lock.asking) {
      if (lock.asked) {
        return true;
      }
      try {
        lock.held = stuckIn();
        lock.asked = true;
        return true;
      } catch (IOException unasked) {
        unless(
            running,
            new PtyMessage.FilesRefused(
                request.id(),
                FilesRefusal.FAILED,
                "Sail could not ask whether a save in "
                    + workspace
                    + " is still ending, so nothing was changed; try again."));
        return false;
      }
    }
  }

  /** A save's {@code temporary} as {@link Writes#saving} holds it: its container's, then it. */
  private String saving(String temporary) {
    return container + "\0" + temporary;
  }

  /**
   * Whether no process in the workspace holds the save's {@code temporary} open, asked by {@link
   * FileScripts#HELD} now and every {@link #PROBE_EVERY} until {@code bound} has passed: a probe
   * that cannot be asked counts as held, since nothing then says the process is gone.
   */
  private boolean released(String temporary, Duration bound) throws InterruptedException {
    var deadline = System.nanoTime() + bound.toNanos();
    while (true) {
      if (probe(temporary) == 0) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      Thread.sleep(PROBE_EVERY);
    }
  }

  /**
   * A stuck save's temporary the workspace holds, asked by {@link FileScripts#STUCK} before a
   * lock's first write, so a host that started since, or a lock made anew, does not forget it, and
   * none of the saves this host runs there; null when there is none. Throws when the workspace
   * cannot be asked.
   */
  private String stuckIn() throws IOException {
    var mine = saving("");
    var running =
        writes.saving().stream()
            .filter(saving -> saving.startsWith(mine))
            .map(saving -> saving.substring(mine.length()))
            .toList();
    var stuck =
        files.start(
            container, FileScripts.STUCK, running, false, "" + writes.afterGo().toSeconds());
    var held = stuck.field();
    while (stuck.field() != null) {
      continue;
    }
    var status = stuck.status();
    if (status == FileScripts.STILL_HELD && held != null) {
      return held;
    }
    if (status != 0) {
      throw new IOException("the stuck probe exited " + status);
    }
    return null;
  }

  private int probe(String temporary) {
    try {
      var held = files.start(container, FileScripts.HELD, List.of(temporary), false);
      while (held.field() != null) {
        continue;
      }
      return held.status();
    } catch (IOException unasked) {
      return FileScripts.STILL_HELD;
    }
  }

  /**
   * A save whose script did not end within {@link Writes#afterGo} and was ended: it may have
   * renamed before it was, so the file holds either version, and the person reads it again.
   */
  private PtyMessage.FilesRefused overdue(Request request) {
    return new PtyMessage.FilesRefused(
        request.id(),
        FilesRefusal.FAILED,
        "Sail could not tell whether "
            + FileNames.shown(request.path())
            + " was saved: its save did not end within "
            + seconds(writes.afterGo())
            + " s and was ended; the file holds its old or its new bytes; read it again.");
  }

  /**
   * {@code bound} in seconds as a sentence says them: whole when they are, else with a fraction.
   */
  private static String seconds(Duration bound) {
    var millis = bound.toMillis();
    return millis % 1000 == 0 ? Long.toString(millis / 1000) : Double.toString(millis / 1000.0);
  }

  private PtyMessage.FilesRefused failed(Request request, String why) {
    return new PtyMessage.FilesRefused(
        request.id(), FilesRefusal.FAILED, FileNames.shown(request.path()) + why);
  }

  private void listed(Request request, Running running) throws IOException {
    var script = start(request, running, FileScripts.LIST);
    if (script == null) {
      return;
    }
    var version = script.field();
    if (version == null) {
      refuse(request, running, script.status());
      return;
    }
    var page = new ArrayList<PtyMessage.FileEntry>();
    var bytes = 0;
    var count = 0;
    var truncated = false;
    for (var entry = script.entry(); entry != null; entry = script.entry()) {
      if (count == PtyMessage.LISTING_CAP) {
        truncated = true;
        script.end();
        break;
      }
      count++;
      page.add(entry);
      bytes += entry.name().length() * 3 + 64;
      if (bytes >= LISTING_BYTES) {
        unless(running, new PtyMessage.FilesListing(request.id(), version, page, true, false));
        page = new ArrayList<>();
        bytes = 0;
      }
    }
    if (!truncated && script.status() != 0) {
      fail(request, running);
      return;
    }
    unless(running, new PtyMessage.FilesListing(request.id(), version, page, false, truncated));
  }

  private void statted(Request request, Running running) throws IOException {
    var script = start(request, running, FileScripts.STAT);
    if (script == null) {
      return;
    }
    var version = script.field();
    var entry = version == null ? null : script.entry();
    var status = script.status();
    if (entry != null && status == 0) {
      unless(running, new PtyMessage.FilesEntry(request.id(), entry, version));
    } else {
      refuse(request, running, status);
    }
  }

  /**
   * Reads what the file holds from {@code offset}, at most {@code length} bytes, in frames of
   * {@link PtyMessage#FILE_CHUNK}, its last frame held until the read's three proofs agree: the
   * SHA-256 of the bytes it sent is the range's hashed again from the file after them, and the
   * version stat-ed after is the one stat-ed before, read where the bytes the size promised end
   * (fewer bytes, or more, leave other bytes there). Then the last frame carries that digest, the
   * content version of what was read; else the read is answered {@link FilesRefusal#CHANGED}, and
   * the client, which drops what it had of it, reads again. A read spliced across a write would
   * need the same splice twice, so no filesystem clock's coarseness lets one through.
   */
  private void readOf(Request request, Running running, long offset, long length)
      throws IOException {
    var script =
        start(request, running, FileScripts.READ, Long.toString(offset), Long.toString(length));
    if (script == null) {
      return;
    }
    var version = script.field();
    var size = version == null ? null : script.field();
    if (size == null) {
      refuse(request, running, script.status());
      return;
    }
    var expected = Math.max(0, Math.min(length, Long.parseLong(size) - offset));
    var total = 0L;
    var chunk = script.bytes((int) Math.min(PtyMessage.FILE_CHUNK, expected));
    while (total + chunk.length < expected && chunk.length > 0) {
      unless(running, new PtyMessage.FilesData(request.id(), version, chunk, false, false, ""));
      total += chunk.length;
      chunk = script.bytes((int) Math.min(PtyMessage.FILE_CHUNK, expected - total));
    }
    var sent = script.field();
    var again = script.field();
    var after = script.field();
    if (script.status() != 0) {
      fail(request, running);
    } else if (sent == null || !sent.equals(again) || !version.equals(after)) {
      unless(running, refused(request, FilesRefusal.CHANGED));
    } else if (!DIGEST.matcher(sent).matches()) {
      fail(request, running);
    } else {
      var eof = offset + expected >= Long.parseLong(size);
      unless(
          running,
          new PtyMessage.FilesData(request.id(), version, chunk, eof, offset == 0 && eof, sent));
    }
  }

  /** Answers {@code answer} unless the request was cancelled or the connection closed. */
  private void unless(Running running, PtyMessage answer) {
    if (!gone(running)) {
      reply.accept(answer);
    }
  }

  private synchronized boolean gone(Running running) {
    return closed || running.cancelled;
  }

  /** A digest as a read's proof must be one: a SHA-256 in lowercase hex, nothing else. */
  private static final java.util.regex.Pattern DIGEST =
      java.util.regex.Pattern.compile("[0-9a-f]{64}");

  /** {@code request}'s script exited {@code status} unanswered: its own refusal, else a failure. */
  private void refuse(Request request, Running running, int status) {
    if (status == FileScripts.BAD_DIGEST) {
      unless(
          running,
          new PtyMessage.FilesRefused(
              request.id(),
              FilesRefusal.FAILED,
              "Sail could not check "
                  + FileNames.shown(request.path())
                  + "'s content in "
                  + workspace
                  + ": its digest is no SHA-256; nothing was changed."));
      return;
    }
    if (status == FileScripts.NO_SHA256) {
      unless(
          running,
          new PtyMessage.FilesRefused(
              request.id(),
              FilesRefusal.FAILED,
              workspace
                  + " has no sha256sum, so Sail cannot prove what it reads of "
                  + FileNames.shown(request.path())
                  + "; nothing was changed."));
      return;
    }
    if (status == FileScripts.FOLDER_GONE) {
      var path = request.paths().getLast();
      unless(
          running,
          new PtyMessage.FilesRefused(
              request.id(),
              FilesRefusal.NOT_FOUND,
              "The folder "
                  + FileNames.shown(path.substring(0, path.lastIndexOf('/')))
                  + " is gone from "
                  + workspace
                  + ", so "
                  + FileNames.shown(path)
                  + " was not made."));
      return;
    }
    if (status == FileScripts.OTHER_DISK) {
      unless(
          running,
          failed(
              request,
              " is on another disk than "
                  + FileNames.shown(request.paths().getLast())
                  + " would be, so Sail does not move it; nothing was changed."));
      return;
    }
    var kind = FileScripts.refusalOf(status);
    if (kind == null) {
      fail(request, running);
    } else {
      unless(running, refused(request, kind));
    }
  }

  /**
   * {@code request} failed for no reason of its own: the connection is ended when the workspace no
   * longer serves it, else the request is refused as failed. Nothing for one cancelled or closed.
   */
  private void fail(Request request, Running running) {
    if (gone(running)) {
      return;
    }
    var why = ended.get();
    if (why != null) {
      end.accept(why);
    } else {
      unless(running, refused(request, FilesRefusal.FAILED));
    }
  }

  /** {@code request} refused as {@code kind}, naming its path, or a move's taken name. */
  private PtyMessage.FilesRefused refused(Request request, FilesRefusal kind) {
    var named = kind == FilesRefusal.EXISTS ? request.paths().getLast() : request.path();
    return new PtyMessage.FilesRefused(request.id(), kind, sentence(kind, named, request.verb()));
  }

  private void refuseAt(int id, FilesRefusal kind, String path, String verb) {
    reply.accept(new PtyMessage.FilesRefused(id, kind, sentence(kind, path, verb)));
  }

  /**
   * The sentence {@code kind} is said in, naming the path, each byte of it that is no UTF-8 shown
   * as {@code U+FFFD}, and the workspace; a failure says what Sail could not {@code verb}.
   */
  String sentence(FilesRefusal kind, String named, String verb) {
    var path = FileNames.shown(named);
    return switch (kind) {
      case NOT_FOUND -> "No file at " + path + " in " + workspace + ".";
      case NOT_PERMITTED ->
          "The workspace's user may not "
              + ("read".equals(verb) ? "open " : "change ")
              + path
              + ".";
      case OUTSIDE_HOME ->
          path
              + " is outside "
              + workspace
              + "'s home, "
              + files.home()
              + "; Sail does not go there.";
      case IS_FOLDER -> path + " is a folder, so it is listed, not read.";
      case NOT_A_FOLDER -> path + " is not a folder, so it has nothing to list.";
      case NOT_A_FILE ->
          path + " is not a file (a pipe, a socket or a device), so Sail does not read it.";
      case LOOP -> path + " leads back to itself.";
      case CHANGED -> path + " changed while it was read; read it again.";
      case EXISTS -> path + " exists already, so nothing was replaced.";
      case NO_SPACE -> workspace + "'s disk is full, so " + path + " was not saved.";
      case TOO_LARGE ->
          "diff".equals(verb)
              ? "The change to " + path + " is over the 20 MB Sail shows, so it is not shown."
              : path + " is over the 20 MB Sail reads or saves whole, so it was not saved.";
      case NO_GIT -> workspace + " has no git, so Sail cannot say what changed there.";
      case NO_REPOSITORY ->
          "diff".equals(verb)
              ? path + " is in no git repository, so it has no change to show."
              : "No git repository is in "
                  + path
                  + ", so "
                  + workspace
                  + " has no changes to show.";
      case BINARY -> path + " is binary, so its change is not shown as text.";
      case PARTLY_REMOVED ->
          "Some of " + path + " could not be removed; what is left is still there.";
      case UNSAFE ->
          "Sail does not make "
              + path
              + ": a name it makes is UTF-8 with no control character, and a folder never moves"
              + " into itself.";
      case FAILED ->
          "Sail could not " + verb + " " + path + " in " + workspace + "; nothing was changed.";
    };
  }

  /**
   * Ends every request, waiting or under way, and the watch, at once; nothing is answered after.
   */
  void close() {
    List<FileScripts.Script> ending = new ArrayList<>();
    List<Running> stopping = new ArrayList<>();
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      reads.clear();
      others.clear();
      waiting = 0;
      notifyAll();
      bodies.clear();
      changing.clear();
      answering.clear();
      stopping.addAll(underWay.values());
      if (watch != null) {
        ending.add(watch);
      }
    }
    stopping.forEach(Running::stop);
    ending.forEach(FileScripts.Script::end);
  }
}
