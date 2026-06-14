/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compare-and-set on main's commit, in isolation: a push lands only when the node's expected
 * rev still matches main's current rev, so a stale push is rejected with main's untouched state
 * instead of overwriting it.
 */
class CommitCasTest {

  @TempDir Path tempDir;
  private SyncBox main;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
  }

  @AfterEach
  void tearDown() {
    main.close();
  }

  private LinkedHashMap<String, Object> editedTitle(String title) {
    var snapshot = new LinkedHashMap<>(main.replica.current("auth"));
    snapshot.put("title", title);
    return snapshot;
  }

  @Test
  void acceptsAPushWhenTheExpectedRevMatches() {
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    var expected = main.replica.currentRev("auth");

    var outcome = main.replica.commit("auth", editedTitle("Edited"), expected);

    var accepted = assertInstanceOf(CommitOutcome.Accepted.class, outcome);
    assertNotEquals(expected, accepted.rev());
    assertEquals("Edited", main.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void rejectsAStalePushAndLeavesMainUntouched() {
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));

    var outcome = main.replica.commit("auth", editedTitle("Edited"), "1-stale");

    var rejected = assertInstanceOf(CommitOutcome.Rejected.class, outcome);
    assertEquals(main.replica.currentRev("auth"), rejected.currentRev());
    assertEquals("Auth", rejected.currentSnapshot().get("title"));
    assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void acceptsADeleteWhenTheExpectedRevMatches() {
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    var expected = main.replica.currentRev("auth");

    assertInstanceOf(CommitOutcome.Accepted.class, main.replica.commit("auth", null, expected));
    assertTrue(main.specs.findById("auth").isEmpty());
  }

  @Test
  void acceptsANewEntityWhenNoRevIsExpected() {
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    var snapshot = main.replica.current("auth");

    assertInstanceOf(CommitOutcome.Accepted.class, main.replica.commit("fresh", snapshot, null));
    assertTrue(main.specs.findById("fresh").isPresent());
  }

  @Test
  void rejectsANewEntityWhenAnotherWasExpected() {
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    var snapshot = main.replica.current("auth");

    assertInstanceOf(
        CommitOutcome.Rejected.class, main.replica.commit("fresh", snapshot, "1-bogus"));
    assertTrue(main.specs.findById("fresh").isEmpty());
  }

  @Test
  void deletingAnAbsentEntityIsANoOpAccept() {
    assertInstanceOf(CommitOutcome.Accepted.class, main.replica.commit("ghost", null, null));
    assertTrue(main.specs.findById("ghost").isEmpty());
  }
}
