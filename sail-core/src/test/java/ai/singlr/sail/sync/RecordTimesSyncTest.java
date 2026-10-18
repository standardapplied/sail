/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SpecStore;
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

  private <T> void assertSameEverywhere(Function<SyncBox, T> read) {
    var mains = read.apply(main);
    assertEquals(mains, read.apply(ada), "ada differs from main");
    assertEquals(mains, read.apply(bob), "bob differs from main");
  }

  private static List<String> times(SyncBox box, String id) {
    var spec = box.specs.findById(id).orElseThrow();
    return List.of(spec.createdAt(), spec.updatedAt());
  }

  private static String archivedAt(SyncBox box, String id) {
    return box.db
        .queryOne("SELECT COALESCE(archived_at, '') FROM specs WHERE id = ?", r -> r.text(0), id)
        .orElseThrow();
  }

  private static List<String> board(SyncBox box) {
    return box.specs.list(SpecStore.SpecFilter.all()).stream().map(SpecStore.SpecRow::id).toList();
  }
}
