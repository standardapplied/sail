/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.sync.StoreReplica;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A row whose head is filed under another project than its last state names: a node adopts main's
 * deletion of a row main had moved, so its tombstone keeps the node's last state, in {@code old},
 * and its head main's project, {@code acme}. A write over it is decided on the one project the head
 * files it under, whose audience the scope rule decides, so an FDE granted {@code acme} alone
 * re-creating it there hears what main answered, never an error.
 */
class FiledElsewhereTest {

  private static final Actor SCOPED =
      new Actor("uday", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
  private static final Actor SCOPED_PUSHER =
      Actor.sync("uday", Role.MEMBER).withScope(ProjectScope.of(List.of("acme")));

  private Sqlite db;
  private SpecStore specs;
  private RoomStore rooms;
  private Map<String, Map<String, Object>> last;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "old", "acme");
    specs = new SpecStore(db);
    rooms = new RoomStore(db);
    Actor.run(Actor.system(), () -> specs.create(spec("old")));
    Actor.run(Actor.system(), () -> rooms.createJournaled(room("old")));
    last = Map.of("s", specs.comparableSnapshot("s"), "r", rooms.comparableSnapshot("r"));
    adoptDeletionFiledUnderAcme(specs, "s");
    adoptDeletionFiledUnderAcme(rooms, "r");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aSpecReCreatedOverItIsRefusedAsMainRefusedIt() {
    var refused =
        assertThrows(WriteRefused.class, () -> Actor.run(SCOPED, () -> specs.create(spec("acme"))));

    assertEquals(
        new Refusal(
            Refusal.Kind.NOT_OWNER,
            "Spec 's' is unassigned; only sail or an admin may change it.",
            "Have an admin change it, or claim it first with --assignee <you>."),
        refused.refusal());
  }

  @Test
  void aRoomReCreatedOverItIsAllowedAsMainAllowedIt() {
    Actor.run(SCOPED, () -> rooms.createJournaled(room("acme")));

    assertEquals("acme", rooms.findById("r").orElseThrow().project());
  }

  @Test
  void mainsCommitOfThePushedReCreationAnswersAsMainAnswered() {
    var spec = pushed(specs, "s");
    var room = pushed(rooms, "r");

    assertEquals(
        "Spec 's' is unassigned; only sail or an admin may change it.",
        assertInstanceOf(PushOutcome.Denied.class, spec).reason());
    assertInstanceOf(PushOutcome.Accepted.class, room);
  }

  private PushOutcome pushed(SyncedStore store, String id) {
    var next = new LinkedHashMap<>(last.get(id));
    next.put("project", "acme");
    next.put(Snapshots.ACTOR, "uday");
    return Actor.call(
        SCOPED_PUSHER,
        () -> store.commitRevision(id, next, store.latestRev(id), store.authority()));
  }

  private void adoptDeletionFiledUnderAcme(SyncedStore store, String id) {
    var replica =
        new StoreReplica(
            "node", store, new ChangeLog(db), new SyncConflicts(db), new SyncState(db));
    Actor.run(Actor.main("root"), () -> replica.adopt(id, null, "9-main", "acme"));
  }

  private static SpecStore.SpecRow spec(String project) {
    return new SpecStore.SpecRow(
        "s",
        project,
        "s",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        null,
        null,
        "uday",
        List.of(),
        List.of());
  }

  private static RoomStore.RoomRow room(String project) {
    return new RoomStore.RoomRow(
        "r",
        project,
        "r",
        "uday",
        null,
        null,
        "uday",
        DateTimeUtils.now(),
        DateTimeUtils.now(),
        null);
  }
}
