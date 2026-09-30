/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A node whose answer to an offer main took was lost learns, from main, exactly which of its states
 * main took from it, and reconciles three-way against that: a field only one side changed since
 * merges, a field both changed parks for the FDE, and nothing main did since is reverted unseen.
 */
class LostAnswerSyncTest {

  private SyncBox main;
  private SyncBox ada;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
    ada = new SyncBox("ada");
  }

  @AfterEach
  void tearDown() {
    ada.close();
    main.close();
  }

  @Test
  void aLostAnswerOnALaterPushNeverParksAConflictMainDidNotCause() throws IOException {
    var run = reserve(ada);
    SyncBox.quiesce(main, ada);
    Acting.system(() -> runs(ada).recordSession(run, "s1", "claude", "/t"));
    SyncBox.pushLosingTheAnswer(main, ada, "run");
    assertEquals("s1", runs(main).findById(run).orElseThrow().sessionId(), "main took s1");
    Acting.system(() -> runs(ada).recordSession(run, "s2", "claude", "/t"));

    SyncBox.quiesce(main, ada);

    assertTrue(ada.conflicts.pendingFor("run", run).isEmpty(), "main changed nothing since");
    assertEquals("s2", runs(main).findById(run).orElseThrow().sessionId());
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aSpecMainTookAndThenDeletedAfterALostAnswerStaysDeleted() throws IOException {
    Acting.as("ada", () -> ada.specs.create(spec("gone", null, null)));
    SyncBox.pushLosingTheAnswer(main, ada, "spec");
    assertTrue(main.specs.findById("gone").isPresent(), "main took it");
    Acting.as("ada", () -> main.specs.delete("gone"));

    SyncBox.quiesce(main, ada);

    assertTrue(main.specs.findById("gone").isEmpty(), "main's delete is never reverted unseen");
    assertTrue(ada.specs.findById("gone").isEmpty(), "the node takes it");
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aFieldEachSideSetAloneAfterALostAnswerMerges() throws IOException {
    Acting.as("ada", () -> ada.specs.create(spec("s", null, null)));
    SyncBox.pushLosingTheAnswer(main, ada, "spec");
    Acting.as("ada", () -> main.specs.update(spec("s", "codex", null)));
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/x")));

    SyncBox.quiesce(main, ada);

    assertTrue(ada.conflicts.pendingFor("spec", "s").isEmpty(), "disjoint fields merge");
    assertEquals("feat/x", main.specs.findById("s").orElseThrow().branch());
    assertEquals("codex", main.specs.findById("s").orElseThrow().agent());
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aFieldBothSidesChangedAfterALostAnswerParksForTheFde() throws IOException {
    Acting.as("ada", () -> ada.specs.create(spec("s", null, "feat/a")));
    SyncBox.pushLosingTheAnswer(main, ada, "spec");
    Acting.as("ada", () -> main.specs.update(spec("s", null, "feat/main")));
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/ada")));

    SyncBox.round(main, ada);

    assertEquals(List.of("branch"), ada.conflicts.pendingFor("spec", "s").orElseThrow().fields());
    assertEquals("feat/main", main.specs.findById("s").orElseThrow().branch());
    assertEquals("feat/ada", ada.specs.findById("s").orElseThrow().branch(), "nothing is lost");
  }

  private static RunStore runs(SyncBox box) {
    return new RunStore(box.db);
  }

  private static String reserve(SyncBox box) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs(box)
                .reserveDispatch(
                    id,
                    "acme",
                    null,
                    null,
                    box.handle(),
                    "adhoc",
                    List.of(),
                    "claude-code",
                    "b",
                    "t",
                    "/l",
                    "u",
                    null));
    return id;
  }

  private static SpecStore.SpecRow spec(String id, String agent, String branch) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Spec " + id,
        SpecStatus.PENDING,
        "ada",
        agent,
        null,
        null,
        branch,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }
}
