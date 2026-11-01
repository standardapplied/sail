/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.PushOutcome;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A record's times are its author's on every box: a revision carries when its entity was created
 * and when it was made, and every box applying it writes both verbatim, so main and two nodes show
 * the same {@code created_at}, {@code updated_at}, {@code archived_at} and board order for every
 * spec, project and room they hold — and two revisions that differ only in their times never
 * conflict.
 */
@ActingAs
class RecordTimesSyncTest {

  private SyncBox main;
  private SyncBox ada;
  private SyncBox bob;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
    ada = new SyncBox("ada");
    bob = new SyncBox("bob");
  }

  @AfterEach
  void tearDown() {
    bob.close();
    ada.close();
    main.close();
  }

  @Test
  void aSpecMadeOnMainHoldsMainsTimesOnBothNodes() {
    main.create(SyncBox.spec("m-acme", "Main's", "pending"));

    SyncBox.quiesce(main, ada, bob);

    assertSameEverywhere(box -> times(box, "m-acme"));
  }

  @Test
  void aSpecMadeOnANodeHoldsItsAuthorsTimesOnMainAndTheOtherNode() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    var authored = times(ada, "e-acme");

    SyncBox.quiesce(main, ada, bob);

    assertEquals(authored, times(main, "e-acme"));
    assertSameEverywhere(box -> times(box, "e-acme"));
  }

  @Test
  void anEditOnMainHoldsTheTimeItWasMadeWhereverItIsPulled() {
    main.create(SyncBox.spec("m-acme", "Main's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    Acting.as("main", () -> main.specs.updateStatus("m-acme", SpecStatus.IN_PROGRESS));
    var edited = times(main, "m-acme");
    SyncBox.quiesce(main, ada, bob);

    assertEquals(edited, times(bob, "m-acme"));
    assertSameEverywhere(box -> times(box, "m-acme"));
  }

  @Test
  void anArchiveOnANodeHoldsItsTimeOnEveryBox() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    Acting.as("ada", () -> ada.specs.updateStatus("e-acme", SpecStatus.ARCHIVED));
    SyncBox.quiesce(main, ada, bob);

    var archived = archivedAt(ada, "e-acme");
    assertNotNull(archived);
    assertEquals(ada.specs.findById("e-acme").orElseThrow().updatedAt(), archived);
    assertSameEverywhere(box -> archivedAt(box, "e-acme"));
  }

  @Test
  void anArchiveEditedBeforeItSyncsHoldsTheTimeOfTheArchiveEverywhere() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    var clock = DateTimeUtils.clock;
    Instant archived;
    try {
      DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T19:09:39.656Z"), ZoneOffset.UTC);
      Acting.as("ada", () -> ada.specs.updateStatus("e-acme", SpecStatus.ARCHIVED));
      archived = archivedAt(ada, "e-acme");
      DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T19:09:39.657Z"), ZoneOffset.UTC);
      Acting.as("ada", () -> ada.specs.setContent("e-acme", "Kept for the record.", ""));
    } finally {
      DateTimeUtils.clock = clock;
    }
    SyncBox.quiesce(main, ada, bob);

    assertNotEquals(ada.specs.findById("e-acme").orElseThrow().updatedAt(), archived);
    assertEquals(archived, archivedAt(main, "e-acme"));
    assertSameEverywhere(box -> archivedAt(box, "e-acme"));
  }

  @Test
  void concurrentArchivesSettleOnOneArchiveTime() {
    main.create(SyncBox.spec("m-acme", "Main's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    var clock = DateTimeUtils.clock;
    try {
      DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T19:09:39.656Z"), ZoneOffset.UTC);
      Acting.as("main", () -> main.specs.updateStatus("m-acme", SpecStatus.ARCHIVED));
      DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T19:09:39.657Z"), ZoneOffset.UTC);
      Acting.as("ada", () -> ada.specs.updateStatus("m-acme", SpecStatus.ARCHIVED));
    } finally {
      DateTimeUtils.clock = clock;
    }
    var mains = archivedAt(main, "m-acme");
    var adas = archivedAt(ada, "m-acme");
    SyncBox.quiesce(main, ada, bob);

    assertNotEquals(mains, adas);
    assertEquals(mains, archivedAt(ada, "m-acme"));
    assertSameEverywhere(box -> archivedAt(box, "m-acme"));
    assertSameEverywhere(box -> times(box, "m-acme"));
  }

  @Test
  void anArchiveMergedWithAnEditElsewhereHoldsTheTimeOfTheArchiveEverywhere() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    Acting.as("ada", () -> main.specs.updateStatus("e-acme", SpecStatus.ARCHIVED));
    var archived = archivedAt(main, "e-acme");
    Acting.as("ada", () -> ada.specs.setContent("e-acme", "Kept for the record.", ""));
    SyncBox.quiesce(main, ada, bob);

    assertEquals(archived, archivedAt(main, "e-acme"));
    assertSameEverywhere(box -> archivedAt(box, "e-acme"));
  }

  @Test
  void anArchiveMergedWithACancelUndoneElsewhereHoldsTheTimeOfTheArchiveEverywhere() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    Acting.as("ada", () -> ada.specs.updateStatus("e-acme", SpecStatus.CANCELLED));
    Acting.as("ada", () -> ada.specs.updateStatus("e-acme", SpecStatus.PENDING));
    Acting.as("ada", () -> ada.specs.setContent("e-acme", "Kept for the record.", ""));
    Acting.as("ada", () -> main.specs.updateStatus("e-acme", SpecStatus.ARCHIVED));
    var archived = archivedAt(main, "e-acme");
    SyncBox.quiesce(main, ada, bob);

    assertEquals(archived, archivedAt(main, "e-acme"));
    assertSameEverywhere(box -> archivedAt(box, "e-acme"));
  }

  @Test
  void aBodyEditHoldsTheTimeItWasMadeEverywhere() {
    main.create(SyncBox.spec("m-acme", "Main's", "pending"));
    SyncBox.quiesce(main, ada, bob);
    var created = times(main, "m-acme");

    Acting.as("main", () -> main.specs.setContent("m-acme", "A sharper body.", ""));
    var edited = main.specs.getContent("m-acme").orElseThrow().updatedAt();
    SyncBox.quiesce(main, ada, bob);

    assertNotEquals(created.getLast(), edited);
    assertEquals(List.of(created.getFirst(), edited), times(main, "m-acme"));
    assertSameEverywhere(box -> times(box, "m-acme"));
    assertSameEverywhere(box -> box.specs.getContent("m-acme").orElseThrow().updatedAt());
  }

  @Test
  void oneRoomMadeAlikeOnMainAndANodeNeverConflictsOnItsTimes() {
    var lounge =
        new RoomStore.RoomRow("lounge", "acme", "Lounge", null, null, null, null, null, null, null);
    Acting.as("ada", () -> new RoomStore(main.db).create(lounge));
    Acting.as("ada", () -> new RoomStore(ada.db).create(lounge));

    SyncBox.quiesce(main, ada, bob);

    assertEquals(List.of(), ada.conflicts.pending());
    assertSameEverywhere(
        box -> {
          var room = new RoomStore(box.db).findById("lounge").orElseThrow();
          return List.of(room.createdAt(), room.updatedAt());
        });
  }

  @Test
  void oneRoomMadeOnMainAndANodeAndMergedHoldsOneCreationTimeEverywhere() {
    Acting.as(
        "ada",
        () ->
            new RoomStore(main.db)
                .create(
                    new RoomStore.RoomRow(
                        "lounge", "acme", "Lounge", null, null, null, null, null, null, null)));
    Acting.as(
        "ada",
        () ->
            new RoomStore(ada.db)
                .create(
                    new RoomStore.RoomRow(
                        "lounge", "acme", "Lounge", "ada", null, null, null, null, null, null)));

    SyncBox.quiesce(main, ada, bob);

    assertEquals(List.of(), ada.conflicts.pending());
    assertSameEverywhere(
        box -> {
          var room = new RoomStore(box.db).findById("lounge").orElseThrow();
          return List.of(room.assignee(), room.createdAt(), room.updatedAt());
        });
  }

  @Test
  void specsOfOnePriorityFromThreeBoxesShowInOneOrderEverywhere() {
    bob.create(SyncBox.spec("c-third", "Bob's", "pending"));
    main.create(SyncBox.spec("a-second", "Main's", "pending"));
    ada.create(SyncBox.spec("b-first", "Ada's", "pending"));

    SyncBox.quiesce(main, ada, bob);

    assertSameEverywhere(RecordTimesSyncTest::board);
    assertEquals(3, board(main).size());
  }

  @Test
  void aProjectAndARoomMadeOnANodeHoldTheirAuthorsTimesEverywhere() {
    Acting.as(
        "ada",
        () -> {
          new ProjectStore(ada.db).upsert("zenith", "name: zenith\n");
          new RoomStore(ada.db)
              .create(
                  new RoomStore.RoomRow(
                      "lounge", "acme", "Lounge", null, null, null, null, null, null, null));
        });

    SyncBox.quiesce(main, ada, bob);

    assertSameEverywhere(
        box -> {
          var project = new ProjectStore(box.db).findByName("zenith").orElseThrow();
          return List.of(project.createdAt(), project.updatedAt());
        });
    assertSameEverywhere(
        box -> {
          var room = new RoomStore(box.db).findById("lounge").orElseThrow();
          return List.of(room.createdAt(), room.updatedAt());
        });
  }

  @Test
  void twoRevisionsThatDifferOnlyInTheirTimesNeverConflict() {
    main.create(SyncBox.spec("m-acme", "Main's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    Acting.as("main", () -> main.specs.updateStatus("m-acme", SpecStatus.DONE));
    Acting.as("main", () -> ada.specs.updateStatus("m-acme", SpecStatus.DONE));
    SyncBox.quiesce(main, ada, bob);

    assertEquals(List.of(), ada.conflicts.pending());
    assertEquals(SpecStatus.DONE, bob.specs.findById("m-acme").orElseThrow().status());
  }

  @Test
  void aRowFromBeforeTheTimesConvergesOnMainsCreationTimeOnceMainTakesARevisionOfIt() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    SyncBox.quiesce(main, ada, bob);
    var mains = times(main, "e-acme").getFirst();
    ada.db.execute("UPDATE specs SET created_at = 1577836800000 WHERE id = 'e-acme'");

    Acting.as("ada", () -> ada.specs.updateStatus("e-acme", SpecStatus.IN_PROGRESS));
    SyncBox.quiesce(main, ada, bob);

    assertEquals(SpecStatus.IN_PROGRESS, main.specs.findById("e-acme").orElseThrow().status());
    assertEquals(mains, times(main, "e-acme").getFirst());
    assertSameEverywhere(box -> times(box, "e-acme"));
  }

  @Test
  void aMergeOfferedToMainIsStampedWhenItIsMade() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    SyncBox.quiesce(main, ada, bob);

    Acting.as("ada", () -> ada.specs.setContent("e-acme", "Edited offline.", ""));
    ada.db.execute("UPDATE specs SET updated_at = 1577836800000 WHERE id = 'e-acme'");
    Acting.as("ada", () -> main.specs.updateStatus("e-acme", SpecStatus.IN_PROGRESS));
    var mainsEdit = times(main, "e-acme").getLast();
    SyncBox.quiesce(main, ada, bob);

    assertTrue(
        times(main, "e-acme").getLast().compareTo(mainsEdit) >= 0, "never before main's edit");
    assertEquals(SpecStatus.IN_PROGRESS, bob.specs.findById("e-acme").orElseThrow().status());
    assertEquals("Edited offline.", bob.specs.getContent("e-acme").orElseThrow().body());
    assertSameEverywhere(box -> times(box, "e-acme"));
  }

  @Test
  void aResolveKeepingMineNeverMovesMainsCreationTime() {
    ada.create(SyncBox.spec("e-acme", "Ada's", "pending"));
    SyncBox.quiesce(main, ada, bob);
    var mains = times(main, "e-acme").getFirst();
    ada.db.execute("UPDATE specs SET created_at = 1577836800000 WHERE id = 'e-acme'");
    Acting.as("ada", () -> ada.specs.updateStatus("e-acme", SpecStatus.IN_PROGRESS));
    Acting.as("ada", () -> main.specs.updateStatus("e-acme", SpecStatus.REVIEW));
    SyncBox.round(main, ada);
    assertEquals(
        List.of("status"), ada.conflicts.pendingFor("spec", "e-acme").orElseThrow().fields());

    SyncBox.resolve(ada, "spec", "e-acme", SyncBox.Resolve.MINE);

    assertEquals(mains, times(ada, "e-acme").getFirst());
    SyncBox.quiesce(main, ada, bob);
    assertEquals(SpecStatus.IN_PROGRESS, main.specs.findById("e-acme").orElseThrow().status());
    assertSameEverywhere(box -> times(box, "e-acme"));
  }

  @Test
  void aRestoredRoomIsStampedWhenItIsRestored() {
    var rooms = new RoomStore(main.db);
    Acting.as("main", () -> rooms.create(room("lounge")));
    main.db.execute("UPDATE rooms SET updated_at = 1577836800000 WHERE id = 'lounge'");
    Acting.as("main", () -> rooms.delete("lounge"));

    Acting.as("main", () -> rooms.restoreDeleted("lounge"));

    assertNotEquals("2020-01-01T00:00:00Z", rooms.findById("lounge").orElseThrow().updatedAt());
  }

  @Test
  void aRoomRevisionFromABoxBeforeTheTimesLeavesMainsCreationTime() {
    var rooms = new RoomStore(main.db);
    Acting.as("main", () -> rooms.create(room("lounge")));
    var created = rooms.findById("lounge").orElseThrow().createdAt();
    var offer = new LinkedHashMap<>(rooms.currentForSync("lounge"));
    offer.remove(Snapshots.CREATED_AT);
    offer.remove(Snapshots.UPDATED_AT);
    offer.put("created_at", Snapshots.millis(Instant.parse("2020-01-01T00:00:00Z")));
    offer.put("title", "Renamed");

    var taken =
        Actor.call(
            Actor.sync("main", Role.MEMBER),
            () ->
                rooms.commitRevision(
                    "lounge", offer, rooms.latestRev("lounge"), rooms.authority()));

    assertInstanceOf(PushOutcome.Accepted.class, taken);
    assertEquals(created, rooms.findById("lounge").orElseThrow().createdAt());
    assertEquals("Renamed", rooms.findById("lounge").orElseThrow().title());
  }

  private static RoomStore.RoomRow room(String id) {
    return new RoomStore.RoomRow(id, "acme", "Lounge", null, null, null, null, null, null, null);
  }

  private <T> void assertSameEverywhere(Function<SyncBox, T> read) {
    var mains = read.apply(main);
    assertEquals(mains, read.apply(ada), "ada differs from main");
    assertEquals(mains, read.apply(bob), "bob differs from main");
  }

  private static List<Instant> times(SyncBox box, String id) {
    var spec = box.specs.findById(id).orElseThrow();
    return List.of(spec.createdAt(), spec.updatedAt());
  }

  private static Instant archivedAt(SyncBox box, String id) {
    return box.db
        .queryOne("SELECT archived_at FROM specs WHERE id = ?", r -> r.instant(0), id)
        .orElseThrow();
  }

  private static List<String> board(SyncBox box) {
    return box.specs.list(SpecStore.SpecFilter.all()).stream().map(SpecStore.SpecRow::id).toList();
  }
}
