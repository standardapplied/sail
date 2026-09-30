/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Main over a real box, each of whose commits may first lose the race to a scripted concurrent
 * write that moves main to the next scripted snapshot; an empty script races nothing. {@code
 * onFirstCommit} runs once, as the first commit arrives, for a write racing it elsewhere.
 */
final class ScriptedMain implements MainReplica {
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
