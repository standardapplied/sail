/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
class ConcurrentReconcileTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();
  private SyncBox node;
  private ScriptedMain main;
  private Map<String, Object> base;

  /** A main whose commit verdicts are scripted; an empty script auto-accepts. */
  private static final class ScriptedMain implements MainReplica {
    final Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
    final Map<String, String> revs = new LinkedHashMap<>();
    final Deque<CommitOutcome> script = new ArrayDeque<>();
    int minted = 1;

    @Override
    public String id() {
      return "main";
    }

    @Override
    public Set<String> entityIds() {
      return new LinkedHashSet<>(snapshots.keySet());
    }

    @Override
    public Map<String, Object> current(String id) {
      return snapshots.get(id);
    }

    @Override
    public String currentRev(String id) {
      return revs.get(id);
    }

    @Override
    public long maxSeq() {
      return minted;
    }

    @Override
    public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
      var outcome = script.isEmpty() ? new CommitOutcome.Accepted("m-" + minted++) : script.poll();
      if (outcome instanceof CommitOutcome.Accepted accepted) {
        snapshots.put(id, snapshot);
        revs.put(id, accepted.rev());
      }
      return outcome;
    }
  }

  @BeforeEach
  void setUp() {
    node = new SyncBox(tempDir, "node");
    main = new ScriptedMain();
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    engine.reconcile(node.replica, main);
    base = node.specs.comparableSnapshot("auth");
  }

  @AfterEach
  void tearDown() {
    node.close();
  }

  private Map<String, Object> baseWith(String field, Object value) {
    var snapshot = new LinkedHashMap<>(base);
    snapshot.put(field, value);
    return snapshot;
  }

  @Test
  void aDisjointConcurrentEditIsReMergedAndLands() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    main.script.add(new CommitOutcome.Rejected("m-2", baseWith("status", "in_progress")));

    var report = engine.reconcile(node.replica, main);

    assertEquals(1, report.merged());
    var merged = node.specs.findById("auth").orElseThrow();
    assertEquals("Title from node", merged.title());
    assertEquals("in_progress", merged.status().wire());
    assertTrue(node.conflicts.pending().isEmpty());
  }

  @Test
  void mainChurningPastEveryRetryParksAConflictAndKeepsLocalWork() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    for (var i = 0; i < 6; i++) {
      main.script.add(new CommitOutcome.Rejected("m-stale", base));
    }

    var report = engine.reconcile(node.replica, main);

    assertEquals(1, report.conflicts());
    assertEquals(List.of("<stale>"), node.conflicts.pending().getFirst().fields());
    assertEquals("Title from node", node.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void whenMainSettlesOnAClashingValuePastEveryRetryTheFieldsAreNamed() {
    node.specs.update(SyncBox.spec("auth", "Title from node", "pending"));
    main.script.add(new CommitOutcome.Rejected("m-2", base));
    main.script.add(new CommitOutcome.Rejected("m-3", base));
    main.script.add(new CommitOutcome.Rejected("m-4", base));
    main.script.add(new CommitOutcome.Rejected("m-5", baseWith("title", "Title from main")));

    var report = engine.reconcile(node.replica, main);

    assertEquals(1, report.conflicts());
    assertEquals(List.of("title"), node.conflicts.pending().getFirst().fields());
  }
}
