/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a prune takes with it, declared once, and how main and a node apply it. */
@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class ErasureTest {

  private Sqlite db;
  private SpecStore specs;
  private RoomStore rooms;
  private MessageStore messages;
  private RunStore runs;
  private Erasure erasure;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    rooms = new RoomStore(db);
    messages = new MessageStore(db);
    runs = new RunStore(db);
    erasure = new Erasure(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aSpecTakesItsRunsReviewsIdentityRoomAndThatRoomsMessagesAndRuns() {
    spec("old", null);
    room("old", "proj");
    var first = messages.append("old", "uday", "first", null);
    var reply = messages.append("old", "uday", "reply", first.id());
    var build = run("proj", "old", null);
    var roomRun = run("proj", null, "old");
    var review = new ReviewStore(db).createReview("old", 1);

    var closure = erasure.closure(List.of(spec("old")));

    assertEquals(
        Set.of(
            "spec:old",
            "run:" + build,
            "review:" + review,
            "room:old",
            "message:" + first.id(),
            "message:" + reply.id(),
            "run:" + roomRun),
        names(closure));
    assertEquals(new Erasure.Target("spec", "old"), closure.getFirst(), "the root comes first");
  }

  @Test
  void aRoomASpecWasBornIntoOutlivesIt() {
    room("shared", "proj");
    specs.create(row("child", "proj").withRoomId("shared"));
    messages.append("shared", "uday", "the room's own talk", null);

    assertEquals(Set.of("spec:child"), names(erasure.closure(List.of(spec("child")))));
  }

  @Test
  void aSpecsIdentityRoomGoesWithItThoughNoRoomRowWasEverMinted() {
    spec("old", null);
    var posted = messages.append("old", "uday", "talk in a room with no row", null);

    prune(spec("old"), "uday");

    assertTrue(messages.findById(posted.id()).isEmpty());
    assertTrue(erasure.isErased(new Erasure.Target("room", "old")));
    assertTrue(erasure.isErased(new Erasure.Target("message", posted.id())));
  }

  @Test
  void aRoomSharingTheIdOfASpecThisBoxNeverHeldIsNotItsToTake() {
    room("lobby", "proj");
    messages.append("lobby", "uday", "the lobby's own talk", null);

    assertEquals(Set.of("spec:lobby"), names(erasure.closure(List.of(spec("lobby")))));
  }

  @Test
  void aMessageTakesEveryReplyDownItsThread() {
    room("lobby", "proj");
    var first = messages.append("lobby", "uday", "first", null);
    var reply = messages.append("lobby", "uday", "reply", first.id());
    var nested = messages.append("lobby", "uday", "nested", reply.id());
    var aside = messages.append("lobby", "uday", "aside", null);

    var closure = names(erasure.closure(List.of(new Erasure.Target("message", first.id()))));

    assertEquals(
        Set.of("message:" + first.id(), "message:" + reply.id(), "message:" + nested.id()),
        closure);
    assertFalse(closure.contains("message:" + aside.id()));
  }

  @Test
  void theJournalRefusesEveryWriteThatWouldBelongToAnErasedEntityAndNothingElse() {
    spec("old", null);
    spec("deleted", null);
    specs.delete("deleted");
    room("lobby", "proj");
    var first = messages.append("lobby", "uday", "first", null);
    prune(spec("old"), "uday");
    erasure.erase(List.of(new Erasure.Target("message", first.id())), "local");
    erasure.erase(List.of(new Erasure.Target("project", "gone")), "local");

    var run = assertThrows(ChangeLog.Pruned.class, () -> run("proj", "old", null));
    assertTrue(
        run.getMessage().contains("belongs to spec 'old', which was pruned"), run.getMessage());
    assertThrows(ChangeLog.Pruned.class, () -> messages.append("old", "uday", "late", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> messages.append("lobby", "uday", "reply", first.id()));
    assertThrows(ChangeLog.Pruned.class, () -> specs.create(row("fresh", "gone")));
    assertThrows(ChangeLog.Pruned.class, () -> room("fresh-room", "gone"));
    assertThrows(
        ChangeLog.Pruned.class,
        () -> ContentFixtures.put(new FileStore(db), "gone", "notes.md", "notes"));

    run("proj", "deleted", null);
    new ReviewStore(db).createReview("unseen", 1);
    messages.append("lobby", "uday", "fresh talk", null);
    assertEquals(0, count("SELECT count(*) FROM specs WHERE id = 'fresh'"));
  }

  @Test
  void aRoomOtherSpecsWereBornIntoOutlivesItsMinterUntilTheLastOfThemGoes() {
    spec("epic", null);
    room("epic", "proj");
    spec("child", "epic");
    spec("sibling", "epic");
    specs.delete("sibling");
    var talk = messages.append("epic", "uday", "the epic's talk", null);

    assertEquals(Set.of("spec:epic"), names(erasure.closure(List.of(spec("epic")))));
    prune(spec("epic"), "uday");
    assertTrue(messages.findById(talk.id()).isPresent(), "a live and a restorable spec still talk");

    assertEquals(Set.of("spec:child"), names(erasure.closure(List.of(spec("child")))));
    assertEquals(
        Set.of("spec:child", "spec:sibling", "room:epic", "message:" + talk.id()),
        names(erasure.closure(List.of(spec("child"), spec("sibling")))));
  }

  @Test
  void aRoomWhoseMinterIsNotGoingOutlivesASpecBornIntoIt() {
    spec("epic", null);
    room("epic", "proj");
    spec("child", "epic");

    assertEquals(Set.of("spec:child"), names(erasure.closure(List.of(spec("child")))));
  }

  @Test
  void anErasureTakesTheRowsThisBoxKeepsAboutTheEntityOnItsOwn() {
    spec("old", null);
    run("proj", "old", null);
    assertEquals(1, count("SELECT count(*) FROM run_credentials"));
    new SlackThreadStore(db).save("proj", "old", "C1", "1.0");
    runs.acquireContainerLease("proj", "node", "snapshot");

    prune(spec("old"), "uday");
    erasure.erase(List.of(new Erasure.Target("project", "proj")), "local");

    assertEquals(0, count("SELECT count(*) FROM run_credentials"));
    assertEquals(0, count("SELECT count(*) FROM slack_threads"));
    assertEquals(0, count("SELECT count(*) FROM container_leases"));
  }

  @Test
  void aTombstonedChildStillBelongsToItsParent() {
    spec("old", null);
    var gone = run("proj", "old", null);
    runs.applyRevision(gone, null, "9-gone");

    assertTrue(names(erasure.closure(List.of(spec("old")))).contains("run:" + gone));
  }

  @Test
  void aDeletedSpecIsPrunedWithTheIdentityRoomItsTombstoneNames() {
    spec("old", null);
    room("old", "proj");
    specs.delete("old");

    assertEquals(Set.of("spec:old", "room:old"), names(erasure.closure(List.of(spec("old")))));
  }

  @Test
  void aProjectTakesEverythingThatNamesIt() {
    specs.create(row("a", "acme"));
    room("a", "acme");
    room("lobby", "acme");
    ContentFixtures.put(new FileStore(db), "acme", "notes.md", "notes");
    var adhoc = run("acme", null, null);
    spec("elsewhere", null);

    var closure = names(erasure.closure(List.of(new Erasure.Target("project", "acme"))));

    assertTrue(closure.containsAll(Set.of("project:acme", "spec:a", "room:a", "room:lobby")));
    assertTrue(closure.contains("file:acme/notes.md"));
    assertTrue(closure.contains("run:" + adhoc));
    assertFalse(closure.contains("spec:elsewhere"));
  }

  @Test
  void erasingAThreadInAnyOrderCommitsAndLeavesOnlyErasureRows() {
    spec("old", null);
    room("old", "proj");
    var first = messages.append("old", "uday", "first", null);
    var reply = messages.append("old", "uday", "reply", first.id());
    new EventStore(db)
        .insert(
            new EventStore.EventRow(
                0, "2026-01-01T00:00:00Z", "t", "proj", "old", null, "h", "{}"));

    var result =
        erasure.erase(
            List.of(
                new Erasure.Target("message", first.id()),
                new Erasure.Target("message", reply.id()),
                new Erasure.Target("room", "old"),
                spec("old")),
            "local");

    assertEquals(4, result.entities().size());
    assertEquals(2, result.count("message"));
    assertEquals(1, result.events(), "the one event of the spec and its room");
    assertEquals(0, count("SELECT count(*) FROM room_messages"));
    assertEquals(
        4, count("SELECT count(*) FROM change_log WHERE kind = 'erasure' AND actor = 'uday'"));
    assertEquals(0, count("SELECT count(*) FROM change_log WHERE kind <> 'erasure'"));
    assertEquals(0, count("SELECT count(*) FROM events"));
  }

  @Test
  void erasingAgainIsANoOpThatKeepsTheFirstErasure() {
    spec("old", null);
    prune(spec("old"), "uday");
    var first = new ChangeLog(db).erasure("spec", "old").orElseThrow();

    var second = erasure.erase(erasure.closure(List.of(spec("old"))), "local");

    assertEquals(List.of(), second.entities());
    assertEquals(first, new ChangeLog(db).erasure("spec", "old").orElseThrow());
    assertEquals(
        List.of("spec:uday", "room:uday"),
        db.query(
            "SELECT entity_type || ':' || actor FROM change_log WHERE kind = 'erasure' ORDER BY seq",
            row -> row.text(0)));
  }

  @Test
  void aPrunedIdIsNeverUsedAgain() {
    spec("reborn", null);
    prune(spec("reborn"), "uday");

    var spec = assertThrows(ChangeLog.Pruned.class, () -> spec("reborn", null));
    assertTrue(spec.getMessage().contains("'reborn' was pruned"), spec.getMessage());
    assertThrows(IllegalArgumentException.class, () -> room("reborn", "proj"));
    assertEquals(0, count("SELECT count(*) FROM specs"));
    assertEquals(0, count("SELECT count(*) FROM change_log WHERE kind <> 'erasure'"));
  }

  @Test
  void adoptingMainsErasureRecordsItsRowAndPurgesChildrenWithoutRowsOfTheirOwn() {
    spec("old", null);
    room("old", "proj");
    var unpushed = messages.append("old", "uday", "never reached main", null);

    assertTrue(erasure.adopt("spec", "old", "7-mainsrev"));

    assertEquals(0, count("SELECT count(*) FROM room_messages"));
    assertEquals(
        0, count("SELECT count(*) FROM change_heads WHERE entity_id = '" + unpushed.id() + "'"));
    assertEquals(
        List.of("spec:old:7-mainsrev"),
        db.query(
            "SELECT entity_type || ':' || entity_id || ':' || rev FROM change_log",
            row -> row.text(0)));
    assertFalse(erasure.adopt("spec", "old", "7-mainsrev"), "adopting it twice changes nothing");
  }

  @Test
  void adoptingLeavesWhatMainAcknowledgedToItsOwnErasureRowButTakesEveryReply() {
    spec("old", null);
    var synced = run("proj", "old", null);
    var local = run("proj", "old", null);
    db.execute("UPDATE runs SET base_rev = rev WHERE id = ?", synced);
    room("old", "proj");
    db.execute("UPDATE rooms SET base_rev = rev WHERE id = 'old'");
    var acknowledged = messages.append("old", "uday", "main has it", null);
    db.execute("UPDATE room_messages SET base_rev = rev WHERE id = ?", acknowledged.id());
    var reply = messages.append("old", "uday", "a reply main has too", acknowledged.id());
    db.execute("UPDATE room_messages SET base_rev = rev WHERE id = ?", reply.id());

    erasure.adopt("spec", "old", "7-mainsrev");

    assertTrue(runs.findById(synced).isPresent(), "main's own erasure of it arrives in its page");
    assertTrue(runs.findById(local).isEmpty());
    assertTrue(rooms.findById("old").isPresent());
    assertTrue(messages.findById(acknowledged.id()).isPresent());

    erasure.adopt("message", acknowledged.id(), "3-mainsrev");

    assertTrue(messages.findById(reply.id()).isEmpty(), "a reply cannot outlive its message");
  }

  @Test
  void discardingTakesOnlyWhatMainNeverSawAndSpendsNoId() {
    spec("draft", null);
    var mine = messages.append("draft", "uday", "never pushed", null);

    var discarded = erasure.discard(List.of(spec("draft")));

    assertEquals(
        Set.of("spec:draft", "room:draft", "message:" + mine.id()), names(discarded.entities()));
    assertEquals(0, count("SELECT count(*) FROM change_log"));
    spec("draft", null);
    assertTrue(specs.findById("draft").isPresent(), "the id was never spent");
  }

  @Test
  void aBoxHoldsAProjectWhileItHasHistoryOfItOrAnythingInIt() {
    specs.create(row("a", "acme"));

    assertTrue(erasure.holds(new Erasure.Target("project", "acme")));
    assertFalse(erasure.holds(new Erasure.Target("project", "typo")));
  }

  @Test
  void adoptingTheErasureOfASpecHeardOfOnlyAsDeletedRecordsItsRow() {
    specs.applyRevision("gone", null, "3-deleted-on-main");

    assertTrue(erasure.adopt("spec", "gone", "4-erased-on-main"));
    assertTrue(erasure.isErased(spec("gone")));
    assertEquals(Set.of("spec:gone"), names(erasure.closure(List.of(spec("gone")))));
  }

  @Test
  void adoptingTheErasureOfAnEntityNeverHeldRecordsOnlyTheRow() {
    assertTrue(erasure.adopt("spec", "never-held", "3-rev"));

    assertEquals(1, count("SELECT count(*) FROM change_log"));
    assertTrue(erasure.isErased(spec("never-held")));
  }

  @Test
  void anErasureRemovesTheEntitysConflictsAndThisBoxsRequestToEraseIt() {
    spec("old", null);
    new SyncConflicts(db).record("spec", "old", "{}", "{}", "{}", List.of("title"));
    new EraseRequests(db).request("spec", "old", "uday");

    erasure.adopt("spec", "old", "5-x");

    assertEquals(0, count("SELECT count(*) FROM sync_conflicts"));
    assertEquals(List.of(), new EraseRequests(db).pending("spec"));
  }

  @Test
  void anUnknownTypeIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> erasure.closure(List.of(new Erasure.Target("widget", "x"))));
    assertThrows(IllegalArgumentException.class, () -> erasure.adopt("widget", "x", "1-a"));
    assertThrows(IllegalArgumentException.class, () -> erasure.adopt("spec", "x", " "));
    assertThrows(IllegalArgumentException.class, () -> new Erasure.Target("spec", ""));
  }

  private void prune(Erasure.Target root, String actor) {
    Actor.run(
        Actor.cliOperator(actor), () -> erasure.erase(erasure.closure(List.of(root)), "local"));
  }

  private Erasure.Target spec(String id) {
    return new Erasure.Target("spec", id);
  }

  private void spec(String id, String roomId) {
    var row = row(id, "proj");
    specs.create(roomId == null ? row : row.withRoomId(roomId));
  }

  private static SpecStore.SpecRow row(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        id,
        SpecStatus.ARCHIVED,
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  private void room(String id, String project) {
    rooms.create(
        new RoomStore.RoomRow(id, project, id, "uday", null, null, "uday", null, null, "uday"));
  }

  private String run(String project, String specId, String roomId) {
    var id = DateTimeUtils.newId().toString();
    runs.create(
        id, project, specId, "node", "node", "build", "claude", "b", "t", null, null, "/l", "u");
    if (roomId != null) {
      db.execute("UPDATE runs SET room_id = ? WHERE id = ?", roomId, id);
    }
    return id;
  }

  private static Set<String> names(List<Erasure.Target> targets) {
    return targets.stream()
        .map(target -> target.type() + ":" + target.id())
        .collect(Collectors.toSet());
  }

  private long count(String sql) {
    return db.queryOne(sql, row -> row.integer(0)).orElseThrow();
  }
}
