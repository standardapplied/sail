/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.BlobStore;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's reaction when main rejects a stale push, driven by a scripted main so the rare
 * concurrent paths are deterministic: a disjoint concurrent edit re-merges and lands, and main
 * churning past every retry parks the entity as a conflict rather than looping or losing work.
 */
@ActingAs
class ConcurrentReconcileTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();
  private SyncBox node;
  private SyncBox box;
  private ScriptedMain main;
  private Map<String, Object> base;

  /**
   * Main over a real box, each of whose commits may first lose the race to a scripted concurrent
   * write that moves main to the next scripted snapshot; an empty script races nothing.
   */
  private static final class ScriptedMain implements MainReplica {
    final Deque<Map<String, Object>> script = new ArrayDeque<>();
    private final StoreReplica replica;
    Runnable onFirstCommit;

    ScriptedMain(StoreReplica replica) {
      this.replica = replica;
    }

    @Override
    public String id() {
      return replica.id();
    }

    @Override
    public Set<String> entityIds() {
      return replica.entityIds();
    }

    @Override
    public Map<String, Object> current(String id) {
      return replica.current(id);
    }

    @Override
    public String currentRev(String id) {
      return replica.currentRev(id);
    }

    @Override
    public State state(String id) {
      return replica.state(id);
    }

    @Override
    public long maxSeq() {
      return replica.maxSeq();
    }

    @Override
    public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
      if (onFirstCommit != null) {
        var hook = onFirstCommit;
        onFirstCommit = null;
        hook.run();
      }
      if (!script.isEmpty()) {
        replica.commit(id, script.poll(), replica.currentRev(id));
      }
      return replica.commit(id, snapshot, expectedRev);
    }
  }

  @BeforeEach
  void setUp() {
    node = new SyncBox(tempDir, "node");
    box = new SyncBox(tempDir, "main");
    new BlobStore(box.db).putText("");
    main = new ScriptedMain(box.replica);
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    engine.reconcile(node.replica, main);
    base = node.specs.comparableSnapshot("auth");
  }

  @AfterEach
  void tearDown() {
    box.close();
    node.close();
  }

  private void settleKeepingMine() {
    var conflict = node.conflicts.pendingFor("spec", "auth").orElseThrow();
    var mine = YamlUtil.parseMap(conflict.localSnapshot());
    node.conflicts.resolve(
        conflict.id(), node.specs.resolveConflict("auth", mine, conflict.theirs()));
  }

  private void assertConverged() {
    SyncBox.quiesce(box, node);
    SyncBox.assertEqualToMain(box, node);
  }

  private Map<String, Object> baseWith(String field, Object value) {
    var snapshot = new LinkedHashMap<>(base);
    snapshot.put(field, value);
    return snapshot;
  }

  @Test
  void aDisjointConcurrentEditIsReMergedAndLands() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    main.script.add(baseWith("status", "in_progress"));

    var report = engine.reconcile(node.replica, main);

    assertEquals(1, report.merged());
    var merged = node.specs.findById("auth").orElseThrow();
    assertEquals("Title from node", merged.title());
    assertEquals("in_progress", merged.status().wire());
    assertTrue(node.conflicts.pending().isEmpty());

    assertConverged();
  }

  @Test
  void mainChurningPastEveryRetryParksAConflictAndKeepsLocalWork() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    for (var i = 0; i < 6; i++) {
      main.script.add(base);
    }

    var report = engine.reconcile(node.replica, main);

    assertEquals(1, report.conflicts());
    assertEquals(List.of("<stale>"), node.conflicts.pending().getFirst().fields());
    assertEquals("Title from node", node.specs.findById("auth").orElseThrow().title());

    assertConverged();
  }

  @Test
  void aDisjointLocalWriteDuringTheCommitRoundIsReOfferedAndLands() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    main.onFirstCommit =
        () -> node.specs.update(SyncBox.spec("auth", "Title from node", "in_progress"));

    engine.reconcile(node.replica, main);

    assertEquals(
        "in_progress",
        node.specs.findById("auth").orElseThrow().status().wire(),
        "a write landing while the push was in flight must not be overwritten by the"
            + " accepted-but-stale snapshot");
    assertEquals("in_progress", main.current("auth").get("status"));
    assertTrue(node.conflicts.pending().isEmpty());

    assertConverged();
  }

  @Test
  void anOverlappingLocalWriteDuringTheCommitRoundIsOfferedOverTheAcceptedOneWithoutAConflict() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    main.onFirstCommit =
        () -> node.specs.update(SyncBox.spec("auth", "Title during round", "pending"));

    var report = engine.reconcile(node.replica, main);

    assertEquals(0, report.conflicts(), "the box's own accepted title is its base, not main's");
    assertEquals("Title during round", node.specs.findById("auth").orElseThrow().title());
    assertEquals("Title during round", main.current("auth").get("title"));

    assertConverged();
  }

  @Test
  void whenMainSettlesOnAClashingValuePastEveryRetryTheFieldsAreNamed() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    main.script.add(base);
    main.script.add(base);
    main.script.add(base);
    main.script.add(baseWith("title", "Title from main"));

    var report = engine.reconcile(node.replica, main);

    assertEquals(1, report.conflicts());
    assertEquals(List.of("title"), node.conflicts.pending().getFirst().fields());

    settleKeepingMine();
    assertConverged();
  }
}
