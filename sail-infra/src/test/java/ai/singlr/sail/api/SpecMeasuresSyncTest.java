/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The measures' times over real sync rounds between main and a node, ada, read on each box from its
 * own change log through the production computation. A move a box wrote is exact there, its own
 * write coming back from main included; a move another box made, heard in a round that may carry
 * several, is {@code collapsed_by_sync}; and an unedited birth heard by sync is one move.
 */
class SpecMeasuresSyncTest {

  private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");

  @TempDir Path dir;
  private Clock clock;
  private SyncBox main;
  private SyncBox ada;

  @BeforeEach
  void aMainAndANode() {
    clock = DateTimeUtils.clock;
    at(0);
    main = new SyncBox(dir, "main");
    ada = new SyncBox(dir, "ada");
    Acting.as("root", () -> new FdeStore(main.db).add("ada", null, null, "member"));
  }

  @AfterEach
  void tearDown() {
    ada.close();
    main.close();
    DateTimeUtils.clock = clock;
  }

  private static void at(int minutes) {
    DateTimeUtils.clock = Clock.fixed(T0.plusSeconds(60L * minutes), ZoneOffset.UTC);
  }

  private static SpecStore.SpecRow draft(String id, String title) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        title,
        SpecStatus.DRAFT,
        "ada",
        null,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  private static void move(SyncBox box, String id, SpecStatus status, int minutes) {
    at(minutes);
    Acting.as("ada", () -> box.specs.updateStatus(id, status));
  }

  private void sync() {
    SyncBox.quiesce(main, ada);
  }

  /** The times {@code box} reads of {@code id}, as the route computes them there. */
  private static Map<?, ?> times(SyncBox box, String id) {
    var operations =
        new GlobalSpecOperations(
            box.specs, new ReviewStore(box.db), null, new RunStore(box.db), () -> null, () -> true);
    return (Map<?, ?>) Acting.system(() -> operations.measures(id, box.id)).toMap().get("times");
  }

  private static Map<String, Object> exact(int minutes) {
    return Map.of(
        "at", DateTimeUtils.format(T0.plusSeconds(60L * minutes)), "source", "change_log");
  }

  private static final Map<String, Object> COLLAPSED = Map.of("unknown", "collapsed_by_sync");

  /**
   * Major 1 of review 2: every move made on ada, ada syncing between them as the loop's box does.
   */
  @Test
  void aSpecCreatedAndDoneOnTheNodeReadsExactTimesThereAndCollapsedOnesOnMain() {
    Acting.as("ada", () -> ada.specs.create(draft("n1", "Node work")));
    sync();
    move(ada, "n1", SpecStatus.PENDING, 10);
    sync();
    move(ada, "n1", SpecStatus.IN_PROGRESS, 20);
    move(ada, "n1", SpecStatus.REVIEW, 50);
    sync();
    move(ada, "n1", SpecStatus.AWAITING_MERGE, 70);
    sync();
    move(ada, "n1", SpecStatus.DONE, 130);
    sync();

    var onAda = times(ada, "n1");
    var onMain = times(main, "n1");

    assertEquals(exact(10), onAda.get("ready"));
    assertEquals(exact(70), onAda.get("awaiting_merge"));
    assertEquals(exact(130), onAda.get("done"));
    assertEquals(Map.of("sail_id", "ada"), onAda.get("box"));
    assertEquals(COLLAPSED, onMain.get("awaiting_merge"), "ada's moves reach main a round at once");
    assertEquals(COLLAPSED, onMain.get("done"));
    assertEquals(COLLAPSED, onMain.get("ready"));
  }

  /**
   * A node spec retitled and synced before it is made ready: the echo of the retitle adds nothing.
   */
  @Test
  void aNodeSpecRetitledAndSyncedBeforeItIsMadeReadyHasAnExactReadyThere() {
    Acting.as("ada", () -> ada.specs.create(draft("t1", "First")));
    at(5);
    var row = ada.specs.findById("t1").orElseThrow();
    Acting.as(
        "ada",
        () ->
            ada.specs.update(
                new SpecStore.SpecRow(
                    row.id(),
                    row.project(),
                    "Retitled",
                    row.status(),
                    row.assignee(),
                    null,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of())));
    sync();
    move(ada, "t1", SpecStatus.PENDING, 10);
    sync();

    assertEquals(exact(10), times(ada, "t1").get("ready"));
  }

  /**
   * Minor 4 of review 2: a spec born on ada reaches main unedited, one move, and is made ready on
   * main, so main reads its ready time exactly.
   */
  @Test
  void aNodeBirthHeardUneditedOnMainIsOneMoveSoReadyMadeThereIsExact() {
    Acting.as("ada", () -> ada.specs.create(draft("b1", "Born on ada")));
    sync();
    move(main, "b1", SpecStatus.PENDING, 10);
    sync();

    assertEquals(exact(10), times(main, "b1").get("ready"));
    assertEquals(
        Map.of("unknown", "collapsed_by_sync"),
        times(ada, "b1").get("ready"),
        "main's move reached ada in a round");
  }
}
