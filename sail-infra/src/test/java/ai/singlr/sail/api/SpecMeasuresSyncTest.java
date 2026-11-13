/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.AttentionStore;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A change's own record over real sync rounds between main and a node, ada, read on each box
 * through the production computation: the spec's ready, awaiting-merge and done times are the
 * moves' own, the same on every box whichever box made them (M4); a run's usage is its executing
 * box's fact, held as it pushed it (M2); retention erasing a spec's runs on main changes no measure
 * of it on either box (M14); and a box that may lack a run says so rather than serve a smaller
 * exact number. Every scenario ends converged.
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

  private static SpecStore.SpecRow draft(String id) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Spec " + id,
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

  /** What {@code box} measures of {@code id}, as the route computes it there. */
  private static Map<String, Object> measures(SyncBox box, String id) {
    var operations =
        new GlobalSpecOperations(
            box.specs,
            new ReviewStore(box.db),
            null,
            new RunStore(box.db),
            () -> null,
            () -> true,
            project -> Optional.empty(),
            () -> new AttentionStore(box.db));
    return Acting.system(() -> operations.measures(id, box.id)).toMap();
  }

  private static Map<?, ?> times(SyncBox box, String id) {
    return (Map<?, ?>) measures(box, id).get("times");
  }

  /** {@code measures} but the box that counted them, which is each box's own name. */
  private static Map<String, Object> counted(Map<String, Object> measures) {
    var counted = new LinkedHashMap<>(measures);
    counted.remove("box");
    return counted;
  }

  private static Map<String, Object> exact(int minutes) {
    return Map.of("at", DateTimeUtils.format(T0.plusSeconds(60L * minutes)), "source", "spec");
  }

  private static String finishedRun(SyncBox box, String spec, String role, RunUsage usage) {
    var runs = new RunStore(box.db);
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () -> {
          runs.reserveDispatch(
              id, "acme", spec, box.handle(), role, List.of(), "claude-code", "b", "t", "/l", "u");
          runs.finish(id, "running", "completed", 0, usage);
        });
    return id;
  }

  private static RunUsage reported(long in, double usd) {
    return new RunUsage(
        "claude-opus-5-5", in, 2 * in, 10 * in, 3 * in, usd, RunUsage.Source.RESULT);
  }

  /** Scenario 6. */
  @Test
  void aSpecMadeReadyDispatchedRestartedAndDoneHoldsTheFirstReadyAndTheDoneOnEveryBox() {
    Acting.as("ada", () -> main.specs.create(draft("s6")));
    sync();
    move(main, "s6", SpecStatus.PENDING, 10);
    sync();
    move(ada, "s6", SpecStatus.IN_PROGRESS, 20);
    move(ada, "s6", SpecStatus.REVIEW, 30);
    sync();
    move(main, "s6", SpecStatus.PENDING, 40);
    sync();
    move(ada, "s6", SpecStatus.IN_PROGRESS, 50);
    move(ada, "s6", SpecStatus.AWAITING_MERGE, 60);
    sync();
    move(main, "s6", SpecStatus.DONE, 90);

    SyncBox.assertConverged(main, ada);

    for (var box : List.of(main, ada)) {
      var times = times(box, "s6");
      assertEquals(exact(10), times.get("ready"), box.id + ": the first move into pending");
      assertEquals(exact(60), times.get("awaiting_merge"), box.id);
      assertEquals(exact(90), times.get("done"), box.id);
    }
  }

  /** Scenario 7. */
  @Test
  void aDoneSpecReopenedAndDoneAgainHoldsTheSecondDoneAndItsFirstReady() {
    Acting.as("ada", () -> ada.specs.create(draft("s7")));
    move(ada, "s7", SpecStatus.PENDING, 5);
    move(ada, "s7", SpecStatus.DONE, 30);
    sync();
    assertEquals(exact(30), times(main, "s7").get("done"));
    move(main, "s7", SpecStatus.PENDING, 40);
    sync();
    assertEquals(
        Map.of("unknown", "not_reached"), times(ada, "s7").get("done"), "cleared on leaving");
    move(ada, "s7", SpecStatus.DONE, 70);

    SyncBox.assertConverged(main, ada);

    for (var box : List.of(main, ada)) {
      var times = times(box, "s7");
      assertEquals(exact(5), times.get("ready"), box.id);
      assertEquals(exact(70), times.get("done"), box.id);
    }
  }

  /** Scenario 8: what a node did between two rounds reaches main as the moves it made. */
  @Test
  void aSpecDoneOnTheNodeReadsItsExactTimesOnMain() {
    Acting.as("ada", () -> ada.specs.create(draft("s8")));
    move(ada, "s8", SpecStatus.PENDING, 10);
    move(ada, "s8", SpecStatus.IN_PROGRESS, 20);
    move(ada, "s8", SpecStatus.AWAITING_MERGE, 70);
    move(ada, "s8", SpecStatus.DONE, 130);
    at(140);
    var row = ada.specs.findById("s8").orElseThrow();
    Acting.as(
        "ada",
        () ->
            ada.specs.update(
                new SpecStore.SpecRow(
                    row.id(),
                    row.project(),
                    "Retitled after it shipped",
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

    SyncBox.assertConverged(main, ada);

    var onMain = times(main, "s8");
    assertEquals(exact(10), onMain.get("ready"));
    assertEquals(exact(70), onMain.get("awaiting_merge"));
    assertEquals(exact(130), onMain.get("done"), "the move's own time, not the retitle's");
    assertEquals(onMain, times(ada, "s8"));
  }

  /** Scenario 9 and scenario 1's "main holds the same after one round" (M2, M14). */
  @Test
  void retentionErasingThreeOfFiveRunsOnMainChangesNoMeasureOnEitherBox() {
    Acting.as("ada", () -> main.specs.create(draft("s9")));
    sync();
    var ids = new ArrayList<String>();
    ids.add(finishedRun(ada, "s9", "build", reported(100, 1.5)));
    ids.add(finishedRun(ada, "s9", "review", reported(10, 0.25)));
    ids.add(finishedRun(ada, "s9", "fix", reported(50, 0.75)));
    ids.add(finishedRun(ada, "s9", "review", RunUsage.NONE));
    ids.add(finishedRun(ada, "s9", "build", reported(200, 2.5)));
    SyncBox.round(main, ada);
    assertEquals(
        new RunStore(ada.db).findById(ids.getFirst()).orElseThrow().usage(),
        new RunStore(main.db).findById(ids.getFirst()).orElseThrow().usage(),
        "after one round main holds the usage ada recorded");
    sync();
    var before = measures(main, "s9");
    assertEquals(counted(before), counted(measures(ada, "s9")), "every box counts the same");

    Acting.system(
        () ->
            new Erasure(main.db)
                .erase(
                    ids.subList(0, 3).stream()
                        .map(id -> new Erasure.Target(Erasure.RUN, id))
                        .toList(),
                    SpecPruner.RETENTION));
    SyncBox.assertConverged(main, ada);

    assertEquals(2, new RunStore(ada.db).listForSpec("s9").size(), "the node took the erasure");
    assertEquals(before, measures(main, "s9"));
    assertEquals(counted(before), counted(measures(ada, "s9")));
    var cost = (Map<?, ?>) before.get("cost");
    assertEquals(Map.of("count", 5L), cost.get("runs"));
    assertEquals(Map.of("count", 1L), cost.get("runs_without_usage"));
    assertEquals(
        Map.of("count", 360L * 16, "at_least", true),
        cost.get("tokens"),
        "a review reported nothing, so what the others reported is a lower bound");
    assertEquals(Map.of("usd", 5.0, "at_least", true), cost.get("usd"));
  }

  /**
   * Review 1, major 2: main finishes a run of a spec ada already holds and its retention erases it
   * before ada's next round, so ada never counts it. Ada adopts an erasure of a run it never saw
   * finish and serves the spec's run figures as a lower bound; main, which counted it, serves them
   * exact. No two boxes serve two different exact numbers.
   */
  @Test
  void aRunMadeAndErasedOnMainBetweenTwoOfANodesRoundsIsALowerBoundThereAndExactOnMain() {
    Acting.as("ada", () -> main.specs.create(draft("g1")));
    sync();
    at(10);
    var missed =
        finishedRun(
            main,
            "g1",
            "build",
            new RunUsage("claude-opus-5-5", 1000L, 0L, 0L, 0L, 7.25, RunUsage.Source.RESULT));
    Acting.system(
        () ->
            new Erasure(main.db)
                .erase(List.of(new Erasure.Target(Erasure.RUN, missed)), SpecPruner.RETENTION));
    at(20);

    SyncBox.assertConverged(main, ada);
    at(30);
    Acting.as("ada", () -> main.specs.create(draft("later")));
    SyncBox.assertConverged(main, ada);

    var onMain = (Map<?, ?>) measures(main, "g1").get("cost");
    var onAda = (Map<?, ?>) measures(ada, "g1").get("cost");
    assertEquals(Map.of("usd", 7.25), onMain.get("usd"));
    assertEquals(Map.of("count", 1L), onMain.get("runs"));
    assertEquals(Map.of("usd", 0.0, "at_least", true), onAda.get("usd"));
    assertEquals(Map.of("count", 0L, "at_least", true), onAda.get("runs"));
    neverTwoExactNumbers(onMain, onAda);
    assertEquals(
        Map.of("count", 0L),
        ((Map<?, ?>) measures(ada, "later").get("cost")).get("runs"),
        "a spec made after the erasure could not have lost a run to it");
  }

  /**
   * A run ada held while it ran, finished and erased on main between ada's rounds, is one ada knows
   * the spec of: that spec alone is a lower bound there, and another spec of the project stays
   * exact.
   */
  @Test
  void aRunHeldRunningThenFinishedAndErasedOnMainBoundsOnlyItsOwnSpecOnTheNode() {
    Acting.as("ada", () -> main.specs.create(draft("held")));
    Acting.as("ada", () -> main.specs.create(draft("other")));
    var runs = new RunStore(main.db);
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs.reserveDispatch(
                id,
                "acme",
                "held",
                main.handle(),
                "build",
                List.of(),
                "claude-code",
                "b",
                "t",
                "/l",
                "u"));
    SyncBox.assertConverged(main, ada);
    assertEquals("running", new RunStore(ada.db).findById(id).orElseThrow().status());
    at(10);
    Acting.system(() -> runs.finish(id, "running", "completed", 0, reported(10, 2.0)));
    Acting.system(
        () ->
            new Erasure(main.db)
                .erase(List.of(new Erasure.Target(Erasure.RUN, id)), SpecPruner.RETENTION));
    at(20);

    SyncBox.assertConverged(main, ada);

    assertEquals(Map.of("usd", 2.0), ((Map<?, ?>) measures(main, "held").get("cost")).get("usd"));
    assertEquals(
        Map.of("usd", 0.0, "at_least", true),
        ((Map<?, ?>) measures(ada, "held").get("cost")).get("usd"));
    assertEquals(Map.of("count", 0L), ((Map<?, ?>) measures(ada, "other").get("cost")).get("runs"));
  }

  /** Every figure both boxes serve exact is the same number on both. */
  private static void neverTwoExactNumbers(Map<?, ?> one, Map<?, ?> other) {
    for (var figure : one.keySet()) {
      if (one.get(figure) instanceof Map<?, ?> a
          && other.get(figure) instanceof Map<?, ?> b
          && !a.containsKey("at_least")
          && !b.containsKey("at_least")
          && !a.containsKey("unknown")
          && !b.containsKey("unknown")) {
        assertEquals(a, b, String.valueOf(figure));
      }
    }
  }

  /**
   * The coordinator's ruling on a box that joins late: ada first holds the spec after main's
   * retention erased one of its runs, so ada cannot vouch for its run counts and serves them as a
   * lower bound, where main, which counted every run before erasing it, serves them exact; a spec
   * made once ada holds the project is exact on ada too.
   */
  @Test
  void aNodeThatFirstHoldsTheSpecAfterARunOfItWasErasedServesItsRunsAsALowerBound() {
    Acting.as("ada", () -> main.specs.create(draft("old")));
    var erased = finishedRun(main, "old", "build", reported(100, 1.0));
    finishedRun(main, "old", "fix", reported(10, 0.5));
    Acting.system(
        () ->
            new Erasure(main.db)
                .erase(List.of(new Erasure.Target(Erasure.RUN, erased)), SpecPruner.RETENTION));
    at(30);

    SyncBox.assertConverged(main, ada);
    at(40);
    Acting.as("ada", () -> main.specs.create(draft("new")));
    finishedRun(main, "new", "build", reported(1, 0.25));
    SyncBox.assertConverged(main, ada);

    var onMain = (Map<?, ?>) measures(main, "old").get("cost");
    var onAda = (Map<?, ?>) measures(ada, "old").get("cost");
    assertEquals(Map.of("count", 2L), onMain.get("runs"), "main counted both before erasing one");
    assertEquals(Map.of("count", 1L, "at_least", true), onAda.get("runs"));
    assertEquals(Map.of("usd", 0.5, "at_least", true), onAda.get("usd"));
    assertEquals(
        Map.of("count", 1L, "at_least", true),
        ((Map<?, ?>) measures(ada, "old").get("rework")).get("fix_runs"));
    assertEquals(
        Map.of("count", 1L),
        ((Map<?, ?>) measures(ada, "new").get("cost")).get("runs"),
        "a spec made after the erasure could not have lost a run to it");
  }
}
