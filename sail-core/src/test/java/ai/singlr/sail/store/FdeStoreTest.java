/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FdeStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new FdeStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void addAssignsGeneratedIdAndActiveStatus() {
    var fde = store.add("uday", "Uday Chandra", "uday@singlr.ai");
    assertTrue(fde.id().startsWith("fde_"));
    assertEquals("uday", fde.handle());
    assertEquals("Uday Chandra", fde.displayName());
    assertEquals("uday@singlr.ai", fde.email());
    assertEquals("active", fde.status());
  }

  @Test
  void byHandleAndByIdResolveTheSameRecord() {
    var added = store.add("nova", null, null);
    assertEquals(added.id(), store.byHandle("nova").orElseThrow().id());
    assertEquals("nova", store.byId(added.id()).orElseThrow().handle());
  }

  @Test
  void lookupsReturnEmptyWhenAbsent() {
    assertTrue(store.byHandle("ghost").isEmpty());
    assertTrue(store.byId("fde_missing").isEmpty());
  }

  @Test
  void listIsOrderedByHandle() {
    store.add("zara", null, null);
    store.add("amir", null, null);
    var handles = store.list().stream().map(FdeStore.Fde::handle).toList();
    assertEquals(java.util.List.of("amir", "zara"), handles);
  }

  @Test
  void duplicateHandleIsRejected() {
    store.add("dup", null, null);
    assertThrows(SqliteException.class, () -> store.add("dup", null, null));
  }
}
