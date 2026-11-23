/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.pty.PtyMessage.Change;
import ai.singlr.sail.pty.PtyMessage.ChangeState;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What changed since {@code HEAD} in one workspace, asked by every files connection to it and
 * answered by one {@link FileScripts#CHANGES} run at a time (F12, M67): a request that comes while
 * one runs starts nothing and waits, and every request waiting is answered by the one next run. A
 * run past its patience is ended. An answer is made whole before any of it is sent, at most {@link
 * #ANSWER_BYTES} of paths, so each connection sends its own at its own pace, and one slow to read
 * never holds another's.
 */
final class WorkspaceChanges {

  /** How long a run may take before it is ended. */
  static final Duration PATIENCE = Duration.ofSeconds(30);

  /** The most bytes of paths one answer holds; lists past it are cut and say so. */
  static final int ANSWER_BYTES = 4 << 20;

  /** A request waiting for the next run. */
  interface Waiter {
    /** Whether it no longer wants an answer: cancelled, or its connection closed. */
    boolean gone();

    /** Takes the answer of the run it waited for, without waiting on its connection. */
    void answered(Answer answer);
  }

  /**
   * What a run found: each repository's parts, each a frame's worth, when its {@code status} is 0;
   * else its exit status, a refusal of the scripts' own or a failure, with the {@code repository}
   * it failed in, if any. {@code overdue} is the patience it was ended at, null when it ended
   * itself.
   */
  record Answer(
      List<Part> parts, long tookMillis, int status, Duration overdue, String repository) {
    Answer {
      parts = List.copyOf(parts);
    }
  }

  /**
   * One frame's worth of a repository's changes; {@code truncated} on each of them when cut, and
   * {@code failure}, git's own first line, for a repository git refused, which lists nothing.
   */
  record Part(
      String root,
      String branch,
      String failure,
      List<Change> entries,
      List<String> ignored,
      boolean truncated) {
    Part {
      entries = List.copyOf(entries);
      ignored = List.copyOf(ignored);
    }
  }

  private final FileScripts files;
  private final String container;
  private final Duration patience;
  private final Runnable idle;
  private List<Waiter> next = new ArrayList<>();
  private boolean running;

  /**
   * The changes of the workspace in {@code container}, each run given {@code patience}; {@code
   * idle} is told each time the last run ends with no request waiting.
   */
  WorkspaceChanges(FileScripts files, String container, Duration patience, Runnable idle) {
    this.files = files;
    this.container = container;
    this.patience = patience;
    this.idle = idle;
  }

  /** How long a run, and a diff of the workspace, may take before it is ended. */
  Duration patience() {
    return patience;
  }

  /** Has {@code waiter} answered by the next run, starting it now when none runs. */
  void ask(Waiter waiter) {
    synchronized (this) {
      next.add(waiter);
      if (running) {
        return;
      }
      running = true;
    }
    Thread.ofVirtual().name("pty-files-changes").start(this::runs);
  }

  /** Whether no run is under way and no request waits. */
  synchronized boolean idle() {
    return !running && next.isEmpty();
  }

  private void runs() {
    while (true) {
      List<Waiter> these;
      synchronized (this) {
        next.removeIf(Waiter::gone);
        if (next.isEmpty()) {
          running = false;
          break;
        }
        these = next;
        next = new ArrayList<>();
      }
      var answer = run();
      these.forEach(waiter -> waiter.answered(answer));
    }
    idle.run();
  }

  private Answer run() {
    var started = System.nanoTime();
    FileScripts.Script script;
    try {
      script = files.start(container, FileScripts.CHANGES, List.of(), false);
    } catch (IOException unstarted) {
      return new Answer(List.of(), 0, -1, null, null);
    }
    var overdue = new AtomicBoolean();
    var bound =
        CompletableFuture.runAsync(
            () -> {
              overdue.set(true);
              script.end();
            },
            CompletableFuture.delayedExecutor(patience.toMillis(), TimeUnit.MILLISECONDS));
    var reading = new Reading();
    try {
      reading.read(script);
    } catch (IOException | RuntimeException broken) {
      script.end();
      reading.failed = true;
    } finally {
      bound.cancel(false);
    }
    var status = reading.failed ? -1 : script.status();
    script.close();
    var took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    if (overdue.get()) {
      return new Answer(List.of(), took, -1, patience, reading.root);
    }
    return status == 0
        ? new Answer(reading.parts, took, 0, null, null)
        : new Answer(List.of(), took, status, null, reading.root);
  }

  /** One run's output read: its repositories' parts, and the repository it last began. */
  private static final class Reading {
    final List<Part> parts = new ArrayList<>();
    int budget = ANSWER_BYTES;
    String root;
    boolean failed;

    void read(FileScripts.Script script) throws IOException {
      for (var field = script.field(); field != null; field = script.field()) {
        if (!field.equals("repo")) {
          throw new IOException("a changes answer named no repository");
        }
        repository(script);
      }
    }

    private void repository(FileScripts.Script script) throws IOException {
      root = need(script.field());
      var branch = "";
      var entries = new ArrayList<Change>();
      var ignored = new ArrayList<String>();
      var truncated = false;
      for (var record = need(script.field());
          !record.equals("end");
          record = need(script.field())) {
        if (record.equals("failed")) {
          parts.add(new Part(root, "", need(script.field()), List.of(), List.of(), false));
          return;
        }
        if (record.startsWith("# branch.head ")) {
          branch = record.substring("# branch.head ".length());
          continue;
        }
        if (record.startsWith("# ")) {
          continue;
        }
        if (record.startsWith("! ")) {
          var path = absolute(record.substring(2));
          truncated |= !kept(ignored, path, path.length());
          continue;
        }
        var change = change(record, script);
        if (change != null) {
          truncated |= !kept(entries, change, change.path().length() + change.from().length());
        }
      }
      parts.addAll(pieces(root, branch, entries, ignored, truncated));
    }

    /** Keeps {@code item} unless its list holds {@code CHANGES_CAP} or the answer is full. */
    private <T> boolean kept(List<T> list, T item, int chars) {
      var cost = chars * 3 + 16;
      if (list.size() >= PtyMessage.CHANGES_CAP || cost > budget) {
        return false;
      }
      budget -= cost;
      list.add(item);
      return true;
    }

    /** The change a porcelain v2 record says, null for one that is no change against HEAD. */
    private Change change(String record, FileScripts.Script script) throws IOException {
      if (record.startsWith("? ")) {
        return new Change(absolute(record.substring(2)), ChangeState.UNTRACKED, "");
      }
      if (record.startsWith("u ")) {
        return new Change(absolute(field(record, 11)), ChangeState.CONFLICTED, "");
      }
      if (record.startsWith("2 ")) {
        var path = absolute(field(record, 10));
        var from = absolute(need(script.field()));
        var xy = xy(record);
        if (xy.charAt(1) == 'D') {
          return new Change(from, ChangeState.DELETED, "");
        }
        return xy.charAt(0) == 'R' || xy.charAt(1) == 'R'
            ? new Change(path, ChangeState.RENAMED, from)
            : new Change(path, ChangeState.ADDED, "");
      }
      if (record.startsWith("1 ")) {
        var xy = xy(record);
        var path = absolute(field(record, 9));
        if (xy.charAt(0) == 'A' && xy.charAt(1) == 'D') {
          return null;
        }
        if (xy.charAt(0) == 'D' || xy.charAt(1) == 'D') {
          return new Change(path, ChangeState.DELETED, "");
        }
        return new Change(path, xy.charAt(0) == 'A' ? ChangeState.ADDED : ChangeState.MODIFIED, "");
      }
      throw new IOException("a changes answer held a record of no kind git writes");
    }

    /** The last of {@code count} space-separated fields of {@code record}, its path, whole. */
    private static String field(String record, int count) throws IOException {
      var fields = record.split(" ", count);
      if (fields.length < count) {
        throw new IOException("a changes record was short");
      }
      return fields[count - 1];
    }

    /** A record's two letters: its index's state against HEAD, then its work tree's. */
    private static String xy(String record) throws IOException {
      if (record.length() < 4) {
        throw new IOException("a changes record was short");
      }
      return record.substring(2, 4);
    }

    private String absolute(String relative) {
      var path = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
      return root + "/" + path;
    }

    private static String need(String field) throws IOException {
      if (field == null) {
        throw new IOException("a changes answer ended inside a repository");
      }
      return field;
    }
  }

  /**
   * A repository's changes in frames of at most {@link FilesConnection#LISTING_BYTES} of paths, one
   * at least; each says {@code truncated} when its lists were cut.
   */
  static List<Part> pieces(
      String root, String branch, List<Change> entries, List<String> ignored, boolean truncated) {
    var parts = new ArrayList<Part>();
    var changes = new ArrayList<Change>();
    var matched = new ArrayList<String>();
    var bytes = 0;
    for (var change : entries) {
      changes.add(change);
      bytes += (change.path().length() + change.from().length()) * 3 + 16;
      if (bytes >= FilesConnection.LISTING_BYTES) {
        parts.add(new Part(root, branch, "", changes, matched, truncated));
        changes = new ArrayList<>();
        bytes = 0;
      }
    }
    for (var path : ignored) {
      matched.add(path);
      bytes += path.length() * 3 + 16;
      if (bytes >= FilesConnection.LISTING_BYTES) {
        parts.add(new Part(root, branch, "", changes, matched, truncated));
        changes = new ArrayList<>();
        matched = new ArrayList<>();
        bytes = 0;
      }
    }
    if (parts.isEmpty() || !changes.isEmpty() || !matched.isEmpty()) {
      parts.add(new Part(root, branch, "", changes, matched, truncated));
    }
    return parts;
  }
}
