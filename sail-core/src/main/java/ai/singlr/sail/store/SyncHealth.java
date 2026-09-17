/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.sync.SyncEngine;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** The local, durable outcome of rounds against each configured peer. */
public final class SyncHealth {
  private final Sqlite db;

  public SyncHealth(Sqlite db) {
    this.db = db;
  }

  public record Health(
      String peer,
      Instant lastAttemptAt,
      Instant lastSuccessAt,
      int consecutiveFailures,
      String lastErrorKind,
      String lastError,
      SyncEngine.Report lastReport,
      String state,
      Instant staleSince) {}

  public Optional<Health> find(String peer) {
    return db.queryOne(
        """
        SELECT peer, last_attempt_at, last_success_at, consecutive_failures,
               last_error_kind, last_error, last_report, state, stale_since
        FROM sync_health WHERE peer = ?""",
        row ->
            new Health(
                row.text(0),
                instant(row.text(1)),
                instant(row.text(2)),
                Math.toIntExact(row.integer(3)),
                row.text(4),
                row.text(5),
                report(row.text(6)),
                row.text(7),
                instant(row.text(8))),
        peer);
  }

  public void begin(String peer, Instant at) {
    db.execute(
        """
        INSERT INTO sync_health (peer, last_attempt_at, state) VALUES (?, ?, 'syncing')
        ON CONFLICT(peer) DO UPDATE SET last_attempt_at = excluded.last_attempt_at, state = 'syncing'""",
        peer,
        at.toString());
  }

  public boolean succeeded(String peer, Instant at, SyncEngine.Report report) {
    return db.transaction(
        () -> {
          var recovered = find(peer).orElseThrow().consecutiveFailures() > 0;
          db.execute(
              """
        UPDATE sync_health SET last_attempt_at = ?, last_success_at = ?, consecutive_failures = 0,
            last_error_kind = NULL, last_error = NULL, last_report = ?, state = 'in_sync',
            stale_since = NULL WHERE peer = ?""",
              at.toString(),
              at.toString(),
              YamlUtil.dumpJson(
                  Map.of(
                      "pulled",
                      report.pulled(),
                      "pushed",
                      report.pushed(),
                      "merged",
                      report.merged(),
                      "conflicts",
                      report.conflicts())),
              peer);
          return recovered;
        });
  }

  public int failed(String peer, Instant at, String kind, String error) {
    return db.transaction(
        () -> {
          db.execute(
              """
        UPDATE sync_health SET last_attempt_at = ?, consecutive_failures = consecutive_failures + 1,
            last_error_kind = ?, last_error = ?, state = 'stale',
            stale_since = coalesce(stale_since, ?) WHERE peer = ?""",
              at.toString(),
              kind,
              error,
              at.toString(),
              peer);
          return find(peer).orElseThrow().consecutiveFailures();
        });
  }

  private static Instant instant(String value) {
    return value == null ? null : Instant.parse(value);
  }

  private static SyncEngine.Report report(String value) {
    if (value == null) return null;
    var map = YamlUtil.parseMap(value);
    return new SyncEngine.Report(
        ((Number) map.get("pulled")).intValue(),
        ((Number) map.get("pushed")).intValue(),
        ((Number) map.get("merged")).intValue(),
        ((Number) map.get("conflicts")).intValue());
  }
}
