/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoomStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RoomStore rooms;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    rooms = new RoomStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static RoomStore.RoomRow room(String id) {
    return new RoomStore.RoomRow(
        id,
        "acme",
        "Auth design",
        "uday",
        "on",
        "[{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}]",
        "uday",
        null,
        null,
        "uday");
  }

  @Test
  void createRoundTripsAndJournalsARevision() {
    rooms.create(room("auth"));

    var found = rooms.findById("auth").orElseThrow();
    assertEquals("acme", found.project());
    assertEquals("Auth design", found.title());
    assertEquals("uday", found.assignee());
    assertEquals("on", found.wake());
    assertTrue(found.roster().contains("claude-code"));
    assertNotNull(found.createdAt());
    assertNotNull(found.updatedAt());
    assertNotNull(rooms.latestRev("auth"));
    assertNull(rooms.baseRevOf("auth"), "a local creation has no synced ancestor");
    assertTrue(rooms.syncEntityIds().contains("auth"));
  }

  @Test
  void createRequiresIdAndTitle() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    " ", "acme", "t", null, null, null, "uday", null, null, "uday")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "id", "acme", "", null, null, null, "uday", null, null, "uday")));
  }

  @Test
  void updateJournalsANewRevisionAndKeepsCreationIdentity() {
    rooms.create(room("auth"));
    var created = rooms.findById("auth").orElseThrow();
    var firstRev = rooms.latestRev("auth");

    rooms.update(
        new RoomStore.RoomRow(
            "auth",
            "acme",
            "Auth design v2",
            "sam",
            "mention",
            null,
            "ignored",
            null,
            null,
            "sam"));

    var updated = rooms.findById("auth").orElseThrow();
    assertEquals("Auth design v2", updated.title());
    assertEquals("sam", updated.assignee());
    assertEquals("mention", updated.wake());
    assertNull(updated.roster());
    assertEquals("sam", updated.updatedBy());
    assertEquals(created.createdBy(), updated.createdBy());
    assertEquals(created.createdAt(), updated.createdAt());
    assertFalse(firstRev.equals(rooms.latestRev("auth")), "an edit mints a new revision");
  }

  @Test
  void applyRevisionAdoptsRemoteStateAsTheSyncedAncestor() {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("project", "acme");
    snapshot.put("title", "Synced room");
    snapshot.put("assignee", "uday");
    snapshot.put("wake", "on");
    snapshot.put("roster", null);
    snapshot.put("created_by", "uday");
    snapshot.put("created_at", "2026-08-01T00:00:00Z");
    snapshot.put(Snapshots.ACTOR, "sam");

    rooms.applyRevision("synced", snapshot, "3-abc");

    var found = rooms.findById("synced").orElseThrow();
    assertEquals("Synced room", found.title());
    assertEquals("2026-08-01T00:00:00Z", found.createdAt(), "adoption preserves creation time");
    assertEquals("sam", found.updatedBy(), "the _actor key resolves into the row's author");
    assertEquals("3-abc", rooms.latestRev("synced"));
    assertEquals("3-abc", rooms.baseRevOf("synced"), "adopting from main sets the merge base");
  }

  @Test
  void commitRevisionRejectsAStaleRev() {
    rooms.create(room("auth"));
    var current = rooms.latestRev("auth");

    var stale = rooms.commitRevision("auth", rooms.comparableSnapshot("auth"), "0-stale");
    assertInstanceOf(PushOutcome.Stale.class, stale);
    assertEquals(current, ((PushOutcome.Stale) stale).currentRev());

    var accepted = rooms.commitRevision("auth", rooms.comparableSnapshot("auth"), current);
    assertInstanceOf(PushOutcome.Accepted.class, accepted);
  }

  @Test
  void aRemoteDeletionTombstonesTheRow() {
    rooms.create(room("auth"));

    rooms.applyRevision("auth", null, "4-gone");

    assertTrue(rooms.findById("auth").isEmpty());
    assertNull(rooms.comparableSnapshot("auth"));
    assertNotNull(rooms.latestRev("auth"), "the tombstone stays journaled");
    assertTrue(rooms.syncEntityIds().contains("auth"), "a tombstoned id remains visible to sync");
  }

  @Test
  void comparableSnapshotCarriesWorkFieldsAndActorButNoTimestampMetadata() {
    rooms.create(room("auth"));

    Map<String, Object> comparable = rooms.comparableSnapshot("auth");

    assertEquals("Auth design", comparable.get("title"));
    assertEquals("on", comparable.get("wake"));
    assertTrue(comparable.containsKey("roster"));
    assertEquals("uday", comparable.get(Snapshots.ACTOR));
    assertFalse(comparable.containsKey("updated_at"));
    assertFalse(comparable.containsKey("updated_by"));
    assertFalse(comparable.containsKey("id"));
  }

  @Test
  void ensureForReturnsTheExistingRoomOrMintsTheIdentityRoom() {
    rooms.create(room("auth"));
    var existing = rooms.ensureFor("auth", "other", "Other title", null, null, "sam");
    assertEquals("Auth design", existing.title(), "an existing room is returned untouched");

    var minted = rooms.ensureFor("fresh", "acme", "Fresh spec", "uday", "on", "uday");
    assertEquals("Fresh spec", minted.title());
    assertEquals("uday", minted.assignee());
    assertEquals("on", minted.wake());
    assertNull(minted.roster(), "a minted room starts with no members");
    assertNotNull(rooms.latestRev("fresh"), "the minted room is journaled for sync");
  }

  @Test
  void listEngagedReturnsOnlyRoomsWithMembers() {
    rooms.create(room("auth"));
    rooms.create(
        new RoomStore.RoomRow(
            "empty", "acme", "Empty room", null, null, null, "uday", null, null, "uday"));

    var engaged = rooms.listEngaged();

    assertEquals(1, engaged.size());
    assertEquals("auth", engaged.getFirst().id());
  }

  @Test
  void listReturnsOnlyTheProjectsRooms() {
    rooms.create(room("auth"));
    rooms.create(
        new RoomStore.RoomRow(
            "other", "beta", "Beta room", null, null, null, "sam", null, null, "sam"));

    var acme = rooms.list("acme");

    assertEquals(1, acme.size());
    assertEquals("auth", acme.getFirst().id());
  }
}
