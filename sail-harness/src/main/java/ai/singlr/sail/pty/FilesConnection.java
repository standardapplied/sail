/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.pty.PtyMessage.FilesRefusal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One files connection's requests answered in its workspace's container, by {@link FileScripts}: at
 * most {@link #SCRIPTS} scripts at once, at most {@link #READS} of them reads, so a listing always
 * has one (F6); the rest wait in order, reads and the others each in their own line, and are never
 * refused; past {@link #WAITING} the connection's next request waits to be read. The watch is one
 * loop for the connection, started by its first watch set. A script that fails for no reason of its
 * own has the workspace asked again ({@code ended}): one stopped, gone or no longer the person's
 * ends the connection in those words; else the request is refused as failed. {@link #close} ends
 * every script and the loop at once, however the connection ended (F5).
 */
final class FilesConnection {

  static final int SCRIPTS = 4;
  static final int READS = 2;
  static final int WAITING = 64;

  /** The most paths one watch set holds. */
  static final int WATCHED = 1_024;

  /** The most bytes one read asks for: text is shown up to 20 MB. */
  static final long READ_CAP = 20L << 20;

  /** The listing frames' budget of entry bytes, well inside the frame cap. */
  static final int LISTING_BYTES = 512 << 10;

  private record Request(int id, String path, boolean reads, Answer answer, long order) {}

  @FunctionalInterface
  private interface Answer {
    void run(Request request, Running running) throws IOException;
  }

  /** A request under way: the script it runs, once it runs one, and whether it was cancelled. */
  private static final class Running {
    FileScripts.Script script;
    boolean cancelled;
  }

  private final FileScripts files;
  private final String container;
  private final String workspace;
  private final Consumer<PtyMessage> reply;
  private final Supplier<PtyMessage.Err> ended;
  private final Consumer<PtyMessage.Err> end;
  private final ArrayDeque<Request> reads = new ArrayDeque<>();
  private final ArrayDeque<Request> others = new ArrayDeque<>();
  private final Map<Integer, Running> underWay = new HashMap<>();
  private int scripts;
  private int reading;
  private int waiting;
  private long asked;
  private boolean closed;
  private FileScripts.Script watch;

  /**
   * The connection to workspace {@code workspace}, instance {@code container}, answering on {@code
   * reply}: {@code ended} says why the workspace no longer serves it, null while it does, and
   * {@code end} ends the connection with that.
   */
  FilesConnection(
      FileScripts files,
      String container,
      String workspace,
      Consumer<PtyMessage> reply,
      Supplier<PtyMessage.Err> ended,
      Consumer<PtyMessage.Err> end) {
    this.files = files;
    this.container = container;
    this.workspace = workspace;
    this.reply = reply;
    this.ended = ended;
    this.end = end;
  }

  void list(PtyMessage.FilesList m) throws InterruptedException {
    submit(m.id(), m.path(), false, this::listed);
  }

  void stat(PtyMessage.FilesStat m) throws InterruptedException {
    submit(m.id(), m.path(), false, this::statted);
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
        m.id(), m.path(), true, (request, running) -> readOf(request, running, m.offset(), length));
  }

  /** Ends request {@code id}: unanswered from now on, its script ended or never run. */
  synchronized void cancel(PtyMessage.FilesCancel m) {
    if (reads.removeIf(request -> request.id() == m.id())
        | others.removeIf(request -> request.id() == m.id())) {
      waiting--;
      notifyAll();
    }
    var running = underWay.get(m.id());
    if (running != null) {
      running.cancelled = true;
      if (running.script != null) {
        running.script.end();
      }
    }
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
                watched.path() + " is not in this workspace's home, so the watch is as it was."));
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
        if (!field.isEmpty()) {
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
   * Queues {@code request}, its path refused at once when it is not under the home, waiting while
   * {@link #WAITING} are queued already.
   */
  private void submit(int id, String path, boolean isRead, Answer answer)
      throws InterruptedException {
    if (!files.inHome(path)) {
      reply.accept(
          new PtyMessage.FilesRefused(
              id, FilesRefusal.OUTSIDE_HOME, sentence(FilesRefusal.OUTSIDE_HOME, path)));
      return;
    }
    synchronized (this) {
      while (waiting >= WAITING && !closed) {
        wait();
      }
      if (closed) {
        return;
      }
      var request = new Request(id, path, isRead, answer, asked++);
      if (underWay.containsKey(request.id())
          || reads.stream().anyMatch(waits -> waits.id() == request.id())
          || others.stream().anyMatch(waits -> waits.id() == request.id())) {
        reply.accept(
            new PtyMessage.FilesRefused(
                request.id(),
                FilesRefusal.FAILED,
                "Request " + request.id() + " is under way already, so this one was not started."));
        return;
      }
      (request.reads() ? reads : others).addLast(request);
      waiting++;
      dispatch();
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
      var running = new Running();
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
    synchronized (this) {
      if (closed || running.cancelled) {
        return null;
      }
    }
    var started = files.start(container, script, request.path(), args);
    synchronized (this) {
      running.script = started;
      if (closed || running.cancelled) {
        started.end();
        return null;
      }
    }
    return started;
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
   * {@link PtyMessage#FILE_CHUNK}, its last frame held until the version stat-ed after the bytes is
   * the one stat-ed before them, read where the bytes the size promised end (fewer bytes, or more,
   * leave other bytes there, never that version): else the read is answered {@link
   * FilesRefusal#CHANGED}, and the client, which drops what it had of it, reads again. So the bytes
   * a read ends with are always the version it names, as far as a version tells one file's states
   * apart (a rewrite of the same size within one tick of the filesystem's clock is one version).
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
      unless(running, new PtyMessage.FilesData(request.id(), version, chunk, false));
      total += chunk.length;
      chunk = script.bytes((int) Math.min(PtyMessage.FILE_CHUNK, expected - total));
    }
    var after = script.field();
    if (script.status() != 0) {
      fail(request, running);
    } else if (!version.equals(after)) {
      unless(running, refused(request, FilesRefusal.CHANGED));
    } else {
      var eof = offset + expected >= Long.parseLong(size);
      unless(running, new PtyMessage.FilesData(request.id(), version, chunk, eof));
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

  /** {@code request}'s script exited {@code status} unanswered: its own refusal, else a failure. */
  private void refuse(Request request, Running running, int status) {
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

  private PtyMessage.FilesRefused refused(Request request, FilesRefusal kind) {
    return new PtyMessage.FilesRefused(request.id(), kind, sentence(kind, request.path()));
  }

  /**
   * The sentence {@code kind} is said in, naming the path, each byte of it that is no UTF-8 shown
   * as {@code U+FFFD}, and the workspace.
   */
  String sentence(FilesRefusal kind, String named) {
    var path = FileNames.shown(named);
    return switch (kind) {
      case NOT_FOUND -> "No file at " + path + " in " + workspace + ".";
      case NOT_PERMITTED -> "The workspace's user may not open " + path + ".";
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
      case FAILED -> "Sail could not read " + path + " in " + workspace + "; nothing was changed.";
    };
  }

  /**
   * Ends every request, waiting or under way, and the watch, at once; nothing is answered after.
   */
  void close() {
    List<FileScripts.Script> ending = new ArrayList<>();
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      reads.clear();
      others.clear();
      waiting = 0;
      notifyAll();
      for (var running : underWay.values()) {
        if (running.script != null) {
          ending.add(running.script);
        }
      }
      if (watch != null) {
        ending.add(watch);
      }
    }
    ending.forEach(FileScripts.Script::end);
  }
}
