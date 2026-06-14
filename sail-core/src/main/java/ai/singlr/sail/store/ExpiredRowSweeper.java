/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically deletes expired credential rows — login sessions, enrollment tickets, WebAuthn
 * challenges, and expiring API tokens — that were never touched again after expiry. Expiry is
 * already enforced on read (each store prunes the row it looks up), so this is purely housekeeping
 * to stop expired-but-untouched rows accumulating; a missed sweep is harmless.
 *
 * <p>Each sweep runs on its <b>own</b> short-lived connection rather than sharing the server's
 * connection: the server's {@link Sqlite} uses a single connection with {@code BEGIN}/{@code
 * COMMIT} transactions, and a delete issued onto it from another thread could land inside an
 * in-flight request transaction. A separate connection lets SQLite's own file locking serialize the
 * writes, and a transient lock contention just fails this sweep — the next one cleans up.
 */
public final class ExpiredRowSweeper implements AutoCloseable {

  /** Default cadence: hourly is far finer than any credential lifetime, yet negligible load. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

  private final Path dbPath;
  private final ScheduledExecutorService scheduler;

  public ExpiredRowSweeper(Path dbPath) {
    this.dbPath = dbPath;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sail-sweep-", 0).factory());
  }

  /** Starts the periodic sweep at the default interval. */
  public void start() {
    start(DEFAULT_INTERVAL);
  }

  public void start(Duration interval) {
    var seconds = Math.max(1, interval.toSeconds());
    scheduler.scheduleAtFixedRate(this::sweepQuietly, seconds, seconds, TimeUnit.SECONDS);
  }

  /**
   * Opens a dedicated connection, sweeps once, and never throws — failures are logged and retried.
   */
  void sweepQuietly() {
    try (var db = Sqlite.open(dbPath)) {
      sweep(db);
    } catch (Exception e) {
      System.err.println("sail sweep: could not prune expired rows (" + e.getMessage() + ").");
    }
  }

  /**
   * Deletes every row whose expiry has passed; returns how many were removed. Visible for tests.
   */
  static int sweep(Sqlite db) {
    var now = Instant.now().toString();
    var deleted = 0;
    db.execute("DELETE FROM sessions WHERE expires_at < ?", now);
    deleted += db.changes();
    db.execute("DELETE FROM webauthn_challenges WHERE expires_at < ?", now);
    deleted += db.changes();
    db.execute("DELETE FROM enrollment_tickets WHERE expires_at < ?", now);
    deleted += db.changes();
    db.execute("DELETE FROM api_tokens WHERE expires_at IS NOT NULL AND expires_at < ?", now);
    deleted += db.changes();
    return deleted;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
