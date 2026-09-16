/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.Resolution;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.SyncedEntities;
import java.util.List;
import java.util.Map;

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

  public SyncConflicts.Conflict find(String entityId) {
    var matches = list().stream().filter(c -> c.entityId().equals(entityId)).toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  public SyncConflicts.Conflict resolve(String entityId, Resolution resolution) {
    return db.transaction(
        () -> {
          var conflict = find(entityId);
          if (conflict == null) {
            throw new IllegalArgumentException("No open conflict for '" + entityId + "'.");
          }
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
