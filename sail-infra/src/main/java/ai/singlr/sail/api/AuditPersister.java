/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Persists every {@link Event} delivered by the {@link EventBus} as a single JSONL line at the
 * configured path (default: {@code ~/.sail/events.jsonl}) and keeps the most-recent N events in
 * memory so {@code GET /v1/events/recent} can replay without re-reading the file.
 *
 * <p>Writes are best-effort: I/O failures are logged to stderr and swallowed so a missing
 * filesystem can't take the bus down. The recent-buffer is unaffected by write failures.
 */
public final class AuditPersister implements EventSubscriber {

  /** Default capacity of the in-memory recent-events ring. */
  public static final int DEFAULT_RECENT_CAPACITY = 1024;

  private final Path eventsFile;
  private final ArrayDeque<Event> recent;
  private final int recentCapacity;
  private final Object writeLock = new Object();
  private final Object recentLock = new Object();

  /** Persister writing to {@code ~/.sail/events.jsonl} with a 1024-event recent buffer. */
  public static AuditPersister atDefault() {
    return new AuditPersister(defaultEventsFile(), DEFAULT_RECENT_CAPACITY);
  }

  public AuditPersister(Path eventsFile, int recentCapacity) {
    if (eventsFile == null) {
      throw new IllegalArgumentException("eventsFile is required");
    }
    if (recentCapacity <= 0) {
      throw new IllegalArgumentException("recentCapacity must be positive, got " + recentCapacity);
    }
    this.eventsFile = eventsFile;
    this.recentCapacity = recentCapacity;
    this.recent = new ArrayDeque<>(recentCapacity);
  }

  @Override
  public String name() {
    return "audit-persister";
  }

  @Override
  public Predicate<Event> filter() {
    return EventSubscriber.all();
  }

  @Override
  public void onEvent(Event event) {
    rememberRecent(event);
    appendToFile(event);
  }

  /** Returns up to {@code limit} most-recently-observed events, oldest first. */
  public List<Event> recent(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
    synchronized (recentLock) {
      var size = recent.size();
      var n = Math.min(limit, size);
      var out = new ArrayList<Event>(n);
      var iter = recent.iterator();
      for (var skip = size - n; skip > 0; skip--) {
        iter.next();
      }
      for (var i = 0; i < n; i++) {
        out.add(iter.next());
      }
      return List.copyOf(out);
    }
  }

  /** Absolute path of the events log. */
  public Path eventsFilePath() {
    return eventsFile;
  }

  private void rememberRecent(Event event) {
    synchronized (recentLock) {
      if (recent.size() == recentCapacity) {
        recent.removeFirst();
      }
      recent.addLast(event);
    }
  }

  private void appendToFile(Event event) {
    var line = event.toJsonLine() + "\n";
    synchronized (writeLock) {
      try {
        if (eventsFile.getParent() != null) {
          Files.createDirectories(eventsFile.getParent());
        }
        Files.writeString(
            eventsFile,
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        System.err.println(
            "sail audit-persister: failed to append to " + eventsFile + ": " + e.getMessage());
      }
    }
  }

  private static Path defaultEventsFile() {
    return Path.of(System.getProperty("user.home"), ".sail", "events.jsonl");
  }
}
