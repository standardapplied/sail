/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SyncViews {
  private SyncViews() {}

  static Map<String, Object> report(SyncEngine.Report report) {
    var map = new LinkedHashMap<String, Object>();
    map.put("pulled", report.pulled());
    map.put("pushed", report.pushed());
    map.put("merged", report.merged());
    map.put("conflicts", report.conflicts());
    return map;
  }

  static Map<String, Object> round(SyncReport round) {
    var map = new LinkedHashMap<>(report(round.report()));
    map.put("message", round.message());
    map.put("bytes_fetched", round.fetchedBytes());
    map.put("bytes_sent", round.sentBytes());
    map.put("types", round.types().stream().map(SyncViews::type).toList());
    return map;
  }

  public static Map<String, Object> type(SyncSession.TypeReport type) {
    var map = new LinkedHashMap<String, Object>();
    map.put("type", type.type());
    map.putAll(report(type.report()));
    map.put("pages", type.pages());
    map.put("entries", type.entries());
    map.put("skipped", type.skipped());
    map.put("failure", type.failure());
    map.put("bytes_fetched", type.fetchedBytes());
    map.put("bytes_sent", type.sentBytes());
    return map;
  }

  public static Map<String, Object> status(SyncStatus status) {
    var map = new LinkedHashMap<String, Object>();
    map.put("role", status.role());
    map.put("main", status.main());
    map.put("state", status.state());
    map.put("last_attempt_at", status.lastAttemptAt());
    map.put("last_success_at", status.lastSuccessAt());
    map.put("consecutive_failures", status.consecutiveFailures());
    map.put("last_error_kind", status.lastErrorKind());
    map.put("last_error", status.lastError());
    map.put("stale_since", status.staleSince());
    map.put("bytes_fetched", status.fetchedBytes());
    map.put("bytes_sent", status.sentBytes());
    map.put("last_report", status.lastReport() == null ? null : report(status.lastReport()));
    return map;
  }

  static Map<String, Object> conflicts(List<SyncConflicts.Conflict> conflicts) {
    return Map.of("conflicts", conflicts.stream().map(SyncViews::conflict).toList());
  }

  static Map<String, Object> conflict(SyncConflicts.Conflict conflict) {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", conflict.id());
    map.put("entity_type", conflict.entityType());
    map.put("entity_id", conflict.entityId());
    map.put("base_snapshot", conflict.baseSnapshot());
    map.put("local_snapshot", conflict.localSnapshot());
    map.put("remote_snapshot", conflict.remoteSnapshot());
    map.put("fields", conflict.fields());
    map.put("detected_at", conflict.detectedAt());
    map.put("status", conflict.status());
    map.put("resolved_rev", conflict.resolvedRev());
    return map;
  }
}
