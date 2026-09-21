/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.Resolution;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ConflictDetector;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.SyncedEntities;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ConflictOperations {
  private final Sqlite db;
  private final SyncConflicts conflicts;

  public ConflictOperations(Sqlite db) {
    this.db = db;
    this.conflicts = new SyncConflicts(db);
  }

  public List<SyncConflicts.Conflict> list() {
    return conflicts.pending();
  }

  /**
   * The open conflict on {@code entityId}, or {@code null} when there is none. Ids are unique only
   * within a type — a spec's room carries the spec's id — so a blank {@code entityType} is accepted
   * only while the id names a single conflict; several are refused by name, never guessed.
   */
  public SyncConflicts.Conflict find(String entityType, String entityId) {
    if (Strings.isNotBlank(entityType)) {
      return conflicts.pendingFor(entityType, entityId).orElse(null);
    }
    var matches = list().stream().filter(c -> c.entityId().equals(entityId)).toList();
    if (matches.size() > 1) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "'%s' has open conflicts as %s: pass --type"
              .formatted(
                  entityId,
                  matches.stream()
                      .map(SyncConflicts.Conflict::entityType)
                      .collect(Collectors.joining(" and "))));
    }
    return matches.isEmpty() ? null : matches.getFirst();
  }

  /**
   * Settles a conflict on the side the engineer chose. The decision was made looking at the
   * recorded snapshots, so it applies only while the row still is what was shown: a local write
   * since then refuses the resolve untouched, whatever the strategy, until a round re-records it.
   */
  public SyncConflicts.Conflict resolve(String entityType, String entityId, Resolution resolution) {
    return db.transaction(
        () -> {
          var conflict = find(entityType, entityId);
          if (conflict == null) {
            throw new IllegalArgumentException("No open conflict for '" + entityId + "'.");
          }
          requireCurrent(conflict);
          var chosen =
              switch (resolution.strategy()) {
                case MINE -> parse(conflict.localSnapshot());
                case THEIRS -> parse(conflict.remoteSnapshot());
                case MERGE -> {
                  if (!mergeable(conflict)) {
                    throw new IllegalArgumentException(
                        "Field-level --merge isn't available for this conflict; use --mine or --theirs.");
                  }
                  yield ConflictMerge.parseTemplate(resolution.merged());
                }
              };
          var rev =
              SyncedEntities.require(conflict.entityType())
                  .resolver(db)
                  .resolveConflict(conflict.entityId(), chosen, parse(conflict.remoteSnapshot()));
          conflicts.resolve(conflict.id(), rev);
          return new SyncConflicts.Conflict(
              conflict.id(),
              conflict.entityType(),
              conflict.entityId(),
              conflict.baseSnapshot(),
              conflict.localSnapshot(),
              conflict.remoteSnapshot(),
              conflict.fields(),
              conflict.detectedAt(),
              "resolved",
              rev);
        });
  }

  private void requireCurrent(SyncConflicts.Conflict conflict) {
    var store = SyncedEntities.require(conflict.entityType()).store(db);
    var drift =
        ConflictDetector.drift(
            parse(conflict.localSnapshot()),
            store.currentForSync(conflict.entityId()),
            store.latestWinsFields());
    if (!drift.isEmpty()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          """
          '%s' changed on this box after this conflict was recorded (%s).
          Run 'sail sync' to refresh it, then resolve."""
              .formatted(conflict.entityId(), String.join(", ", drift)));
    }
  }

  public static boolean mergeable(SyncConflicts.Conflict conflict) {
    return conflict.entityType().equals("spec")
        && parse(conflict.baseSnapshot()) != null
        && parse(conflict.localSnapshot()) != null
        && parse(conflict.remoteSnapshot()) != null;
  }

  public static Map<String, Object> parse(String snapshot) {
    return snapshot == null || snapshot.isBlank() ? null : YamlUtil.parseMap(snapshot);
  }
}
