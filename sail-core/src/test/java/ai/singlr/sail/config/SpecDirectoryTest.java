/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecDirectoryTest {

  @Test
  void nextReadyReturnsFirstPending() {
    var specs =
        List.of(
            new Spec("done-spec", "test", "Done", SpecStatus.DONE, null, List.of(), null),
            new Spec("ready", "test", "Ready", SpecStatus.PENDING, null, List.of(), null));

    var next = SpecDirectory.nextReady(specs);

    assertNotNull(next);
    assertEquals("ready", next.id());
  }

  @Test
  void nextReadyRespectsDependencies() {
    var specs =
        List.of(
            new Spec("first", "test", "First", SpecStatus.PENDING, null, List.of(), null),
            new Spec("second", "test", "Second", SpecStatus.PENDING, null, List.of("first"), null));

    var next = SpecDirectory.nextReady(specs);

    assertEquals("first", next.id());
  }

  @Test
  void nextReadySkipsBlockedDependency() {
    var specs =
        List.of(
            new Spec("first", "test", "First", SpecStatus.IN_PROGRESS, null, List.of(), null),
            new Spec("second", "test", "Second", SpecStatus.PENDING, null, List.of("first"), null),
            new Spec("third", "test", "Third", SpecStatus.PENDING, null, List.of(), null));

    var next = SpecDirectory.nextReady(specs);

    assertEquals("third", next.id());
  }

  @Test
  void nextReadyReturnsDependentWhenDepDone() {
    var specs =
        List.of(
            new Spec("first", "test", "First", SpecStatus.DONE, null, List.of(), null),
            new Spec("second", "test", "Second", SpecStatus.PENDING, null, List.of("first"), null));

    var next = SpecDirectory.nextReady(specs);

    assertEquals("second", next.id());
  }

  @Test
  void nextReadyReturnsNullWhenAllDone() {
    var specs =
        List.of(
            new Spec("a", "test", "A", SpecStatus.DONE, null, List.of(), null),
            new Spec("b", "test", "B", SpecStatus.DONE, null, List.of(), null));

    assertNull(SpecDirectory.nextReady(specs));
  }

  @Test
  void nextReadyAssignedToPicksOnlyThisFdesSpecStrictly() {
    var specs =
        List.of(
            new Spec("unassigned", "test", "U", SpecStatus.PENDING, null, List.of(), null),
            new Spec("other", "test", "Other", SpecStatus.PENDING, "mady", List.of(), null),
            new Spec("mine", "test", "Mine", SpecStatus.PENDING, "uday", List.of(), null));

    assertEquals("mine", SpecDirectory.nextReadyAssignedTo(specs, "uday").id());
  }

  @Test
  void nextReadyAssignedToSkipsUnassignedAndOtherFdeSpecs() {
    var specs =
        List.of(
            new Spec("unassigned", "test", "U", SpecStatus.PENDING, null, List.of(), null),
            new Spec("other", "test", "Other", SpecStatus.PENDING, "mady", List.of(), null));

    assertNull(SpecDirectory.nextReadyAssignedTo(specs, "uday"));
  }

  @Test
  void nextReadyAssignedToReturnsNullWhenNoFdeIsBound() {
    var specs =
        List.of(new Spec("mine", "test", "Mine", SpecStatus.PENDING, "uday", List.of(), null));

    assertNull(SpecDirectory.nextReadyAssignedTo(specs, null));
  }

  @Test
  void nextReadyAssignedToRespectsDependencies() {
    var specs =
        List.of(
            new Spec("dep", "test", "Dep", SpecStatus.PENDING, "uday", List.of(), null),
            new Spec("mine", "test", "Mine", SpecStatus.PENDING, "uday", List.of("dep"), null));

    assertEquals("dep", SpecDirectory.nextReadyAssignedTo(specs, "uday").id());
  }

  @Test
  void nextReadyReturnsNullWhenEmpty() {
    assertNull(SpecDirectory.nextReady(List.of()));
  }

  @Test
  void nextReadySkipsInProgress() {
    var specs =
        List.of(
            new Spec("wip", "test", "WIP", SpecStatus.IN_PROGRESS, null, List.of(), null),
            new Spec("ready", "test", "Ready", SpecStatus.PENDING, null, List.of(), null));

    var next = SpecDirectory.nextReady(specs);

    assertEquals("ready", next.id());
  }

  @Test
  void nextReadySkipsReviewStatus() {
    var specs =
        List.of(
            new Spec("reviewing", "test", "Reviewing", SpecStatus.REVIEW, null, List.of(), null),
            new Spec("ready", "test", "Ready", SpecStatus.PENDING, null, List.of(), null));

    var next = SpecDirectory.nextReady(specs);

    assertEquals("ready", next.id());
  }

  @Test
  void nextReadyFiltersbyAssignee() {
    var specs =
        List.of(
            new Spec("alice-task", "test", "Alice's", SpecStatus.PENDING, "alice", List.of(), null),
            new Spec("bob-task", "test", "Bob's", SpecStatus.PENDING, "bob", List.of(), null));

    var next = SpecDirectory.nextReady(specs, "bob");

    assertEquals("bob-task", next.id());
  }

  @Test
  void nextReadyIncludesUnassignedForAnyAssignee() {
    var specs =
        List.of(new Spec("unassigned", "test", "Open", SpecStatus.PENDING, null, List.of(), null));

    var next = SpecDirectory.nextReady(specs, "alice");

    assertEquals("unassigned", next.id());
  }

  @Test
  void nextReadyNullAssigneeMatchesAll() {
    var specs =
        List.of(
            new Spec(
                "alice-task", "test", "Alice's", SpecStatus.PENDING, "alice", List.of(), null));

    var next = SpecDirectory.nextReady(specs, null);

    assertEquals("alice-task", next.id());
  }

  @Test
  void nextReadyReturnsNullWhenNoMatchingAssignee() {
    var specs =
        List.of(
            new Spec(
                "alice-task", "test", "Alice's", SpecStatus.PENDING, "alice", List.of(), null));

    assertNull(SpecDirectory.nextReady(specs, "bob"));
  }

  @Test
  void nextReadyMultipleDependenciesAllMet() {
    var specs =
        List.of(
            new Spec("a", "test", "A", SpecStatus.DONE, null, List.of(), null),
            new Spec("b", "test", "B", SpecStatus.DONE, null, List.of(), null),
            new Spec("c", "test", "C", SpecStatus.PENDING, null, List.of("a", "b"), null));

    var next = SpecDirectory.nextReady(specs);

    assertEquals("c", next.id());
  }

  @Test
  void nextReadyMultipleDependenciesPartiallyMet() {
    var specs =
        List.of(
            new Spec("a", "test", "A", SpecStatus.DONE, null, List.of(), null),
            new Spec("b", "test", "B", SpecStatus.IN_PROGRESS, null, List.of(), null),
            new Spec("c", "test", "C", SpecStatus.PENDING, null, List.of("a", "b"), null));

    assertNull(SpecDirectory.nextReady(specs));
  }

  @Test
  void statusCountsAllStatuses() {
    var specs =
        List.of(
            new Spec("a", "test", "A", SpecStatus.DONE, null, List.of(), null),
            new Spec("b", "test", "B", SpecStatus.DONE, null, List.of(), null),
            new Spec("c", "test", "C", SpecStatus.IN_PROGRESS, null, List.of(), null),
            new Spec("d", "test", "D", SpecStatus.PENDING, null, List.of(), null),
            new Spec("e", "test", "E", SpecStatus.PENDING, null, List.of(), null),
            new Spec("f", "test", "F", SpecStatus.REVIEW, null, List.of(), null),
            new Spec("g", "test", "G", SpecStatus.AWAITING_MERGE, null, List.of(), null));

    var counts = SpecDirectory.statusCounts(specs);

    assertEquals(2, counts.get("done"));
    assertEquals(1, counts.get("in_progress"));
    assertEquals(2, counts.get("pending"));
    assertEquals(1, counts.get("review"));
    assertEquals(1, counts.get("awaiting_merge"));
  }

  @Test
  void statusCountsEmpty() {
    var counts = SpecDirectory.statusCounts(List.of());

    assertEquals(0, counts.get("done"));
    assertEquals(0, counts.get("in_progress"));
    assertEquals(0, counts.get("pending"));
    assertEquals(0, counts.get("review"));
    assertEquals(0, counts.get("awaiting_merge"));
  }

  @Test
  void awaitingMergeDependencyKeepsDependentBlocked() {
    var specs =
        List.of(
            new Spec("base", "test", "Base", SpecStatus.AWAITING_MERGE, null, List.of(), null),
            new Spec("child", "test", "Child", SpecStatus.PENDING, null, List.of("base"), null));

    assertNull(SpecDirectory.nextReady(specs));
    assertTrue(SpecDirectory.isBlocked(specs, specs.get(1)));
    assertEquals(List.of("base"), SpecDirectory.unmetDependencies(specs, specs.get(1)));
  }

  @Test
  void awaitingMergeIsCliSettable() {
    var specs = List.of(new Spec("auth", "test", "Auth", SpecStatus.REVIEW, null, List.of(), null));

    var updated = SpecDirectory.updateStatus(specs, "auth", SpecStatus.AWAITING_MERGE);

    assertEquals(SpecStatus.AWAITING_MERGE, updated.getFirst().status());
  }

  @Test
  void unknownStatusIsRejectedAsCorruption() {
    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Spec.fromMap(
                    Map.<String, Object>of(
                        "id", "x", "project", "test", "title", "X", "status", "blocked")));

    assertEquals(
        "Invalid spec status: 'blocked'. Must be one of: draft, pending, in_progress, review,"
            + " awaiting_merge, done, cancelled, archived",
        refusal.getMessage());
  }

  @Test
  void retiredArchiveAliasIsRejectedAsCorruption() {
    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Spec.fromMap(
                    Map.<String, Object>of(
                        "id", "x", "project", "test", "title", "X", "status", "archive")));

    assertEquals(
        "Invalid spec status: 'archive'. Must be one of: draft, pending, in_progress, review,"
            + " awaiting_merge, done, cancelled, archived",
        refusal.getMessage());
  }

  @Test
  void nextReadyCombinesAssigneeAndDependencyFiltering() {
    var specs =
        List.of(
            new Spec("setup", "test", "Setup", SpecStatus.DONE, null, List.of(), null),
            new Spec(
                "alice-dep",
                "test",
                "Alice dep",
                SpecStatus.PENDING,
                "alice",
                List.of("setup"),
                null),
            new Spec("bob-nodep", "test", "Bob nodep", SpecStatus.PENDING, "bob", List.of(), null));

    assertEquals("alice-dep", SpecDirectory.nextReady(specs, "alice").id());
    assertEquals("bob-nodep", SpecDirectory.nextReady(specs, "bob").id());
  }

  @Test
  void findByIdReturnsMatchingSpec() {
    var specs =
        List.of(new Spec("oauth-flow", "test", "OAuth", SpecStatus.PENDING, null, List.of(), null));

    var spec = SpecDirectory.findById(specs, "oauth-flow");

    assertNotNull(spec);
    assertEquals("OAuth", spec.title());
  }

  @Test
  void updateStatusReplacesOnlyMatchingSpec() {
    var specs =
        List.of(
            new Spec("auth", "test", "Auth", SpecStatus.PENDING, null, List.of(), null),
            new Spec("search", "test", "Search", SpecStatus.PENDING, null, List.of(), null));

    var updated = SpecDirectory.updateStatus(specs, "search", SpecStatus.REVIEW);

    assertEquals(SpecStatus.PENDING, updated.getFirst().status());
    assertEquals(SpecStatus.REVIEW, updated.get(1).status());
  }

  @Test
  void updateStatusRejectsNonSettableStatus() {
    var specs =
        List.of(new Spec("auth", "test", "Auth", SpecStatus.PENDING, null, List.of(), null));

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SpecDirectory.updateStatus(specs, "auth", SpecStatus.ARCHIVED));

    assertTrue(error.getMessage().contains("Invalid spec status"));
  }

  @Test
  void isReadyReturnsTrueForPendingSpecWithSatisfiedDependencies() {
    var specs =
        List.of(
            new Spec("setup", "test", "Setup", SpecStatus.DONE, null, List.of(), null),
            new Spec("oauth", "test", "OAuth", SpecStatus.PENDING, null, List.of("setup"), null));

    assertTrue(SpecDirectory.isReady(specs, specs.get(1)));
    assertFalse(SpecDirectory.isBlocked(specs, specs.get(1)));
  }

  @Test
  void isBlockedReturnsTrueForPendingSpecWithUnmetDependencies() {
    var specs =
        List.of(
            new Spec("setup", "test", "Setup", SpecStatus.IN_PROGRESS, null, List.of(), null),
            new Spec("oauth", "test", "OAuth", SpecStatus.PENDING, null, List.of("setup"), null));

    assertTrue(SpecDirectory.isBlocked(specs, specs.get(1)));
    assertEquals(List.of("setup"), SpecDirectory.unmetDependencies(specs, specs.get(1)));
  }

  @Test
  void summarizeReportsReadyAndBlockedCounts() {
    var specs =
        List.of(
            new Spec("setup", "test", "Setup", SpecStatus.DONE, null, List.of(), null),
            new Spec("ready", "test", "Ready", SpecStatus.PENDING, null, List.of("setup"), null),
            new Spec(
                "blocked", "test", "Blocked", SpecStatus.PENDING, null, List.of("missing"), null),
            new Spec("review", "test", "Review", SpecStatus.REVIEW, null, List.of(), null));

    var summary = SpecDirectory.summarize(specs);

    assertEquals(1, summary.readyCount());
    assertEquals(1, summary.blockedCount());
    assertEquals("ready", summary.nextReadyId());
    assertEquals(2, summary.counts().get("pending"));
  }
}
