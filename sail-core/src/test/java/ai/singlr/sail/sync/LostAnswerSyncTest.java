/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node whose answer to an offer main took was lost learns, from main, exactly which of its states
 * main took from it, and reconciles three-way against that: a field only one side changed since
 * merges, a field both changed parks for the FDE, and nothing main did since is reverted unseen.
 */
class LostAnswerSyncTest {

  @TempDir Path dir;
  private SyncBox main;
  private SyncBox ada;

  @BeforeEach
  void setUp() {
    main = new SyncBox(dir, "main");
    ada = new SyncBox(dir, "ada");
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

  @Test
  void anEditAfterMainMadeTheRowAgainReachesMainWithNoConflict() {
    madeAgainOnMain();
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/c")));

    SyncBox.quiesce(main, ada);

    assertTrue(ada.conflicts.pendingFor("spec", "s").isEmpty(), "main changed nothing since");
    assertEquals("feat/c", main.specs.findById("s").orElseThrow().branch());
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aFieldSetBackAfterMainMadeTheRowAgainIsNeverReverted() {
    madeAgainOnMain();
    Acting.as("ada", () -> ada.specs.update(spec("s", "codex", "feat/c")));

    SyncBox.quiesce(main, ada);

    var held = main.specs.findById("s").orElseThrow();
    assertEquals("codex", held.agent());
    assertEquals("feat/c", held.branch(), "an older version ada once offered is not the base");
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void anEditAfterMainMadeWhatAdaDeletedAgainParksNoConflict() {
    Acting.as("ada", () -> ada.specs.create(spec("s", null, "feat/a")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/b")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> ada.specs.delete("s"));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> main.specs.create(spec("s", null, "feat/a")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/d")));

    SyncBox.quiesce(main, ada);

    assertTrue(ada.conflicts.pendingFor("spec", "s").isEmpty(), "main changed nothing since");
    assertEquals("feat/d", main.specs.findById("s").orElseThrow().branch());
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aChangeMadeAfterALostAnswerIsRecordedAsItsWritersOnEveryBox() throws IOException {
    put("v1");
    SyncBox.pushLosingTheAnswer(main, ada, "file");
    put("v2");

    SyncBox.quiesce(main, ada);

    var id = FileStore.idOf("acme", "a.txt");
    assertEquals("ada", new ChangeLog(main.db).head("file", id).orElseThrow().actor());
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void anEditToAFileMainMadeAgainWithTheSameContentReachesMain() {
    put("v1");
    SyncBox.quiesce(main, ada);
    put("v2");
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> new FileStore(main.db).delete("acme", "a.txt"));
    putOn(main, "v1");
    SyncBox.quiesce(main, ada);
    put("v2");

    SyncBox.quiesce(main, ada);

    assertEquals(hashOn(ada), hashOn(main), "the rev v1 had before is not the one ada heard");
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aFileMadeAgainAfterMainDeletedItReachesMainWithNoConflict() {
    put("v1");
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> new FileStore(main.db).delete("acme", "a.txt"));
    SyncBox.quiesce(main, ada);
    put("v3");

    SyncBox.quiesce(main, ada);

    var id = FileStore.idOf("acme", "a.txt");
    assertTrue(ada.conflicts.pendingFor("file", id).isEmpty(), "ada heard main's delete");
    assertEquals(hashOn(ada), hashOn(main));
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aLostAnswerAfterAResolveKeepingMineNeverRevertsMainsLaterChange() throws IOException {
    Acting.as("ada", () -> ada.specs.create(spec("s", null, "feat/a")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> main.specs.update(spec("s", null, "feat/main")));
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/ada")));
    SyncBox.round(main, ada);
    var conflict = ada.conflicts.pendingFor("spec", "s").orElseThrow();
    var rev =
        Acting.as(
            "ada",
            () ->
                ada.specs.resolveConflict(
                    "s",
                    YamlUtil.parseMap(conflict.localSnapshot()),
                    YamlUtil.parseMap(conflict.remoteSnapshot())));
    ada.conflicts.resolve(conflict.id(), rev);
    SyncBox.pushLosingTheAnswer(main, ada, "spec");
    assertEquals("feat/ada", main.specs.findById("s").orElseThrow().branch(), "main took ada's");
    Acting.as("ada", () -> main.specs.update(spec("s", null, "feat/main")));

    SyncBox.quiesce(main, ada);

    assertEquals("feat/main", main.specs.findById("s").orElseThrow().branch());
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aLostAnswerThenMainMakingTheRowAgainAsItWasNeverRevertsIt() throws IOException {
    Acting.as("ada", () -> ada.specs.create(spec("s", null, "feat/a")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/b")));
    SyncBox.pushLosingTheAnswer(main, ada, "spec");
    Acting.as("ada", () -> main.specs.delete("s"));
    Acting.as("ada", () -> main.specs.create(spec("s", null, "feat/a")));

    SyncBox.quiesce(main, ada);

    assertEquals("feat/a", main.specs.findById("s").orElseThrow().branch(), "main made it again");
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aLostAnswerThenMainPuttingTheFileBackAsItWasNeverRevertsIt() throws IOException {
    put("v1");
    SyncBox.quiesce(main, ada);
    put("v2");
    SyncBox.pushLosingTheAnswer(main, ada, "file");
    Acting.as("ada", () -> new FileStore(main.db).delete("acme", "a.txt"));
    putOn(main, "v1");
    var putBack = hashOn(main);

    SyncBox.quiesce(main, ada);

    assertEquals(putBack, hashOn(main), "a rev never repeats, so main's put-back stands");
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aLostAnswerThenMainCompactingPastWhatTheNodeHeardNeverRevertsMain() throws IOException {
    Acting.as("ada", () -> ada.specs.create(spec("s", null, "feat/a")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/b")));
    SyncBox.pushLosingTheAnswer(main, ada, "spec");
    for (var i = 0; i < ChangeLog.HISTORY_REVISIONS - 2; i++) {
      var agent = "a" + i;
      Acting.as("ada", () -> main.specs.update(spec("s", agent, "feat/b")));
    }
    Acting.as("ada", () -> main.specs.update(spec("s", "last", "feat/a")));
    new ChangeLog(main.db).compact("spec", List.of("s"), main.specs::baseRevOf);

    SyncBox.quiesce(main, ada);

    assertEquals("feat/a", main.specs.findById("s").orElseThrow().branch(), "main's set-back");
    SyncBox.assertEqualToMain(main, ada);
  }

  private void madeAgainOnMain() {
    Acting.as("ada", () -> ada.specs.create(spec("s", null, "feat/a")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/b")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> ada.specs.update(spec("s", null, "feat/c")));
    SyncBox.quiesce(main, ada);
    Acting.as("ada", () -> main.specs.delete("s"));
    Acting.as("ada", () -> main.specs.create(spec("s", null, "feat/a")));
    SyncBox.quiesce(main, ada);
  }

  private void put(String text) {
    putOn(ada, text);
  }

  private static void putOn(SyncBox box, String text) {
    Acting.as(
        "ada",
        () ->
            new FileStore(box.db)
                .put(
                    "acme",
                    "a.txt",
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                    0644));
  }

  private static String hashOn(SyncBox box) {
    return new FileStore(box.db).find("acme", "a.txt").orElseThrow().contentHash();
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
