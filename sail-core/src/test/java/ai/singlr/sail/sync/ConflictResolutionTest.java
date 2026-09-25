/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The end-to-end property of {@link SpecStore#resolveConflict}: after a conflict is parked, the
 * user's choice (mine / theirs / merge) is applied, and a follow-up sync converges main and node on
 * that choice <em>without re-raising the conflict</em> — because resolving rebases the row onto
 * main's content, so detection can never see the same divergence twice. Covers field-level and
 * delete-vs-edit conflicts in both directions.
 */
@ActingAs
class ConflictResolutionTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private SyncBox main;
  private SyncBox node;
  private SyncBox other;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    node = new SyncBox(tempDir, "node");
    other = new SyncBox(tempDir, "other");
  }

  @AfterEach
  void tearDown() {
    other.close();
    node.close();
    main.close();
  }

  private void sync(SyncBox box) {
    SyncBox.round(main.db, box.db, "spec");
  }

  private SyncConflicts.Conflict raiseTitleConflict() {
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    sync(node);
    sync(other);

    other.specs.update(SyncBox.spec("auth", "Title from other", "pending"));
    sync(other);

    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    sync(node);

    return node.conflicts.pendingFor("spec", "auth").orElseThrow();
  }

  private Map<String, Object> snapshot(String json) {
    return json == null || json.isBlank() ? null : YamlUtil.parseMap(json);
  }

  @Test
  void keepMinePushesTheLocalValueAndConvergesWithoutReConflict() {
    var conflict = raiseTitleConflict();

    var mine = snapshot(conflict.localSnapshot());
    var theirs = snapshot(conflict.remoteSnapshot());
    var rev = node.specs.resolveConflict("auth", mine, theirs);
    node.conflicts.resolve(conflict.id(), rev);

    sync(node);
    sync(other);

    assertEquals("Title from node", main.specs.findById("auth").orElseThrow().title());
    assertEquals("Title from node", other.specs.findById("auth").orElseThrow().title());
    assertEquals("Title from node", node.specs.findById("auth").orElseThrow().title());
    assertTrue(node.conflicts.pending().isEmpty());
  }

  @Test
  void takeTheirsAdoptsMainAndRetainsMineInHistory() {
    var conflict = raiseTitleConflict();

    var theirs = snapshot(conflict.remoteSnapshot());
    var rev = node.specs.resolveConflict("auth", theirs, theirs);
    node.conflicts.resolve(conflict.id(), rev);

    assertEquals("Title from other", node.specs.findById("auth").orElseThrow().title());

    var second = SyncBox.round(main.db, node.db, "spec");
    assertEquals(0, second.conflicts());
    assertEquals("Title from other", main.specs.findById("auth").orElseThrow().title());
    assertTrue(
        node.specs.history("auth").stream().anyMatch(e -> e.snapshot().contains("Title from node")),
        "the local version is retained in the change log");
  }

  @Test
  void takeTheirsKeepsMainsAuthorOnTheResolvingNode() {
    var theirs = snapshot(raiseTitleConflictBetween("mady", "rajesh").remoteSnapshot());

    Acting.as("rajesh", () -> node.specs.resolveConflict("auth", theirs, theirs));

    assertEquals("mady", main.specs.findById("auth").orElseThrow().updatedBy());
    assertEquals(
        "mady",
        node.specs.findById("auth").orElseThrow().updatedBy(),
        "main's title, adopted, is still mady's");
    var head = node.specs.history("auth").getLast();
    assertEquals("mady", head.actor());
    assertEquals(Actor.MAIN_HANDLE, head.peer());
  }

  @Test
  void keepMineCreditsTheResolverOnlyWithTheEditItPushes() {
    var conflict = raiseTitleConflictBetween("mady", "rajesh");
    var mine = snapshot(conflict.localSnapshot());
    var theirs = snapshot(conflict.remoteSnapshot());

    Acting.as("rajesh", () -> node.specs.resolveConflict("auth", mine, theirs));

    var history = node.specs.history("auth");
    assertEquals("mady", history.get(history.size() - 2).actor(), "the adopted base is main's");
    assertEquals("rajesh", history.getLast().actor());
    assertEquals("rajesh", node.specs.findById("auth").orElseThrow().updatedBy());
  }

  private SyncConflicts.Conflict raiseTitleConflictBetween(String onOther, String onNode) {
    Acting.as(onNode, () -> node.specs.create(SyncBox.spec("auth", "Auth", "pending")));
    sync(node);
    sync(other);
    Acting.as(
        onOther, () -> other.specs.update(SyncBox.spec("auth", "Title from other", "pending")));
    sync(other);
    Acting.as(onNode, () -> node.specs.update(SyncBox.spec("auth", "Title from node", "pending")));
    sync(node);
    return node.conflicts.pendingFor("spec", "auth").orElseThrow();
  }

  @Test
  void mergePushesAThirdValueAndConverges() {
    var conflict = raiseTitleConflict();

    var theirs = snapshot(conflict.remoteSnapshot());
    var merged = new LinkedHashMap<>(snapshot(conflict.localSnapshot()));
    merged.put("title", "Merged title");
    var rev = node.specs.resolveConflict("auth", merged, theirs);
    node.conflicts.resolve(conflict.id(), rev);

    sync(node);
    sync(other);

    assertEquals("Merged title", main.specs.findById("auth").orElseThrow().title());
    assertEquals("Merged title", other.specs.findById("auth").orElseThrow().title());
    assertTrue(node.conflicts.pending().isEmpty());
  }

  @Test
  void resolvingDeleteVersusEditByTakingTheRemoteEditReCreatesLocally() {
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    sync(node);
    sync(other);

    node.specs.delete("auth");
    other.specs.update(SyncBox.spec("auth", "Edited by other", "pending"));
    sync(other);
    sync(node);

    var conflict = node.conflicts.pendingFor("spec", "auth").orElseThrow();
    assertEquals(List.of("<deleted>"), conflict.fields());

    var theirs = snapshot(conflict.remoteSnapshot());
    var rev = node.specs.resolveConflict("auth", theirs, theirs);
    node.conflicts.resolve(conflict.id(), rev);

    assertEquals("Edited by other", node.specs.findById("auth").orElseThrow().title());
    var second = SyncBox.round(main.db, node.db, "spec");
    assertEquals(0, second.conflicts());
  }

  @Test
  void resolvingDeleteVersusEditByKeepingTheLocalDeleteRemovesItEverywhere() {
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    sync(node);
    sync(other);

    node.specs.delete("auth");
    other.specs.update(SyncBox.spec("auth", "Edited by other", "pending"));
    sync(other);
    sync(node);

    var conflict = node.conflicts.pendingFor("spec", "auth").orElseThrow();
    var theirs = snapshot(conflict.remoteSnapshot());
    var rev = node.specs.resolveConflict("auth", null, theirs);
    node.conflicts.resolve(conflict.id(), rev);

    assertTrue(node.specs.findById("auth").isEmpty());
    sync(node);
    sync(other);
    assertTrue(main.specs.findById("auth").isEmpty());
    assertTrue(other.specs.findById("auth").isEmpty());
  }

  @Test
  void resolvingEditVersusRemoteDeleteByKeepingMineReCreatesOnMain() {
    other.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    sync(other);
    sync(node);

    other.specs.delete("auth");
    node.specs.update(SyncBox.spec("auth", "Kept by node", "pending"));
    sync(other);
    sync(node);

    var conflict = node.conflicts.pendingFor("spec", "auth").orElseThrow();
    assertEquals(List.of("<deleted>"), conflict.fields());

    var mine = snapshot(conflict.localSnapshot());
    var rev = node.specs.resolveConflict("auth", mine, null);
    node.conflicts.resolve(conflict.id(), rev);

    assertEquals("Kept by node", node.specs.findById("auth").orElseThrow().title());
    sync(node);
    sync(other);
    assertEquals("Kept by node", main.specs.findById("auth").orElseThrow().title());
    assertEquals("Kept by node", other.specs.findById("auth").orElseThrow().title());
  }
}
