/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class SyncedEntitiesTest {
  @TempDir Path tempDir;
  @Test
  void everyEntityHasAStoreResolverAndReplicaInDependencyOrder() {
    try (var db = Sqlite.open(tempDir.resolve("test.db"))) {
      new SchemaManager(db).migrate();
      assertEquals(List.of("spec", "room", "file", "project", "run", "review", "message"),
          SyncedEntities.all().stream().map(SyncedEntities.Entity::type).toList());
      for (var entity : SyncedEntities.all()) {
        assertEquals(entity.type(), entity.store(db).entityType());
        assertNotNull(entity.resolver(db));
      }
      assertEquals(SyncedEntities.all().stream().map(SyncedEntities.Entity::type).toList(),
          List.copyOf(SyncedEntities.replicas(db, "node", "owner").keySet()));
    }
  }
}
